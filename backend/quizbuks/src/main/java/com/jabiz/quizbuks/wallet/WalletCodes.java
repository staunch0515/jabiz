package com.jabiz.quizbuks.wallet;

import java.util.List;

/** The error codes of top-ups (Q6a) and payout accounts (Q6b), each with a text in the three languages. */
public final class WalletCodes {

    /** The amount is not whole Kudos within {@code qb.topup.min} – {@code qb.topup.max} (params {@code min}, {@code max}). */
    public static final String TOPUP_AMOUNT_RANGE = "QB_TOPUP_AMOUNT_RANGE";
    /** The sponsor has {@link WalletLimits#MAX_OPEN_TOPUPS} unfinished top-ups already (param {@code max}). */
    public static final String TOPUP_TOO_MANY_OPEN = "QB_TOPUP_TOO_MANY_OPEN";
    /** Top-ups are not configured here: the sponsor console's address, where Stripe returns, is missing. */
    public static final String TOPUP_UNAVAILABLE = "QB_TOPUP_UNAVAILABLE";

    /** The taker's country is not the one payout accounts are opened in (param {@code country}; Q18: no cross-border). */
    public static final String PAYOUT_COUNTRY_UNSUPPORTED = "QB_PAYOUT_COUNTRY_UNSUPPORTED";
    /** Payout accounts are not configured here: the app's address, where Stripe returns, is missing. */
    public static final String PAYOUT_UNAVAILABLE = "QB_PAYOUT_UNAVAILABLE";
    /** The taker disconnected their payout account from QuizBuks: finance opens it again. */
    public static final String PAYOUT_ACCOUNT_CLOSED = "QB_PAYOUT_ACCOUNT_CLOSED";
    /** A release of a taker whose transfers are not held (nothing rejected or reversed since the last release). */
    public static final String TRANSFER_NOT_HELD = "QB_TRANSFER_NOT_HELD";
    /** A finance administrator releasing transfers to themselves (four eyes, as with the review). */
    public static final String TRANSFER_RELEASE_OWN = "QB_TRANSFER_RELEASE_OWN";

    public static final List<String> ALL = List.of(TOPUP_AMOUNT_RANGE, TOPUP_TOO_MANY_OPEN, TOPUP_UNAVAILABLE,
        PAYOUT_COUNTRY_UNSUPPORTED, PAYOUT_UNAVAILABLE, PAYOUT_ACCOUNT_CLOSED, TRANSFER_NOT_HELD, TRANSFER_RELEASE_OWN);

    private WalletCodes() {}
}
