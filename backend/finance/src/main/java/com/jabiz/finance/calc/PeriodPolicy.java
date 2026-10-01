package com.jabiz.finance.calc;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether an entry may be posted into a period (FIN-PC-003, docs/finance/00-design.md section 7.2), the one check
 * every posting process makes. The general ledger's state comes first: closed refuses everything ("period closed"),
 * soft-closed admits only adjusting entries of holders of the close permission. A subledger's own document is then
 * refused once that subledger is closed for the period, even while the general ledger is open.
 */
public final class PeriodPolicy {

    /** State of the general ledger in a period. */
    public enum Status { OPEN, SOFT_CLOSED, CLOSED }

    /** Where an entry comes from; {@link #GL} for journal entries, the others for their subledgers' documents. */
    public enum Source { GL, AR, AP, BANK, FA }

    public static final String PERIOD_CLOSED = "FIN_PERIOD_CLOSED";
    public static final String PERIOD_SOFT_CLOSED = "FIN_PERIOD_SOFT_CLOSED";
    public static final String SUBLEDGER_CLOSED = "FIN_SUBLEDGER_CLOSED";

    /**
     * A period as a posting sees it.
     *
     * @param status         the general ledger's state
     * @param closedForSource whether the entry's own subledger is closed (always false for {@link Source#GL})
     */
    public record State(String periodKey, Status status, boolean closedForSource) {
        public State {
            Objects.requireNonNull(periodKey, "periodKey must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }
    }

    /** Why a posting is refused, with a code of the finance messages. */
    public record Refusal(String code, String message) {}

    private PeriodPolicy() {}

    /**
     * @param adjusting whether the entry is an adjusting entry
     * @param mayClose  whether the actor holds the close permission ({@code fin.period.close})
     * @return empty when the entry may be posted
     */
    public static Optional<Refusal> check(State period, Source source, boolean adjusting, boolean mayClose) {
        Objects.requireNonNull(source, "source must not be null");
        if (period.status() == Status.CLOSED) {
            return Optional.of(new Refusal(PERIOD_CLOSED, "Period " + period.periodKey() + " is closed"));
        }
        if (period.status() == Status.SOFT_CLOSED && !(adjusting && mayClose)) {
            return Optional.of(new Refusal(PERIOD_SOFT_CLOSED, "Period " + period.periodKey()
                + " is soft-closed: only adjusting entries by the close permission"));
        }
        if (source != Source.GL && period.closedForSource()) {
            return Optional.of(new Refusal(SUBLEDGER_CLOSED, source + " is closed for period " + period.periodKey()));
        }
        return Optional.empty();
    }
}
