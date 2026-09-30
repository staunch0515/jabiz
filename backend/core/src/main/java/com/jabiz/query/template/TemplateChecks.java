package com.jabiz.query.template;

import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.QueryParameter;
import com.jabiz.query.custom.ResultListSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks of a SQL template that need the metamodel but not the database (docs/design/05-sql-template.md sections 2
 * to 6), and resolution of the semantic kinds parameters and result columns inherit through {@code like} and
 * {@code from}. The database-side checks (result columns, types, parameters) are the runtime's precompile check.
 */
public final class TemplateChecks {

    public static final String SCOPE_PREFIX = "scope_";
    public static final String RESERVED_PREFIX = "__";

    private static final Set<String> OUTER_CLAUSES = Set.of("LIMIT", "OFFSET", "FETCH");
    private static final Set<String> OUTER_ORDER = Set.of("ORDER");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)ORDER\\s+BY\\b");
    private static final Pattern RESULT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ARRAY_CONTEXT = Pattern.compile("(?i)(ANY|ALL)\\s*\\(\\s*$");

    /** The query with inherited kinds filled in, and the problems of resolving them. */
    public record Resolved(AdvancedQueryDefinition query, List<TemplateProblem> problems) {}

    private TemplateChecks() {}

    /** Fills in the kinds of {@code like} parameters and {@code from} results that declare none. */
    public static Resolved resolve(AdvancedQueryDefinition query,
        Function<String, Optional<EntityDefinition>> entities) {
        List<TemplateProblem> problems = new ArrayList<>();
        List<QueryParameter> parameters = new ArrayList<>();
        for (QueryParameter parameter : query.parameters()) {
            if (parameter.kind() == null) {
                Optional<FieldDefinition> source = source(query, parameter.likeEntity(), parameter.likeField(),
                    "parameter " + parameter.name(), entities, problems);
                parameters.add(source.map(f -> parameter.withKind(f.kind())).orElse(parameter));
            } else {
                parameters.add(parameter);
            }
        }
        List<ProjectedField> results = new ArrayList<>();
        for (ProjectedField field : query.resultFields()) {
            if (field.sourceEntity() != null) {
                Optional<FieldDefinition> source = source(query, field.sourceEntity(), field.sourceField(),
                    "result " + field.name(), entities, problems);
                results.add(field.kind() == null ? source.map(f -> field.withKind(f.kind())).orElse(field) : field);
            } else {
                results.add(field);
            }
        }
        return new Resolved(query.withFields(parameters, results), problems);
    }

    /**
     * Everything that can be checked without a database: entities, placeholders, parameters and their use (list
     * parameters as {@code = ANY(:name)}, no {@code IN (:name)} - decision D7), no outer {@code LIMIT}/{@code OFFSET},
     * result names and the list whitelist; and, as warnings, words of the SQL that are physical names of the
     * participating entities (tables and columns belong in placeholders).
     *
     * @param query a query whose kinds have been {@linkplain #resolve resolved}
     */
    public static List<TemplateProblem> check(AdvancedQueryDefinition query,
        Function<String, Optional<EntityDefinition>> entities) {
        List<TemplateProblem> problems = new ArrayList<>();
        String template = query.sqlTemplate();
        String masked = SqlText.mask(template);

        if (query.participatingEntities().isEmpty()) {
            problems.add(TemplateProblem.header("entities must name at least one entity"));
        }
        Map<String, EntityDefinition> known = new HashMap<>();
        for (String name : query.participatingEntities()) {
            entities.apply(name).ifPresentOrElse(def -> known.put(name, def),
                () -> problems.add(TemplateProblem.header("unknown entity " + name + " in entities")));
        }
        query.datasets().keySet().stream().filter(e -> !query.participatingEntities().contains(e))
            .forEach(e -> problems.add(TemplateProblem.header("datasets names " + e + ", which is not in entities")));

        for (SqlText.Placeholder placeholder : SqlText.placeholders(masked)) {
            if (!query.participatingEntities().contains(placeholder.entity())) {
                problems.add(TemplateProblem.error(placeholder.start(),
                    "entity " + placeholder.entity() + " is not declared in entities"));
            } else if (placeholder.field() != null && known.containsKey(placeholder.entity())
                && known.get(placeholder.entity()).findField(placeholder.field()).isEmpty()) {
                problems.add(TemplateProblem.error(placeholder.start(),
                    "unknown field " + placeholder.entity() + "." + placeholder.field()));
            } else if (placeholder.field() != null && known.containsKey(placeholder.entity())
                && known.get(placeholder.entity()).field(placeholder.field()).sensitive()) {
                // Sensitive values never leave through a read (docs/design/10-security.md).
                problems.add(TemplateProblem.error(placeholder.start(),
                    "field " + placeholder.entity() + "." + placeholder.field() + " is sensitive and cannot be read"));
            }
        }
        checkParameters(query, masked, problems);
        String code = withoutPlaceholders(masked);
        for (int offset : SqlText.topLevelKeywords(code, OUTER_CLAUSES)) {
            problems.add(TemplateProblem.error(offset,
                "the template must not limit its outer result (LIMIT/OFFSET/FETCH); the platform pages it"));
        }
        for (int offset : SqlText.topLevelKeywords(code, OUTER_ORDER)) {
            if (!ORDER_BY.matcher(code).region(offset, code.length()).lookingAt()) {
                continue;
            }
            problems.add(TemplateProblem.warning(offset, "a top-level ORDER BY has no effect: the platform sorts the"
                + " result (list.defaultSort, then the stable key)"));
        }
        checkResults(query, problems);
        checkList(query, problems);
        checkPointInTime(query, problems);
        warnBarePhysicalNames(query, code, known, problems);
        return problems;
    }

    private static void checkParameters(AdvancedQueryDefinition query, String masked, List<TemplateProblem> problems) {
        Map<String, QueryParameter> declared = new HashMap<>();
        for (QueryParameter parameter : query.parameters()) {
            if (declared.put(parameter.name(), parameter) != null) {
                problems.add(TemplateProblem.header("parameter " + parameter.name() + " is declared twice"));
            }
            if (parameter.name().startsWith(SCOPE_PREFIX) || parameter.name().startsWith(RESERVED_PREFIX)) {
                problems.add(TemplateProblem.header("parameter " + parameter.name() + " uses a reserved prefix ("
                    + SCOPE_PREFIX + ", " + RESERVED_PREFIX + ")"));
            }
            if (parameter.kind() == null && parameter.likeEntity() == null) {
                problems.add(TemplateProblem.header("parameter " + parameter.name() + " has no kind"));
            }
            requireSupportedKind(parameter.kind(), "parameter " + parameter.name(), problems);
        }
        Set<String> used = new HashSet<>();
        Map<String, Integer> listUses = new HashMap<>();
        Set<String> arrayUses = new HashSet<>();
        for (SqlText.ParameterRef ref : SqlText.parameters(masked)) {
            used.add(ref.name());
            QueryParameter parameter = declared.get(ref.name());
            if (parameter == null) {
                problems.add(TemplateProblem.error(ref.start(), "parameter :" + ref.name() + " is not declared"));
            } else if (parameter.list()) {
                listUses.putIfAbsent(ref.name(), ref.start());
                if (ARRAY_CONTEXT.matcher(masked.substring(Math.max(0, ref.start() - 40), ref.start())).find()) {
                    arrayUses.add(ref.name());
                }
            }
        }
        // Other uses, such as CAST(:ids AS uuid[]) IS NULL for an optional list, are fine besides.
        listUses.forEach((name, offset) -> {
            if (!arrayUses.contains(name)) {
                problems.add(TemplateProblem.error(offset, "list parameter :" + name + " must be used as = ANY(:"
                    + name + ") or <> ALL(:" + name + ")"));
            }
        });
        for (SqlText.ParameterRef ref : SqlText.inListParameters(masked)) {
            problems.add(TemplateProblem.error(ref.start(), "IN (:" + ref.name() + ") is not allowed; write = ANY(:"
                + ref.name() + ") with a list parameter (decision D7)"));
        }
        for (QueryParameter parameter : query.parameters()) {
            if (!used.contains(parameter.name())) {
                problems.add(TemplateProblem.header("parameter " + parameter.name() + " is declared but not used"));
            }
        }
    }

    private static void checkResults(AdvancedQueryDefinition query, List<TemplateProblem> problems) {
        Set<String> names = new HashSet<>();
        for (ProjectedField field : query.resultFields()) {
            if (!RESULT_NAME.matcher(field.name()).matches()) {
                problems.add(TemplateProblem.header("result " + field.name() + " is not a plain SQL name"));
            }
            if (!names.add(field.name().toLowerCase(Locale.ROOT))) {
                problems.add(TemplateProblem.header("result " + field.name()
                    + " is declared twice (result names are case-insensitive)"));
            }
            if (field.kind() == null && field.sourceEntity() == null) {
                problems.add(TemplateProblem.header("result " + field.name() + " has no kind"));
            }
            requireSupportedKind(field.kind(), "result " + field.name(), problems);
        }
    }

    private static void checkList(AdvancedQueryDefinition query, List<TemplateProblem> problems) {
        ResultListSpec list = query.list();
        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(list.filters());
        referenced.addAll(list.sorts());
        referenced.addAll(list.key());
        if (list.defaultSort() != null) {
            referenced.add(list.defaultSort().field());
            if (!list.allowsSort(list.defaultSort().field())) {
                problems.add(TemplateProblem.header("list.defaultSort " + list.defaultSort().field()
                    + " is not in list.sorts"));
            }
        }
        for (String name : referenced) {
            if (query.resultFields().stream().noneMatch(f -> f.name().equals(name))) {
                problems.add(TemplateProblem.header("list refers to " + name + ", which is not a result column"));
            }
        }
    }

    /**
     * The parameters {@code timeSlice} and {@code report.period} name must be declared single temporal parameters;
     * public queries take no point in time (docs/design/19-reports.md sections 2.2 and 3.1).
     */
    private static void checkPointInTime(AdvancedQueryDefinition query, List<TemplateProblem> problems) {
        if (query.timeSlice() != null) {
            if (query.publicAccess()) {
                problems.add(TemplateProblem.header("a public query cannot declare timeSlice: anonymous visitors read"
                    + " the current state only"));
            }
            if (query.timeSlice().asOf() == null && query.timeSlice().knownAt() == null) {
                problems.add(TemplateProblem.header("timeSlice must name asOf, knownAt or both"));
            }
            requireTimeParameter(query, query.timeSlice().asOf(), "timeSlice.asOf", problems);
            requireTimeParameter(query, query.timeSlice().knownAt(), "timeSlice.knownAt", problems);
        }
        if (query.report() != null) {
            requireTimeParameter(query, query.report().periodFrom(), "report.period.from", problems);
            requireTimeParameter(query, query.report().periodTo(), "report.period.to", problems);
        }
    }

    private static void requireTimeParameter(AdvancedQueryDefinition query, String name, String where,
        List<TemplateProblem> problems) {
        if (name == null) {
            return;
        }
        Optional<QueryParameter> parameter = query.parameters().stream().filter(p -> p.name().equals(name))
            .findFirst();
        if (parameter.isEmpty()) {
            problems.add(TemplateProblem.header(where + " names " + name + ", which is not a declared parameter"));
        } else if (parameter.get().list()) {
            problems.add(TemplateProblem.header(where + " names " + name + ", which is a list parameter"));
        } else if (parameter.get().kind() != null && !(parameter.get().kind() instanceof SemanticKind.Temporal)) {
            problems.add(TemplateProblem.header(where + " names " + name + ", which is not a temporal parameter"));
        }
    }

    private static void warnBarePhysicalNames(AdvancedQueryDefinition query, String code,
        Map<String, EntityDefinition> entities, List<TemplateProblem> problems) {
        Map<String, String> physical = new HashMap<>();
        for (EntityDefinition def : entities.values()) {
            physical.put(def.physicalTable.toLowerCase(Locale.ROOT), "table of " + def.name);
            for (FieldDefinition field : def.fields.values()) {
                physical.putIfAbsent(field.physicalColumn().toLowerCase(Locale.ROOT),
                    "column of " + def.name + "." + field.name());
            }
        }
        Set<String> exempt = new HashSet<>();
        query.resultFields().forEach(f -> exempt.add(f.name().toLowerCase(Locale.ROOT)));
        query.parameters().forEach(p -> exempt.add(p.name().toLowerCase(Locale.ROOT)));
        for (SqlText.ParameterRef word : SqlText.words(code)) {
            String lower = word.name().toLowerCase(Locale.ROOT);
            String what = physical.get(lower);
            if (what != null && !exempt.contains(lower)) {
                problems.add(TemplateProblem.warning(word.start(), "bare physical name " + word.name() + " ("
                    + what + "); use a placeholder so the template follows the metamodel"));
            }
        }
    }

    /** The masked SQL with its placeholders blanked too: only the template's own SQL code remains. */
    private static String withoutPlaceholders(String masked) {
        StringBuilder code = new StringBuilder(masked);
        for (SqlText.Placeholder placeholder : SqlText.placeholders(masked)) {
            for (int i = placeholder.start(); i < placeholder.end(); i++) {
                code.setCharAt(i, ' ');
            }
        }
        return code.toString();
    }

    private static void requireSupportedKind(SemanticKind kind, String what, List<TemplateProblem> problems) {
        if (kind instanceof SemanticKind.Custom custom && CustomKinds.find(custom.kindId()).isEmpty()) {
            problems.add(TemplateProblem.header(what + " uses custom kind " + custom.kindId()
                + ", which has no registered CustomKindSupport"));
        }
    }

    private static Optional<FieldDefinition> source(AdvancedQueryDefinition query, String entity, String field,
        String what, Function<String, Optional<EntityDefinition>> entities, List<TemplateProblem> problems) {
        if (entity == null || field == null) {
            problems.add(TemplateProblem.header(what + " has no kind and no source field"));
            return Optional.empty();
        }
        if (!query.participatingEntities().contains(entity)) {
            problems.add(TemplateProblem.header(what + " refers to " + entity + "." + field + ", but " + entity
                + " is not in entities"));
            return Optional.empty();
        }
        Optional<EntityDefinition> def = entities.apply(entity);
        if (def.isEmpty()) {
            problems.add(TemplateProblem.header(what + " refers to unknown entity " + entity));
            return Optional.empty();
        }
        Optional<FieldDefinition> found = def.get().findField(field);
        if (found.isEmpty()) {
            problems.add(TemplateProblem.header(what + " refers to unknown field " + entity + "." + field));
        } else if (found.get().sensitive()) {
            problems.add(TemplateProblem.header(what + " refers to sensitive field " + entity + "." + field));
        }
        return found;
    }
}
