package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The storage half of the sweep (after commit; docs/design/14-files.md section 6): deletes the content of the files
 * the purge deleted, then every stored object that has no row and whose {@code fileId} (a UUIDv7, so its creation
 * time) is older than {@code jabiz.files.orphan-object-grace} before the operation time, which leaves uploads in
 * progress alone. Finally removes upload leftovers of crashed requests. Observed as {@code jabiz.file.sweep}.
 */
@Component
public class PurgeStoredContent<C extends ProcessContext> implements StepHandler<PurgeStoredContent.Metadata, C> {

    private static final Logger log = LoggerFactory.getLogger(PurgeStoredContent.class);

    /** Ids checked against the table at once. */
    private static final int CHECK_BATCH = 500;

    /** Upload leftovers older than this are removed; no upload takes that long. */
    private static final Duration STALE_UPLOAD = Duration.ofHours(6);

    /** @param orphansKey context key of the files whose rows the purge deleted */
    public record Metadata(String orphansKey) {
        public Metadata {
            Objects.requireNonNull(orphansKey, "orphansKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata, C> of(String orphansKey) {
        return StepSpec.of(PurgeStoredContent.class, new Metadata(orphansKey));
    }

    private final LocalFileStore store;
    private final StorageAdapterRegistry storages;
    private final FileProperties properties;
    private final PlatformObservations observations;
    private final String poolRef;

    public PurgeStoredContent(LocalFileStore store, StorageAdapterRegistry storages, FileProperties properties,
        PlatformObservations observations, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.store = store;
        this.storages = storages;
        this.properties = properties;
        this.observations = observations;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return observations.mono(PlatformObservations.FILE_SWEEP, "file sweep", KeyValues.empty(), Mono.defer(() -> {
            @SuppressWarnings("unchecked")
            List<EntityInstance> orphans = (List<EntityInstance>) ctx.get(metadata.orphansKey());
            Instant cutoff = ctx.opTime().minus(properties.orphanObjectGrace());
            Mono<Void> deletedRows = Flux.fromIterable(orphans)
                .concatMap(orphan -> store.deleteAll(FileKeys.prefix((UUID) orphan.id())))
                .then();
            Mono<Integer> orphanObjects = store.list()
                .flatMapIterable(key -> FileKeys.fileIdOf(key).stream().toList())
                .filter(id -> FileKeys.timeOf(id).isBefore(cutoff))
                // The walk is depth first: the objects of one file come together.
                .distinctUntilChanged()
                .buffer(CHECK_BATCH)
                .concatMap(ids -> withoutRow(ids).flatMapMany(Flux::fromIterable))
                .concatMap(id -> store.deleteAll(FileKeys.prefix(id)).thenReturn(1))
                .reduce(0, Integer::sum);
            return deletedRows
                .then(orphanObjects)
                .zipWith(store.deleteStaleParts(STALE_UPLOAD))
                .doOnNext(counts -> log.info("File sweep: content of {} deleted file(s), {} orphan object group(s) "
                    + "and {} upload leftover(s) removed", orphans.size(), counts.getT1(), counts.getT2()))
                .then(PlatformObservations.tag(PlatformObservations.RESULT, "swept"));
        }));
    }

    /** Those of the ids that have no row. */
    private Mono<Set<UUID>> withoutRow(List<UUID> ids) {
        EntityDefinition files = FileEntities.SYS_FILE;
        String id = SqlIdentifiers.require(files.primaryKeyColumn());
        String sql = "SELECT " + id + " AS file_id FROM " + SqlIdentifiers.require(files.physicalTable) + " WHERE "
            + id + " = ANY(:ids)";
        return storages.getEngine(poolRef).select(sql, Map.of("ids", BoundValue.of(ids.toArray(UUID[]::new))))
            .map(row -> (UUID) row.get("file_id"))
            .collectList()
            .map(existing -> {
                Set<UUID> missing = new LinkedHashSet<>(ids);
                existing.forEach(missing::remove);
                return missing;
            });
    }
}
