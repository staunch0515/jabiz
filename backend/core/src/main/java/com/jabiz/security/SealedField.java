package com.jabiz.security;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A field that holds a value sealed with {@code JABIZ_SEAL_KEY} (decision D56) and the context it was sealed for,
 * computed from the row's fields exactly as the process that sealed it computed it (decision D78): so that the platform
 * can store it again under a new key.
 *
 * @param entity  the entity's logical name
 * @param field   the field holding the sealed value
 * @param context the context of a row, from its fields by logical name; synchronous and free of I/O
 */
public record SealedField(String entity, String field, Function<Map<String, Object>, String> context) {

    public SealedField {
        if (entity == null || entity.isBlank()) {
            throw new IllegalArgumentException("entity must not be blank");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }
        Objects.requireNonNull(context, "context must not be null");
    }

    /** Sealed for the value of another field of the row (its key, usually). */
    public static SealedField of(String entity, String field, String contextField) {
        Objects.requireNonNull(contextField, "contextField must not be null");
        return new SealedField(entity, field, row -> {
            Object value = row.get(contextField);
            return value == null ? null : value.toString();
        });
    }

    /** Sealed for a context the row's fields give. */
    public static SealedField of(String entity, String field, Function<Map<String, Object>, String> context) {
        return new SealedField(entity, field, context);
    }

    @Override
    public String toString() {
        return "SealedField[" + entity + "." + field + "]";
    }
}
