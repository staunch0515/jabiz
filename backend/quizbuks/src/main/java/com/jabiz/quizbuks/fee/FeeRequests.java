package com.jabiz.quizbuks.fee;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.AccountEditing;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.quizbuks.setup.QbParams;
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
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 *   <li>{@value #RECORD} (finance): a fee charged by hand ({@code MANUAL}: the sponsor, the amount, why), an
 *       invoice voided ({@code VOID}: which, why) or platform fee Kudos granted by hand ({@code CREDIT}, F-M2: the
 *       sponsor, the amount within {@code qb.fee-credit.manual.max} (none set: no cap), why, and for how many days). The request is written and another administrator's approval asked
 *       (the recorder is the preparer, so they cannot approve their own); nothing is charged or voided before it.</li>
 *   <li>{@value #APPROVAL_RESULT} ({@code internal()}, on the approval's events): an approval of the request's own
 *       case charges the fee ({@code QB_FEE_CHARGE}, under the sponsor's wallet lock, in the current month) or voids
 *       the invoice (its charges' postings reversed, a payment given back to the cash account); a rejection, or an
 *       approval by the sponsor themselves, rejects the request. A grant is made by {@code QB_FEE_CREDIT_GRANT}
 *       (source {@code MANUAL}, once per request); one above the cap when approved (the cap lowered since) is
 *       rejected.</li>
 * </ul>
 */
public final class FeeRequests {

    public static final String RECORD = "QB_FEE_REQUEST";
    public static final String APPROVAL_RESULT = "QB_FEE_REQUEST_APPROVAL_RESULT";
    public static final String TRANSACTIONS_TEMPLATE = "qb.system.fee-invoice-transactions";
    /** F-M7 (§10.3-3): the credit notes on an invoice's fees, and what notes paid of it. */
    public static final String INVOICE_NOTES_TEMPLATE = "qb.system.fee-invoice-credit-notes";

    /**
     * @param kind      {@code MANUAL} (a fee charged by hand) or {@code VOID} (an invoice voided)
     * @param sponsorId a manual fee: the sponsor charged
     * @param amount    a manual fee: the fee before tax, in yen
     * @param invoiceId a void: the invoice
     * @param reason    why, as the approver reads it
     */
    public record RequestInput(@NotBlank @Pattern(regexp = "MANUAL|VOID|CREDIT|CREDIT_NOTE") String kind,
        @Size(max = 128) String sponsorId,
        @DecimalMin("1") @DecimalMax(FeeProcesses.MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @Pattern(regexp = "[0-9a-fA-F-]{36}") String invoiceId,
        @NotBlank @Size(max = QbFees.REASON_LENGTH) String reason,
        @Min(1) @Max(FeeCreditRules.MAX_VALID_DAYS) Integer validDays,
        @Pattern(regexp = "[0-9a-fA-F-]{36}") String chargeId) {

        /** A fee by hand or a void, as before F-M2 (no validity). */
        public RequestInput(String kind, String sponsorId, BigDecimal amount, String invoiceId, String reason) {
            this(kind, sponsorId, amount, invoiceId, reason, null, null);
        }

        /** A request of the kinds before F-M7 (no fee credited). */
        public RequestInput(String kind, String sponsorId, BigDecimal amount, String invoiceId, String reason,
            Integer validDays) {
            this(kind, sponsorId, amount, invoiceId, reason, validDays, null);
        }

        /** F-M7: a credit note by hand on {@code chargeId}, of {@code amount} before tax. */
        public static RequestInput creditNote(String chargeId, BigDecimal amount, String reason) {
            return new RequestInput(QbFees.REQUEST_CREDIT_NOTE, null, amount, null, reason, null, chargeId);
        }
    }

    /**
     * @param approvalRequestId the request another administrator decides
     * @param grantId           a grant by hand: the lot the approval made
     */
    public record RequestOutput(String requestId, String kind, String status, String approvalRequestId,
        String chargeId, String grantId, String creditNoteId) {}

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
    static final String REFUNDED = "refunded";
    static final String STATUS_AFTER = "statusAfter";
    static final String DO_VOID = "doVoid";

    /** Published when an invoice was voided (the mail tells the sponsor; the payload names the invoice only). */
    public static final String VOIDED_EVENT = "qb.fee.invoice-voided";
    static final String DO_CHARGE = "doCharge";
    static final String DO_GRANT = "doGrant";
    static final String BAN = "ban";
    static final String GRANTED = "granted";
    static final String CAP = "cap";
    static final String CHARGE = "charge";
    static final String CHARGE_INVOICE = "chargeInvoice";
    static final String MONTH_INVOICE = "monthInvoice";
    static final String CHARGE_NOTES = "chargeNotes";
    static final String DO_NOTE = "doNote";
    static final String NOTED = "noted";
    static final String NOTE_FAILED = "noteFailed";
    static final String INVOICE_NOTES = "invoiceNotes";
    /**
     * F-M7 (N8, §10.3-3): finance's task when a request approved could not be done as approved: a credit note the fee
     * no longer allows, or a void of an invoice a note was issued on since.
     */
    public static final String NOTE_FAILED_TASK = "qb.fee.request-failed";
    public static final String NOTE_FAILED_TASK_TITLE = "task.qb.fee.request-failed";
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
            // F-M2: the cap of a grant by hand; not set (the setup does not create it) is no cap.
            .step("Load the cap of a grant by hand", LoadParams.<ProcessContext>optional(ProcessContext::opTime, CAP,
                QbParams.FEE_CREDIT_MANUAL_MAX))
            .step("Load the sponsor, their ban, the invoice and the review rule", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("userId", FeeGate.ids(sponsorOf(ctx))),
                        new QueryPredicate.Eq("reviewStatus", SponsorStatus.APPROVED)))).limit(1).build(), SPONSOR),
                // F-M15 a0: a grant by hand is not asked for a banned sponsor (a fee by hand still is).
                QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> banOf(sponsorOf(ctx)), BAN),
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> FeeProcesses.byId("invoiceId", input(ctx).invoiceId()), INVOICE),
                QueryEntities.query(ApprovalEntities.RULE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("subject", QbFees.APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES),
                // F-M7: a credit note's fee and its own invoice (no SQL for the other kinds).
                QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE),
                    ctx -> FeeProcesses.byId("chargeId", input(ctx).chargeId()), CHARGE),
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                    ctx -> FeeProcesses.byId("chargeId", input(ctx).chargeId()), CHARGE_INVOICE)))
            // F-M7 (H1 of the review): the fee's notes so far, however many (a credit note's request only).
            .step("Read the notes on the fee", RunTemplate.<ProcessContext>when(
                ctx -> QbFees.REQUEST_CREDIT_NOTE.equals(input(ctx).kind()) && input(ctx).chargeId() != null,
                CreditNotes.CHARGE_NOTES_TEMPLATE, ctx -> Map.of("chargeId", input(ctx).chargeId()), CHARGE_NOTES))
            .step("Load the fee's month invoice", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.INVOICE),
                FeeRequests::monthInvoice, MONTH_INVOICE))
            .step("Look for credit notes on the invoice", RunTemplate.<ProcessContext>when(
                ctx -> QbFees.REQUEST_VOID.equals(input(ctx).kind()) && input(ctx).invoiceId() != null,
                INVOICE_NOTES_TEMPLATE, ctx -> Map.of("invoiceId", input(ctx).invoiceId()), INVOICE_NOTES))
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
        boolean manual = bySponsor(input.kind());
        boolean credit = QbFees.REQUEST_CREDIT.equals(input.kind());
        boolean note = QbFees.REQUEST_CREDIT_NOTE.equals(input.kind());
        EntityInstance invoice = first(ctx, INVOICE);
        if (note) {
            EntityInstance charge = first(ctx, CHARGE);
            if (input.amount() == null) {
                problems.add(new Violation("amount", FeeCodes.FEE_PARAMS, "A credit note has an amount", Map.of()));
            }
            if (charge == null) {
                throw new com.jabiz.runtime.EntityNotFoundException("No platform fee " + input.chargeId());
            }
            Violation refusal = noteRefusal(ctx, input.amount());
            if (refusal != null) {
                problems.add(refusal);
            }
        } else if (manual) {
            if (input.amount() == null) {
                problems.add(new Violation("amount", FeeCodes.FEE_PARAMS, "A manual fee has an amount", Map.of()));
            }
            if (first(ctx, SPONSOR) == null) {
                problems.add(new Violation("sponsorId", credit ? FeeCodes.FEE_CREDIT_SPONSOR : FeeCodes.FEE_SPONSOR,
                    credit ? "Only an approved sponsor is granted platform fee Kudos" : "Only an approved sponsor is charged",
                    Map.of()));
            }
            if (credit && banned(ctx)) {
                problems.add(new Violation("sponsorId", FeeCodes.FEE_CREDIT_BANNED,
                    "A banned sponsor is granted no platform fee Kudos", Map.of()));
            }
            if (credit && input.amount() != null && !FeeCreditRules.withinCap(input.amount(), cap(ctx))) {
                problems.add(new Violation("amount", FeeCodes.FEE_CREDIT_MAX,
                    "Above the most one grant by hand may be", Map.of("max", cap(ctx).setScale(0).toPlainString())));
            }
        } else {
            if (invoice == null) {
                problems.add(new Violation("invoiceId", FeeCodes.FEE_INVOICE_STATE, "No such invoice", Map.of()));
            } else if (QbFees.VOID.equals(invoice.get("status"))) {
                problems.add(new Violation("invoiceId", FeeCodes.FEE_INVOICE_STATE, "The invoice is voided already",
                    Map.of("status", QbFees.VOID)));
            } else if (hasCreditNote(ctx)) {
                problems.add(hasCreditNoteViolation(invoice));
            }
        }
        String sponsor = note ? (first(ctx, CHARGE) == null ? null : first(ctx, CHARGE).get("sponsorId"))
            : manual ? input.sponsorId() : invoice == null ? null : invoice.get("sponsorId");
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

    /** F-M7 (§10.3-3): whether the invoice read has a credit note on a fee of it, or was paid by one. */
    static boolean hasCreditNote(ProcessContext ctx) {
        List<Map<String, Object>> rows = rows(ctx, INVOICE_NOTES);
        EntityInstance invoice = first(ctx, INVOICE);
        return (!rows.isEmpty() && money(rows.getFirst().get("notes")).signum() > 0)
            || (invoice != null && money(invoice.get("creditApplied")).signum() > 0);
    }

    static Violation hasCreditNoteViolation(EntityInstance invoice) {
        return new Violation("invoiceId", FeeCodes.INVOICE_HAS_CREDIT_NOTE,
            "The invoice has a credit note on it, or was paid by one: correct it with a credit note",
            Map.of("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo"))));
    }

    /** F-M7: a postpaid fee not paid early is on its month's invoice (no SQL for anything else). */
    static EntityQuery monthInvoice(ProcessContext ctx) {
        EntityInstance charge = first(ctx, CHARGE);
        boolean month = charge != null && FeeRules.POSTPAID.equals(charge.get("billingMode"))
            && first(ctx, CHARGE_INVOICE) == null;
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                new QueryPredicate.In("sponsorId", month ? FeeGate.ids(charge.get("sponsorId")) : List.of()),
                new QueryPredicate.Eq("period", month ? charge.get("period") : ""),
                new QueryPredicate.IsNull("chargeId"))))
            .limit(1).build();
    }

    /**
     * F-M7 (§10.3-3, -12, N8): why a credit note of {@code amount} on the fee read is not issued: its invoice voided,
     * or more than the fee may still be credited (the notes so far, automatic and by hand). Null: it may be.
     */
    static Violation noteRefusal(ProcessContext ctx, BigDecimal amount) {
        EntityInstance charge = first(ctx, CHARGE);
        EntityInstance invoice = first(ctx, CHARGE_INVOICE) != null ? first(ctx, CHARGE_INVOICE)
            : first(ctx, MONTH_INVOICE);
        if (invoice != null && QbFees.VOID.equals(invoice.get("status"))) {
            return new Violation("chargeId", FeeCodes.CREDIT_NOTE_VOIDED,
                "The fee's invoice is voided: what it was paid went back with it",
                Map.of("invoiceNo", String.valueOf((Object) invoice.get("invoiceNo"))));
        }
        BigDecimal credited = CreditNotes.rows(ctx, CHARGE_NOTES).stream().map(n -> money(n.get("amount")))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditable = CreditNoteRules.creditable(money(charge.get("amount")), credited);
        if (amount != null && amount.compareTo(creditable) > 0) {
            return new Violation("amount", FeeCodes.CREDIT_NOTE_EXCEEDS, "The fee may still be credited " + creditable,
                Map.of("creditable", creditable.toPlainString(), "amount", amount.toPlainString()));
        }
        return null;
    }

    /** The ban of the sponsor a request names (none for none). */
    static EntityQuery banOf(Object sponsorId) {
        return EntityQuery.builder().where(new QueryPredicate.In("userId", AccountEditing.uuids(sponsorId))).limit(1)
            .build();
    }

    /** Whether the sponsor read is banned now. */
    static boolean banned(ProcessContext ctx) {
        EntityInstance ban = first(ctx, BAN);
        return BanRules.banned(ban == null ? null : ban.attributes());
    }

    /** Whether a kind of request names its sponsor and an amount (a fee or a grant by hand), not an invoice. */
    static boolean bySponsor(Object kind) {
        return QbFees.REQUEST_MANUAL.equals(kind) || QbFees.REQUEST_CREDIT.equals(kind);
    }

    /** A grant's validity in days as recorded; null for none (the parameter's at the approval). */
    static Integer validDays(EntityInstance row) {
        Object days = row.get("validDays");
        return days == null ? null : money(days).intValueExact();
    }

    /** The cap of a grant by hand in effect; null when not set (no cap). */
    static BigDecimal cap(ProcessContext ctx) {
        ParamValues params = ctx.get(CAP, ParamValues.class);
        return params == null ? null : params.find(QbParams.FEE_CREDIT_MANUAL_MAX, BigDecimal.class).orElse(null);
    }

    static void insert(ProcessContext ctx) {
        RequestInput input = input(ctx);
        boolean manual = bySponsor(input.kind());
        boolean note = QbFees.REQUEST_CREDIT_NOTE.equals(input.kind());
        EntityInstance invoice = first(ctx, INVOICE);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", input.kind());
        values.put("sponsorId", note ? first(ctx, CHARGE).get("sponsorId") : manual ? input.sponsorId()
            : invoice.get("sponsorId"));
        values.put("amount", manual || note ? input.amount() : null);
        values.put("invoiceId", manual || note ? null : String.valueOf(invoice.id()));
        values.put("creditedChargeId", note ? String.valueOf(first(ctx, CHARGE).id()) : null);
        values.put("validDays", QbFees.REQUEST_CREDIT.equals(input.kind()) ? input.validDays() : null);
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
        // F-M2: only a grant's (a request recorded before keeps the content it was asked to approve).
        if (row.get("validDays") != null) {
            content.put("validDays", validDays(row));
        }
        // F-M7: only a credit note's: the fee credited.
        if (row.get("creditedChargeId") != null) {
            content.put("creditedChargeId", row.get("creditedChargeId"));
        }
        return ApprovalCase.of(String.valueOf(row.id()), facts, content).preparedBy(row.get("requestedBy"))
            .reference((String) row.get("kind") + " " + (row.get("invoiceId") != null ? row.get("invoiceId")
                : row.get("creditedChargeId") != null ? row.get("creditedChargeId") : row.get("sponsorId")));
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
                .step("Load the cap of a grant by hand", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                    CAP, QbParams.FEE_CREDIT_MANUAL_MAX))
                .step("Find the request", QueryEntities.<ProcessContext>of(QbFees.defaultDataset(QbFees.REQUEST),
                    ctx -> FeeProcesses.byId("requestId", QbFees.APPROVAL_SUBJECT.equals(approvalInput(ctx).subject())
                        ? approvalInput(ctx).entityId() : null), FOUND))
                .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbWallets.lockName(QbWallets.SPONSOR, first(ctx, FOUND).get("sponsorId"))))
                .step("Read the request, the sponsor, the invoice, the cash and the approval", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> FeeProcesses.byId("requestId",
                        first(ctx, FOUND) == null ? null : String.valueOf(first(ctx, FOUND).id())), ROW),
                    // A fee by hand still needs an approved sponsor (the charge refuses otherwise): rejected, not stuck.
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.SPONSOR), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.And(List.of(
                            new QueryPredicate.In("userId", FeeGate.ids(first(ctx, FOUND) == null ? null
                                : first(ctx, FOUND).get("sponsorId"))),
                            new QueryPredicate.Eq("reviewStatus", SponsorStatus.APPROVED)))).limit(1).build(), SPONSOR),
                    // F-M15 a0: banned while the grant by hand was in review: rejected, not stuck.
                    QueryEntities.query(QbAccounts.defaultDataset(QbAccounts.BAN), ctx -> banOf(first(ctx, FOUND) == null
                        ? null : first(ctx, FOUND).get("sponsorId")), BAN),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> FeeProcesses.byId("invoiceId",
                        first(ctx, FOUND) == null ? null : (String) first(ctx, FOUND).get("invoiceId")), INVOICE),
                    // Both keyed by the lookup: the row above is read in this same statement, not yet in hand.
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(1).build(), REQUEST),
                    QueryEntities.query(ApprovalEntities.DECISION_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", foundRequest(ctx))).limit(MAX_DECISIONS).build(),
                        DECISIONS),
                    // F-M7: a credit note's fee and its own invoice, read again under the wallet lock.
                    QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE),
                        ctx -> FeeProcesses.byId("chargeId", creditedOf(ctx)), CHARGE),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                        ctx -> FeeProcesses.byId("chargeId", creditedOf(ctx)), CHARGE_INVOICE)))
                // F-M7 (H1 of the review): the fee's notes so far, however many (a credit note's request only).
                .step("Read the notes on the fee", RunTemplate.<ProcessContext>when(ctx -> creditedOf(ctx) != null,
                    CreditNotes.CHARGE_NOTES_TEMPLATE, ctx -> Map.of("chargeId", creditedOf(ctx)), CHARGE_NOTES))
                .step("Read the fee's month invoice", QueryEntities.<ProcessContext>of(
                    QbFees.defaultDataset(QbFees.INVOICE), FeeRequests::monthInvoice, MONTH_INVOICE))
                .step("Look for credit notes on the invoice", RunTemplate.<ProcessContext>when(
                    ctx -> first(ctx, FOUND) != null && QbFees.REQUEST_VOID.equals(first(ctx, FOUND).get("kind"))
                        && first(ctx, INVOICE) != null,
                    INVOICE_NOTES_TEMPLATE, ctx -> Map.of("invoiceId", String.valueOf(first(ctx, INVOICE).id())),
                    INVOICE_NOTES))
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
                // F-M2: a grant by hand, once per request (the wallet lock is held).
                .step("Grant the platform fee Kudos", CallProcess.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(DO_GRANT)), FeeCredits.GRANT, 1,
                    ctx -> new FeeCredits.GrantInput(first(ctx, ROW).get("sponsorId"), QbFeeAccount.MANUAL,
                        FeeCreditRules.manualKey(first(ctx, ROW).id()), money(first(ctx, ROW).get("amount")),
                        validDays(first(ctx, ROW))),
                    GRANTED))
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
                // F-M1: what paid the invoice back to the lots it came from (the reversals put the ledger back).
                .step("Give the payments back", CallProcess.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(DO_VOID)), FeeSettlement.REFUND, 1, FeeRequests::refundInput,
                    REFUNDED))
                // F-M7 (§10.3-12): the note as approved, never less (N8: checked in decided(), under the lock).
                .step("Issue the credit note", CallProcess.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(DO_NOTE)), CreditNotes.ISSUE, 1,
                    ctx -> new CreditNotes.IssueInput(first(ctx, ROW).get("sponsorId"),
                        (String) first(ctx, ROW).get("creditedChargeId"), money(first(ctx, ROW).get("amount")),
                        QbCreditNotes.MANUAL, null, null, first(ctx, ROW).get("reason"),
                        QbCreditNotes.manualSource(String.valueOf(first(ctx, ROW).id())), String.valueOf(approver(ctx)), true),
                    NOTED))
                .compute("Record the outcome", (metadata, ctx) -> record(ctx))
                .step("Ask the mail to tell the sponsor", com.jabiz.runtime.process.steps.PublishEvent.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(DO_VOID)), VOIDED_EVENT,
                    ctx -> Map.of("invoiceId", String.valueOf(first(ctx, INVOICE).id()))))
                .step("Tell finance it was not done", CreateTask.<ProcessContext>when(
                    ctx -> ctx.contains(NOTE_FAILED), FeeRequests::noteFailedTask))
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
            boolean manual = bySponsor(row.get("kind"));
            boolean credit = QbFees.REQUEST_CREDIT.equals(row.get("kind"));
            if (QbFees.REQUEST_CREDIT_NOTE.equals(row.get("kind"))) {
                // N8: as approved or not at all; never cut to what is left now (another note since): the request
                // fails and finance asks again for what is left.
                Violation refusal = first(ctx, CHARGE) == null ? new Violation("chargeId", FeeCodes.FEE_PARAMS,
                    "The fee is gone", Map.of()) : noteRefusal(ctx, money(row.get("amount")));
                if (refusal != null) {
                    ctx.put(NOTE_FAILED, refusal);
                    ctx.put(STATUS_AFTER, QbFees.FAILED);
                    return;
                }
                ctx.put(DO_NOTE, true);
                ctx.put(STATUS_AFTER, QbFees.DONE);
                return;
            }
            EntityInstance invoice = first(ctx, INVOICE);
            if (!manual && (invoice == null || QbFees.VOID.equals(invoice.get("status")))) {
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            // F-M7 (§10.3-3): a credit note since the request was made: not voided; finance corrects by a note.
            if (!manual && hasCreditNote(ctx)) {
                ctx.put(NOTE_FAILED, hasCreditNoteViolation(invoice));
                ctx.put(STATUS_AFTER, QbFees.FAILED);
                return;
            }
            if (manual && first(ctx, SPONSOR) == null) {
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            if (credit && banned(ctx)) {
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            // The cap lowered since the request: what it allows now decides (a control point, A1-6).
            if (credit && !FeeCreditRules.withinCap(money(row.get("amount")), cap(ctx))) {
                ctx.put(STATUS_AFTER, QbFees.REJECTED);
                return;
            }
            ctx.put(credit ? DO_GRANT : manual ? DO_CHARGE : DO_VOID, true);
            ctx.put(STATUS_AFTER, QbFees.DONE);
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, row)) {
            ctx.put(STATUS_AFTER, QbFees.REJECTED);
        }
    }

    /** Who approved: the last decision read (the requester prepared it; the platform keeps them apart). */
    private static Object approver(ProcessContext ctx) {
        List<EntityInstance> decisions = list(ctx, DECISIONS);
        return decisions.isEmpty() ? approvalInput(ctx).preparerId() : decisions.getLast().get("approverId");
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
        if (ctx.contains(GRANTED)) {
            values.put("grantId", ((FeeCredits.GrantOutput) ctx.get(GRANTED)).grantId());
        }
        if (ctx.contains(NOTED)) {
            values.put("creditNoteId", ((CreditNotes.IssueOutput) ctx.get(NOTED)).creditNoteId());
        }
        ctx.changes().update(QbFees.REQUEST, row.id(), row.version(), values);
        if (Boolean.TRUE.equals(ctx.get(DO_VOID))) {
            EntityInstance invoice = first(ctx, INVOICE);
            Map<String, Object> voided = new LinkedHashMap<>();
            voided.put("status", QbFees.VOID);
            voided.put("voidedAt", ctx.opTime());
            voided.put("voidedBy", approver(ctx));
            voided.put("voidReason", row.get("reason"));
            voided.put("voidRequestedBy", row.get("requestedBy"));
            ctx.changes().update(QbFees.INVOICE, invoice.id(), invoice.version(), voided);
        }
    }

    /** The invoice voided, what was paid of it and the payment postings reversed, for {@code QB_FEE_REFUND}. */
    @SuppressWarnings("unchecked")
    static FeeSettlement.RefundInput refundInput(ProcessContext ctx) {
        EntityInstance invoice = first(ctx, INVOICE);
        List<FeeSettlement.Reversal> reversals = new ArrayList<>();
        List<Object> outputs = (List<Object>) ctx.get(REVERSALS);
        if (outputs != null) {
            for (Object output : outputs) {
                LedgerProcesses.ReverseOutput reversed = (LedgerProcesses.ReverseOutput) output;
                reversals.add(new FeeSettlement.Reversal(reversed.reversedTransactionId(), reversed.transactionId()));
            }
        }
        // Paid before F-M1 an invoice was paid in full or not at all, and has no paid amount of its own.
        BigDecimal paidAmount = money(invoice.get("paidAmount"));
        return new FeeSettlement.RefundInput(invoice.get("sponsorId"), String.valueOf(invoice.id()),
            invoice.get("chargeId"), paidAmount, reversals);
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
                : row == null ? null : row.get("chargeId"),
            ctx.contains(GRANTED) ? ((FeeCredits.GrantOutput) ctx.get(GRANTED)).grantId()
                : row == null ? null : row.get("grantId"),
            ctx.contains(NOTED) ? ((CreditNotes.IssueOutput) ctx.get(NOTED)).creditNoteId()
                : row == null ? null : row.get("creditNoteId"));
    }

    /** The fee a request found credits (F-M7); none for the other kinds. */
    private static String creditedOf(ProcessContext ctx) {
        EntityInstance found = first(ctx, FOUND);
        return found == null || found.get("creditedChargeId") == null ? null
            : String.valueOf((Object) found.get("creditedChargeId"));
    }

    /** F-M7 (N8): finance's task: the request approved was not done, and why; they ask again as the fee allows. */
    static com.jabiz.runtime.task.TaskSpec noteFailedTask(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        Violation why = ctx.get(NOTE_FAILED, Violation.class);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("kind", String.valueOf((Object) row.get("kind")));
        params.put("amount", row.get("amount") == null ? "" : money(row.get("amount")).toPlainString());
        params.put("code", why.ruleCode());
        return com.jabiz.runtime.task.TaskSpec.forPermission(NOTE_FAILED_TASK, NOTE_FAILED_TASK_TITLE, params,
                QbPermissions.FEE_MANAGE)
            .about(QbFees.REQUEST, String.valueOf(row.id())).source(NOTE_FAILED_TASK + ":" + row.id());
    }
}
