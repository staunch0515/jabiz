package com.jabiz.runtime.process.steps;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs an entity query through a dataset and puts the result, a {@code List<}{@link EntityInstance}{@code >}, into
 * the context (docs/design/06-process.md section 2.1). The query is built from the context when the step runs. Up to
 * the query's own limit, not a page of the dataset's {@code maxQueryBatchSize} (decision D32); more rows than {@code jabiz.process.max-read-rows} are
 * refused (422 {@code PROCESS_READ_TOO_LARGE}). Rows past the query's own limit are not read: a query that must see
 * them all asks for more than it expects.
 *
 * <p>{@link #all} runs several such queries as one step and, where their datasets share a storage engine, one
 * statement (ROADMAP phase 16p, T5): each exactly as it would run alone, its result under its own key.
 */
@Component
public class QueryEntities<C extends ProcessContext> implements StepHandler<QueryEntities.Metadata<C>, C>,
    CheckedStep<QueryEntities.Metadata<C>> {

    public record Metadata<C>(String datasetId, Function<C, EntityQuery> query, String targetKey) {
        public Metadata {
            Objects.requireNonNull(datasetId, "datasetId must not be null");
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String datasetId,
        Function<C, EntityQuery> query, String targetKey) {
        return StepSpec.of(QueryEntities.class, new Metadata<>(datasetId, query, targetKey));
    }

    /** One query of {@link #all}, as {@link #of} takes it. */
    public static <C extends ProcessContext> Metadata<C> query(String datasetId, Function<C, EntityQuery> query,
        String targetKey) {
        return new Metadata<>(datasetId, query, targetKey);
    }

    /** The queries of one {@link #all} step, in order. */
    public record AllMetadata<C>(List<Metadata<C>> queries) {
        public AllMetadata {
            queries = List.copyOf(queries);
            if (queries.isEmpty()) {
                throw new IllegalArgumentException("queries must not be empty");
            }
        }
    }

    /**
     * Several queries in one step, sent as one statement where the engine can: each result under its key, as
     * {@link #of} puts it. For reads that do not depend on each other, such as the rereads after taking a lock.
     */
    @SafeVarargs
    public static <C extends ProcessContext> StepSpec<AllMetadata<C>, C> all(Metadata<C>... queries) {
        return StepSpec.of(All.class, new AllMetadata<>(List.of(queries)));
    }

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;
    private final int maxRows;

    public QueryEntities(DatasetRegistry datasets, EntityDefinitionRegistry entities,
        DatasetEntityManager entityManager, @Value("${jabiz.process.max-read-rows:100000}") int maxRows) {
        ProcessReads.checkMaximum(maxRows);
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
        this.maxRows = maxRows;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = datasets.findById(metadata.datasetId()).orElseThrow();
            EntityQuery query = Objects.requireNonNull(metadata.query().apply(ctx), "query must not be null");
            // The query's own limit, not a page of the dataset (decision D32). A limit above the processes' maximum is
            // read to one row past it: refused only when there are that many rows, not for asking.
            boolean bounded = query.limit() > maxRows;
            EntityQuery read = bounded
                ? new EntityQuery(query.predicate(), query.sorts(), query.offset(), maxRows + 1) : query;
            return entityManager.queryAll(dataset, entities.getOrThrow(dataset.targetEntityType()), read)
                .collectList()
                .flatMap(found -> bounded && found.size() > maxRows
                    ? Mono.<Void>error(ProcessReads.tooLarge("dataset " + metadata.datasetId(), maxRows))
                    : Mono.fromRunnable(() -> ctx.put(metadata.targetKey(), List.copyOf(found))));
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return datasets.findById(metadata.datasetId()).isPresent()
            ? List.of()
            : List.of("unknown dataset " + metadata.datasetId());
    }

    /** The step of {@link QueryEntities#all}. */
    @Component
    public static class All<C extends ProcessContext> implements StepHandler<AllMetadata<C>, C>,
        CheckedStep<AllMetadata<C>> {

        private final DatasetRegistry datasets;
        private final EntityDefinitionRegistry entities;
        private final DatasetEntityManager entityManager;
        private final int maxRows;

        public All(DatasetRegistry datasets, EntityDefinitionRegistry entities, DatasetEntityManager entityManager,
            @Value("${jabiz.process.max-read-rows:100000}") int maxRows) {
            ProcessReads.checkMaximum(maxRows);
            this.datasets = datasets;
            this.entities = entities;
            this.entityManager = entityManager;
            this.maxRows = maxRows;
        }

        @Override
        public Mono<Void> execute(AllMetadata<C> metadata, C ctx) {
            return Mono.defer(() -> {
                List<DatasetEntityManager.EntityRead> reads = new java.util.ArrayList<>();
                List<Boolean> bounded = new java.util.ArrayList<>();
                for (Metadata<C> query : metadata.queries()) {
                    DatasetDefinition dataset = datasets.findById(query.datasetId()).orElseThrow();
                    EntityQuery asked = Objects.requireNonNull(query.query().apply(ctx), "query must not be null");
                    boolean tooMany = asked.limit() > maxRows;
                    bounded.add(tooMany);
                    reads.add(new DatasetEntityManager.EntityRead(dataset,
                        entities.getOrThrow(dataset.targetEntityType()), tooMany
                            ? new EntityQuery(asked.predicate(), asked.sorts(), asked.offset(), maxRows + 1) : asked));
                }
                return entityManager.queryMany(reads).flatMap(results -> {
                    for (int i = 0; i < results.size(); i++) {
                        if (bounded.get(i) && results.get(i).size() > maxRows) {
                            return Mono.<Void>error(ProcessReads.tooLarge(
                                "dataset " + metadata.queries().get(i).datasetId(), maxRows));
                        }
                    }
                    for (int i = 0; i < results.size(); i++) {
                        ctx.put(metadata.queries().get(i).targetKey(), List.copyOf(results.get(i)));
                    }
                    return Mono.<Void>empty();
                });
            });
        }

        @Override
        public List<String> problems(AllMetadata<C> metadata) {
            return metadata.queries().stream()
                .filter(query -> datasets.findById(query.datasetId()).isEmpty())
                .map(query -> "unknown dataset " + query.datasetId())
                .toList();
        }
    }
}
