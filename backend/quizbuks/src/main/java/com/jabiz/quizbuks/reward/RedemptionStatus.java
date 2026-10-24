package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * Where a redemption stands (docs/quizbuks/plans/Q22-reward-methods.md §3): the prize may be collected
 * ({@link #DUE}), was claimed by a scan of the sponsor's printed code and waits for the sponsor to confirm
 * ({@link #CLAIMED}, Q22b), was collected ({@link #REDEEMED}), was not collected in time ({@link #EXPIRED}) or was
 * voided by an administrator ({@link #VOID}).
 */
public final class RedemptionStatus {

    public static final String DUE = "DUE";
    public static final String CLAIMED = "CLAIMED";
    public static final String REDEEMED = "REDEEMED";
    public static final String EXPIRED = "EXPIRED";
    public static final String VOID = "VOID";

    public static final List<String> ALL = List.of(DUE, CLAIMED, REDEEMED, EXPIRED, VOID);
    /** The states a prize may still be collected from. */
    public static final List<String> OPEN = List.of(DUE, CLAIMED);

    /** How the taker's scan reached the sponsor: the console's rotating code, or a printed one. */
    public static final String KIND_ROTATING = "ROTATING";
    public static final String KIND_PRINTED = "PRINTED";
    public static final List<String> KINDS = List.of(KIND_ROTATING, KIND_PRINTED);

    private RedemptionStatus() {}
}
