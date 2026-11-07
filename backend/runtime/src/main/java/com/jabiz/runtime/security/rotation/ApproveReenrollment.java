package com.jabiz.runtime.security.rotation;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The step of {@code SEC_MFA_REENROLL_ALL_APPROVE}: the request must be open (not approved, not older than
 * {@link MfaReenrollment#EXPIRY}) and another administrator's; the approval is recorded once (its key is the
 * request's), and from then on the job takes the second factors back.
 */
@Component
class ApproveReenrollment implements StepHandler<NoMetadata, ProcessContext> {

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    ApproveReenrollment(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> execute(NoMetadata metadata, ProcessContext ctx) {
        StorageEngine engine = storages.getEngine(poolRef);
        MfaReenrollment.ApproveInput input = ctx.get(MfaReenrollment.INPUT, MfaReenrollment.ApproveInput.class);
        String request = String.valueOf(input.requestId());
        return engine.select("SELECT r.requested_by, r.requested_time, a.request_id AS approved"
                + " FROM sec_mfa_reenrollment r LEFT JOIN sec_mfa_reenrollment_approval a"
                + " ON a.request_id = r.request_id WHERE r.request_id = :id FOR UPDATE OF r",
                Map.of("id", BoundValue.of(input.requestId())))
            .next()
            .switchIfEmpty(Mono.error(() -> notPending(request)))
            .flatMap(found -> {
                Instant requested = Rows.instant(found.get("requested_time"));
                if (found.get("approved") != null || requested.plus(MfaReenrollment.EXPIRY).isBefore(ctx.opTime())) {
                    return Mono.error(notPending(request));
                }
                if (ctx.request().actorId().equals(found.get("requested_by"))) {
                    return Mono.error(new BusinessRuleViolationException(new Violation("requestId",
                        PlatformErrorCodes.APPROVAL_OWN_REQUEST, "Another administrator approves the request",
                        Map.of())));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("request_id", input.requestId());
                row.put("approved_by", ctx.request().actorId());
                row.put("approved_time", ctx.opTime());
                row.put("process_seq_id", ctx.processSeqId());
                ctx.put(MfaReenrollment.OUTPUT, new MfaReenrollment.ApproveOutput(input.requestId(), ctx.opTime()));
                return engine.insert("sec_mfa_reenrollment_approval", row);
            });
    }

    private static BusinessRuleViolationException notPending(String request) {
        return new BusinessRuleViolationException(new Violation("requestId",
            PlatformErrorCodes.MFA_REENROLLMENT_NOT_PENDING, "The request is not waiting for an approval",
            Map.of("request", request)));
    }
}
