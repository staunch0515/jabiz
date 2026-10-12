package com.jabiz.quizbuks.wallet;

import java.util.regex.Pattern;

/**
 * The shapes of the identifiers QuizBuks exchanges with Stripe. A value from Stripe (or from a signed event, which is
 * no more trusted) is used as a key, a lock name or part of a request path only when it has its shape.
 */
public final class StripeIds {

    /** QuizBuks's own top-up keys: canonical lower-case UUIDs. */
    public static final Pattern TOPUP = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Checkout Sessions, of test or live mode. */
    public static final Pattern CHECKOUT_SESSION = Pattern.compile("cs_(test|live)_[A-Za-z0-9]{1,240}");
    public static final Pattern PAYMENT_INTENT = Pattern.compile("pi_[A-Za-z0-9]{1,240}");
    /** Charges (cards) and payments of other methods. */
    public static final Pattern CHARGE = Pattern.compile("(ch|py)_[A-Za-z0-9]{1,240}");
    public static final Pattern BALANCE_TRANSACTION = Pattern.compile("txn_[A-Za-z0-9]{1,240}");
    public static final Pattern DISPUTE = Pattern.compile("(dp|du)_[A-Za-z0-9]{1,240}");
    /** A three-letter currency code as Stripe writes them (lower case). */
    public static final Pattern CURRENCY = Pattern.compile("[a-z]{3}");

    /** Whether {@code value} is a text of {@code shape}. */
    public static boolean is(Pattern shape, Object value) {
        return value instanceof String text && shape.matcher(text).matches();
    }

    /** {@code value} when it has {@code shape}, else null. */
    public static String valid(Pattern shape, Object value) {
        return is(shape, value) ? (String) value : null;
    }

    /** Whether a Checkout Session's id belongs to the mode ({@code cs_live_} for live mode). */
    public static boolean sessionOfMode(String sessionId, boolean livemode) {
        return sessionId != null && sessionId.startsWith(livemode ? "cs_live_" : "cs_test_");
    }

    private StripeIds() {}
}
