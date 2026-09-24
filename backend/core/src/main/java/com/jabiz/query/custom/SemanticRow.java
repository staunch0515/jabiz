package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SemanticRow {
    private final Map<String, SemanticValue> columns = new LinkedHashMap<>();

    public void put(String fieldName, Object rawValue, SemanticKind kind) {
        columns.put(fieldName, new SemanticValue(rawValue, kind));
    }

    public SemanticValue get(String fieldName) {
        return columns.get(fieldName);
    }

    public Object getRaw(String fieldName) {
        SemanticValue sv = columns.get(fieldName);
        return sv != null ? sv.value() : null;
    }

    public Map<String, SemanticValue> getAllColumns() {
        return Collections.unmodifiableMap(columns);
    }
}
