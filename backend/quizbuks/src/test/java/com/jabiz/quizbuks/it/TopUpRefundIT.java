package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refunds and disputes made in Stripe's dashboard (docs/quizbuks/plans/Q6-payments.md, D-Q6-6): each yen refunded is
 * taken back from the sponsor's wallet once (debit 2110 / credit 1110), whatever the order and repetition of the
 * events, and never below zero (a task instead); a refund of a payment whose top-up is not confirmed yet is retried
 * until it is; a dispute is a task, a lost one is taken back as a refund, a won one is only recorded.
 */
class TopUpRefundIT extends TopUpItSupport {

    String paidTopUp(String sponsor, long amount) {
        String topUpId = openTopUp(sponsor, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    Map<String, Object> charge(String topUpId, long amount, long refunded) {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("id", "ch_" + topUpId.replace("-", ""));
        charge.put("object", "charge");
        charge.put("payment_intent", paymentOf(topUpId));
        charge.put("amount", amount);
        charge.put("amount_refunded", refunded);
        charge.put("currency", "jpy");
        charge.put("livemode", false);
        return charge;
    }

    Map<String, Object> dispute(String topUpId, String id, long amount, String status) {
        Map<String, Object> dispute = new LinkedHashMap<>();
        dispute.put("id", id);
        dispute.put("object", "dispute");
        dispute.put("charge", "ch_" + topUpId.replace("-", ""));
        dispute.put("payment_intent", paymentOf(topUpId));
        dispute.put("amount", amount);
        dispute.put("currency", "jpy");
        dispute.put("status", status);
        dispute.put("livemode", false);
        return dispute;
    }

    List<Map<String, Object>> clawbacks(String topUpId) {
        return postingsOf(topUpId).stream().filter(line -> "2110".equals(line.get("account_code"))
            && "DEBIT".equals(line.get("direction"))).toList();
    }

    @Test
    void refundsAreTakenBackOnceWhateverTheOrder() {
        String sponsor = newSponsor("s-refund");
        String topUpId = paidTopUp(sponsor, 5000);
        Map<String, Object> partial = event(eventId(), "charge.refunded", charge(topUpId, 5000, 1000));
        Map<String, Object> full = event(eventId(), "charge.refunded", charge(topUpId, 5000, 5000));
        deliver(partial);
        assertThat(balance(sponsor)).isEqualByComparingTo("4000");
        assertThat(money(topUp(topUpId).get("refunded_amount"))).isEqualByComparingTo("1000");
        deliver(full);
        assertThat(balance(sponsor)).isEqualByComparingTo("0");
        assertThat(clawbacks(topUpId)).hasSize(2).allSatisfy(line -> assertThat(line).containsEntry("dimension_1",
            sponsor));
        // The partial refund's event again (a duplicate with a new id, or late): nothing.
        deliver(event(eventId(), "charge.refunded", charge(topUpId, 5000, 1000)));
        deliver(event(eventId(), "charge.refunded", charge(topUpId, 5000, 5000)));
        assertThat(clawbacks(topUpId)).hasSize(2);
        assertThat(money(topUp(topUpId).get("refunded_amount"))).isEqualByComparingTo("5000");
        assertThat(balance(sponsor)).isEqualByComparingTo("0");
        assertBooksBalance();
    }

    @Test
    void aRefundTheWalletCannotCoverIsATaskAndPostsNothing() {
        String sponsor = newSponsor("s-refund-short");
        String topUpId = paidTopUp(sponsor, 1000);
        // The Kudos were paid out as rewards.
        String publicationId = livePublication(sponsor, "Spent", Map.of("regions", List.of("GLOBAL")));
        for (int i = 0; i < 5; i++) {
            assertThat(play(newTaker("t-refund-short-" + i, null), publicationId, true, true))
                .containsEntry("rewardStatus", "PAID");
        }
        assertThat(balance(sponsor)).isEqualByComparingTo("500");
        Map<String, Object> refund = event(eventId(), "charge.refunded", charge(topUpId, 1000, 1000));
        deliver(refund);
        assertThat(balance(sponsor)).isEqualByComparingTo("500");
        assertThat(clawbacks(topUpId)).isEmpty();
        Map<String, Object> row = topUp(topUpId);
        assertThat(money(row.get("refunded_amount"))).isEqualByComparingTo("0");
        assertThat(money(row.get("refund_shortfall"))).isEqualByComparingTo("1000");
        assertThat(tasksAbout("stripe:" + refund.get("id"))).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.refund-check.REFUND_SHORTFALL").containsEntry("subject_id",
                topUpId));
        assertBooksBalance();
    }

