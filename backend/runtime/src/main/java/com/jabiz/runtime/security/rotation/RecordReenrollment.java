package com.jabiz.runtime.security.rotation;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The step of {@code SEC_MFA_REENROLL_ALL_REQUEST}: one open request at a time; recorded with its reason. */
@Component
class RecordReenrollment implements StepHandler<NoMetadata, ProcessContext> {

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final EntityIdGenerator ids;
    private final EntityDefinitionRegistry entities;

    RecordReenrollment(StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        EntityIdGenerator ids, EntityDefinitionRegistry entities) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.ids = ids;
        this.entities = entities;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        StorageEngine engine = storages.getEngine(poolRef);
        MfaReenrollment.RequestInput input = ctx.get(MfaReenrollment.INPUT, MfaReenrollment.RequestInput.class);
        return engine.select("SELECT r.request_id FROM sec_mfa_reenrollment r WHERE r.requested_time > :since"
                + " AND NOT EXISTS (SELECT 1 FROM sec_mfa_reenrollment_approval a WHERE a.request_id = r.request_id)"
                + " LIMIT 1", Map.of("since", BoundValue.of(ctx.opTime().minus(MfaReenrollment.EXPIRY))))
            .next()
            .flatMap(open -> Mono.<Void>error(new BusinessRuleViolationException(new Violation(null,
                PlatformErrorCodes.MFA_REENROLLMENT_OPEN, "A request that everyone set up a new second factor waits "
                + "for its approval already", Map.of("request", String.valueOf(open.get("request_id")))))))
            .switchIfEmpty(Mono.defer(() -> {
                UUID requestId = UUID.fromString(String.valueOf(ids.next(entities.getOrThrow(
                    MfaReenrollment.ENTITY))));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request_id", requestId);
                row.put("requested_by", ctx.request().actorId());
                row.put("requested_time", ctx.opTime());
                row.put("reason", input.reason());
                row.put("process_seq_id", ctx.processSeqId());
                ctx.put(MfaReenrollment.OUTPUT, new MfaReenrollment.RequestOutput(requestId,
                    ctx.opTime().plus(MfaReenrollment.EXPIRY)));
                return engine.insert("sec_mfa_reenrollment", row);
            }));
    }
}
