package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Finds files uploaded longer than {@code jabiz.files.orphan-after} ago that no current data refers to, oldest first
 * and at most {@code jabiz.files.sweep-batch-size} of them, and puts them (as {@link EntityInstance}s with their row
 * versions) into the context (docs/design/14-files.md section 6). The age is measured from the operation time.
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
            EntityDefinition files = FileEntities.SYS_FILE;
            String id = SqlIdentifiers.require(files.primaryKeyColumn());
            String version = SqlIdentifiers.require(files.physicalColumn("version"));
            String uploaded = SqlIdentifiers.require(files.physicalColumn(FileEntities.UPLOADED_TIME));
            String sql = "SELECT " + id + " AS file_id, " + version + " AS version FROM "
                + SqlIdentifiers.require(files.physicalTable) + " WHERE " + uploaded + " < :cutoff ORDER BY "
                + uploaded + ", " + id + " LIMIT :limit";
            Map<String, BoundValue> params = Map.of(
                "cutoff", BoundValue.of(ctx.opTime().minus(properties.orphanAfter())),
                "limit", BoundValue.of(properties.sweepBatchSize()));
            return storages.getEngine(poolRef).select(sql, params)
                .map(row -> new EntityInstance(row.get("file_id"), FileEntities.ENTITY,
                    ((Number) row.get("version")).longValue(), null, Map.of()))
                .collectList()
                .flatMap(candidates -> references.referenced(candidates.stream().map(c -> (UUID) c.id()).toList())
                    .map(used -> candidates.stream().filter(c -> !used.contains((UUID) c.id())).toList()))
                .doOnNext(orphans -> ctx.put(metadata.targetKey(), List.copyOf(orphans)))
                .then();
        });
    }
}
