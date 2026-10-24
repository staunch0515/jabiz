package com.jabiz.quizbuks.wallet;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.identity.SponsorStatus;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.TOPUP;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.WALLET;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;

/**
 * Manual credits (docs/quizbuks/plans/Q18-bank-settings-credit.md, Q18b): a finance administrator credits a sponsor's
 * wallet by hand and another administrator approves it, as with a bank top-up's record (B-1):
 * <ul>
 *   <li>{@value #RECORD} (finance): the sponsor, the amount, the kind and why: money received outside the system
 *       ({@code PAID_OUTSIDE}, with the day and the statement's reference, which one top-up at most is recorded
 *       against) or a grant ({@code GRANT}, no money). It is a top-up of method {@code MANUAL}, under review, with a code
 *       from the transfer codes as its reference; the platform asks for another administrator's approval (the
 *       recorder is the preparer, so they cannot approve it). Nothing is posted, and the sponsor sees nothing;</li>
 *   <li>{@value #APPROVAL_RESULT} (the approval's events): an approval of the credit's own request for what is recorded
 *       posts debit 1120 (money received) or 5120 (a grant) / credit 2110 (the sponsor), raises the wallet, marks it
 *       {@code PAID} and shows it to the sponsor, as a bank top-up's approval does (pending rewards, the mail; a receipt
 *       for money received only). A rejection, or an approval by the sponsor themselves, cancels it.</li>
 * </ul>
 * There is no reversal (Q18b): a wrong credit is corrected by finance outside these processes.
 */
public final class ManualCredits {

    public static final String RECORD = "QB_TOPUP_MANUAL_RECORD";
    public static final String APPROVAL_RESULT = "QB_TOPUP_MANUAL_APPROVAL_RESULT";

    /** The approval subject of manual credits, and its rule (proposed by {@code QB_SETUP}). */
    public static final String APPROVAL_SUBJECT = "qb.topup-manual";
    public static final String REVIEW_RULE = "qb.topup-manual.review";

    /** The most one manual credit may be, in Kudos (security review Q18b: the rule has no limit of its own). */
    public static final String MAX_AMOUNT = "10000000";

    /** The prefix of a manual credit's posting reference: the monthly report tells them from bank top-ups by it. */
    public static final String REFERENCE_PREFIX = "mn_";

    /** Finance's task about a credit the sponsor approved themselves ({@code task.qb.topup-manual.approved-by-sponsor}). */
    static final String TASK_TYPE = "qb.topup-manual";

    /**
     * @param sponsorId     the sponsor (their user id) whose wallet is credited
     * @param kind          {@code PAID_OUTSIDE} or {@code GRANT}
     * @param amount        whole Kudos (one per yen), at most {@value #MAX_AMOUNT} (a ceiling on what two people can
     *                      create by hand; larger sums are split, and seen)
     * @param reason        why, as the approver reads it
     * @param receivedOn    money received outside: the statement's day of the deposit, in Japan; a grant has none
     * @param bankReference money received outside: the statement's reference of the deposit; a grant has none
     */
    public record RecordInput(@NotBlank @Size(max = 128) String sponsorId,
        @NotBlank @Pattern(regexp = "PAID_OUTSIDE|GRANT") String kind,
        @NotNull @DecimalMin("1") @DecimalMax(MAX_AMOUNT) @Digits(integer = 13, fraction = 0) BigDecimal amount,
        @NotBlank @Size(max = QbTopUps.MANUAL_REASON_LENGTH) String reason, LocalDate receivedOn,
        @Size(max = BankTransfers.REFERENCE_LENGTH) String bankReference) {}

