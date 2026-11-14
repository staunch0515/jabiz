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
 * platform fee account. The invoices are paid through F-M13's automatic payment item, the earliest first, the granted
 * credit before the cash; the granted credit left is voided only after the last payment (H2). E1–E3 are the plan's §2.
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
        // Finance's page: the amounts summed from the ledger and the parts; done, nothing owed.
        assertThat(items("qb.admin.money-closure-amounts", finance(), Map.of("params", Map.of("sponsorId", sponsor))))
            .singleElement().satisfies(row -> {
                assertThat(new BigDecimal(String.valueOf(row.get("creditVoided")))).isEqualByComparingTo("1000");
                assertThat(new BigDecimal(String.valueOf(row.get("owed")))).isZero();
            });
        assertThat(closuresShown("OPEN")).doesNotContain(sponsor);
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
        // Finance's list: done with fees owed, not among those not done.
        assertThat(closuresShown("OWED")).contains(sponsor);
        assertThat(closuresShown("OPEN")).doesNotContain(sponsor);

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
        // The cash pays what it can of the invoice; still owed and nothing left to pay with: done again, FEE_OWED.
        MoneyClose.CloseOutput paid = close(sponsor);
        assertThat(paid.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(paid.invoicesPaid()).isEqualTo(1);
        assertThat(paid.owed()).isPositive();
        assertThat(query("SELECT paid_amount, status FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor))
            .singleElement().satisfies(row -> {
                assertThat(new BigDecimal(String.valueOf(row.get("paid_amount")))).isEqualByComparingTo("200");
                assertThat(row).containsEntry("status", QbFees.PARTIAL);
            });
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
        assertThat(due()).doesNotContain(sponsor);
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
    void theInvoiceIsPaidFromTheGrantedCreditBeforeWhatIsLeftIsVoided() {
        String sponsor = newSponsor(unique("s-fm15b-topay"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        String lot = grant(sponsor, 1000, clock.instant().plus(Duration.ofDays(60)));
        erase(sponsor);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.invoicesIssued()).isEqualTo(1);
        assertThat(run.invoicesPaid()).isEqualTo(1);
        assertThat(run.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(run.owed()).isZero();
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst();
        assertThat(invoice).containsEntry("status", QbFees.PAID);
        BigDecimal total = new BigDecimal(String.valueOf(invoice.get("total")));
        // Paid first, then the rest voided: the lot's void is what was left after the payment.
        assertThat(new BigDecimal(String.valueOf(lotOf(lot).get("voided_amount"))))
            .isEqualByComparingTo(new BigDecimal("1000").subtract(total));
        assertThat(remainingOf(lot)).isZero();
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED).containsEntry("reason_code", null);
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void e1FeesOwedBeyondTheAccountArePaidEarliestFirstAsFarAsItGoes() {
        // Plan §2 E1: grants A 1,500 and B 2,000, cash 3,000 then 1,000 (7,500); invoices of two past months and the
        // current month's fees not invoiced yet; the three owe more than the account. The earliest are paid in full,
        // the last in part; the account is empty; done with fees owed; the granted credit (nothing left) marked.
        String sponsor = newSponsor(unique("s-fm15b-e1"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 3_000);
        cash(sponsor, 1_000);
        manualFee(sponsor, 3_600);
        clock.set(clock.instant().plus(Duration.ofDays(32)));
        invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        manualFee(sponsor, 2_700);
        clock.set(clock.instant().plus(Duration.ofDays(32)));
        invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        manualFee(sponsor, 2_000);
        String a = grant(sponsor, 1_500, clock.instant().plus(Duration.ofDays(30)));
        String b = grant(sponsor, 2_000, clock.instant().plus(Duration.ofDays(60)));
        erase(sponsor);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.invoicesIssued()).isEqualTo(1);
        assertThat(run.invoicesPaid()).isEqualTo(3);
        assertThat(run.outcome()).isEqualTo(MoneyClose.CLOSED);
        List<Map<String, Object>> invoices = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY period",
            sponsor);
        assertThat(invoices).extracting(row -> row.get("status"))
            .containsExactly(QbFees.PAID, QbFees.PAID, QbFees.PARTIAL);
        BigDecimal first = money(invoices.get(0).get("total"));
        BigDecimal second = money(invoices.get(1).get("total"));
        BigDecimal third = money(invoices.get(2).get("total"));
        BigDecimal lastPaid = new BigDecimal("7500").subtract(first).subtract(second);
        assertThat(money(invoices.get(2).get("paid_amount"))).isEqualByComparingTo(lastPaid);
        assertThat(run.owed()).isEqualByComparingTo(third.subtract(lastPaid));
        // The account is empty: both lots and the cash used up; the lots marked so what comes back is voided at once.
        assertThat(remainingOf(a)).isZero();
        assertThat(remainingOf(b)).isZero();
        assertThat(lotOf(a).get("voided_at")).isNotNull();
        assertThat(money(lotOf(b).get("voided_amount"))).isZero();
        assertThat(balance(sponsor, "CASH")).isZero();
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED)
            .containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
        // A replay pays nothing more.
        MoneyClose.CloseOutput again = close(sponsor);
        assertThat(again.invoicesPaid()).isZero();
        assertThat(again.outcome()).isEqualTo(MoneyClose.CLOSED);
    }

    @Test
    void e2TheGrantedCreditLeftAfterThePaymentIsVoidedAndTheCashStays() {
        // Plan §2 E2: grants A 1,500 + B 3,500, one invoice, cash 4,000: paid A then B; B's rest voided; the cash stays.
        String sponsor = newSponsor(unique("s-fm15b-e2"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 4_000);
        manualFee(sponsor, 1_800);
        String a = grant(sponsor, 1_500, clock.instant().plus(Duration.ofDays(30)));
        String b = grant(sponsor, 3_500, clock.instant().plus(Duration.ofDays(60)));
        erase(sponsor);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.outcome()).isEqualTo(MoneyClose.CLOSED);
        assertThat(run.invoicesPaid()).isEqualTo(1);
        BigDecimal total = money(query("SELECT total FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst()
            .get("total"));
        assertThat(money(lotOf(a).get("voided_amount"))).isZero();
        assertThat(money(lotOf(b).get("voided_amount")))
            .isEqualByComparingTo(new BigDecimal("5000").subtract(total));
        assertThat(balance(sponsor, "CASH")).isEqualByComparingTo("4000");
        assertThat(balance(sponsor, "FEE_CREDIT")).isZero();
        assertThat(closure(sponsor)).containsEntry("reason_code", null);
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void e3TheGrantedCreditFrozenInAPrepaymentPaysTheInvoiceBeforeAnythingIsVoided() {
        // Plan §2 E3 (the prepayment's part; the AI credit's is F-M15 c): lot Y partly frozen in a prepayment, lot X in
        // the account, an old invoice. The first run waits for the deposit; Y is not voided then; after the release Y
        // pays first (it expires first), X the rest, and only X's remainder is voided.
        autoApprove(true);
        String sponsor = pricedSponsor("fm15b-e3");
        String y = grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(30)));
        String publicationId = audienceDraft(sponsor, "FM15b E3");
        submit(sponsorToken(sponsor), publicationId);
        assertThat(remainingOf(y)).isLessThan(new BigDecimal("1000"));
        String x = grant(sponsor, 500, clock.instant().plus(Duration.ofDays(90)));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 1_000);
        erase(sponsor);

        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
        assertThat(lotOf(y)).containsEntry("voided_at", null);
        assertThat(lotOf(x)).containsEntry("voided_at", null);
        if (!"ENDED".equals(status(publicationId))) {
            run("QB_PUBLICATION_ADMIN_END", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-content",
                com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE), Map.of("publicationId", publicationId,
                "reason", "F-M15 b test"));
        }
        systemRun(com.jabiz.quizbuks.fee.Prepayments.RELEASE_ITEM, Map.of("publicationId", publicationId,
            "sponsorId", sponsor, "purpose", "AUDIENCE"));
        BigDecimal yBack = remainingOf(y);

        MoneyClose.CloseOutput run = close(sponsor);
        assertThat(run.outcome()).isEqualTo(MoneyClose.CLOSED);
        BigDecimal total = money(query("SELECT total FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst()
            .get("total"));
        assertThat(total).isGreaterThan(yBack);
        // Y used up by the invoice (nothing of it voided), X paid the rest and only its remainder is voided.
        assertThat(remainingOf(y)).isZero();
        assertThat(money(lotOf(y).get("voided_amount"))).isZero();
        assertThat(money(lotOf(x).get("voided_amount")))
            .isEqualByComparingTo(new BigDecimal("500").subtract(total.subtract(yBack)));
        assertThat(query("SELECT status FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst())
            .containsEntry("status", QbFees.PAID);
        assertBooksBalance();
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void moreInvoicesThanARunPaysWaitForTheNextRun() {
        String sponsor = newSponsor(unique("s-fm15b-max"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 5_000);
        manualFee(sponsor, 500);
        clock.set(clock.instant().plus(Duration.ofDays(32)));
        invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        manualFee(sponsor, 500);
        clock.set(clock.instant().plus(Duration.ofDays(32)));
        invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        manualFee(sponsor, 500);
        erase(sponsor);
        run("PARAM_SET", admin(), body("key", QbParams.FEE_AUTO_PAY_MAX_INVOICES, "value", "2"));
        try {
            MoneyClose.CloseOutput first = close(sponsor);
            assertThat(first.invoicesPaid()).isEqualTo(2);
            assertThat(first.outcome()).isEqualTo(MoneyClose.TO_PAY);
            assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.WAITING);
            assertThat(due()).contains(sponsor);
            MoneyClose.CloseOutput second = close(sponsor);
            assertThat(second.invoicesPaid()).isEqualTo(1);
            assertThat(second.outcome()).isEqualTo(MoneyClose.CLOSED);
            assertThat(query("SELECT status FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor))
                .extracting(row -> row.get("status")).containsOnly(QbFees.PAID).hasSize(3);
        } finally {
            run("PARAM_SET", admin(), body("key", QbParams.FEE_AUTO_PAY_MAX_INVOICES, "value", "20"));
        }
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
        assertThat(closuresShown("SLOW")).contains(waits).doesNotContain(owes, left);
        assertThat(closuresShown("OPEN")).contains(waits);
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

    /** The postpaid invoice of the month {@code in} falls in, as the monthly job's item issues it. */
    private void invoicePeriod(String sponsor, java.time.Instant in) {
        systemRun(com.jabiz.quizbuks.fee.FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period",
            com.jabiz.quizbuks.fee.FeeRules.period(in, ZoneId.of("Asia/Tokyo"))));
    }

    private static BigDecimal money(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    /** Finance's list of wind-ups, filtered as the page does, at the clock's time. */
    private List<Object> closuresShown(String show) {
        return items("qb.admin.money-closures", finance(), Map.of("limit", 1000, "params", Map.of("show", show,
            "at", clock.instant().toString()))).stream().map(row -> row.get("sponsorId")).toList();
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
