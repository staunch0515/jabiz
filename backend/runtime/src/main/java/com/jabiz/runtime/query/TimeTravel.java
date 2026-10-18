package com.jabiz.runtime.query;

import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The points in time a caller reads at ({@code asOf}, {@code knownAt}) must lie in what PostgreSQL stores (decision
 * D51 item 4): beyond it they are invalid input (400 {@code INVALID_VALUE}), not a database error.
 */
public final class TimeTravel {

    private TimeTravel() {}

    /** Fails with a {@link ValidationException} naming each given point outside the range. */
    public static void requireStorable(Instant asOf, Instant knownAt) {
        List<Violation> violations = new ArrayList<>();
        check("asOf", asOf, violations);
        check("knownAt", knownAt, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    private static void check(String field, Instant value, List<Violation> violations) {
        try {
            FieldValueCoercer.storable(value);
        } catch (IllegalArgumentException e) {
            violations.add(new Violation(field, PlatformErrorCodes.INVALID_VALUE, field + ": " + e.getMessage()));
        }
    }
}
