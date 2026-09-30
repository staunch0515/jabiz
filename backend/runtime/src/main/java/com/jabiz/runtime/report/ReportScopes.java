package com.jabiz.runtime.report;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.ScopeUnavailableException;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The data scope of an issued report (docs/design/19-reports.md section 5.3): the values the issuer's request gave
 * the scopes that depend on the caller (decision D3). An archived run holds rows only callers with those values see,
 * so only such callers may read it - default deny: a dataset gone, or a scope the reader cannot resolve, reads as
 * different.
 */
@Component
public class ReportScopes {

    private final SqlTemplateRegistry templates;
    private final DatasetRegistry datasets;

    public ReportScopes(SqlTemplateRegistry templates, DatasetRegistry datasets) {
        this.templates = templates;
        this.datasets = datasets;
    }

    /** By dataset id, the caller-dependent scope values of the template's datasets, as text. */
    Map<String, Map<String, String>> of(AdvancedQueryDefinition query, RequestContext request) {
        Map<String, Map<String, String>> scopes = new TreeMap<>();
        for (DatasetDefinition dataset : templates.datasetsOf(query).values()) {
            if (dataset.scope().isDynamic()) {
                scopes.put(dataset.resourceId(), text(dataset.scope().resolve(request)));
            }
        }
        return scopes;
    }

    /** Whether the reader's request gives every archived scope the same values. */
    public boolean matches(Map<String, Map<String, String>> archived, RequestContext reader) {
        for (Map.Entry<String, Map<String, String>> entry : archived.entrySet()) {
            Optional<DatasetDefinition> dataset = datasets.findById(entry.getKey());
            if (dataset.isEmpty()) {
                return false;
            }
            try {
                if (!text(dataset.get().scope().resolve(reader)).equals(entry.getValue())) {
                    return false;
                }
            } catch (ScopeUnavailableException unavailable) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> text(Map<String, Object> values) {
        Map<String, String> text = new LinkedHashMap<>();
        new TreeMap<>(values).forEach((field, value) -> text.put(field, String.valueOf(value)));
        return text;
    }
}
