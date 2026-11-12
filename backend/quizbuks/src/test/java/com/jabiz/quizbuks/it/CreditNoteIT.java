package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbCreditNotes;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-2 to -5, -12): credit notes by hand and the unused days of an audience
 * fee given back. A note pays its fee's invoice first, gives back what a prepaid fee was paid with (the last paid
 * first, a part paid from a prepayment through it), and leaves the rest as credit the next invoice takes first; a
 * fee's notes never come to more than it; an invoice with a note is not voided.
 */
class CreditNoteIT extends CreditNoteItSupport {

    @Test
    void aNoteOnAPrepaidFeeGivesItsCashBackAndNeverComesToMoreThanTheFee() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-prepaid"));
        cash(sponsor, 2_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Map<String, Object> charge = monthlyCharge(sponsor);
        Object chargeId = charge.get("charge_id");
        Object invoiceId = invoiceOf(chargeId).get("invoice_id");
        assertThat(invoiceOf(chargeId)).containsEntry("status", QbFees.PAID);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("900");

        // One person cannot do it alone; another approves; the note is written once and posted.
        Map<String, Object> first = creditNote(chargeId, 400);
        assertThat(first).containsEntry("status", QbFees.DONE);
        assertThat(notes(sponsor)).singleElement().satisfies(note -> {
            assertThat(note).containsEntry("reason", QbCreditNotes.MANUAL).containsEntry("billing_mode", "PREPAID")
                .containsEntry("note", "Service was down two days");
            assertThat((BigDecimal) note.get("amount")).isEqualByComparingTo("400");
            assertThat((BigDecimal) note.get("tax")).isEqualByComparingTo("40");
            assertThat((BigDecimal) note.get("refunded_cash")).isEqualByComparingTo("440");
            assertThat((BigDecimal) note.get("applied_own")).isEqualByComparingTo("0");
            assertThat(String.valueOf(note.get("credit_note_no"))).matches("QBC-\\d{4}-\\d{5}");
        });
        Object noteId = notes(sponsor).getFirst().get("credit_note_id");
        assertThat(lines(QbCreditNotes.CREDIT_NOTE, noteId)).containsExactly("4120 DEBIT 400", "2150 DEBIT 40",
            "1130 CREDIT 440", "1130 DEBIT 440", "2160 CREDIT 440");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1340");
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        // The fee and its invoice unchanged.
        assertThat(invoiceOf(chargeId)).containsEntry("status", QbFees.PAID);
        assertThat((BigDecimal) invoiceOf(chargeId).get("paid_amount")).isEqualByComparingTo("1100");

        // More than is left: refused when proposed; an invoice with a note is not voided.
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE, "chargeId",
            String.valueOf(chargeId), "amount", 601, "reason", "Too much"), 422)))
            .containsExactly(FeeCodes.CREDIT_NOTE_EXCEEDS);
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_VOID, "invoiceId",
            String.valueOf(invoiceId), "reason", "Charged by mistake"), 422)))
            .containsExactly(FeeCodes.INVOICE_HAS_CREDIT_NOTE);

        // The rest of the fee: its tax is the rest of the fee's (60), all of it back.
        creditNote(chargeId, 600);
        assertThat(notes(sponsor)).hasSize(2);
        Map<String, Object> last = notes(sponsor).getLast();
        assertThat((BigDecimal) last.get("tax")).isEqualByComparingTo("60");
        assertThat((BigDecimal) last.get("refunded_cash")).isEqualByComparingTo("660");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("2000");
        assertThat(query("SELECT sum(amount) AS n FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)
            .getFirst().get("n")).satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("0"));
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void aNoteApprovedAfterAnotherTookWhatWasLeftIsNotIssuedForLess() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-n8"));
        cash(sponsor, 2_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        Map<String, Object> waiting = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
            "chargeId", String.valueOf(chargeId), "amount", 800, "reason", "Two weeks down"));
        // Meanwhile another note takes 300: 700 is left, the 800 approved is not issued for less (N8).
        creditNote(chargeId, 300);
        run("APPROVAL_DECIDE", content(), body("requestId", waiting.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?", waiting.get("requestId")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("status", QbFees.FAILED));
        assertThat(notes(sponsor)).hasSize(1);
        assertThat(query("SELECT DISTINCT task_id FROM sys_task_version WHERE source_key = ?", FeeRequests.NOTE_FAILED_TASK + ":"
            + waiting.get("requestId"))).hasSize(1);
    }

    @Test
    void aPostpaidFeesNoteIsCreditTheNextInvoiceTakesFirst() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-postpaid"));
        billing(sponsor, "POSTPAID", 5_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        creditNote(chargeId, 500);
        // No invoice yet, none unpaid: all of it is credit; no cash back (C2).
        assertThat(notes(sponsor)).singleElement().satisfies(note -> {
            assertThat((BigDecimal) note.get("refunded_cash")).isEqualByComparingTo("0");
            assertThat((BigDecimal) note.get("total")).isEqualByComparingTo("550");
        });
        // 1130 (credit less debit here): 1100 receivable less the note's 550, 550 owed.
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-550");

        // The month's invoice takes it first: 550 of 1100.
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL",
            sponsor).getFirst();
        assertThat(invoice).containsEntry("status", QbFees.PARTIAL);
        assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("550");
        assertThat(query("SELECT amount FROM qb_fee_credit_note_use_version WHERE invoice_id = ?",
            String.valueOf(invoice.get("invoice_id")))).singleElement()
            .satisfies(use -> assertThat((BigDecimal) use.get("amount")).isEqualByComparingTo("550"));
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("unpaidTotal", 550);
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_VOID, "invoiceId",
            String.valueOf(invoice.get("invoice_id")), "reason", "Charged by mistake"), 422)))
            .containsExactly(FeeCodes.INVOICE_HAS_CREDIT_NOTE);
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("-550");
        assertThat(differences(sponsor)).isEmpty();
    }



    @Test
    void anAudienceFeeEndedEarlyGivesBackItsUnusedDaysOnce() {
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        String sponsor = pricedSponsor("fm7-audience");
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = oneWinnerAudienceDraft(sponsor, "FM7 audience");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        Map<String, Object> charge = chargeOf(sponsor);
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo(SET_ASIDE);
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);

        // Two days after it went on, its one winner ends it (QUOTA): three of seven days used, 400 and 40 tax back.
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        play(womanTaker("t-fm7-winner"), publicationId, true, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        Map<String, Object> prepayment = prepayments(publicationId).getFirst();
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayment.get("prepayment_id")),
            "publicationId", publicationId, "sponsorId", sponsor, "endedAt", clock.instant().toString());
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome())
            .isEqualTo("REFUNDED");
        assertThat(notes(sponsor)).singleElement().satisfies(note -> {
            assertThat(note).containsEntry("reason", QbCreditNotes.AUDIENCE_UNUSED).containsEntry("end_reason", "QUOTA")
                .containsEntry("publication_id", publicationId);
            assertThat((BigDecimal) note.get("used_days")).isEqualByComparingTo("3");
            assertThat((BigDecimal) note.get("amount")).isEqualByComparingTo("400");
            assertThat((BigDecimal) note.get("total")).isEqualByComparingTo("440");
        });
        // Back through the prepayment it was paid from (F-M5's path), to the cash it was set aside from.
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore.add(new BigDecimal("440")));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(prepayments(publicationId).getFirst()).containsEntry("refund_outcome", "REFUNDED");

        // Decided once: asked again, nothing more; the job's selection no longer has it.
        systemRun(AudienceRefundJob.ITEM, ref);
        assertThat(notes(sponsor)).hasSize(1);
        assertThat(items(AudienceRefundJob.DUE_TEMPLATE, TestTokens.bearer(tokens, "fm7-job", QbPermissions.FEE_SYSTEM),
            Map.of("params", Map.of("at", clock.instant().toString())))).noneMatch(row ->
            publicationId.equals(row.get("publicationId")));
        assertThat(differences(sponsor)).isEmpty();
    }


    /**
     * (c) A fee paid partly from its prepayment and partly from the account (the tax rate rose while it waited): the
     * last paid goes back first, across the two (the account's cash, then the prepayment's parts, through it).
     */
    @Test
    void aFeePaidFromItsPrepaymentAndTheAccountGivesBackTheLastPaidFirst() {
        autoApprove(true);
        String sponsor = pricedSponsor("fm7-mixed");
        cash(sponsor, 2_000);
        String publicationId = audienceDraft(sponsor, "FM7 mixed");
        submit(sponsorToken(sponsor), publicationId);
        // 770 set aside at 10%; charged at 20% when it goes on: 840, of which 70 from the account.
        param(QbParams.FEE_TAX_RATE, "0.2");
        try {
            goLive(publicationId);
        } finally {
            param(QbParams.FEE_TAX_RATE, "0.1");
        }
        Map<String, Object> charge = chargeOf(sponsor);
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo("840");
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);

        // 100 of 700: 20 tax at the fee's 20%; the account's 70 back first, then 50 through the prepayment.
        creditNote(charge.get("charge_id"), 100);
        Map<String, Object> note = notes(sponsor).getFirst();
        assertThat((BigDecimal) note.get("total")).isEqualByComparingTo("120");
        assertThat((BigDecimal) note.get("refunded_cash")).isEqualByComparingTo("120");
        Object noteId = note.get("credit_note_id");
        assertThat(lines(QbCreditNotes.CREDIT_NOTE, noteId)).containsExactly("4120 DEBIT 100", "2150 DEBIT 20",
            "1130 CREDIT 120", "1130 DEBIT 120", "2160 CREDIT 70", "2180 CREDIT 50");
        String prepaymentId = String.valueOf(prepayments(publicationId).getFirst().get("prepayment_id"));
        assertThat(lines(QbPrepayments.PREPAYMENT, prepaymentId)).contains("2180 DEBIT 50", "2160 CREDIT 50");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore.add(new BigDecimal("120")));
        assertThat(query("SELECT amount, prepayment_id FROM qb_fee_payment_version WHERE target_entity = "
            + "'QbFeeCreditNote' AND sponsor_id = ? ORDER BY row_id", sponsor)).extracting(r -> ((BigDecimal) r.get("amount")).intValue() + ":"
            + (r.get("prepayment_id") == null ? "account" : "prepayment"))
            .containsExactly("-70:account", "-50:prepayment");
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /**
     * (d) A postpaid fee's note pays the sponsor's other unpaid invoices at once, the earliest first (OVERDUE among
     * them, C1), and leaves nothing while one is unpaid.
     */
    @Test
    void aPostpaidNotePaysTheOtherUnpaidInvoicesAtOnce() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-others"));
        billing(sponsor, "POSTPAID", 50_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        Map<String, Object> month = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? AND charge_id IS NULL",
            sponsor).getFirst();
        // A fee by hand afterwards (not invoiced yet); its note pays the month's invoice, 550 of 1100.
        Object manual = manualFee(sponsor, 500);
        creditNote(manual, 500);
        Map<String, Object> after = query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", month.get("invoice_id"))
            .getFirst();
        assertThat(after).containsEntry("status", QbFees.PARTIAL);
        assertThat((BigDecimal) after.get("credit_applied")).isEqualByComparingTo("550");
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        // A note on the month's own fee pays the rest: the invoice is paid by credit (nothing was received), the
        // rest of that note left for the next invoice.
        creditNote(monthlyCharge(sponsor).get("charge_id"), 1_000);
        Map<String, Object> paid = query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", month.get("invoice_id"))
            .getFirst();
        assertThat(paid).containsEntry("status", QbFees.PAID);
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat((BigDecimal) paid.get("credit_applied")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) paid.get("paid_amount")).isEqualByComparingTo("0");
        // 1100 + 550 credited, 1100 used: 550 left; the next invoice (the fee by hand's, next month) takes it.
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("creditNoteCredit", 550).containsEntry("unpaidTotal", 0);
        assertThat(differences(sponsor)).isEmpty();
    }

    /**
     * C3: the operator gives back its share of the month's credit notes (before tax), at the platform's share of that
     * month's plan; what its share that month does not cover is carried to the next, and the statement shows it.
     */
    @Test
    void theOperatorGivesBackItsShareOfTheMonthsCreditNotes() {
        clock.set(Instant.parse("2027-06-10T01:00:00Z"));
        String hq = TestTokens.bearer(tokens, "fm7-ops-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
        String operatorId = newOperator(hq, unique("FM7 operator"));
        String sponsor = newSponsor(unique("s-fm7-operator"));
        run(com.jabiz.quizbuks.operator.OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId",
            operatorId, "reason", "F-M7"));
        param(QbParams.FEE_MONTHLY, "1000");
        cash(sponsor, 2_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");

        // June: 1000 of fees under plan 1 (the platform keeps half): 500 to the operator.
        clock.set(Instant.parse("2027-07-02T00:00:00Z"));
        systemRun(com.jabiz.quizbuks.operator.OperatorSettlements.RUN, body("operatorId", operatorId, "month",
            "2027-06"));
        assertThat(settlementOf(operatorId, "2027-06")).satisfies(row -> {
            assertThat((BigDecimal) row.get("operator_share")).isEqualByComparingTo("500");
            assertThat((BigDecimal) row.get("credit_note_deduction")).isEqualByComparingTo("0");
        });
        // July: the whole fee credited; nothing earned in July, so the 500 given back is carried.
        creditNote(chargeId, 1_000);
        clock.set(Instant.parse("2027-08-02T00:00:00Z"));
        systemRun(com.jabiz.quizbuks.operator.OperatorSettlements.RUN, body("operatorId", operatorId, "month",
            "2027-07"));
        assertThat(settlementOf(operatorId, "2027-07")).satisfies(row -> {
            assertThat((BigDecimal) row.get("credit_notes")).isEqualByComparingTo("1000");
            assertThat((BigDecimal) row.get("credit_note_deduction")).isEqualByComparingTo("500");
            assertThat((BigDecimal) row.get("operator_share")).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("payable")).isEqualByComparingTo("0");
            assertThat((BigDecimal) row.get("carried_out")).isEqualByComparingTo("500");
        });
        assertThat(items("qb.admin.operator-settlements", finance(), Map.of("limit", 100))).filteredOn(row ->
            operatorId.equals(String.valueOf(row.get("operatorId"))) && "2027-07".equals(row.get("month")))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("creditNoteDeduction", 500));
    }

    static Map<String, Object> settlementOf(String operatorId, String month) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .getFirst();
    }

    /** Ends the publication by its one winner, two days after it went on; the item's reference. */
    Map<String, Object> endEarly(String sponsor, String publicationId) {
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        play(womanTaker("t-fm7-end"), publicationId, true, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        return body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst().get("prepayment_id")),
            "publicationId", publicationId, "sponsorId", sponsor, "endedAt", clock.instant().toString());
    }

    /**
     * The switch as it was when the publication ended decides (§10.3-1): off then and on later gives nothing back; on
     * then and off later gives back. A prepayment frozen before F-M7 has no period of its own: the publication's as it
     * was when the prepayment was frozen is read instead (N5).
     */
    @Test
    void theSwitchAtTheEndDecidesAndAnOldPrepaymentTakesThePeriodItWasFrozenWith() {
        autoApprove(true);
        String sponsor = pricedSponsor("fm7-switch");
        cash(sponsor, 3_000);
        credit(sponsor, 20_000);

        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "false");
        String off = oneWinnerAudienceDraft(sponsor, "FM7 off");
        submit(sponsorToken(sponsor), off);
        goLive(off);
        Map<String, Object> offRef = endEarly(sponsor, off);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, offRef)).outcome())
            .isEqualTo("NONE_SWITCH_OFF");
        assertThat(notes(sponsor)).isEmpty();
        assertThat(prepayments(off).getFirst()).containsEntry("refund_outcome", "NONE_SWITCH_OFF");

        String on = oneWinnerAudienceDraft(sponsor, "FM7 on");
        submit(sponsorToken(sponsor), on);
        goLive(on);
        // As frozen before F-M7: the stored quote without its start and end.
        execute("UPDATE qb_prepayment SET detail = regexp_replace(detail, ',\"startAt\":.*$', '}') "
            + "WHERE publication_id = ?", on);
        assertThat(String.valueOf(prepayments(on).getFirst().get("detail"))).doesNotContain("startAt");
        Map<String, Object> onRef = endEarly(sponsor, on);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "false");
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, onRef)).outcome())
            .isEqualTo("REFUNDED");
        assertThat(notes(sponsor)).singleElement().satisfies(note -> {
            assertThat((BigDecimal) note.get("used_days")).isEqualByComparingTo("3");
            assertThat((BigDecimal) note.get("amount")).isEqualByComparingTo("400");
        });
        // The daily release closes the two ended publications' prepayments (F-M5), as it does every day.
        for (String publicationId : List.of(off, on)) {
            systemRun(com.jabiz.quizbuks.fee.Prepayments.RELEASE_ITEM, body("publicationId", publicationId,
                "sponsorId", sponsor, "purpose", "AUDIENCE"));
        }
        assertThat(differences(sponsor)).isEmpty();
    }
}
