package com.jabiz.entity;

import com.jabiz.dictionary.DictionaryLookup;
import com.jabiz.i18n.PlatformErrorCodes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single validation path for incoming entity attributes: normalizes values to their
 * canonical types, rejects unknown fields, enforces required fields, the constraints of the semantic kind
 * (text length, numeric precision, dictionary membership, the constraints of custom kinds) and runs field rules.
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
        return check(def, raw, ctx, forInsert, DictionaryLookup.NONE);
    }

    /**
     * @param dictionaries enabled codes of the dictionaries behind {@link SemanticKind.Code} fields without
     *                     fixed values; a code outside its dictionary is reported as {@code NOT_IN_DICTIONARY}
     */
    public static Result check(EntityDefinition def, Map<String, Object> raw, ValidationContext ctx, boolean forInsert,
        DictionaryLookup dictionaries) {
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
            Violation kindViolation = checkKind(field, value, dictionaries);
            if (kindViolation != null) {
                violations.add(kindViolation);
                continue;
            }
            for (FieldRule rule : field.rules()) {
                try {
                    if (!rule.isSatisfiedBy(value, ctx)) {
                        violations.add(new Violation(field.name(), rule.code(),
                            "Field '" + field.name() + "' failed rule " + rule.code(), ruleParams(field, rule)));
                    }
                } catch (RuntimeException e) {
                    violations.add(new Violation(field.name(), "RULE_EVALUATION_FAILED",
                        "Rule " + rule.code() + " could not be evaluated: " + e.getMessage(),
                        Map.of("rule", rule.code())));
                }
            }
        }
        return new Result(normalized, violations);
    }

    /** Constraint of the field's semantic kind the value breaks, or null. */
    private static Violation checkKind(FieldDefinition field, Object value, DictionaryLookup dictionaries) {
        switch (field.kind()) {
            case SemanticKind.Text t when t.maxLength() != null
                && ((String) value).codePointCount(0, ((String) value).length()) > t.maxLength() -> {
                return new Violation(field.name(), PlatformErrorCodes.TOO_LONG,
                    "Field '" + field.name() + "' is longer than " + t.maxLength() + " characters",
                    Map.of("max", t.maxLength()));
            }
            case SemanticKind.Numeric n when !fits((BigDecimal) value, n) -> {
                return new Violation(field.name(), PlatformErrorCodes.NUMERIC_PRECISION,
                    "Field '" + field.name() + "' does not fit numeric(" + n.precision() + "," + n.scale() + ")",
                    Map.of("precision", n.precision(), "scale", n.scale()));
            }
            case SemanticKind.Custom c -> {
                return CustomKinds.require(c.kindId()).validate(c.params(), value).stream().findFirst()
                    .map(kv -> new Violation(field.name(), kv.code(),
                        "Field '" + field.name() + "' breaks " + kv.code() + " " + kv.params(), kv.params()))
                    .orElse(null);
            }
            case SemanticKind.Code c when c.allowedValues().isEmpty() -> {
                return dictionaries.enabledCodes(c.dictUrn())
                    .filter(codes -> !codes.contains((String) value))
                    .map(codes -> new Violation(field.name(), PlatformErrorCodes.NOT_IN_DICTIONARY,
                        "Value '" + value + "' of field '" + field.name() + "' is not an enabled code of " + c.dictUrn(),
                        Map.of("value", value, "dict", c.dictUrn())))
                    .orElse(null);
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean fits(BigDecimal value, SemanticKind.Numeric kind) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        // Zero and pure fractions have no integer digits (BigDecimal counts one for 0).
        int integerDigits = stripped.signum() == 0 ? 0 : Math.max(stripped.precision() - stripped.scale(), 0);
        return scale <= kind.scale() && integerDigits <= kind.precision() - kind.scale();
    }

    /** Parameters of the exported rule with the same code, which fill the placeholders of its message. */
    private static Map<String, Object> ruleParams(FieldDefinition field, FieldRule rule) {
        return field.ruleSpecs().stream()
            .filter(spec -> spec.code().equals(rule.code()) && spec.params() != null)
            .findFirst()
            .map(RuleSpec::params)
            .orElse(Map.of());
    }

    /**
     * Validates and returns the normalized attributes.
     *
     * @throws ValidationException if any violation was found
     */
    public static Map<String, Object> requireValid(EntityDefinition def, Map<String, Object> raw,
        ValidationContext ctx, boolean forInsert) {
        return requireValid(def, raw, ctx, forInsert, DictionaryLookup.NONE);
    }

    /**
     * Validates, checking dictionary-backed codes against {@code dictionaries}, and returns the normalized attributes.
     *
     * @throws ValidationException if any violation was found
     */
    public static Map<String, Object> requireValid(EntityDefinition def, Map<String, Object> raw,
        ValidationContext ctx, boolean forInsert, DictionaryLookup dictionaries) {
        Result result = check(def, raw, ctx, forInsert, dictionaries);
        if (!result.isValid()) {
            throw new ValidationException(result.violations());
        }
        return result.attributes();
    }
}
