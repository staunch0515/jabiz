package com.jabiz.quizbuks.reward;

import java.util.List;

/**
 * Where a digital prize stands (Q33, docs/quizbuks/plans/Q33-digital-prize.md): owed by the sponsor, who sends it
 * by hand ({@link #DUE}); delivered, by the system from the pool or the shared content, or by the sponsor
 * ({@link #DELIVERED}); confirmed received by the taker ({@link #CONFIRMED}); disputed by the taker for an
 * administrator to decide ({@link #DISPUTED}); overdue when the sponsor did not send it in time ({@link #OVERDUE});
 * or void ({@link #VOID}).
 */
public final class DigitalPrizeStatus {

    public static final String DUE = "DUE";
    public static final String DELIVERED = "DELIVERED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String DISPUTED = "DISPUTED";
    public static final String OVERDUE = "OVERDUE";
    public static final String VOID = "VOID";

    public static final List<String> ALL = List.of(DUE, DELIVERED, CONFIRMED, DISPUTED, OVERDUE, VOID);

    /** What an administrator finds about a dispute: delivered (confirmed), not delivered (owed again) or void. */
    public static final String RESOLUTION_DELIVERED = "DELIVERED";
    public static final String RESOLUTION_UNDELIVERED = "UNDELIVERED";
    public static final String RESOLUTION_VOID = "VOID";
    public static final List<String> RESOLUTIONS = List.of(RESOLUTION_DELIVERED, RESOLUTION_UNDELIVERED,
        RESOLUTION_VOID);

    /** Who delivered the prize: the system (pool or shared content) or the sponsor by hand. */
    public static final String BY_SYSTEM = "SYSTEM";
    public static final String BY_SPONSOR = "SPONSOR";

    /** A code of a pool: not issued yet, issued to a winner, or taken back by the sponsor. */
    public static final String CODE_AVAILABLE = "AVAILABLE";
    public static final String CODE_ISSUED = "ISSUED";
    public static final String CODE_REVOKED = "REVOKED";
    public static final List<String> CODE_STATUSES = List.of(CODE_AVAILABLE, CODE_ISSUED, CODE_REVOKED);

    private DigitalPrizeStatus() {}
}
