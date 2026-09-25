package com.jabiz.runtime.dictionary;

import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dictionary whose entries come from an SQL template (docs/design/02-metamodel.md section 5), declared as a
 * bean. The query returns the columns {@code code} and {@code label}, optionally {@code sortOrder} and
 * {@code enabled}; if it declares a parameter {@value #LOCALE_PARAMETER}, it receives the language tag the
 * labels are wanted in. It runs through the given dataset with the system identity.
 *
 * <p>Changes to the underlying tables are not observed, so entries are cached for {@code ttl} at most; a
 * {@code NOTIFY jabiz_dict_changed, '<urn>'} evicts them earlier.
 */
public record SqlDictionary(String urn, String datasetId, AdvancedQueryDefinition query, Duration ttl) {

    public static final String LOCALE_PARAMETER = "locale";

    public SqlDictionary {
        if (urn == null || urn.isBlank()) {
            throw new IllegalArgumentException("dictionary urn must not be blank");
        }
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        Set<String> columns = query.resultFields().stream().map(ProjectedField::name).collect(Collectors.toSet());
        if (!columns.contains("code") || !columns.contains("label")) {
            throw new IllegalArgumentException("SQL dictionary " + urn + " must return the columns code and label");
        }
    }

    public boolean localized() {
        return query.parameters().stream().anyMatch(p -> p.name().equals(LOCALE_PARAMETER));
    }
}
