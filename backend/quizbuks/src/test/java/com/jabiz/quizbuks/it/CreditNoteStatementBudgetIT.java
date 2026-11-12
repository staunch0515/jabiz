package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.AudienceRefundJob;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M7 (docs/quizbuks/plans/impl/F-M.md §10.3-6, N11): how many statements the refund job's item and a credit note by
 * hand send. Each case runs once before it is counted (warm caches), as {@link DistributionStatementBudgetIT}. A change
 * of these paths changes the numbers on purpose: say why in the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class CreditNoteStatementBudgetIT extends CreditNoteItSupport {

    /**
     * The item of a prepaid audience fee that ended early, given back through its prepayment [24]: the lock, the switch
     * at the end, one read of the prepayment, publication and fee; the note's three reads (fee, invoices, notes, uses
     * and wallets; the payments; their lots), its number, its writes and postings (the note, what it gives back, out of
     * the prepayment), the lots and wallets, and the decision on the prepayment.
     */
    static final int REFUND_ITEM = 24;
    /**
     * The approval of a credit note by hand on a prepaid fee paid from the account, given back to it [27]: the approval
     * result's reads (the request, the sponsor, the approval, the fee, its invoice and notes in one), then the note as
     * above, and the request's outcome.
     */
    static final int MANUAL_NOTE = 27;

    /** The statements of the one transaction {@code action} wrote a credit note in. */
    static List<String> issued(Runnable action) {
        SqlStatementLog.clear();
        action.run();
        List<List<String>> issuing = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.contains("INSERT INTO qb_fee_credit_note_version ("))).toList();
        assertThat(issuing).as("one transaction wrote the note").hasSize(1);
        return issuing.getFirst();
    }

    @Test
    void theRefundItemAndANoteByHandSendAsManyAsCounted() {
        autoApprove(true);
        param(QbParams.AUDIENCE_REFUND_UNUSED_DAYS, "true");
        param(QbParams.FEE_MONTHLY, "1000");
        List<String> refund = null;
        List<String> manual = null;
        for (int i = 0; i < 2; i++) {
            String sponsor = pricedSponsor("fm7-budget-" + i);
            cash(sponsor, 3_000);
            credit(sponsor, 10_000);
            String publicationId = oneWinnerAudienceDraft(sponsor, "FM7 budget " + i);
            submit(sponsorToken(sponsor), publicationId);
            goLive(publicationId);
            clock.set(clock.instant().plus(Duration.ofDays(2)));
            play(womanTaker("t-fm7-budget"), publicationId, true, true);
            Map<String, Object> ref = body("prepaymentId", String.valueOf(prepayments(publicationId).getFirst()
                .get("prepayment_id")), "publicationId", publicationId, "sponsorId", sponsor, "endedAt",
                clock.instant().toString());
            refund = issued(() -> systemRun(AudienceRefundJob.ITEM, ref));

            assertThat(monthly(sponsor)).isEqualTo("CHARGED");
            Map<String, Object> request = run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_CREDIT_NOTE,
                "chargeId", String.valueOf(monthlyCharge(sponsor).get("charge_id")), "amount", 100, "reason",
                "Down a day"));
            manual = issued(() -> {
                run("APPROVAL_DECIDE", content(), body("requestId", request.get("approvalRequestId"), "decision",
                    "APPROVE"));
                deliverEvents();
            });
        }
        DistributionStatementBudgetIT.print("refund item", refund);
        DistributionStatementBudgetIT.print("note by hand", manual);
        assertThat(refund).hasSize(REFUND_ITEM);
        assertThat(manual).hasSize(MANUAL_NOTE);
    }
}
