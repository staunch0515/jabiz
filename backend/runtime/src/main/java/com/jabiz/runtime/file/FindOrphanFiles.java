package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Finds files uploaded longer than {@code jabiz.files.orphan-after} ago (measured from the operation time) that no
 * current data refers to, at most {@code jabiz.files.sweep-batch-size} of them, and puts them (as
 * {@link EntityInstance}s with their row versions) into the context (docs/design/14-files.md section 6).
 *
 * <p>Old files are read page by page, oldest first, until enough orphans are found or none are left, so files still in
 * use never hide newer orphans. Candidates are then locked {@code FOR UPDATE SKIP LOCKED} and checked again: a
 * write that is setting a reference holds the row ({@link FileFieldCheck}) and is skipped, and a reference committed
 * between the first check and the lock is seen by the second.
 */
@Component
public class FindOrphanFiles<C extends ProcessContext> implements StepHandler<FindOrphanFiles.Metadata, C> {

    /** @param targetKey context key receiving the list */
    public record Metadata(String targetKey) {
        public Metadata {
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata, C> into(String targetKey) {
        return StepSpec.of(FindOrphanFiles.class, new Metadata(targetKey));
    }

    /** One old file: its id, row version and upload time (the paging key). */
    private record Candidate(UUID id, long version, Instant uploaded) {}

    private final StorageAdapterRegistry storages;
    private final FileReferences references;
    private final FileProperties properties;
    private final String poolRef;

    public FindOrphanFiles(StorageAdapterRegistry storages, FileReferences references, FileProperties properties,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.references = references;
        this.properties = properties;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            Instant cutoff = ctx.opTime().minus(properties.orphanAfter());
            return collect(new ArrayList<>(), cutoff, null)
                .flatMap(this::lockAndRecheck)
                .doOnNext(orphans -> ctx.put(metadata.targetKey(), orphans.stream()
                    .map(c -> new EntityInstance(c.id(), FileEntities.ENTITY, c.version(), null, Map.of()))
                    .toList()))
                .then();
        });
    }

    /** Pages through old files after {@code last} until the batch is full or no old file is left. */
    private Mono<List<Candidate>> collect(List<Candidate> found, Instant cutoff, Candidate last) {
        int batch = properties.sweepBatchSize();
        return page(cutoff, last, batch).flatMap(rows -> {
            if (rows.isEmpty()) {
                return Mono.just(found);
            }
            return references.referenced(rows.stream().map(Candidate::id).toList()).flatMap(used -> {
                for (Candidate row : rows) {
                    if (found.size() < batch && !used.contains(row.id())) {
                        found.add(row);
                    }
                }
                return found.size() >= batch || rows.size() < batch ? Mono.just(found)
                    : collect(found, cutoff, rows.getLast());
            });
        });
    }

    private Mono<List<Candidate>> page(Instant cutoff, Candidate last, int limit) {
        EntityDefinition files = FileEntities.SYS_FILE;
        String id = SqlIdentifiers.require(files.primaryKeyColumn());
        String version = SqlIdentifiers.require(files.physicalColumn("version"));
        String uploaded = SqlIdentifiers.require(files.physicalColumn(FileEntities.UPLOADED_TIME));
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("cutoff", BoundValue.of(cutoff));
        params.put("limit", BoundValue.of(limit));
        String after = "";
        if (last != null) {
            after = " AND (" + uploaded + ", " + id + ") > (:afterTime, :afterId)";
            params.put("afterTime", BoundValue.of(last.uploaded()));
            params.put("afterId", BoundValue.of(last.id()));
        }
        String sql = "SELECT " + id + " AS file_id, " + version + " AS version, " + uploaded + " AS uploaded FROM "
            + SqlIdentifiers.require(files.physicalTable) + " WHERE " + uploaded + " < :cutoff" + after
            + " ORDER BY " + uploaded + ", " + id + " LIMIT :limit";
        return engine().select(sql, params)
            .map(row -> new Candidate(Rows.uuid(row.get("file_id")), Rows.longValue(row.get("version")),
                Rows.instant(row.get("uploaded"))))
            .collectList();
    }

    /** The candidates this run could lock and that are still unreferenced once locked. */
    private Mono<List<Candidate>> lockAndRecheck(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        EntityDefinition files = FileEntities.SYS_FILE;
        String id = SqlIdentifiers.require(files.primaryKeyColumn());
        String sql = "SELECT " + id + " AS file_id FROM " + SqlIdentifiers.require(files.physicalTable) + " WHERE "
            + id + " = ANY(:ids) FOR UPDATE SKIP LOCKED";
        List<UUID> ids = candidates.stream().map(Candidate::id).toList();
        return engine().select(sql, Map.of("ids", BoundValue.of(ids.toArray(UUID[]::new))))
            .map(row -> (UUID) row.get("file_id"))
            .collectList()
            .flatMap(locked -> references.referenced(locked).map(used -> candidates.stream()
                .filter(c -> locked.contains(c.id()) && !used.contains(c.id()))
                .toList()));
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }
}