    @Test
    void aRefundBeforeItsTopUpsConfirmationWaitsForIt() {
        String sponsor = newSponsor("s-refund-early");
        String topUpId = openTopUp(sponsor, 2000);
        Map<String, Object> confirmation = event(eventId(), "checkout.session.completed", completed(topUpId));
        // The refund is processed first: it fails, and is tried again.
        deliver(event(eventId(), "charge.refunded", charge(topUpId, 2000, 500)));
        assertThat(query("SELECT 1 FROM sys_outbox_attempt WHERE consumer = 'qb.topup-refund'")).isNotEmpty();
        deliver(confirmation);
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");
        advance(Duration.ofMinutes(1));
        deliverEvents();
        assertThat(balance(sponsor)).isEqualByComparingTo("1500");
        assertThat(clawbacks(topUpId)).hasSize(1);
        // A refund of a payment no top-up has, still unknown after the wait: a task.
        Map<String, Object> stray = charge(topUpId, 2000, 100);
        stray.put("payment_intent", "pi_unknown_payment");
        Map<String, Object> event = event(eventId(), "charge.refunded", stray);
        deliver(event);
        assertThat(tasksAbout("stripe:" + event.get("id"))).isEmpty();
        advance(Duration.ofMinutes(31));
        deliverEvents();
        assertThat(tasksAbout("stripe:" + event.get("id"))).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.refund-check.UNKNOWN_PAYMENT"));
        assertBooksBalance();
    }

    @Test
    void refundsThatAreNotBelievableAreTasks() {
        String sponsor = newSponsor("s-refund-odd");
        String topUpId = paidTopUp(sponsor, 1000);
        Map<String, Object> over = event(eventId(), "charge.refunded", charge(topUpId, 1000, 1001));
        deliver(over);
        Map<String, Object> usd = charge(topUpId, 1000, 10);
        usd.put("currency", "usd");
        Map<String, Object> currency = event(eventId(), "charge.refunded", usd);
        deliver(currency);
        Map<String, Object> live = event(eventId(), "charge.refunded", charge(topUpId, 1000, 10));
        live.put("livemode", true);
        deliver(live);
        assertThat(tasksAbout("stripe:" + over.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.OVER_REFUND");
        assertThat(tasksAbout("stripe:" + currency.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.CURRENCY");
        assertThat(tasksAbout("stripe:" + live.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.LIVEMODE");
        assertThat(clawbacks(topUpId)).isEmpty();
        assertThat(balance(sponsor)).isEqualByComparingTo("1000");
    }

    @Test
    void disputesAreTasksAndALostOneIsTakenBack() {
        String sponsor = newSponsor("s-dispute");
        String lost = paidTopUp(sponsor, 3000);
        String won = paidTopUp(sponsor, 2000);
        Map<String, Object> opened = event(eventId(), "charge.dispute.created", dispute(lost, "dp_lost", 3000,
            "needs_response"));
        deliver(opened);
        assertThat(topUp(lost)).containsEntry("dispute_status", "OPEN").containsEntry("dispute_id", "dp_lost");
        assertThat(tasksAbout("stripe:" + opened.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.DISPUTE_OPENED");
        assertThat(balance(sponsor)).isEqualByComparingTo("5000");
        deliver(event(eventId(), "charge.dispute.closed", dispute(lost, "dp_lost", 3000, "lost")));
        assertThat(topUp(lost)).containsEntry("dispute_status", "LOST");
        assertThat(money(topUp(lost).get("dispute_clawback"))).isEqualByComparingTo("3000");
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");
        // The outcome again: nothing more.
        deliver(event(eventId(), "charge.dispute.closed", dispute(lost, "dp_lost", 3000, "lost")));
        assertThat(clawbacks(lost)).hasSize(1);
        // A won dispute (its outcome before its opening: events come in any order) is only recorded.
        deliver(event(eventId(), "charge.dispute.closed", dispute(won, "dp_won", 2000, "won")));
        deliver(event(eventId(), "charge.dispute.created", dispute(won, "dp_won", 2000, "needs_response")));
        assertThat(topUp(won)).containsEntry("dispute_status", "WON");
        assertThat(clawbacks(won)).isEmpty();
        // A second dispute of a charge: a task, nothing changed.
        Map<String, Object> second = event(eventId(), "charge.dispute.created", dispute(won, "dp_other", 100,
            "needs_response"));
        deliver(second);
        assertThat(tasksAbout("stripe:" + second.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.DISPUTE_MISMATCH");
        assertThat(balance(sponsor)).isEqualByComparingTo("2000");
        assertBooksBalance();
    }

    @Test
    void aLostDisputeTheWalletCannotCoverIsATask() {
        String sponsor = newSponsor("s-dispute-short");
        String topUpId = paidTopUp(sponsor, 1000);
        String publicationId = livePublication(sponsor, "Spent too", Map.of("regions", List.of("GLOBAL")));
        assertThat(play(newTaker("t-dispute-short", null), publicationId, true, true))
            .containsEntry("rewardStatus", "PAID");
        Map<String, Object> closed = event(eventId(), "charge.dispute.closed", dispute(topUpId, "dp_short", 1000,
            "lost"));
        deliver(closed);
        assertThat(topUp(topUpId)).containsEntry("dispute_status", "LOST");
        assertThat(money(topUp(topUpId).get("dispute_clawback"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balance(sponsor)).isEqualByComparingTo("900");
        assertThat(tasksAbout("stripe:" + closed.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.refund-check.DISPUTE_SHORTFALL");
        assertBooksBalance();
    }
}
