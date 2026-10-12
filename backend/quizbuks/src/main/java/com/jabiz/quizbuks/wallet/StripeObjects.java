package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The parts of Stripe's objects QuizBuks reads, from their JSON as maps (an event's {@code data.object}, or an answer
 * of the API; numbers as {@link BigDecimal}). Nothing here is trusted: a missing or mistyped value is null, and the
 * checks ({@link TopUpChecks}, {@link RefundMath}) decide what a value means.
 */
public final class StripeObjects {

    /**
     * A Checkout Session. The amounts and currency are those of the integration (what QuizBuks asked for: yen); when
     * the customer paid in another currency (Stripe's Adaptive Pricing) the presentment fields say what they paid.
     * Two shapes of that exist, by API version: before {@code 2025-03-31.basil} the session itself is in the
     * customer's currency and {@code currency_conversion} holds the original amounts and the rate; from that version
     * the session stays in the integration's currency and {@code presentment_details} holds the customer's. Both are
     * read into the same fields.
     *
     * @param status        {@code open}, {@code complete} or {@code expired}
     * @param paymentStatus {@code paid}, {@code unpaid} or {@code no_payment_required}
     * @param expiresAt     epoch seconds
     * @param fxRate        the conversion's rate, when Stripe gives one
     */
    public record CheckoutSession(String id, String status, String paymentStatus, String mode, String currency,
        BigDecimal amountTotal, BigDecimal amountSubtotal, String clientReferenceId, String metadataTopUpId,
        String metadataSponsorId, String paymentIntentId, Boolean livemode, String url, Long expiresAt,
        String presentmentCurrency, BigDecimal presentmentAmount, BigDecimal fxRate) {

        /** The identifiers and states only: the address and the amounts stay out of logs. */
        @Override
        public String toString() {
            return "CheckoutSession[id=" + id + ", status=" + status + ", paymentStatus=" + paymentStatus + "]";
        }
    }

    /** A charge: its payment, amounts in the charge's currency, and the running total refunded. */
    public record Charge(String id, String paymentIntentId, BigDecimal amount, BigDecimal amountRefunded,
        String currency, Boolean livemode) {}

    /**
     * A dispute of a charge.
     *
     * @param status Stripe's: {@code warning_needs_response}, {@code needs_response}, {@code under_review},
     *               {@code won}, {@code lost}, {@code warning_closed}, …
     */
    public record Dispute(String id, String chargeId, String paymentIntentId, BigDecimal amount, String currency,
        String status, Boolean livemode) {}

    public static CheckoutSession session(Object value) {
        Map<String, Object> o = map(value);
        String currency = text(o, "currency");
        BigDecimal total = decimal(o.get("amount_total"));
        BigDecimal subtotal = decimal(o.get("amount_subtotal"));
        String presentmentCurrency = null;
        BigDecimal presentmentAmount = null;
        BigDecimal rate = null;
        Map<String, Object> conversion = map(o.get("currency_conversion"));
        Map<String, Object> presentment = map(o.get("presentment_details"));
        if (!conversion.isEmpty()) {
            presentmentCurrency = currency;
            presentmentAmount = total;
            currency = text(conversion, "source_currency");
            total = decimal(conversion.get("amount_total"));
            subtotal = decimal(conversion.get("amount_subtotal"));
            rate = rate(conversion.get("fx_rate"));
        } else if (!presentment.isEmpty()) {
            presentmentCurrency = text(presentment, "presentment_currency");
            presentmentAmount = decimal(presentment.get("presentment_amount"));
        }
        Map<String, Object> metadata = map(o.get("metadata"));
        BigDecimal expires = decimal(o.get("expires_at"));
        return new CheckoutSession(text(o, "id"), text(o, "status"), text(o, "payment_status"), text(o, "mode"),
            currency, total, subtotal, text(o, "client_reference_id"), text(metadata, "topUpId"),
            text(metadata, "sponsorId"), idOf(o.get("payment_intent")), bool(o.get("livemode")), text(o, "url"),
            expires == null ? null : longOrNull(expires), presentmentCurrency, presentmentAmount, rate);
    }

    public static Charge charge(Object value) {
        Map<String, Object> o = map(value);
        return new Charge(text(o, "id"), idOf(o.get("payment_intent")), decimal(o.get("amount")),
            decimal(o.get("amount_refunded")), text(o, "currency"), bool(o.get("livemode")));
    }

    public static Dispute dispute(Object value) {
        Map<String, Object> o = map(value);
        return new Dispute(text(o, "id"), idOf(o.get("charge")), idOf(o.get("payment_intent")),
            decimal(o.get("amount")), text(o, "currency"), text(o, "status"), bool(o.get("livemode")));
    }

    /** A map, or an empty one for anything else. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String text(Map<String, Object> o, String key) {
        return o.get(key) instanceof String s ? s : null;
    }

    /** An id, or the id of an expanded object; null for anything else. */
    static String idOf(Object value) {
        if (value instanceof String s) {
            return s;
        }
        return value instanceof Map<?, ?> m && m.get("id") instanceof String s ? s : null;
    }

    /** A number; null for anything else (an amount written as text is not Stripe's). */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal d) {
            return d;
        }
        return value instanceof Number n ? new BigDecimal(n.toString()) : null;
    }

    /** A rate: a number, or a decimal written as text (as Stripe writes rates); null for anything else. */
    static BigDecimal rate(Object value) {
        if (value instanceof String s && s.matches("[0-9]{1,30}(\\.[0-9]{1,30})?")) {
            return new BigDecimal(s);
        }
        return decimal(value);
    }

    static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    private static Long longOrNull(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private StripeObjects() {}
}
