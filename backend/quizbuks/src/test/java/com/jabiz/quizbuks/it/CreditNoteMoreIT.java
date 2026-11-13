package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.CreditNotes;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.Prepayments;
import com.jabiz.quizbuks.fee.QbCreditNotes;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7, M2 of the review (docs/quizbuks/plans/impl/F-M.md §10.4): the cases {@link CreditNoteIT} did not cover. Paying
 * early and a fee charged after the switch to prepaid take the credit first; what goes back to an expired lot is voided
 * again; a voided invoice's fee takes no note (when proposed or approved); a note pays an OVERDUE invoice and closes its
 * reminder; one person cannot approve their own note; a note pays its own invoice's debt before giving back; a period
 * that cannot be read is NONE_NO_PERIOD and seen by the check; the refund runs alongside the 03:23 release; the
 * monthly report's rows; and the note's PDF.
 */
class CreditNoteMoreIT extends CreditNoteItSupport {

    /** C2: paying early takes the credit left first; a fee the credit pays all of takes nothing from the account. */
    @Test
    void payingEarlyTakesTheCreditFirst() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-early"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 1_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object monthlyId = monthlyCharge(sponsor).get("charge_id");
        // The whole month's fee credited before its invoice: 1100 of credit. Then a fee by hand of 500 (550).
        creditNote(monthlyId, 1_000);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        Object manualId = manualFee(sponsor, 500);
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);

        // The oldest first: the month's fee all from the credit (nothing from the account), the fee by hand from cash.
        assertThat(run(FeeProcesses.PAY_EARLY, sponsorToken(sponsor), body())).containsEntry("count", 2);
        assertThat(invoiceOf(monthlyId)).satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID);
            assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("1100");
            assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("0");
        });
        assertThat(invoiceOf(manualId)).satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID);
            assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("0");
            assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("550");
        });
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE target_id = ?",
            String.valueOf(invoiceOf(monthlyId).get("invoice_id")))).isEmpty();
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore.subtract(new BigDecimal("550")));
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("creditNoteCredit", 0).containsEntry("unpaidTotal", 0);
        assertThat(differences(sponsor)).isEmpty();
    }

    /** A postpaid sponsor's credit, once they pay as they go (prepaid), pays their next fee before the account. */
    @Test
    void aFeeChargedAfterTheSwitchToPrepaidTakesTheCreditFirst() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-switch-billing"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 1_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(sponsor).get("charge_id"), 1_000);
        billing(sponsor, "PREPAID", 0);
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);

        Object manualId = manualFee(sponsor, 500);
        assertThat(invoiceOf(manualId)).satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID).containsEntry("billing_mode", "PREPAID");
            assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("550");
            assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("0");
        });
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore);
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("creditNoteCredit", 550);
    }

    /** What a note gives back to a lot of fee credit that expired and was voided is voided again at once. */
    @Test
    void whatGoesBackToAnExpiredLotIsVoidedAgain() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-expired"));
        Instant now = clock.instant();
        String lot = grant(sponsor, 2_000, now.minus(Duration.ofDays(1)), now.plus(Duration.ofMinutes(1)));
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        clock.set(now.plus(Duration.ofMinutes(2)));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeCredits.EXPIRE_ITEM, body("grantId", lot))).outcome())
            .isEqualTo("VOIDED");

        creditNote(chargeId, 1_000);
        Map<String, Object> note = notes(sponsor).getFirst();
        assertThat((BigDecimal) note.get("refunded_credit")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) note.get("voided_credit")).isEqualByComparingTo("1100");
        assertThat((BigDecimal) note.get("refunded_cash")).isEqualByComparingTo("0");
        assertThat(query("SELECT remaining, voided_amount FROM qb_fee_credit_grant WHERE grant_id = ?", lot)
            .getFirst()).satisfies(row -> {
                assertThat((BigDecimal) row.get("remaining")).isEqualByComparingTo("0");
                assertThat((BigDecimal) row.get("voided_amount")).isEqualByComparingTo("2000");
            });
        // The note gives back to 2190; the lot is voided again (2190 to 5140), so the fee credit stays 0.
        assertThat(lines(QbCreditNotes.CREDIT_NOTE, note.get("credit_note_id"))).contains("1130 DEBIT 1100",
            "2190 CREDIT 1100");
        assertThat(lines(QbFeeAccount.GRANT, lot)).endsWith("2190 DEBIT 1100", "5140 CREDIT 1100");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
        assertThat(ledger("2190", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** A voided invoice's fee takes no note: refused when proposed, and FAILED when the invoice is voided meanwhile. */
    @Test
    void aVoidedInvoicesFeeTakesNoNote() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-voided"));
        cash(sponsor, 3_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        // Proposed, then the invoice is voided before it is approved: the approval does not issue it.
        Map<String, Object> waiting = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
            "chargeId", String.valueOf(chargeId), "amount", 100, "reason", "Down a day"));
        voidInvoice(invoiceOf(chargeId).get("invoice_id"));
        assertThat(invoiceOf(chargeId)).containsEntry("status", QbFees.VOID);
        run("APPROVAL_DECIDE", content(), body("requestId", waiting.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?", waiting.get("requestId"))
            .getFirst()).containsEntry("status", QbFees.FAILED);
        assertThat(notes(sponsor)).isEmpty();
        // Proposed after: refused at once.
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE, "chargeId",
            String.valueOf(chargeId), "amount", 100, "reason", "Down a day"), 422)))
            .containsExactly(FeeCodes.CREDIT_NOTE_VOIDED);
    }

    /**
     * C1: a note pays an OVERDUE invoice (the overdue job made it so) and its reminder is closed. One administrator
     * cannot approve the note they proposed.
     */
    @Test
    void aNotePaysAnOverdueInvoiceAndClosesItsReminder() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-overdue"));
        billing(sponsor, "POSTPAID", 50_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        assertThat(invoiceTheMonth(sponsor)).isEqualTo("ISSUED");
        String invoiceId = String.valueOf(query("SELECT invoice_id FROM qb_fee_invoice WHERE sponsor_id = ? "
            + "AND charge_id IS NULL", sponsor).getFirst().get("invoice_id"));
        clock.set(clock.instant().plus(Duration.ofDays(31)));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeProcesses.OVERDUE_ITEM, body("invoiceId", invoiceId)))
            .outcome()).isEqualTo("REMINDED");
        assertThat(invoice(invoiceId)).containsEntry("status", QbFees.OVERDUE);
        String source = QbFees.overdueTaskSource(invoiceId);
        assertThat(taskStatuses(source)).containsExactly("OPEN");

        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
            "chargeId", String.valueOf(chargeId), "amount", 1_000, "reason", "Never delivered"));
        assertThat(codes(refused("APPROVAL_DECIDE", finance(), body("requestId", request.get("approvalRequestId"),
            "decision", "APPROVE"), 422))).containsExactly("APPROVAL_OWN_REQUEST");
        assertThat(notes(sponsor)).isEmpty();
        run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();

        assertThat(invoice(invoiceId)).satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID);
            assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("1100");
        });
        assertThat(taskStatuses(source)).doesNotContain("OPEN");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** (a) A prepaid fee whose invoice is partly unpaid: the note pays the debt first, then gives the rest back. */
    @Test
    void aNotePaysItsOwnInvoicesDebtBeforeGivingBack() {
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-own"));
        cash(sponsor, 600);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Object chargeId = monthlyCharge(sponsor).get("charge_id");
        assertThat(invoiceOf(chargeId)).containsEntry("status", QbFees.PARTIAL);
        assertThat((BigDecimal) invoiceOf(chargeId).get("paid_amount")).isEqualByComparingTo("600");
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);

        // 800 and 80 tax: 500 pays the invoice's debt, 380 goes back to the cash it was paid with.
        creditNote(chargeId, 800);
        Map<String, Object> note = notes(sponsor).getFirst();
        assertThat((BigDecimal) note.get("applied_own")).isEqualByComparingTo("500");
        assertThat((BigDecimal) note.get("refunded_cash")).isEqualByComparingTo("380");
        assertThat(invoiceOf(chargeId)).satisfies(invoice -> {
            assertThat(invoice).containsEntry("status", QbFees.PAID);
            assertThat((BigDecimal) invoice.get("credit_applied")).isEqualByComparingTo("500");
        });
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore.add(new BigDecimal("380")));
        assertThat(ledger("1130", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** N5: a prepayment whose period cannot be read gives NONE_NO_PERIOD, and the daily check lists it for finance. */
    @Test
    void aPeriodThatCannotBeReadIsDecidedAsSuchAndChecked() {
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        String sponsor = pricedSponsor("fm7-noperiod");
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = oneWinnerAudienceDraft(sponsor, "FM7 no period");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        // Frozen before F-M7 (no period of its own) and, as far as the versions go, before the publication was saved.
        execute("UPDATE qb_prepayment SET detail = regexp_replace(detail, ',\"startAt\":.*$', '}'), "
            + "frozen_at = ? WHERE publication_id = ?", Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),
            publicationId);
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        play(womanTaker("t-fm7-noperiod"), publicationId, true, true);
        assertThat(status(publicationId)).isEqualTo("ENDED");
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
            clock.instant().toString());

        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome())
            .isEqualTo("NONE_NO_PERIOD");
        assertThat(notes(sponsor)).isEmpty();
        assertThat(differences(sponsor)).extracting(row -> row.get("checkCode"))
            .containsExactly("AUDIENCE_REFUND_NO_PERIOD");
    }

    /**
     * The refund item and the 03:23 release of the same publication's prepayment at once: both under the sponsor's
     * wallet lock, so one waits for the other; either way one note, the prepayment released, no difference.
     */
    @Test
    void theRefundAndTheReleaseAtOnceLeaveNoDifference() {
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        String sponsor = pricedSponsor("fm7-overlap");
        cash(sponsor, 1_000);
        credit(sponsor, 10_000);
        String publicationId = oneWinnerAudienceDraft(sponsor, "FM7 overlap");
        submit(sponsorToken(sponsor), publicationId);
        goLive(publicationId);
        BigDecimal cashBefore = balance(sponsor, QbWallets.CASH);
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        play(womanTaker("t-fm7-overlap"), publicationId, true, true);
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
            clock.instant().toString());

        CompletableFuture<Object> refund = CompletableFuture.supplyAsync(() -> systemRun(AudienceRefundJob.ITEM, ref));
        CompletableFuture<Object> release = CompletableFuture.supplyAsync(() -> systemRun(Prepayments.RELEASE_ITEM,
            body("publicationId", publicationId, "sponsorId", sponsor, "purpose", "AUDIENCE")));
        CompletableFuture.allOf(refund, release).join();

        assertThat(((FeeProcesses.ItemOutput) refund.join()).outcome()).isEqualTo("REFUNDED");
        assertThat(notes(sponsor)).singleElement()
            .satisfies(note -> assertThat((BigDecimal) note.get("total")).isEqualByComparingTo("440"));
        assertThat(prepayments(publicationId).getFirst()).containsEntry("status", "RELEASED");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo(cashBefore.add(new BigDecimal("440")));
        assertThat(ledger("2180", sponsor)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** The note's PDF: issued the first time it is asked for, the same document after; another sponsor's not found. */
    @Test
    void aNotesPdfIsIssuedOnceAndOnlyItsSponsorReadsIt() {
        param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
        param(QbParams.FEE_MONTHLY, "1000");
        String sponsor = newSponsor(unique("s-fm7-pdf"));
        cash(sponsor, 2_000);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(sponsor).get("charge_id"), 300);
        String noteId = String.valueOf(notes(sponsor).getFirst().get("credit_note_id"));

        Map<String, Object> first = run(CreditNotes.DOCUMENT, sponsorToken(sponsor), body("creditNoteId", noteId));
        assertThat(first.get("documentRunId")).isNotNull();
        Map<String, Object> again = run(CreditNotes.DOCUMENT, sponsorToken(sponsor), body("creditNoteId", noteId));
        assertThat(again).containsEntry("documentRunId", first.get("documentRunId"))
            .containsEntry("documentNo", first.get("documentNo"));
        assertThat(query("SELECT count(*) AS n FROM sys_document_run WHERE layout_id = ?", QbCreditNotes.LAYOUT_ID)
            .getFirst().get("n")).satisfies(n -> assertThat(((Number) n).longValue()).isEqualTo(1));
        assertThat(query("SELECT document_run_id FROM qb_fee_credit_note_document WHERE credit_note_id = ?", noteId))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("document_run_id")))
                .isEqualTo(String.valueOf(first.get("documentRunId"))));

        String other = newSponsor(unique("s-fm7-pdf-other"));
        refused(CreditNotes.DOCUMENT, sponsorToken(other), body("creditNoteId", noteId), 404);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    static Map<String, Object> invoice(String invoiceId) {
        return query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId).getFirst();
    }

    /** The statuses of finance's tasks of a source (their latest versions; as FeeIT.openTasks). */
    static List<Object> taskStatuses(String source) {
        return query("SELECT status FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE assignee_permission = ? AND source_key = ? AND NOT is_deleted",
            QbPermissions.FEE_MANAGE, source).stream()
            .map(row -> row.get("status")).toList();
    }

    /** A lot of fee credit written as a grant writes it (as FeeAccountIT): posted, the lot, the wallet's cache. */
    String grant(String sponsor, long amount, Instant grantedAt, Instant expiresAt) {
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Granted credit (test)", "entries", List.of(
                body("accountCode", "5120", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2190", "direction", "CREDIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)))));
        String grantId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
            + "granted_at, expires_at, version) VALUES (?, ?, 'MANUAL', ?, ?, ?, ?, ?, 1)", grantId, sponsor,
            "test:" + grantId, amount, amount, Timestamp.from(grantedAt), Timestamp.from(expiresAt));
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, pending_out, "
            + "version) VALUES (?, ?, 'FEE_CREDIT', ?, 0, 0, 0, 1) ON CONFLICT (owner_id, kind) DO UPDATE SET "
            + "balance = qb_wallet.balance + EXCLUDED.balance, version = qb_wallet.version + 1",
            UUID.randomUUID().toString(), sponsor, amount);
        return grantId;
    }
}
