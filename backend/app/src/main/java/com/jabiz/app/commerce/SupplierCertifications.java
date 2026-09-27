package com.jabiz.app.commerce;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dictionary.StaticDictionary;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Supplier certifications, the sample of content authoring (docs/design/16-content-authoring.md section 9): a
 * multilingual title and Markdown body edited in the generic admin pages, a reference to the supplier picked by its
 * name, and a status and review comment that only the processes below change. The processes are offered as actions
 * on the certification rows; the lifecycle decides whether a run is allowed.
 * <ul>
 *   <li>{@code CERTIFICATION_SUBMIT}: a draft (or a rejected certification) goes to review;</li>
 *   <li>{@code CERTIFICATION_APPROVE}: a submitted certification is approved, with an optional comment;</li>
 *   <li>{@code CERTIFICATION_REJECT}: a submitted certification is rejected, with a comment.</li>
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

    public record CertificationOutput(String certificationId, String status) {}

    private static final String ID = "certificationId";
    private static final String LOADED = "certification";
    private static final String OUTPUT = "output";

    public static final ProcessDefinition<CertificationInput, CertificationOutput, ProcessContext> SUBMIT_PROCESS =
        ProcessDefinition.define(SUBMIT, 1, CertificationInput.class, CertificationOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sends a supplier certification to review.")
                .permissions("commerce.certification.submit")
                .actsOn(CERTIFICATION, ID, a -> a.whenField("status", DRAFT, REJECTED))
                .contextFactory((start, input) -> start(start, input.certificationId()))
                .outputMapper(ctx -> ctx.get(OUTPUT, CertificationOutput.class))
                .step("Load the certification", LoadEntity.by(DATASET, ID, LOADED))
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
                .compute("Approve", (metadata, ctx) -> move(ctx, APPROVED, ctx.get("comment", String.class))));

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
        ctx.put(OUTPUT, new CertificationOutput(String.valueOf(certification.id()), status));
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
}
