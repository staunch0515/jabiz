package com.jabiz.entity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Normalizes raw values (from JSON, from the database driver, from query parameters)
 * to the canonical Java type of a semantic kind:
 *
 * <ul>
 *   <li>Temporal: {@link Instant} (a timestamp without zone is interpreted as UTC)</li>
 *   <li>Monetary, PhysicalQuantity: {@link BigDecimal}</li>
 *   <li>SpatialH3, Version: {@link Long}</li>
 *   <li>Code: {@link String}</li>
 *   <li>SemanticIdentity, None: unchanged</li>
 * </ul>
 *
 * Working on canonical types makes rules, immutability checks and query binding
 * independent of how a value happened to arrive.
 */
public final class FieldValueCoercer {

    private FieldValueCoercer() {}

    public static Object coerce(FieldDefinition field, Object raw, boolean enforceDictionary) {
        try {
            return coerce(field.kind(), raw, enforceDictionary);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Field '" + field.name() + "': " + e.getMessage(), e);
        }
    }

    /**
     * @param enforceDictionary when true, Code values must belong to the declared dictionary
     *                          (use for input; not for values read from storage)
     * @throws IllegalArgumentException if the value cannot be represented in the canonical type
     */
    public static Object coerce(SemanticKind kind, Object raw, boolean enforceDictionary) {
        if (raw == null) {
            return null;
        }
        return switch (kind) {
            case SemanticKind.Temporal t -> toInstant(raw);
            case SemanticKind.Monetary m -> toDecimal(raw);
            case SemanticKind.PhysicalQuantity q -> toDecimal(raw);
            case SemanticKind.SpatialH3 h -> toLong(raw);
            case SemanticKind.Version v -> toLong(raw);
            case SemanticKind.Code c -> toCode(c, raw, enforceDictionary);
            case SemanticKind.SemanticIdentity s -> raw;
            case SemanticKind.None n -> raw;
        };
    }

    /** Java type used when binding a NULL for a value of the given kind. */
    public static Class<?> javaType(SemanticKind kind) {
        return switch (kind) {
            case SemanticKind.Temporal t -> Instant.class;
            case SemanticKind.Monetary m -> BigDecimal.class;
            case SemanticKind.PhysicalQuantity q -> BigDecimal.class;
            case SemanticKind.SpatialH3 h -> Long.class;
            case SemanticKind.Version v -> Long.class;
            case SemanticKind.Code c -> String.class;
            case SemanticKind.SemanticIdentity s -> String.class;
            case SemanticKind.None n -> String.class;
        };
    }

    private static Instant toInstant(Object raw) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof OffsetDateTime o) return o.toInstant();
        if (raw instanceof ZonedDateTime z) return z.toInstant();
        if (raw instanceof LocalDateTime l) return l.toInstant(ZoneOffset.UTC);
        if (raw instanceof Date d) return d.toInstant();
        if (raw instanceof CharSequence s) {
            String text = s.toString().trim();
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // fall through to offset-aware parsing
            }
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("not an ISO-8601 timestamp: '" + text + "'", e);
            }
        }
        throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName() + " to a timestamp");
    }

    private static BigDecimal toDecimal(Object raw) {
        if (raw instanceof BigDecimal d) return d;
        if (raw instanceof BigInteger b) return new BigDecimal(b);
        if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Double || raw instanceof Float) {
            double v = ((Number) raw).doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("not a finite number: " + raw);
            }
            return BigDecimal.valueOf(v);
        }
        if (raw instanceof CharSequence s) {
            try {
                return new BigDecimal(s.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a decimal number: '" + s + "'", e);
            }
        }
        throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName() + " to a decimal");
    }

    private static Long toLong(Object raw) {
        if (raw instanceof Long l) return l;
        if (raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return ((Number) raw).longValue();
        }
        try {
            if (raw instanceof BigInteger b) return b.longValueExact();
            if (raw instanceof BigDecimal d) return d.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("not a 64-bit integer: " + raw, e);
        }
        if (raw instanceof Double || raw instanceof Float) {
            double v = ((Number) raw).doubleValue();
            if (v != Math.rint(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException("not an integer: " + raw);
            }
            return (long) v;
        }
        if (raw instanceof CharSequence s) {
            String text = s.toString().trim();
            try {
                if (text.startsWith("0x") || text.startsWith("0X")) {
                    return Long.parseUnsignedLong(text.substring(2), 16);
                }
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a 64-bit integer: '" + text + "'", e);
            }
        }
        throw new IllegalArgumentException("cannot convert " + raw.getClass().getSimpleName() + " to an integer");
    }

    private static String toCode(SemanticKind.Code code, Object raw, boolean enforceDictionary) {
        String value = String.valueOf(raw);
        if (enforceDictionary && !code.allowedValues().isEmpty() && !code.allowedValues().contains(value)) {
            throw new IllegalArgumentException("value '" + value + "' is not allowed by dictionary "
                                               + code.dictUrn() + " " + code.allowedValues());
        }
        return value;
    }
}
