package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Checkout Session of a top-up (docs/quizbuks/plans/Q6-payments.md, D-Q6-2), made from the server's record only:
 * the amount, the currency, the references and the return addresses never come from the client, so a sponsor can
 * neither pay less than the record says nor be sent anywhere but the sponsor console (no open redirect). The browser's
 * return is only for showing the state: nothing is credited because of it.
 */
public final class CheckoutRequest {

    /** The product's name on Stripe's page; Kudos are the only thing sold. */
    static final String PRODUCT_NAME = "QuizBuks Kudos";

    /**
     * The input of the job that creates the checkout ({@code qb.stripe.checkout-create}); stored with the job, so it
     * holds no secret.
     *
     * @param amount    whole yen (Kudos)
     * @param expiresAt when the checkout expires
     */
    public record CheckoutInput(String topUpId, String sponsorId, BigDecimal amount, Instant expiresAt,
        String successUrl, String cancelUrl) {}

    /** The job's input for a new top-up at {@code now}. */
    public static CheckoutInput input(String topUpId, String sponsorId, BigDecimal amount, Instant now,
        StripeSettings settings) {
        return new CheckoutInput(topUpId, sponsorId, amount, now.plus(settings.checkoutTtl()),
            returnUrl(settings.sponsorLink(), topUpId, "paid"), returnUrl(settings.sponsorLink(), topUpId, "cancelled"));
    }

    /** {@code <sponsor console>/wallet/topups/<topUpId>?checkout=<outcome>}. */
    public static String returnUrl(String sponsorLink, String topUpId, String outcome) {
        String base = sponsorLink.endsWith("/") ? sponsorLink : sponsorLink + "/";
        return base + "wallet/topups/" + topUpId + "?checkout=" + outcome;
    }

    /**
     * The form of {@code POST /v1/checkout/sessions}: one line of yen at the record's amount, paid by card (cards pay
     * at once, so the completion event means the money is in), without promotion codes, the top-up's and the sponsor's
     * ids as the client reference and metadata of the session and of its payment.
     */
    public static StripeForm form(CheckoutInput input) {
        return StripeForm.of()
            .with("mode", "payment")
            .with("client_reference_id", input.topUpId())
            .with("line_items[0][quantity]", 1)
            .with("line_items[0][price_data][currency]", StripeService.CURRENCY)
            .with("line_items[0][price_data][unit_amount]", input.amount().toBigIntegerExact())
            .with("line_items[0][price_data][product_data][name]", PRODUCT_NAME)
            .with("payment_method_types[0]", "card")
            .with("allow_promotion_codes", "false")
            // Yen only (D-Q6-5): no price in the customer's currency, whatever the account's settings say.
            .with("adaptive_pricing[enabled]", "false")
            .with("expires_at", input.expiresAt().getEpochSecond())
            .with("success_url", input.successUrl())
            .with("cancel_url", input.cancelUrl())
            .with("metadata[topUpId]", input.topUpId())
            .with("metadata[sponsorId]", input.sponsorId())
            .with("payment_intent_data[metadata][topUpId]", input.topUpId())
            .with("payment_intent_data[metadata][sponsorId]", input.sponsorId());
    }

    /** The idempotency key of the creation: one checkout per top-up, whichever job or attempt asks. */
    public static String idempotencyKey(String topUpId) {
        return "qb-checkout-" + topUpId;
    }

    private CheckoutRequest() {}
}
