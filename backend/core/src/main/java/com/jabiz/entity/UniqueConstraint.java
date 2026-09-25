package com.jabiz.entity;

import java.util.List;

/**
 * Values of {@code fields} are unique among the instances of an entity (docs/design/02-metamodel.md section 6).
 * For ordinary entities the database enforces it with a unique index of the same name.
 */
public record UniqueConstraint(String name, List<String> fields) {

    public UniqueConstraint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("unique constraint name must not be blank");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("unique constraint " + name + " declares no fields");
        }
        fields = List.copyOf(fields);
    }
}
