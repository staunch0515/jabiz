package com.jabiz.runtime.web;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ListViewDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dataset API (docs/design/03-dataset.md section 3): reads, queries and batch changes through one dataset, so its
 * scope, soft delete, limits and list-view whitelists always apply. Complex business writes belong in processes;
 * {@code commit} serves the generic pages generated from metadata.
 */
@RestController
@RequestMapping("/api/datasets/{resourceId}")
class DatasetController {

    /**
     * One filter condition. {@code op} is one of eq, ne, gt, gte, lt, lte, in, like, isNull, isNotNull, between;
     * {@code value} is the operand, {@code values} the list of {@code in}, {@code from}/{@code to} the bounds of
     * {@code between}.
     */
    record Filter(String field, String op, Object value, List<Object> values, Object from, Object to) {}

    record Sort(String field, Boolean asc) {}

    record QueryRequest(List<Filter> filters, List<Sort> sorts, Integer offset, Integer limit) {}

    record QueryResponse(List<EntityInstance> items, long total, int offset, int limit) {}

    record Change(EntityAction action, Object id, Long version, Map<String, Object> attributes) {}

    record CommitRequest(List<Change> changes) {}

    private static final int DEFAULT_LIMIT = 50;

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;
    private final EntityIdGenerator ids;

    DatasetController(DatasetRegistry datasets, EntityDefinitionRegistry entities, DatasetEntityManager entityManager,
        EntityIdGenerator ids) {
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
        this.ids = ids;
    }

    @GetMapping("/entities/{id}")
    Mono<EntityInstance> read(@PathVariable String resourceId, @PathVariable String id) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = dataset(resourceId);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            return entityManager.findById(dataset, def, id)
                .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(
                    def.name + " [ID: " + id + "] not found in dataset " + resourceId)));
        });
    }

    @PostMapping("/query")
    Mono<QueryResponse> query(@PathVariable String resourceId, @RequestBody(required = false) QueryRequest request) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = dataset(resourceId);
            EntityDefinition def = entities.getOrThrow(dataset.targetEntityType());
            QueryRequest body = request == null ? new QueryRequest(null, null, null, null) : request;
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

            return entityManager.query(dataset, def, compiled).collectList()
                .zipWith(entityManager.count(dataset, def, compiled))
                .map(result -> new QueryResponse(result.getT1(), result.getT2(), offset, effectiveLimit));
        });
    }

    @PostMapping("/commit")
    Mono<List<EntityInstance>> commit(@PathVariable String resourceId, @RequestBody CommitRequest request) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = dataset(resourceId);
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
                Object id = change.id();
                if (change.action() == EntityAction.INSERT && generatedKey) {
                    // As in the generic add process: a generated key is issued here, never taken from the caller.
                    id = ids.next(def);
                    attributes = new java.util.LinkedHashMap<>(attributes);
                    attributes.put(def.primaryKey, id);
                }
                // Only the dataset's own entity: other entity types would escape their own datasets' scope.
                changes.add(new EntityChange(change.action(), new EntityInstance(
                    id, dataset.targetEntityType(), version, null, attributes)));
            }
            return entityManager.commitBatch(dataset, changes);
        });
    }

    private DatasetDefinition dataset(String resourceId) {
        return datasets.findById(resourceId).orElseThrow(
            () -> new EntityNotFoundException("Unknown dataset: " + resourceId));
    }

    /** Conjunction of the filters; each field must be whitelisted by the list view. */
    private static QueryPredicate predicate(EntityDefinition def, ListViewDefinition view, List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        List<QueryPredicate> parts = new ArrayList<>(filters.size());
        for (Filter filter : filters) {
            if (filter == null || filter.field() == null || filter.op() == null) {
                throw invalid("filters", "every filter needs a field and an op");
            }
            if (view == null || !view.allowsFilter(filter.field())) {
                throw new ValidationException(List.of(new Violation(filter.field(),
                    PlatformErrorCodes.FILTER_NOT_ALLOWED,
                    "Filtering " + def.name + " by [" + filter.field() + "] is not allowed")));
            }
            parts.add(toPredicate(filter));
        }
        return parts.size() == 1 ? parts.getFirst() : new QueryPredicate.And(parts);
    }

    private static QueryPredicate toPredicate(Filter filter) {
        String field = filter.field();
        return switch (filter.op().toLowerCase(Locale.ROOT)) {
            case "eq" -> new QueryPredicate.Eq(field, filter.value());
            case "ne" -> new QueryPredicate.Ne(field, filter.value());
            case "gt" -> new QueryPredicate.Gt(field, filter.value());
            case "gte" -> new QueryPredicate.Gte(field, filter.value());
            case "lt" -> new QueryPredicate.Lt(field, filter.value());
            case "lte" -> new QueryPredicate.Lte(field, filter.value());
            case "in" -> new QueryPredicate.In(field, filter.values() == null ? List.of() : filter.values());
            case "like" -> {
                if (!(filter.value() instanceof String pattern)) {
                    throw invalid(field, "like needs a text pattern");
                }
                yield new QueryPredicate.Like(field, pattern);
            }
            case "isnull" -> new QueryPredicate.IsNull(field);
            case "isnotnull" -> new QueryPredicate.IsNotNull(field);
            case "between" -> new QueryPredicate.Between(field, filter.from(), filter.to());
            default -> throw invalid(field, "unknown operator " + filter.op());
        };
    }

    /** Requested sorts, each whitelisted by the list view; otherwise the view's default sort. */
    private static void applySorts(ListViewDefinition view, List<Sort> sorts, EntityQuery.Builder query) {
        if (sorts == null || sorts.isEmpty()) {
            if (view != null && view.defaultSort() != null) {
                query.orderBy(view.defaultSort().field(), view.defaultSort().ascending());
            }
            return;
        }
        for (Sort sort : sorts) {
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
        return new ValidationException(List.of(new Violation(field, PlatformErrorCodes.INVALID_VALUE, message)));
    }
}
