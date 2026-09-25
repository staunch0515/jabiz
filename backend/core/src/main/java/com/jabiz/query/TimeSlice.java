package com.jabiz.query;

import java.time.Instant;
import java.util.Objects;

/**
 * Point of view of a read of temporal entities (docs/design/04-temporal-append-only.md section 5.1): the versions
 * in effect at {@code asOf}, as recorded until {@code knownAt}.
 *
 * @param asOf    business time; the runtime defaults it to the current time of its clock
 * @param knownAt recording time; null means everything recorded so far
 */
public record TimeSlice(Instant asOf, Instant knownAt) {
    public TimeSlice {
        Objects.requireNonNull(asOf, "asOf must not be null");
    }

    public static TimeSlice asOf(Instant asOf) {
        return new TimeSlice(asOf, null);
    }
}
