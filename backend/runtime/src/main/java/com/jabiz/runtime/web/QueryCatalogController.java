package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.query.custom.ProjectedField;
import com.jabiz.query.custom.ReportSpec;
import com.jabiz.query.custom.ResultListSpec;
import com.jabiz.query.template.TemplateSchemas;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog of SQL templates the caller may run (docs/design/19-reports.md section 3.2): parameters as JSON
 * Schema, result columns, outer filters and sorts, version, point in time and report settings. Like the other
 * catalogs it drives pages only; {@code POST /api/queries/{id}} checks the permissions again. Public templates have
 * their own contract (docs/design/15-public-access.md section 7) and are left out.
 */
@RestController
@RequestMapping("/api/meta")
class QueryCatalogController {

    /**
     * @param timeSlice  the parameters giving the point in time, or null
     * @param timeTravel whether a run accepts {@code asOf} / {@code knownAt}: the template reads temporal entities
     *                   whose datasets allow time travel, and declares no {@code timeSlice}
     * @param report     the report settings, or null when the template is not a report
     */
    record QueryEntry(String id, String title, String description, String version, Map<String, Object> params,
        List<ResultEntry> results, List<String> filters, List<String> sorts, SortEntry defaultSort,
        TimeSliceEntry timeSlice, boolean timeTravel, ReportEntry report) {}

    /** @param kind the semantic kind as the metamodel export writes it */
    record ResultEntry(String name, String label, Map<String, Object> kind) {}

    record SortEntry(String field, boolean asc) {}

    record TimeSliceEntry(String asOf, String knownAt) {}

    record ReportEntry(PeriodEntry period, boolean landscape) {}

    record PeriodEntry(String from, String to) {}

    private final SqlTemplateRegistry templates;
    private final EntityDefinitionRegistry entities;
    private final MessageCatalog messages;
    private final boolean development;

    QueryCatalogController(SqlTemplateRegistry templates, EntityDefinitionRegistry entities, MessageCatalog messages,
        Environment environment) {
        this.templates = templates;
        this.entities = entities;
        this.messages = messages;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** Templates the caller may run, ordered by id. */
    @GetMapping("/queries")
    Mono<List<QueryEntry>> queries() {
        return RequestContexts.current().map(context -> templates.all().stream()
            .filter(query -> !query.publicAccess())
            .filter(query -> Permissions.allowsAll(context, query.permissions(), development))
            .sorted(Comparator.comparing(AdvancedQueryDefinition::queryId))
            .map(query -> entry(query, context))
            .toList());
    }

    private QueryEntry entry(AdvancedQueryDefinition query, RequestContext context) {
        String title = label("query." + query.queryId(), context).orElse(query.queryId());
        List<ResultEntry> results = query.resultFields().stream()
            .map(field -> new ResultEntry(field.name(), columnLabel(query, field, context),
                MetaModelExporter.kindToJson(field.kind())))
            .toList();
        ResultListSpec list = query.list();
        SortEntry defaultSort = list.defaultSort() == null ? null
            : new SortEntry(list.defaultSort().field(), list.defaultSort().ascending());
        TimeSliceEntry timeSlice = query.timeSlice() == null ? null
            : new TimeSliceEntry(query.timeSlice().asOf(), query.timeSlice().knownAt());
        return new QueryEntry(query.queryId(), title, query.description(), query.version(),
            TemplateSchemas.params(query), results, list.filters(), list.sorts(), defaultSort, timeSlice,
            timeTravel(query), report(query.report()));
    }

    /** A point in time is accepted when it would change something and every temporal entity's dataset allows it. */
    private boolean timeTravel(AdvancedQueryDefinition query) {
        if (query.timeSlice() != null) {
            return false;
        }
        Map<String, DatasetDefinition> datasets = templates.datasetsOf(query);
        boolean anyTemporal = false;
        for (Map.Entry<String, DatasetDefinition> entry : datasets.entrySet()) {
            if (entities.getOrThrow(entry.getKey()).temporal) {
                anyTemporal = true;
                if (!entry.getValue().policy().allowTimeTravel()) {
                    return false;
                }
            }
        }
        return anyTemporal;
    }

    private static ReportEntry report(ReportSpec spec) {
        if (spec == null) {
            return null;
        }
        PeriodEntry period = spec.periodFrom() == null && spec.periodTo() == null ? null
            : new PeriodEntry(spec.periodFrom(), spec.periodTo());
        return new ReportEntry(period, spec.landscape());
    }

    /** The column's own text, else the display name of the field it comes from, else its name. */
    private String columnLabel(AdvancedQueryDefinition query, ProjectedField field, RequestContext context) {
        return label("query." + query.queryId() + "." + field.name(), context)
            .or(() -> field.sourceEntity() == null ? Optional.empty()
                : label(MetaModelExporter.labelKey(field.sourceEntity(), field.sourceField()), context))
            .orElse(field.name());
    }

    private Optional<String> label(String key, RequestContext context) {
        return messages.find(key, context.locale());
    }
}
