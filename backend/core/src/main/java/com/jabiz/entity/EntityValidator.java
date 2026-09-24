package com.jabiz.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single validation path for incoming entity attributes: normalizes values to their
 * canonical types, rejects unknown fields, enforces required fields and runs field rules.
 *
 * System-managed fields (version, system-recorded timestamps) are ignored if supplied.
 */
public final class EntityValidator {

    private EntityValidator() {}

    /** Outcome of a validation run: the normalized attributes and any violations found. */
    public record Result(Map<String, Object> attributes, List<Violation> violations) {
        public boolean isValid() {
            return violations.isEmpty();
        }
    }

    public static Result check(EntityDefinition def, Map<String, Object> raw, ValidationContext ctx, boolean forInsert) {
        List<Violation> violations = new ArrayList<>();
        Map<String, Object> normalized = new LinkedHashMap<>();
        Set<String> rejected = new HashSet<>();

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            FieldDefinition field = def.fields.get(entry.getKey());
            if (field == null) {
                violations.add(new Violation(entry.getKey(), "UNKNOWN_FIELD",
                    "Entity '" + def.name + "' has no field '" + entry.getKey() + "'"));
                continue;
            }
            if (def.isSystemManaged(field)) {
                continue;
            }
            try {
                normalized.put(field.name(), FieldValueCoercer.coerce(field, entry.getValue(), true));
            } catch (IllegalArgumentException e) {
                rejected.add(field.name());
                violations.add(new Violation(field.name(), "INVALID_VALUE", e.getMessage()));
            }
        }

        for (FieldDefinition field : def.fields.values()) {
            if (rejected.contains(field.name())) {
                continue;
            }
            Object value = normalized.get(field.name());
            if (value == null) {
                boolean supplied = normalized.containsKey(field.name());
                if (field.required() && (forInsert || supplied) && !def.isSystemManaged(field)) {
                    violations.add(new Violation(field.name(), "REQUIRED",
                        "Field '" + field.name() + "' is required"));
                }
                continue;
            }
            for (FieldRule rule : field.rules()) {
                try {
                    if (!rule.isSatisfiedBy(value, ctx)) {
                        violations.add(new Violation(field.name(), rule.code(),
                            "Field '" + field.name() + "' failed rule " + rule.code()));
                    }
                } catch (RuntimeException e) {
                    violations.add(new Violation(field.name(), "RULE_EVALUATION_FAILED",
                        "Rule " + rule.code() + " could not be evaluated: " + e.getMessage()));
                }
            }
        }
        return new Result(normalized, violations);
    }

    /**
     * Validates and returns the normalized attributes.
     *
     * @throws ValidationException if any violation was found
     */
    public static Map<String, Object> requireValid(EntityDefinition def, Map<String, Object> raw,
        ValidationContext ctx, boolean forInsert) {
        Result result = check(def, raw, ctx, forInsert);
        if (!result.isValid()) {
            throw new ValidationException(result.violations());
        }
        return result.attributes();
    }
}
