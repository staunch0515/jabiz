package com.jabiz.runtime.file;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.file.FileKind;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which files current data still refers to (docs/design/14-files.md section 6): a file is referenced when a
 * {@code jabiz.file} field holds its id in the current data, read straight from the tables, not through a dataset's
 * scope. For a temporal entity that is its current version and every version scheduled after it (but not history:
 * old versions may point at a deleted file); for an ordinary entity every row, logically deleted ones included.
 */
@Component
public class FileReferences {

    /** One field that refers to a file. */
    public record Reference(String entity, String field) {}

    private record FileField(EntityDefinition entity, FieldDefinition field, DatasetDefinition dataset) {}

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final QueryCompiler queryCompiler;
    private final Clock clock;

    public FileReferences(EntityDefinitionRegistry entities, DatasetRegistry datasets,
        StorageAdapterRegistry storages, QueryCompiler queryCompiler, Clock clock) {
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.queryCompiler = queryCompiler;
        this.clock = clock;
    }

    /** The first field found that refers to the file; empty when nothing does. */
    public Mono<Optional<Reference>> firstReference(UUID fileId) {
        return Flux.fromIterable(fileFields())
            .concatMap(field -> referenced(field, List.of(fileId))
                .filter(found -> !found.isEmpty())
                .map(found -> new Reference(field.entity().name, field.field().name())))
            .next()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    /** Those of {@code fileIds} that some field refers to. */
    public Mono<Set<UUID>> referenced(Collection<UUID> fileIds) {
        if (fileIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        List<UUID> ids = List.copyOf(fileIds);
        return Flux.fromIterable(fileFields())
            .concatMap(field -> referenced(field, ids))
            .collect(LinkedHashSet<UUID>::new, Set::addAll)
            .map(Set::copyOf);
    }

    private List<FileField> fileFields() {
        return entities.all().stream()
            .flatMap(def -> FileKind.fieldsOf(def).stream()
                .flatMap(field -> datasets.findForEntity(def.name).stream()
                    .map(dataset -> new FileField(def, field, dataset))))
            .toList();
    }

    private Mono<Set<UUID>> referenced(FileField fileField, List<UUID> ids) {
        EntityDefinition def = fileField.entity();
        String table = queryCompiler.resolveTable(fileField.dataset(), def);
        String column = SqlIdentifiers.require(def.physicalColumn(fileField.field().name()));
        Map<String, BoundValue> params = new LinkedHashMap<>();
        params.put("ids", BoundValue.of(ids.toArray(UUID[]::new)));
        String sql;
        if (!def.temporal) {
            sql = "SELECT DISTINCT " + column + " AS file_id FROM " + table + " WHERE " + column + " = ANY(:ids)";
        } else {
            String id = SqlIdentifiers.require(def.primaryKeyColumn());
            String effective = SqlIdentifiers.require(def.systemColumn(TemporalSpec.EFFECT_START_TIME));
            String version = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
            String deleted = SqlIdentifiers.require(def.systemColumn(TemporalSpec.DELETED));
            params.put("now", BoundValue.of(clock.instant()));
            // Per instance that ever held one of the ids: the winning version of each effective time, then those
            // from the current one on. The winner is chosen before filtering on the column, so that a later
            // correction that cleared the field is seen.
            sql = "WITH w AS (SELECT DISTINCT ON (" + id + ", " + effective + ") " + id + " AS iid, " + effective
                + " AS eff, " + deleted + " AS del, " + column + " AS fid FROM " + table
                + " WHERE " + id + " IN (SELECT " + id + " FROM " + table + " WHERE " + column + " = ANY(:ids))"
                + " ORDER BY " + id + ", " + effective + ", " + version + " DESC),"
                + " cur AS (SELECT DISTINCT ON (iid) iid, eff FROM w WHERE eff <= :now ORDER BY iid, eff DESC)"
                + " SELECT DISTINCT w.fid AS file_id FROM w LEFT JOIN cur ON cur.iid = w.iid"
                + " WHERE NOT w.del AND w.fid = ANY(:ids) AND (cur.iid IS NULL OR w.eff >= cur.eff)";
        }
        return storages.getEngine(fileField.dataset().storage().connectionPoolRef())
            .select(sql, params)
            .map(row -> (UUID) row.get("file_id"))
            .collect(LinkedHashSet<UUID>::new, Set::add)
            .map(Set::copyOf);
    }
}
