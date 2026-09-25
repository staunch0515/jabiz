package com.jabiz.runtime.process.entity;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;

import java.util.List;

/** Shared checks for the input records of the entity change processes. */
final class InputChecks {

    private InputChecks() {}

    static void require(String field, Object value) {
        boolean missing = value == null || (value instanceof CharSequence text && text.toString().isBlank());
        if (missing) {
            throw new ValidationException(List.of(new Violation(field, "REQUIRED", "'" + field + "' is required")));
        }
    }
}
