package com.jabiz.quizbuks.fee;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CloseTasks;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.fee.FeeProcesses.first;
import static com.jabiz.quizbuks.fee.FeeProcesses.list;
import static com.jabiz.quizbuks.fee.FeeProcesses.money;

/**
 * What headquarters asks a second administrator to approve about the platform's fees (Q87, D-Q87-4, D-Q87-6):
 * <ul>
 *   <li>{@value #RECORD} (finance): a fee charged by hand ({@code MANUAL}: the sponsor, the amount, why) or an
 *       invoice voided ({@code VOID}: which, why). The request is written and another administrator's approval asked
 *       (the recorder is the preparer, so they cannot approve their own); nothing is charged or voided before it.</li>
 *   <li>{@value #APPROVAL_RESULT} ({@code internal()}, on the approval's events): an approval of the request's own
 *       case charges the fee ({@code QB_FEE_CHARGE}, under the sponsor's wallet lock, in the current month) or voids
 *       the invoice (its charges' postings reversed, a payment given back to the cash account); a rejection, or an
 *       approval by the sponsor themselves, rejects the request.</li>
 * </ul>
 */
public final class FeeRequests {

    public static final String RECORD = "QB_FEE_REQUEST";
    public static final String APPROVAL_RESULT = "QB_FEE_REQUEST_APPROVAL_RESULT";
    public static final String TRANSACTIONS_TEMPLATE = "qb.system.fee-invoice-transactions";

    /**
     * @param kind      {@code MANUAL} (a fee charged by hand) or {@code VOID} (an invoice voided)
     * @param sponsorId a manual fee: the sponsor charged
     * @param amount    a manual fee: the fee before tax, in yen
     * @param invoiceId a void: the invoice
     * @param reason    why, as the approver reads it
     */
    public record RequestInput(@NotBlank @Pattern(regexp = "MANUAL|VOID") String kind,
        @Size(max = 128) String sponsorId,
        @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @Size(max = 36) String invoiceId, @NotBlank @Size(max = QbFees.REASON_LENGTH) String reason) {}

    /** @param approvalRequestId the request another administrator decides */
    public record RequestOutput(String requestId, String kind, String status, String approvalRequestId,
        String chargeId) {}

    static final String INPUT = "input";
    static final String SPONSOR = "sponsor";
    static final String INVOICE = "invoice";
    static final String RULES = "rules";
    static final String NEW_ID = "newId";
    static final String ROW = "row";
    static final String FOUND = "found";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String CHARGED = "charged";
    static final String TRANSACTIONS = "transactions";
    static final String REVERSALS = "reversals";
    static final String CASH_WALLET = "cashWallet";
    static final String STATUS_AFTER = "statusAfter";
    static final String DO_VOID = "doVoid";
    static final String DO_CHARGE = "doCharge";
    /** How many decisions of one request are read (one person at most decides each level). */
    static final int MAX_DECISIONS = 20;

    private FeeRequests() {}

