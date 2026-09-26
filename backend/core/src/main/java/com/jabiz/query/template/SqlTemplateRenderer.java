package com.jabiz.query.template;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.query.TimeSlice;
import com.jabiz.query.custom.AdvancedQueryDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a SQL template (docs/design/05-sql-template.md section 3). Every {@code {{Entity}}} becomes the entity as
 * its dataset shows it, via {@link QueryCompiler#templateExpression}: the scope and soft-delete exclusion of the
 * dataset, and for a temporal entity its versions in effect with tombstones and the scope applied outside the
 * version sub-select (decisions D3, D10). {@code {{Entity.field}}} becomes the physical column. Everything else is
 * copied verbatim, so the rendered SQL keeps the template's {@code :name} parameters.
 */
public final class SqlTemplateRenderer {

    /**
     * How one entity is read by a template.
     *
     * @param scope the dataset scope resolved for the caller
     */
    public record EntityBinding(EntityDefinition entity, DatasetDefinition dataset, Map<String, Object> scope) {
        public EntityBinding {
            Objects.requireNonNull(entity, "entity must not be null");
            Objects.requireNonNull(dataset, "dataset must not be null");
            scope = Map.copyOf(scope);
        }
    }

    /** One replaced placeholder: its range in the rendered SQL and in the template. */
    public record Segment(int renderedStart, int renderedEnd, int templateStart, int templateEnd) {}

    /** Rendered SQL together with the mapping back to the template, for locating database errors. */
    public record Rendered(String sql, List<Segment> segments) {
        public Rendered {
            segments = List.copyOf(segments);
        }

        /** Offset in the template of an offset in the rendered SQL; inside a placeholder, the placeholder start. */
        public int templateOffset(int renderedOffset) {
            int delta = 0;
            for (Segment segment : segments) {
                if (renderedOffset < segment.renderedStart()) {
                    break;
                }
                if (renderedOffset < segment.renderedEnd()) {
                    return segment.templateStart();
                }
                delta += (segment.renderedEnd() - segment.renderedStart())
                    - (segment.templateEnd() - segment.templateStart());
            }
            return renderedOffset - delta;
        }
    }

    private final QueryCompiler compiler;

    public SqlTemplateRenderer(QueryCompiler compiler) {
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
    }

    /**
     * @param bindings how each participating entity is read, by entity name
     * @param slice    point in time of temporal reads
     * @param binder   receives the platform's parameters (scope values, {@code __asOf})
     * @throws SqlTemplateException if a placeholder names an undeclared entity or an unknown field
     */
    public Rendered render(AdvancedQueryDefinition query, Map<String, EntityBinding> bindings, TimeSlice slice,
        QueryCompiler.Binder binder) {
        String template = query.sqlTemplate();
        List<TemplateProblem> problems = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        StringBuilder out = new StringBuilder(template.length() * 2);
        int copied = 0;
        for (SqlText.Placeholder placeholder : SqlText.placeholders(SqlText.mask(template))) {
            out.append(template, copied, placeholder.start());
            copied = placeholder.end();
            String replacement = replacement(query, placeholder, bindings, slice, binder, problems);
            int start = out.length();
            out.append(replacement);
            segments.add(new Segment(start, out.length(), placeholder.start(), placeholder.end()));
        }
        out.append(template, copied, template.length());
        if (!problems.isEmpty()) {
            throw new SqlTemplateException(query.queryId(), problems);
        }
        return new Rendered(out.toString(), segments);
    }

    private String replacement(AdvancedQueryDefinition query, SqlText.Placeholder placeholder,
        Map<String, EntityBinding> bindings, TimeSlice slice, QueryCompiler.Binder binder,
        List<TemplateProblem> problems) {
        String entityName = placeholder.entity();
        if (!query.participatingEntities().contains(entityName)) {
            problems.add(TemplateProblem.error(placeholder.start(),
                "entity " + entityName + " is not declared in entities"));
            return "";
        }
        EntityBinding binding = bindings.get(entityName);
        if (binding == null) {
            problems.add(TemplateProblem.error(placeholder.start(), "unknown entity " + entityName));
            return "";
        }
        EntityDefinition def = binding.entity();
        if (placeholder.field() == null) {
            return compiler.templateExpression(binding.dataset(), def, binding.scope(), slice, binder);
        }
        if (def.findField(placeholder.field()).isEmpty()) {
            problems.add(TemplateProblem.error(placeholder.start(),
                "unknown field " + entityName + "." + placeholder.field()));
            return "";
        }
        return SqlIdentifiers.require(def.physicalColumn(placeholder.field()));
    }
}
