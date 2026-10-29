package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.event.DomainEvent;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.ledger.Direction;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.identity.QbAccounts;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.list;

/**
 * Automatic transfers of the takers' rewards to their Stripe accounts (U-53, P-03; docs/quizbuks/plans/
 * Q6-payments.md, D-Q6-12). Every process holds the taker's wallet lock ({@code qb.wallet:TAKER:<user>}) and no other,
 * reads everything again under it, and changes nothing when the state no longer fits.
 * <ul>
 *   <li>{@value #CREATE} (each item of the daily job {@value #RUN_JOB}): transfers the taker's whole balance, read
 *       from the ledger, when transfers are on, it reaches the threshold, the taker is not banned, their account is
 *       enabled, nothing of theirs is unfinished and their last transfers did not all fail. Above the review limit it
 *       waits for a person ({@code REVIEW}, the payee as the preparer: they cannot approve it); otherwise it is
 *       sent.</li>
 *   <li>Sending: debit 2120 (the taker) the amount / credit 2130 (the taker) the net and 4110 the fee, the job that
 *       calls Stripe ({@link StripeTransferHandler}: the group looked up first, the transfer's own key), the cache
 *       recomputed, {@value QbPayouts#CHANGED_EVENT}.</li>
 *   <li>{@value #APPROVAL_RESULT}: an approval of the transfer's own request, for its content, sends it if it still
 *       can (balance, account, ban, switch); a rejection rejects it. Nothing was posted for a transfer in review.</li>
 *   <li>{@value #SENT}: Stripe's transfer, checked against the record, settles it (debit 2130 / credit 1110).</li>
 *   <li>{@value #SEND_FAILED}: a refusal of the creation is certain (no transfer): the sending is reversed
 *       ({@code LEDGER_REVERSE}, the money back on 2120). Anything else leaves the transfer in doubt: {@code CHECK}, a
 *       task, nothing undone until Stripe is asked ({@value #CHECK} / {@value #CHECKED}, the hourly job
 *       {@value #STALE_JOB}).</li>
 *   <li>{@value #REVERSED} ({@code transfer.reversed}): what Stripe reversed comes back to the taker (debit 1110 /
 *       credit 2120), from the running total, once.</li>
 * </ul>
 */
public final class TransferProcesses {

    public static final String CREATE = "QB_TRANSFER_CREATE";
    public static final String APPROVAL_RESULT = "QB_TRANSFER_APPROVAL_RESULT";
    /** Q14: a finance administrator's transfer for one taker now, and what it would do. */
    public static final String NOW = "QB_TRANSFER_NOW";
    public static final String PREVIEW = "QB_TRANSFER_PREVIEW";
    public static final String SENT = "QB_TRANSFER_SENT";
    public static final String SEND_FAILED = "QB_TRANSFER_SEND_FAILED";
    public static final String CHECK = "QB_TRANSFER_CHECK";
    public static final String CHECKED = "QB_TRANSFER_CHECKED";
    public static final String CHECK_FAILED = "QB_TRANSFER_CHECK_FAILED";
    public static final String REVERSED = "QB_TRANSFER_REVERSED";
    public static final String REVERSED_CONSUMER = "qb.transfer-reversed";
    public static final String HOLD_RELEASE = "QB_TRANSFER_HOLD_RELEASE";

    /** The daily job and its selection; the hourly check of the transfers in doubt and its selection. */
    public static final String RUN_JOB = "qb.transfer-run";
    public static final String DUE_TEMPLATE = "qb.system.transfers-due";
    public static final String STALE_JOB = "qb.transfer-stale";
    public static final String STALE_TEMPLATE = "qb.system.transfers-stale";
    /**
     * The daily run's schedule (01:07 UTC, D-Q6-12) and its setting. Only the end-to-end tests set another one
     * (every minute); {@code TransferScheduleCheck} refuses any other next to live mode or a live key.
     */
    public static final String RUN_CRON = "0 7 1 * * *";
    public static final String RUN_CRON_SETTING = "quizbuks.transfers.run-cron";

    /** A transfer still sending after this long is asked about; Stripe is asked again after this long. */
    public static final Duration STALE_AFTER = Duration.ofHours(1);
    public static final Duration ASK_AGAIN_AFTER = Duration.ofMinutes(50);
    /**
     * A transfer in doubt is asked about this long after its doubt began at the earliest: far longer than any request
     * of its job may still take (a lease lost while a request was under way), so a lookup that finds nothing is final.
     */
    public static final Duration SETTLE_BEFORE_CHECK = Duration.ofMinutes(10);
    /** The most takers one daily run creates transfers for. */
    public static final int RUN_BATCH = 2000;
    public static final int STALE_BATCH = 500;

    public record UserRef(@NotBlank @Size(max = 128) String userId) {}

    public record TransferRef(@NotBlank @Size(max = 36) String transferId) {}

    /**
     * Input of {@value #APPROVAL_RESULT}, from an approval event of any subject.
     *
     * @param requestId the request decided: only the transfer's own request counts
     */
    public record ApprovalResultInput(@NotBlank String subject, @NotBlank String entityId, @NotBlank String status,
        String requestId, String preparerId, String contentHash, String reason) {}

    /**
     * @param status            the transfer's state after the process; null without a transfer
     * @param reason            why nothing was done, or the task's reason
     * @param approvalRequestId the review's request of a transfer waiting for one
     */
    public record TransferOutput(String transferId, String status, String reason, String approvalRequestId) {}

    /**
     * What the daily run would do for a taker now (Q14).
     *
     * @param transfers whether it would transfer; otherwise {@code skip} says why ({@link TransferRules})
     * @param review    whether the transfer would wait for a review (above {@code qb.transfer.review-above})
     * @param balance   the taker's rewards payable (2120) in the ledger
     * @param held      what of it is held back (the hold period, open disputes)
     */
    public record PreviewOutput(boolean transfers, String skip, BigDecimal amount, BigDecimal fee, BigDecimal net,
        boolean review, BigDecimal balance, BigDecimal held) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String BAN = "ban";
    static final String ACCOUNT = "account";
    static final String LATEST = "latest";
    static final String LEDGER = "ledger";
    static final String FOUND = "found";
    static final String TRANSFER = "transfer";
    static final String NEW_ID = "newId";
    static final String PLAN = "plan";
    static final String APPROVAL = "approval";
    static final String REQUEST = "request";
    static final String EVENT = "event";
    static final String SEND = "send";
    static final String SETTLE = "settle";
    static final String REVERSE = "reverse";
    static final String RETURN = "return";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String SETTLE_INPUT = "settleInput";
    static final String SETTLEMENT = "settlement";
    static final String REVERSE_INPUT = "reverseInput";
    static final String REVERSAL = "reversal";
    static final String RETURN_INPUT = "returnInput";
    static final String RETURNED = "returned";
    static final String STRIPE_TRANSFER = "stripeTransfer";
    static final String FAILURE = "failure";
    static final String CHANGED = "changed";
    static final String STATUS_AFTER = "statusAfter";
    static final String REASON = "reason";
    static final String REASON_AMOUNT = "reasonAmount";
    static final String SKIP = "skip";
    static final String ASK = "ask";
    static final String JOB_ID = "jobId";
    static final String HOLDS = "holds";
    static final String RULES = "rules";
    static final String REVIEW_TASKS = "reviewTasks";
    static final String PENDING_WRITE = "pendingWrite";
    static final String BANK_ACCOUNT = "bankAccount";

