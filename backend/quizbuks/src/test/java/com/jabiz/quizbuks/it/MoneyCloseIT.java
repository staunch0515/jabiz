package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbSponsorClosures;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ManualCredits;
import org.junit.jupiter.api.Test;

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
class MoneyCloseIT extends MoneyCloseItSupport {

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
    void theMonthIsInvoicedOnlyAfterTheDistributionFeesFinalSettlementTheDayAfterTheErasure() {
        // Plan H1 (Fable's review of the first draft): a postpaid sponsor erased while a publication runs; its
        // distribution fee is settled for good the day after (period: the month of the erasure). The month's invoice
        // waits for that (DEPOSIT_OPEN, no invoice), then takes the fee: no postpaid fee is left without an invoice.
        autoApprove(true);
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        try {
            String sponsor = newSponsor(unique("s-fm15b-h1"));
            billing(sponsor, "POSTPAID", 50_000);
            credit(sponsor, 5_000);
            String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
                sponsor).getFirst().get("operator_id"));
            run(com.jabiz.quizbuks.fee.DistributionPrices.SET, com.jabiz.runtime.test.TestTokens.bearer(tokens,
                "fm15b-hq", com.jabiz.quizbuks.QbPermissions.FEE_MANAGE), body("operatorId", operator, "rate", "0.05",
                "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500, "reason", "F-M15 b H1"));
            String publicationId = livePublication(sponsor, "FM15b H1", Map.of("maxWinners", 30, "regions",
                List.of("GLOBAL")));
            for (int i = 0; i < 3; i++) {
                start(taker(newTaker(unique("t-fm15b-h1-" + i), null)), publicationId);
            }
            assertThat(prepayments(publicationId)).anySatisfy(row -> assertThat(row)
                .containsEntry("purpose", "DISTRIBUTION").containsEntry("status", "OPEN"));
            erase(sponsor);
            if (!"ENDED".equals(status(publicationId))) {
                run("QB_PUBLICATION_ADMIN_END", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-content",
                    com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE), Map.of("publicationId", publicationId,
                    "reason", "F-M15 b H1"));
            }

            // The day of the erasure: the deposit is open, nothing is invoiced.
            assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
            assertThat(query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)).isEmpty();

            // Later (in production the next day; here the same month): the final settlement charges the fee and
            // releases the deposit.
            clock.set(clock.instant().plus(Duration.ofHours(4)));
            systemRun(com.jabiz.quizbuks.fee.DistributionSettlements.SETTLE, body("publicationId", publicationId,
                "sponsorId", sponsor, "period", null, "last", true));
            List<Map<String, Object>> fees = query("SELECT DISTINCT ON (charge_id) * FROM qb_fee_charge_version "
                + "WHERE sponsor_id = ? AND kind = 'DISTRIBUTION' ORDER BY charge_id, version_no DESC", sponsor);
            assertThat(fees).singleElement().satisfies(fee -> assertThat(fee).containsEntry("billing_mode", "POSTPAID"));
            assertThat(prepayments(publicationId)).allSatisfy(row -> assertThat(row.get("status")).isNotEqualTo("OPEN"));

