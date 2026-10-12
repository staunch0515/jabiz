package com.jabiz.quizbuks.wallet;

import java.util.List;

/**
 * The states of a transfer (docs/quizbuks/plans/Q6-payments.md, D-Q6-12). {@code REVIEW}, {@code SENDING} and
 * {@code CHECK} are unfinished: a user has at most one such transfer (a partial unique index).
 */
public final class TransferStatus {

    /** Waiting for a person's review; nothing posted. */
    public static final String REVIEW = "REVIEW";
    /** Posted out of the user's balance; the job sends it to Stripe. */
    public static final String SENDING = "SENDING";
    /** Stripe made the transfer; settled. */
    public static final String SENT = "SENT";
    /** Stripe certainly made no transfer; the posting was reversed. */
    public static final String FAILED = "FAILED";
    /** Whether Stripe made the transfer is not known: Stripe is asked before anything is undone. */
    public static final String CHECK = "CHECK";
    /** The review refused it, or it could no longer be sent; nothing posted. */
    public static final String REJECTED = "REJECTED";
    /** Stripe reversed all of it. */
    public static final String REVERSED = "REVERSED";

    public static final List<String> ALL = List.of(REVIEW, SENDING, SENT, FAILED, CHECK, REJECTED, REVERSED);
    public static final List<String> UNFINISHED = List.of(REVIEW, SENDING, CHECK);

    private TransferStatus() {}
}