    /** @param approvalRequestId the request another administrator decides */
    public record ManualOutput(String topUpId, String status, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String SPONSOR = "sponsor";
    static final String RULES = "rules";
    static final String SAME_ENTRY = "sameEntry";
    static final String CODE = "code";
    static final String NEW_ID = "newId";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String DECISIONS = "decisions";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String STATUS_AFTER = "statusAfter";
    static final String SPONSOR_APPROVED = "sponsorApproved";

    // ---- finance ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<RecordInput, ManualOutput, ProcessContext> record() {
        return ProcessDefinition.define(RECORD, 1, RecordInput.class, ManualOutput.class, ProcessContext.class, pb -> pb
            .description("Records a manual credit of a sponsor's wallet (money received outside the system, or a "
                + "grant), for another administrator's approval; nothing is credited before it.")
            .permissions(QbPermissions.TOPUP_MANUAL_RECORD)
            .requiresVerifiedEmail()
            .contextFactory(ManualCredits::context)
            .outputMapper(ManualCredits::output)
            .step("Load the sponsor", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.SPONSOR),
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("userId", input(ctx).sponsorId()),
                    new QueryPredicate.Eq("reviewStatus", SponsorStatus.APPROVED)))).limit(1).build(), SPONSOR))
            .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("subject", APPROVAL_SUBJECT),
                    new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES))
            // The statement's entry, whatever it was recorded against: a bank top-up or another manual credit. A grant
            // has none: its query matches nothing (the platform's query steps have no condition).
            .step("Look for the statement's entry", QueryEntities.<ProcessContext>of(
                QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> {
                    RecordInput input = input(ctx);
                    if (input.receivedOn() == null || input.bankReference() == null) {
                        return EntityQuery.builder().where(new QueryPredicate.In("topUpId", new ArrayList<>()))
                            .limit(1).build();
                    }
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("receivedOn", input.receivedOn()),
                        new QueryPredicate.Eq("bankReference", BankTransfers.reference(input.bankReference())))))
                        .limit(1).build();
                }, SAME_ENTRY))
            .compute("Check the credit", (metadata, ctx) -> check(ctx))
            // After every check that can refuse: a code drawn is kept (the count is rolled back with a failure).
            .step("Draw the credit's code", AssignNumber.<ProcessContext>of(QbTopUps.TRANSFER_CODE_SEQUENCE, CODE))
            .compute("Record the credit", (metadata, ctx) -> insert(ctx))
            // Written first, so that the approval names it and binds what is stored (read back, as its result reads it).
            .step("Save the credit", SaveChanges.now())
            .step("Read the credit", QueryEntities.<ProcessContext>of(QbTopUps.defaultDataset(QbTopUps.TOPUP),
                ctx -> TopUpCrediting.byId((String) ctx.get(NEW_ID)), TOPUP))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(APPROVAL_SUBJECT,
                ctx -> approvalCase(first(ctx, TOPUP)), APPROVAL))
            .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    static void check(ProcessContext ctx) {
        RecordInput input = input(ctx);
        List<Violation> problems = new ArrayList<>();
        if (first(ctx, SPONSOR) == null) {
            problems.add(new Violation("sponsorId", WalletCodes.TOPUP_MANUAL_SPONSOR, "Only an approved sponsor's "
                + "wallet is credited", Map.of()));
        }
        if (ctx.request().actorId().equals(input.sponsorId())) {
            problems.add(new Violation(null, WalletCodes.TOPUP_MANUAL_OWN, "One's own wallet is credited by another "
                + "administrator", Map.of()));
        }
        boolean outside = QbTopUps.PAID_OUTSIDE.equals(input.kind());
        boolean hasEntry = input.receivedOn() != null || (input.bankReference() != null
            && !input.bankReference().isBlank());
        boolean fullEntry = input.receivedOn() != null && input.bankReference() != null
            && !BankTransfers.reference(input.bankReference()).isEmpty();
        if (outside ? !fullEntry : hasEntry) {
            problems.add(new Violation(outside ? "bankReference" : null, WalletCodes.TOPUP_MANUAL_KIND, outside
                ? "Money received outside needs the statement's day and reference" : "A grant has no statement entry",
                Map.of("kind", input.kind())));
        }
        if (outside && input.receivedOn() != null) {
            Violation future = BankTransfers.futureProblem("receivedOn", input.receivedOn(), ctx.opTime());
            if (future != null) {
                problems.add(future);
            }
        }
        if (first(ctx, SAME_ENTRY) != null) {
            problems.add(new Violation("bankReference", WalletCodes.TOPUP_BANK_REFERENCE_USED, "The statement's entry "
                + "is recorded against another top-up", Map.of()));
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    private static Violation noReview() {
        return new Violation(null, WalletCodes.TOPUP_MANUAL_NO_REVIEW, "No approval rule asks for a second person on "
            + "manual credits", Map.of());
    }

    static void insert(ProcessContext ctx) {
        RecordInput input = input(ctx);
        boolean outside = QbTopUps.PAID_OUTSIDE.equals(input.kind());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", input.sponsorId());
        values.put("method", QbTopUps.MANUAL);
        values.put("manualKind", input.kind());
        values.put("manualReason", input.reason().strip());
        values.put("transferCode", ctx.get(CODE));
        values.put("requestedAmount", input.amount());
        values.put("amount", input.amount());
        values.put("status", TopUpStatus.REVIEW);
        // A rejection cancels it.
        values.put("reviewFrom", TopUpStatus.CANCELLED);
        values.put("receivedOn", outside ? input.receivedOn() : null);
        values.put("bankReference", outside ? BankTransfers.reference(input.bankReference()) : null);
        values.put("recordedBy", ctx.request().actorId());
        values.put("recordedAt", ctx.opTime());
        values.put("createdAt", ctx.opTime());
        values.put("refundedAmount", BigDecimal.ZERO);
        values.put("refundShortfall", BigDecimal.ZERO);
        values.put("disputeClawback", BigDecimal.ZERO);
        values.put("disputeShortfall", BigDecimal.ZERO);
        values.put("disputeHeld", BigDecimal.ZERO);
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbTopUps.TOPUP, values)));
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // The rule's condition was changed so that it no longer applies: never credited by one person. Nothing is
            // kept (the whole transaction is refused).
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance topUp = first(ctx, TOPUP);
        ctx.put(STATUS_AFTER, TopUpStatus.REVIEW);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(),
            Map.of("approvalRequestId", approval.requestId()));
    }

    /**
     * The case: the rule's facts, and the content approved (whose wallet, how much, which kind and why, which statement
     * entry), from the stored record. The recorder is the preparer, so the platform refuses their own approval.
     */
    static ApprovalCase approvalCase(EntityInstance topUp) {
        BigDecimal amount = QbTopUps.money(topUp.get("amount")).setScale(0);
        Object receivedOn = topUp.get("receivedOn");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("manual", true);
        facts.put("amount", amount);
        facts.put("sponsorId", topUp.get("sponsorId"));
        facts.put("kind", topUp.get("manualKind"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("topUpId", String.valueOf(topUp.id()));
        content.put("sponsorId", topUp.get("sponsorId"));
        content.put("code", topUp.get("transferCode"));
        content.put("kind", topUp.get("manualKind"));
        content.put("amount", amount);
        content.put("reason", topUp.get("manualReason"));
        content.put("receivedOn", receivedOn == null ? null : receivedOn.toString());
        content.put("bankReference", topUp.get("bankReference"));
        content.put("recordedAt", BankTopUps.Recorded.recordedAt(QbTopUps.instant(topUp.get("recordedAt"))));
        return ApprovalCase.of(String.valueOf(topUp.id()), facts, content).preparedBy(topUp.get("recordedBy"))
            .reference(topUp.get("transferCode"));
    }

    // ---- the approval -----------------------------------------------------------------------------------------------

    /**
     * Continues after the approval is decided (subscribed to both approval events, run as the system). Only the
     * credit's own request counts: an approval posts only when {@link RequireApproval} finds that request approved for
     * what is recorded, a rejection only when the stored request is rejected for it.
     */
    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, ManualOutput, ProcessContext>
        approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            ManualOutput.class, ProcessContext.class, pb -> pb
                .description("Credits a manual credit once another administrator approved it, or cancels it when "
                    + "they rejected it.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory(ManualCredits::context)
                .outputMapper(ManualCredits::output)
                .steps(b -> TopUpCrediting.lockAndLoad(b, ctx -> {
                    TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
                    return APPROVAL_SUBJECT.equals(input.subject()) ? input.entityId() : null;
                }))
                .step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId", ownRequest(ctx)
                        ? new ArrayList<>(List.of(approvalInput(ctx).requestId())) : new ArrayList<>()))
                        .limit(1).build(), REQUEST))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(approvalInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, TOPUP))
                        .preparedBy(approvalInput(ctx).preparerId()), APPROVAL))
                // Who approved: a sponsor who is also an administrator never approves their own credit.
                .step("Load the decisions", QueryEntities.<ProcessContext>of(ApprovalEntities.DECISION_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId", ownRequest(ctx)
                        ? new ArrayList<>(List.of(approvalInput(ctx).requestId())) : new ArrayList<>()))
                        .limit(BankTopUps.MAX_DECISIONS).build(), DECISIONS))
                .compute("Credit or cancel", (metadata, ctx) -> decided(ctx))
                .step("Post the credit", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Credit the wallet", (metadata, ctx) -> credit(ctx))
                .step("Ask for the pending rewards to be paid", PublishEvent.<ProcessContext>when(
                    ctx -> ctx.contains(POSTING), QbWallets.SETTLE_EVENT,
                    ctx -> Map.of("sponsorId", first(ctx, TOPUP).get("sponsorId"))))
                .step("Announce the top-up", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(POSTING),
                    QbTopUps.PAID_EVENT, ctx -> {
                        EntityInstance topUp = first(ctx, TOPUP);
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("topUpId", String.valueOf(topUp.id()));
                        payload.put("sponsorId", topUp.get("sponsorId"));
                        payload.put("amount", QbTopUps.money(topUp.get("amount")));
                        return payload;
                    }))
                .step("Tell finance", CreateTask.<ProcessContext>when(ctx -> ctx.contains(SPONSOR_APPROVED),
                    ctx -> sponsorApprovedTask(first(ctx, TOPUP)))));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        return topUp != null && QbTopUps.MANUAL.equals(topUp.get("method"))
            && TopUpStatus.REVIEW.equals(topUp.get("status")) && input.requestId() != null
            && input.requestId().equals(topUp.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance topUp = first(ctx, TOPUP);
        TransferProcesses.ApprovalResultInput input = approvalInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            if (TopUpCrediting.list(ctx, DECISIONS).stream()
                .anyMatch(decision -> topUp.get("sponsorId").equals(decision.get("approverId")))) {
                // Approved by the sponsor themselves (an administrator with a sponsor account): not credited, and
                // finance is told, to record it again for someone else to approve.
                ctx.put(SPONSOR_APPROVED, true);
                cancel(ctx, topUp);
                return;
            }
            BigDecimal amount = QbTopUps.money(topUp.get("amount"));
            boolean grant = QbTopUps.GRANT.equals(topUp.get("manualKind"));
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(),
                grant ? "Kudos granted" : "Manual top-up (paid outside)",
                REFERENCE_PREFIX + topUp.get("transferCode"), List.of(
                    new LedgerProcesses.Line(grant ? QbLedger.PROMOTION_EXPENSES : QbLedger.BANK, Direction.DEBIT,
                        amount),
                    new LedgerProcesses.Line(QbLedger.SPONSOR_PREPAYMENTS, Direction.CREDIT, amount, null,
                        Map.of(QbLedger.PARTY, topUp.get("sponsorId")))), QbTopUps.TOPUP,
                String.valueOf(topUp.id())));
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, topUp)) {
            // The reviewer's note stays with the approval.
            cancel(ctx, topUp);
        }
    }

    /** Cancelled; the statement's entry is let go, so that it may be recorded again. */
    private static void cancel(ProcessContext ctx, EntityInstance topUp) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", TopUpStatus.CANCELLED);
        values.put("receivedOn", null);
        values.put("bankReference", null);
        ctx.put(STATUS_AFTER, TopUpStatus.CANCELLED);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    /** Whether the stored request (not the event) says that the credit, as it is, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance topUp) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(topUp).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(topUp.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    static void credit(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        EntityInstance topUp = first(ctx, TOPUP);
        BigDecimal amount = QbTopUps.money(topUp.get("amount"));
        QbWallets.change(ctx, first(ctx, WALLET), QbWallets.SPONSOR, topUp.get("sponsorId"), amount, 0,
            BigDecimal.ZERO);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", TopUpStatus.PAID);
        values.put("paidAt", ctx.opTime());
        values.put("confirmedBy", "approval:" + approvalInput(ctx).requestId());
        values.put("ledgerTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        ctx.put(STATUS_AFTER, TopUpStatus.PAID);
        ctx.changes().update(QbTopUps.TOPUP, topUp.id(), topUp.version(), values);
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    /** Finance's task about a credit the sponsor approved themselves (keyed by the credit). */
    static TaskSpec sponsorApprovedTask(EntityInstance topUp) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", TopUpTasks.shown(topUp.get("transferCode")));
        params.put("amount", QbTopUps.money(topUp.get("amount")).toPlainString());
        return TaskSpec.forPermission(TASK_TYPE, "task.qb.topup-manual.approved-by-sponsor", params,
                QbPermissions.TOPUP_MANUAL_RECORD)
            .source("topup-manual:" + topUp.id()).about(QbTopUps.TOPUP, String.valueOf(topUp.id()));
    }

    private static RecordInput input(ProcessContext ctx) {
        return ctx.get(INPUT, RecordInput.class);
    }

    private static TransferProcesses.ApprovalResultInput approvalInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static ManualOutput output(ProcessContext ctx) {
        EntityInstance topUp = first(ctx, TOPUP);
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : topUp == null ? null : topUp.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : topUp == null ? null : topUp.get("approvalRequestId");
        return new ManualOutput(topUp == null ? null : String.valueOf(topUp.id()), status, requestId);
    }

    private ManualCredits() {}
}
