package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7, M2 of the review (§10.3-8): the monthly report's rows of credit notes, in a month of this class's own (its
 * schema holds nothing else): a prepaid fee paid from the account (given back to it), a postpaid fee (credit, nothing
 * given back) and an audience fee paid from its prepayment (given back through it). The note is FEE_CREDIT_NOTE; what
 * it gives back is FEE_PAID negative; out of the prepayment again is PREPAYMENT_RELEASED.
 */
class CreditNoteMonthlyIT extends CreditNoteItSupport {

    static final Instant FROM = Instant.parse("2031-05-01T00:00:00Z");
    static final Instant TO = Instant.parse("2031-06-01T00:00:00Z");

    @Test
    void theMonthlyReportHasTheNotesRowByRow() {
        clock.set(Instant.parse("2031-05-03T01:00:00Z"));
        param(QbParams.FEE_MONTHLY, "1000");
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        autoApprove(true);

        // A prepaid fee paid from the account: 1100 paid; a note of 400 and 40 back to the cash.
        String prepaid = newSponsor(unique("s-fm7-month-prepaid"));
        cash(prepaid, 2_000);
        assertThat(monthly(prepaid)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(prepaid).get("charge_id"), 400);

        // A postpaid fee: a note of 500 and 50, credit only.
        String postpaid = newSponsor(unique("s-fm7-month-postpaid"));
        billing(postpaid, "POSTPAID", 50_000);
        assertThat(monthly(postpaid)).isEqualTo("CHARGED");
        creditNote(monthlyCharge(postpaid).get("charge_id"), 500);

        // An audience fee paid from its prepayment (770), ended after three days: 440 back through the prepayment.
        String audience = pricedSponsor("fm7-month-audience");
        cash(audience, 1_000);
        credit(audience, 10_000);
        String publicationId = oneWinnerAudienceDraft(audience, "FM7 month");
        submit(sponsorToken(audience), publicationId);
        goLive(publicationId);
        clock.set(clock.instant().plus(java.time.Duration.ofDays(2)));
        play(womanTaker("t-fm7-month"), publicationId, true, true);
        Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
            .get("prepayment_id")), "publicationId", publicationId, "sponsorId", audience, "endedAt",
            clock.instant().toString());
        assertThat(((FeeProcesses.ItemOutput) systemRun(AudienceRefundJob.ITEM, ref)).outcome())
            .isEqualTo("REFUNDED");

        Map<String, Map<String, Object>> report = items("qb.admin.monthly", TestTokens.bearer(tokens,
            "fm7-monthly", QbPermissions.ADMIN_FINANCE_READ), Map.of("params", Map.of("from", FROM.toString(),
            "to", TO.toString()), "limit", 100)).stream()
            .collect(Collectors.toMap(row -> String.valueOf(row.get("category")), row -> row));
        // The notes: 440, 550 and 440; 1130 down by them, fee revenue and tax down by their amounts and taxes.
        assertThat(report.get("FEE_CREDIT_NOTE")).containsEntry("transactionCount", 3)
            .containsEntry("amount", 1430.0).containsEntry("feesReceivable", -1430.0)
            .containsEntry("feeRevenue", 1300.0).containsEntry("taxPayable", 130.0)
            .containsEntry("sponsorCash", 0).containsEntry("feePrepayments", 0);
        // FEE_PAID: the prepaid fee's 1100 paid from the cash, and what two notes gave back (negative): 440 to the
        // cash, 440 into the prepayment again. Cash 1100 - 440; 1130 -1100 + 440 + 440; 2180 -440.
        assertThat(report.get("FEE_PAID")).containsEntry("transactionCount", 3).containsEntry("amount", 1980.0)
            .containsEntry("sponsorCash", 660.0).containsEntry("feesReceivable", -220.0)
            .containsEntry("feePrepayments", -440.0);
        // Out of the prepayment again to the cash it was set aside from.
        assertThat(report.get("PREPAYMENT_RELEASED")).containsEntry("transactionCount", 1)
            .containsEntry("amount", 440.0).containsEntry("sponsorCash", -440.0).containsEntry("feePrepayments", 440.0);
        assertThat(report.get("PREPAYMENT_USED")).containsEntry("transactionCount", 1).containsEntry("amount", 770.0);
        // Every posting has its kind: OTHER holds only the test's postings of no source (the money it starts with).
        everyPostingHasItsKind();
    }
}
