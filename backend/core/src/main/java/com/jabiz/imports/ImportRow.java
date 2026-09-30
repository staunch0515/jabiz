package com.jabiz.imports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One row of an import after conversion: the values of the import's fields in their canonical types (text,
 * {@link BigDecimal}, {@link Instant}, {@link Boolean}, {@link Long}); a field without a value is null.
 *
 * @param number   1-based among the file's records
 * @param location where it is in the file, for people
 * @param formulas fields whose value a spreadsheet formula calculated
 */
public record ImportRow(int number, String location, Map<String, Object> values, Set<String> formulas) {

    public ImportRow {
        Objects.requireNonNull(location, "location must not be null");
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        formulas = Set.copyOf(formulas);
    }

    public Object get(String field) {
        return values.get(field);
    }

    public String text(String field) {
        Object value = values.get(field);
        return value == null ? null : value.toString();
    }

    public BigDecimal decimal(String field) {
        return (BigDecimal) values.get(field);
    }

    public Instant instant(String field) {
        return (Instant) values.get(field);
    }

    public Boolean bool(String field) {
        return (Boolean) values.get(field);
    }

    public Long integer(String field) {
        return (Long) values.get(field);
    }
}
