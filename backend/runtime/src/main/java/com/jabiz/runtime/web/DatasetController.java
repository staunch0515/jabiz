package com.jabiz.runtime.web;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ListViewDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.entity.ProcessOnlyFields;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.security.SensitiveDataMasker;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dataset API (docs/design/03-dataset.md section 3): reads, queries and batch changes through one dataset, so its
 * scope, soft delete, limits and list-view whitelists always apply. Complex business writes belong in processes;
 * {@code commit} serves the generic pages generated from metadata.
 *
 * <p>Reading needs the dataset's read permission, committing its write permission (403 otherwise; default deny for
 * datasets that declare none, outside the dev profile). Sensitive fields are neither returned nor accepted.
 */
@RestController
@RequestMapping("/api/datasets/{resourceId}")
class DatasetController {

    /**
     * {@code asOf} and {@code knownAt} read temporal entities at another point in time
     * (docs/design/04-temporal-append-only.md section 5.1); the default is the current state.
     */
    record QueryRequest(List<ListRequests.Filter> filters, List<ListRequests.Sort> sorts, Integer offset,
        Integer limit, Instant asOf, Instant knownAt) {}

    record QueryResponse(List<EntityInstance> items, long total, int offset, int limit) {}

    /**
     * One change. {@code effectiveTime} applies to temporal entities only: when the change takes effect (default:
     * the time of the operation); for {@code CANCEL_SCHEDULED} the time of the scheduled version to cancel.
     */
    record Change(EntityAction action, Object id, Long version, Map<String, Object> attributes,
        Instant effectiveTime) {}

    /** {@code reason} is recorded with the operation; corrections of the past require it. */
    record CommitRequest(List<Change> changes, String reason) {}

    /** One match of a lookup: the primary key and the display text (all languages of a multilingual text). */
    record LookupItem(Object id, Object label) {}

    record LabelsRequest(List<Object> ids) {}

    private static final int DEFAULT_LIMIT = 50;
    /** Most keys one labels request may ask for (docs/design/16-content-authoring.md section 2). */
    static final int MAX_LABELS = 200;
    private static final int MAX_LOOKUP_TEXT = 200;

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;
    private final EntityIdGenerator ids;
    private final SensitiveDataMasker masker;
    private final MessageCatalog messages;
    private final boolean development;

