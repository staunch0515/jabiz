package com.jabiz.quizbuks.play;

import java.util.List;

/**
 * What became of a finished attempt's reward (docs/quizbuks/plans/Q5-answering.md, D-Q5-4, D-Q5-5): none (a reward of
 * 0, nothing posted), paid (posted to the taker's wallet) or pending (the sponsor's wallet could not pay it yet; not in
 * the ledger until paid, in the order of submission).
 */
public final class RewardStatus {

    public static final String NONE = "NONE";
    public static final String PAID = "PAID";
    public static final String PENDING = "PENDING";

    public static final List<String> ALL = List.of(NONE, PAID, PENDING);

    private RewardStatus() {}
}
