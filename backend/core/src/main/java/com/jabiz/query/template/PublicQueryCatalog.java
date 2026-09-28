package com.jabiz.query.template;

import com.jabiz.entity.MetaModelExporter;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.ResultListSpec;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract of the public templates for public front ends (docs/design/15-public-access.md section 7): per
 * template its parameters, result columns, outer filters and sorts, and cache lifetime. Applications compare it with
 * a snapshot in the repository and generate front-end types from that snapshot, as they do with the OpenAPI document.
 */
public final class PublicQueryCatalog {

    private PublicQueryCatalog() {}

    /**
     * @param queries templates with their kinds resolved; those that are not public are left out
     * @param defaultCacheSeconds {@code cacheSeconds} of templates that declare none
     * @return {@code {"queries": [...]}}, ordered by id so that the snapshot is stable
     */
    public static Map<String, Object> export(Collection<AdvancedQueryDefinition> queries, int defaultCacheSeconds) {
        List<Map<String, Object>> entries = queries.stream()
            .filter(AdvancedQueryDefinition::publicAccess)
            .sorted(Comparator.comparing(AdvancedQueryDefinition::queryId))
            .map(query -> entry(query, defaultCacheSeconds))
            .toList();
        return Map.of("queries", entries);
    }

    private static Map<String, Object> entry(AdvancedQueryDefinition query, int defaultCacheSeconds) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", query.queryId());
        if (!query.description().isBlank()) {
            entry.put("description", query.description());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (QueryParameter parameter : query.parameters()) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("kind", MetaModelExporter.kindToJson(parameter.kind()));
            spec.put("list", parameter.list());
            spec.put("required", parameter.required());
            params.put(parameter.name(), spec);
        }
        entry.put("params", params);
        Map<String, Object> results = new LinkedHashMap<>();
        for (ProjectedField field : query.resultFields()) {
            results.put(field.name(), MetaModelExporter.kindToJson(field.kind()));
        }
        entry.put("results", results);
        ResultListSpec list = query.list();
        Map<String, Object> listJson = new LinkedHashMap<>();
        listJson.put("filters", list.filters());
        listJson.put("sorts", list.sorts());
        if (list.defaultSort() != null) {
            listJson.put("defaultSort", Map.of("field", list.defaultSort().field(), "asc",
                list.defaultSort().ascending()));
        }
        entry.put("list", listJson);
        entry.put("cacheSeconds", query.cacheSeconds() == null ? defaultCacheSeconds : query.cacheSeconds());
        return entry;
    }
}
