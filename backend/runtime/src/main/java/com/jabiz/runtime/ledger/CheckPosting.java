package com.jabiz.runtime.ledger;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.ledger.LedgerDimension;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.dictionary.DictionaryRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What {@code LEDGER_POST} checks against the database besides the accounts (docs/design/11-ledger-events-jobs.md
 * sections 1.5 and 1.6): every dimension value is in its dimension's list ({@code LEDGER_DIMENSION_INVALID}) and the
 * source document exists ({@code LEDGER_SOURCE_NOT_FOUND}). It also takes the account lock shared, so that no account
 * turns summary while the transaction is booked ({@link LedgerAccountCheck}), and puts the declared dimensions into the
 * context for the booking.
 */
@Component
class CheckPosting<C extends ProcessContext> implements StepHandler<CheckPosting.Metadata, C> {

    /** Context key of the declared dimensions, a {@code List<LedgerDimension>}. */
    static final String DIMENSIONS = "ledger_dimensions";

    /**
     * Context key of the values of the dimensions whose values are ids as they are checked and stored, a
     * {@code Map<String, Map<String, String>>}: dimension name, then value as given to its canonical form.
     */
    static final String ID_VALUES = "ledger_id_values";

    record Metadata(String inputKey) {
        Metadata {
            Objects.requireNonNull(inputKey, "inputKey must not be null");
        }
    }

    static <C extends ProcessContext> StepSpec<Metadata, C> of(String inputKey) {
        return StepSpec.of(CheckPosting.class, new Metadata(inputKey));
    }

    private final LedgerDimensionRegistry dimensions;
    private final DictionaryRegistry dictionaries;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    CheckPosting(LedgerDimensionRegistry dimensions, DictionaryRegistry dictionaries, EntityDefinitionRegistry entities,
        DatasetRegistry datasets, DatasetEntityManager entityManager, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.dimensions = dimensions;
        this.dictionaries = dictionaries;
        this.entities = entities;
        this.datasets = datasets;
        this.entityManager = entityManager;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, C ctx) {
        return Mono.defer(() -> {
            LedgerProcesses.PostInput input = ctx.get(metadata.inputKey(), LedgerProcesses.PostInput.class);
            ctx.put(DIMENSIONS, dimensions.all());
            Map<String, IdValues> ids = new LinkedHashMap<>();
            Map<String, Map<String, String>> stored = new LinkedHashMap<>();
            for (String name : dimensions.idValued()) {
                IdValues values = IdValues.of(input.entries(), name);
                ids.put(name, values);
                stored.put(name, values.canonical());
            }
            ctx.put(ID_VALUES, Map.copyOf(stored));
            return LedgerAccountCheck.lock(storages.getEngine(poolRef), true)
                .thenMany(Flux.fromIterable(dimensions.all()).concatMap(dimension -> checkDimension(ctx, input,
                    dimension, ids.get(dimension.name()))))
                .then(checkSource(ctx, input));
        });
    }

    /**
     * The values of an id dimension in a posting: each value given (not blank, not too long, which LedgerPosting
     * refuses) that is an id, with its canonical form ({@link LedgerDimension#canonicalId}), and those that are not.
     */
    record IdValues(Map<String, String> canonical, Set<String> malformed) {
        static IdValues of(List<LedgerProcesses.Line> lines, String dimension) {
            Map<String, String> canonical = new LinkedHashMap<>();
            Set<String> malformed = new LinkedHashSet<>();
            for (String value : given(lines, dimension)) {
                String id = LedgerDimension.canonicalId(value);
                if (id == null) {
                    malformed.add(value);
                } else {
                    canonical.put(value, id);
                }
            }
            return new IdValues(Map.copyOf(canonical), Set.copyOf(malformed));
        }
    }

    /** The values of a dimension in the lines that are checked against its list. */
    private static Set<String> given(List<LedgerProcesses.Line> lines, String dimension) {
        Set<String> values = new LinkedHashSet<>();
        lines.forEach(line -> {
            String value = line.dimensions() == null ? null : line.dimensions().get(dimension);
            if (value != null && !value.isBlank() && value.length() <= LedgerDimension.MAX_VALUE_LENGTH) {
                values.add(value);
            }
        });
        return values;
    }

