package com.jabiz.entity;

import com.jabiz.i18n.PlatformErrorCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An {@link EntityCheck} declared on an entity with {@code eb.check(code, check)}.
 *
 * @param code identifies the check; by convention the rule code of the violations it reports, so it needs a message
 *             in every language (startup check)
 */
public record CheckDefinition(String code, EntityCheck check) {

    public CheckDefinition {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("check code must not be blank");
        }
        Objects.requireNonNull(check, "check must not be null");
    }

    /**
     * Runs every check of {@code def} on {@code state} and adds what they report to {@code violations}. A check that
     * throws is reported as {@code CHECK_EVALUATION_FAILED} rather than failing the write with an unexpected error.
     */
    public static void evaluate(EntityDefinition def, Map<String, Object> state, ValidationContext ctx,
        List<Violation> violations) {
        if (def.checks.isEmpty()) {
            return;
        }
        Map<String, Object> view = Collections.unmodifiableMap(new LinkedHashMap<>(state));
        for (CheckDefinition definition : def.checks) {
            List<Violation> found;
            try {
                found = definition.check().check(view, ctx);
            } catch (RuntimeException e) {
                violations.add(new Violation(null, PlatformErrorCodes.CHECK_EVALUATION_FAILED,
                    "Check " + definition.code() + " could not be evaluated: " + e.getMessage(),
                    Map.of("check", definition.code())));
                continue;
            }
            if (found != null) {
                violations.addAll(new ArrayList<>(found));
            }
        }
    }
}
