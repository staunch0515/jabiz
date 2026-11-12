package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbCreditNotes;
import com.jabiz.quizbuks.fee.QbFees;
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
class CreditNoteIT extends PrepaymentItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    String finance() {
        return tokenOf(user("fm7-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("fm7-content"), QbRoles.ADMIN_CONTENT);
    }

    String monthly(String sponsor) {
        return ((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM,
            body("sponsorId", sponsor, "period", FeeRules.period(clock.instant(), TOKYO)))).outcome();
    }

    static Map<String, Object> monthlyCharge(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? AND kind = 'MONTHLY'", sponsor)
            .getFirst();
    }

    static Map<String, Object> invoiceOf(Object chargeId) {
        return query("SELECT * FROM qb_fee_invoice WHERE charge_id = ?", String.valueOf(chargeId)).getFirst();
    }

    static List<Map<String, Object>> notes(String sponsor) {
        return query("SELECT * FROM qb_fee_credit_note_version WHERE sponsor_id = ? ORDER BY issued_at, credit_note_no",
            sponsor);
    }

    static BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    static List<String> lines(String sourceEntity, Object sourceId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND t.source_id = ? AND t.reverses_transaction_id IS NULL "
            + "ORDER BY t.process_seq_id, t.transaction_id, e.line_no", sourceEntity, String.valueOf(sourceId))
            .stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
                + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    /** A credit note by hand: proposed by finance, approved by another administrator. */
    Map<String, Object> creditNote(Object chargeId, long amount) {
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
            "chargeId", String.valueOf(chargeId), "amount", amount, "reason", "Service was down two days"));
        run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        return query("SELECT * FROM qb_fee_request WHERE request_id = ?", request.get("requestId")).getFirst();
    }

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
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("550");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** A seven-day audience draft (gender: FEMALE) whose single winner's place ends it. */
    String oneWinnerAudienceDraft(String sponsor, String name) {
        String token = sponsorToken(sponsor);
        Instant now = clock.instant();
        Map<String, Object> input = complete(version(token, name + " quiz"), name, now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        input.put("maxWinners", 1);
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, input).get("publicationId");
        Map<String, Object> gender = new java.util.HashMap<>();
        gender.put("key", "gender");
        gender.put("options", List.of("FEMALE"));
        run("QB_PUBLICATION_AUDIENCE_SAVE", token, body("publicationId", publicationId, "criteria",
            new java.util.ArrayList<>(List.of(gender))));
        return publicationId;
    }

    /** A Taker in Japan who told the platform they are a woman, so that the publication is theirs to take. */
    String womanTaker(String name) {
        String taker = newTaker(unique(name), "JP");
        Map<String, Object> answer = new java.util.HashMap<>();
        answer.put("key", "gender");
        answer.put("options", List.of("FEMALE"));
        run("QB_PROFILE_ATTRIBUTES_SAVE", taker(taker), body("profileAnswers", List.of(answer)));
        return taker;
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
}
