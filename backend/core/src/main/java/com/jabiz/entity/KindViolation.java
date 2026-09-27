package com.jabiz.entity;

import java.util.Map;
import java.util.Objects;

/**
 * A constraint of a {@link SemanticKind.Custom} kind the value breaks ({@link CustomKindSupport#validate}); the
 * validator adds the field and reports it as a {@link Violation}.
 *
 * @param code   error code, which needs a message in every supported language
 * @param params message placeholders
 */
public record KindViolation(String code, Map<String, Object> params) {
    public KindViolation {
        Objects.requireNonNull(code, "code");
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
