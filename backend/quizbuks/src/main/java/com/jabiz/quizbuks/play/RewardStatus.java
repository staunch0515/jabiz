package com.jabiz.quizbuks.play;

import java.util.List;

/**
 * What became of a finished attempt's reward (docs/quizbuks/plans/Q5-answering.md, D-Q5-4, D-Q5-5): none (a reward of
 * 0, nothing posted), paid (posted to the taker's wallet) or pending (the sponsor's wallet could not pay it yet; not in
 * the ledger until paid, in the order of submission); since Q22 also cash owed by the sponsor or a prize to collect.
 */
public final class RewardStatus {

    public static final String NONE = "NONE";
    public static final String PAID = "PAID";
    public static final String PENDING = "PENDING";
    /** Q22: owed in cash by the sponsor (a {@code QbCashReward} says where it stands). */
    public static final String CASH = "CASH";
    /** Q22: a prize to collect at the sponsor's place (a {@code QbRedemption} says where it stands). */
    public static final String REDEEM = "REDEEM";

    public static final List<String> ALL = List.of(NONE, PAID, PENDING, CASH, REDEEM);

    /** Whether the reward is settled outside the wallet (Q22): never posted, never pending. */
    public static boolean external(Object status) {
        return CASH.equals(status) || REDEEM.equals(status);
    }

    private RewardStatus() {}
}
