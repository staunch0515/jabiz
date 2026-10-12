package com.jabiz.quizbuks.it;

import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.wallet.TopUpChecks;
import com.jabiz.runtime.test.StubHttpServer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stripe's fee and the customer's currency (F-04; docs/quizbuks/plans/Q6-payments.md, D-Q6-5): after a credit a job
 * reads the payment's balance transaction; a fee that adds up is posted debit 5110 / credit 1110 (none when it is 0)
 * with the charge and the balance transaction; one that does not, or that cannot be read, is a task and posts
 * nothing; a second result changes nothing. A payment made in another currency keeps what the customer paid.
 */
class TopUpFeeIT extends TopUpItSupport {

    String paidTopUp(String sponsor, long amount) {
        String topUpId = openTopUp(sponsor, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    void scriptFee(String topUpId, String chargeId, long amount, long fee, long net, String currency) {
        STRIPE.script("/v1/payment_intents/" + paymentOf(topUpId), StubHttpServer.Answer.json(200, """
            {"id": "%s", "latest_charge": {"id": "%s", "balance_transaction": {"id": "txn_%s", "amount": %d,
             "fee": %d, "net": %d, "currency": "%s"}}}""".formatted(paymentOf(topUpId), chargeId,
            chargeId.substring(3), amount, fee, net, currency)));
    }

    /** Runs the fee's completion as the platform would, with a result of the test's making. */
    @SuppressWarnings("unchecked")
    void completeFee(String topUpId, TopUpChecks.Fee fee) {
        var definition = (ProcessDefinition<Object, Object, ProcessContext>) processes
            .findLatest("QB_TOPUP_FEE_RECORD").orElseThrow();
        asRequest(RequestContext.system(Locale.ENGLISH, "qb-it"), executor.execute(definition,
            new ExternalJobCompletion("job-test", "qb.stripe.payment-fee", "system", topUpId, fee, Map.of(), 1,
                now()))).block();
    }

    @Test
    void aFeeThatAddsUpIsPostedOnce() {
        String sponsor = newSponsor("s-fee-ok");
        String topUpId = paidTopUp(sponsor, 10_000);
        scriptFee(topUpId, "ch_feeok", 10_000, 360, 9_640, "jpy");
        runJobs();
        Map<String, Object> row = topUp(topUpId);
        assertThat(money(row.get("stripe_fee"))).isEqualByComparingTo("360");
        assertThat(postingsOf(topUpId)).filteredOn(line -> "5110".equals(line.get("account_code"))).singleElement()
            .satisfies(line -> assertThat(money(line.get("amount"))).isEqualByComparingTo("360"));
        // The same result again (another job of the same payment): nothing more.
        completeFee(topUpId, new TopUpChecks.Fee("ch_feeok", "txn_feeok", new BigDecimal("10000"),
            new BigDecimal("360"), new BigDecimal("9640"), "jpy"));
        assertThat(postingsOf(topUpId)).filteredOn(line -> "5110".equals(line.get("account_code"))).hasSize(1);
        // The sponsor's balance is not the fee's business: the platform pays it.
        assertThat(balance(sponsor)).isEqualByComparingTo("10000");
        assertBooksBalance();
    }

    @Test
    void aFeeOfZeroIsRecordedWithoutAPosting() {
        String topUpId = paidTopUp(newSponsor("s-fee-zero"), 1000);
        scriptFee(topUpId, "ch_feezero", 1000, 0, 1000, "jpy");
        runJobs();
        assertThat(money(topUp(topUpId).get("stripe_fee"))).isEqualByComparingTo("0");
        assertThat(topUp(topUpId).get("fee_transaction_id")).isNull();
        assertThat(postingsOf(topUpId)).noneMatch(line -> "5110".equals(line.get("account_code")));
    }

    @Test
    void aFeeThatDoesNotAddUpIsATask() {
        String topUpId = paidTopUp(newSponsor("s-fee-bad"), 1000);
        scriptFee(topUpId, "ch_feebad", 1000, 50, 900, "jpy");
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("stripe_fee", null).containsEntry("check_reason", "FEE_MISMATCH");
        assertThat(postingsOf(topUpId)).noneMatch(line -> "5110".equals(line.get("account_code")));
        assertThat(tasksAbout("topup:" + topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.topup-check.FEE_MISMATCH"));
    }

    @Test
    void aFeeThatCannotBeReadIsATask() {
        String topUpId = paidTopUp(newSponsor("s-fee-fails"), 1000);
        // Not found at Stripe: a permanent failure.
        runJobs();
        assertThat(topUp(topUpId)).containsEntry("stripe_fee", null);
        assertThat(tasksAbout("topup:" + topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("title_key", "task.qb.topup-check.FEE_FAILED"));
        // A charge without its balance transaction yet is asked again later.
        String later = paidTopUp(newSponsor("s-fee-later"), 1000);
        STRIPE.script("/v1/payment_intents/" + paymentOf(later), StubHttpServer.Answer.json(200,
            "{\"id\": \"x\", \"latest_charge\": {\"id\": \"ch_later\", \"balance_transaction\": null}}"));
        runJobs();
        scriptFee(later, "ch_later", 1000, 36, 964, "jpy");
        advance(Duration.ofMinutes(2));
        runJobs();
        assertThat(money(topUp(later).get("stripe_fee"))).isEqualByComparingTo("36");
    }

    @Test
    void aPaymentInAnotherCurrencyKeepsWhatTheCustomerPaid() {
        String sponsor = newSponsor("s-fee-fx");
        String topUpId = openTopUp(sponsor, 5000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId, s -> {
            s.put("currency", "usd");
            s.put("amount_total", 3412);
            s.put("amount_subtotal", 3412);
            s.put("currency_conversion", Map.of("source_currency", "jpy", "amount_total", 5000,
                "amount_subtotal", 5000, "fx_rate", "0.006824"));
        })));
        Map<String, Object> row = topUp(topUpId);
        assertThat(row).containsEntry("status", "PAID").containsEntry("presentment_currency", "usd");
        assertThat(money(row.get("presentment_amount"))).isEqualByComparingTo("3412");
        assertThat(money(row.get("fx_rate"))).isEqualByComparingTo("0.006824");
        // The ledger stays in yen, at the record's amount.
        assertThat(balance(sponsor)).isEqualByComparingTo("5000");
        assertBooksBalance();
    }
}
