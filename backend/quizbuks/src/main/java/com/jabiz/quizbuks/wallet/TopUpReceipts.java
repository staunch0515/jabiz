package com.jabiz.quizbuks.wallet;

import com.jabiz.document.DocumentLayout;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.document.DocumentProcesses;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The receipt of a paid top-up (docs/quizbuks/plans/Q9d-journal-receipts.md, F-09, D-Q9d-7): a platform document
 * ({@value #LAYOUT_ID}) the sponsor issues from the wallet page and downloads, kept exactly as issued
 * ({@code DOCUMENT_ISSUE}); asking again gives the receipt already issued. It shows the operator as the issuer (the
 * controlled parameters {@code qb.issuer.*}: data, kept with each receipt), the sponsor, the payment and its amount,
 * in the sponsor's language, with what the customer paid in their own currency when Stripe converted. A top-up refunded
 * or disputed gets no first receipt; refunds after the receipt do not change it: the transactions page shows them.
 */
public final class TopUpReceipts {

    public static final String LAYOUT_ID = "qb.topup_receipt";
    public static final String RECEIPT_TEMPLATE = "qb.sponsor.topup-receipt";
    public static final String ISSUER_TEMPLATE = "qb.sponsor.receipt-issuer";
    public static final String ISSUE = "QB_TOPUP_RECEIPT_ISSUE";

    public static final String RECEIPT = "QbTopUpReceipt";

    public static final DocumentLayout LAYOUT = DocumentLayout.define(LAYOUT_ID, d -> d
        .permissions(QbPermissions.TOPUP)
        .subject(QbTopUps.TOPUP, "topUpId")
        .number(RECEIPT_TEMPLATE, "receiptNo")
        .party("issuer", ISSUER_TEMPLATE, "name", "address", "registrationNo")
        .party("sponsor", RECEIPT_TEMPLATE, "company", "contactName")
        .facts(RECEIPT_TEMPLATE, "paidAt", "paymentReference", "paidIn", "kudos")
        .totals(RECEIPT_TEMPLATE, "amount")
        .note("note"));

    public static final EntityDefinition RECEIPT_ENTITY = EntityDefinition.define(RECEIPT, eb -> {
        eb.physicalTable("qb_topup_receipt_version");
        eb.primaryKey("receiptId");
        eb.field("receiptId", f -> f.physicalColumn("receipt_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:topup-receipt"));
        eb.field("topUpId", f -> f.physicalColumn("topup_id").immutable(true).required(true).asText(36));
        eb.field("sponsorId", f -> f.physicalColumn("sponsor_id").immutable(true).required(true).asText(128));
        eb.field("runId", f -> f.physicalColumn("run_id").immutable(true).required(true).asText(36));
        eb.field("documentNo", f -> f.physicalColumn("document_no").immutable(true).required(true).asText(100));
        eb.field("language", f -> f.physicalColumn("language").immutable(true).required(true).asText(10));
        eb.unique("uk_qb_topup_receipt_topup", "topUpId");
        eb.temporal(t -> t.allowScheduled(false).writeOnce());
        eb.listView("default", lv -> lv
            .columns("topUpId", "sponsorId", "documentNo", "language")
            .filters("topUpId", "sponsorId")
            .sorts("documentNo")
            .defaultSort("documentNo", true));
    });

    /**
     * @param language the receipt's language, the sponsor's ({@code en}, {@code zh}, {@code ja}; D-5). Used only the
     *                 first time: asking again gives the receipt as issued.
     */
    public record IssueInput(@NotBlank @Pattern(regexp = UUID) String topUpId,
        @NotBlank @Pattern(regexp = "en|zh|ja") String language) {}

    /** @param issued whether this call issued it (false: it was issued before) */
    public record IssueOutput(String runId, String documentNo, boolean issued) {}

    /** A top-up's key (review L6): anything else is refused before it reaches a lock name or a query. */
    static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    static final String INPUT = "input";
    static final String TOPUP_ID = "topUpId";
    static final String TOPUP = "topUp";
    static final String EXISTING = "existing";
    static final String ISSUER = "issuer";
    static final String ISSUED = "issued";

    public static ProcessDefinition<IssueInput, IssueOutput, ProcessContext> issue() {
        return ProcessDefinition.define(ISSUE, 1, IssueInput.class, IssueOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the receipt of one's own paid top-up, or gives the one already issued.")
            .permissions(QbPermissions.TOPUP)
            .requiresVerifiedEmail()
            .actsOn(QbTopUps.TOPUP, TOPUP_ID)
            .steps(CallerChecks::refuseBanned)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                ctx.put(TOPUP_ID, input.topUpId());
                return ctx;
            })
            .outputMapper(TopUpReceipts::output)
            // Two requests at once issue one receipt: the second waits and finds it (the unique index besides).
            .step("Take the receipt's lock", HoldLock.<ProcessContext>exclusive(ctx -> "qb.receipt:"
                + ctx.get(TOPUP_ID)))
            // Through the sponsor's own dataset: another sponsor's top-up is not found (404).
            .step("Load the top-up", LoadEntity.by(QbTopUps.SPONSOR_TOPUPS, TOPUP_ID, TOPUP))
            .step("Load its receipt", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(RECEIPT),
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("topUpId", ctx.get(TOPUP_ID))).limit(1)
                    .build(), EXISTING))
            .step("Load the issuer", QueryEntities.<ProcessContext>of(ParamEntities.DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In(ParamEntities.KEY,
                    new ArrayList<>(QbParams.ISSUER))).limit(QbParams.ISSUER.size()).build(), ISSUER))
            .compute("Check the top-up", (metadata, ctx) -> check(ctx))
            // At the issue time (the top-up's own data does not change with time; the issuer is the one now). The
            // receipt shows when the payment arrived.
            .step("Issue the receipt", CallProcess.<ProcessContext>when(ctx -> existing(ctx) == null,
                DocumentProcesses.ISSUE, 1, ctx -> new DocumentProcesses.IssueInput(LAYOUT_ID,
                    Map.of(TOPUP_ID, ctx.get(TOPUP_ID)), null, null, ctx.get(INPUT, IssueInput.class).language()),
                ISSUED))
            .compute("Record it", (metadata, ctx) -> record(ctx)));
    }

    static void check(ProcessContext ctx) {
        if (existing(ctx) != null) {
            return;
        }
        List<Violation> problems = new ArrayList<>();
        EntityInstance topUp = ctx.get(TOPUP, EntityInstance.class);
        if (!TopUpStatus.PAID.equals(topUp.get("status"))) {
            problems.add(new Violation(null, WalletCodes.RECEIPT_NOT_PAID, "A receipt is only for a paid top-up",
                Map.of()));
        } else if (refundedOrDisputed(topUp)) {
            problems.add(new Violation(null, WalletCodes.RECEIPT_REFUNDED, "The top-up has been refunded or disputed",
                Map.of()));
        }
        boolean named = TopUpCrediting.list(ctx, ISSUER).stream()
            .anyMatch(param -> QbParams.ISSUER_NAME.equals(param.get(ParamEntities.KEY))
                && param.get(ParamEntities.VALUE) instanceof String name && !name.isBlank());
        if (!named) {
            problems.add(new Violation(null, WalletCodes.RECEIPT_NO_ISSUER, "The receipts' issuer is not set yet",
                Map.of()));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    /** Refunded in whole or in part, or with a dispute open or lost (what a won or closed one held is back). */
    static boolean refundedOrDisputed(EntityInstance topUp) {
        Object refunded = topUp.get("refundedAmount");
        Object clawback = topUp.get("disputeClawback");
        String dispute = topUp.get("disputeStatus");
        return positive(refunded) || positive(clawback) || TopUpStatus.DISPUTE_OPEN.equals(dispute)
            || TopUpStatus.DISPUTE_LOST.equals(dispute);
    }

    private static boolean positive(Object amount) {
        return amount != null && new BigDecimal(amount.toString()).signum() > 0;
    }

    static void record(ProcessContext ctx) {
        if (existing(ctx) != null) {
            return;
        }
        DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
        IssueInput input = ctx.get(INPUT, IssueInput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topUpId", input.topUpId());
        values.put("sponsorId", ctx.request().actorId());
        values.put("runId", issued.runId());
        values.put("documentNo", issued.documentNo());
        values.put("language", input.language());
        ctx.changes().insert(RECEIPT, values);
    }

    static IssueOutput output(ProcessContext ctx) {
        EntityInstance existing = existing(ctx);
        if (existing != null) {
            return new IssueOutput(existing.get("runId"), existing.get("documentNo"), false);
        }
        DocumentProcesses.IssueOutput issued = ctx.get(ISSUED, DocumentProcesses.IssueOutput.class);
        return new IssueOutput(issued.runId(), issued.documentNo(), true);
    }

    private static EntityInstance existing(ProcessContext ctx) {
        return TopUpCrediting.first(ctx, EXISTING);
    }

    private TopUpReceipts() {}
}
