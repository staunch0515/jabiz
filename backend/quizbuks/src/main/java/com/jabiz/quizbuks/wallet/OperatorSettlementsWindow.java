package com.jabiz.quizbuks.wallet;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** F-M12 c: where the history the operators' settlement checks read begins (see {@code Reconciliation}). */
final class OperatorSettlementsWindow {

    /** The last clean run's time less the cushion's months, in the fiscal zone. */
    static Instant since(Object lastCleanRun, ZoneId zone) {
        Instant at = lastCleanRun instanceof Instant instant ? instant : lastCleanRun instanceof OffsetDateTime time
            ? time.toInstant() : Instant.parse(lastCleanRun.toString());
        return at.atZone(zone).minusMonths(Reconciliation.SETTLEMENT_CHECK_CUSHION_MONTHS).toInstant();
    }

    private OperatorSettlementsWindow() {}
}
