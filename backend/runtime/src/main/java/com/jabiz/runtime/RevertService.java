package com.jabiz.runtime;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.QueryCompiler;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.operation.Operation;
import com.jabiz.runtime.operation.OperationItem;
import com.jabiz.runtime.operation.OperationRecord;
import com.jabiz.runtime.operation.OperationRecorder;
import com.jabiz.runtime.operation.OperationRequest;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.runtime.storage.UniqueKeyViolationException;
import com.jabiz.runtime.temporal.TemporalPermissions;
import com.jabiz.runtime.temporal.TemporalStore;
import com.jabiz.runtime.temporal.VersionAppender;
import com.jabiz.temporal.EntityVersion;
import com.jabiz.temporal.Timeline;
import com.jabiz.temporal.VersionAction;
import com.jabiz.temporal.VersionPlanner;
import org.springframework.beans.factory.annotation.Value;
import com.jabiz.runtime.security.Permissions;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reverts operations (decision D2, docs/design/04-temporal-append-only.md section 6).
 *
 * <p>A revert is a new operation whose {@code reverts_seq_id} points at the reverted one; sub-operations are
 * reverted as well, each by a sub-operation of the revert, newest first and all in one transaction. Every version
 * the operation wrote is undone by a new version at the same effective time that restores the fields it changed to
 * their values in its base version (an insertion becomes a tombstone, a deletion is brought back); versions it
 * rebased are recomputed by the rebase of these writes.
 *
 * <p>A revert is refused as a whole (409) when a later operation changed a field the reverted one changed on the
 * same entity (any later change blocks the revert of an insertion). Rebased copies written by later operations do
 * not count: they only carry versions over. Reverting a revert redoes the original change.
 */
@Component
public class RevertService {

    private final OperationRecorder operations;
    private final TemporalStore store;
    private final VersionAppender versions;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final QueryCompiler queryCompiler;
    private final DatasetEntityManager entityManager;
    private final String poolRef;
    private final boolean development;

    public RevertService(OperationRecorder operations, TemporalStore store, VersionAppender versions,
        EntityDefinitionRegistry entities, DatasetRegistry datasets, StorageAdapterRegistry storages,
        QueryCompiler queryCompiler, DatasetEntityManager entityManager, Environment environment,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
        this.operations = Objects.requireNonNull(operations);
        this.store = Objects.requireNonNull(store);
        this.versions = Objects.requireNonNull(versions);
        this.entities = Objects.requireNonNull(entities);
        this.datasets = Objects.requireNonNull(datasets);
        this.storages = Objects.requireNonNull(storages);
        this.queryCompiler = Objects.requireNonNull(queryCompiler);
        this.entityManager = Objects.requireNonNull(entityManager);
        this.poolRef = Objects.requireNonNull(poolRef);
    }

    /** The storage engine holding the operation tables. */
    public StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    /**
     * Reverts the operation and its sub-operations.
     *
     * @return the revert operation
     */
    public Mono<OperationRecord> revert(long processSeqId, String reason) {
        return RequestContexts.current().flatMap(request -> {
            if (!request.hasPermission(TemporalPermissions.REVERT)) {
                return Mono.error(new PermissionDeniedException(TemporalPermissions.REVERT,
                    "Reverting operations needs permission " + TemporalPermissions.REVERT));
            }
            if (reason == null || reason.isBlank()) {
                return Mono.error(new ValidationException(List.of(new Violation("reason",
                    PlatformErrorCodes.REASON_REQUIRED, "A revert needs a reason"))));
            }
            StorageEngine engine = engine();
            Mono<OperationRecord> work = operations.tree(engine, processSeqId).collectList().flatMap(tree -> {
                if (tree.isEmpty()) {
                    return Mono.error(new EntityNotFoundException("Operation " + processSeqId + " not found"));
                }
                return Flux.fromIterable(tree)
                    .concatMap(op -> operations.items(engine, op.processSeqId())
                        .filter(item -> item.action() != VersionAction.REBASE)
                        .collectList()
                        .map(items -> Map.entry(op, items)))
                    .collectList()
                    .flatMap(entries -> revertTree(engine, processSeqId, reason, request, tree, entries));
            });
            return engine.inTransaction(work);
        });
    }