    /** {@code ids}: the values of a dimension whose values are ids; null for any other. */
    private Mono<Void> checkDimension(C ctx, LedgerProcesses.PostInput input, LedgerDimension dimension,
        IdValues ids) {
        // Each value as given, and the form it is looked up in; a malformed id is invalid as it stands.
        Map<String, String> lookup = new LinkedHashMap<>();
        Set<String> malformed = ids == null ? Set.of() : ids.malformed();
        if (ids == null) {
            given(input.entries(), dimension.name()).forEach(value -> lookup.put(value, value));
        } else {
            lookup.putAll(ids.canonical());
        }
        if (lookup.isEmpty() && malformed.isEmpty()) {
            return Mono.empty();
        }
        Mono<Set<String>> valid = switch (dimension.source()) {
            case LedgerDimension.DictionarySource dictionary -> dictionaries.enabledCodes(dictionary.dictionaryUrn());
            case LedgerDimension.EntitySource source when lookup.isEmpty() -> Mono.just(Set.of());
            case LedgerDimension.EntitySource source -> {
                EntityDefinition def = entities.getOrThrow(source.entity());
                DatasetDefinition dataset = datasets.findForEntity(source.entity()).orElseThrow();
                List<Object> wanted = List.copyOf(new LinkedHashSet<>(lookup.values()));
                EntityQuery query = EntityQuery.builder()
                    .where(new QueryPredicate.In(source.field(), wanted))
                    .limit(wanted.size() + 1).build();
                // Every value of the posting, not a page of the source's dataset (decision D32).
                yield entityManager.queryAll(dataset, def, query)
                    .map(found -> String.valueOf(found.<Object>get(source.field())))
                    .map(found -> ids == null ? found : LedgerDimension.canonicalId(found))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            }
        };
        return valid.doOnNext(known -> {
            for (int i = 0; i < input.entries().size(); i++) {
                LedgerProcesses.Line line = input.entries().get(i);
                String value = line.dimensions() == null ? null : line.dimensions().get(dimension.name());
                if (value == null) {
                    continue;
                }
                if (malformed.contains(value) || (lookup.containsKey(value) && !known.contains(lookup.get(value)))) {
                    ctx.reject(new Violation("entries", PlatformErrorCodes.LEDGER_DIMENSION_INVALID,
                        "Entry " + (i + 1) + ": " + value + " is not a valid " + dimension.name(),
                        Map.of("line", i + 1, "dimension", dimension.name(), "value", value)));
                }
            }
        }).then();
    }

    private Mono<Void> checkSource(C ctx, LedgerProcesses.PostInput input) {
        String entity = input.sourceEntity();
        String id = input.sourceId();
        if (entity == null && id == null) {
            return Mono.empty();
        }
        Map<String, Object> params = Map.of("entity", String.valueOf(entity), "id", String.valueOf(id));
        Violation missing = new Violation("sourceId", PlatformErrorCodes.LEDGER_SOURCE_NOT_FOUND,
            "The source document " + entity + " " + id + " does not exist", params);
        var def = entity == null ? null : entities.find(entity).orElse(null);
        var dataset = def == null ? null : datasets.findForEntity(entity).orElse(null);
        if (dataset == null || id == null || id.isBlank()) {
            ctx.reject(missing);
            return Mono.empty();
        }
        Object key;
        try {
            key = def.temporal ? def.normalizeId(id) : id;
        } catch (IllegalArgumentException e) {
            ctx.reject(missing);
            return Mono.empty();
        }
        return entityManager.findById(dataset, def, key)
            .map(EntityInstance::id)
            .hasElement()
            .doOnNext(found -> {
                if (!found) {
                    ctx.reject(missing);
                }
            })
            .then();
    }

    /**
     * The entry fields of a line's memo and dimensions: {@code memo}, {@code dimension1} … A dimension whose values
     * are ids stores the form its value was checked in ({@code idValues}, from {@link #ID_VALUES}).
     */
    static Map<String, Object> entryFields(LedgerProcesses.Line line, List<LedgerDimension> declared,
        Map<String, Map<String, String>> idValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("memo", line.memo());
        for (LedgerDimension dimension : declared) {
            String value = line.dimensions() == null ? null : line.dimensions().get(dimension.name());
            Map<String, String> canonical = idValues.get(dimension.name());
            if (value != null && canonical != null && canonical.containsKey(value)) {
                value = canonical.get(value);
            }
            fields.put(dimension.field(), value == null || value.isBlank() ? null : value);
        }
        return fields;
    }
}