            MoneyClose.CloseOutput done = close(sponsor);
            assertThat(done.invoicesIssued()).isEqualTo(1);
            Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst();
            assertThat(invoice).containsEntry("period", fees.getFirst().get("period"));
            BigDecimal charged = money(query("SELECT DISTINCT ON (charge_id) total FROM qb_fee_charge_version "
                + "WHERE sponsor_id = ? AND billing_mode = 'POSTPAID' ORDER BY charge_id, version_no DESC", sponsor)
                .stream().map(row -> money(row.get("total"))).reduce(BigDecimal.ZERO, BigDecimal::add));
            assertThat(money(invoice.get("total"))).isPositive().isEqualByComparingTo(charged);
            // No cash and no credit: nothing paid, all of it owed.
            assertThat(money(invoice.get("paid_amount"))).isZero();
            assertThat(done.owed()).isEqualByComparingTo(charged);
            // No month with a postpaid fee left without its invoice, and the receivable agrees.
            assertThat(items(MoneyClose.UNINVOICED_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens,
                "fm15b-job", "qb.fee.system"), Map.of("limit", 100, "params", Map.of("sponsorId", sponsor)))).isEmpty();
            assertThat(differences(sponsor)).isEmpty();
            assertBooksBalance();
        } finally {
            param(QbParams.DISTRIBUTION_BILLING_ENABLED, "false");
        }
    }

    @Test
    void aPostpaidFeeChargedAfterTheCurrentMonthIsInvoicedGoesOnTheNextMonth() {
        // Fable's re-review (the special question): the wind-up invoices the current month; a postpaid fee charged in
        // it afterwards must not land on that month (whose invoice is out: a receivable never invoiced) but on the next.
        String sponsor = newSponsor(unique("s-fm15b-late-fee"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        invoicePeriod(sponsor, clock.instant());
        String current = currentPeriod();
        assertThat(query("SELECT period FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("period", current));

        Object late = manualFee(sponsor, 200);
        String next = java.time.YearMonth.parse(current).plusMonths(1).toString();
        assertThat(query("SELECT period FROM qb_fee_charge_version WHERE charge_id = CAST(? AS uuid)", String.valueOf(late)))
            .first().satisfies(row -> assertThat(row).containsEntry("period", next));
    }

    @Test
    void aFeeChargedInTheMonthAfterTheWindUpIsInvoicedByTheNextRunAndReconciledUntilThen() {
        // Fable's re-review (special question, M4): wound up (the current month invoiced), then a postpaid fee in the
        // same month. It goes on the next month; the reconciliation counts it as owed, the daily job takes the closure
        // again and the next run invoices it. (Registered without the real erasure, so a fee can still be charged.)
        String sponsor = newSponsor(unique("s-fm15b-after"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        spentLot(sponsor);
        assertThat(((MoneyClose.RegisterOutput) systemRun(MoneyClose.REGISTER, Map.of("userId", sponsor,
            "erasedAt", clock.instant().toString()))).registered()).isTrue();
        // The registration's event winds it up (delivered now, so the fee's own delivery below runs nothing more).
        deliverEvents();
        assertThat(closure(sponsor)).containsEntry("status", QbSponsorClosures.CLOSED);
        BigDecimal firstOwed = money(query("SELECT total FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst()
            .get("total"));
        assertThat(due()).doesNotContain(sponsor);

        Object late = manualFee(sponsor, 200);
        BigDecimal lateTotal = money(query("SELECT total FROM qb_fee_charge_version WHERE charge_id = CAST(? AS uuid)",
            String.valueOf(late)).getFirst().get("total"));
        // Owed: the invoice's and the fee not invoiced yet (one code); the daily job takes it again.
        assertThat(erasedOwed(sponsor)).isEqualByComparingTo(firstOwed.add(lateTotal));
        assertThat(due()).contains(sponsor);

        MoneyClose.CloseOutput again = close(sponsor);
        assertThat(again.invoicesIssued()).isEqualTo(1);
        assertThat(query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY period", sponsor)).hasSize(2)
            .last().satisfies(row -> assertThat(money(row.get("total"))).isEqualByComparingTo(lateTotal));
        assertThat(items(MoneyClose.UNINVOICED_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens,
            "fm15b-job", "qb.fee.system"), Map.of("limit", 100, "params", Map.of("sponsorId", sponsor)))).isEmpty();
        assertThat(erasedOwed(sponsor)).isEqualByComparingTo(firstOwed.add(lateTotal));
        assertThat(due()).doesNotContain(sponsor);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.jabiz.job.JobDefinition<com.jabiz.quizbuks.fee.DistributionSettlements.SettleRef> qbDistributionFinalJob;

    @Test
    void erasedAtTheMonthsEndSettledByTheJobsOnTheFirstAndInvoicedWithEveryFee() {
        // Re-review M3: erased in the last hour of a month; the distribution fee's final settlement and the audience
        // deposit's release run as their daily jobs on the 1st. The wind-up waits until both are done, then invoices
        // every postpaid fee (the invoices' totals are the fees'), pays what the cash covers and owes the rest.
        autoApprove(true);
        param(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        try {
            String sponsor = pricedSponsor("fm15b-m3");
            billing(sponsor, "POSTPAID", 50_000);
            cash(sponsor, 10);
            credit(sponsor, 5_000);
            run(com.jabiz.quizbuks.fee.DistributionPrices.SET, com.jabiz.runtime.test.TestTokens.bearer(tokens,
                "fm15b-hq", com.jabiz.quizbuks.QbPermissions.FEE_MANAGE), body("operatorId", String.valueOf(query(
                "SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?", sponsor).getFirst().get("operator_id")),
                "rate", "0.05", "unitFloor", "1", "unitCeiling", "20", "minimumDeposit", 500, "reason", "F-M15 b M3"));
            String distributed = livePublication(sponsor, "FM15b M3 distribution", Map.of("maxWinners", 30,
                "regions", List.of("GLOBAL")));
            for (int i = 0; i < 3; i++) {
                start(taker(newTaker(unique("t-fm15b-m3-" + i), null)), distributed);
            }
            String audience = audienceDraft(sponsor, "FM15b M3 audience");
            submit(sponsorToken(sponsor), audience);

            java.time.YearMonth month = java.time.YearMonth.from(clock.instant().atZone(ZoneId.of("Asia/Tokyo")));
            clock.set(month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant()
                .minus(Duration.ofMinutes(30)));
            erase(sponsor);
            for (String publicationId : List.of(distributed, audience)) {
                if (!"ENDED".equals(status(publicationId))) {
                    run("QB_PUBLICATION_ADMIN_END", com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-content",
                        com.jabiz.quizbuks.QbPermissions.ADMIN_CONTENT_WRITE), Map.of("publicationId", publicationId,
                        "reason", "F-M15 b M3"));
                }
            }
            assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
            assertThat(query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)).isEmpty();

            // The 1st: the two daily jobs.
            clock.set(month.plusMonths(1).atDay(1).atTime(3, 12).atZone(ZoneId.of("Asia/Tokyo")).toInstant());
            assertThat(jobRunner.execute(qbDistributionFinalJob, clock.instant()).outcome())
                .isEqualTo(com.jabiz.runtime.job.JobRunner.Outcome.SUCCEEDED);
            clock.set(clock.instant().plus(Duration.ofHours(1)));
            assertThat(jobRunner.execute(qbPrepaymentReleaseJob, clock.instant()).outcome())
                .isEqualTo(com.jabiz.runtime.job.JobRunner.Outcome.SUCCEEDED);
            assertThat(query("SELECT purpose, status FROM qb_prepayment WHERE sponsor_id = ?", sponsor))
                .hasSizeGreaterThanOrEqualTo(2).allSatisfy(row -> assertThat(row).containsEntry("status", "RELEASED"));

            MoneyClose.CloseOutput done = close(sponsor);
            assertThat(done.outcome()).isEqualTo(MoneyClose.CLOSED);
            List<Map<String, Object>> fees = query("SELECT DISTINCT ON (charge_id) total, kind FROM "
                + "qb_fee_charge_version WHERE sponsor_id = ? AND billing_mode = 'POSTPAID' ORDER BY charge_id, "
                + "version_no DESC", sponsor);
            // The audience draft's deposit was given back by the release job (it never went live: no audience fee).
            assertThat(fees).extracting(row -> row.get("kind")).contains("DISTRIBUTION");
            BigDecimal charged = fees.stream().map(row -> money(row.get("total"))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
            List<Map<String, Object>> invoices = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor);
            BigDecimal invoiced = invoices.stream().map(row -> money(row.get("total"))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
            BigDecimal paid = invoices.stream().map(row -> money(row.get("paid_amount"))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
            assertThat(invoiced).isEqualByComparingTo(charged);
            assertThat(charged).isGreaterThan(BigDecimal.TEN);
            // The cash paid what it could, the rest is owed.
            assertThat(paid).isEqualByComparingTo("10");
            assertThat(done.owed()).isEqualByComparingTo(charged.subtract(paid));
            assertThat(balance(sponsor, "CASH")).isZero();
            assertThat(closure(sponsor)).containsEntry("reason_code", QbSponsorClosures.FEE_OWED);
            assertThat(items(MoneyClose.UNINVOICED_TEMPLATE, com.jabiz.runtime.test.TestTokens.bearer(tokens,
                "fm15b-job", "qb.fee.system"), Map.of("limit", 100, "params", Map.of("sponsorId", sponsor)))).isEmpty();
            assertThat(differences(sponsor)).isEmpty();
            assertBooksBalance();
        } finally {
            param(QbParams.DISTRIBUTION_BILLING_ENABLED, "false");
        }
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
    void aRequestMadeAfterTheErasureIsNotWithdrawn() {
        // Re-review M1: while the wind-up waits on a deposit, finance asks to void the erased sponsor's invoice. The
        // request is bound to what is there now: the next runs leave it under review.
        autoApprove(true);
        String sponsor = pricedSponsor("fm15b-m1");
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM15b M1");
        submit(sponsorToken(sponsor), publicationId);
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        invoicePeriod(sponsor, clock.instant());
        String invoiceId = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor)
            .getFirst().get("invoice_id"));
        erase(sponsor);
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);

        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_VOID,
            "invoiceId", invoiceId, "reason", "Charged by mistake"));
        String requestId = String.valueOf(request.get("requestId"));
        MoneyClose.CloseOutput next = close(sponsor);
        assertThat(next.outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
        assertThat(next.withdrawn()).isZero();
        assertThat(query("SELECT status, ended_code FROM qb_fee_request WHERE request_id = ?", requestId))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbFees.REVIEW)
                .containsEntry("ended_code", null));
    }

    @Test
    void aWindUpWaitingOnlyOnTheAiCreditsPrepaymentSaysSoInFinancesTask() {
        // Re-review M2: the AI credit's prepayment is closed by F-M15 c; until then such a wind-up waits, as expected,
        // and the slow task's title says that it is the AI credit's.
        String sponsor = newSponsor(unique("s-fm15b-ai"));
        grant(sponsor, 200, clock.instant().plus(Duration.ofDays(90)));
        SyntheticRows.rows("qb_prepayment", null, 1, null, n -> Map.of("sponsor_id", text(sponsor), "purpose", "'AI'",
            "mode", "'PREPAID'", "amount", "100", "remaining", "100", "charge_amount", "0", "status", "'OPEN'",
            "frozen_at", "TIMESTAMPTZ '2026-01-01T00:00:00Z'"));
        erase(sponsor);
        clock.set(clock.instant().plus(Duration.ofDays(3)));
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.WAITING_DEPOSITS);
        assertThat(openTasks("erasure-close:" + sponsor)).singleElement()
            .satisfies(task -> assertThat(String.valueOf(task.get("title_key")))
                .isEqualTo(MoneyClose.SLOW_TASK_TITLE + ".DEPOSIT_OPEN_AI"));
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
            com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm15b-job", "qb.fee.system"), Map.of("limit", 100,
                "params", Map.of("at", clock.instant().toString())));
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

    /** What ERASED_FEE_OWED reports for the sponsor now (null when nothing). */
    private BigDecimal erasedOwed(String sponsor) {
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        var output = (com.jabiz.quizbuks.wallet.Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN",
            Map.of("scheduledTime", clock.instant().toString()));
        return query("SELECT actual FROM qb_recon_item_version WHERE run_id = ? AND check_code = 'ERASED_FEE_OWED' "
            + "AND party_id = ?", UUID.fromString(output.runId()), sponsor).stream()
            .map(row -> money(row.get("actual"))).findFirst().orElse(null);
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

    private static List<Map<String, Object>> openTasks(String sourceKey) {
        return query("SELECT * FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE source_key = ? AND status = 'OPEN' AND NOT is_deleted", sourceKey);
    }

    private String currentPeriod() {
        return com.jabiz.quizbuks.fee.FeeRules.period(clock.instant(), ZoneId.of("Asia/Tokyo"));
    }
}
