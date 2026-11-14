package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbSponsorClosures;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ManualCredits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.quizbuks.it.SyntheticRows.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §5.2, §5.3, §5.6, §10 MoneyCloseIT): the wind-up of an erased sponsor's
 * platform fee account. Paying the invoices is F-M13's automatic payment (#696): until it is merged, a run with fees
 * owed and money to pay them waits ({@code TO_PAY}), which this class asserts as the order the plan wants (H2: the
 * granted credit is voided only after the last payment).
 */
class MoneyCloseIT extends CreditNoteItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRoleAndTheSwitch() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
    }

    @Test
    void theSwitchOffOnlyRecordsAndTheNextRunAfterItIsOnWindsUp() {
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        String sponsor = newSponsor(unique("s-fm15b-switch"));
        String lot = grant(sponsor, 1000, clock.instant().plus(Duration.ofDays(60)));
        erase(sponsor);

        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.SWITCHED_OFF);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.WAITING)
            .containsEntry("reason_code", QbSponsorClosures.SWITCH_OFF);
        assertThat(lotOf(lot)).containsEntry("voided_at", null);
        assertThat(remainingOf(lot)).isEqualByComparingTo("1000");

        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
        MoneyClose.CloseOutput done = close(sponsor);
        assertThat(done.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(done.lotsVoided()).isEqualTo(1);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", null);
        assertThat(closure(sponsor).get("closed_at")).isNotNull();
        // Voided: nothing left, the wallet's cache down, 5140 credited back with the lot as the source.
        assertThat(remainingOf(lot)).isZero();
        assertThat(lotOf(lot).get("voided_at")).isNotNull();
        assertThat(new BigDecimal(String.valueOf(lotOf(lot).get("voided_amount")))).isEqualByComparingTo("1000");
        assertThat(balance(sponsor, "FEE_CREDIT")).isZero();
        assertThat(lines("QbFeeCreditGrant", lot)).hasSize(4);

        // A replay does nothing more.
        MoneyClose.CloseOutput again = close(sponsor);
        assertThat(again.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(again.lotsVoided()).isZero();
        assertThat(lines("QbFeeCreditGrant", lot)).hasSize(4);
    }

    @Test
    void aPrepaymentStillHeldStopsTheRunAndTheGrantedCreditGivenBackToItsLotIsVoidedLast() {
        // Review M3: a real prepayment frozen from a lot, given back by the real release; voided only after that.
        autoApprove(true);
        String sponsor = pricedSponsor("fm15b-held");
        String lot = grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM15b held");
        submit(sponsorToken(sponsor), publicationId);
        assertThat(prepayments(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "OPEN"));
        BigDecimal frozen = new BigDecimal("1000").subtract(remainingOf(lot));
        assertThat(frozen).isPositive();
        erase(sponsor);

        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.WAITING)
            .containsEntry("reason_code", QbSponsorClosures.DEPOSIT_OPEN);
        assertThat(lotOf(lot)).containsEntry("voided_at", null);

        // Ended (the erasure ends the Sponsor's publications, or an administrator does) and given back by the job.
        if (!"ENDED".equals(status(publicationId))) {
            run("QB_PUBLICATION_ADMIN_END", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-content",
                com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE), Map.of("publicationId", publicationId,
                "reason", "F-M15 b test"));
        }
        systemRun(com.jabiz.quizbuks.fee.Prepayments.RELEASE_ITEM, Map.of("publicationId", publicationId,
            "sponsorId", sponsor, "purpose", "AUDIENCE"));
        assertThat(prepayments(publicationId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "RELEASED"));
        assertThat(remainingOf(lot)).isEqualByComparingTo("1000");

        MoneyClose.CloseOutput done = close(sponsor);
        assertThat(done.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(remainingOf(lot)).isZero();
        assertThat(new BigDecimal(String.valueOf(lotOf(lot).get("voided_amount")))).isEqualByComparingTo("1000");
        // The books: balanced, the ledger only inserted into, the fee credit and its promotion agree.
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
        // FEE_CREDIT_PROMOTION for this lot: 5140 debited when granted, credited back as much when voided.
        assertThat(promotion(lot)).isEqualByComparingTo("0");
    }

    @Test
    void aWindUpDoneWithFeesOwedKeepsItsReasonWhenALaterRunStopsAndIsTakenAgain() {
        // Review M1: done with fees owed, cash arrives later; a run the switch stops keeps FEE_OWED and the daily job
        // still takes it.
        String sponsor = newSponsor(unique("s-fm15b-owed-again"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        erase(sponsor);
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(closure(sponsor)).containsEntry("reason_code", QbSponsorClosures.FEE_OWED);

        // Cash that arrives after it (a top-up confirmed late): its lot and the wallet's cache, as the crediting writes.
        SyntheticRows.rows("qb_wallet", null, 1, null, n -> Map.of("owner_id", text(sponsor), "kind", "'CASH'",
            "balance", "200", "held", "0", "pending_count", "0", "pending_amount", "0", "pending_out", "0"));
        SyntheticRows.rows("qb_cash_lot", null, 1, null, n -> Map.of("sponsor_id", text(sponsor), "source", "'CARD'",
            "amount", "200", "remaining", "200"));
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.SWITCHED_OFF);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
        assertThat(due()).contains(sponsor);
        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
        // Until F-M13's payment: something to pay with, so it waits to pay, still FEE_OWED and still taken.
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.TO_PAY);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
        assertThat(due()).contains(sponsor);
    }

    @Test
    void withoutAWindUpNothingIsWithdrawn() {
        // Review L4: the process run for a sponsor never registered (not erased) withdraws nothing.
        String sponsor = newSponsor(unique("s-fm15b-none"));
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), Map.of("kind", QbFees.REQUEST_MANUAL,
            "sponsorId", sponsor, "amount", 500, "reason", "Custom report"));
        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.outcome()).isEqualTo(MoneyClose.NONE);
        assertThat(run.withdrawn()).isZero();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?",
            String.valueOf(request.get("requestId")))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", QbFees.REVIEW));
    }

    @Test
    void thePostpaidFeesNotInvoicedAreInvoicedTheCurrentMonthToo() {
        String sponsor = newSponsor(unique("s-fm15b-postpaid"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        // A lot with nothing left is marked too: what a prepayment gives back to it later is voided on arrival.
        SyntheticRows.rows("qb_fee_credit_grant", null, 1, null, n -> Map.of("sponsor_id", text(sponsor),
            "source", "'MANUAL'", "amount", "800", "remaining", "0", "voided_amount", "0",
            "granted_at", "TIMESTAMPTZ '2026-01-01T00:00:00Z'", "expires_at", "TIMESTAMPTZ '2099-01-01T00:00:00Z'"));
        erase(sponsor);
        assertThat(query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)).isEmpty();

        MoneyClose.CloseOutput done = close(sponsor);
        assertThat(done.invoicesIssued()).isEqualTo(1);
        // Nothing to pay them with: done, with the fees owed (it does not hold the deletion).
        assertThat(done.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(done.owed()).isPositive();
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst();
        assertThat(invoice).containsEntry("period", currentPeriod()).containsEntry("status", QbFees.OPEN);
        assertThat(query("SELECT voided_at, voided_amount FROM qb_fee_credit_grant WHERE sponsor_id = ?", sponsor))
            .singleElement().satisfies(row -> {
                assertThat(row.get("voided_at")).isNotNull();
                assertThat(new BigDecimal(String.valueOf(row.get("voided_amount")))).isZero();
            });
        // A second run invoices nothing more.
        assertThat(close(sponsor).invoicesIssued()).isZero();
        assertThat(query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)).hasSize(1);
    }

    @Test
    void feesOwedWithMoneyToPayThemWaitAndNothingIsVoidedBeforeThePayment() {
        String sponsor = newSponsor(unique("s-fm15b-topay"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        String lot = grant(sponsor, 1000, clock.instant().plus(Duration.ofDays(60)));
        erase(sponsor);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.invoicesIssued()).isEqualTo(1);
        assertThat(run.outcome()).isEqualTo(MoneyClose.TO_PAY);
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.WAITING);
        assertThat(lotOf(lot)).containsEntry("voided_at", null);
        assertThat(remainingOf(lot)).isEqualByComparingTo("1000");
    }

    @Test
    void whatIsUnderReviewIsWithdrawnWhateverTheSwitchAndFinanceIsToldOfTheManualCredit() {
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        // Review M2: nothing but a manual credit and a fee request under review (no wallet, grant, invoice, prepayment
        // or fee): registered all the same, and step 0 withdraws them.
        String sponsor = newSponsor(unique("s-fm15b-review"));
        Map<String, Object> credit = run(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind",
            "PAID_OUTSIDE", "amount", 3000, "reason", "Paid in cash", "receivedOn",
            LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")).toString(), "bankReference",
            "R-" + UUID.randomUUID().toString().substring(0, 8)));
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_MANUAL,
            "sponsorId", sponsor, "amount", 500, "reason", "Custom report"));
        String topUpId = String.valueOf(credit.get("topUpId"));
        String requestId = String.valueOf(request.get("requestId"));
        erase(sponsor);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.withdrawn()).isEqualTo(2);
        // Withdrawn after the erasure overwrote what was bound; the statement's entry let go (review L5).
        assertThat(query("SELECT status, ended_code, received_on, bank_reference FROM qb_topup WHERE topup_id = ?",
            topUpId)).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "CANCELLED")
                .containsEntry("ended_code", "ERASED").containsEntry("received_on", null)
                .containsEntry("bank_reference", null));
        assertThat(query("SELECT status, ended_code FROM qb_fee_request WHERE request_id = ?", requestId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbFees.REJECTED)
                .containsEntry("ended_code", "ERASED"));
        // Their approvals withdrawn; nothing posted for either.
        for (String approval : List.of(String.valueOf(credit.get("approvalRequestId")),
            String.valueOf(request.get("approvalRequestId")))) {
            assertThat(query("SELECT DISTINCT ON (request_id) status FROM sys_approval_request_version "
                + "WHERE request_id = CAST(? AS uuid) ORDER BY request_id, version_no DESC", approval))
                .singleElement().satisfies(row -> assertThat(row.get("status")).isEqualTo("WITHDRAWN"));
        }
        assertThat(lines("QbTopUp", topUpId)).isEmpty();
        assertThat(openTasks("erasure-withdrawn:" + topUpId)).hasSize(1);
        // A replay withdraws nothing more and tells finance once.
        assertThat(close(sponsor).withdrawn()).isZero();
        assertThat(openTasks("erasure-withdrawn:" + topUpId)).hasSize(1);
    }

    @Test
    void financeIsToldOnceWhenItTakesLongAndTheTaskClosesWhenItIsDone() {
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        String sponsor = newSponsor(unique("s-fm15b-slow"));
        grant(sponsor, 200, clock.instant().plus(Duration.ofDays(90)));
        erase(sponsor);
        close(sponsor);
        assertThat(openTasks("erasure-close:" + sponsor)).isEmpty();

        clock.set(clock.instant().plus(Duration.ofDays(3)));
        close(sponsor);
        close(sponsor);
        assertThat(openTasks("erasure-close:" + sponsor)).hasSize(1);
        assertThat(closure(sponsor).get("alerted_at")).isNotNull();

        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(openTasks("erasure-close:" + sponsor)).isEmpty();
    }

    @Test
    void theErasureRegistersAndWindsUpThroughItsEventsAndTheDailyJobTakesWhatWaits() {
        String sponsor = newSponsor(unique("s-fm15b-event"));
        String lot = grant(sponsor, 700, clock.instant().plus(Duration.ofDays(60)));
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        deliverEvents();
        deliverEvents();
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED);
        assertThat(lotOf(lot).get("voided_at")).isNotNull();

        // One waiting is the daily job's item; one done is not.
        String waiting = newSponsor(unique("s-fm15b-job"));
        grant(waiting, 100, clock.instant().plus(Duration.ofDays(60)));
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        erase(waiting);
        close(waiting);
        param(QbParams.ERASURE_CLOSE_ENABLED, "true");
        List<Map<String, Object>> due = items(MoneyClose.DUE_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens,
            "fm15b-job", "qb.fee.system"), Map.of("limit", 100));
        assertThat(due).extracting(row -> row.get("sponsorId")).contains(waiting).doesNotContain(sponsor);
    }

    @Test
    void theReconciliationFindsWhatAWindUpLeftWaitingOwedOrNotRegistered() {
        // Owed after a wind-up done: a note with what is owed.
        String owes = newSponsor(unique("s-fm15b-rc-owed"));
        billing(owes, "POSTPAID", 50_000);
        manualFee(owes, 300);
        spentLot(owes);
        erase(owes);
        assertThat(close(owes).outcome()).isEqualTo(MoneyClose.CLOSED);
        // Granted credit left after it (a lot that the wind-up should have voided): a difference.
        String left = newSponsor(unique("s-fm15b-rc-left"));
        String lot = grant(left, 400, clock.instant().plus(Duration.ofDays(60)));
        erase(left);
        assertThat(close(left).outcome()).isEqualTo(MoneyClose.CLOSED);
        execute("UPDATE qb_fee_credit_grant SET remaining = 150, voided_amount = 0, voided_at = NULL WHERE grant_id = ?", lot);
        // Waiting: the switch off.
        param(QbParams.ERASURE_CLOSE_ENABLED, "false");
        String waits = newSponsor(unique("s-fm15b-rc-wait"));
        grant(waits, 100, clock.instant().plus(Duration.ofDays(60)));
        erase(waits);
        close(waits);
        // Erased with the account, the registration's event never delivered.
        String lost = newSponsor(unique("s-fm15b-rc-lost"));
        grant(lost, 100, clock.instant().plus(Duration.ofDays(60)));
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", lost, "requestedBy", "it-support"));

        // Before the alert days: only what does not wait for them.
        Map<String, List<String>> early = erasedItems();
        assertThat(early.get(owes)).containsExactly(com.jabiz.quizbuks.wallet.ReconChecks.ERASED_FEE_OWED);
        assertThat(early.get(left)).contains(com.jabiz.quizbuks.wallet.ReconChecks.ERASED_GIFT_LEFT);
        assertThat(early).doesNotContainKeys(waits, lost);

        clock.set(clock.instant().plus(Duration.ofDays(3)));
        Map<String, List<String>> late = erasedItems();
        assertThat(late.get(waits)).containsExactly(com.jabiz.quizbuks.wallet.ReconChecks.ERASED_CLOSE_OPEN);
        assertThat(late.get(lost)).containsExactly(com.jabiz.quizbuks.wallet.ReconChecks.ERASED_NO_CLOSURE);

        // The daily sweep registers the one whose event was lost; the reconciliation no longer finds it.
        List<Map<String, Object>> unregistered = items(MoneyClose.UNREGISTERED_TEMPLATE,
            com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-job", "qb.fee.system"), Map.of("limit", 100));
        assertThat(unregistered).extracting(row -> row.get("userId")).contains(lost)
            .doesNotContain(owes, left, waits);
        // As the job's item: with the erasure's time, from which the alert days still count.
        Object erasedTime = unregistered.stream().filter(row -> lost.equals(row.get("userId"))).findFirst().orElseThrow()
            .get("erasedTime");
        assertThat(((MoneyClose.RegisterOutput) systemRun(MoneyClose.REGISTER, Map.of("userId", lost,
            "erasedAt", String.valueOf(erasedTime)))).registered()).isTrue();
        assertThat(closure(lost).get("erased_at")).isNotNull();
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        assertThat(erasedItems().get(lost)).containsExactly(com.jabiz.quizbuks.wallet.ReconChecks.ERASED_CLOSE_OPEN);
    }

    @Test
    void anErasedSponsorsInvoicesAreNotRemindedOf() {
        String sponsor = newSponsor(unique("s-fm15b-overdue"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        spentLot(sponsor);
        erase(sponsor);
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);
        String invoiceId = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)
            .getFirst().get("invoice_id"));
        clock.set(clock.instant().plus(Duration.ofDays(62)));

        // Not an item of the daily job, and the item itself (registered between the two) does nothing.
        LocalDate today = clock.instant().atZone(ZoneId.of("Asia/Tokyo")).toLocalDate();
        assertThat(items("qb.system.fee-overdue-due", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-job",
            "qb.fee.system"), Map.of("limit", 1000, "params", Map.of("today", today.toString(),
            "remindedBefore", clock.instant().toString())))).extracting(row -> row.get("invoiceId"))
            .doesNotContain(invoiceId);
        assertThat(((com.jabiz.quizbuks.fee.FeeProcesses.ItemOutput) systemRun(
            com.jabiz.quizbuks.fee.FeeProcesses.OVERDUE_ITEM, Map.of("invoiceId", invoiceId))).outcome())
            .isEqualTo("NONE");
        assertThat(query("SELECT status, reminders_sent FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId)
            .getFirst()).containsEntry("status", QbFees.OPEN);
        assertThat(openTasks(QbFees.overdueTaskSource(invoiceId))).isEmpty();
    }

    private List<Object> due() {
        return items(MoneyClose.DUE_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-job",
            "qb.fee.system"), Map.of("limit", 1000)).stream().map(row -> row.get("sponsorId")).toList();
    }

    /** 5140 posted with the lot as the source, debit less credit. */
    private static BigDecimal promotion(String lot) {
        return (BigDecimal) query("SELECT coalesce(sum(CASE e.direction WHEN 'DEBIT' THEN e.amount ELSE -e.amount END), 0)"
            + " AS net FROM ledger_transaction_version t JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id"
            + " JOIN ledger_account_version a ON a.account_id = e.account_id WHERE t.source_entity = 'QbFeeCreditGrant'"
            + " AND t.source_id = ? AND a.account_code = '5140'", lot).getFirst().get("net");
    }

    /** A granted lot used up: the sponsor had the account (fees not invoiced yet are not part of that, §4.1). */
    private static void spentLot(String sponsor) {
        SyntheticRows.rows("qb_fee_credit_grant", null, 1, null, n -> Map.of("sponsor_id", text(sponsor),
            "source", "'MANUAL'", "amount", "800", "remaining", "0", "voided_amount", "0",
            "granted_at", "TIMESTAMPTZ '2026-01-01T00:00:00Z'", "expires_at", "TIMESTAMPTZ '2099-01-01T00:00:00Z'"));
    }

    /** One reconciliation now: the ERASED_* items found, by party. */
    private Map<String, List<String>> erasedItems() {
        var output = (com.jabiz.quizbuks.wallet.Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN",
            Map.of("scheduledTime", clock.instant().toString()));
        Map<String, List<String>> found = new java.util.HashMap<>();
        for (Map<String, Object> row : query("SELECT party_id, check_code FROM qb_recon_item_version WHERE run_id = ?"
            + " AND check_code LIKE 'ERASED%' ORDER BY check_code", UUID.fromString(output.runId()))) {
            found.computeIfAbsent(String.valueOf(row.get("party_id")), k -> new java.util.ArrayList<>())
                .add(String.valueOf(row.get("check_code")));
        }
        return found;
    }

    /**
     * Review M3: the account is really erased first (ACCOUNT_ERASE: tombstones, the reasons overwritten), then the
     * registration runs as the erasure's event would (the event is left undelivered: a replay registers nothing more).
     */
    private void erase(String sponsor) {
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", sponsor, "requestedBy", "it-support"));
        assertThat(((MoneyClose.RegisterOutput) systemRun(MoneyClose.REGISTER, Map.of("userId", sponsor)))
            .registered()).isTrue();
    }

    private MoneyClose.CloseOutput close(String sponsor) {
        return (MoneyClose.CloseOutput) systemRun(MoneyClose.CLOSE, Map.of("sponsorId", sponsor));
    }

    private static Map<String, Object> closure(String sponsor) {
        return query("SELECT * FROM qb_sponsor_closure WHERE sponsor_id = ?", sponsor).getFirst();
    }

    private static Map<String, Object> lotOf(String grantId) {
        return query("SELECT * FROM qb_fee_credit_grant WHERE grant_id = ?", grantId).getFirst();
    }

    private static List<Map<String, Object>> openTasks(String sourceKey) {
        return query("SELECT * FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE source_key = ? AND status = 'OPEN' AND NOT is_deleted", sourceKey);
    }

    private String currentPeriod() {
        return com.jabiz.quizbuks.fee.FeeRules.period(clock.instant(), ZoneId.of("Asia/Tokyo"));
    }
}
