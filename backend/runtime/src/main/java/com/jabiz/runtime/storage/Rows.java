package com.jabiz.runtime.storage;

import com.jabiz.entity.FieldValueCoercer;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Conversions of values read through a {@link StorageEngine} into the platform's canonical types. */
public final class Rows {

    private static final SemanticKind INSTANT = new SemanticKind.Temporal(TemporalRole.EVENT_TIME);

    private Rows() {}

    public static Instant instant(Object raw) {
        return (Instant) FieldValueCoercer.coerce(INSTANT, raw, false);
    }

    public static Long longValue(Object raw) {
        return raw == null ? null : FieldValueCoercer.toLong(raw);
    }

    public static Integer intValue(Object raw) {
        return raw == null ? null : Math.toIntExact(FieldValueCoercer.toLong(raw));
    }

    public static UUID uuid(Object raw) {
        if (raw == null || raw instanceof UUID) {
            return (UUID) raw;
        }
        return UUID.fromString(raw.toString());
    }

    public static String string(Object raw) {
        return raw == null ? null : raw.toString();
    }

    /** A text[] column. */
    public static List<String> strings(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof String[] array) {
            return List.copyOf(Arrays.asList(array));
        }
        if (raw instanceof Object[] array) {
            return Arrays.stream(array).map(String::valueOf).toList();
        }
        throw new IllegalArgumentException("Not a text array: " + raw.getClass().getName());
    }
}
