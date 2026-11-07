package com.jabiz.runtime.security.rotation;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.security.SealedFields;
import com.jabiz.security.SealedSecrets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * The first step of {@code KEY_ROTATION_REKEY}: up to {@code jabiz.key-rotation.batch-size} rows whose TOTP secret or
 * declared sealed value is stored under a retired key, found by how such values begin (the key id is part of the
 * stored form), through each entity's default dataset (current versions; no scope applies to the system).
 */
@Component
class FindRetiredValues implements StepHandler<NoMetadata, ProcessContext> {

    private final MfaSecretCipher mfa;
    private final SecretSealer sealer;
    private final ObjectProvider<SealedFields> declared;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;
    private final int batchSize;

    FindRetiredValues(MfaSecretCipher mfa, SecretSealer sealer, ObjectProvider<SealedFields> declared,
        EntityDefinitionRegistry entities, DatasetRegistry datasets, DatasetEntityManager entityManager,
        @Value("${jabiz.key-rotation.batch-size:500}") int batchSize) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalStateException("jabiz.key-rotation.batch-size must be between 1 and 10000");
        }
        this.mfa = mfa;
        this.sealer = sealer;
        this.declared = declared;
        this.entities = entities;
        this.datasets = datasets;
        this.entityManager = entityManager;
        this.batchSize = batchSize;
    }

    int batchSize() {
        return batchSize;
    }

    /**
     * At most this many values of the entity are written in one run: the job's batch, and no more than the entity's
     * dataset takes in one commit (its {@code maxWriteBatchSize}, 100 unless declared otherwise), since a run's changes
     * of an entity are committed together (ROADMAP 17zz4).
     */
    int writeLimit(String entityType) {
        return datasets.findForEntity(entityType)
            .map(dataset -> Math.max(1, Math.min(batchSize, dataset.policy().maxWriteBatchSize())))
            .orElse(batchSize);
    }

    /** This run's writes left by entity type, shared by the steps (created on first use). */
    @SuppressWarnings("unchecked")
    static java.util.Map<String, Integer> budget(ProcessContext ctx) {
        if (!ctx.contains(KeyRotationProcesses.WRITE_BUDGET)) {
            ctx.put(KeyRotationProcesses.WRITE_BUDGET, new java.util.HashMap<String, Integer>());
        }
        return (java.util.Map<String, Integer>) ctx.get(KeyRotationProcesses.WRITE_BUDGET, java.util.Map.class);
    }

    /** What may hold values under a retired key; nothing without retired keys. */
    List<RetiredValue.Target> targets() {
        List<RetiredValue.Target> targets = new ArrayList<>();
        if (!mfa.previousKeyIds().isEmpty()) {
            targets.add(new RetiredValue.Target(RetiredValue.Kind.MFA, SecurityEntities.USER_MFA, "secret",
                prefixes(mfa.previousKeyIds(), MfaSecretCipher::storedPrefix),
                row -> String.valueOf(row.get("userId"))));
        }
        if (!sealer.previousKeyIds().isEmpty()) {
            List<String> sealedPrefixes = prefixes(sealer.previousKeyIds(), SealedSecrets::storedPrefix);
            declared.orderedStream().flatMap(bean -> bean.fields().stream()).forEach(field -> targets.add(
                new RetiredValue.Target(RetiredValue.Kind.SEALED, field.entity(), field.field(), sealedPrefixes,
                    field.context())));
        }
        return targets;
    }

    private static List<String> prefixes(List<String> keyIds, Function<String, String> prefix) {
        return keyIds.stream().map(prefix).toList();
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        AtomicInteger left = new AtomicInteger(batchSize);
        java.util.Map<String, Integer> budget = budget(ctx);
        return Flux.fromIterable(targets())
            .concatMap(target -> Flux.defer(() -> {
                int entityLeft = budget.computeIfAbsent(target.entity(), this::writeLimit);
                int limit = Math.min(left.get(), entityLeft);
                if (limit <= 0) {
                    // Nothing more may be written this run: the next run looks at this target.
                    ctx.put(KeyRotationProcesses.MORE, true);
                    return Flux.empty();
                }
                return rows(target, limit).collectList().flatMapMany(rows -> {
                    left.addAndGet(-rows.size());
                    budget.put(target.entity(), entityLeft - rows.size());
                    if (rows.size() >= limit) {
                        ctx.put(KeyRotationProcesses.MORE, true);
                    }
                    return Flux.fromIterable(rows);
                });
            }))
            .collectList()
            .doOnNext(found -> ctx.put(KeyRotationProcesses.FOUND, List.copyOf(found)))
            .then();
    }

    private Flux<RetiredValue> rows(RetiredValue.Target target, int limit) {
        EntityDefinition def = entities.getOrThrow(target.entity());
        DatasetDefinition dataset = datasets.findForEntity(target.entity()).orElseThrow(() ->
            new IllegalStateException("No dataset for " + target.entity()));
        List<QueryPredicate> any = target.retiredPrefixes().stream()
            .<QueryPredicate>map(prefix -> new QueryPredicate.Like(target.field(), prefix + "%")).toList();
        EntityQuery query = EntityQuery.builder()
            .where(any.size() == 1 ? any.getFirst() : new QueryPredicate.Or(any)).limit(limit).build();
        return entityManager.queryAll(dataset, def, query).map(row -> new RetiredValue(target, row));
    }
}
