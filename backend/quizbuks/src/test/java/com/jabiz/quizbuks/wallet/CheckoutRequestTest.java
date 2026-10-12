package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutRequestTest {

    static final String TOPUP = "01890000-0000-7000-8000-000000000001";
    static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    static final StripeSettings SETTINGS = new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), null,
        "https://quizbuks.example.com/sponsor/");

    @Test
    void theAmountAndReferencesComeFromTheRecordOnly() {
        CheckoutRequest.CheckoutInput input = CheckoutRequest.input(TOPUP, "sponsor-1", new BigDecimal("5000"), NOW,
            SETTINGS);
        StripeForm form = CheckoutRequest.form(input);
        assertThat(form.get("mode")).isEqualTo("payment");
        assertThat(form.get("line_items[0][price_data][currency]")).isEqualTo("jpy");
        assertThat(form.get("line_items[0][price_data][unit_amount]")).isEqualTo("5000");
        assertThat(form.get("line_items[0][quantity]")).isEqualTo("1");
        assertThat(form.get("client_reference_id")).isEqualTo(TOPUP);
        assertThat(form.get("metadata[topUpId]")).isEqualTo(TOPUP);
        assertThat(form.get("metadata[sponsorId]")).isEqualTo("sponsor-1");
        assertThat(form.get("payment_intent_data[metadata][topUpId]")).isEqualTo(TOPUP);
        assertThat(form.get("payment_method_types[0]")).isEqualTo("card");
        assertThat(form.get("allow_promotion_codes")).isEqualTo("false");
        // Yen only (D-Q6-5): Stripe does not offer the customer their own currency.
        assertThat(form.get("adaptive_pricing[enabled]")).isEqualTo("false");
        // Nothing of the customer: Stripe collects it on its own page.
        assertThat(form.fields()).noneMatch(f -> f.key().startsWith("customer"));
    }

    @Test
    void theReturnAddressesComeFromTheConfigurationOnly() {
        CheckoutRequest.CheckoutInput input = CheckoutRequest.input(TOPUP, "s", BigDecimal.TEN, NOW, SETTINGS);
        assertThat(input.successUrl()).isEqualTo("https://quizbuks.example.com/sponsor/wallet/topups/" + TOPUP
            + "?checkout=paid");
        assertThat(input.cancelUrl()).startsWith("https://quizbuks.example.com/sponsor/wallet/topups/" + TOPUP);
        assertThat(CheckoutRequest.returnUrl("https://q.example.com/sponsor", TOPUP, "paid"))
            .isEqualTo("https://q.example.com/sponsor/wallet/topups/" + TOPUP + "?checkout=paid");
        assertThat(CheckoutRequest.form(input).get("success_url")).isEqualTo(input.successUrl());
    }

    @Test
    void theCheckoutExpiresWithinWhatStripeAccepts() {
        CheckoutRequest.CheckoutInput input = CheckoutRequest.input(TOPUP, "s", BigDecimal.TEN, NOW, SETTINGS);
        assertThat(input.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(CheckoutRequest.form(input).get("expires_at"))
            .isEqualTo(String.valueOf(NOW.plus(Duration.ofHours(1)).getEpochSecond()));
        // Stripe takes 30 minutes to 24 hours; the job may start a few minutes late.
        assertThat(StripeSettings.MIN_TTL).isGreaterThan(Duration.ofMinutes(30));
        assertThat(StripeSettings.MAX_TTL).isLessThan(Duration.ofHours(24));
        assertThat(new StripeSettings(false, "2025-03-31.basil", Duration.ofMinutes(31), null, "x").problems())
            .anyMatch(p -> p.startsWith("quizbuks.stripe.checkout-ttl"));
        assertThat(new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(24), null, "x").problems())
            .anyMatch(p -> p.startsWith("quizbuks.stripe.checkout-ttl"));
        assertThat(SETTINGS.problems()).isEmpty();
    }

    @Test
    void theSettlementCurrencyIsYenUnlessSetAndACurrencyCode() {
        assertThat(SETTINGS.settlementCurrency()).isEqualTo("jpy");
        StripeSettings usd = new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), null, "x", " USD ");
        assertThat(usd.settlementCurrency()).isEqualTo("usd");
        assertThat(usd.problems()).isEmpty();
        assertThat(new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), null, "x", "dollars")
            .problems()).anyMatch(p -> p.startsWith("quizbuks.stripe.settlement-currency"));
        assertThat(new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), null, "x", "")
            .settlementCurrency()).isEqualTo("jpy");
    }

    @Test
    void theIdempotencyKeyIsTheTopUpsOwn() {
        assertThat(CheckoutRequest.idempotencyKey(TOPUP)).isEqualTo("qb-checkout-" + TOPUP);
    }
}
