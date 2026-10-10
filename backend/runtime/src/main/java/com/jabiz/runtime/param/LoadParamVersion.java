package com.jabiz.runtime.param;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.StepHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform step of the parameter processes: loads the {@code SysParam} entity with the key found in the context,
 * in the version in effect at a time taken from the context (the version a change at that time is based on).
 * A key without a version in effect then fails with {@code PARAM_NOT_FOUND} (422).
 */
@Component
class LoadParamVersion implements StepHandler<LoadParamVersion.Metadata, ProcessContext> {

    record Metadata(String keyKey, Function<ProcessContext, Instant> asOf, String targetKey) {
        Metadata {
            Objects.requireNonNull(keyKey, "keyKey must not be null");
            Objects.requireNonNull(asOf, "asOf must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    static StepSpec<Metadata, ProcessContext> of(String keyKey, Function<ProcessContext, Instant> asOf,
        String targetKey) {
        return StepSpec.of(LoadParamVersion.class, new Metadata(keyKey, asOf, targetKey));
    }

    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;

    LoadParamVersion(DatasetRegistry datasets, DatasetEntityManager entityManager) {
        this.datasets = datasets;
        this.entityManager = entityManager;
    }

    @Override
    public Mono<Void> execute(Metadata metadata, ProcessContext ctx) {
        return Mono.defer(() -> {
            String key = ctx.get(metadata.keyKey(), String.class);
            Instant asOf = metadata.asOf().apply(ctx);
            return find(key, asOf)
                .switchIfEmpty(Mono.error(() -> new BusinessRuleViolationException(new Violation("key",
                    PlatformErrorCodes.PARAM_NOT_FOUND, "Parameter " + key + " has no value in effect at " + asOf,
                    Map.of("key", key, "asOf", asOf.toString())))))
                .doOnNext(param -> ctx.put(metadata.targetKey(), param))
                .then();
        });
    }

    /** The version of the parameter {@code key} in effect at {@code asOf}; empty when none is. */
    Mono<EntityInstance> find(String key, Instant asOf) {
        EntityQuery query = EntityQuery.builder()
            .where(new QueryPredicate.Eq(ParamEntities.KEY, key))
            .limit(1)
            .build();
        return entityManager.query(datasets.findById(ParamEntities.DATASET).orElseThrow(), ParamEntities.SYS_PARAM,
            query, asOf, null).next();
    }
}
