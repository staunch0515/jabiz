package com.jabiz.dataset;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Declares a dataset readable by anonymous visitors (docs/design/15-public-access.md section 2; decision D17): the
 * rows are those of the dataset's fixed scope, the columns only the whitelisted fields. Public templates read such a
 * dataset as a projection of the whitelist, so SQL naming any other column finds nothing.
 *
 * @param allRows whether the dataset deliberately has no scope (every row is public)
 * @param fields  logical fields anonymous visitors may read, in declaration order
 */
public record PublicRead(boolean allRows, List<String> fields) {

    public PublicRead {
        fields = List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("publicRead needs at least one field");
        }
        if (new LinkedHashSet<>(fields).size() != fields.size()) {
            throw new IllegalArgumentException("publicRead lists a field twice: " + fields);
        }
    }

    public boolean allows(String field) {
        return fields.contains(field);
    }

    public static final class Builder {
        private final List<String> fields = new ArrayList<>();
        private boolean allRows;

        /** Fields anonymous visitors may read. */
        public Builder fields(String... names) {
            fields.addAll(List.of(names));
            return this;
        }

        /** States that the dataset has no scope on purpose: every row of the entity is public. */
        public Builder allRows() {
            this.allRows = true;
            return this;
        }

        PublicRead build() {
            return new PublicRead(allRows, fields);
        }
    }
}
