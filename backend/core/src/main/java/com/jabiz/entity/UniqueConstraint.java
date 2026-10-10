package com.jabiz.entity;

import java.util.List;

/**
 * Values of {@code fields} are unique among the instances of an entity (docs/design/02-metamodel.md section 6).
 * For ordinary entities the database enforces it with a unique index of the same name.
 *
 * @param ignoreCase text values that differ in case only count as the same (decision D36: {@code A@x.com} and
 *                   {@code a@x.com} are one address); the fields must be text, and the supporting index is on
 *                   {@code lower(column)}
 */
public record UniqueConstraint(String name, List<String> fields, boolean ignoreCase) {

    public UniqueConstraint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("unique constraint name must not be blank");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("unique constraint " + name + " declares no fields");
        }
        fields = List.copyOf(fields);
    }

    /** A constraint that compares values exactly. */
    public UniqueConstraint(String name, List<String> fields) {
        this(name, fields, false);
    }
}
