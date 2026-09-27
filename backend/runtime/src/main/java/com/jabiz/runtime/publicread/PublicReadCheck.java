package com.jabiz.runtime.publicread;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.TemplateSource;
import com.jabiz.query.template.PublicReadChecks;
import com.jabiz.query.template.TemplateProblem;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup check of public read access (category {@code PUBLIC}, docs/design/15-public-access.md sections 2 and 3):
 * every public dataset and every template, all problems at once. It runs whether or not
 * {@code jabiz.public.enabled} is set, so switching public access on never uncovers a problem.
 */
@Component
public class PublicReadCheck implements PlatformCheck {

    public static final String CATEGORY = "PUBLIC";

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final SqlTemplateRegistry templates;
    private final PublicProperties properties;

    public PublicReadCheck(DatasetRegistry datasets, EntityDefinitionRegistry entities, SqlTemplateRegistry templates,
        PublicProperties properties) {
        this.datasets = datasets;
        this.entities = entities;
        this.templates = templates;
        this.properties = properties;
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        PublicReadChecks.Limits limits;
        try {
            limits = properties.limits();
        } catch (IllegalArgumentException e) {
            return List.of(CheckProblem.error(CATEGORY, "jabiz.public", e.getMessage()));
        }
        if (properties.defaultCacheSeconds() < 0 || properties.defaultCacheSeconds() > PublicReadChecks.MAX_CACHE_SECONDS
            || properties.fileCacheSeconds() < 0) {
            problems.add(CheckProblem.error(CATEGORY, "jabiz.public", "default-cache-seconds must be between 0 and "
                + PublicReadChecks.MAX_CACHE_SECONDS + " and file-cache-seconds must not be negative"));
        }
        for (DatasetDefinition dataset : datasets.all()) {
            entities.find(dataset.targetEntityType()).ifPresent(entity ->
                PublicReadChecks.checkDataset(dataset, entity, limits).forEach(message ->
                    problems.add(CheckProblem.error(CATEGORY, "Dataset " + dataset.resourceId(), message))));
        }
        for (AdvancedQueryDefinition query : templates.all()) {
            TemplateSource source = query.source();
            for (TemplateProblem problem : PublicReadChecks.checkTemplate(query, datasets::findById, entities::find,
                limits)) {
                String location = problem.offset() < 0 ? source.path()
                    : source.locate(query.sqlTemplate(), problem.offset());
                problems.add(CheckProblem.error(CATEGORY, location, "query " + query.queryId() + ": "
                    + problem.message()));
            }
        }
        return problems;
    }
}
