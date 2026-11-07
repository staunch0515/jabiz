package com.jabiz.runtime.security.rotation;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.entity.MetaModelConsistencyChecker;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.security.SealedFields;
import com.jabiz.security.SealedSecrets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Finds values sealed under a retired seal key in fields not declared as sealed (decision D78 item 8). The re-keying
 * reaches only declared fields ({@link SealedFields}); a value a process sealed ({@code SealSecret}) into a field
 * nobody declared stays under the retired key and no longer opens once that key is removed. Sealed values are
 * recognised by how they begin ({@code s1:<key id>:}), so the scan needs no key.
 *
 * <p>One statement per entity with a dataset counts, for each of its text fields whose column holds text (declared
 * fields left out), the rows beginning with a retired key's prefix: of every row, not only those a dataset scope would
 * show (a tenant's mail server password counts as much as any), and of a temporal entity only the current version of
 * each instance (history is not stored again, D78). Through the storage engine, values bound, names from metadata; it
 * returns counts only, never a value.
 *
 * <p>Runs only while a retired seal key is configured. The last count is the gauge
 * {@value PlatformObservations#KEYS_UNDECLARED_SEALED}, alerted on above zero.
 */
@Component
public class RetiredSealScan {

    /** Values under a retired key in one undeclared field. */
    public record Finding(String entity, String field, long values) {
        public String location() {
            return entity + "." + field;
        }

        @Override
        public String toString() {
            return location() + ": " + values;
        }
    }

    private final SecretSealer sealer;
    private final ObjectProvider<SealedFields> declared;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final QueryCompiler queryCompiler;
    private final MetaModelConsistencyChecker schema;
    private final Clock clock;
    private final AtomicLong lastCount = new AtomicLong();
    /** Text columns by table, read once (the schema does not change while the application runs). */
    private final Map<String, Set<String>> textColumns = new ConcurrentHashMap<>();

    RetiredSealScan(SecretSealer sealer, ObjectProvider<SealedFields> declared, EntityDefinitionRegistry entities,
        DatasetRegistry datasets, StorageAdapterRegistry storages, QueryCompiler queryCompiler,
        MetaModelConsistencyChecker schema, Clock clock) {
        this.sealer = sealer;
        this.declared = declared;
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.queryCompiler = queryCompiler;
        this.schema = schema;
        this.clock = clock;
    }

    /** Whether a retired seal key is configured, so that a scan means anything. */
    public boolean active() {
        return !sealer.previousKeyIds().isEmpty();
    }

    /** The undeclared fields holding values under a retired key; empty without retired keys (nothing is read). */
    public Mono<List<Finding>> undeclared() {
        if (!active()) {
            lastCount.set(0);
            return Mono.just(List.of());
        }
        Set<String> declaredFields = declared.orderedStream().flatMap(bean -> bean.fields().stream())
            .map(field -> field.entity() + "." + field.field()).collect(Collectors.toUnmodifiableSet());
        String[] prefixes = sealer.previousKeyIds().stream().map(id -> SealedSecrets.storedPrefix(id) + "%")
            .toArray(String[]::new);
        return Flux.fromIterable(entities.all())
            .sort(Comparator.comparing((EntityDefinition def) -> def.name))
            .concatMap(def -> datasets.findForEntity(def.name)
                .map(dataset -> entity(def, dataset, declaredFields, prefixes))
                .orElseGet(Flux::empty))
            .collectList()
            .doOnNext(found -> lastCount.set(found.stream().mapToLong(Finding::values).sum()));
    }

    private Flux<Finding> entity(EntityDefinition def, DatasetDefinition dataset, Set<String> declaredFields,
        String[] prefixes) {
        return textColumns(def).flatMapMany(columns -> {
            List<FieldDefinition> fields = def.fields.values().stream()
                .filter(field -> field.kind() instanceof SemanticKind.Text)
                .filter(field -> columns.contains(field.physicalColumn().toLowerCase(Locale.ROOT)))
                .filter(field -> !declaredFields.contains(def.name + "." + field.name()))
                .toList();
            if (fields.isEmpty()) {
                return Flux.empty();
            }
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("prefixes", BoundValue.of(prefixes));
            String sql = countSql(def, queryCompiler.resolveTable(dataset, def), fields, params);
            return storages.getEngine(dataset.storage().connectionPoolRef()).select(sql, params)
                .next()
                .flatMapMany(row -> {
                    List<Finding> found = new ArrayList<>();
                    for (int i = 0; i < fields.size(); i++) {
                        long values = ((Number) row.get("c" + i)).longValue();
                        if (values > 0) {
                            found.add(new Finding(def.name, fields.get(i).name(), values));
                        }
                    }
                    return Flux.fromIterable(found);
                });
        });
    }

    /** {@code count(*) FILTER (WHERE column LIKE ANY(:prefixes)) AS c<i>} per field, over the current rows. */
    private String countSql(EntityDefinition def, String table, List<FieldDefinition> fields,
        Map<String, BoundValue> params) {
        StringBuilder counts = new StringBuilder();
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            String column = SqlIdentifiers.require(fields.get(i).physicalColumn());
            columns.add(column);
            counts.append(i == 0 ? "" : ", ").append("count(*) FILTER (WHERE ").append(column)
                .append(" LIKE ANY(:prefixes)) AS c").append(i);
        }
        if (!def.temporal) {
            return "SELECT " + counts + " FROM " + table;
        }
        String id = SqlIdentifiers.require(def.primaryKeyColumn());
        String effective = SqlIdentifiers.require(def.systemColumn(TemporalSpec.EFFECT_START_TIME));
        String version = SqlIdentifiers.require(def.systemColumn(TemporalSpec.VERSION_NO));
        String deleted = SqlIdentifiers.require(def.systemColumn(TemporalSpec.DELETED));
        params.put("now", BoundValue.of(clock.instant()));
        // The version in effect now of each instance: the latest effective time not after now, its latest version.
        return "SELECT " + counts + " FROM (SELECT DISTINCT ON (" + id + ") " + deleted + " AS del, "
            + String.join(", ", columns) + " FROM " + table + " WHERE " + effective + " <= :now ORDER BY " + id + ", "
            + effective + " DESC, " + version + " DESC) cur WHERE NOT del";
    }

    /** Columns of the entity's table that hold text: a field of a text kind may be kept in another type. */
    private Mono<Set<String>> textColumns(EntityDefinition def) {
        Set<String> known = textColumns.get(def.physicalTable);
        if (known != null) {
            return Mono.just(known);
        }
        return schema.textColumns(def.physicalTable)
            .map(Set::copyOf)
            .doOnNext(columns -> textColumns.put(def.physicalTable, columns));
    }

    /** Values found by the last scan (0 before one, or without retired keys). */
    public long lastCount() {
        return lastCount.get();
    }

    @Autowired(required = false)
    void meters(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder(PlatformObservations.KEYS_UNDECLARED_SEALED, this, RetiredSealScan::lastCount)
            .description("Values under a retired seal key in fields not declared as sealed, at the last scan "
                + "(startup or a run of jabiz.key-rotation)")
            .register(registry);
    }
}
