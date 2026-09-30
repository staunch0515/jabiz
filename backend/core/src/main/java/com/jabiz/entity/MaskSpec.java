package com.jabiz.entity;

import java.util.Objects;

/**
 * A masked field's permission and style ({@link FieldBuilder#masked}): holders of {@code permission} may read one
 * value at a time in plain text and write the field; everyone else sees it only in {@code style}.
 */
public record MaskSpec(String permission, MaskStyle style) {
    public MaskSpec {
        if (permission == null || permission.isBlank()) {
            throw new IllegalArgumentException("a masked field needs a permission");
        }
        Objects.requireNonNull(style, "style must not be null");
    }
}
