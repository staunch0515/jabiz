package com.jabiz.finance.ar;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.finance.ar.InvoiceProcesses.list;
import static com.jabiz.finance.ar.InvoiceProcesses.uuid;

/**
 * Write-offs of uncollectible invoices and recoveries (FIN-AR-012; docs/finance/00-design.md section 8):
 * <ul>
 *   <li>{@code FIN_WRITE_OFF_REQUEST}: asks for (part of) an invoice's open amount to be written off on a day. A
 *       write-off always needs approval: the approval rules of {@code fin.ar.write-off} say by whom, and without a
 *       rule that stops it the request is refused rather than posted unapproved. The requester never approves
 *       (FIN-CT-001).</li>
 *   <li>{@code FIN_WRITE_OFF_APPROVAL_RESULT}: run on the platform's approval events; an approval of the content
 *       requested posts it, debiting the allowance and crediting receivables, and the invoice whose open amount is
 *       gone shows as written off. An approval that came too late (the invoice paid meanwhile, the period closed) is
 *       recorded as refused, with the reason.</li>
 *   <li>{@code FIN_WRITE_OFF_RECOVER}: a customer pays after all: what is recovered is put back on the invoice and in
 *       the allowance, and the receipt is recorded and applied as any other.</li>
 * </ul>
 * The approval case is the invoice: one write-off of it waits at a time, and its audit trail lists the approvals.
 */
public final class WriteOffProcesses {

    public static final String REQUEST = "FIN_WRITE_OFF_REQUEST";
    public static final String APPROVAL_RESULT = "FIN_WRITE_OFF_APPROVAL_RESULT";
    public static final String RECOVER = "FIN_WRITE_OFF_RECOVER";

    /** The approval subject of write-offs. */
    public static final String SUBJECT = "fin.ar.write-off";

    public static final String NOT_OPEN = "FIN_WRITE_OFF_NOT_OPEN";
    public static final String PENDING = "FIN_WRITE_OFF_PENDING";
    public static final String NO_RULE = "FIN_WRITE_OFF_NO_RULE";
    public static final String INVALID_VALUE = "FIN_WRITE_OFF_INVALID_VALUE";
    public static final String NOT_RECOVERABLE = "FIN_WRITE_OFF_NOT_RECOVERABLE";

