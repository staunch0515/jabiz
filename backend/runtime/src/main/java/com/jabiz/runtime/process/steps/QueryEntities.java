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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Runs an entity query through a dataset and puts the result, a {@code List<}{@link EntityInstance}{@code >}, into
 * the context (docs/design/06-process.md section 2.1). The query is built from the context when the step runs.
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

    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final DatasetEntityManager entityManager;

    public QueryEntities(DatasetRegistry datasets, EntityDefinitionRegistry entities,
        DatasetEntityManager entityManager) {
        this.datasets = datasets;
        this.entities = entities;
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            DatasetDefinition dataset = datasets.findById(metadata.datasetId()).orElseThrow();
            EntityQuery query = Objects.requireNonNull(metadata.query().apply(ctx), "query must not be null");
            return entityManager.query(dataset, entities.getOrThrow(dataset.targetEntityType()), query)
                .collectList()
                .doOnNext(found -> ctx.put(metadata.targetKey(), List.copyOf(found)))
                .then();
        });
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return datasets.findById(metadata.datasetId()).isPresent()
            ? List.of()
            : List.of("unknown dataset " + metadata.datasetId());
    }
}
