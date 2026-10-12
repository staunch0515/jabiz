package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.wallet.LiveModeDataCheck;
import com.jabiz.quizbuks.wallet.StripeSettings;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A sponsor's top-up end to end (M-04; docs/quizbuks/plans/Q6-payments.md, D-Q6-2, D-Q6-3, D-Q6-5): started by the
 * sponsor, its checkout created by a job from the server's record, credited once on Stripe's signed completion at the
 * record's amount (debit 1110 / credit 2110 for the sponsor, booked when the payment happened), the pending rewards
 * paid in order and the underfunded publication back on, the fee read and posted (debit 5110 / credit 1110).
 */
class TopUpIT extends TopUpItSupport {

    @Test
    void aTopUpIsCreditedOncePaysThePendingRewardsAndRecordsTheFee() {
        String sponsor = newSponsor("s-topup");
        String publicationId = livePublication(sponsor, "Top-up week", Map.of("regions", List.of("GLOBAL")));
        // Enough for one top reward (the development credit); two start, the second's reward waits.
        credit(sponsor, 100);
        String first = newTaker("t-topup-1", null);
        String second = newTaker("t-topup-2", null);
        String firstAttempt = (String) start(taker(first), publicationId).get("attemptId");
        String secondAttempt = (String) start(taker(second), publicationId).get("attemptId");
        assertThat(submit(taker(first), firstAttempt, answers(true, true))).containsEntry("rewardStatus", "PAID");
        Map<String, Object> pending = submit(taker(second), secondAttempt, answers(true, true));
        assertThat(pending).containsEntry("rewardStatus", "PENDING");
        assertThat(status(publicationId)).isEqualTo("FUNDS_SHORT");

        // Started: opening, a job creating the checkout.
        Map<String, Object> started = run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 5000));
        String topUpId = (String) started.get("topUpId");
        assertThat(started).containsEntry("status", "OPENING").containsKey("jobId");
        assertThat(topUp(topUpId)).containsEntry("status", "OPENING").containsEntry("sponsor_id", sponsor);

        // The job creates the checkout from the record: the amount, the references, the return addresses.
        runJobs();
        StubHttpServer.Received create = STRIPE.requests(SESSIONS).getFirst();
        assertThat(create.method()).isEqualTo("POST");
        assertThat(create.header("Authorization")).isEqualTo("Bearer sk_test_test_only_quizbuks_key");
        assertThat(create.header("Stripe-Version")).isEqualTo("2025-03-31.basil");
        assertThat(create.header("Idempotency-Key")).isEqualTo("qb-checkout-" + topUpId);
        Map<String, String> form = form(create.body());
        assertThat(form).containsEntry("line_items[0][price_data][unit_amount]", "5000")
            .containsEntry("line_items[0][price_data][currency]", "jpy")
            .containsEntry("client_reference_id", topUpId)
            .containsEntry("metadata[sponsorId]", sponsor)
            .containsEntry("adaptive_pricing[enabled]", "false")
            .containsEntry("success_url", "https://sponsor.quizbuks.test/sponsor/wallet/topups/" + topUpId
                + "?checkout=paid");
        Map<String, Object> open = topUp(topUpId);
        assertThat(open).containsEntry("status", "OPEN");
        assertThat((String) open.get("checkout_url")).startsWith("https://checkout.stripe.com/c/pay/cs_test_");
        assertThat(instant(open.get("expires_at"))).isEqualTo(Instant.ofEpochSecond(
            Long.parseLong(form.get("expires_at"))));

        // The sponsor sees the address of the open checkout; nothing is credited by coming back from it.
        List<Map<String, Object>> mine = items("qb.sponsor.topups", sponsorToken(sponsor),
            Map.of("params", Map.of("at", now().toString())));
        assertThat(mine).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("checkoutUrl", open.get("checkout_url")).containsEntry("status", "OPEN"));

        // Stripe's signed completion: credited at the record's amount, once.
        Instant paidAt = now();
        advance(Duration.ofSeconds(30));
        Map<String, Object> event = event(eventId(), "checkout.session.completed", completed(topUpId));
        event.put("created", paidAt.getEpochSecond());
        deliver(event);
        Map<String, Object> paid = topUp(topUpId);
        assertThat(paid).containsEntry("status", "PAID").containsEntry("payment_intent_id", paymentOf(topUpId))
            .containsEntry("livemode", false);
        assertThat(instant(paid.get("paid_at"))).isEqualTo(paidAt);
        List<Map<String, Object>> lines = postingsOf(topUpId);
        assertThat(lines).hasSize(2);
        assertThat(money(lines.get(0).get("amount"))).isEqualByComparingTo("5000");
        assertThat(lines.get(0)).containsEntry("account_code", "1110").containsEntry("direction", "DEBIT")
            .containsEntry("description", "Top-up")
            .containsEntry("reference", paymentOf(topUpId));
        assertThat(lines.get(1)).containsEntry("account_code", "2110").containsEntry("direction", "CREDIT")
            .containsEntry("dimension_1", sponsor);
        assertThat(instant(lines.get(0).get("booking_time"))).isEqualTo(paidAt);
        assertThat(paid.get("ledger_transaction_id")).isEqualTo(String.valueOf(lines.get(0).get("transaction_id")));
        assertThat(outboxEvents("qb.topup.paid", "topUpId", topUpId)).isEqualTo(1);

        // The pending reward was paid (by event, as the system) and the publication is back on (5.3-2, 5.3-8).
        assertThat(attempt((String) pending.get("attemptId"))).containsEntry("reward_status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("4900");
        assertThat(status(publicationId)).isEqualTo("LIVE");
        assertBooksBalance();

        // The fee (F-04): the payment's balance transaction, read by a job, posted debit 5110 / credit 1110.
        STRIPE.script("/v1/payment_intents/" + paymentOf(topUpId), StubHttpServer.Answer.json(200, """
            {"id": "%s", "object": "payment_intent", "latest_charge": {"id": "ch_fee1", "object": "charge",
             "balance_transaction": {"id": "txn_fee1", "object": "balance_transaction", "amount": 5000,
             "fee": 180, "net": 4820, "currency": "jpy"}}}""".formatted(paymentOf(topUpId))));
        runJobs();
        StubHttpServer.Received read = STRIPE.requests("/v1/payment_intents/" + paymentOf(topUpId)).getFirst();
        assertThat(read.method()).isEqualTo("GET");
        assertThat(read.query()).isEqualTo("expand%5B%5D=latest_charge.balance_transaction");
        Map<String, Object> fee = topUp(topUpId);
        assertThat(fee).containsEntry("stripe_fee", new BigDecimal("180")).containsEntry("net_amount",
            new BigDecimal("4820")).containsEntry("charge_id", "ch_fee1").containsEntry("balance_transaction_id",
            "txn_fee1");
        List<Map<String, Object>> withFee = postingsOf(topUpId);
        assertThat(withFee).hasSize(4);
        assertThat(withFee.get(2)).containsEntry("account_code", "5110").containsEntry("direction", "DEBIT");
        assertThat(money(withFee.get(2).get("amount"))).isEqualByComparingTo("180");
        assertThat(withFee.get(3)).containsEntry("account_code", "1110").containsEntry("direction", "CREDIT");
        assertThat(fee.get("fee_transaction_id")).isEqualTo(String.valueOf(withFee.get(2).get("transaction_id")));

        // The wallet as the sponsor sees it.
        Map<String, Object> wallet = items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst();
        assertThat(money(wallet.get("balance"))).isEqualByComparingTo("4900");
        assertThat(money(wallet.get("toppedUp"))).isEqualByComparingTo("5000");
        assertThat(money(wallet.get("spent"))).isEqualByComparingTo("200");
        assertThat(money(wallet.get("pendingCount"))).isEqualByComparingTo("0");
        // The checkout of a paid top-up is no longer offered.
        assertThat(items("qb.sponsor.topups", sponsorToken(sponsor), Map.of()).getFirst().get("checkoutUrl")).isNull();
        assertBooksBalance();
    }

    @Test
    void theTakersWalletAndTransactionsShowPaidAndPendingRewards() {
        String sponsor = newSponsor("s-topup-taker");
        String publicationId = livePublication(sponsor, "Taker wallet", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 100);
        String taker = newTaker("t-topup-wallet", null);
        String other = newTaker("t-topup-wallet-2", null);
        String takerAttempt = (String) start(taker(taker), publicationId).get("attemptId");
        String otherAttempt = (String) start(taker(other), publicationId).get("attemptId");
        assertThat(submit(taker(taker), takerAttempt, answers(true, true))).containsEntry("rewardStatus", "PAID");
        assertThat(submit(taker(other), otherAttempt, answers(true, true))).containsEntry("rewardStatus", "PENDING");

        Map<String, Object> wallet = items("qb.app.wallet", taker(taker), Map.of()).getFirst();
        assertThat(money(wallet.get("available"))).isEqualByComparingTo("100");
        assertThat(money(wallet.get("totalEarned"))).isEqualByComparingTo("100");
        assertThat(money(wallet.get("pendingCount"))).isEqualByComparingTo("0");
        Map<String, Object> otherWallet = items("qb.app.wallet", taker(other), Map.of()).getFirst();
        assertThat(money(otherWallet.get("available"))).isEqualByComparingTo("0");
        assertThat(money(otherWallet.get("pendingAmount"))).isEqualByComparingTo("100");

        List<Map<String, Object>> ledger = items("qb.app.ledger", taker(taker), Map.of());
        assertThat(ledger).singleElement().satisfies(row -> assertThat(row).containsEntry("type", "REWARD")
            .containsEntry("status", "COMPLETED").containsEntry("name", "Taker wallet"));
        assertThat(items("qb.app.ledger", taker(other), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", "PROCESSING"));
        // Filters: keyword, status, amount.
        assertThat(items("qb.app.ledger", taker(taker), Map.of("params", Map.of("q", "nothing like it")))).isEmpty();
        assertThat(items("qb.app.ledger", taker(taker), Map.of("filters", List.of(Map.of("field", "status", "op",
            "eq", "value", "PROCESSING"))))).isEmpty();
        assertThat(items("qb.app.ledger", taker(taker), Map.of("filters", List.of(Map.of("field", "amount", "op",
            "gte", "value", 100))))).hasSize(1);
    }

    @Autowired
    Environment environment;
    @Autowired
    EntityDefinitionRegistry entityDefinitions;

    /** Going live on a database with test mode's top-ups and wallets fails the startup check, unless allowed. */
    @Test
    void goingLiveOnTestModesMoneyFailsTheStartupCheck() {
        String sponsor = newSponsor("s-topup-golive");
        deliver(event(eventId(), "checkout.session.completed", completed(openTopUp(sponsor, 1000))));
        StripeSettings live = new StripeSettings(true,
            "2025-03-31.basil", Duration.ofHours(1), null, "https://q.example.com/sponsor/");
        var refused = new LiveModeDataCheck(live, false, environment, entityDefinitions)
            .check();
        assertThat(refused).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(problem.message()).contains("test-mode top-up").doesNotContain(" 0 test-mode");
        });
        assertThat(new LiveModeDataCheck(live, true, environment, entityDefinitions)
            .check()).singleElement().satisfies(problem -> assertThat(problem.severity())
                .isEqualTo(CheckProblem.Severity.WARNING));
        // Test mode does not look.
        assertThat(new LiveModeDataCheck(new StripeSettings(
            false, "2025-03-31.basil", Duration.ofHours(1), null, "x"), false, environment,
            entityDefinitions).check()).isEmpty();
    }
}
