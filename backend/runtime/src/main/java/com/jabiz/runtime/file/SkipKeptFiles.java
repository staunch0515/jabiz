package com.jabiz.runtime.file;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.retention.DeletionGuard;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Takes the files that must be kept - within their retention or under a legal hold - out of the orphans to delete
 * (docs/design/21-audit-retention.md section 3.2): the sweep skips them and reports how many, instead of failing on
 * the first one as a single deletion does.
 */
@Component
public class SkipKeptFiles implements StepHandler<SkipKeptFiles.Metadata, ProcessContext> {

    /**
     * @param orphansKey context key of the orphans ({@link EntityInstance}s); replaced by those that may go
     * @param keptKey    context key receiving the ids of the files kept
     */
    public record Metadata(String orphansKey, String keptKey) {
        public Metadata {
            Objects.requireNonNull(orphansKey, "orphansKey must not be null");
            Objects.requireNonNull(keptKey, "keptKey must not be null");
        }
    }

    public static StepSpec<Metadata, ProcessContext> of(String orphansKey, String keptKey) {
        return StepSpec.of(SkipKeptFiles.class, new Metadata(orphansKey, keptKey));
    }

    private final StorageAdapterRegistry storages;
    private final DeletionGuard guard;
    private final String poolRef;

    public SkipKeptFiles(StorageAdapterRegistry storages, DeletionGuard guard,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.guard = guard;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        @SuppressWarnings("unchecked")
        List<EntityInstance> orphans = (List<EntityInstance>) ctx.get(metadata.orphansKey());
        if (orphans == null || orphans.isEmpty()) {
            ctx.put(metadata.keptKey(), List.of());
            return Mono.empty();
        }
        StorageEngine engine = storages.getEngine(poolRef);
        EntityDefinition files = FileEntities.SYS_FILE;
        String id = SqlIdentifiers.require(files.primaryKeyColumn());
        UUID[] ids = orphans.stream().map(orphan -> (UUID) orphan.id()).toArray(UUID[]::new);
        return engine.select("SELECT * FROM " + SqlIdentifiers.require(files.physicalTable) + " WHERE " + id
                + " = ANY(:ids)", Map.of("ids", BoundValue.of(ids)))
            .collectMap(row -> Rows.uuid(row.get(id)), SkipKeptFiles::attributes)
            .flatMap(rows -> Flux.fromIterable(orphans)
                .concatMap(orphan -> guard.refusal(engine, files, orphan.id(), rows.get((UUID) orphan.id()))
                    .map(refused -> new Decision(orphan, true))
                    .defaultIfEmpty(new Decision(orphan, false)))
                .collectList())
            .doOnNext(decisions -> {
                List<EntityInstance> deletable = new ArrayList<>();
                List<UUID> kept = new ArrayList<>();
                for (Decision decision : decisions) {
                    if (decision.kept()) {
                        kept.add((UUID) decision.file().id());
                    } else {
                        deletable.add(decision.file());
                    }
                }
                ctx.put(metadata.orphansKey(), List.copyOf(deletable));
                ctx.put(metadata.keptKey(), List.copyOf(kept));
            })
            .then();
    }

    private record Decision(EntityInstance file, boolean kept) {}

    /** The row's values by field name, as the guard reads them. */
    private static Map<String, Object> attributes(Map<String, Object> row) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (FieldDefinition field : FileEntities.SYS_FILE.fields.values()) {
            attributes.put(field.name(), row.get(field.physicalColumn()));
        }
        return attributes;
    }
}
