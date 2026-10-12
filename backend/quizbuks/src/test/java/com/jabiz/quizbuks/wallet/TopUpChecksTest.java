package com.jabiz.quizbuks.wallet;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class TopUpChecksTest {

    static final String TOPUP = "01890000-0000-7000-8000-000000000001";
    static final String SPONSOR = "01890000-0000-7000-8000-0000000000aa";
    static final String SESSION = "cs_test_a1B2c3";

    static TopUpChecks.TopUpView topUp(String status, String session) {
        return new TopUpChecks.TopUpView(TOPUP, SPONSOR, new BigDecimal("5000"), status, session);
    }

    /** A paid session of the top-up as Stripe sends it, with changes. */
    static StripeObjects.CheckoutSession session(UnaryOperator<Map<String, Object>> change) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", SESSION);
        o.put("object", "checkout.session");
        o.put("status", "complete");
        o.put("payment_status", "paid");
        o.put("mode", "payment");
        o.put("currency", "jpy");
        o.put("amount_total", new BigDecimal("5000"));
        o.put("amount_subtotal", new BigDecimal("5000"));
        o.put("client_reference_id", TOPUP);
        o.put("metadata", Map.of("topUpId", TOPUP, "sponsorId", SPONSOR));
        o.put("payment_intent", "pi_123");
        o.put("livemode", false);
        return StripeObjects.session(change.apply(o));
    }

    static StripeObjects.CheckoutSession paid() {
        return session(o -> o);
    }

    static String reason(TopUpChecks.Verdict verdict) {
        assertThat(verdict.outcome()).isEqualTo(TopUpChecks.Outcome.CHECK);
        return verdict.reason();
    }

    @Test
    void creditsOnlyWhatFitsInEveryRespect() {
        assertThat(TopUpChecks.paid(topUp(TopUpStatus.OPEN, SESSION), paid(), false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.CREDIT);
        // Before the job's result was applied: the session is not recorded yet, the references decide.
        assertThat(TopUpChecks.paid(topUp(TopUpStatus.OPENING, null), paid(), false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.CREDIT);
        // Stripe's answer to a check carries no event mode.
        assertThat(TopUpChecks.paid(topUp(TopUpStatus.OPEN, SESSION), paid(), false, null).outcome())
            .isEqualTo(TopUpChecks.Outcome.CREDIT);
    }

    @Test
    void theSameSessionAgainIsDone() {
        assertThat(TopUpChecks.paid(topUp(TopUpStatus.PAID, SESSION), paid(), false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.ALREADY);
    }

    @Test
    void everyMismatchIsAReasonNotACredit() {
        TopUpChecks.TopUpView open = topUp(TopUpStatus.OPEN, SESSION);
        assertThat(reason(TopUpChecks.paid(null, paid(), false, false))).isEqualTo(TopUpChecks.UNKNOWN_TOPUP);
        assertThat(reason(TopUpChecks.paid(open, paid(), false, true))).isEqualTo(TopUpChecks.LIVEMODE);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "livemode", true)), false, false)))
            .isEqualTo(TopUpChecks.LIVEMODE);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "id", "cs_live_x")), false, false)))
            .isEqualTo(TopUpChecks.LIVEMODE);
        assertThat(reason(TopUpChecks.paid(open, paid(), true, true))).isEqualTo(TopUpChecks.LIVEMODE);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "id", "cs_test_other")), false, false)))
            .isEqualTo(TopUpChecks.SESSION_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "client_reference_id",
            "01890000-0000-7000-8000-000000000002")), false, false))).isEqualTo(TopUpChecks.SESSION_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "metadata", Map.of("topUpId", TOPUP,
            "sponsorId", "someone-else"))), false, false))).isEqualTo(TopUpChecks.SESSION_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "metadata", Map.of())), false, false)))
            .isEqualTo(TopUpChecks.SESSION_MISMATCH);
        assertThat(reason(TopUpChecks.paid(topUp(TopUpStatus.EXPIRED, SESSION), paid(), false, false)))
            .isEqualTo(TopUpChecks.STATUS);
        assertThat(reason(TopUpChecks.paid(topUp(TopUpStatus.FAILED, SESSION), paid(), false, false)))
            .isEqualTo(TopUpChecks.STATUS);
        // Paid with another session: a second payment for one top-up.
        assertThat(reason(TopUpChecks.paid(topUp(TopUpStatus.PAID, "cs_test_first"), paid(), false, false)))
            .isEqualTo(TopUpChecks.SESSION_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "mode", "setup")), false, false)))
            .isEqualTo(TopUpChecks.NOT_PAYMENT);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "payment_status", "unpaid")), false, false)))
            .isEqualTo(TopUpChecks.NOT_PAID);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "status", "open")), false, false)))
            .isEqualTo(TopUpChecks.NOT_PAID);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "payment_intent", null)), false, false)))
            .isEqualTo(TopUpChecks.NO_PAYMENT_INTENT);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "currency", "usd")), false, false)))
            .isEqualTo(TopUpChecks.CURRENCY);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "amount_total", new BigDecimal("4999"))),
            false, false))).isEqualTo(TopUpChecks.AMOUNT_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "amount_subtotal", new BigDecimal("6000"))),
            false, false))).isEqualTo(TopUpChecks.AMOUNT_MISMATCH);
        assertThat(reason(TopUpChecks.paid(open, session(o -> put(o, "amount_total", "5000")), false, false)))
            .isEqualTo(TopUpChecks.AMOUNT_MISMATCH);
    }

    @Test
    void readsBothShapesOfAConvertedPayment() {
        TopUpChecks.TopUpView open = topUp(TopUpStatus.OPEN, SESSION);
        // Before 2025-03-31.basil: the session in the customer's currency, the original in currency_conversion.
        StripeObjects.CheckoutSession converted = session(o -> {
            o.put("currency", "usd");
            o.put("amount_total", new BigDecimal("3412"));
            o.put("amount_subtotal", new BigDecimal("3412"));
            o.put("currency_conversion", Map.of("source_currency", "jpy", "amount_total", new BigDecimal("5000"),
                "amount_subtotal", new BigDecimal("5000"), "fx_rate", "0.006824"));
            return o;
        });
        assertThat(TopUpChecks.paid(open, converted, false, false).outcome()).isEqualTo(TopUpChecks.Outcome.CREDIT);
        assertThat(converted.presentmentCurrency()).isEqualTo("usd");
        assertThat(converted.presentmentAmount()).isEqualByComparingTo("3412");
        assertThat(converted.fxRate()).isEqualByComparingTo("0.006824");
        // From that version: the session in yen, the customer's amount in presentment_details.
        StripeObjects.CheckoutSession presented = session(o -> put(o, "presentment_details",
            Map.of("presentment_currency", "eur", "presentment_amount", new BigDecimal("3105"))));
        assertThat(TopUpChecks.paid(open, presented, false, false).outcome()).isEqualTo(TopUpChecks.Outcome.CREDIT);
        assertThat(presented.presentmentCurrency()).isEqualTo("eur");
        // A conversion whose original is not the record's amount is refused like any other.
        StripeObjects.CheckoutSession short1 = session(o -> {
            o.put("currency", "usd");
            o.put("currency_conversion", Map.of("source_currency", "jpy", "amount_total", new BigDecimal("500"),
                "amount_subtotal", new BigDecimal("500")));
            return o;
        });
        assertThat(reason(TopUpChecks.paid(open, short1, false, false))).isEqualTo(TopUpChecks.AMOUNT_MISMATCH);
    }

    @Property
    void noAmountButTheRecordsIsEverCredited(@ForAll @LongRange(min = -10, max = 2_000_000) long paid,
        @ForAll @LongRange(min = 1, max = 1_000_000) long recorded) {
        TopUpChecks.TopUpView record = new TopUpChecks.TopUpView(TOPUP, SPONSOR, BigDecimal.valueOf(recorded),
            TopUpStatus.OPEN, SESSION);
        StripeObjects.CheckoutSession session = session(o -> {
            o.put("amount_total", BigDecimal.valueOf(paid));
            o.put("amount_subtotal", BigDecimal.valueOf(paid));
            return o;
        });
        TopUpChecks.Verdict verdict = TopUpChecks.paid(record, session, false, false);
        assertThat(verdict.outcome() == TopUpChecks.Outcome.CREDIT).isEqualTo(paid == recorded);
    }

    @Test
    void expiresOnlyAnUnsettledTopUpOfTheSameSession() {
        StripeObjects.CheckoutSession expired = session(o -> {
            o.put("status", "expired");
            o.put("payment_status", "unpaid");
            return o;
        });
        assertThat(TopUpChecks.expired(topUp(TopUpStatus.OPEN, SESSION), expired, false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.EXPIRE);
        assertThat(TopUpChecks.expired(topUp(TopUpStatus.OPENING, null), expired, false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.EXPIRE);
        assertThat(TopUpChecks.expired(topUp(TopUpStatus.EXPIRED, SESSION), expired, false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.ALREADY);
        assertThat(TopUpChecks.expired(topUp(TopUpStatus.FAILED, SESSION), expired, false, false).outcome())
            .isEqualTo(TopUpChecks.Outcome.ALREADY);
        assertThat(reason(TopUpChecks.expired(topUp(TopUpStatus.PAID, SESSION), expired, false, false)))
            .isEqualTo(TopUpChecks.STATUS);
        assertThat(reason(TopUpChecks.expired(topUp(TopUpStatus.OPEN, SESSION), paid(), false, false)))
            .isEqualTo(TopUpChecks.STATUS);
        assertThat(reason(TopUpChecks.expired(topUp(TopUpStatus.OPEN, "cs_test_other"), expired, false, false)))
            .isEqualTo(TopUpChecks.SESSION_MISMATCH);
    }

    @Test
    void opensOnlyACheckoutThatFitsTheRecord() {
        TopUpChecks.TopUpView opening = topUp(TopUpStatus.OPENING, null);
        String prefix = StripeSettings.STRIPE_CHECKOUT;
        StripeObjects.CheckoutSession created = session(o -> {
            o.put("status", "open");
            o.put("payment_status", "unpaid");
            o.put("url", "https://checkout.stripe.com/c/pay/" + SESSION);
            o.put("expires_at", new BigDecimal("1791000000"));
            return o;
        });
        assertThat(TopUpChecks.openProblem(opening, created, false, prefix)).isNull();
        assertThat(TopUpChecks.openProblem(null, created, false, prefix)).isEqualTo(TopUpChecks.UNKNOWN_TOPUP);
        assertThat(TopUpChecks.openProblem(opening, created, true, prefix)).isEqualTo(TopUpChecks.LIVEMODE);
        assertThat(TopUpChecks.openProblem(opening, session(o -> {
            o.put("url", "https://evil.example.com/pay");
            o.put("expires_at", new BigDecimal("1791000000"));
            return o;
        }), false, prefix)).isEqualTo(TopUpChecks.OPEN_MISMATCH);
        assertThat(TopUpChecks.openProblem(opening, session(o -> {
            o.put("url", "https://checkout.stripe.com/c/pay/x y");
            o.put("expires_at", new BigDecimal("1791000000"));
            return o;
        }), false, prefix)).isEqualTo(TopUpChecks.OPEN_MISMATCH);
        assertThat(TopUpChecks.openProblem(opening, session(o -> put(o, "url",
            "https://checkout.stripe.com/c/pay/1")), false, prefix)).isEqualTo(TopUpChecks.OPEN_MISMATCH);
        assertThat(TopUpChecks.openProblem(opening, session(o -> {
            o.put("url", "https://checkout.stripe.com/c/pay/1");
            o.put("expires_at", new BigDecimal("1791000000"));
            o.put("amount_total", new BigDecimal("50"));
            return o;
        }), false, prefix)).isEqualTo(TopUpChecks.OPEN_MISMATCH);
    }

    @Test
    void recordsAFeeThatAddsUpOnly() {
        TopUpChecks.TopUpView paid = topUp(TopUpStatus.PAID, SESSION);
        TopUpChecks.Fee fee = new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"), new BigDecimal("180"),
            new BigDecimal("4820"), "jpy");
        assertThat(TopUpChecks.feeProblem(paid, fee, "jpy")).isNull();
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"),
            BigDecimal.ZERO, new BigDecimal("5000"), "jpy"), "jpy")).isNull();
        assertThat(TopUpChecks.feeProblem(topUp(TopUpStatus.OPEN, SESSION), fee, "jpy"))
            .isEqualTo(TopUpChecks.STATUS);
        assertThat(TopUpChecks.feeProblem(null, fee, "jpy")).isEqualTo(TopUpChecks.UNKNOWN_TOPUP);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"),
            new BigDecimal("180"), new BigDecimal("4800"), "jpy"), "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("4000"),
            new BigDecimal("180"), new BigDecimal("3820"), "jpy"), "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"),
            new BigDecimal("180"), new BigDecimal("4820"), "usd"), "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"),
            new BigDecimal("-1"), new BigDecimal("5001"), "jpy"), "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("x", "txn_1", new BigDecimal("5000"),
            new BigDecimal("180"), new BigDecimal("4820"), "jpy"), "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, null, "jpy")).isEqualTo(TopUpChecks.FEE_MISMATCH);
    }

    @Test
    void theSettlementCurrencyIsASetting() {
        TopUpChecks.TopUpView paid = topUp(TopUpStatus.PAID, SESSION);
        // Settled in dollars (a Stripe account outside Japan): the expected currency, but not yen, so the fee and the
        // net cannot be checked against the payment nor posted in the yen ledger: a person books them.
        TopUpChecks.Fee usd = new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("3350"), new BigDecimal("127"),
            new BigDecimal("3223"), "usd");
        assertThat(TopUpChecks.feeProblem(paid, usd, "usd")).isEqualTo(TopUpChecks.FEE_CURRENCY);
        // Another currency than the setting's is a mismatch, yen included.
        assertThat(TopUpChecks.feeProblem(paid, usd, "eur")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.feeProblem(paid, new TopUpChecks.Fee("ch_1", "txn_1", new BigDecimal("5000"),
            new BigDecimal("180"), new BigDecimal("4820"), "jpy"), "usd")).isEqualTo(TopUpChecks.FEE_MISMATCH);
        assertThat(TopUpChecks.REASONS).contains(TopUpChecks.FEE_CURRENCY);
    }

    @Test
    void everyReasonIsListed() {
        assertThat(TopUpChecks.REASONS).doesNotHaveDuplicates().contains(TopUpChecks.UNKNOWN_TOPUP,
            TopUpChecks.FEE_FAILED, TopUpChecks.CHECK_FAILED, TopUpChecks.OPEN_MISMATCH);
    }

    private static Map<String, Object> put(Map<String, Object> o, String key, Object value) {
        o.put(key, value);
        return o;
    }
}
