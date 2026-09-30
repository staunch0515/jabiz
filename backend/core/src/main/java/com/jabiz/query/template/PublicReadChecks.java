package com.jabiz.query.template;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.dataset.PublicRead;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Checks of public datasets and public templates (docs/design/15-public-access.md sections 2 and 3; decision D17).
 * They report every problem instead of stopping at the first, so the startup check lists them all at once.
 */
public final class PublicReadChecks {

    /** Upper bounds that public reads must stay within ({@code jabiz.public.*}). */
    public record Limits(Duration maxTimeout, int maxLimit) {
        public Limits {
            if (maxTimeout == null || maxTimeout.isNegative() || maxTimeout.isZero()) {
                throw new IllegalArgumentException("maxTimeout must be positive");
            }
            if (maxLimit <= 0) {
                throw new IllegalArgumentException("maxLimit must be positive");
            }
        }
    }

    public static final int MAX_CACHE_SECONDS = 3600;

    private PublicReadChecks() {}

    /**
     * Problems of a public dataset against its target entity; empty for a dataset that is not public.
     *
     * @param entity the dataset's target entity
     */
    public static List<String> checkDataset(DatasetDefinition dataset, EntityDefinition entity, Limits limits) {
        if (!dataset.isPublic()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        PublicRead read = dataset.publicRead();
        if (dataset.isDefault()) {
            problems.add("a default dataset serves the back office and cannot be public; declare a separate one");
        }
        for (DatasetScope.Entry entry : dataset.scope().entries()) {
            if (!(entry instanceof DatasetScope.Fixed)) {
                problems.add("scope field " + entry.field() + " is taken from the request context, which anonymous "
                    + "visitors do not have; public datasets allow fixed scope values only");
            }
        }
        if (dataset.scope().isEmpty() && !read.allRows()) {
            problems.add("has no scope; declare one with fixed values, or publicRead(p -> p.allRows()...) to make "
                + "every row public on purpose");
        }
        if (!dataset.scope().isEmpty() && read.allRows()) {
            problems.add("declares allRows() but also a scope");
        }
        for (String name : read.fields()) {
            Optional<FieldDefinition> field = entity.findField(name);
            if (field.isEmpty()) {
                problems.add("public field " + name + " does not exist on " + entity.name);
            } else if (field.get().sensitive()) {
                problems.add("public field " + name + " is sensitive and can never be public");
            } else if (field.get().isMasked()) {
                problems.add("public field " + name + " is masked and can never be public");
            }
        }
        if (dataset.policy().maxQueryBatchSize() > limits.maxLimit()) {
            problems.add("maxQueryBatchSize " + dataset.policy().maxQueryBatchSize() + " exceeds jabiz.public.max-limit "
                + limits.maxLimit());
        }
        return problems;
    }

    /**
     * Problems of a template in connection with public access: a public template must read public datasets only and
     * only their whitelisted fields, must declare no permissions and must stay within the limits; a template that
     * is not public may not set {@code cacheSeconds}.
     *
     * @param datasets resolves a dataset id
     * @param entities resolves an entity name
     * @return problems located in the template ({@link TemplateProblem#offset()}) or its header
     */
    public static List<TemplateProblem> checkTemplate(AdvancedQueryDefinition query,
        Function<String, Optional<DatasetDefinition>> datasets, Function<String, Optional<EntityDefinition>> entities,
        Limits limits) {
        List<TemplateProblem> problems = new ArrayList<>();
        if (!query.publicAccess()) {
            if (query.cacheSeconds() != null) {
                problems.add(TemplateProblem.header("cacheSeconds applies to public templates (access: public) only"));
            }
            return problems;
        }
        if (!query.permissions().isEmpty()) {
            problems.add(TemplateProblem.header("a public template (access: public) declares no permissions"));
        }
        if (query.cacheSeconds() != null
            && (query.cacheSeconds() < 0 || query.cacheSeconds() > MAX_CACHE_SECONDS)) {
            problems.add(TemplateProblem.header("cacheSeconds must be between 0 and " + MAX_CACHE_SECONDS));
        }
        if (query.timeoutOverride() != null && query.timeoutOverride().compareTo(limits.maxTimeout()) > 0) {
            problems.add(TemplateProblem.header("timeoutMs (" + query.timeoutOverride().toMillis()
                + ") exceeds jabiz.public.max-timeout (" + limits.maxTimeout().toMillis() + " ms)"));
        }
        for (String entity : query.participatingEntities()) {
            String datasetId = query.datasets().get(entity);
            if (datasetId == null) {
                problems.add(TemplateProblem.header("entity " + entity + " must be read through a public dataset named"
                    + " in datasets; default datasets are never public"));
                continue;
            }
            datasets.apply(datasetId).filter(DatasetDefinition::isPublic).ifPresentOrElse(d -> { },
                () -> problems.add(TemplateProblem.header("dataset " + datasetId + " of entity " + entity
                    + " is not public")));
        }
        String masked = SqlText.mask(query.sqlTemplate());
        for (SqlText.Placeholder placeholder : SqlText.placeholders(masked)) {
            if (placeholder.field() != null && !whitelisted(query, placeholder.entity(), placeholder.field(), datasets,
                entities)) {
                problems.add(TemplateProblem.error(placeholder.start(), "field " + placeholder.entity() + "."
                    + placeholder.field() + " is not in the whitelist of its public dataset"));
            }
        }
        for (ProjectedField result : query.resultFields()) {
            if (result.sourceEntity() != null
                && !whitelisted(query, result.sourceEntity(), result.sourceField(), datasets, entities)) {
                problems.add(TemplateProblem.header("result " + result.name() + " comes from "
                    + result.sourceEntity() + "." + result.sourceField()
                    + ", which is not in the whitelist of its public dataset"));
            }
        }
        return problems;
    }

    /**
     * Whether the field is readable through the entity's public dataset. Fields of entities without a (public)
     * dataset count as whitelisted: that problem is reported once, for the entity.
     */
    private static boolean whitelisted(AdvancedQueryDefinition query, String entity, String field,
        Function<String, Optional<DatasetDefinition>> datasets, Function<String, Optional<EntityDefinition>> entities) {
        String datasetId = query.datasets().get(entity);
        if (datasetId == null || entities.apply(entity).isEmpty()) {
            return true;
        }
        return datasets.apply(datasetId).filter(DatasetDefinition::isPublic)
            .map(d -> d.publicRead().allows(field)).orElse(true);
    }
}
