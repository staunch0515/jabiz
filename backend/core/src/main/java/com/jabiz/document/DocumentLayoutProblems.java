package com.jabiz.document;

import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.template.TemplateSchemas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What is wrong with a document layout against the application's SQL templates (docs/design/22-documents.md section
 * 2.2), all at once: the platform reports these at startup instead of when a document is first issued.
 */
public final class DocumentLayoutProblems {

    /**
     * @param location where: the layout, or the layout and a template
     * @param message  what is wrong
     */
    public record Problem(String location, String message) {}

    private DocumentLayoutProblems() {}

    /**
     * @param templates the template of an id, with its parameter and result kinds resolved; empty when there is none
     */
    public static List<Problem> of(DocumentLayout layout, Function<String, Optional<AdvancedQueryDefinition>> templates) {
        List<Problem> problems = new ArrayList<>();
        String where = layout.id();
        if (layout.permissions().isEmpty()) {
            problems.add(new Problem(where, "declares no permissions"));
        }
        Map<String, QueryParameter> params = new LinkedHashMap<>();
        Map<String, String> paramSource = new LinkedHashMap<>();
        for (String id : layout.templates()) {
            String at = where + " | " + id;
            Optional<AdvancedQueryDefinition> found = templates.apply(id);
            if (found.isEmpty()) {
                problems.add(new Problem(at, "no such template"));
                continue;
            }
            AdvancedQueryDefinition template = found.get();
            if (template.publicAccess()) {
                problems.add(new Problem(at, "is a public template; documents read with the issuer's permissions"));
            }
            if (template.timeSlice() != null) {
                problems.add(new Problem(at, "declares timeSlice; a document is read at the point in time it is"
                    + " issued at, the same for all its templates"));
            }
            boolean single = layout.singleRow(id);
            boolean table = layout.blocks().stream().anyMatch(b -> id.equals(b.template()) && !b.singleRow());
            if (single && table) {
                problems.add(new Problem(at, "is read both for one row and as a table"));
            }
            for (String column : layout.columnsOf(id)) {
                if (template.result(column).isEmpty()) {
                    problems.add(new Problem(at, "has no result column " + column));
                }
            }
            for (QueryParameter parameter : template.parameters()) {
                QueryParameter earlier = params.putIfAbsent(parameter.name(), parameter);
                if (earlier == null) {
                    paramSource.put(parameter.name(), id);
                } else if (!sameKind(earlier, parameter)) {
                    problems.add(new Problem(at, "parameter " + parameter.name() + " differs from the one of "
                        + paramSource.get(parameter.name())));
                }
            }
        }
        if (layout.subjectParam() != null && !params.containsKey(layout.subjectParam())) {
            problems.add(new Problem(where, "no template has the subject parameter " + layout.subjectParam()));
        }
        return problems;
    }

    /**
     * Whether the two take the same values: the same form in the parameters' schema (a reference and the identity it
     * points to are the same uuid), descriptions and defaults aside.
     */
    private static boolean sameKind(QueryParameter a, QueryParameter b) {
        return TemplateSchemas.values(a).equals(TemplateSchemas.values(b));
    }
}
