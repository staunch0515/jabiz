package com.jabiz.query;

import java.util.ArrayList;
import java.util.List;

public record EntityQuery(
    QueryPredicate predicate,
    List<SortOrder> sorts,
    int offset,
    int limit
) {
    public EntityQuery {
        sorts = sorts == null ? List.of() : List.copyOf(sorts);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private QueryPredicate predicate;
        private final List<SortOrder> sorts = new ArrayList<>();
        private int offset = 0;
        private int limit = 50;

        public Builder where(QueryPredicate p) { this.predicate = p; return this; }

        /** Appends a sort key; call repeatedly for multi-column ordering. */
        public Builder orderBy(String field, boolean asc) {
            this.sorts.add(new SortOrder(field, asc));
            return this;
        }

        public Builder offset(int o) { this.offset = o; return this; }
        public Builder limit(int l) { this.limit = l; return this; }
        public EntityQuery build() { return new EntityQuery(predicate, sorts, offset, limit); }
    }
}