    private Mono<OperationRecord> revertTree(StorageEngine engine, long rootSeq, String reason,
        com.jabiz.context.RequestContext request, List<OperationRecord> tree,
        List<Map.Entry<OperationRecord, List<OperationItem>>> entries) {
        if (entries.stream().allMatch(entry -> entry.getValue().isEmpty())) {
            return Mono.error(new BusinessRuleViolationException(new Violation(null,
                PlatformErrorCodes.NOTHING_TO_REVERT, "Operation " + rootSeq + " wrote no versions",
                Map.of("operation", rootSeq))));
        }
        requireWriteAccess(request, entries);
        Set<Long> inTree = new HashSet<>();
        tree.forEach(op -> inTree.add(op.processSeqId()));

        return findBlocking(engine, entries, inTree).flatMap(blocking -> {
            if (!blocking.isEmpty()) {
                return Mono.<OperationRecord>error(new RevertConflictException(
                    "Operation " + rootSeq + " cannot be reverted: later operations changed the same data; "
                        + "revert them first, newest first", blocking));
            }
            // Revert operations mirror the tree: created parents first, all at the time of the root.
            Map<Long, Operation> started = new HashMap<>();
            return Flux.fromIterable(tree)
                .concatMap(op -> {
                    Operation parent = op.processSeqId() == rootSeq ? null : started.get(op.parentSeqId());
                    OperationRequest spec = new OperationRequest(OperationRequest.REVERT, 1, null,
                        parent == null ? null : parent.processSeqId(), op.processSeqId(), reason, null,
                        parent == null ? null : parent.opTime());
                    return operations.begin(engine, spec, request)
                        .doOnNext(operation -> started.put(op.processSeqId(), operation));
                })
                .then(Flux.fromIterable(entries.reversed())
                    .concatMap(entry -> restore(engine, entry.getValue(), started.get(entry.getKey().processSeqId())))
                    .then())
                .then(Mono.defer(() -> operations.find(engine, started.get(rootSeq).processSeqId())));
        });
    }

    /** Later operations outside the reverted tree that changed fields the tree changed. */
    private Mono<List<RevertConflictException.Blocking>> findBlocking(StorageEngine engine,
        List<Map.Entry<OperationRecord, List<OperationItem>>> entries, Set<Long> inTree) {
        List<Mono<List<RevertConflictException.Blocking>>> checks = new ArrayList<>();
        for (Map.Entry<OperationRecord, List<OperationItem>> entry : entries) {
            Map<String, List<OperationItem>> byEntity = new LinkedHashMap<>();
            for (OperationItem item : entry.getValue()) {
                byEntity.computeIfAbsent(item.entityType() + "/" + item.entityId(), key -> new ArrayList<>()).add(item);
            }
            for (List<OperationItem> items : byEntity.values()) {
                OperationItem first = items.getFirst();
                Set<String> fields = new LinkedHashSet<>();
                boolean inserted = false;
                for (OperationItem item : items) {
                    fields.addAll(item.changedFields());
                    inserted |= item.action() == VersionAction.INSERT;
                }
                boolean anyChangeBlocks = inserted;
                long seq = entry.getKey().processSeqId();
                checks.add(operations.laterItems(engine, first.entityType(), first.entityId(), seq)
                    .collectList()
                    .map(later -> effectiveLaterChanges(later, seq, inTree).stream()
                        .filter(item -> anyChangeBlocks || item.changedFields().stream().anyMatch(fields::contains))
                        .map(item -> new RevertConflictException.Blocking(item.processSeqId(), item.entityType(),
                            item.entityId(), anyChangeBlocks ? item.changedFields()
                                : item.changedFields().stream().filter(fields::contains).sorted().toList()))
                        .toList()));
            }
        }
        return Flux.concat(checks)
            .flatMapIterable(list -> list)
            .distinct(b -> b.processSeqId() + "/" + b.entityType() + "/" + b.entityId())
            .sort(Comparator.comparingLong(RevertConflictException.Blocking::processSeqId).reversed())
            .collectList();
    }

    /**
     * The later changes that still count: rebased copies only carry versions over, and a later operation that
     * was itself reverted afterwards cancels out with its revert (the caller reverted it first, as decision D2
     * suggests). A revert of a revert (redo) cancels the revert, so the redone operation counts again.
     *
     * @param later versions written after {@code since}, newest operation first
     */
    static List<OperationItem> effectiveLaterChanges(List<OperationRecorder.LaterItem> later, long since,
        Set<Long> inTree) {
        Set<Long> cancelled = new HashSet<>();
        List<OperationItem> effective = new ArrayList<>();
        for (OperationRecorder.LaterItem entry : later) {
            OperationItem item = entry.item();
            long seq = item.processSeqId();
            if (inTree.contains(seq) || cancelled.contains(seq)) {
                continue;
            }
            Long reverted = entry.revertsSeqId();
            if (reverted != null && reverted > since) {
                cancelled.add(reverted);
                continue;
            }
            if (item.action() != VersionAction.REBASE) {
                effective.add(item);
            }
        }
        return effective;
    }

