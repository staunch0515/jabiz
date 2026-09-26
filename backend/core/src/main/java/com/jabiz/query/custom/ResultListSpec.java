package com.jabiz.query.custom;

import com.jabiz.entity.ListViewDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Outer filtering and sorting of a SQL template's result (docs/design/05-sql-template.md section 5): only the
 * result columns listed here may be filtered or sorted by.
 *
 * @param defaultSort sort applied when the caller asks for none, or null
 * @param key         result columns appended to every sort to make pagination stable; empty means all result columns
 */
public record ResultListSpec(
    List<String> filters,
    List<String> sorts,
    ListViewDefinition.Sort defaultSort,
    List<String> key
) {
    /** No filtering or sorting allowed. */
    public static final ResultListSpec NONE = new ResultListSpec(List.of(), List.of(), null, List.of());

    public ResultListSpec {
        filters = List.copyOf(filters);
        sorts = List.copyOf(sorts);
        key = List.copyOf(key);
    }

    public boolean allowsFilter(String column) {
        return filters.contains(column);
    }

    public boolean allowsSort(String column) {
        return sorts.contains(column);
    }

    public static final class Builder {
        private final List<String> filters = new ArrayList<>();
        private final List<String> sorts = new ArrayList<>();
        private final List<String> key = new ArrayList<>();
        private ListViewDefinition.Sort defaultSort;

        public Builder filters(String... columns) { filters.addAll(List.of(columns)); return this; }
        public Builder sorts(String... columns) { sorts.addAll(List.of(columns)); return this; }
        public Builder key(String... columns) { key.addAll(List.of(columns)); return this; }
        public Builder defaultSort(String column, boolean ascending) {
            defaultSort = new ListViewDefinition.Sort(column, ascending);
            return this;
        }

        public ResultListSpec build() {
            return new ResultListSpec(filters, sorts, defaultSort, key);
        }
    }
}