    DatasetController(DatasetRegistry datasets, EntityDefinitionRegistry entities, DatasetEntityManager entityManager,
        EntityIdGenerator ids, SensitiveDataMasker masker, MessageCatalog messages, Environment environment) {
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
        this.ids = ids;
        this.masker = masker;
        this.messages = messages;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @GetMapping("/entities/{id}")
    Mono<EntityInstance> read(@PathVariable String resourceId, @PathVariable String id,
        @RequestParam(required = false) Instant asOf, @RequestParam(required = false) Instant knownAt) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = readable(resourceId, context);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            return entityManager.findById(dataset, def, id, asOf, knownAt)
                .map(masker::hide)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                    def.name + " [ID: " + id + "] not found in dataset " + resourceId)));
        });
    }

    @PostMapping("/query")
    Mono<QueryResponse> query(@PathVariable String resourceId, @RequestBody(required = false) QueryRequest request) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = readable(resourceId, context);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            QueryRequest body = request == null ? new QueryRequest(null, null, null, null, null, null) : request;
            ListViewDefinition view = def.listView(dataset.listView()).orElse(null);

            int offset = body.offset() == null ? 0 : body.offset();
            int limit = body.limit() == null ? DEFAULT_LIMIT : body.limit();
            if (offset < 0) {
                throw invalid("offset", "offset must not be negative");
            }
            if (limit <= 0) {
                throw invalid("limit", "limit must be positive");
            }
            EntityQuery.Builder query = EntityQuery.builder().offset(offset).limit(limit);
            query.where(predicate(def, view, body.filters()));
            applySorts(view, body.sorts(), query);
            EntityQuery compiled = query.build();
            int effectiveLimit = Math.min(limit, dataset.policy().maxQueryBatchSize());

            return entityManager.query(dataset, def, compiled, body.asOf(), body.knownAt()).map(masker::hide)
                .collectList()
                .zipWith(entityManager.count(dataset, def, compiled, body.asOf(), body.knownAt()))
                .map(result -> new QueryResponse(result.getT1(), result.getT2(), offset, effectiveLimit));
        });
    }

    @PostMapping("/commit")
    Mono<List<EntityInstance>> commit(@PathVariable String resourceId, @RequestBody CommitRequest request) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = dataset(resourceId);
            Permissions.requireDeclared(context, dataset.permissions().write(), development,
                "Writing through dataset " + resourceId);
            DatasetEntityManager.rejectDirectWrites(dataset);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            boolean generatedKey = def.field(def.primaryKey).generated();
            if (request == null || request.changes() == null) {
                throw invalid("changes", "changes are required");
            }
            List<EntityChange> changes = new ArrayList<>(request.changes().size());
            for (Change change : request.changes()) {
                if (change == null || change.action() == null) {
                    throw invalid("action", "every change needs an action");
                }
                long version = change.version() == null ? 0L : change.version();
                Map<String, Object> attributes = change.attributes() == null ? Map.of() : change.attributes();
                SensitiveDataMasker.rejectWrites(def, attributes);
                ProcessOnlyFields.rejectWrites(def, attributes);
                Object id = change.id();
                if (change.action() == EntityAction.INSERT && generatedKey) {
                    // As in the generic add process: a generated key is issued here, never taken from the caller.
                    id = ids.next(def);
                    attributes = new java.util.LinkedHashMap<>(attributes);
                    attributes.put(def.primaryKey, id);
                }
                // Only the dataset's own entity: other entity types would escape their own datasets' scope.
                changes.add(new EntityChange(change.action(), new EntityInstance(
                    id, dataset.targetEntityType(), version, null, attributes), change.effectiveTime()));
            }
            return entityManager.commitBatch(dataset, changes, request.reason())
                .map(saved -> saved.stream().map(masker::hide).toList());
        });
    }

    /**
     * Instances whose display field contains {@code q}, ignoring case, for reference pickers
     * (docs/design/16-content-authoring.md section 2); at most 20, within the scope, with the read permission.
     */
    @GetMapping("/lookup")
    Mono<List<LookupItem>> lookup(@PathVariable String resourceId, @RequestParam(required = false) String q,
        @RequestParam(required = false) Integer limit) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = readable(resourceId, context);
            EntityDefinition def = displayed(dataset);
            int size = limit == null ? QueryCompiler.MAX_LOOKUP : limit;
            if (size <= 0) {
                throw invalid("limit", "limit must be positive");
            }
            if (q != null && q.length() > MAX_LOOKUP_TEXT) {
                throw invalid("q", "q must not exceed " + MAX_LOOKUP_TEXT + " characters");
            }
            return entityManager.lookup(dataset, def, q, size, context.locale().getLanguage(),
                    messages.defaultLocale().getLanguage())
                .map(instance -> new LookupItem(instance.id(), instance.attributes().get(def.displayField)))
                .collectList();
        });
    }

    /**
     * Display texts of the given instances, for reference columns (docs/design/16-content-authoring.md section 2):
     * at most 200 keys; keys outside the scope or unknown are left out.
     */
    @PostMapping("/labels")
    Mono<Map<String, Object>> labels(@PathVariable String resourceId, @RequestBody LabelsRequest request) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = readable(resourceId, context);
            EntityDefinition def = displayed(dataset);
            List<Object> keys = request == null || request.ids() == null ? List.of() : request.ids();
            if (keys.size() > MAX_LABELS) {
                throw invalid("ids", "at most " + MAX_LABELS + " ids are allowed");
            }
            if (keys.isEmpty()) {
                return Mono.just(Map.<String, Object>of());
            }
            return entityManager.byIds(dataset, def, keys)
                .collect(java.util.LinkedHashMap<String, Object>::new, (labels, instance) -> labels.put(
                    String.valueOf(instance.id()), instance.attributes().get(def.displayField)))
                .map(labels -> (Map<String, Object>) labels);
        });
    }

    /** The dataset's entity, which must declare a display field (400 {@code DISPLAY_NOT_DECLARED}). */
    private EntityDefinition displayed(DatasetDefinition dataset) {
        EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
        if (def.displayField == null) {
            throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.DISPLAY_NOT_DECLARED,
                def.name + " declares no display field", Map.of("entity", def.name))));
        }
        return def;
    }

    /**
     * All versions of a temporal entity, with the operations that wrote them (docs/design/04-temporal-append-only.md
     * section 5.2). Available when the dataset allows time travel and the latest state of the entity lies within
     * its scope.
     */
    @GetMapping("/entities/{id}/history")
    Mono<List<Map<String, Object>>> history(@PathVariable String resourceId, @PathVariable String id) {
        return RequestContexts.current().flatMap(context -> {
            DatasetDefinition dataset = readable(resourceId, context);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            return entityManager.history(dataset, def, id)
                .map(versions -> versions.stream().map(version -> hideInVersion(def, version)).toList())
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                    def.name + " [ID: " + id + "] not found in dataset " + resourceId)));
        });
    }

    /** The dataset, when the caller may read through it (default deny, docs/design/10-security.md section 5). */
    private DatasetDefinition readable(String resourceId, RequestContext context) {
        DatasetDefinition dataset = dataset(resourceId);
        Permissions.requireDeclared(context, dataset.permissions().read(), development,
            "Reading through dataset " + resourceId);
        return dataset;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> hideInVersion(EntityDefinition def, Map<String, Object> version) {
        Object attributes = version.get("attributes");
        if (!(attributes instanceof Map<?, ?>) || def.sensitiveFields().isEmpty()) {
            return version;
        }
        Map<String, Object> visible = new java.util.LinkedHashMap<>(version);
        visible.put("attributes", masker.hide(def, (Map<String, Object>) attributes));
        return visible;
    }

    private DatasetDefinition dataset(String resourceId) {
        return datasets.findById(resourceId).orElseThrow(
            () -> new EntityNotFoundException("Unknown dataset: " + resourceId));
    }

    /** Conjunction of the filters; each field must be whitelisted by the list view. */
    private static QueryPredicate predicate(EntityDefinition def, ListViewDefinition view,
        List<ListRequests.Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<QueryPredicate> parts = new ArrayList<>(filters.size());
        for (ListRequests.Filter filter : filters) {
            if (filter == null || filter.field() == null || filter.op() == null) {
                throw invalid("filters", "every filter needs a field and an op");
            }
            if (view == null || !view.allowsFilter(filter.field())) {
                throw new ValidationException(List.of(new Violation(filter.field(),
                    PlatformErrorCodes.FILTER_NOT_ALLOWED,
                    "Filtering " + def.name + " by [" + filter.field() + "] is not allowed")));
            }
            parts.add(ListRequests.toPredicate(filter));
        }
        return parts.size() == 1 ? parts.getFirst() : new QueryPredicate.And(parts);
    }

    /** Requested sorts, each whitelisted by the list view; otherwise the view's default sort. */
    private static void applySorts(ListViewDefinition view, List<ListRequests.Sort> sorts,
        EntityQuery.Builder query) {
        if (sorts == null || sorts.isEmpty()) {
            if (view != null && view.defaultSort() != null) {
                query.orderBy(view.defaultSort().field(), view.defaultSort().ascending());
            }
            return;
        }
        for (ListRequests.Sort sort : sorts) {
            if (sort == null || sort.field() == null) {
                throw invalid("sorts", "every sort needs a field");
            }
            if (view == null || !view.allowsSort(sort.field())) {
                throw new ValidationException(List.of(new Violation(sort.field(), PlatformErrorCodes.SORT_NOT_ALLOWED,
                    "Sorting by [" + sort.field() + "] is not allowed")));
            }
            query.orderBy(sort.field(), sort.asc() == null || sort.asc());
        }
    }

    private static ValidationException invalid(String field, String message) {
        return ListRequests.invalid(field, message);
    }
}