    /** The code of a transfer in doubt that Stripe was found not to have: reversed (not the taker's failure). */
    public static final String NOT_AT_STRIPE = "NOT_AT_STRIPE";
    /** The code of a transfer a reviewer rejected (their note stays with the approval). */
    public static final String REVIEW_REJECTED = "REVIEW_REJECTED";
    /** The most transfers one release reads (a taker held by more is released by more runs). */
    static final int HOLD_BATCH = 100;

    // ---- create ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<UserRef, TransferOutput, ProcessContext> create(StripeSettings stripe) {
        return ProcessDefinition.define(CREATE, 1, UserRef.class, TransferOutput.class, ProcessContext.class,
            pb -> pb
                .description("Transfers a taker's whole balance to their Stripe account, or asks for a review.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> createSteps(b, stripe, true)));
    }

    /**
     * A finance administrator's "transfer now" for one taker (Q14): the daily run's steps for that taker at once, with
     * all its checks (the switch, the threshold, the account, holds). Nothing is sent that the daily run would not send;
     * the output names the reason when nothing is. Never to oneself. Above the review limit it writes nothing
     * ({@link TransferRules#REVIEW_NOT_NOW}): the daily run asks for the review, as the payee's, so that the
     * administrator who would start it is not the one who could approve it (Q14 review H1).
     */
    public static ProcessDefinition<UserRef, TransferOutput, ProcessContext> now(StripeSettings stripe) {
        return ProcessDefinition.define(NOW, 1, UserRef.class, TransferOutput.class, ProcessContext.class,
            pb -> pb
                .description("Transfers a taker's balance to their Stripe account now, as the daily run would.")
                .permissions(QbPermissions.TRANSFER_NOW)
                .requiresVerifiedEmail()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .compute("Not to oneself", (metadata, ctx) -> {
                    if (ctx.request().actorId().equalsIgnoreCase(userOf(ctx).strip())) {
                        throw new BusinessRuleViolationException(List.of(new Violation(null,
                            WalletCodes.TRANSFER_NOW_OWN, "A transfer to oneself is made by another administrator",
                            Map.of())));
                    }
                })
                .steps(b -> createSteps(b, stripe, false)));
    }

    /**
     * What the daily run would do for a taker now, without doing it (Q14): the decision and its reason, the balance
     * and what is held back. Read without the wallet lock: a view, not a promise.
     */
    public static ProcessDefinition<UserRef, PreviewOutput, ProcessContext> preview() {
        return ProcessDefinition.define(PREVIEW, 1, UserRef.class, PreviewOutput.class, ProcessContext.class,
            pb -> pb
                .description("Says whether a taker would be transferred to now, and why not.")
                .permissions(QbPermissions.ADMIN_FINANCE_READ)
                .contextFactory(TransferProcesses::context)
                .outputMapper(ctx -> {
                    TransferRules.Plan plan = ctx.get(PLAN, TransferRules.Plan.class);
                    return new PreviewOutput(plan.transfers(), plan.skip(), plan.amount(), plan.fee(), plan.net(),
                        plan.review(), ledgerBalance(ctx), rewardsHeld(ctx));
                })
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TRANSFER_ENABLED, QbParams.TRANSFER_THRESHOLD, QbParams.TRANSFER_REVIEW_ABOVE,
                    QbParams.TRANSFER_FEE, QbParams.TRANSFER_HOLD_DAYS))
                .steps(b -> loadTaker(b, TransferProcesses::userOf))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> latest(userOf(ctx)), LATEST))
                .step("Load what holds the taker", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> holds(userOf(ctx)), HOLDS))
                .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("subject", QbPayouts.APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES))
                .steps(b -> loadBankAccount(b, TransferProcesses::userOf))
                .compute("Decide", (metadata, ctx) -> ctx.put(PLAN, planOf(ctx))));
    }

    /**
     * The daily run's steps for one taker: decide under their wallet lock, then send or (when {@code reviewable}) ask
     * for a review.
     */
    private static <I, O> void createSteps(ProcessDefinitionBuilder<I, O, ProcessContext> pb, StripeSettings stripe,
        boolean reviewable) {
        pb
                .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.TAKER, userOf(ctx))))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TRANSFER_ENABLED, QbParams.TRANSFER_THRESHOLD, QbParams.TRANSFER_REVIEW_ABOVE,
                    QbParams.TRANSFER_FEE, QbParams.TRANSFER_HOLD_DAYS))
                .steps(b -> loadTaker(b, TransferProcesses::userOf))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> latest(userOf(ctx)), LATEST))
                .step("Load what holds the taker", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> holds(userOf(ctx)), HOLDS))
                .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("subject", QbPayouts.APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES))
                .steps(b -> TakerWallets.load(b, TransferProcesses::userOf))
                .steps(b -> loadBankAccount(b, TransferProcesses::userOf))
                .compute("Decide", (metadata, ctx) -> plan(ctx, reviewable))
                // No rule: nothing is written for the taker; finance hears of it once a day, not once per taker.
                .step("Find the day's task about the rule", QueryEntities.<ProcessContext>of(TaskEntities.TASK_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("sourceKey", reviewMissing(ctx)
                        ? new ArrayList<>(List.of(PayoutTasks.reviewMissingSource(ctx.opTime())))
                        : new ArrayList<>())).limit(1).build(), REVIEW_TASKS))
                .step("Tell finance the rule is missing", CreateTask.<ProcessContext>when(
                    ctx -> reviewMissing(ctx) && list(ctx, REVIEW_TASKS).isEmpty(),
                    ctx -> PayoutTasks.reviewMissing(ctx.opTime())))
                .step("Save the transfer", SaveChanges.now())
                .step("Read the transfer", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> byId((String) ctx.get(NEW_ID)), TRANSFER))
                .step("Ask for a review", RequireApproval.<ProcessContext>when(
                    ctx -> first(ctx, TRANSFER) != null && TransferStatus.REVIEW.equals(first(ctx, TRANSFER)
                        .get("status")), QbPayouts.APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, TRANSFER)),
                    APPROVAL))
                .compute("Send or wait", (metadata, ctx) -> created(ctx))
                .steps(b -> sending(b, stripe))
                .steps(TransferProcesses::finish);
    }

    static void plan(ProcessContext ctx, boolean reviewable) {
        EntityInstance account = first(ctx, ACCOUNT);
        TransferRules.Plan plan = planOf(ctx);
        ctx.put(PLAN, plan);
        if (!plan.transfers()) {
            ctx.put(SKIP, plan.skip());
            return;
        }
        if (plan.review() && !reviewable) {
            ctx.put(SKIP, TransferRules.REVIEW_NOT_NOW);
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", userOf(ctx));
        values.put("method", QbPayouts.STRIPE);
        values.put("externalAccountId", account.get("externalAccountId"));
        values.put("amount", plan.amount());
        values.put("fee", plan.fee());
        values.put("net", plan.net());
        values.put("status", plan.review() ? TransferStatus.REVIEW : TransferStatus.SENDING);
        values.put("reversedAmount", BigDecimal.ZERO);
        values.put("createdAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbPayouts.TRANSFER, values)));
    }

    /** The rules' decision for the taker from what was read (the parameters, the taker, their transfers, the rule). */
    static TransferRules.Plan planOf(ProcessContext ctx) {
        EntityInstance bank = first(ctx, BANK_ACCOUNT);
        if (bank != null && QbPayouts.BANK.equals(bank.get("payoutMethod"))) {
            // Paid by bank (Q13b): the taker asks for each withdrawal; the daily run leaves them.
            return TransferRules.Plan.skip(TransferRules.BANK_METHOD);
        }
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        EntityInstance ban = first(ctx, BAN);
        EntityInstance account = first(ctx, ACCOUNT);
        List<TransferRules.Past> latest = past(list(ctx, LATEST));
        return TransferRules.plan(
            new TransferRules.Params(params.get(QbParams.TRANSFER_ENABLED, Boolean.class),
                params.get(QbParams.TRANSFER_THRESHOLD, BigDecimal.class),
                params.get(QbParams.TRANSFER_REVIEW_ABOVE, BigDecimal.class),
                params.get(QbParams.TRANSFER_FEE, BigDecimal.class),
                params.get(QbParams.TRANSFER_HOLD_DAYS, BigDecimal.class)),
            new TransferRules.Facts(BanRules.banned(ban == null ? null : ban.attributes()),
                account == null ? null : account.get("status"),
                latest.stream().anyMatch(t -> TransferStatus.UNFINISHED.contains(t.status())),
                TransferRules.failureStreak(latest), TransferRules.held(past(list(ctx, HOLDS))),
                first(ctx, RULES) != null, ledgerBalance(ctx), rewardsHeld(ctx)));
    }

    static void created(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer == null) {
            return;
        }
        if (TransferStatus.SENDING.equals(transfer.get("status"))) {
            ctx.put(SEND, true);
            return;
        }
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        switch (approval.status()) {
            case PENDING -> {
                write(ctx, transfer, Map.of("approvalRequestId", approval.requestId()));
                ctx.put(STATUS_AFTER, TransferStatus.REVIEW);
            }
            case APPROVED -> ctx.put(SEND, true);
            // A rule is in effect (plan) yet none asked for a review: its condition was changed. Never sent
            // unreviewed; the whole item fails and nothing is kept (the job's run shows it).
            case NOT_REQUIRED -> throw new IllegalStateException("The transfer review rule did not apply to "
                + transfer.id());
            default -> throw new IllegalStateException("Unknown approval outcome " + approval.status());
        }
    }

    // ---- approval result -------------------------------------------------------------------------------------------

    /**
     * Continues after a review is decided (subscribed to both approval events, run as the system). Only the transfer's
     * own request counts; an approval goes through only when {@link RequireApproval} finds that request approved for
     * the transfer's content, a rejection only when the stored request is rejected for it.
     */
    public static ProcessDefinition<ApprovalResultInput, TransferOutput, ProcessContext> approvalResult(
        StripeSettings stripe) {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sends or rejects a transfer after its review is decided.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> lockAndLoad(b, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    return QbPayouts.APPROVAL_SUBJECT.equals(input.subject()) ? input.entityId() : null;
                }))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TRANSFER_ENABLED, QbParams.TRANSFER_HOLD_DAYS, QbParams.WITHDRAWAL_BANK_ENABLED))
                .steps(b -> loadTaker(b, TransferProcesses::transferUser))
                .step("Load the request", QueryEntities.<ProcessContext>of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("requestId", ownRequest(ctx)
                        ? ids(approvalInput(ctx).requestId()) : new ArrayList<>())).limit(1).build(), REQUEST))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(approvalInput(ctx).status()),
                    QbPayouts.APPROVAL_SUBJECT,
                    ctx -> approvalCase(first(ctx, TRANSFER)).preparedBy(approvalInput(ctx).preparerId()), APPROVAL))
                .compute("Send or reject", (metadata, ctx) -> decided(ctx))
                .steps(b -> sending(b, stripe))
                .steps(TransferProcesses::finish)
                // An approved withdrawal (Q13b) is paid by finance by hand.
                .step("Ask finance to pay", CreateTask.<ProcessContext>when(
                    ctx -> ctx.contains(SEND) && bank(first(ctx, TRANSFER)),
                    ctx -> BankWithdrawals.payTask(first(ctx, TRANSFER)))));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        ApprovalResultInput input = approvalInput(ctx);
        return transfer != null && TransferStatus.REVIEW.equals(transfer.get("status")) && input.requestId() != null
            && input.requestId().equals(transfer.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance transfer = first(ctx, TRANSFER);
        ApprovalResultInput input = approvalInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            // Approved: sent only if it still can be, as it was when created (D-Q6-12): what is not held back
            // (Q10b, M3: a dispute opened during the review holds its sponsor's rewards) still covers it.
            EntityInstance account = first(ctx, ACCOUNT);
            EntityInstance ban = first(ctx, BAN);
            BigDecimal amount = QbPayouts.money(transfer.get("amount"));
            ParamValues params = ctx.get(PARAMS, ParamValues.class);
            boolean common = TransferRules.wholeAndNotNegative(params.get(QbParams.TRANSFER_HOLD_DAYS,
                    BigDecimal.class))
                && !BanRules.banned(ban == null ? null : ban.attributes())
                && TransferRules.transferable(ledgerBalance(ctx), rewardsHeld(ctx)).compareTo(amount) >= 0;
            // A withdrawal goes to the bank account copied when it was asked for (Q13b): finance pays it by hand.
            boolean sendable = common && (bank(transfer)
                ? Boolean.TRUE.equals(params.get(QbParams.WITHDRAWAL_BANK_ENABLED, Boolean.class))
                : Boolean.TRUE.equals(params.get(QbParams.TRANSFER_ENABLED, Boolean.class))
                    && account != null && PayoutAccountStatus.ENABLED.equals(account.get("status"))
                    && transfer.get("externalAccountId").equals(account.get("externalAccountId")));
            if (sendable) {
                ctx.put(SEND, true);
            } else {
                reject(ctx, transfer, PayoutChecks.NOT_SENDABLE);
            }
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, transfer)) {
            // The reviewer's note stays with the approval (finance's): the taker reads their transfer.
            reject(ctx, transfer, null);
        }
    }

    /** Whether the stored request (not the event) says that the transfer, as it is, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance transfer) {
        EntityInstance request = first(ctx, REQUEST);
        if (request == null) {
            return false;
        }
        String hash = com.jabiz.approval.ContentHash.of(approvalCase(transfer).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && QbPayouts.APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(transfer.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    /**
     * The review's case: the facts the rule tests, and the content approved (who, how much, to which account). The
     * payee is the preparer, so the platform refuses their own approval ({@code APPROVAL_OWN_REQUEST}).
     */
    static ApprovalCase approvalCase(EntityInstance transfer) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("needsReview", true);
        facts.put("amount", QbPayouts.money(transfer.get("amount")));
        facts.put("userId", transfer.get("userId"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("transferId", String.valueOf(transfer.id()));
        content.put("userId", transfer.get("userId"));
        content.put("externalAccountId", transfer.get("externalAccountId"));
        content.put("amount", QbPayouts.money(transfer.get("amount")));
        content.put("fee", QbPayouts.money(transfer.get("fee")));
        content.put("net", QbPayouts.money(transfer.get("net")));
        if (bank(transfer)) {
            // The account the money goes to, as the reviewer sees it: the number masked as the platform masks it (a
            // 7-digit number entirely: its last four would give most of it away); the copy on the transfer, which the
            // approval belongs to, never changes.
            content.put("method", QbPayouts.BANK);
            content.put("bank", transfer.get("bankCode") + " " + transfer.get("bankName"));
            content.put("branch", transfer.get("branchCode") + " " + transfer.get("branchName"));
            content.put("accountType", transfer.get("accountType"));
            content.put("accountNumber", com.jabiz.entity.MaskStyle.LAST4.apply(transfer.get("accountNumber")));
            content.put("accountHolder", transfer.get("accountHolder"));
        }
        return ApprovalCase.of(transfer.id(), facts, content).preparedBy(transfer.get("userId"))
            .reference(String.valueOf(transfer.id()));
    }

    // ---- sending (create and approval) -----------------------------------------------------------------------------

    private static <I, O> void sending(ProcessDefinitionBuilder<I, O, ProcessContext> pb, StripeSettings stripe) {
        pb.compute("Prepare the sending", (metadata, ctx) -> {
                if (!ctx.contains(SEND)) {
                    return;
                }
                EntityInstance transfer = first(ctx, TRANSFER);
                String userId = transfer.get("userId");
                BigDecimal fee = QbPayouts.money(transfer.get("fee"));
                List<LedgerProcesses.Line> lines = new ArrayList<>();
                lines.add(new LedgerProcesses.Line(QbLedger.REWARDS_PAYABLE, Direction.DEBIT,
                    QbPayouts.money(transfer.get("amount")), null, Map.of(QbLedger.PARTY, userId)));
                lines.add(new LedgerProcesses.Line(QbLedger.TRANSFERS_IN_TRANSIT, Direction.CREDIT,
                    QbPayouts.money(transfer.get("net")), null, Map.of(QbLedger.PARTY, userId)));
                if (fee.signum() > 0) {
                    lines.add(new LedgerProcesses.Line(QbLedger.SERVICE_REVENUE, Direction.CREDIT, fee));
                }
                ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Transfer",
                    String.valueOf(transfer.id()), lines, QbPayouts.TRANSFER, String.valueOf(transfer.id())));
            })
            .step("Post the sending", CallProcess.<ProcessContext>when(ctx -> ctx.contains(SEND),
                LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
            .compute("Record the sending", (metadata, ctx) -> {
                if (!ctx.contains(SEND)) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", TransferStatus.SENDING);
                values.put("sendTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
                write(ctx, first(ctx, TRANSFER), values);
                ctx.put(STATUS_AFTER, TransferStatus.SENDING);
            })
            .step("Send it", StartExternalJob.<ProcessContext>of(PayoutJobs.TRANSFER, ctx -> {
                    EntityInstance transfer = first(ctx, TRANSFER);
                    return new PayoutJobs.TransferInput(String.valueOf(transfer.id()),
                        transfer.get("externalAccountId"), QbPayouts.money(transfer.get("net")));
                })
                .reference(ctx -> String.valueOf(first(ctx, TRANSFER).id()))
                // A withdrawal waits in SENDING for finance to pay it by hand (Q13b): nothing goes to Stripe.
                .when(ctx -> ctx.contains(SEND) && !bank(first(ctx, TRANSFER)))
                .outputKey(JOB_ID));
    }

    /** Whether the transfer is a withdrawal to a bank account (Q13b). */
    static boolean bank(EntityInstance transfer) {
        return transfer != null && QbPayouts.BANK.equals(transfer.get("method"));
    }

    /** The taker's bank account, if any (Q13b): how they are paid. */
    static <I, O> void loadBankAccount(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId) {
        pb.step("Load the bank account", QueryEntities.<ProcessContext>of(
            QbPayouts.defaultDataset(QbPayouts.BANK_ACCOUNT), ctx -> EntityQuery.builder().where(
                new QueryPredicate.In("userId", userIds(userId.apply(ctx)))).limit(1).build(), BANK_ACCOUNT));
    }

    // ---- the job's result ------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExternalJobCompletion, TransferOutput, ProcessContext> sent(
        StripeSettings stripe) {
        return ProcessDefinition.define(SENT, 1, ExternalJobCompletion.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Settles a transfer Stripe made.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobCompletion.class).reference()))
                .compute("Note the sending's end", (metadata, ctx) -> sendEnded(ctx))
                .compute("Check Stripe's transfer", (metadata, ctx) -> found(ctx, stripe,
                    ctx.get(INPUT, ExternalJobCompletion.class).result(PayoutObjects.TransferLookup.class), false,
                    "sent"))
                .steps(TransferProcesses::settling)
                .steps(TransferProcesses::reversing)
                .steps(TransferProcesses::finish));
    }

    public static ProcessDefinition<ExternalJobFailure, TransferOutput, ProcessContext> sendFailed() {
        return ProcessDefinition.define(SEND_FAILED, 1, ExternalJobFailure.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Reverses a transfer Stripe refused, or holds one in doubt until Stripe is asked.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobFailure.class).reference()))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> latest(transferUser(ctx)), LATEST))
                .compute("Note the sending's end", (metadata, ctx) -> sendEnded(ctx))
                .compute("Decide", (metadata, ctx) -> {
                    EntityInstance transfer = first(ctx, TRANSFER);
                    if (transfer == null || !TransferStatus.SENDING.equals(transfer.get("status"))) {
                        return;
                    }
                    ExternalJobFailure job = ctx.get(INPUT, ExternalJobFailure.class);
                    if (TransferRules.certainFailure(job.errorCode())) {
                        failed(ctx, transfer, job.errorCode(), job.errorMessage());
                    } else {
                        doubt(ctx, transfer, job.errorCode(), job.errorMessage());
                    }
                })
                .steps(TransferProcesses::settling)
                .steps(TransferProcesses::reversing)
                .steps(TransferProcesses::finish));
    }

    // ---- the check of a transfer in doubt --------------------------------------------------------------------------

    public static ProcessDefinition<TransferRef, TransferOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK, 1, TransferRef.class, TransferOutput.class, ProcessContext.class,
            pb -> pb
                .description("Asks Stripe whether a transfer in doubt was made.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, TransferRef.class).transferId()))
                .compute("Decide whether to ask", (metadata, ctx) -> {
                    EntityInstance transfer = first(ctx, TRANSFER);
                    if (transfer == null || !inDoubt(transfer, ctx.opTime())) {
                        return;
                    }
                    ctx.put(ASK, true);
                    write(ctx, transfer, Map.of("checkRequestedAt", ctx.opTime()));
                })
                .step("Ask Stripe", StartExternalJob.<ProcessContext>of(PayoutJobs.TRANSFER_CHECK, ctx -> {
                        EntityInstance transfer = first(ctx, TRANSFER);
                        return new PayoutJobs.TransferInput(String.valueOf(transfer.id()),
                            transfer.get("externalAccountId"), QbPayouts.money(transfer.get("net")));
                    })
                    .reference(ctx -> String.valueOf(first(ctx, TRANSFER).id()))
                    .when(ctx -> ctx.contains(ASK))
                    .outputKey(JOB_ID))
                .compute("Save the transfer's changes", (metadata, ctx) -> save(ctx)));
    }

    /**
     * In doubt and not asked about within {@link #ASK_AGAIN_AFTER}: a transfer in {@code CHECK} for at least
     * {@link #SETTLE_BEFORE_CHECK} (so that no request of its job can still be under way, and Stripe's list shows what
     * was made), or one still {@code SENDING} after {@link #STALE_AFTER} (its job stuck). One whose transfer at Stripe
     * does not fit the record is a person's: Stripe would only say the same again.
     */
    static boolean inDoubt(EntityInstance transfer, Instant now) {
        if (bank(transfer)) {
            return false;
        }
        Instant asked = transfer.get("checkRequestedAt");
        if (asked != null && asked.isAfter(now.minus(ASK_AGAIN_AFTER))) {
            return false;
        }
        if (PayoutChecks.TRANSFER_MISMATCH.equals(transfer.get("failureCode"))) {
            return false;
        }
        String status = transfer.get("status");
        Instant created = transfer.get("createdAt");
        return (TransferStatus.CHECK.equals(status) && settledBefore(transfer, now))
            || (TransferStatus.SENDING.equals(status) && created != null && created.isBefore(now.minus(STALE_AFTER)));
    }

    /**
     * Whether the transfer's sending job ended at least {@link #SETTLE_BEFORE_CHECK} before {@code time}: no request
     * of it can still reach Stripe after that, so a lookup from then on that finds nothing is final.
     */
    static boolean settledBefore(EntityInstance transfer, Instant time) {
        Instant ended = transfer.get("sendEndedAt");
        return ended != null && time != null && !time.isBefore(ended.plus(SETTLE_BEFORE_CHECK));
    }

    /** The sending job reported (its result or its failure): when, the first time. */
    static void sendEnded(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer != null && transfer.get("sendEndedAt") == null) {
            write(ctx, transfer, Map.of("sendEndedAt", ctx.opTime()));
        }
    }

    public static ProcessDefinition<ExternalJobCompletion, TransferOutput, ProcessContext> checked(
        StripeSettings stripe) {
        return ProcessDefinition.define(CHECKED, 1, ExternalJobCompletion.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Settles or reverses a transfer in doubt as Stripe says.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, ExternalJobCompletion.class).reference()))
                .compute("Decide", (metadata, ctx) -> found(ctx, stripe,
                    ctx.get(INPUT, ExternalJobCompletion.class).result(PayoutObjects.TransferLookup.class), true,
                    "checked"))
                .steps(TransferProcesses::settling)
                .steps(TransferProcesses::reversing)
                .steps(TransferProcesses::finish));
    }

    public static ProcessDefinition<ExternalJobFailure, TransferOutput, ProcessContext> checkFailed() {
        return ProcessDefinition.define(CHECK_FAILED, 1, ExternalJobFailure.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Asks a person to look at a transfer Stripe could not be asked about.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .step("Find the transfer", QueryEntities.<ProcessContext>of(QbPayouts.defaultDataset(QbPayouts.TRANSFER),
                    ctx -> byId(ctx.get(INPUT, ExternalJobFailure.class).reference()), TRANSFER))
                .compute("Note it", (metadata, ctx) -> {
                    ctx.put(REASON, PayoutChecks.UNCERTAIN);
                    EntityInstance transfer = first(ctx, TRANSFER);
                    ctx.put(REASON_AMOUNT, transfer == null ? null : QbPayouts.money(transfer.get("net")));
                })
                .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                    ExternalJobFailure job = ctx.get(INPUT, ExternalJobFailure.class);
                    return PayoutTasks.transfer(PayoutChecks.UNCERTAIN, job.reference(),
                        (BigDecimal) ctx.get(REASON_AMOUNT), PayoutTasks.transferSource(job.reference(),
                            "check-failed:" + job.jobId()), null);
                })));
    }

    /**
     * What a job learned about the transfer: Stripe's transfer of the record is settled; one that does not fit holds
     * it in doubt (a task); none, after the check of a transfer whose sending job ended ({@code CHECK}), means none
     * was made: reversed. A transfer still sending (its job may still run) is left as it is.
     */
    static void found(ProcessContext ctx, StripeSettings stripe, PayoutObjects.TransferLookup lookup,
        boolean fromCheck, String what) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer == null) {
            return;
        }
        String status = transfer.get("status");
        if (!TransferStatus.SENDING.equals(status) && !TransferStatus.CHECK.equals(status)) {
            return;
        }
        if (lookup == null) {
            doubt(ctx, transfer, StripeErrors.BAD_RESPONSE, "The job's result is missing");
            return;
        }
        if (!lookup.found()) {
            if (fromCheck && TransferStatus.CHECK.equals(status)) {
                // Final only for a lookup well after the sending ended: a check started while the transfer was
                // still sending may have asked before its last attempt created it. Otherwise the next check asks.
                if (settledBefore(transfer, lookup.lookedUpFrom())) {
                    failed(ctx, transfer, NOT_AT_STRIPE, "Stripe has no transfer of the group");
                }
            } else if (!fromCheck) {
                doubt(ctx, transfer, StripeErrors.BAD_RESPONSE, "Stripe reported no transfer");
            }
            return;
        }
        String problem = PayoutChecks.transferProblem(String.valueOf(transfer.id()),
            QbPayouts.money(transfer.get("net")), transfer.get("externalAccountId"), lookup.transfer(),
            stripe.livemode());
        if (problem != null) {
            if (TransferStatus.SENDING.equals(status)) {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", TransferStatus.CHECK);
                values.put("failureCode", problem);
                values.put("failedAt", ctx.opTime());
                write(ctx, transfer, values);
                ctx.put(STATUS_AFTER, TransferStatus.CHECK);
            }
            ctx.put(REASON, problem);
            ctx.put(REASON_AMOUNT, QbPayouts.money(transfer.get("net")));
            return;
        }
        ctx.put(SETTLE, true);
        ctx.put(STRIPE_TRANSFER, lookup.transfer().id());
        String userId = transfer.get("userId");
        BigDecimal net = QbPayouts.money(transfer.get("net"));
        ctx.put(SETTLE_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Transfer settled",
            TopUpCrediting.reference(lookup.transfer().id()), List.of(
                new LedgerProcesses.Line(QbLedger.TRANSFERS_IN_TRANSIT, Direction.DEBIT, net, null,
                    Map.of(QbLedger.PARTY, userId)),
                new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.CREDIT, net)),
            QbPayouts.TRANSFER, String.valueOf(transfer.id())));
    }

    /** Stripe certainly made no transfer: the sending is reversed and the money is back on the taker's balance. */
    static void failed(ProcessContext ctx, EntityInstance transfer, String code, String message) {
        ctx.put(REVERSE, true);
        ctx.put(FAILURE, new String[] {clip(code, 100), clip(message, 200)});
        ctx.put(REVERSE_INPUT, new LedgerProcesses.ReverseInput(transfer.get("sendTransactionId"),
            "Transfer failed: " + clip(code, 100), ctx.opTime()));
    }

    /** Whether Stripe made the transfer is not known: held, a task, nothing undone. */
    static void doubt(ProcessContext ctx, EntityInstance transfer, String code, String message) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!TransferStatus.CHECK.equals(transfer.get("status"))) {
            values.put("status", TransferStatus.CHECK);
            ctx.put(STATUS_AFTER, TransferStatus.CHECK);
        }
        values.put("failureCode", clip(code, 100));
        values.put("failureMessage", clip(message, 200));
        // When the doubt began: the check waits a little after it (inDoubt).
        values.put("failedAt", ctx.opTime());
        write(ctx, transfer, values);
        ctx.put(REASON, PayoutChecks.UNCERTAIN);
        ctx.put(REASON_AMOUNT, QbPayouts.money(transfer.get("net")));
    }

    static <I, O> void settling(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Post the settlement", CallProcess.<ProcessContext>when(ctx -> ctx.contains(SETTLE),
                LedgerProcesses.POST, 1, ctx -> ctx.get(SETTLE_INPUT), SETTLEMENT))
            .compute("Record the settlement", (metadata, ctx) -> {
                if (!ctx.contains(SETTLE)) {
                    return;
                }
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", TransferStatus.SENT);
                values.put("stripeTransferId", ctx.get(STRIPE_TRANSFER));
                values.put("settleTransactionId", ctx.get(SETTLEMENT, LedgerProcesses.PostOutput.class)
                    .transactionId());
                values.put("sentAt", ctx.opTime());
                values.put("failureCode", null);
                values.put("failureMessage", null);
                write(ctx, first(ctx, TRANSFER), values);
                ctx.put(STATUS_AFTER, TransferStatus.SENT);
            });
    }

    static <I, O> void reversing(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Reverse the sending", CallProcess.<ProcessContext>when(ctx -> ctx.contains(REVERSE),
                LedgerProcesses.REVERSE, 1, ctx -> ctx.get(REVERSE_INPUT), REVERSAL))
            .compute("Record the failure", (metadata, ctx) -> {
                if (!ctx.contains(REVERSE)) {
                    return;
                }
                String[] failure = (String[]) ctx.get(FAILURE);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("status", TransferStatus.FAILED);
                values.put("reversalTransactionId", ctx.get(REVERSAL, LedgerProcesses.ReverseOutput.class)
                    .transactionId());
                values.put("failureCode", failure[0]);
                values.put("failureMessage", failure[1]);
                values.put("failedAt", ctx.opTime());
                EntityInstance transfer = first(ctx, TRANSFER);
                write(ctx, transfer, values);
                ctx.put(STATUS_AFTER, TransferStatus.FAILED);
                // The latest transfers were read before this one failed: it is the newest of them.
                List<TransferRules.Past> latest = new ArrayList<>();
                latest.add(new TransferRules.Past(TransferStatus.FAILED, failure[0], BigDecimal.ZERO, null));
                latest.addAll(past(list(ctx, LATEST).stream().filter(t -> !t.id().equals(transfer.id())).toList()));
                if (TransferRules.ACCOUNT_FAILURES.contains(failure[0])
                    && TransferRules.failureStreak(latest) == TransferRules.MAX_FAILURE_STREAK) {
                    ctx.put(REASON, PayoutChecks.FAILURE_STREAK);
                    ctx.put(REASON_AMOUNT, QbPayouts.money(transfer.get("net")));
                }
            });
    }

    // ---- releasing a held taker ------------------------------------------------------------------------------------

    /**
     * Releases a taker held after a rejected or reversed transfer ({@link TransferRules#HELD}), once finance has looked:
     * the next daily run transfers their balance again. Records who released them on each transfer that held them.
     */
    public static ProcessDefinition<UserRef, HoldReleaseOutput, ProcessContext> holdRelease() {
        return ProcessDefinition.define(HOLD_RELEASE, 1, UserRef.class, HoldReleaseOutput.class, ProcessContext.class,
            pb -> pb
                .description("Lets transfers to a taker held after a rejected or reversed transfer go on.")
                .permissions(QbPermissions.PAYOUT_REVIEW)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(TransferProcesses::context)
                .outputMapper(ctx -> {
                    List<EntityInstance> held = list(ctx, HOLDS);
                    return new HoldReleaseOutput(userOf(ctx), held.isEmpty() ? null
                        : String.valueOf(held.getFirst().id()), held.size());
                })
                .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.TAKER, userOf(ctx))))
                .step("Load what holds the taker", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> holds(userOf(ctx)), HOLDS))
                .compute("Release", (metadata, ctx) -> {
                    List<EntityInstance> held = list(ctx, HOLDS);
                    if (userOf(ctx).equals(ctx.request().actorId())) {
                        // Four eyes: the payee never releases their own money (as they never approve it).
                        throw new BusinessRuleViolationException(List.of(new Violation("userId",
                            WalletCodes.TRANSFER_RELEASE_OWN, "Transfers to yourself are released by someone else",
                            Map.of())));
                    }
                    if (held.isEmpty()) {
                        throw new BusinessRuleViolationException(List.of(new Violation("userId",
                            WalletCodes.TRANSFER_NOT_HELD, "Transfers to this taker are not held", Map.of())));
                    }
                    for (EntityInstance transfer : held) {
                        Map<String, Object> values = new LinkedHashMap<>();
                        values.put("releasedBy", ctx.request().actorId());
                        values.put("releasedAt", ctx.opTime());
                        ctx.changes().update(QbPayouts.TRANSFER, transfer.id(), transfer.version(), values);
                    }
                }));
    }

    /**
     * @param transferId the newest transfer that held the taker
     * @param released   how many transfers held them
     */
    public record HoldReleaseOutput(String userId, String transferId, int released) {}

    // ---- Stripe's reversal -----------------------------------------------------------------------------------------

    public static ProcessDefinition<TopUpEvents.InboundRef, TransferOutput, ProcessContext> reversed(
        StripeSettings stripe) {
        return ProcessDefinition.define(REVERSED, 1, TopUpEvents.InboundRef.class, TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Returns what Stripe reversed of a transfer to the taker's balance.")
                .permissions(QbPermissions.PAYOUT_SYSTEM)
                .internal()
                .contextFactory(TransferProcesses::context)
                .outputMapper(TransferProcesses::output)
                .step("Load the event", LoadInboundEvent.of(StripeService.PROVIDER, StripeService.TRANSFER_REVERSED,
                    ctx -> ctx.get(INPUT, TopUpEvents.InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the transfer", (metadata, ctx) -> ctx.put(STRIPE_TRANSFER,
                    PayoutObjects.transfer(event(ctx).at("data.object"))))
                .steps(b -> lockAndLoad(b, ctx -> {
                    PayoutObjects.Transfer reported = reported(ctx);
                    return StripeIds.valid(StripeIds.TOPUP, reported.metadataTransferId());
                }))
                .compute("Decide", (metadata, ctx) -> reversal(ctx, stripe))
                .step("Post the return", CallProcess.<ProcessContext>when(ctx -> ctx.contains(RETURN),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(RETURN_INPUT), RETURNED))
                .compute("Record the return", (metadata, ctx) -> {
                    if (!ctx.contains(RETURN)) {
                        return;
                    }
                    EntityInstance transfer = first(ctx, TRANSFER);
                    BigDecimal total = (BigDecimal) ctx.get(RETURN);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("reversedAmount", total);
                    if (total.compareTo(QbPayouts.money(transfer.get("net"))) == 0) {
                        values.put("status", TransferStatus.REVERSED);
                        ctx.put(STATUS_AFTER, TransferStatus.REVERSED);
                    }
                    write(ctx, transfer, values);
                })
                .steps(TransferProcesses::finish));
    }

    private static PayoutObjects.Transfer reported(ProcessContext ctx) {
        return ctx.get(STRIPE_TRANSFER, PayoutObjects.Transfer.class);
    }

    static void reversal(ProcessContext ctx, StripeSettings stripe) {
        ReceivedEvent event = event(ctx);
        PayoutObjects.Transfer reported = reported(ctx);
        EntityInstance transfer = first(ctx, TRANSFER);
        String source = TopUpTasks.eventSource(event.externalEventId());
        // The transfer is the record whose Stripe transfer this is (the metadata only finds it).
        boolean ours = transfer != null && StripeIds.is(StripeIds.TRANSFER, reported.id())
            && reported.id().equals(transfer.get("stripeTransferId"));
        if (!ours) {
            Instant received = event.receivedTime() == null ? ctx.opTime() : event.receivedTime();
            if (transfer != null && transfer.get("stripeTransferId") == null
                && ctx.opTime().isBefore(received.plus(WalletLimits.UNKNOWN_PAYMENT_GRACE))) {
                // Its settlement may not be processed yet (events and results come in any order): tried again.
                throw new IllegalStateException("The transfer is not settled yet");
            }
            reasonTask(ctx, PayoutChecks.UNKNOWN_TRANSFER, reported.amountReversed(), source);
            return;
        }
        if (!Boolean.valueOf(stripe.livemode()).equals(event.livemode())
            || !Boolean.valueOf(stripe.livemode()).equals(reported.livemode())) {
            reasonTask(ctx, PayoutChecks.LIVEMODE, reported.amountReversed(), source);
            return;
        }
        if (!StripeService.CURRENCY.equals(reported.currency())) {
            reasonTask(ctx, PayoutChecks.CURRENCY, reported.amountReversed(), source);
            return;
        }
        BigDecimal net = QbPayouts.money(transfer.get("net"));
        BigDecimal recorded = QbPayouts.money(transfer.get("reversedAmount"));
        BigDecimal part = TransferRules.reversalPart(net, recorded, reported.amountReversed());
        if (part == null) {
            reasonTask(ctx, PayoutChecks.OVER_REVERSAL, reported.amountReversed(), source);
            return;
        }
        if (part.signum() == 0) {
            return;
        }
        String userId = transfer.get("userId");
        ctx.put(RETURN, recorded.add(part));
        ctx.put(RETURN_INPUT, new LedgerProcesses.PostInput(TopUpEvents.happened(event), "Transfer reversed",
            TopUpCrediting.reference(reported.id()), List.of(
                new LedgerProcesses.Line(QbLedger.STRIPE_BALANCE, Direction.DEBIT, part),
                new LedgerProcesses.Line(QbLedger.REWARDS_PAYABLE, Direction.CREDIT, part, null,
                    Map.of(QbLedger.PARTY, userId))),
            QbPayouts.TRANSFER, String.valueOf(transfer.id())));
        ctx.put(CHANGED, true);
        reasonTask(ctx, PayoutChecks.REVERSED, part, source);
    }

    private static void reasonTask(ProcessContext ctx, String reason, BigDecimal amount, String source) {
        ctx.put(REASON, reason);
        ctx.put(REASON_AMOUNT, amount);
        ctx.put("reasonSource", source);
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /** Finds the transfer, locks its taker's wallet, reads the transfer and the wallet again. */
    static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, String> transferId) {
        pb.step("Find the transfer", QueryEntities.<ProcessContext>of(QbPayouts.defaultDataset(QbPayouts.TRANSFER),
                ctx -> byId(transferId.apply(ctx)), FOUND))
            .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(ctx -> {
                EntityInstance found = first(ctx, FOUND);
                return found == null ? null : QbWallets.lockName(QbWallets.TAKER, found.get("userId"));
            }))
            .step("Read the transfer again", QueryEntities.<ProcessContext>of(
                QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> byId(transferId.apply(ctx)), TRANSFER))
            .steps(b -> TakerWallets.load(b, TransferProcesses::transferUser));
    }

    /**
     * The taker's ban, payout account, ledger balance and the rewards held back (under their wallet lock; Q10b, M3:
     * {@value TakerWallets#TRANSFERABLE_TEMPLATE}, after the parameters are loaded).
     */
    static <I, O> void loadTaker(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, Object> userId) {
        pb.step("Load the taker's ban", QueryEntities.<ProcessContext>of(QbAccounts.defaultDataset(QbAccounts.BAN),
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("userId", uuids(userId.apply(ctx))))
                    .limit(1).build(), BAN))
            .step("Load the payout account", QueryEntities.<ProcessContext>of(
                QbPayouts.defaultDataset(QbPayouts.ACCOUNT), ctx -> PayoutProcesses.byUser(userId.apply(ctx)),
                ACCOUNT))
            .step("Read the taker's balance", RunTemplate.<ProcessContext>of(TakerWallets.TRANSFERABLE_TEMPLATE,
                ctx -> {
                    BigDecimal days = ctx.get(PARAMS, ParamValues.class).get(QbParams.TRANSFER_HOLD_DAYS,
                        BigDecimal.class);
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("userId", String.valueOf(userId.apply(ctx)));
                    params.put("at", ctx.opTime());
                    // A missing or unusable period refuses the transfer (TransferRules); the read still needs one.
                    params.put("holdDays", TransferRules.wholeAndNotNegative(days) ? days : BigDecimal.ZERO);
                    return params;
                }, LEDGER));
    }

    /**
     * The end of every transfer process: the taker's wallet recomputed from the ledger when the transfer moved money,
     * the change announced (and its outcome, for Q7), a task when there is a reason.
     */
    static <I, O> void finish(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.compute("Save the transfer's changes", (metadata, ctx) -> save(ctx))
            .compute("Note what changed", (metadata, ctx) -> {
                if (ctx.contains(SEND) || ctx.contains(SETTLE) || ctx.contains(REVERSE) || ctx.contains(RETURN)) {
                    ctx.put(CHANGED, true);
                }
            })
            .steps(b -> TakerWallets.refresh(b, TransferProcesses::transferUser, ctx -> ctx.contains(CHANGED)))
            .step("Announce the change", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(CHANGED)
                || ctx.contains(STATUS_AFTER), QbPayouts.CHANGED_EVENT, TransferProcesses::payload))
            .step("Announce the transfer", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(SETTLE),
                QbPayouts.SENT_EVENT, TransferProcesses::payload))
            .step("Announce the failure", PublishEvent.<ProcessContext>when(ctx -> ctx.contains(REVERSE),
                QbPayouts.FAILED_EVENT, TransferProcesses::payload))
            .step("Ask a person", CreateTask.<ProcessContext>when(ctx -> ctx.contains(REASON), ctx -> {
                EntityInstance transfer = first(ctx, TRANSFER);
                String transferId = transfer == null ? null : String.valueOf(transfer.id());
                String source = ctx.contains("reasonSource") ? (String) ctx.get("reasonSource")
                    : PayoutTasks.transferSource(String.valueOf(transferId), (String) ctx.get(REASON));
                ReceivedEvent event = ctx.contains(EVENT) ? event(ctx) : null;
                return PayoutTasks.transfer((String) ctx.get(REASON), transferId, (BigDecimal) ctx.get(REASON_AMOUNT),
                    source, event == null ? null : event.inboundEventId());
            }));
    }

    private static Map<String, Object> payload(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", String.valueOf(transfer.id()));
        payload.put("userId", transfer.get("userId"));
        payload.put("status", ctx.contains(STATUS_AFTER) ? ctx.get(STATUS_AFTER) : transfer.get("status"));
        payload.put("net", QbPayouts.money(transfer.get("net")).toPlainString());
        return payload;
    }

    /** Rejected by the reviewer (no reason), or for a reason found after the approval (a task). */
    private static void reject(ProcessContext ctx, EntityInstance transfer, String reason) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", TransferStatus.REJECTED);
        values.put("failureCode", reason == null ? REVIEW_REJECTED : reason);
        values.put("failedAt", ctx.opTime());
        write(ctx, transfer, values);
        ctx.put(STATUS_AFTER, TransferStatus.REJECTED);
        if (reason != null) {
            ctx.put(REASON, reason);
            ctx.put(REASON_AMOUNT, QbPayouts.money(transfer.get("amount")));
        }
    }

    /**
     * Notes changes of the process's transfer; {@link #save} registers them as one update (several steps may change
     * it, and each update is checked against the version read).
     */
    @SuppressWarnings("unchecked")
    static void write(ProcessContext ctx, EntityInstance transfer, Map<String, Object> values) {
        Map<String, Object> pending = ctx.contains(PENDING_WRITE) ? (Map<String, Object>) ctx.get(PENDING_WRITE)
            : new LinkedHashMap<>();
        pending.putAll(values);
        ctx.put(PENDING_WRITE, pending);
    }

    @SuppressWarnings("unchecked")
    static void save(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer == null || !ctx.contains(PENDING_WRITE)) {
            return;
        }
        ctx.changes().update(QbPayouts.TRANSFER, transfer.id(), transfer.version(),
            (Map<String, Object>) ctx.get(PENDING_WRITE));
        ctx.put(PENDING_WRITE, null);
    }

    /** The latest transfers of a taker, newest first (the unfinished one, if any, is among them). */
    static EntityQuery latest(Object userId) {
        return EntityQuery.builder().where(new QueryPredicate.In("userId", userIds(userId)))
            .orderBy("createdAt", false).limit(TransferRules.LATEST).build();
    }

    /** The taker's transfers that hold them (rejected, or reversed in whole or part) and were not released. */
    static EntityQuery holds(Object userId) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.In("userId", userIds(userId)),
            new QueryPredicate.IsNull("releasedAt"),
            new QueryPredicate.Or(List.of(new QueryPredicate.Eq("status", TransferStatus.REJECTED),
                new QueryPredicate.Gt("reversedAmount", BigDecimal.ZERO))))))
            .orderBy("createdAt", false).limit(HOLD_BATCH).build();
    }

    static List<Object> userIds(Object userId) {
        List<Object> ids = new ArrayList<>();
        if (userId != null) {
            ids.add(String.valueOf(userId));
        }
        return ids;
    }

    static List<TransferRules.Past> past(List<EntityInstance> transfers) {
        return transfers.stream().map(t -> new TransferRules.Past(t.get("status"), t.get("failureCode"),
            QbPayouts.money(t.get("reversedAmount")), t.get("releasedAt"))).toList();
    }

    private static boolean reviewMissing(ProcessContext ctx) {
        return TransferRules.REVIEW_NOT_CONFIGURED.equals(ctx.get(SKIP));
    }

    static EntityQuery byId(String transferId) {
        return EntityQuery.builder().where(new QueryPredicate.In("transferId", ids(transferId))).limit(1).build();
    }

    static List<Object> ids(String id) {
        List<Object> ids = new ArrayList<>();
        if (StripeIds.is(StripeIds.TOPUP, id)) {
            ids.add(id);
        }
        return ids;
    }

    static List<Object> uuids(Object id) {
        List<Object> ids = new ArrayList<>();
        try {
            if (id != null) {
                ids.add(UUID.fromString(String.valueOf(id)));
            }
        } catch (IllegalArgumentException e) {
            // Not a platform user: nobody to ban.
        }
        return ids;
    }

    static BigDecimal ledgerBalance(ProcessContext ctx) {
        return ledger(ctx, "available");
    }

    /** The rewards held back from transfers (Q10b, M3). */
    static BigDecimal rewardsHeld(ProcessContext ctx) {
        return ledger(ctx, "held");
    }

    private static BigDecimal ledger(ProcessContext ctx, String column) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(LEDGER);
        return rows == null || rows.isEmpty() ? BigDecimal.ZERO : TakerWallets.money(rows.getFirst().get(column));
    }

    static String userOf(ProcessContext ctx) {
        return ctx.get(INPUT, UserRef.class).userId();
    }

    /** The taker the process is about: the transfer's, or the input's for the creation. */
    static Object transferUser(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer != null) {
            return transfer.get("userId");
        }
        EntityInstance found = first(ctx, FOUND);
        if (found != null) {
            return found.get("userId");
        }
        Object input = ctx.get(INPUT);
        return input instanceof UserRef ref ? ref.userId() : null;
    }

    private static ApprovalResultInput approvalInput(ProcessContext ctx) {
        return ctx.get(INPUT, ApprovalResultInput.class);
    }

    private static ReceivedEvent event(ProcessContext ctx) {
        return ctx.get(EVENT, ReceivedEvent.class);
    }

    static String clip(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    static TransferOutput output(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (transfer == null && TransferRules.OPEN_TRANSFER.equals(ctx.get(SKIP))) {
            // Skipped for the unfinished one: that is the transfer the caller is told about.
            transfer = list(ctx, LATEST).stream().filter(t -> TransferStatus.UNFINISHED.contains(t.get("status")))
                .findFirst().orElse(null);
        }
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER)
            : transfer == null ? null : transfer.get("status");
        String reason = ctx.contains(REASON) ? (String) ctx.get(REASON) : (String) ctx.get(SKIP);
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : transfer == null ? null : transfer.get("approvalRequestId");
        return new TransferOutput(transfer == null ? null : String.valueOf(transfer.id()), status, reason,
            requestId);
    }

    /** The input of {@value #APPROVAL_RESULT} from an approval event. */
    public static ApprovalResultInput resultInput(DomainEvent event) {
        Map<String, Object> payload = event.payload();
        return new ApprovalResultInput(text(payload, "subject"), text(payload, "entityId"), text(payload, "status"),
            text(payload, "requestId"), text(payload, "preparerId"), text(payload, "contentHash"),
            text(payload, "reason"));
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private TransferProcesses() {}
}
