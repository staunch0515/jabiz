package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.JsonSchemaExporter;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.ProcessInputSchemas;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exposes the metamodel so that clients can build pages, forms and client-side validation from it
 * (docs/design/02-metamodel.md section 8, docs/design/12-frontend.md section 2).
 *
 * <p>The catalogs list only what the caller may use (default deny: an undeclared permission counts only in the dev
 * profile). They drive navigation, never access: every entry point still checks its own permissions.
 */
@RestController
@RequestMapping("/api/meta")
class MetaModelController {

    /**
     * One dataset the caller may read. {@code canWrite}: the caller holds the write permission and the policy lets
     * the dataset API write (not read-only, not written by processes only, the entity is writable).
     */
    record DatasetEntry(String id, String entity, String label, boolean isDefault, boolean temporal,
        boolean allowScheduled, boolean readOnly, boolean processOnlyWrites, boolean allowTimeTravel,
        boolean softDelete, String listView, int maxQueryBatchSize, boolean canWrite) {}

    /** One process the caller may run, with the JSON Schema of its input. */
    record ProcessEntry(String name, int version, boolean latest, boolean deprecated, String label,
        String description, Map<String, Object> input) {}

    private final EntityDefinitionRegistry registry;
    private final DatasetRegistry datasets;
    private final ProcessRegistry processes;
    private final MessageCatalog messages;
    private final boolean development;

    MetaModelController(EntityDefinitionRegistry registry, DatasetRegistry datasets, ProcessRegistry processes,
        MessageCatalog messages, Environment environment) {
        this.registry = registry;
        this.datasets = datasets;
        this.processes = processes;
        this.messages = messages;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** The entity with labels and message templates in the language of the request. */
    @GetMapping("/entities/{name}")
    Mono<Map<String, Object>> getEntity(@PathVariable("name") String name) {
        EntityDefinition def = find(name);
        return RequestContexts.current().map(context -> MetaModelExporter.export(def, messages, context.locale()));
    }

    /** JSON Schema (draft 2020-12) of the attributes of one instance. */
    @GetMapping(value = "/schema/{name}", produces = {"application/schema+json", "application/json"})
    Map<String, Object> getSchema(@PathVariable("name") String name) {
        return JsonSchemaExporter.export(find(name));
    }

    /** Datasets the caller may read, ordered by label. */
    @GetMapping("/datasets")
    Mono<List<DatasetEntry>> datasets() {
        return RequestContexts.current().map(context -> datasets.all().stream()
            .filter(dataset -> Permissions.allowsDeclared(context, dataset.permissions().read(), development))
            .flatMap(dataset -> registry.find(dataset.targetEntityType()).map(def -> entry(dataset, def, context))
                .stream())
            .sorted(Comparator.comparing(DatasetEntry::label).thenComparing(DatasetEntry::id))
            .toList());
    }

    /** Processes the caller may run through the process API, except internal ones, ordered by label and version. */
    @GetMapping("/processes")
    Mono<List<ProcessEntry>> processes() {
        return RequestContexts.current().map(context -> processes.all().stream()
            .filter(definition -> !definition.internal())
            .filter(definition -> Permissions.allowsAll(context, definition.permissions(), development))
            .map(definition -> entry(definition, context))
            .sorted(Comparator.comparing(ProcessEntry::label).thenComparing(ProcessEntry::version))
            .toList());
    }

    private DatasetEntry entry(DatasetDefinition dataset, EntityDefinition def, RequestContext context) {
        var policy = dataset.policy();
        boolean writable = !policy.readOnly() && !policy.processOnlyWrites() && def.versionColumn().isPresent()
                           && Permissions.allowsDeclared(context, dataset.permissions().write(), development);
        String label = label("dataset." + dataset.resourceId(), context)
            .or(() -> label(MetaModelExporter.labelKey(def.name, null), context))
            .orElse(def.name);
        return new DatasetEntry(dataset.resourceId(), def.name, label, dataset.isDefault(), def.temporal,
            def.temporal && def.temporalSpec.allowScheduled(), policy.readOnly(), policy.processOnlyWrites(),
            policy.allowTimeTravel(), policy.softDelete(), dataset.listView(), policy.maxQueryBatchSize(), writable);
    }

    private ProcessEntry entry(ProcessDefinition<?, ?, ?> definition, RequestContext context) {
        boolean latest = processes.findLatest(definition.name())
            .map(newest -> newest.version() == definition.version()).orElse(false);
        return new ProcessEntry(definition.name(), definition.version(), latest, definition.deprecated(),
            label("process." + definition.name(), context).orElse(definition.name()), definition.description(),
            ProcessInputSchemas.of(definition.inputType()).schema());
    }

    private Optional<String> label(String key, RequestContext context) {
        return messages.find(key, context.locale());
    }

    private EntityDefinition find(String name) {
        return registry.find(name).orElseThrow(
            () -> new EntityNotFoundException("Unregistered entity type: " + name));
    }
}
