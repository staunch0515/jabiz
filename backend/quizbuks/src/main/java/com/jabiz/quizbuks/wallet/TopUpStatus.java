package com.jabiz.quizbuks.wallet;

import java.util.List;

/**
 * The states of a top-up (docs/quizbuks/plans/Q6-payments.md, D-Q6-2): {@code OPENING} until the job has created its
 * checkout, {@code OPEN} with the checkout's address, then {@code PAID} (credited once), {@code EXPIRED} (the checkout
 * expired unpaid) or {@code FAILED} (the checkout could not be created). Refunds and disputes do not change it: they
 * are amounts of their own. Only the processes change it, under the sponsor's wallet lock.
 */
public final class TopUpStatus {

    public static final String OPENING = "OPENING";
    public static final String OPEN = "OPEN";
    public static final String PAID = "PAID";
    public static final String EXPIRED = "EXPIRED";
    public static final String FAILED = "FAILED";

    public static final List<String> ALL = List.of(OPENING, OPEN, PAID, EXPIRED, FAILED);
    /** The states a payment may still arrive in. */
    public static final List<String> UNSETTLED = List.of(OPENING, OPEN);

    /** The dispute of a top-up's charge: open, then won, lost or closed with a warning only. */
    public static final String DISPUTE_OPEN = "OPEN";
    public static final String DISPUTE_WON = "WON";
    public static final String DISPUTE_LOST = "LOST";
    public static final String DISPUTE_CLOSED = "CLOSED";

    public static boolean unsettled(Object status) {
        return UNSETTLED.contains(status);
    }

    private TopUpStatus() {}
}