    /** Undoes the versions one operation wrote, newest first. */
    /**
     * A revert writes versions of every entity the operation touched: the caller needs the write permission of each
     * entity's default dataset, as for any other write (docs/design/10-security.md section 5), besides
     * {@code temporal.revert}. An older value of a sensitive field (a password hash) is never written back: only its
     * own process sets it.
     */
    private void requireWriteAccess(com.jabiz.context.RequestContext request,
        List<Map.Entry<OperationRecord, List<OperationItem>>> entries) {
        Set<String> checked = new HashSet<>();
        List<Violation> sensitive = new java.util.ArrayList<>();
        for (Map.Entry<OperationRecord, List<OperationItem>> entry : entries) {
            for (OperationItem item : entry.getValue()) {
                EntityDefinition def = entities.getOrThrow(item.entityType());
                if (checked.add(def.name)) {
                    DatasetDefinition dataset = datasets.findForEntity(def.name).orElseThrow(
                        () -> new IllegalStateException("No default dataset for " + def.name));
                    Permissions.requireDeclared(request, dataset.permissions().write(), development,
                        "Reverting changes of " + def.name + " through dataset " + dataset.resourceId());
                    // Written by processes only (decision D14): such data is corrected by the process that owns
                    // it (a reversing ledger transaction), never by restoring older versions.
                    if (dataset.policy().processOnlyWrites()) {
                        throw new BusinessRuleViolationException(new Violation(null,
                            PlatformErrorCodes.REVERT_NOT_ALLOWED, "Operation " + entry.getKey().processSeqId()
                                + " wrote " + def.name + ", which only its processes change",
                            Map.of("operation", entry.getKey().processSeqId(), "entity", def.name)));
                    }
                }
                if (item.action() == VersionAction.UPDATE) {
                    def.sensitiveFields().stream().filter(item.changedFields()::contains)
                        .forEach(field -> sensitive.add(new Violation(field, PlatformErrorCodes.SENSITIVE_FIELD,
                            "A revert cannot restore an earlier value of " + def.name + "." + field)));
                    // Process-only fields are corrected by processes, or a revert would bypass the lifecycle
                    // (docs/design/16-content-authoring.md section 5).
                    def.processOnlyFields().stream().filter(item.changedFields()::contains)
                        .forEach(field -> sensitive.add(new Violation(field, PlatformErrorCodes.PROCESS_ONLY_FIELD,
                            "A revert cannot restore an earlier value of " + def.name + "." + field)));
                }
            }
        }
        if (!sensitive.isEmpty()) {
            throw new BusinessRuleViolationException(sensitive);
        }
    }

    private Mono<Void> restore(StorageEngine engine, List<OperationItem> items, Operation operation) {
        List<OperationItem> newestFirst = items.stream()
            .sorted(Comparator.comparingLong(OperationItem::versionNo).reversed())
            .toList();
        return Flux.fromIterable(newestFirst).concatMap(item -> restore(engine, item, operation)).then();
    }

    private Mono<Void> restore(StorageEngine engine, OperationItem item, Operation operation) {
        return Mono.defer(() -> {
            EntityDefinition def = entities.getOrThrow(item.entityType());
            DatasetDefinition dataset = datasets.findForEntity(def.name).orElseThrow(
                () -> new IllegalStateException("No dataset serves entity type " + def.name));
            if (!poolRef.equals(dataset.storage().connectionPoolRef())) {
                return Mono.error(new IllegalStateException("Entity " + def.name + " is stored outside the storage "
                    + "of the operation tables; its operations cannot be reverted"));
            }
            String table = queryCompiler.resolveTable(dataset, def);
            UUID id = item.entityId();
            return store.load(engine, table, def, id).map(Timeline::of).flatMap(timeline -> {
                EntityVersion reverted = timeline.version(item.versionNo()).orElseThrow(() ->
                    new IllegalStateException(def.name + " [ID: " + id + "] has no version " + item.versionNo()));
                EntityVersion base = item.baseVersionNo() == null ? null
                    : timeline.version(item.baseVersionNo()).orElseThrow(() -> new IllegalStateException(
                        def.name + " [ID: " + id + "] has no version " + item.baseVersionNo()));
                VersionPlanner.Write write = VersionPlanner.Write.revert(timeline, reverted, base,
                    Set.copyOf(item.changedFields()));
                EntityVersion current = timeline.at(reverted.effectiveFrom()).orElseThrow();
                if (current.deleted() == write.deleted() && sameState(current.state(), write.state())) {
                    return Mono.<Void>empty();
                }
                Mono<Void> referenced = write.deleted() && !current.deleted()
                    ? entityManager.ensureNotReferenced(def, TemporalWriter.snapshot(def, current,
                        current.processSeqId(), current.recordedAt()))
                    : Mono.empty();
                return referenced.then(versions.append(engine, table, def, id, timeline, write, operation, true))
                    .onErrorMap(UniqueKeyViolationException.class, e -> new ConcurrentUpdateException(
                        def.name + " [ID: " + id + "] was changed concurrently; retry the revert"))
                    .then();
            });
        });
    }

    private static boolean sameState(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (!DatasetEntityManager.sameValue(entry.getValue(), b.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
