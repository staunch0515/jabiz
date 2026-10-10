package com.jabiz.runtime.ledger;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.SemanticKind;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
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

    /** Context key of the names of the dimensions whose values are instance ids, a {@code Set<String>}. */
    static final String ID_DIMENSIONS = "ledger_id_dimensions";

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
            ctx.put(ID_DIMENSIONS, dimensions.all().stream().filter(this::takesIds).map(LedgerDimension::name)
                .collect(Collectors.toUnmodifiableSet()));
            return LedgerAccountCheck.lock(storages.getEngine(poolRef), true)
                .thenMany(Flux.fromIterable(dimensions.all()).concatMap(dimension -> checkDimension(ctx, input,
                    dimension)))
                .then(checkSource(ctx, input));
        });
    }

    private Mono<Void> checkDimension(C ctx, LedgerProcesses.PostInput input, LedgerDimension dimension) {
        boolean ids = takesIds(dimension);
        // Values as given (longer ones are refused by LedgerPosting), and the form they are looked up and stored in.
        Map<String, String> values = new java.util.LinkedHashMap<>();
        input.entries().forEach(line -> {
            String value = line.dimensions() == null ? null : line.dimensions().get(dimension.name());
            if (value != null && !value.isBlank() && value.length() <= LedgerDimension.MAX_VALUE_LENGTH) {
                values.put(value, ids ? canonicalId(value) : value);
            }
        });
        if (values.isEmpty()) {
            return Mono.empty();
        }
        // A malformed id is not looked up: it is invalid as it stands.
        Set<String> lookup = values.values().stream().filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Mono<Set<String>> valid = switch (dimension.source()) {
            case LedgerDimension.DictionarySource dictionary -> dictionaries.enabledCodes(dictionary.dictionaryUrn());
            case LedgerDimension.EntitySource source when lookup.isEmpty() -> Mono.just(Set.of());
            case LedgerDimension.EntitySource source -> {
                EntityDefinition def = entities.getOrThrow(source.entity());
                DatasetDefinition dataset = datasets.findForEntity(source.entity()).orElseThrow();
                EntityQuery query = EntityQuery.builder()
                    .where(new QueryPredicate.In(source.field(), List.copyOf(lookup)))
                    .limit(lookup.size() + 1).build();
                // Every value of the posting, not a page of the source's dataset (decision D32).
                yield entityManager.queryAll(dataset, def, query)
                    .map(found -> String.valueOf(found.<Object>get(source.field())))
                    .map(found -> ids ? found.toLowerCase(Locale.ROOT) : found)
                    .collect(Collectors.toSet());
            }
        };
        return valid.doOnNext(known -> {
            for (int i = 0; i < input.entries().size(); i++) {
                LedgerProcesses.Line line = input.entries().get(i);
                String value = line.dimensions() == null ? null : line.dimensions().get(dimension.name());
                String key = value == null ? null : values.get(value);
                if (value != null && values.containsKey(value) && (key == null || !known.contains(key))) {
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
     * Whether the dimension's values are instance ids: its source is an identity or reference field. They are
     * compared and stored in canonical form, as the platform writes UUIDs.
     */
    private boolean takesIds(LedgerDimension dimension) {
        return dimension.source() instanceof LedgerDimension.EntitySource source
            && entities.find(source.entity()).map(def -> def.fields.get(source.field()))
                .map(field -> field.kind() instanceof SemanticKind.SemanticIdentity
                    || field.kind() instanceof SemanticKind.Reference)
                .orElse(false);
    }

    /**
     * The canonical (lower-case) form of a UUID written in full, or null for anything else: {@link java.util.UUID#fromString}
     * also takes shortened groups such as {@code 1-2-3-4-5}, which no stored id matches as text.
     */
    static String canonicalId(String value) {
        return value != null && UUID_TEXT.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : null;
    }

    private static final Pattern UUID_TEXT =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The entry fields of a line's memo and dimensions: {@code memo}, {@code dimension1} … The values of the
     * dimensions named in {@code idDimensions} (checked to be ids) are stored in canonical form.
     */
    static Map<String, Object> entryFields(LedgerProcesses.Line line, List<LedgerDimension> declared,
        Set<String> idDimensions) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("memo", line.memo());
        for (LedgerDimension dimension : declared) {
            String value = line.dimensions() == null ? null : line.dimensions().get(dimension.name());
            if (value != null && idDimensions.contains(dimension.name())) {
                value = value.toLowerCase(Locale.ROOT);
            }
            fields.put(dimension.field(), value == null || value.isBlank() ? null : value);
        }
        return fields;
    }
}
