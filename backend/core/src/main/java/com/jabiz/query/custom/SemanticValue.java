package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

public record SemanticValue(Object value, SemanticKind kind) {
    public <T> T as(Class<T> clazz) {
        return clazz.cast(value);
    }
}
