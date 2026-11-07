package com.jabiz.runtime.security.rotation;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.security.MfaRequirement;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.security.SecurityPermissions;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * "Everyone sets up a new second factor" (decision D78): the response to a {@code JABIZ_MFA_KEY} that leaked together
 * with a copy of the database, where re-encrypting cannot help (the history keeps the old ciphertext of the same
 * TOTP secrets). One administrator asks for it with a reason ({@code SEC_MFA_REENROLL_ALL_REQUEST}); another approves
 * it within {@link #EXPIRY} ({@code SEC_MFA_REENROLL_ALL_APPROVE}; the asker cannot). From the approval on, the job
 * {@code jabiz.key-rotation} takes back every second factor confirmed before it - no longer confirmed, its recovery
 * codes gone, marked {@code reenrollRequired}, the user's sessions ended - so that the next sign-in asks for a new one.
 * Passkeys are not affected (they hold no secret of the platform's).
 */
@Configuration
public class MfaReenrollment {

    public static final String REQUEST = "SEC_MFA_REENROLL_ALL_REQUEST";
    public static final String APPROVE = "SEC_MFA_REENROLL_ALL_APPROVE";
    public static final String ENTITY = "MfaReenrollment";
    public static final String APPROVAL_ENTITY = "MfaReenrollmentApproval";
    static final String DATASET = "urn:jabiz:dataset:platform:MfaReenrollment";
    static final String APPROVAL_DATASET = "urn:jabiz:dataset:platform:MfaReenrollmentApproval";
    /** How long a request waits for its approval. */
    public static final Duration EXPIRY = Duration.ofHours(24);

    static final String INPUT = "input";
    static final String OUTPUT = "output";

    public record RequestInput(@NotBlank @Size(max = 1000) String reason) {}

    public record RequestOutput(UUID requestId, Instant expiresAt) {}

    public record ApproveInput(@NotNull UUID requestId) {}

    public record ApproveOutput(UUID requestId, Instant approvedTime) {}

    public static final ProcessDefinition<RequestInput, RequestOutput, ProcessContext> REQUEST_PROCESS =
        ProcessDefinition.define(REQUEST, 1, RequestInput.class, RequestOutput.class, ProcessContext.class, pb -> pb
            .description("Asks that everyone set up a new second factor (a leaked MFA key); another administrator "
                + "approves.")
            .permissions(SecurityPermissions.MFA_RESET)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, RequestOutput.class))
            .step("Record the request", RecordReenrollment.class, NoMetadata.INSTANCE));

    public static final ProcessDefinition<ApproveInput, ApproveOutput, ProcessContext> APPROVE_PROCESS =
        ProcessDefinition.define(APPROVE, 1, ApproveInput.class, ApproveOutput.class, ProcessContext.class, pb -> pb
            .description("Approves another administrator's request that everyone set up a new second factor.")
            .permissions(SecurityPermissions.MFA_RESET)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ApproveOutput.class))
            .step("Approve the request", ApproveReenrollment.class, NoMetadata.INSTANCE));

    public static final EntityDefinition REQUEST_ENTITY = EntityDefinition.define(ENTITY, eb -> {
        eb.physicalTable("sec_mfa_reenrollment");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:mfa-reenrollment"));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(100));
        eb.field("requestedTime", f -> f.physicalColumn("requested_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("reason", f -> f.physicalColumn("reason").immutable(true).required(true).asText(1000));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv.columns("requestedTime", "requestedBy", "reason")
            .sorts("requestedTime").defaultSort("requestedTime", false));
    });

    public static final EntityDefinition APPROVAL = EntityDefinition.define(APPROVAL_ENTITY, eb -> {
        eb.physicalTable("sec_mfa_reenrollment_approval");
        eb.primaryKey("requestId");
        eb.field("requestId", f -> f.physicalColumn("request_id").immutable(true).required(true)
            .asSemanticIdentity("urn:jabiz:entity:platform:mfa-reenrollment"));
        eb.field("approvedBy", f -> f.physicalColumn("approved_by").immutable(true).required(true).asText(100));
        eb.field("approvedTime", f -> f.physicalColumn("approved_time").immutable(true).required(true)
            .asTemporal(TemporalRole.SYSTEM_RECORDED));
        eb.field("processSeqId", f -> f.physicalColumn("process_seq_id").immutable(true).required(true)
            .asNumeric(19, 0));
        eb.listView("default", lv -> lv.columns("requestId", "approvedTime", "approvedBy")
            .sorts("approvedTime").defaultSort("approvedTime", false));
    });

    @Bean
    ProcessDefinition<RequestInput, RequestOutput, ProcessContext> mfaReenrollRequestProcess() {
        return REQUEST_PROCESS;
    }

    @Bean
    ProcessDefinition<ApproveInput, ApproveOutput, ProcessContext> mfaReenrollApproveProcess() {
        return APPROVE_PROCESS;
    }

    @Bean
    EntityDefinition mfaReenrollmentEntity() {
        return REQUEST_ENTITY;
    }

    @Bean
    EntityDefinition mfaReenrollmentApprovalEntity() {
        return APPROVAL;
    }

    @Bean
    DatasetDefinition mfaReenrollmentDataset(@Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(DATASET, ENTITY, poolRef);
    }

    @Bean
    DatasetDefinition mfaReenrollmentApprovalDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return dataset(APPROVAL_DATASET, APPROVAL_ENTITY, poolRef);
    }

    private static DatasetDefinition dataset(String id, String entity, String poolRef) {
        // Read-only: the two processes write these tables themselves, append-only.
        return DatasetDefinition.define(id, d -> d
            .targetEntityType(entity)
            .asDefault()
            .permissions(SecurityPermissions.MFA_RESET, SecurityPermissions.MFA_RESET)
            .policy(p -> p.readOnly(true).maxQueryBatchSize(1000))
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }
}
