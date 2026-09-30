package com.jabiz.retention;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A legal hold in force (docs/design/21-audit-retention.md section 3.3): entries of one entity type that must not be
 * deleted, whatever their retention - named by id, or all whose field has a value.
 *
 * @param holdId the hold
 * @param entity the entity type
 * @param ids    the entries' ids; empty when the hold names a field
 * @param field  the field, or null when the hold names ids
 * @param value  the field's value, as text; numbers match by value ({@code 10} and {@code 10.00})
 */
public record LegalHold(String holdId, String entity, List<String> ids, String field, String value) {

    public LegalHold {
        Objects.requireNonNull(entity, "entity must not be null");
        ids = ids == null ? List.of() : List.copyOf(ids);
        if (ids.isEmpty() == (field == null)) {
            throw new IllegalArgumentException("A legal hold names either ids or a field and its value");
        }
    }

    /** Whether the hold covers the entry. */
    public boolean covers(String entityType, Object id, Map<String, ?> attributes) {
        if (!entity.equals(entityType)) {
            return false;
        }
        if (!ids.isEmpty()) {
            return ids.contains(String.valueOf(id));
        }
        Object actual = attributes == null ? null : attributes.get(field);
        return actual != null && sameValue(actual, value);
    }

    private static boolean sameValue(Object actual, String expected) {
        if (expected == null) {
            return false;
        }
        String text = actual instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(actual);
        if (text.equals(expected)) {
            return true;
        }
        try {
            return new BigDecimal(text).compareTo(new BigDecimal(expected)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
