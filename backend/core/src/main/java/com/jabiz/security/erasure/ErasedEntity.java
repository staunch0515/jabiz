package com.jabiz.security.erasure;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One entity of a {@link PersonalDataEraser}: its rows whose {@code userField} holds the erased user's id.
 *
 * @param entity    the entity's name
 * @param userField the field that holds the user id (a reference to the user, or its id as text)
 * @param action    what becomes of the rows when the account is erased
 * @param fields    the personal fields: overwritten in every version and append-only row by the purge, and masked in
 *                  the audit records of the rows
 * @param documents the document layouts whose issued documents have one of these rows as their subject: their PDF,
 *                  content and recipients are overwritten unless a retention period keeps them (decision D62 item 5)
 */
public record ErasedEntity(String entity, String userField, RowAction action, List<String> fields,
    List<String> documents) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,199}");

    /** What becomes of an erased user's rows when the account is erased. */
    public enum RowAction {
        /** Deleted: a tombstone for a temporal entity, removed for a plain one; the purge overwrites the history. */
        DELETE,
        /** Kept with the personal fields cleared (each must be optional); the purge overwrites the history. */
        CLEAR,
        /**
         * Kept as they are (append-only records the business needs, such as ledger lines); only the purge overwrites
         * the personal fields.
         */
        KEEP
    }

    public ErasedEntity {
        requireName(entity, "entity");
        requireName(userField, "user field of " + entity);
        Objects.requireNonNull(action, "action must not be null");
        fields = List.copyOf(fields);
        documents = List.copyOf(documents);
        Set<String> seen = new LinkedHashSet<>();
        for (String field : fields) {
            requireName(field, "field of " + entity);
            if (!seen.add(field)) {
                throw new IllegalArgumentException(entity + ": field " + field + " is named twice");
            }
            if (field.equals(userField)) {
                throw new IllegalArgumentException(entity + ": the user field " + field + " is kept: it is how the "
                    + "rows are found (the user's id stays, decision D62 item 4)");
            }
        }
        for (String layout : documents) {
            requireName(layout, "document layout of " + entity);
        }
        if (action == RowAction.CLEAR && fields.isEmpty()) {
            throw new IllegalArgumentException(entity + ": CLEAR names no field to clear");
        }
        if (action == RowAction.KEEP && fields.isEmpty() && documents.isEmpty()) {
            throw new IllegalArgumentException(entity + ": KEEP names no field and no document to overwrite");
        }
    }

    /** The rows are deleted (tombstoned); the purge overwrites {@code fields} in their history. */
    public static ErasedEntity delete(String entity, String userField, String... fields) {
        return new ErasedEntity(entity, userField, RowAction.DELETE, List.of(fields), List.of());
    }

    /** The rows stay with {@code fields} cleared; the purge overwrites them in the history. */
    public static ErasedEntity clear(String entity, String userField, String... fields) {
        return new ErasedEntity(entity, userField, RowAction.CLEAR, List.of(fields), List.of());
    }

    /** The rows stay as they are; only the purge overwrites {@code fields} (append-only records). */
    public static ErasedEntity keep(String entity, String userField, String... fields) {
        return new ErasedEntity(entity, userField, RowAction.KEEP, List.of(fields), List.of());
    }

    /** This entity, whose issued documents of the named layouts are overwritten too (unless retained). */
    public ErasedEntity documents(String... layouts) {
        List<String> all = new ArrayList<>(documents);
        all.addAll(List.of(layouts));
        return new ErasedEntity(entity, userField, action, fields, all);
    }

    private static void requireName(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + ": " + name);
        }
    }
}
