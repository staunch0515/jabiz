package com.jabiz.quizbuks.content;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reading values of content back, from stored rows and from snapshot maps alike: keys as {@link UUID}s and whole
 * numbers as {@code long} / {@code int}. A fraction or a number out of range is an error, never rounded away.
 */
final class Values {

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    /** A whole number; null is 0 (counts and places that were never set). */
    static long longValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().longValueExact();
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().longValueExact();
        }
        throw new IllegalArgumentException("Not a number: " + value);
    }

    static int intValue(Object value) {
        return Math.toIntExact(longValue(value));
    }

    private Values() {}
}
