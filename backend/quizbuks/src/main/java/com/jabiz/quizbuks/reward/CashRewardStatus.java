package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * Where a cash reward stands (docs/quizbuks/plans/Q22-reward-methods.md §2): owed by the sponsor ({@link #DUE}),
 * marked paid by the sponsor and waiting for the taker ({@link #MARKED_PAID}), confirmed by the taker, so complete
 * ({@link #CONFIRMED}); disputed by the taker and waiting for an administrator ({@link #DISPUTED}); neither confirmed
 * nor disputed in time, so the taker starts no new attempt until they do ({@link #OVERDUE}); or voided by an
 * administrator ({@link #VOID}).
 */
public final class CashRewardStatus {

    public static final String DUE = "DUE";
    public static final String MARKED_PAID = "MARKED_PAID";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DISPUTED = "DISPUTED";
    public static final String OVERDUE = "OVERDUE";
    public static final String VOID = "VOID";

    public static final List<String> ALL = List.of(DUE, MARKED_PAID, CONFIRMED, DISPUTED, OVERDUE, VOID);

    /** The states a taker still has something to do in, or may dispute from. */
    public static final List<String> OPEN = List.of(DUE, MARKED_PAID, OVERDUE, DISPUTED);

    /** An administrator's resolution of a dispute: the sponsor paid, the sponsor still owes, or nothing is owed. */
    public static final String RESOLUTION_PAID = "PAID";
    public static final String RESOLUTION_UNPAID = "UNPAID";
    public static final String RESOLUTION_VOID = "VOID";
    public static final List<String> RESOLUTIONS = List.of(RESOLUTION_PAID, RESOLUTION_UNPAID, RESOLUTION_VOID);

    public static boolean complete(Object status) {
        return CONFIRMED.equals(status) || VOID.equals(status);
    }

    private CashRewardStatus() {}
}