    public record RequestInput(@NotNull UUID invoiceId, @NotNull LocalDate writeOffDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {}

    /** @param approval {@code PENDING}; the write-off posts once approved */
    public record WriteOffOutput(String writeOffId, String invoiceNo, String status, BigDecimal amount,
        String approval, String approvalRequestId, String glNo, String refusal) {}

    /** The platform's approval decision, as its events carry it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    public record RecoverInput(@NotNull UUID writeOffId, @NotNull LocalDate recoveryDate,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {}

    public record RecoverOutput(String writeOffId, String invoiceNo, BigDecimal recovered, BigDecimal invoiceOpen,
        String glNo) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String INVOICE_ID = "invoiceId";
    static final String INVOICE = "invoice";
    static final String WRITE_OFF_ID = "writeOffId";
    static final String WRITE_OFFS = "writeOffs";
    static final String WRITE_OFF = "writeOff";
    static final String SETTINGS = "settings";
    static final String PERIODS = "periods";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String INVOICES = "invoices";
    static final String SUB_INPUT = "subledgerInput";
    static final String SUB_OUTPUT = "subledgerOutput";

    // ---- request ---------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<RequestInput, WriteOffOutput, ProcessContext> REQUEST_PROCESS =
        ProcessDefinition.define(REQUEST, 1, RequestInput.class, WriteOffOutput.class, ProcessContext.class, pb -> pb
            .description("Asks for an invoice's open amount to be written off; it posts once approved.")
            .permissions(FinancePermissions.WRITE_OFF_REQUEST)
            .actsOn(InvoiceEntities.INVOICE, "invoiceId", a -> a.whenField("status", InvoiceEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(INVOICE_ID, input.invoiceId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, WriteOffOutput.class))
            .step("Load the invoice", LoadEntity.by(InvoiceEntities.INVOICE_DATASET, INVOICE_ID, INVOICE))
            .step("Load its write-offs", QueryEntities.of(ReceiptEntities.WRITE_OFF_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("invoiceId", ctx.get(INVOICE_ID))).limit(100)
                    .build(), WRITE_OFFS))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, RequestInput.class).writeOffDate()), PERIODS))
            .compute("Check the request", (metadata, ctx) -> checkRequest(ctx))
            .step("Ask for approval", RequireApproval.when(ctx -> ctx.contains(CASE), SUBJECT,
                ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
            .compute("Record the request", (metadata, ctx) -> recordRequest(ctx)));

    static void checkRequest(ProcessContext ctx) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        EntityInstance invoice = ctx.get(INVOICE, EntityInstance.class);
        if (!InvoiceEntities.INVOICE_KIND.equals(invoice.get("kind"))
            || !InvoiceEntities.POSTED.equals(invoice.get("status"))
            || input.amount().compareTo(invoice.get("openAmount")) > 0) {
            ctx.reject(new Violation("amount", NOT_OPEN, "Only what is open of a posted invoice is written off; "
                + invoice.get("invoiceNo") + " has " + invoice.get("openAmount") + " open",
                Map.of("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo")))));
            return;
        }
        if (!"USD".equals(invoice.get("currency"))) {
            // Its dollars would differ from the allowance's at today's rate: F7 settles that (FIN-FX-003).
            ctx.reject(new Violation("invoiceId", NOT_OPEN, "Write-offs of invoices in " + invoice.get("currency")
                + " come with foreign currency settlement (F7)",
                Map.of("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo")))));
            return;
        }
        if (list(ctx, WRITE_OFFS).stream().anyMatch(w -> ReceiptEntities.PENDING.equals(w.get("status")))) {
            ctx.reject(new Violation("invoiceId", PENDING, "A write-off of " + invoice.get("invoiceNo")
                + " waits for approval already", Map.of("invoiceNo", (Object) invoice.get("invoiceNo"))));
            return;
        }
        if (input.writeOffDate().isBefore(invoice.get("invoiceDate"))) {
            ctx.reject(new Violation("writeOffDate", INVALID_VALUE, "An invoice is written off on or after its date",
                Map.of("value", input.writeOffDate().toString())));
            return;
        }
        if (list(ctx, SETTINGS).isEmpty()) {
            ctx.reject(new Violation("invoiceId", InvoiceProcesses.NO_SETTINGS, "The receivables settings are not set",
                Map.of()));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AR", input.writeOffDate(), "writeOffDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("amount", input.amount());
        facts.put("customerCode", invoice.get("customerCode"));
        ctx.put(CASE, ApprovalCase.of(invoice.id(), facts, content(invoice, input, ctx.opTime())));
    }

    /** What an approval of a write-off is given for. */
    static Map<String, Object> content(EntityInstance invoice, RequestInput input, java.time.Instant requestedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("writeOff", true);
        content.put("invoiceNo", invoice.get("invoiceNo"));
        content.put("customerCode", invoice.get("customerCode"));
        content.put("writeOffDate", input.writeOffDate());
        content.put("amount", input.amount());
        content.put("reason", input.reason().trim());
        // Each request is its own: an approval of an earlier, identical one does not carry over.
        content.put("requestedAt", requestedAt);
        return content;
    }

    static void recordRequest(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        EntityInstance invoice = ctx.get(INVOICE, EntityInstance.class);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING) {
            // A write-off is never posted unapproved: a rule must name its approvers (FIN-AR-012).
            ctx.reject(new Violation("invoiceId", NO_RULE, "No approval rule of write-offs applies: a controller sets "
                + "one before anything is written off", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("invoiceId", invoice.id());
        values.put("invoiceNo", invoice.get("invoiceNo"));
        values.put("customerCode", invoice.get("customerCode"));
        values.put("writeOffDate", input.writeOffDate());
        values.put("amount", input.amount());
        values.put("reason", input.reason().trim());
        values.put("status", ReceiptEntities.PENDING);
        values.put("requestedBy", ctx.request().actorId());
        values.put("approvalRequestId", approval.requestId());
        values.put("contentHash", ContentHash.of(content(invoice, input, ctx.opTime())));
        Object id = ctx.changes().insert(ReceiptEntities.WRITE_OFF, values);
        // The invoice records the request it waits for: two requests at once conflict on its version, so one
        // invoice never has two write-offs waiting.
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(),
            Map.of("approvalRequestId", approval.requestId()));
        ctx.put(OUTPUT, new WriteOffOutput(String.valueOf(id), invoice.get("invoiceNo"), ReceiptEntities.PENDING,
            input.amount(), approval.status().name(), approval.requestId(), null, null));
    }

    // ---- the approvers' decision -----------------------------------------------------------------------------------

    public static final ProcessDefinition<ApprovalResultInput, WriteOffOutput, ProcessContext>
        APPROVAL_RESULT_PROCESS = ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class,
            WriteOffOutput.class, ProcessContext.class, pb -> pb
                .description("Posts a write-off its approvers approved, or marks it rejected.")
                .permissions(FinancePermissions.SUBLEDGER_POST)
                .internal()
                .contextFactory(InvoiceProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, WriteOffOutput.class))
                .step("Load the write-off", QueryEntities.of(ReceiptEntities.WRITE_OFF_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    // Approvals of other subjects are none of this process's business; the request names its own.
                    List<Object> requests = SUBJECT.equals(input.subject()) && input.requestId() != null
                        ? List.of(input.requestId()) : List.of();
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("approvalRequestId", new java.util.ArrayList<>(requests)),
                        new QueryPredicate.Eq("status", ReceiptEntities.PENDING)))).limit(1).build();
                }, WRITE_OFFS))
                .step("Load the invoice", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                    ctx -> InvoiceProcesses.byIds(writeOff(ctx) == null ? null
                        : uuid(writeOff(ctx).get("invoiceId"))), INVOICES))
                .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                    ctx -> ArSettingsProcesses.current(), SETTINGS))
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> writeOff(ctx) == null ? SubledgerPosting.periodsOn(LocalDate.of(1, 1, 1))
                        : SubledgerPosting.periodsOn(writeOff(ctx).get("writeOffDate")), PERIODS))
                .compute("Apply the decision", (metadata, ctx) -> applyDecision(ctx))
                .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                    ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
                .compute("Record the posting", (metadata, ctx) -> recordPosting(ctx)));

    static void applyDecision(ProcessContext ctx) {
        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
        EntityInstance writeOff = writeOff(ctx);
        if (writeOff == null) {
            ctx.put(OUTPUT, new WriteOffOutput(null, null, null, null, input.status(), input.requestId(), null,
                "not a write-off waiting for approval"));
            return;
        }
        if (!Objects.equals(input.requestId(), writeOff.get("approvalRequestId"))
            || !Objects.equals(input.contentHash(), writeOff.get("contentHash"))) {
            // A late decision of an earlier request: the write-off waits for its own.
            ctx.put(OUTPUT, output(writeOff, writeOff.get("status"), null, "the decision was for another request"));
            return;
        }
        if (!"APPROVED".equals(input.status())) {
            ctx.changes().update(ReceiptEntities.WRITE_OFF, writeOff.id(), writeOff.version(),
                Map.of("status", ReceiptEntities.REJECTED));
            ctx.put(OUTPUT, output(writeOff, ReceiptEntities.REJECTED, null, null));
            return;
        }
        EntityInstance invoice = list(ctx, INVOICES).isEmpty() ? null : list(ctx, INVOICES).getFirst();
        EntityInstance settings = list(ctx, SETTINGS).isEmpty() ? null : list(ctx, SETTINGS).getFirst();
        BigDecimal amount = writeOff.get("amount");
        String refusal = null;
        if (invoice == null || !InvoiceEntities.POSTED.equals(invoice.get("status"))
            || amount.compareTo(invoice.get("openAmount")) > 0) {
            refusal = "the invoice no longer has " + amount.toPlainString() + " open";
        } else if (settings == null) {
            refusal = "the receivables settings are not set";
        } else {
            var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "AR", writeOff.get("writeOffDate"),
                "writeOffDate");
            if (closed.isPresent()) {
                refusal = closed.get().message();
            }
        }
        if (refusal != null) {
            // Approved, but no longer possible as asked: recorded, not posted; a new request may follow.
            ctx.changes().update(ReceiptEntities.WRITE_OFF, writeOff.id(), writeOff.version(),
                Map.of("status", ReceiptEntities.REFUSED, "refusal", refusal));
            ctx.put(OUTPUT, output(writeOff, ReceiptEntities.REFUSED, null, refusal));
            return;
        }
        String number = invoice.get("invoiceNo");
        List<JournalProcesses.LineInput> lines = List.of(
            new JournalProcesses.LineInput(settings.get("allowanceAccount"), amount, null, "Write-off " + number,
                null, null),
            new JournalProcesses.LineInput(settings.get("receivableAccount"), null, amount, "Write-off " + number,
                null, null));
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", writeOff.get("writeOffDate"),
            ReceiptProcesses.memo("Write-off of " + number + ": " + writeOff.get("reason")), number,
            ReceiptEntities.WRITE_OFF, String.valueOf(writeOff.id()), lines, List.of("AR")));
    }

    static void recordPosting(ProcessContext ctx) {
        if (!ctx.contains(SUB_OUTPUT)) {
            return;
        }
        EntityInstance writeOff = writeOff(ctx);
        EntityInstance invoice = list(ctx, INVOICES).getFirst();
        SubledgerPosting.PostOutput booked = ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class);
        BigDecimal amount = writeOff.get("amount");
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("sourceKind", InvoiceEntities.WRITE_OFF_SOURCE);
        application.put("sourceId", String.valueOf(writeOff.id()));
        application.put("sourceNo", invoice.get("invoiceNo"));
        application.put("invoiceId", invoice.id());
        application.put("customerCode", invoice.get("customerCode"));
        application.put("applicationDate", writeOff.get("writeOffDate"));
        application.put("amount", amount);
        application.put("amountUsd", amount);
        application.put("reason", writeOff.get("reason"));
        Object applicationId = ctx.changes().insert(InvoiceEntities.APPLICATION, application);
        BigDecimal open = invoice.<BigDecimal>get("openAmount").subtract(amount);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("openAmount", open);
        changes.put("openAmountUsd", invoice.<BigDecimal>get("openAmountUsd").subtract(amount));
        if (open.signum() == 0) {
            changes.put("status", InvoiceEntities.WRITTEN_OFF);
        }
        ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), changes);
        Map<String, Object> posted = new LinkedHashMap<>();
        posted.put("status", ReceiptEntities.POSTED);
        posted.put("glNo", booked.glNo());
        posted.put("transactionId", UUID.fromString(booked.transactionId()));
        posted.put("applicationId", applicationId);
        posted.put("recoveredAmount", BigDecimal.ZERO.setScale(2));
        ctx.changes().update(ReceiptEntities.WRITE_OFF, writeOff.id(), writeOff.version(), posted);
        ctx.put(OUTPUT, output(writeOff, ReceiptEntities.POSTED, booked.glNo(), null));
    }

    // ---- recovery --------------------------------------------------------------------------------------------------

    public static final ProcessDefinition<RecoverInput, RecoverOutput, ProcessContext> RECOVER_PROCESS =
        ProcessDefinition.define(RECOVER, 1, RecoverInput.class, RecoverOutput.class, ProcessContext.class, pb -> pb
            .description("Puts what is recovered of a write-off back on the invoice, to be paid by a receipt.")
            .permissions(FinancePermissions.WRITE_OFF_REQUEST)
            .actsOn(ReceiptEntities.WRITE_OFF, "writeOffId", a -> a.whenField("status", ReceiptEntities.POSTED))
            .contextFactory((start, input) -> {
                ProcessContext ctx = InvoiceProcesses.withInput(start, input);
                ctx.put(WRITE_OFF_ID, input.writeOffId());
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, RecoverOutput.class))
            .step("Load the write-off", LoadEntity.by(ReceiptEntities.WRITE_OFF_DATASET, WRITE_OFF_ID, WRITE_OFF))
            .step("Load the invoice", QueryEntities.of(InvoiceEntities.INVOICE_DATASET,
                ctx -> InvoiceProcesses.byIds(uuid(ctx.get(WRITE_OFF, EntityInstance.class).get("invoiceId"))),
                INVOICES))
            .step("Load the settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), SETTINGS))
            .compute("Check it", (metadata, ctx) -> {
                RecoverInput input = ctx.get(INPUT, RecoverInput.class);
                EntityInstance writeOff = ctx.get(WRITE_OFF, EntityInstance.class);
                EntityInstance invoice = list(ctx, INVOICES).isEmpty() ? null : list(ctx, INVOICES).getFirst();
                BigDecimal recovered = writeOff.get("recoveredAmount") == null ? BigDecimal.ZERO
                    : writeOff.get("recoveredAmount");
                String reason = null;
                if (!ReceiptEntities.POSTED.equals(writeOff.get("status")) || invoice == null) {
                    reason = "it is " + writeOff.get("status");
                } else if (Objects.equals(ctx.request().actorId(), writeOff.get("requestedBy"))) {
                    // Reopening what one wrote off could hide the recovered money (FIN-CT-001).
                    reason = "who asked for the write-off does not record its recovery";
                } else if (input.amount().compareTo(writeOff.<BigDecimal>get("amount").subtract(recovered)) > 0) {
                    reason = writeOff.<BigDecimal>get("amount").subtract(recovered).toPlainString()
                        + " of it is left to recover";
                } else if (input.recoveryDate().isBefore(writeOff.get("writeOffDate"))) {
                    reason = "a recovery is dated on or after the write-off";
                } else if (list(ctx, SETTINGS).isEmpty()) {
                    reason = "the receivables settings are not set";
                }
                if (reason != null) {
                    ctx.reject(new Violation("amount", NOT_RECOVERABLE, "Nothing is recovered: " + reason,
                        Map.of("reason", reason)));
                    return;
                }
                EntityInstance settings = list(ctx, SETTINGS).getFirst();
                String number = invoice.get("invoiceNo");
                List<JournalProcesses.LineInput> lines = List.of(
                    new JournalProcesses.LineInput(settings.get("receivableAccount"), input.amount(), null,
                        "Recovery " + number, null, null),
                    new JournalProcesses.LineInput(settings.get("allowanceAccount"), null, input.amount(),
                        "Recovery " + number, null, null));
                ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("AR", input.recoveryDate(),
                    ReceiptProcesses.memo("Recovery of " + number + ": " + input.reason().trim()), number,
                    ReceiptEntities.WRITE_OFF, String.valueOf(writeOff.id()), lines, List.of("AR")));
            })
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Record the recovery", (metadata, ctx) -> {
                if (!ctx.contains(SUB_OUTPUT)) {
                    return;
                }
                RecoverInput input = ctx.get(INPUT, RecoverInput.class);
                EntityInstance writeOff = ctx.get(WRITE_OFF, EntityInstance.class);
                EntityInstance invoice = list(ctx, INVOICES).getFirst();
                Map<String, Object> application = new LinkedHashMap<>();
                application.put("sourceKind", InvoiceEntities.RECOVERY_SOURCE);
                application.put("sourceId", String.valueOf(writeOff.id()));
                application.put("sourceNo", invoice.get("invoiceNo"));
                application.put("invoiceId", invoice.id());
                application.put("customerCode", invoice.get("customerCode"));
                application.put("applicationDate", input.recoveryDate());
                application.put("amount", input.amount().negate());
                application.put("amountUsd", input.amount().negate());
                application.put("reversesApplicationId", writeOff.get("applicationId"));
                application.put("reason", input.reason().trim());
                ctx.changes().insert(InvoiceEntities.APPLICATION, application);
                BigDecimal open = invoice.<BigDecimal>get("openAmount").add(input.amount());
                ctx.changes().update(InvoiceEntities.INVOICE, invoice.id(), invoice.version(), Map.of(
                    "openAmount", open, "openAmountUsd", invoice.<BigDecimal>get("openAmountUsd").add(input.amount()),
                    "status", InvoiceEntities.POSTED));
                BigDecimal recovered = (writeOff.get("recoveredAmount") == null ? BigDecimal.ZERO
                    : writeOff.<BigDecimal>get("recoveredAmount")).add(input.amount());
                ctx.changes().update(ReceiptEntities.WRITE_OFF, writeOff.id(), writeOff.version(),
                    Map.of("recoveredAmount", recovered));
                ctx.put(OUTPUT, new RecoverOutput(String.valueOf(writeOff.id()), invoice.get("invoiceNo"), recovered,
                    open, ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
            }));

    private static EntityInstance writeOff(ProcessContext ctx) {
        List<EntityInstance> found = list(ctx, WRITE_OFFS);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static WriteOffOutput output(EntityInstance writeOff, String status, String glNo, String refusal) {
        return new WriteOffOutput(String.valueOf(writeOff.id()), writeOff.get("invoiceNo"), status,
            writeOff.get("amount"), null, writeOff.get("approvalRequestId"), glNo, refusal);
    }

    private WriteOffProcesses() {}
}
