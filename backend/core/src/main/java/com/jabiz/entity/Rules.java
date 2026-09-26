package com.jabiz.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Factories for the exportable field rules (docs/design/02-metamodel.md section 3, decision D15). Each factory
 * derives the exported {@link RuleSpec} and the server-side predicate from the same parameters, so the check the
 * client runs and the one the server runs cannot drift apart. The client implements exactly these kinds
 * ({@link RuleKinds}); the shared cases in {@code spec/validation-cases.json} prove both give the same codes.
 *
 * <p>Usage: {@code eb.field("amount", f -> f.physicalColumn("amount").asMonetary("JPY", 0)
 * .apply(Rules.range("AMOUNT_RANGE", BigDecimal.ZERO, null)))}. Predicates see the value after coercion to the
 * canonical Java type of the field's semantic kind; a value of another type fails the rule.
 */
public final class Rules {

    private Rules() {}

    /**
     * {@code RANGE}: a decimal value within {@code [min, max]} (both inclusive, either may be null for no bound).
     * Parameters {@code min}, {@code max}.
     */
    public static Consumer<FieldBuilder> range(String code, BigDecimal min, BigDecimal max) {
        if (min == null && max == null) {
            throw new IllegalArgumentException("Rule " + code + ": RANGE needs min or max");
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Rule " + code + ": RANGE min is greater than max");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (min != null) {
            params.put("min", min);
        }
        if (max != null) {
            params.put("max", max);
        }
        return f -> f.rule(code, RuleKinds.RANGE, params, (Object v) -> {
            BigDecimal d = decimal(v);
            return d != null && (min == null || d.compareTo(min) >= 0) && (max == null || d.compareTo(max) <= 0);
        });
    }

    /** {@code SCALE}: a decimal value with at most {@code scale} digits after the point (trailing zeros ignored). */
    public static Consumer<FieldBuilder> scale(String code, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException("Rule " + code + ": SCALE must not be negative");
        }
        return f -> f.rule(code, RuleKinds.SCALE, Map.of("scale", scale), (Object v) -> {
            BigDecimal d = decimal(v);
            return d != null && d.stripTrailingZeros().scale() <= scale;
        });
    }

    /**
     * {@code LENGTH}: a text of {@code min} to {@code max} characters (Unicode code points; either bound may be null).
     */
    public static Consumer<FieldBuilder> length(String code, Integer min, Integer max) {
        if (min == null && max == null) {
            throw new IllegalArgumentException("Rule " + code + ": LENGTH needs min or max");
        }
        if ((min != null && min < 0) || (max != null && max < 0) || (min != null && max != null && min > max)) {
            throw new IllegalArgumentException("Rule " + code + ": LENGTH bounds are invalid");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (min != null) {
            params.put("min", min);
        }
        if (max != null) {
            params.put("max", max);
        }
        return f -> f.rule(code, RuleKinds.LENGTH, params, (Object v) -> {
            if (!(v instanceof String s)) {
                return false;
            }
            int n = s.codePointCount(0, s.length());
            return (min == null || n >= min) && (max == null || n <= max);
        });
    }

    /**
     * {@code PATTERN}: a text matching {@code regex} as a whole. The expression must be portable between Java and
     * JavaScript ({@link RuleKinds#checkPortablePattern}), since the client evaluates it too.
     */
    public static Consumer<FieldBuilder> pattern(String code, String regex) {
        RuleKinds.checkPortablePattern(code, regex);
        Pattern compiled = Pattern.compile(regex);
        return f -> f.rule(code, RuleKinds.PATTERN, Map.of("regex", regex),
            (Object v) -> v instanceof String s && compiled.matcher(s).matches());
    }

    /**
     * {@code NOT_FUTURE}: a time not later than the clock's current time plus {@code toleranceSeconds}. The client
     * checks against its own clock, so it is only a hint there; the server's clock decides.
     */
    public static Consumer<FieldBuilder> notFuture(String code, int toleranceSeconds) {
        if (toleranceSeconds < 0) {
            throw new IllegalArgumentException("Rule " + code + ": NOT_FUTURE tolerance must not be negative");
        }
        return f -> f.rule(code, RuleKinds.NOT_FUTURE, Map.of("toleranceSeconds", toleranceSeconds),
            (v, ctx) -> v instanceof Instant t && !t.isAfter(ctx.clock().instant().plusSeconds(toleranceSeconds)));
    }

    /**
     * {@code REQUIRED}: a text that is not blank. Field rules only see present values, so a missing value is the
     * business of {@link FieldBuilder#required(boolean)}; this rule rejects values that are present but empty.
     * Values of other kinds pass.
     */
    public static Consumer<FieldBuilder> notBlank(String code) {
        return f -> f.rule(code, RuleKinds.REQUIRED, Map.of(), (Object v) -> !(v instanceof String s) || !s.isBlank());
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal d ? d : null;
    }
}