    // ---- finance ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, RequestOutput, ProcessContext> record() {
        return ProcessDefinition.define(RECORD, 1, RequestInput.class, RequestOutput.class, ProcessContext.class, pb -> pb
            .description("Asks another administrator to approve a platform fee charged by hand, or an invoice voided; "
                + "nothing is charged or voided before it.")
            .permissions(QbPermissions.FEE_MANAGE)
            .requiresVerifiedEmail()
            .contextFactory(FeeProcesses::context)
            .outputMapper(FeeRequests::output)
            .step("Load the sponsor, the invoice and the review rule", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("userId", FeeGate.ids(sponsorOf(ctx))),
                        new QueryPredicate.Eq("reviewStatus", SponsorStatus.APPROVED)))).limit(1).build(), SPONSOR),
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> FeeProcesses.byId("invoiceId", input(ctx).invoiceId()), INVOICE),
                QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("subject", QbFees.APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES)))
            .compute("Check the request", (metadata, ctx) -> check(ctx))
            .compute("Record the request", (metadata, ctx) -> insert(ctx))
            // Written first, so that the approval names it and binds what is stored (read back, as its result reads it).
            .step("Save the request", SaveChanges.now())
            .step("Read the request", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.REQUEST),
                ctx -> FeeProcesses.byId("requestId", (String) ctx.get(NEW_ID)), ROW))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(
                QbFees.APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, ROW)), APPROVAL))
            .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    /** The sponsor a request is about: given for a manual fee, the invoice's for a void (read after the load). */
    static String sponsorOf(ProcessContext ctx) {
        return input(ctx).sponsorId();
    }

    static void check(ProcessContext ctx) {
        RequestInput input = input(ctx);
        List<Violation> problems = new ArrayList<>();
        boolean manual = QbFees.REQUEST_MANUAL.equals(input.kind());
        EntityInstance invoice = first(ctx, INVOICE);
        if (manual) {
            if (input.amount() == null) {
                problems.add(new Violation("amount", FeeCodes.FEE_PARAMS, "A manual fee has an amount", Map.of()));
            }
            if (first(ctx, SPONSOR) == null) {
                problems.add(new Violation("sponsorId", FeeCodes.FEE_SPONSOR, "Only an approved sponsor is charged",
                    Map.of()));
            }
        } else {
            if (invoice == null) {
                problems.add(new Violation("invoiceId", FeeCodes.FEE_INVOICE_STATE, "No such invoice", Map.of()));
            } else if (QbFees.VOID.equals(invoice.get("status"))) {
                problems.add(new Violation("invoiceId", FeeCodes.FEE_INVOICE_STATE, "The invoice is voided already",
                    Map.of("status", QbFees.VOID)));
            }
        }
        String sponsor = manual ? input.sponsorId() : invoice == null ? null : invoice.get("sponsorId");
        if (sponsor != null && sponsor.equals(ctx.request().actorId())) {
            problems.add(new Violation(null, FeeCodes.FEE_OWN, "One's own fees are handled by another administrator",
                Map.of()));
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static Violation noReview() {
        return new Violation(null, FeeCodes.FEE_NO_REVIEW, "No approval rule asks for a second person on fee requests",
            Map.of());
    }

    static void insert(ProcessContext ctx) {
        RequestInput input = input(ctx);
        boolean manual = QbFees.REQUEST_MANUAL.equals(input.kind());
        EntityInstance invoice = first(ctx, INVOICE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", input.kind());
        values.put("sponsorId", manual ? input.sponsorId() : invoice.get("sponsorId"));
        values.put("amount", manual ? input.amount() : null);
        values.put("invoiceId", manual ? null : String.valueOf(invoice.id()));
        values.put("reason", input.reason().strip());
        values.put("status", QbFees.REVIEW);
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbFees.REQUEST, values)));
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // The rule's condition was changed so that it no longer applies: never done by one person.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance row = first(ctx, ROW);
        ctx.put(STATUS_AFTER, QbFees.REVIEW);
        ctx.changes().update(QbFees.REQUEST, row.id(), row.version(), Map.of("approvalRequestId", approval.requestId()));
    }

    /** The case: the rule's facts, and the content approved, from the stored request; the recorder prepared it. */
    static ApprovalCase approvalCase(EntityInstance row) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("feeRequest", true);
        facts.put("kind", row.get("kind"));
        facts.put("sponsorId", row.get("sponsorId"));
        facts.put("amount", row.get("amount") == null ? BigDecimal.ZERO : money(row.get("amount")).setScale(0));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestId", String.valueOf(row.id()));
        content.put("kind", row.get("kind"));
        content.put("sponsorId", row.get("sponsorId"));
        content.put("amount", row.get("amount") == null ? null : money(row.get("amount")).setScale(0));
        content.put("invoiceId", row.get("invoiceId"));
        content.put("reason", row.get("reason"));
        return ApprovalCase.of(String.valueOf(row.id()), facts, content).preparedBy(row.get("requestedBy"))
            .reference((String) row.get("kind") + " " + (row.get("invoiceId") != null ? row.get("invoiceId")
                : row.get("sponsorId")));
    }

    // ---- the approval -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, RequestOutput, ProcessContext>
        approvalResult(ZoneId zone) {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            RequestOutput.class, ProcessContext.class, pb -> pb
                .description("Charges the fee or voids the invoice once another administrator approved the request, "
                    + "or rejects it when they rejected it.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(FeeRequests::output)
                .step("Find the request", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.REQUEST),
                    ctx -> FeeProcesses.byId("requestId", QbFees.APPROVAL_SUBJECT.equals(approvalInput(ctx).subject())
                        ? approvalInput(ctx).entityId() : null), FOUND))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbWallets.lockName(QbWallets.SPONSOR, first(ctx, FOUND).get("sponsorId"))))
                .step("Read the request, the invoice, the cash and the approval", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> FeeProcesses.byId("requestId",
                        first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())), ROW),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> FeeProcesses.byId("invoiceId",
                        first(ctx, FOUND) == null ? null : (String) first(ctx, FOUND).get("invoiceId")), INVOICE),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.CASH,
                        first(ctx, FOUND) == null ? null : first(ctx, FOUND).get("sponsorId")), CASH_WALLET),
                    // Both keyed by the lookup: the row above is read in this same statement, not yet in hand.
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(1).build(), REQUEST),
                    QueryEntities.query(ApprovalEntities.DECISION_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(MAX_DECISIONS).build(),
                        DECISIONS)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(approvalInput(ctx).status()),
                    QbFees.APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, ROW)).preparedBy(approvalInput(ctx)
                        .preparerId()), APPROVAL))
                .compute("Decide", (metadata, ctx) -> decided(ctx))
                .step("Charge the fee", CallProcess.<ProcessContext>when(ctx -> Boolean.TRUE.equals(ctx.get(DO_CHARGE)),
                    FeeProcesses.CHARGE, 1, ctx -> new FeeProcesses.ChargeInput(first(ctx, ROW).get("sponsorId"),
                        FeeRules.MANUAL, FeeRules.period(ctx.opTime(), zone), money(first(ctx, ROW).get("amount")),
                        first(ctx, ROW).get("reason"), "request:" + first(ctx, ROW).id(), first(ctx, ROW).get("requestedBy")),
                    CHARGED))
                // A void reverses the invoice's postings: the charges' (and a prepaid charge's own payment) by their
                // source, and a postpaid payment by the invoice's transaction.
                .step("Find the invoice's postings", RunTemplate.<ProcessContext>of(TRANSACTIONS_TEMPLATE, ctx -> {
                    EntityInstance invoice = first(ctx, INVOICE);
                    boolean voiding = Boolean.TRUE.equals(ctx.get(DO_VOID));
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("invoiceId", voiding ? String.valueOf(invoice.id()) : "none");
                    params.put("sponsorId", voiding ? invoice.get("sponsorId") : "none");
                    params.put("period", voiding ? invoice.get("period") : "none");
                    return params;
                }, TRANSACTIONS))
                .step("Reverse them", CallProcess.forEach(LedgerProcesses.REVERSE, 1, ctx -> {
                    List<LedgerProcesses.ReverseInput> reversals = new ArrayList<>();
                    for (Map<String, Object> row : rows(ctx, TRANSACTIONS)) {
                        reversals.add(new LedgerProcesses.ReverseInput(String.valueOf(row.get("transactionId")),
                            "Invoice voided: " + first(ctx, ROW).get("reason"), ctx.opTime()));
                    }
                    return reversals;
                }, REVERSALS))
                .compute("Record the outcome", (metadata, ctx) -> record(ctx))
                .step("Close the reminder", CloseTasks.<ProcessContext>cancel(ctx -> Boolean.TRUE.equals(ctx.get(DO_VOID))
                    ? QbFees.overdueTaskSource(first(ctx, INVOICE).id()) : QbFees.overdueTaskSource("none"))));
    }

    /** The approval request named by the event, when the lookup found a request under review for it. */
    private static List<Object> foundRequest(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        String requestId = approvalInput(ctx).requestId();
        return found != null && requestId != null && requestId.equals(found.get("approvalRequestId"))
            ? new ArrayList<>(List.of(requestId)) : new ArrayList<>();
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        return row != null && QbFees.REVIEW.equals(row.get("status")) && input.requestId() != null
            && input.requestId().equals(row.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            if (list(ctx, DECISIONS).stream().anyMatch(d -> row.get("sponsorId").equals(d.get("approverId")))) {
                // Approved by the sponsor themselves (an administrator with a sponsor account): rejected.
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            boolean manual = QbFees.REQUEST_MANUAL.equals(row.get("kind"));
            EntityInstance invoice = first(ctx, INVOICE);
            if (!manual && (invoice == null || QbFees.VOID.equals(invoice.get("status")))) {
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            ctx.put(manual ? DO_CHARGE : DO_VOID, true);
            ctx.put(STATUS_AFTER, QbFees.DONE);
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, row)) {
            ctx.put(STATUS_AFTER, QbFees.REJECTED);
        }
    }

    /** Whether the stored request (not the event) says that the request, as it is, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance row) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(row).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && QbFees.APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(row.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    static void record(ProcessContext ctx) {
        if (!ctx.contains(STATUS_AFTER)) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", ctx.get(STATUS_AFTER));
        values.put("decidedAt", ctx.opTime());
        if (ctx.contains(CHARGED)) {
            values.put("chargeId", ((FeeProcesses.ChargeOutput) ctx.get(CHARGED)).chargeId());
        }
        ctx.changes().update(QbFees.REQUEST, row.id(), row.version(), values);
        if (Boolean.TRUE.equals(ctx.get(DO_VOID))) {
            EntityInstance invoice = first(ctx, INVOICE);
            Map<String, Object> voided = new LinkedHashMap<>();
            voided.put("status", QbFees.VOID);
            voided.put("voidedAt", ctx.opTime());
            voided.put("voidedBy", approvalInput(ctx).preparerId());
            voided.put("voidReason", row.get("reason"));
            voided.put("voidRequestedBy", row.get("requestedBy"));
            ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), voided);
            // A paid invoice voided gives the payment back to the cash account.
            if (invoice.get("paidAt") != null) {
                QbWallets.change(ctx, first(ctx, CASH_WALLET), QbWallets.CASH, invoice.get("sponsorId"),
                    money(invoice.get("total")), 0, BigDecimal.ZERO);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    private static RequestInput input(ProcessContext ctx) {
        return ctx.get(INPUT, RequestInput.class);
    }

    private static TransferProcesses.ApprovalResultInput approvalInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static RequestOutput output(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER) : row == null ? null
            : row.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : row == null ? null : row.get("approvalRequestId");
        return new RequestOutput(row == null ? null : String.valueOf(row.id()), row == null ? null : row.get("kind"),
            status, requestId, ctx.contains(CHARGED) ? ((FeeProcesses.ChargeOutput) ctx.get(CHARGED)).chargeId()
                : row == null ? null : row.get("chargeId"));
    }
}
