package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rewards are held back from transfers (Q10b, review M3): for {@code qb.transfer.hold-days} after they are paid, and
 * while their sponsor has an open dispute over a payment made before them; the rest is transferred as before. The
 * taker's wallet shows what is held and until when, by the same rule.
 */
class TransferHoldIT extends PayoutItSupport {

    private String paidTopUp(String sponsor, long amount) {
        String topUpId = openTopUp(sponsor, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    private Map<String, Object> dispute(String topUpId, String id, String status) {
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("id", id);
        dispute.put("object", "dispute");
        dispute.put("charge", "ch_" + topUpId.replace("-", ""));
        dispute.put("payment_intent", paymentOf(topUpId));
        dispute.put("amount", topUp(topUpId).get("amount"));
        dispute.put("currency", "jpy");
        dispute.put("status", status);
        dispute.put("livemode", false);
        return dispute;
    }

    private Map<String, Object> shownWallet(String userId) {
        // A token of now: the clock moves days.
        return items("qb.app.wallet", taker(userId), Map.of("params", Map.of("at", now().toString()))).getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void heldForTheHoldPeriodAndWhileTheSponsorIsDisputed() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        param(QbParams.TRANSFER_THRESHOLD, "100");
        param(QbParams.TRANSFER_HOLD_DAYS, "7");
        transfersOn();
        String disputed = newSponsor("s-hold-disputed");
        String clean = newSponsor("s-hold-clean");
        String disputedTopUp = paidTopUp(disputed, 5000);
        paidTopUp(clean, 5000);
        String fromDisputed = livePublication(disputed, "Hold disputed");
        String fromClean = livePublication(clean, "Hold clean");
        String taker = payee("t-hold");
        enabledPayee(taker);
        Instant paid = now();
        assertThat(play(taker, fromDisputed, true, true)).containsEntry("rewardStatus", "PAID");
        assertThat(play(taker, fromClean, true, true)).containsEntry("rewardStatus", "PAID");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("200");

        // Within the hold period nothing leaves, though the balance reaches the threshold.
        runTransfers();
        assertThat(transfersOf(taker)).isEmpty();
        Map<String, Object> shown = shownWallet(taker);
        assertThat(money(shown.get("available"))).isEqualByComparingTo("200");
        assertThat(money(shown.get("held"))).isEqualByComparingTo("200");
        assertThat(money(shown.get("heldOpen"))).isEqualByComparingTo("0");
        assertThat(Instant.parse(String.valueOf(shown.get("heldUntil"))))
            .isBetween(paid.plus(Duration.ofDays(7)), paid.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(5)));
        // Without its time the page is told nothing of holds.
        assertThat(items("qb.app.wallet", taker(taker), Map.of()).getFirst().get("held")).isNull();

        // A dispute of the sponsor's payment, made before the reward: that reward stays whatever its age.
        deliver(event(eventId(), "charge.dispute.created", dispute(disputedTopUp, "dp_hold", "needs_response")));
        assertThat(topUp(disputedTopUp)).containsEntry("dispute_status", "OPEN");
        advance(Duration.ofDays(6).plus(Duration.ofHours(23)));
        runTransfers();
        assertThat(transfersOf(taker)).as("day 7: still held").isEmpty();

        // Day 8: the clean sponsor's reward leaves; the disputed one's does not.
        advance(Duration.ofHours(1));
        runTransfers();
        List<Map<String, Object>> transfers = transfersOf(taker);
        assertThat(transfers).hasSize(1);
        assertThat(money(transfers.getFirst().get("amount"))).isEqualByComparingTo("100");
        runJobs();
        assertThat(transfer((String) transfers.getFirst().get("transfer_id"))).containsEntry("status", "SENT");
        shown = shownWallet(taker);
        assertThat(money(shown.get("available"))).isEqualByComparingTo("100");
        assertThat(money(shown.get("held"))).isEqualByComparingTo("100");
        assertThat(money(shown.get("heldOpen"))).isEqualByComparingTo("100");
        assertThat(shown.get("heldUntil")).isNull();
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(1);

        // The dispute is closed (won): the rest leaves at the next run.
        deliver(event(eventId(), "charge.dispute.closed", dispute(disputedTopUp, "dp_hold", "won")));
        runTransfers();
        transfers = transfersOf(taker);
        assertThat(transfers).hasSize(2);
        assertThat(transfers).extracting(t -> money(t.get("amount")))
            .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
            .containsExactlyInAnyOrder(new BigDecimal("100"), new BigDecimal("100"));
        assertThat(money(shownWallet(taker).get("held"))).isEqualByComparingTo("0");
        runJobs();
        assertTakerCacheIsTheLedger(taker);
        assertBooksBalance();
    }

    @Test
    void aDisputeOnlyHoldsRewardsPaidAfterTheDisputedPayment() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        param(QbParams.TRANSFER_THRESHOLD, "100");
        param(QbParams.TRANSFER_HOLD_DAYS, "7");
        transfersOn();
        String sponsor = newSponsor("s-hold-later");
        paidTopUp(sponsor, 5000);
        String publication = livePublication(sponsor, "Hold later");
        String taker = payee("t-hold-later");
        enabledPayee(taker);
        assertThat(play(taker, publication, true, true)).containsEntry("rewardStatus", "PAID");
        // A later top-up, then disputed: the reward was paid from before it.
        advance(Duration.ofHours(1));
        String later = paidTopUp(sponsor, 3000);
        deliver(event(eventId(), "charge.dispute.created", dispute(later, "dp_later", "needs_response")));
        advance(Duration.ofDays(8));
        runTransfers();
        assertThat(transfersOf(taker)).singleElement()
            .satisfies(t -> assertThat(money(t.get("amount"))).isEqualByComparingTo("100"));
    }

    @Test
    void aReviewedTransferIsNotSentWhenADisputeComesMeanwhile() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_THRESHOLD, "100");
        param(QbParams.TRANSFER_HOLD_DAYS, "7");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50");
        transfersOn();
        try {
            String sponsor = newSponsor("s-hold-review");
            String topUpId = paidTopUp(sponsor, 5000);
            String publication = livePublication(sponsor, "Hold review");
            String taker = payee("t-hold-review");
            enabledPayee(taker);
            assertThat(play(taker, publication, true, true)).containsEntry("rewardStatus", "PAID");
            deliverEvents();
            advance(Duration.ofDays(8));
            runTransfers();
            Map<String, Object> transfer = transfersOf(taker).getFirst();
            assertThat(transfer).containsEntry("status", "REVIEW");
            // The dispute comes while the transfer waits for its review: approved, it is not sent.
            deliver(event(eventId(), "charge.dispute.created", dispute(topUpId, "dp_review", "needs_response")));
            run("APPROVAL_DECIDE", transferReviewer(), body("requestId", transfer.get("approval_request_id"),
                "decision", "APPROVE"));
            deliverEvents();
            assertThat(transfer((String) transfer.get("transfer_id"))).containsEntry("status", "REJECTED");
            assertThat(ledger("2120", taker)).isEqualByComparingTo("100");
        } finally {
            param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        }
    }
}
