package com.jabiz.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * How an entity is listed (docs/design/02-metamodel.md section 7): the columns shown and the fields callers may
 * filter and sort by. Filtering or sorting by any other field is rejected.
 *
 * @param defaultSort sort applied when the caller asks for none; null to sort by primary key
 */
public record ListViewDefinition(
    String name,
    List<String> columns,
    List<String> filters,
    List<String> sorts,
    Sort defaultSort
) {
    /** Name of the list view used when none is named. */
    public static final String DEFAULT = "default";

    public record Sort(String field, boolean ascending) {}

    public ListViewDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("list view name must not be blank");
        }
        columns = List.copyOf(columns);
        filters = List.copyOf(filters);
        sorts = List.copyOf(sorts);
    }

    public boolean allowsFilter(String field) {
        return filters.contains(field);
    }

    public boolean allowsSort(String field) {
        return sorts.contains(field);
    }

    public static final class Builder {
        private final String name;
        private final List<String> columns = new ArrayList<>();
        private final List<String> filters = new ArrayList<>();
        private final List<String> sorts = new ArrayList<>();
        private Sort defaultSort;

        Builder(String name) { this.name = name; }

        public Builder columns(String... fields) { columns.addAll(List.of(fields)); return this; }
        public Builder filters(String... fields) { filters.addAll(List.of(fields)); return this; }
        public Builder sorts(String... fields) { sorts.addAll(List.of(fields)); return this; }
        public Builder defaultSort(String field, boolean ascending) { defaultSort = new Sort(field, ascending); return this; }

        ListViewDefinition build() {
            return new ListViewDefinition(name, columns, filters, sorts, defaultSort);
        }
    }
}
