package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.event.EventSubscription;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.ApprovalProcesses;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Supplier certifications, the sample of content authoring (docs/design/16-content-authoring.md section 9): a
 * multilingual title and Markdown body edited in the generic admin pages, a reference to the supplier picked by its
 * name, and a status and review comment that only the processes below change. The processes are offered as actions
 * on the certification rows; the lifecycle decides whether a run is allowed.
 * <ul>
 *   <li>{@code CERTIFICATION_SUBMIT}: a draft (or a rejected certification) goes to review; when an approval rule of
 *       the subject {@value #APPROVAL_SUBJECT} applies, an approval request is made as well
 *       (docs/design/18-numbering-approvals-tasks.md section 3.7);</li>
 *   <li>{@code CERTIFICATION_APPROVE}: a submitted certification is approved, with an optional comment, unless it
 *       awaits an approval ({@code CERTIFICATION_AWAITING_APPROVAL});</li>
 *   <li>{@code CERTIFICATION_REJECT}: a submitted certification is rejected, with a comment;</li>
 *   <li>{@code CERTIFICATION_APPROVAL_RESULT}: run for the approval events: an approved request whose content is
 *       still the certification's approves it, a rejected one rejects it with the approver's reason.</li>
 * </ul>
 */
@Configuration
public class SupplierCertifications {

    public static final String CERTIFICATION = "SupplierCertification";
    public static final String DATASET = "urn:jabiz:dataset:default:SupplierCertification";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:commerce:certification-status";

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public static final String SUBMIT = "CERTIFICATION_SUBMIT";
    public static final String APPROVE = "CERTIFICATION_APPROVE";
    public static final String REJECT = "CERTIFICATION_REJECT";
    public static final String APPROVAL_RESULT = "CERTIFICATION_APPROVAL_RESULT";

    /** The approval subject of certifications: facts the rules may test. */
    public static final String APPROVAL_SUBJECT = "commerce.certification";
    public static final String AWAITING_APPROVAL = "CERTIFICATION_AWAITING_APPROVAL";

    public static final EntityDefinition CERTIFICATION_ENTITY = EntityDefinition.define(CERTIFICATION, eb -> {
        eb.physicalTable("supplier_certification_version");
        eb.primaryKey("certificationId");
        eb.field("certificationId", f -> f.physicalColumn("certification_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:commerce:supplier-certification"));
        eb.field("supplierId", f -> f.physicalColumn("supplier_id").immutable(true).required(true)
            .asReference(SupplierDefinitions.SUPPLIER));
        eb.field("title", f -> f.physicalColumn("title").required(true).apply(I18nText.of(200).required("en")));
        eb.field("body", f -> f.physicalColumn("body").apply(I18nText.markdown(20_000)));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(STATUS_DICTIONARY, DRAFT, SUBMITTED, APPROVED, REJECTED));
        eb.field("reviewComment", f -> f.physicalColumn("review_comment").processOnly().asText(500, true));
        eb.stateTransitions("status", st -> st
            .from(DRAFT).to(SUBMITTED)
            .from(SUBMITTED).to(APPROVED, REJECTED)
            .from(REJECTED).to(SUBMITTED));
        eb.display("title");
        eb.listView("default", lv -> lv
            .columns("title", "supplierId", "status", "reviewComment")
            .filters("supplierId", "status")
            .sorts("status"));
        eb.temporal(t -> t.allowScheduled(false));
    });

    /** Input of the actions that need nothing but the certification. */
    public record CertificationInput(@NotNull UUID certificationId) {}

    public record ReviewInput(@NotNull UUID certificationId, @Size(max = 500) String comment) {}

    public record RejectInput(@NotNull UUID certificationId, @NotBlank @Size(max = 500) String comment) {}

    /**
     * @param approval          what {@link RequireApproval} found; null for processes that do not ask
     * @param approvalRequestId the request that is pending or approved, if any
     */
    public record CertificationOutput(String certificationId, String status, String approval,
        String approvalRequestId) {}

    /** Input of {@code CERTIFICATION_APPROVAL_RESULT}, from an approval event of any subject. */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String preparerId, String contentHash, String reason) {}

    private static final String ID = "certificationId";
    private static final String LOADED = "certification";
    private static final String OUTPUT = "output";
    private static final String APPROVAL = "approval";
    private static final String FOUND = "found";

    public static final ProcessDefinition<CertificationInput, CertificationOutput, ProcessContext> SUBMIT_PROCESS =
        ProcessDefinition.define(SUBMIT, 1, CertificationInput.class, CertificationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sends a supplier certification to review.")
                .permissions("commerce.certification.submit")
                .actsOn(CERTIFICATION, ID, a -> a.whenField("status", DRAFT, REJECTED))
                .contextFactory((start, input) -> start(start, input.certificationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, CertificationOutput.class))
                .step("Load the certification", LoadEntity.by(DATASET, ID, LOADED))
                .step("Ask whether it needs approval", RequireApproval.of(APPROVAL_SUBJECT,
                    ctx -> approvalCase(ctx.get(LOADED, EntityInstance.class)), APPROVAL))
                .compute("Submit", (metadata, ctx) -> move(ctx, SUBMITTED, null)));

    public static final ProcessDefinition<ReviewInput, CertificationOutput, ProcessContext> APPROVE_PROCESS =
        ProcessDefinition.define(APPROVE, 1, ReviewInput.class, CertificationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Approves a submitted supplier certification.")
                .permissions("commerce.certification.review")
                .actsOn(CERTIFICATION, ID, a -> a.whenField("status", SUBMITTED))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = start(start, input.certificationId());
                    ctx.put("comment", input.comment());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, CertificationOutput.class))
                .step("Load the certification", LoadEntity.by(DATASET, ID, LOADED))
                .step("Check that it does not await approval", RequireApproval.of(APPROVAL_SUBJECT,
                    ctx -> approvalCase(ctx.get(LOADED, EntityInstance.class)), APPROVAL))
                .compute("Approve", (metadata, ctx) -> {
                    ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
                    if (!approval.mayProceed()) {
                        ctx.reject(new Violation("certificationId", AWAITING_APPROVAL,
                            "The certification awaits approval request " + approval.requestId(),
                            Map.of("request", approval.requestId())));
                        return;
                    }
                    move(ctx, APPROVED, ctx.get("comment", String.class));
                }));

    /**
     * Continues after an approval request of a certification is decided (subscribed to both approval events). A
     * decision on other content than the certification's current one changes nothing: a new request (made by
     * {@link RequireApproval} here, for the same preparer) awaits approval instead.
     */
    public static final ProcessDefinition<ApprovalResultInput, CertificationOutput, ProcessContext>
        APPROVAL_RESULT_PROCESS = ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class,
            CertificationOutput.class, ProcessContext.class, pb -> pb
                .description("Moves a certification on after its approval request is decided.")
                .permissions("commerce.certification.review")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, CertificationOutput.class)
                    : new CertificationOutput(ctx.get("input", ApprovalResultInput.class).entityId(), null, null,
                        null))
                // Events of other subjects find nothing (their ids need not even be UUIDs).
                .step("Find the certification", QueryEntities.of(DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get("input", ApprovalResultInput.class);
                    List<Object> ids = APPROVAL_SUBJECT.equals(input.subject()) ? List.of(input.entityId())
                        : List.of();
                    return EntityQuery.builder().where(new QueryPredicate.In("certificationId", ids)).limit(1)
                        .build();
                }, FOUND))
                .step("Check the approval", RequireApproval.when(
                    ctx -> found(ctx) != null && ApprovalEntities.APPROVED.equals(
                        ctx.get("input", ApprovalResultInput.class).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(found(ctx))
                        .preparedBy(ctx.get("input", ApprovalResultInput.class).preparerId()), APPROVAL))
                .compute("Move on", (metadata, ctx) -> approvalResult(ctx)));

    public static final ProcessDefinition<RejectInput, CertificationOutput, ProcessContext> REJECT_PROCESS =
        ProcessDefinition.define(REJECT, 1, RejectInput.class, CertificationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Rejects a submitted supplier certification with a comment.")
                .permissions("commerce.certification.review")
                .actsOn(CERTIFICATION, ID, a -> a.whenField("status", SUBMITTED))
                .contextFactory((start, input) -> {
                    ProcessContext ctx = start(start, input.certificationId());
                    ctx.put("comment", input.comment());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, CertificationOutput.class))
                .step("Load the certification", LoadEntity.by(DATASET, ID, LOADED))
                .compute("Reject", (metadata, ctx) -> move(ctx, REJECTED, ctx.get("comment", String.class))));

    /**
     * What an approval of a certification looks at: its supplier, how many languages its title has and whether it
     * has a body; bound to the supplier, title and body.
     */
    static ApprovalCase approvalCase(EntityInstance certification) {
        Map<String, Object> title = certification.get("title");
        Map<String, Object> body = certification.get("body");
        Map<String, Object> facts = new HashMap<>();
        facts.put("supplierId", String.valueOf(certification.<Object>get("supplierId")));
        facts.put("languages", title == null ? 0 : title.size());
        facts.put("hasBody", body != null && !body.isEmpty());
        Map<String, Object> content = new HashMap<>();
        content.put("supplierId", certification.get("supplierId"));
        content.put("title", title);
        content.put("body", body);
        return ApprovalCase.of(certification.id(), facts, content);
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance found(ProcessContext ctx) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(FOUND);
        return found == null || found.isEmpty() ? null : found.getFirst();
    }

    private static void approvalResult(ProcessContext ctx) {
        EntityInstance certification = found(ctx);
        ApprovalResultInput input = ctx.get("input", ApprovalResultInput.class);
        if (certification == null || !SUBMITTED.equals(certification.get("status"))) {
            return;
        }
        ctx.put(LOADED, certification);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            if (ctx.get(APPROVAL, ApprovalOutcome.class).status() == ApprovalOutcome.Status.APPROVED) {
                move(ctx, APPROVED, null);
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status())
            && ContentHash.of(approvalCase(certification).content()).equals(input.contentHash())) {
            move(ctx, REJECTED, input.reason());
        }
    }

    private static ProcessContext start(com.jabiz.process.ProcessStart start, UUID certificationId) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(ID, certificationId);
        return ctx;
    }

    /**
     * Moves the certification to {@code status}; the lifecycle rejects moves that are not allowed (422), whatever the
     * client offered.
     */
    private static void move(ProcessContext ctx, String status, String comment) {
        EntityInstance certification = ctx.get(LOADED, EntityInstance.class);
        Map<String, Object> changes = new HashMap<>();
        changes.put("status", status);
        changes.put("reviewComment", comment == null || comment.isBlank() ? null : comment.strip());
        ctx.changes().update(CERTIFICATION, certification.id(), certification.version(), changes);
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        ctx.put(OUTPUT, new CertificationOutput(String.valueOf(certification.id()), status,
            approval == null ? null : approval.status().name(), approval == null ? null : approval.requestId()));
    }

    @Bean
    EntityDefinition supplierCertificationEntityDefinition() {
        return CERTIFICATION_ENTITY;
    }

    @Bean
    DatasetDefinition supplierCertificationDataset(
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        return DatasetDefinition.define(DATASET, d -> d
            .targetEntityType(CERTIFICATION)
            .asDefault()
            .permissions("commerce.certification.read", "commerce.certification.write")
            .storage(s -> s.driver("r2dbc-postgresql").connectionPoolRef(poolRef)));
    }

    @Bean
    StaticDictionary certificationStatusDictionary() {
        return StaticDictionary.define(STATUS_DICTIONARY, d -> d
            .item(DRAFT, "zh", "草稿", "ja", "下書き", "en", "Draft")
            .item(SUBMITTED, "zh", "审核中", "ja", "審査中", "en", "In review")
            .item(APPROVED, "zh", "已通过", "ja", "承認済み", "en", "Approved")
            .item(REJECTED, "zh", "已驳回", "ja", "差戻し", "en", "Rejected"));
    }

    @Bean
    ProcessDefinition<CertificationInput, CertificationOutput, ProcessContext> certificationSubmitProcess() {
        return SUBMIT_PROCESS;
    }

    @Bean
    ProcessDefinition<ReviewInput, CertificationOutput, ProcessContext> certificationApproveProcess() {
        return APPROVE_PROCESS;
    }

    @Bean
    ProcessDefinition<RejectInput, CertificationOutput, ProcessContext> certificationRejectProcess() {
        return REJECT_PROCESS;
    }

    @Bean
    ProcessDefinition<ApprovalResultInput, CertificationOutput, ProcessContext> certificationApprovalResultProcess() {
        return APPROVAL_RESULT_PROCESS;
    }

    @Bean
    ApprovalSubject certificationApprovalSubject() {
        return ApprovalSubject.define(APPROVAL_SUBJECT, s -> s.text("supplierId").number("languages")
            .bool("hasBody"));
    }

    @Bean
    EventSubscription<ApprovalResultInput> certificationApprovedSubscription() {
        return EventSubscription.of("commerce.certification-approved", ApprovalProcesses.APPROVED_EVENT,
            APPROVAL_RESULT_PROCESS, SupplierCertifications::resultInput);
    }

    @Bean
    EventSubscription<ApprovalResultInput> certificationRejectedSubscription() {
        return EventSubscription.of("commerce.certification-rejected", ApprovalProcesses.REJECTED_EVENT,
            APPROVAL_RESULT_PROCESS, SupplierCertifications::resultInput);
    }

    private static ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"), text(payload, "status"),
            text(payload, "preparerId"), text(payload, "contentHash"), text(payload, "reason"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
