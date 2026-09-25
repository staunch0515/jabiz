package com.jabiz.entity;

import java.util.Map;
import java.util.Objects;

/**
 * One rule a value or change broke.
 *
 * @param field    logical field name; null when the violation concerns the change as a whole
 * @param ruleCode error code; also the key of the localized message (docs/design/02-metamodel.md section 3.1)
 * @param message  developer-facing description in English; clients get the localized text instead
 * @param params   values for the named placeholders of the localized message
 */
public record Violation(String field, String ruleCode, String message, Map<String, Object> params) {

    public Violation {
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public Violation(String field, String ruleCode, String message) {
        this(field, ruleCode, message, Map.of());
    }
}
