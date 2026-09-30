package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A SQL template (docs/design/05-sql-template.md): a hand-written query spanning several entities, declared either
 * in a {@code queries/**}{@code /*.sql} file or with {@link #define}. Both compile to this record.
 *
 * The SQL template may contain placeholders resolved through the metamodel:
 * <ul>
 *   <li>{@code {{Entity}}} - the entity as its dataset shows it: scoped, without soft-deleted rows and, for a temporal
 *       entity, its versions in effect (decision D10); always give it an alias in the template</li>
 *   <li>{@code {{Entity.field}}} - the physical column of a logical field, unqualified</li>
 * </ul>
 * Only entities listed in {@code participatingEntities} may be referenced.
 *
 * @param datasets        dataset through which an entity is read, by entity name; entities not listed use their
 *                        default dataset
 * @param list            outer filters and sorts callers may use
 * @param permissions     permissions required to run the query through the API; empty means undeclared
 * @param source          where the query was declared, for problem reports
 * @param timeoutOverride optional per-query timeout; the effective timeout never exceeds the datasets'
 * @param publicAccess    whether anonymous visitors may run the query ({@code access: public}); it then declares no
 *                        permissions and reads public datasets only (docs/design/15-public-access.md section 3)
 * @param cacheSeconds    {@code max-age} of public responses, or null for the platform default; public queries only
 * @param timeSlice       parameters giving the point in time temporal entities are read at, or null
 *                        (docs/design/19-reports.md section 2.2)
 * @param report          how the query shows as a report, or null when it is not one (19 section 3.1)
 * @param version         SHA-256 of the template file, or of the definition for Java-declared queries (19 section 2.3)
 */
public record AdvancedQueryDefinition(
        String queryId,
        String description,
        List<String> participatingEntities,
        List<QueryParameter> parameters,
        List<ProjectedField> resultFields,
        String sqlTemplate,
        Duration timeoutOverride,
        Map<String, String> datasets,
        ResultListSpec list,
        List<String> permissions,
        TemplateSource source,
        boolean publicAccess,
        Integer cacheSeconds,
        TemplateTimeSlice timeSlice,
        ReportSpec report,
        String version
) {
    public AdvancedQueryDefinition {
        participatingEntities = List.copyOf(participatingEntities);
        parameters = List.copyOf(parameters);
        resultFields = List.copyOf(resultFields);
        datasets = Map.copyOf(datasets);
        list = list == null ? ResultListSpec.NONE : list;
        permissions = List.copyOf(permissions);
    }

    public static AdvancedQueryDefinition define(String queryId, Consumer<Builder> consumer) {
        Builder builder = new Builder(queryId);
        consumer.accept(builder);
        return builder.build();
    }

    /** The same query with the parameters and result columns replaced (used to fill in inherited kinds). */
    public AdvancedQueryDefinition withFields(List<QueryParameter> newParameters, List<ProjectedField> newResults) {
        return new AdvancedQueryDefinition(queryId, description, participatingEntities, newParameters, newResults,
            sqlTemplate, timeoutOverride, datasets, list, permissions, source, publicAccess, cacheSeconds, timeSlice, report,
            version);
    }

    /** The same query reading {@code entity} through another dataset. */
    public AdvancedQueryDefinition withDataset(String entity, String datasetId) {
        Map<String, String> merged = new LinkedHashMap<>(datasets);
        merged.put(entity, datasetId);
        return new AdvancedQueryDefinition(queryId, description, participatingEntities, parameters, resultFields,
            sqlTemplate, timeoutOverride, merged, list, permissions, source, publicAccess, cacheSeconds, timeSlice, report,
            version);
    }

    /** The result column of that name, compared case-insensitively. */
    public Optional<ProjectedField> result(String name) {
        return resultFields.stream().filter(f -> f.name().equalsIgnoreCase(name)).findFirst();
    }

    public static class Builder {
        private final String queryId;
        private String description = "";
        private final List<String> entities = new ArrayList<>();
        private final List<QueryParameter> parameters = new ArrayList<>();
        private final List<ProjectedField> resultFields = new ArrayList<>();
        private final Map<String, String> datasets = new LinkedHashMap<>();
        private final List<String> permissions = new ArrayList<>();
        private ResultListSpec list;
        private String sqlTemplate;
        private Duration timeout;
        private TemplateSource source;
        private boolean publicAccess;
        private Integer cacheSeconds;
        private TemplateTimeSlice timeSlice;
        private ReportSpec report;
        private String version;

        public Builder(String queryId) { this.queryId = queryId; }

        public Builder description(String d) { this.description = d; return this; }

        public Builder fromEntities(String... entityNames) {
            this.entities.addAll(List.of(entityNames));
            return this;
        }

        /** Reads {@code entity} through the given dataset instead of its default one. */
        public Builder dataset(String entity, String datasetId) {
            this.datasets.put(entity, datasetId);
            return this;
        }

        public Builder parameter(QueryParameter parameter) {
            this.parameters.add(parameter);
            return this;
        }

        public Builder returns(ProjectedField field) {
            this.resultFields.add(field);
            return this;
        }

        public Builder parameter(String name, SemanticKind kind, boolean required) {
            this.parameters.add(QueryParameter.of(name, kind, required));
            return this;
        }

        public Builder parameterWithDefault(String name, SemanticKind kind, Object defaultValue) {
            this.parameters.add(QueryParameter.withDefault(name, kind, defaultValue));
            return this;
        }

        /** A list parameter, used in the template as {@code = ANY(:name)} or {@code <> ALL(:name)}. */
        public Builder listParameter(String name, SemanticKind kind, boolean required) {
            this.parameters.add(QueryParameter.listOf(name, kind, required));
            return this;
        }

        /** A parameter with the semantic type of {@code entity.field}. */
        public Builder parameterLike(String name, String entity, String field, boolean required, boolean list) {
            this.parameters.add(QueryParameter.like(name, entity, field, required, list));
            return this;
        }

        public Builder returns(String name, SemanticKind kind) {
            this.resultFields.add(ProjectedField.of(name, kind));
            return this;
        }

        public Builder returns(String name, SemanticKind kind, String sourceEntity, String sourceField) {
            this.resultFields.add(ProjectedField.from(name, kind, sourceEntity, sourceField));
            return this;
        }

        /** A result column with the semantic type of {@code entity.field}. */
        public Builder returnsFrom(String name, String sourceEntity, String sourceField) {
            this.resultFields.add(ProjectedField.inherit(name, sourceEntity, sourceField));
            return this;
        }

        public Builder list(Consumer<ResultListSpec.Builder> consumer) {
            ResultListSpec.Builder builder = new ResultListSpec.Builder();
            consumer.accept(builder);
            this.list = builder.build();
            return this;
        }

        public Builder permissions(String... codes) {
            this.permissions.addAll(List.of(codes));
            return this;
        }

        /** Anonymous visitors may run the query ({@code access: public}); it declares no permissions then. */
        public Builder publicAccess() {
            this.publicAccess = true;
            return this;
        }

        /** {@code max-age} of the public responses of this query. */
        public Builder cacheSeconds(int seconds) {
            this.cacheSeconds = seconds;
            return this;
        }

        /** Temporal entities are read at the time given by these parameters (either may be null). */
        public Builder timeSlice(String asOfParameter, String knownAtParameter) {
            this.timeSlice = new TemplateTimeSlice(asOfParameter, knownAtParameter);
            return this;
        }

        /** The query is a report. */
        public Builder report(ReportSpec spec) {
            this.report = spec;
            return this;
        }

        /** The version, when the query comes from a file; otherwise it is computed from the definition. */
        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder sqlTemplate(String sql) {
            this.sqlTemplate = sql;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder source(TemplateSource source) {
            this.source = source;
            return this;
        }

        public AdvancedQueryDefinition build() {
            if (queryId == null || queryId.isBlank()) {
                throw new IllegalStateException("Query id must not be blank");
            }
            if (sqlTemplate == null || sqlTemplate.isBlank()) {
                throw new IllegalStateException("Query " + queryId + " has no SQL template");
            }
            if (resultFields.isEmpty()) {
                throw new IllegalStateException("Query " + queryId + " declares no result fields");
            }
            TemplateSource where = source != null ? source : new TemplateSource("query " + queryId, 1);
            AdvancedQueryDefinition query = new AdvancedQueryDefinition(
                queryId, description, entities, parameters, resultFields, sqlTemplate, timeout, datasets, list,
                permissions, where, publicAccess, cacheSeconds, timeSlice, report, version
            );
            return version != null ? query : new AdvancedQueryDefinition(queryId, query.description(),
                query.participatingEntities(), query.parameters(), query.resultFields(), sqlTemplate, timeout,
                query.datasets(), query.list(), query.permissions(), where, publicAccess, cacheSeconds, timeSlice,
                report, TemplateVersion.of(query));
        }
    }
}
