package com.jabiz.context;

import java.time.Instant;
import java.util.Collection;

/**
 * The span of business time whose data an actor may see (docs/design/10-security.md section 13.2, decision D28
 * item 8): from {@code from} (inclusive) to {@code to} (exclusive); either end may be open (null), not both.
 * An actor without a period is not limited in time.
 */
public record DataPeriod(Instant from, Instant to) {

    public DataPeriod {
        if (from == null && to == null) {
            throw new IllegalArgumentException("a data period needs at least one end; no period means no limit");
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("a data period must end after it starts");
        }
    }

    /** Whether the instant lies within the period; a missing instant never does. */
    public boolean contains(Instant instant) {
        return instant != null && (from == null || !instant.isBefore(from)) && (to == null || instant.isBefore(to));
    }

    /**
     * The period of an actor with the given role assignments: none (null) as soon as one assignment is unlimited
     * (null), otherwise the smallest period covering all of them, gaps included. Permissions are not split per
     * assignment, so an actor meant to see one period only should hold only roles assigned with that period.
     *
     * @param assignments the periods of the actor's assignments, null for an unlimited one; empty yields null
     */
    public static DataPeriod hull(Collection<DataPeriod> assignments) {
        if (assignments.isEmpty()) {
            return null;
        }
        Instant from = null;
        Instant to = null;
        boolean openStart = false;
        boolean openEnd = false;
        for (DataPeriod period : assignments) {
            if (period == null) {
                return null;
            }
            if (period.from() == null) {
                openStart = true;
            } else if (from == null || period.from().isBefore(from)) {
                from = period.from();
            }
            if (period.to() == null) {
                openEnd = true;
            } else if (to == null || period.to().isAfter(to)) {
                to = period.to();
            }
        }
        from = openStart ? null : from;
        to = openEnd ? null : to;
        return from == null && to == null ? null : new DataPeriod(from, to);
    }

    /** The period of one assignment, or null when both ends are open. */
    public static DataPeriod of(Instant from, Instant to) {
        return from == null && to == null ? null : new DataPeriod(from, to);
    }
}
