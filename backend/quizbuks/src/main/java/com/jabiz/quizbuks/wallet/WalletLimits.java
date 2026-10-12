package com.jabiz.quizbuks.wallet;

import java.time.Duration;

/** The limits and waits of top-ups and reconciliation (docs/quizbuks/plans/Q6-payments.md). */
public final class WalletLimits {

    /** Unfinished (opening, or open and not expired) top-ups a sponsor may have. */
    public static final int MAX_OPEN_TOPUPS = 3;
    /** How long an opening top-up (its checkout not created yet) counts as unfinished. */
    public static final Duration OPENING_COUNTS = Duration.ofHours(1);
    /**
     * How long after its checkout expired an open top-up is asked about at Stripe (the stale job): Stripe sends the
     * expiry itself, so only a lost event leaves one open this long.
     */
    public static final Duration CHECK_AFTER_EXPIRY = Duration.ofHours(1);
    /** How long after asking a top-up is asked about again (the check may still be running, or have failed). */
    public static final Duration CHECK_AGAIN_AFTER = Duration.ofHours(6);
    /** Top-ups the stale job asks about per run. */
    public static final int STALE_BATCH = 200;
    /**
     * How long a refund or dispute of an unknown payment is retried before it becomes a task: its top-up's
     * confirmation may simply not have been processed yet (events are not delivered in order). The outbox retries a
     * failed delivery ten times over some 40 minutes, so a shorter wait leaves the last attempts to open the task.
     */
    public static final Duration UNKNOWN_PAYMENT_GRACE = Duration.ofMinutes(30);
    /** Differences one reconciliation run records item by item; the counts cover them all. */
    public static final int MAX_RECON_ITEMS = 1000;

    private WalletLimits() {}
}
