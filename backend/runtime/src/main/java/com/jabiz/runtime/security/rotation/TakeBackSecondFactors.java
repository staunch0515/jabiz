package com.jabiz.runtime.security.rotation;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.security.RefreshTokenStore;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A step of {@code KEY_ROTATION_REKEY} (decision D78): after an approved re-enrolment of everyone
 * ({@link MfaReenrollment}), takes back up to a batch of second factors confirmed before the approval - no longer
 * confirmed, recovery codes gone, {@code reenrollRequired} - and ends their users' sessions, through the ordinary write
 * path. A second factor set up after the approval is left alone.
 */
@Component
class TakeBackSecondFactors implements StepHandler<NoMetadata, ProcessContext> {

    static final String TAKEN_BACK = "takenBack";
    /** The ids of the second factors taken back in this run, which the re-encryption leaves alone. */
    static final String TAKEN_BACK_IDS = "takenBackIds";
    static final String REASON = "mfa-reenrollment";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;
    private final RefreshTokenStore sessions;
    private final FindRetiredValues finder;

    TakeBackSecondFactors(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, DatasetRegistry datasets,
        DatasetEntityManager entityManager, RefreshTokenStore sessions, FindRetiredValues finder) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.datasets = datasets;
        this.entityManager = entityManager;
        this.sessions = sessions;
        this.finder = finder;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        return storages.getEngine(poolRef).select("SELECT max(approved_time) AS approved FROM"
                + " sec_mfa_reenrollment_approval", Map.of())
            .next()
            .mapNotNull(row -> row.get("approved") == null ? null : Rows.instant(row.get("approved")))
            .flatMap(approved -> secondFactors(approved).collectList())
            .defaultIfEmpty(List.of())
            .flatMap(found -> {
                // Shares the second factors' writes of this run with the re-keying (ROADMAP 17zz4).
                int limit = finder.writeLimit(SecurityEntities.USER_MFA);
                FindRetiredValues.budget(ctx).put(SecurityEntities.USER_MFA, limit - found.size());
                if (found.size() >= limit) {
                    ctx.put(KeyRotationProcesses.MORE, true);
                }
                ctx.put(TAKEN_BACK, found.size());
                ctx.put(TAKEN_BACK_IDS, found.stream().map(mfa -> String.valueOf(mfa.id()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
                return Flux.fromIterable(found).concatMap(mfa -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("confirmed", false);
                    values.put("recoveryCodes", null);
                    values.put("reenrollRequired", true);
                    ctx.changes().update(SecurityEntities.USER_MFA, mfa.id(), mfa.version(), values);
                    Object user = mfa.get("userId");
                    return sessions.revokeUser(UUID.fromString(String.valueOf(user)), REASON, null,
                        ctx.processSeqId());
                }).then();
            });
    }

    private Flux<EntityInstance> secondFactors(Instant approved) {
        DatasetDefinition dataset = datasets.findById(SecurityEntities.USER_MFA_DATASET).orElseThrow();
        return entityManager.queryAll(dataset, SecurityEntities.SEC_USER_MFA, EntityQuery.builder()
            .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("confirmed", true),
                new QueryPredicate.Lt("confirmedTime", approved))))
            .limit(finder.writeLimit(SecurityEntities.USER_MFA)).build());
    }
}
