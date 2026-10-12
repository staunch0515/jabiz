package com.jabiz.quizbuks.wallet;

import java.util.List;

/** The error codes of top-ups (Q6a), each with a text in the three languages. */
public final class WalletCodes {

    /** The amount is not whole Kudos within {@code qb.topup.min} – {@code qb.topup.max} (params {@code min}, {@code max}). */
    public static final String TOPUP_AMOUNT_RANGE = "QB_TOPUP_AMOUNT_RANGE";
    /** The sponsor has {@link WalletLimits#MAX_OPEN_TOPUPS} unfinished top-ups already (param {@code max}). */
    public static final String TOPUP_TOO_MANY_OPEN = "QB_TOPUP_TOO_MANY_OPEN";
    /** Top-ups are not configured here: the sponsor console's address, where Stripe returns, is missing. */
    public static final String TOPUP_UNAVAILABLE = "QB_TOPUP_UNAVAILABLE";

    public static final List<String> ALL = List.of(TOPUP_AMOUNT_RANGE, TOPUP_TOO_MANY_OPEN, TOPUP_UNAVAILABLE);

    private WalletCodes() {}
}
