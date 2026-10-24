package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * Where a redemption stands (docs/quizbuks/plans/Q22-reward-methods.md §3): the prize may be collected
 * ({@link #DUE}), was collected ({@link #REDEEMED}, Q22b), was not collected in time ({@link #EXPIRED}) or was voided
 * by an administrator ({@link #VOID}).
 */
public final class RedemptionStatus {

    public static final String DUE = "DUE";
    public static final String REDEEMED = "REDEEMED";
    public static final String EXPIRED = "EXPIRED";
    public static final String VOID = "VOID";

    public static final List<String> ALL = List.of(DUE, REDEEMED, EXPIRED, VOID);

    private RedemptionStatus() {}
}
