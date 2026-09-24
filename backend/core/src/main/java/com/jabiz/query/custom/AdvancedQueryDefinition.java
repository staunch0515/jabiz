package com.jabiz.query.custom;

import com.jabiz.entity.SemanticKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Definition of a custom query spanning several entities.
 *
 * The SQL template may contain placeholders resolved through the metamodel:
 * <ul>
 *   <li>{@code {{Entity}}} - the entity's table (or a scoped sub-select when dataset rules
 *       apply); always give it an alias in the template</li>
 *   <li>{@code {{Entity.field}}} - the physical column of a logical field, unqualified</li>
 * </ul>
 * Only entities listed in {@code participatingEntities} may be referenced.
 *
 * @param timeoutOverride optional per-query timeout; the effective timeout never exceeds the dataset's
 */
public record AdvancedQueryDefinition(
        String queryId,
        String description,
        List<String> participatingEntities,
        List<QueryParameter> parameters,
        List<ProjectedField> resultFields,
        String sqlTemplate,
        Duration timeoutOverride
) {
    public static AdvancedQueryDefinition define(String queryId, Consumer<Builder> consumer) {
        Builder builder = new Builder(queryId);
        consumer.accept(builder);
        return builder.build();
    }

    public static class Builder {
        private final String queryId;
        private String description = "";
        private final List<String> entities = new ArrayList<>();
        private final List<QueryParameter> parameters = new ArrayList<>();
        private final List<ProjectedField> resultFields = new ArrayList<>();
        private String sqlTemplate;
        private Duration timeout;

        public Builder(String queryId) { this.queryId = queryId; }

        public Builder description(String d) { this.description = d; return this; }

        public Builder fromEntities(String... entityNames) {
            this.entities.addAll(List.of(entityNames));
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

        public Builder returns(String name, SemanticKind kind) {
            this.resultFields.add(ProjectedField.of(name, kind));
            return this;
        }

        public Builder returns(String name, SemanticKind kind, String sourceEntity, String sourceField) {
            this.resultFields.add(ProjectedField.from(name, kind, sourceEntity, sourceField));
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

        public AdvancedQueryDefinition build() {
            if (sqlTemplate == null || sqlTemplate.isBlank()) {
                throw new IllegalStateException("Query " + queryId + " has no SQL template");
            }
            if (resultFields.isEmpty()) {
                throw new IllegalStateException("Query " + queryId + " declares no result fields");
            }
            return new AdvancedQueryDefinition(
                queryId, description, List.copyOf(entities), List.copyOf(parameters),
                List.copyOf(resultFields), sqlTemplate, timeout
            );
        }
    }
}
