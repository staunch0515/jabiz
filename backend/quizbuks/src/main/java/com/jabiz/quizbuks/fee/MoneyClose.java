package com.jabiz.quizbuks.fee;

import com.jabiz.event.DomainEvent;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.ErasedAiJobs;
import com.jabiz.quizbuks.content.ai.QbAi;
import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.ManualCredits;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbTopUps;
import com.jabiz.quizbuks.wallet.TopUpStatus;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §4.1, §5): what becomes of an erased sponsor's platform fee account.
 *
 * <p>{@value #REGISTER} answers the platform's {@code jabiz.account.erased} on a subscription of its own (not in
 * {@code QB_SPONSOR_ERASED}, whose sub-processes may fail and exhaust the event's retries): a sponsor who had any of the
 * account (a cash or granted credit wallet row, a lot of granted credit, an invoice, a prepayment) gets one
 * {@link QbSponsorClosures#CLOSURE} row, waiting; nobody else gets one. It takes no lock and calls no sub-process.
 */
public final class MoneyClose {

    public static final String REGISTER = "QB_SPONSOR_CLOSURE_REGISTER";
    /** The wind-up itself (the daily job's item and the consumer of {@value #DUE_EVENT}). */
    public static final String CLOSE = "QB_SPONSOR_MONEY_CLOSE";
    /** Step 0: a manual credit or a fee request under review, withdrawn (sub-process). */
    public static final String WITHDRAW = "QB_SPONSOR_CLOSE_WITHDRAW";
    public static final String JOB = "qb.sponsor-money-close";
    public static final String DUE_TEMPLATE = "qb.system.money-close-due";
    public static final String UNINVOICED_TEMPLATE = "qb.system.money-close-uninvoiced";
    public static final String DUE_SUBSCRIPTION = "qb.sponsor-money-close";
    /** Finance's task when a wind-up takes long, and the one when a manual credit under review was withdrawn. */
    public static final String SLOW_TASK = "qb.erasure-close.slow";
    public static final String SLOW_TASK_TITLE = "task.qb.erasure-close.slow";
    public static final String WITHDRAWN_TASK = "qb.erasure-close.withdrawn";
    public static final String WITHDRAWN_TASK_TITLE = "task.qb.erasure-close.withdrawn";
    /** The wind-up's last step for one lot of granted credit (sub-process; the caller holds the wallet lock). */
    public static final String VOID_LOT = "QB_FEE_CREDIT_VOID_LOT";
    /** The posting's description (only a description: the monthly report's kind is the expiry's). */
    /** F-M15c: the posting of a credit note's credit left given back to the cash (debit 1130, credit 2160). */
    static final String NOTE_CASH_BACK = "Credit note's credit left given back to the cash on the account's deletion";
    static final String VOIDED_ON_ERASE = "Platform fee Kudos voided on the account's deletion";
    /** The platform's event that an account was erased (its consumers run after the erasure committed). */
    public static final String ACCOUNT_ERASED_EVENT = "jabiz.account.erased";
    public static final String REGISTER_SUBSCRIPTION = "qb.account-erased-money";
    /** The daily sweep that registers the wind-ups whose erasure's event was lost (review M1). */
    public static final String REGISTER_JOB = "qb.sponsor-closure-register";
    public static final String UNREGISTERED_TEMPLATE = "qb.system.money-close-unregistered";
    /** A closure was registered: the wind-up runs ({@code sponsorId}). */
    public static final String DUE_EVENT = "qb.sponsor.money-close-due";

    static final String INPUT = "input";
    static final String MONEY_WALLET = "moneyWallet";
    static final String MONEY_GRANT = "moneyGrant";
    static final String MONEY_INVOICE = "moneyInvoice";
    static final String MONEY_PREPAYMENT = "moneyPrepayment";
    static final String MONEY_POSTPAID = "moneyPostpaid";
    static final String MONEY_TOPUP_REVIEW = "moneyTopUpReview";
    static final String MONEY_REQUEST_REVIEW = "moneyRequestReview";
    static final String CLOSURE = "closure";
    static final String REGISTERED = "registered";
    static final String LOT = "lot";
    static final String CREDIT_WALLET = "creditWallet";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String VOIDED = "voided";
    static final String PARAMS = "params";
    static final String IN_REVIEW_TOPUPS = "inReviewTopUps";
    static final String IN_REVIEW_REQUESTS = "inReviewRequests";
    static final String WITHDRAWN = "withdrawn";
    static final String OPEN_DEPOSIT = "openDeposit";
    static final String UNINVOICED = "uninvoiced";
    static final String ISSUED = "issued";
    static final String UNPAID = "unpaid";
    static final String CREDITS = "credits";
    static final String CASH_WALLET = "cashWallet";
    static final String TO_VOID = "toVoid";
    static final String LOTS_VOIDED = "lotsVoided";
    static final String OUTCOME = "outcome";
    static final String ROW = "row";
    static final String ALERT = "alert";
    static final String REGISTERED_CLOSURE = "registeredClosure";
    static final String UNPAID_AFTER = "unpaidAfter";
    static final String PURGED = "purged";
    public static final String PURGED_TEMPLATE = "qb.system.erasure-purged";
    static final String CREDITS_AFTER = "creditsAfter";
    static final String CASH_WALLET_AFTER = "cashWalletAfter";
    static final String PAID = "paid";
    static final String AI_CREDIT = "aiCredit";
    static final String AI_JOBS = "aiJobs";
    static final String NOTES_LEFT = "notesLeft";
    static final String NOTES_UNUSED = "notesUnused";
    static final String CREDITED = "credited";
    static final String NOTES_PAID = "notesPaid";
    static final String NOTES_OVERDUE = "notesOverdue";
    static final String REFUND_POSTS = "refundPosts";
    static final String REFUNDED = "refunded";
    static final String AI_CLOSED = "aiClosed";

    /** What one run of the wind-up did. */
    public static final String NONE = "NONE";
    public static final String WAITING_DEPOSITS = "DEPOSIT_OPEN";
    /** F-M15c: more AI jobs may be open than this run cancelled; nothing else is done until they are. */
    public static final String WAITING_AI_JOBS = "AI_JOBS_OPEN";
    public static final String SWITCHED_OFF = "SWITCH_OFF";
    /** Fees owed and something to pay them with after a run's payments (more invoices than a run pays): the next run. */
    public static final String TO_PAY = "TO_PAY";
    public static final String CLOSED = "CLOSED";
    /**
     * More lots to void than one run reads ({@code FeeSettlement.MAX_CREDITS}, review L8): those read are voided, the
     * closure stays {@code WAITING} and the next run voids the rest.
     */
    public static final String MORE = "MORE";

    /** A manual credit or a fee request still under review when the wind-up ran. */
    public static final String TOPUP = "TOPUP";
    public static final String REQUEST = "REQUEST";

    /** The wallet rows that are the platform fee account (a zero balance counts: the account was there). */
    static final List<String> ACCOUNT_WALLETS = List.of(QbWallets.CASH, QbWallets.FEE_CREDIT);

    /** @param userId the erased user */
    /**
     * @param erasedAt when it was erased (the daily sweep gives it, from the erasure's record); none: now (the erasure's
     *                 event, run at once)
     */
    public record RegisterInput(@NotBlank @Size(max = 128) String userId, java.time.Instant erasedAt) {}

    /** @param registered whether a closure was written now (false: nothing of the account, or registered before) */
    public record RegisterOutput(String userId, boolean registered) {}

    /**
     * @param sponsorId the sponsor whose wallet lock the caller holds (the lot must be theirs)
     * @param grantId   the lot to void
     */
    public record VoidLotInput(@NotBlank @Size(max = 128) String sponsorId, @NotBlank @Size(max = 36) String grantId) {}

    /**
     * @param marked whether the lot was marked voided now (false: voided before, expired, or not found)
     * @param voided what was left of it, voided
     */
    public record VoidLotOutput(String grantId, boolean marked, BigDecimal voided) {}

    /** @param sponsorId the erased sponsor whose account is wound up */
    public record CloseInput(@NotBlank @Size(max = 128) String sponsorId) {}

    /**
     * @param outcome {@code NONE} (no closure, or nothing to do), {@code SWITCH_OFF}, {@code DEPOSIT_OPEN},
     *                {@code TO_PAY}, {@code MORE} or {@code CLOSED}
     * @param invoicesPaid the invoices this run paid something of
     * @param owed    what the unpaid invoices still owe after the run
     */
    public record CloseOutput(String sponsorId, String outcome, int invoicesIssued, int invoicesPaid, int lotsVoided,
        int withdrawn, BigDecimal owed) {}

    /** @param kind {@value #TOPUP} or {@value #REQUEST}; @param id its record */
    public record WithdrawInput(@NotBlank @Size(max = 10) String kind, @NotBlank @Size(max = 36) String id) {}

    /** @param withdrawn whether it was still under review and is ended now */
    public record WithdrawOutput(String kind, String id, boolean withdrawn) {}

    private MoneyClose() {}

    // ---- the registration -------------------------------------------------------------------------------------------

    public static ProcessDefinition<RegisterInput, RegisterOutput, ProcessContext> register() {
        return ProcessDefinition.define(REGISTER, 1, RegisterInput.class, RegisterOutput.class, ProcessContext.class,
            pb -> pb
                .description("After an account was erased: registers the wind-up of the sponsor's platform fee account "
                    + "when they had one.")
                .permissions(QbPermissions.FEE_SYSTEM)
                .internal()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new RegisterOutput(userId(ctx), Boolean.TRUE.equals(ctx.get(REGISTERED))))
                // One statement, one row of each at most: whether the user had the account, and a closure already.
                .step("Read what the user had of the account", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> one(
                        new QueryPredicate.Eq("ownerId", userId(ctx)),
                        new QueryPredicate.In("kind", List.copyOf(ACCOUNT_WALLETS))), MONEY_WALLET),
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_GRANT),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_INVOICE),
                    QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), MONEY_PREPAYMENT),
                    // A postpaid fee not invoiced yet (the wind-up invoices it), a manual credit or a fee request still
                    // under review (step 0 withdraws them; review M2).
                    QueryEntities.query(QbFees.defaultDataset(QbFees.CHARGE), ctx -> one(
                        new QueryPredicate.Eq("sponsorId", userId(ctx)),
                        new QueryPredicate.Eq("billingMode", FeeRules.POSTPAID)), MONEY_POSTPAID),
                    QueryEntities.query(QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> one(
                        new QueryPredicate.Eq("sponsorId", userId(ctx)),
                        new QueryPredicate.Eq("method", QbTopUps.MANUAL),
                        new QueryPredicate.Eq("status", TopUpStatus.REVIEW)), MONEY_TOPUP_REVIEW),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> one(
                        new QueryPredicate.Eq("sponsorId", userId(ctx)),
                        new QueryPredicate.Eq("status", QbFees.REVIEW)), MONEY_REQUEST_REVIEW),
                    QueryEntities.query(QbFees.defaultDataset(QbSponsorClosures.CLOSURE),
                        ctx -> one(new QueryPredicate.Eq("sponsorId", userId(ctx))), CLOSURE)))
                .compute("Register the wind-up", (metadata, ctx) -> registerClosure(ctx))
                .step("Ask for the wind-up", PublishEvent.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(REGISTERED)), DUE_EVENT,
                    ctx -> Map.of("sponsorId", userId(ctx)))));
    }

    /**
     * Whether a user had the platform fee account: any one row of the eight (§4.1: a CASH or FEE_CREDIT wallet, a grant,
     * an invoice, a prepayment, a postpaid fee, a manual credit or a fee request under review). The reconciliation's
     * {@code ERASED_NO_CLOSURE} and the daily sweep's template write the same in SQL: change the three together.
     */
    static boolean hadAccount(ProcessContext ctx) {
        return FeeGate.first(ctx, MONEY_WALLET) != null || FeeGate.first(ctx, MONEY_GRANT) != null
            || FeeGate.first(ctx, MONEY_INVOICE) != null || FeeGate.first(ctx, MONEY_PREPAYMENT) != null
            || FeeGate.first(ctx, MONEY_POSTPAID) != null || FeeGate.first(ctx, MONEY_TOPUP_REVIEW) != null
            || FeeGate.first(ctx, MONEY_REQUEST_REVIEW) != null;
    }

    static void registerClosure(ProcessContext ctx) {
        if (FeeGate.first(ctx, CLOSURE) != null || !hadAccount(ctx)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sponsorId", userId(ctx));
        values.put("status", QbSponsorClosures.WAITING);
        // The daily sweep registers late: the alert days still count from the erasure.
        java.time.Instant erasedAt = ctx.get(INPUT, RegisterInput.class).erasedAt();
        values.put("erasedAt", erasedAt != null && !erasedAt.isAfter(ctx.opTime()) ? erasedAt : ctx.opTime());
        ctx.changes().insert(QbSponsorClosures.CLOSURE, values);
        ctx.put(REGISTERED, true);
    }

    // ---- the wind-up -------------------------------------------------------------------------------------------------

    /**
     * Winds up an erased sponsor's platform fee account (§5.2), again and again until it is done: the daily job's item
     * and the consumer of {@value #DUE_EVENT}. It never takes {@code qb.account} or {@code qb.quiz} (the account will not
     * change any more); its only lock is the sponsor's wallet lock, after step 0's withdrawals (an approval's lock comes
     * before a wallet's).
     * <ol start="0">
     *   <li>whatever the switch, a manual credit or a fee request still under review is withdrawn ({@value #WITHDRAW});
     *   <li>the switch {@code qb.erasure.close.enabled} off: only {@code SWITCH_OFF} is recorded;
     *   <li>a prepayment still held: {@code DEPOSIT_OPEN}, nothing else (it is released a day after its end);
     *   <li>every month with postpaid fees not invoiced yet is invoiced (the month's own item, re-entering the lock);
     *   <li>the unpaid invoices are paid as far as the account goes, earliest first, through F-M13's
     *       {@value FeeAutoPay#AUTO_PAY_ITEM} (at most {@code qb.fee.auto-pay.max-invoices} a run; fees still owed while
     *       the account can pay record {@code TO_PAY} and the next run goes on);
     *   <li>last, what is left of every lot of granted credit not voided and not expired is voided
     *       ({@value #VOID_LOT}), and the closure is {@code CLOSED} ({@code FEE_OWED} when fees are still owed).
     * </ol>
     * A run that does not finish within {@code qb.erasure.close.alert-days} of the erasure gives finance one task, closed
     * when the wind-up is done.
     */
    public static ProcessDefinition<CloseInput, CloseOutput, ProcessContext> close() {
        return ProcessDefinition.define(CLOSE, 1, CloseInput.class, CloseOutput.class, ProcessContext.class, pb -> pb
            .description("Winds up an erased sponsor's platform fee account: invoices what is not invoiced, pays the "
                + "invoices as far as the account goes and voids the granted credit left.")
            .permissions(QbPermissions.FEE_SYSTEM)
            .internal()
            .contextFactory(FeeProcesses::context)
            .outputMapper(MoneyClose::closeOutput)
            .step("Load the switch and the alert days", LoadParams.<ProcessContext>optional(ProcessContext::opTime,
                PARAMS, QbParams.ERASURE_CLOSE_ENABLED, QbParams.ERASURE_CLOSE_ALERT_DAYS,
                QbParams.FEE_AUTO_PAY_MAX_INVOICES))
            // Step 0, whatever the switch: what is under review is withdrawn before anything else (its approval's lock
            // comes before the wallet's).
            .step("Read what is under review", QueryEntities.<ProcessContext>all(
                // Only a sponsor with a wind-up (review L4): the closure is read again under the lock.
                QueryEntities.query(QbFees.defaultDataset(QbSponsorClosures.CLOSURE),
                    ctx -> one(new QueryPredicate.Eq("sponsorId", sponsorId(ctx))), REGISTERED_CLOSURE),
                QueryEntities.query(QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                        new QueryPredicate.Eq("method", QbTopUps.MANUAL),
                        new QueryPredicate.Eq("status", TopUpStatus.REVIEW)))).limit(FeeGate.MAX_UNPAID).build(),
                    IN_REVIEW_TOPUPS),
                QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                        new QueryPredicate.Eq("status", QbFees.REVIEW)))).limit(FeeGate.MAX_UNPAID).build(),
                    IN_REVIEW_REQUESTS),
                // F-M15c: whether there is an AI credit to close (its lock is taken only then); read again under it.
                QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                        new QueryPredicate.Eq("purpose", QbPrepayments.AI),
                        new QueryPredicate.Eq("status", QbPrepayments.OPEN)))).limit(1).build(), AI_CREDIT)))
            // Re-review L2: when the erasure's purge was done (it overwrites every row naming the sponsor then); read
            // only when something is under review.
            .step("Read when the purge was done", RunTemplate.<ProcessContext>when(MoneyClose::somethingUnderReview,
                PURGED_TEMPLATE, ctx -> Map.of("userId", sponsorId(ctx)), PURGED))
            .step("Withdraw what is under review", CallProcess.forEach(WITHDRAW, 1, MoneyClose::withdrawInputs,
                WITHDRAWN))
            // F-M15c: the sponsor's AI jobs still open are cancelled (QB_SPONSOR_ERASED did it already; again here,
            // whatever the switch, until none is left): a wind-up with more than one run reads waits for the next.
            .step("Cancel the AI jobs still open", CallProcess.<ProcessContext>when(
                ctx -> FeeGate.first(ctx, REGISTERED_CLOSURE) != null, ErasedAiJobs.CANCEL_ALL, 1,
                ctx -> new ErasedAiJobs.SponsorRef(sponsorId(ctx)), AI_JOBS))
            // F-M15c: the AI lock before the wallet's (F-M9's order qb.ai:<sponsor> → qb.wallet), only when an AI
            // credit is to be closed; nothing this process calls takes qb.account or qb.quiz.
            .step("Lock the sponsor's AI", HoldLock.<ProcessContext>exclusive(
                ctx -> closesAiCredit(ctx) ? QbAi.lockName(sponsorId(ctx)) : null))
            .step("Lock the sponsor's wallet", HoldLock.<ProcessContext>exclusive(
                ctx -> QbWallets.lockName(QbWallets.SPONSOR, sponsorId(ctx))))
            // Step 3 (F-M15c): the AI credit given back whole and closed, before the prepayments are looked at, so it
            // never holds the wind-up up. Its jobs were deleted with the account and their fees not charged are never
            // charged (plan H3): nothing is held for them.
            .step("Close the AI credit", CallProcess.<ProcessContext>when(MoneyClose::closesAiCredit,
                AiCreditGiveBack.GIVE_BACK, 1, ctx -> new AiCreditGiveBack.GiveBackInput(sponsorId(ctx),
                    AiCreditGiveBack.CLOSE, null, BigDecimal.ZERO, null), AI_CLOSED))
            .step("Read the closure and what is held", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFees.defaultDataset(QbSponsorClosures.CLOSURE),
                    ctx -> one(new QueryPredicate.Eq("sponsorId", sponsorId(ctx))), CLOSURE),
                QueryEntities.query(QbFees.defaultDataset(QbPrepayments.PREPAYMENT), ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                        new QueryPredicate.Eq("status", QbPrepayments.OPEN))))
                    .limit(1).build(), OPEN_DEPOSIT)))
            .compute("Decide whether to go on", (metadata, ctx) -> decideStart(ctx))
            .step("Find the months not invoiced", RunTemplate.<ProcessContext>when(MoneyClose::goingOn,
                UNINVOICED_TEMPLATE, ctx -> Map.of("sponsorId", sponsorId(ctx)), UNINVOICED))
            .step("Invoice them", CallProcess.forEach(FeeProcesses.INVOICE_ITEM, 1, MoneyClose::invoiceInputs,
                ISSUED))
            // Step 7: F-M13's automatic payment, one invoice an item under the wallet lock this run holds, the
            // earliest first, at most qb.fee.auto-pay.max-invoices a run (the rest next run: TO_PAY). It is the
            // wind-up's own switch that decides, not qb.fee.auto-pay.enabled (plan §5.2 step 7). The invoices, the
            // account and the lots to void are read in one statement; no item runs when the account has nothing to
            // pay with (re-review L1), and only a run that paid something reads the invoices and the account again.
            .step("Read the account and the invoices", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> goingOn(ctx)
                    ? CreditNotes.unpaid(sponsorId(ctx)) : nothing("invoiceId"), UNPAID),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> goingOn(ctx)
                    ? FeeSettlement.credits(sponsorId(ctx), ctx.opTime()) : nothing("grantId"), CREDITS),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET),
                    ctx -> QbWallets.query(QbWallets.CASH, goingOn(ctx) ? sponsorId(ctx) : null), CASH_WALLET),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> goingOn(ctx)
                    ? EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sponsorId", sponsorId(ctx)),
                        new QueryPredicate.IsNull("voidedAt"),
                        new QueryPredicate.Gt("expiresAt", ctx.opTime()))))
                    .limit(FeeSettlement.MAX_CREDITS).build() : nothing("grantId"), TO_VOID)))
            // Step 6 (F-M15c): the credit the sponsor's credit notes have left pays the unpaid invoices first, the
            // earliest note and the earliest invoice first, as a new invoice takes it (CreditNotes.take).
            .step("Read the credit notes' credit left", RunTemplate.<ProcessContext>when(MoneyClose::goingOn,
                CreditNotes.LEFT_TEMPLATE, ctx -> CreditNotes.leftParams(sponsorId(ctx)), NOTES_LEFT))
            .compute("Pay from the credit notes' credit", (metadata, ctx) -> payFromNotes(ctx))
            // Written now: the invoices are read again below (a change still held would not be in that read).
            .step("Save what the credit paid", com.jabiz.runtime.process.steps.SaveChanges.<ProcessContext>when(
                ctx -> Boolean.TRUE.equals(ctx.get(CREDITED))))
            .step("Close the reminders of the invoices it paid", CallProcess.<ProcessContext>forEach(
                CreditNotes.TASK_CLOSE, 1, MoneyClose::paidOverdue, "notesTasksClosed"))
            .step("Pay them", CallProcess.forEach(FeeAutoPay.AUTO_PAY_ITEM, 1, MoneyClose::payInputs, PAID))
            // The lots to void are not read again: each item reads its lot (one used up by the payment is marked too).
            .step("Read the account and the invoices again", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbFees.defaultDataset(QbFees.INVOICE), ctx -> paidSomething(ctx)
                    ? CreditNotes.unpaid(sponsorId(ctx)) : nothing("invoiceId"), UNPAID_AFTER),
                QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT), ctx -> paidSomething(ctx)
                    ? FeeSettlement.credits(sponsorId(ctx), ctx.opTime()) : nothing("grantId"), CREDITS_AFTER),
                QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(QbWallets.CASH,
                    paidSomething(ctx) ? sponsorId(ctx) : null), CASH_WALLET_AFTER)))
            .compute("Decide the end", (metadata, ctx) -> decideEnd(ctx))
            .step("Void the granted credit left", CallProcess.forEach(VOID_LOT, 1, MoneyClose::voidInputs,
                LOTS_VOIDED))
            // Step 9 (F-M15c): what the credit notes still have left once the wind-up is done goes back to the cash
            // (debit 1130, credit 2160, the note the source; a FEE_PAID taken back, as F-M7's giving back).
            .compute("Work out the credit left to give back", (metadata, ctx) -> refundPlan(ctx))
            .step("Give the credit left back to the cash", CallProcess.forEach(LedgerProcesses.POST, 1,
                ctx -> ctx.contains(REFUND_POSTS) ? (List<?>) ctx.get(REFUND_POSTS) : List.of(), REFUNDED))
            .compute("Record the credit given back", (metadata, ctx) -> recordRefunds(ctx))
            .compute("Record the run", (metadata, ctx) -> recordRun(ctx))
            .step("Tell finance it takes long", CreateTask.<ProcessContext>when(ctx -> Boolean.TRUE.equals(ctx.get(ALERT)),
                MoneyClose::slowTask))
            .step("Close finance's task", CloseTasks.<ProcessContext>done(ctx -> CLOSED.equals(ctx.get(OUTCOME))
                ? slowTaskSource(sponsorId(ctx)) : slowTaskSource("none"))));
    }

    /**
     * What step 0 withdraws: what was asked for until the erasure's purge was done (re-review M1, L2). The purge
     * overwrites the reason of every row naming the sponsor at that moment, so a decision on one could not land; one
     * asked for after it (finance's void of an invoice the wind-up waits on) keeps its reason and is decided as usual.
     * While the purge is still to run, everything under review is withdrawn (the purge would overwrite it).
     */
    static List<WithdrawInput> withdrawInputs(ProcessContext ctx) {
        List<WithdrawInput> inputs = new ArrayList<>();
        if (!somethingUnderReview(ctx)) {
            return inputs;
        }
        Instant until = purgedAt(ctx);
        FeeGate.list(ctx, IN_REVIEW_TOPUPS).stream().filter(row -> before(row.get("createdAt"), until))
            .forEach(row -> inputs.add(new WithdrawInput(TOPUP, String.valueOf(row.id()))));
        FeeGate.list(ctx, IN_REVIEW_REQUESTS).stream().filter(row -> before(row.get("requestedAt"), until))
            .forEach(row -> inputs.add(new WithdrawInput(REQUEST, String.valueOf(row.id()))));
        return inputs;
    }

    /** Whether this run closes the AI credit: a wind-up registered, the switch on and an AI credit open. */
    static boolean closesAiCredit(ProcessContext ctx) {
        return FeeGate.first(ctx, REGISTERED_CLOSURE) != null && FeeGate.first(ctx, AI_CREDIT) != null
            && switchOn(ctx.get(PARAMS, ParamValues.class)) && !aiJobsLeft(ctx);
    }

    /** Whether more of the sponsor's AI jobs may be open than this run cancelled. */
    static boolean aiJobsLeft(ProcessContext ctx) {
        return ctx.get(AI_JOBS) instanceof ErasedAiJobs.CancelOutput out && out.more();
    }

    static boolean somethingUnderReview(ProcessContext ctx) {
        return FeeGate.first(ctx, REGISTERED_CLOSURE) != null
            && (!FeeGate.list(ctx, IN_REVIEW_TOPUPS).isEmpty() || !FeeGate.list(ctx, IN_REVIEW_REQUESTS).isEmpty());
    }

    /** When the purge was done; none (everything is before it) while it is still to run. */
    @SuppressWarnings("unchecked")
    static Instant purgedAt(ProcessContext ctx) {
        if (ctx.get(PURGED) instanceof List<?> rows && !rows.isEmpty()) {
            Object at = ((List<Map<String, Object>>) rows).getFirst().get("purgedTime");
            if (at != null) {
                return at instanceof Instant instant ? instant : Instant.parse(String.valueOf(at));
            }
        }
        return null;
    }

    /**
     * Asked for before the purge (strictly: one asked for at its very time was after the erasure, which QB_FEE_REQUEST
     * refuses until the purge is done, third re-review M1), or the purge still to run.
     */
    private static boolean before(Object at, Instant until) {
        return until == null || at instanceof Instant instant && instant.isBefore(until);
    }

    /** Whether the run winds up now: a closure that is not done (or done with fees owed), the switch on, nothing held. */
    static void decideStart(ProcessContext ctx) {
        EntityInstance closure = FeeGate.first(ctx, CLOSURE);
        if (closure == null) {
            ctx.put(OUTCOME, NONE);
            return;
        }
        if (!switchOn(ctx.get(PARAMS, ParamValues.class))) {
            ctx.put(OUTCOME, SWITCHED_OFF);
            return;
        }
        if (aiJobsLeft(ctx)) {
            ctx.put(OUTCOME, WAITING_AI_JOBS);
            return;
        }
        if (FeeGate.first(ctx, OPEN_DEPOSIT) != null) {
            ctx.put(OUTCOME, WAITING_DEPOSITS);
            return;
        }
        ctx.put(GO_ON, true);
    }

    static final String GO_ON = "goOn";

    static boolean goingOn(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(GO_ON));
    }

    /** Whether the switch is {@code true}: anything else (not published, malformed) is off. */
    static boolean switchOn(ParamValues params) {
        return params != null && Boolean.TRUE.equals(params.find(QbParams.ERASURE_CLOSE_ENABLED, Boolean.class)
            .orElse(null));
    }

    /** The months to invoice, the earliest first. */
    @SuppressWarnings("unchecked")
    static List<FeeProcesses.InvoiceInput> invoiceInputs(ProcessContext ctx) {
        if (!goingOn(ctx) || !(ctx.get(UNINVOICED) instanceof List<?> rows)) {
            return List.of();
        }
        return ((List<Map<String, Object>>) rows).stream().map(row -> String.valueOf(row.get("period"))).sorted()
            .map(period -> new FeeProcesses.InvoiceInput(sponsorId(ctx), period)).toList();
    }

    /**
     * The invoices to pay this run: those owing, the earliest issued first, at most the automatic payment's maximum;
     * none when the account has nothing to pay with (re-review L1).
     */
    static List<FeeAutoPay.ItemInput> payInputs(ProcessContext ctx) {
        if (!goingOn(ctx) || spendable(ctx, CREDITS, CASH_WALLET).signum() <= 0) {
            return List.of();
        }
        Set<String> paidByNotes = notesPaid(ctx);
        List<EntityInstance> unpaid = FeeGate.list(ctx, UNPAID).stream()
            .filter(invoice -> !paidByNotes.contains(String.valueOf(invoice.id()))).toList();
        return FeeAutoPay.choose(unpaid, FeeAutoPay.max(ctx.get(PARAMS, ParamValues.class)))
            .invoices().stream().map(invoice -> new FeeAutoPay.ItemInput(String.valueOf(invoice.id()))).toList();
    }

    /**
     * Whether an item of this run paid something, or the credit notes' credit did (the invoices and the account are
     * read again then).
     */
    static boolean paidSomething(ProcessContext ctx) {
        return Boolean.TRUE.equals(ctx.get(CREDITED)) || ctx.get(PAID) instanceof List<?> items
            && items.stream().anyMatch(item -> ((FeeAutoPay.ItemPaid) item).paid().signum() > 0);
    }

    /**
     * Step 6 (F-M15c): the credit left pays the unpaid invoices read under the lock, the earliest issued first, each
     * as far as it goes (the invoice's {@code creditApplied}, a use of each note: CreditNotes' own writing); what is
     * left after it is kept for step 9.
     */
    static void payFromNotes(ProcessContext ctx) {
        if (!goingOn(ctx)) {
            return;
        }
        List<CreditNotes.Unused> unused = CreditNotes.left(CreditNotes.rows(ctx, NOTES_LEFT));
        Set<String> paid = new java.util.HashSet<>();
        List<CreditNotes.TaskCloseInput> overdue = new ArrayList<>();
        for (EntityInstance invoice : FeeAutoPay.owing(FeeGate.list(ctx, UNPAID))) {
            if (unused.isEmpty()) {
                break;
            }
            List<CreditNotes.Take> takes = CreditNotes.take(unused, QbFees.owed(invoice));
            if (takes.isEmpty()) {
                continue;
            }
            CreditNotes.recordUses(ctx, sponsorId(ctx), String.valueOf(invoice.id()), takes);
            if (CreditNotes.applyCredit(ctx, invoice, CreditNotes.takenTotal(takes))) {
                paid.add(String.valueOf(invoice.id()));
                if (QbFees.OVERDUE.equals(invoice.get("status"))) {
                    overdue.add(new CreditNotes.TaskCloseInput(String.valueOf(invoice.id())));
                }
            }
            unused = CreditNotes.after(unused, takes);
            ctx.put(CREDITED, true);
        }
        ctx.put(NOTES_PAID, Set.copyOf(paid));
        ctx.put(NOTES_OVERDUE, List.copyOf(overdue));
        ctx.put(NOTES_UNUSED, unused);
    }

    /** The overdue invoices the credit paid in full: their reminders are closed (F-M7's own process). */
    static List<?> paidOverdue(ProcessContext ctx) {
        return ctx.contains(NOTES_OVERDUE) ? (List<?>) ctx.get(NOTES_OVERDUE) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> notesPaid(ProcessContext ctx) {
        return ctx.contains(NOTES_PAID) ? (Set<String>) ctx.get(NOTES_PAID) : Set.of();
    }

    /**
     * Step 9 (F-M15c): once the wind-up is done, the credit the notes still have left goes back to the cash, one
     * posting a note (debit 1130 for the sponsor, credit 2160; the note the source).
     */
    @SuppressWarnings("unchecked")
    static void refundPlan(ProcessContext ctx) {
        if (!CLOSED.equals(ctx.get(OUTCOME)) || !ctx.contains(NOTES_UNUSED)) {
            return;
        }
        List<LedgerProcesses.PostInput> posts = new ArrayList<>();
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId(ctx));
        for (CreditNotes.Unused note : (List<CreditNotes.Unused>) ctx.get(NOTES_UNUSED)) {
            posts.add(new LedgerProcesses.PostInput(ctx.opTime(), NOTE_CASH_BACK, note.creditNoteNo(), List.of(
                    new LedgerProcesses.Line(QbLedger.FEES_RECEIVABLE, Direction.DEBIT, note.amount(), null, party),
                    new LedgerProcesses.Line(QbLedger.SPONSOR_CASH, Direction.CREDIT, note.amount(), null, party)),
                QbCreditNotes.CREDIT_NOTE, note.creditNoteId()));
        }
        if (!posts.isEmpty()) {
            ctx.put(REFUND_POSTS, List.copyOf(posts));
        }
    }

    /**
     * The cash given back: the cash wallet, a lot a note (its source the note), the note's negative part and the use of
     * its credit naming the lot.
     */
    @SuppressWarnings("unchecked")
    static void recordRefunds(ProcessContext ctx) {
        if (!ctx.contains(REFUND_POSTS)) {
            return;
        }
        List<CreditNotes.Unused> notes = (List<CreditNotes.Unused>) ctx.get(NOTES_UNUSED);
        BigDecimal total = CreditNotes.total(notes);
        QbWallets.change(ctx, FeeGate.first(ctx, paidSomething(ctx) ? CASH_WALLET_AFTER : CASH_WALLET), QbWallets.CASH,
            sponsorId(ctx), total, 0, BigDecimal.ZERO);
        List<LedgerProcesses.PostOutput> posted = (List<LedgerProcesses.PostOutput>) ctx.get(REFUNDED);
        for (int i = 0; i < notes.size(); i++) {
            CreditNotes.Unused note = notes.get(i);
            Object lotId = CashLots.add(ctx, sponsorId(ctx), QbFeeAccount.OTHER, note.amount(),
                QbCreditNotes.CREDIT_NOTE, note.creditNoteId());
            // A negative part naming the posting, as F-M7's giving back writes (the daily check FEE_PAYMENTS matches
            // a note's postings of 2160 with its parts).
            FeeSettlement.insertPayment(ctx, sponsorId(ctx), QbCreditNotes.CREDIT_NOTE, note.creditNoteId(),
                QbFeeAccount.CASH, null, lotId, note.amount().negate(), posted.get(i).transactionId());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("creditNoteId", note.creditNoteId());
            values.put("sponsorId", sponsorId(ctx));
            values.put("cashLotId", String.valueOf(lotId));
            values.put("amount", note.amount());
            values.put("usedAt", ctx.opTime());
            ctx.changes().insert(QbCreditNotes.USE, values);
        }
    }

    private static BigDecimal spendable(ProcessContext ctx, String credits, String cash) {
        return FeeSettlement.spendable(FeeGate.list(ctx, credits), FeeGate.first(ctx, cash), ctx.opTime());
    }

    /** What the invoices still owe after the payments (each as {@link QbFees#owed}). */
    static BigDecimal owed(ProcessContext ctx) {
        return FeeGate.list(ctx, paidSomething(ctx) ? UNPAID_AFTER : UNPAID).stream().map(QbFees::owed)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * After the invoices and the payments: fees owed while the account still has something to pay them with wait for
     * the next payment; otherwise the granted credit left is voided now and the closure is done.
     */
    static void decideEnd(ProcessContext ctx) {
        if (!goingOn(ctx)) {
            return;
        }
        BigDecimal spendable = paidSomething(ctx) ? spendable(ctx, CREDITS_AFTER, CASH_WALLET_AFTER)
            : spendable(ctx, CREDITS, CASH_WALLET);
        String end = ErasureCloseRules.end(owed(ctx), spendable);
        ctx.put(OUTCOME, CLOSED.equals(end) && FeeGate.list(ctx, TO_VOID).size() >= FeeSettlement.MAX_CREDITS ? MORE : end);
    }

    static List<VoidLotInput> voidInputs(ProcessContext ctx) {
        if (!CLOSED.equals(ctx.get(OUTCOME)) && !MORE.equals(ctx.get(OUTCOME))) {
            return List.of();
        }
        return FeeGate.list(ctx, TO_VOID).stream().map(lot -> new VoidLotInput(sponsorId(ctx), String.valueOf(lot.id())))
            .toList();
    }

    /**
     * The closure's row after the run, and whether finance is told now that it takes long. Written by the version read
     * under the wallet lock (re-review L6): the registration and the runs of the event and of the daily job for one
     * sponsor take that lock, so they follow each other; a write that still meets a newer version (the registration of
     * the same erasure by the sweep and by the event at once) fails with a version conflict, its transaction rolls back
     * with every posting in it, and the next run (the job's retry, the next day) does it again from what is there: it
     * converges and never books twice. Accepted.
     */
    static void recordRun(ProcessContext ctx) {
        EntityInstance closure = FeeGate.first(ctx, CLOSURE);
        if (closure == null) {
            return;
        }
        String outcome = (String) ctx.get(OUTCOME);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lastRunAt", ctx.opTime());
        if (CLOSED.equals(outcome)) {
            values.put("status", QbSponsorClosures.CLOSED);
            values.put("closedAt", closure.get("closedAt") == null ? ctx.opTime() : closure.get("closedAt"));
            values.put("reasonCode", owed(ctx).signum() > 0 ? QbSponsorClosures.FEE_OWED : null);
        } else {
            values.put("reasonCode", ErasureCloseRules.reasonOf(outcome, (String) closure.get("reasonCode"),
                QbSponsorClosures.CLOSED.equals(closure.get("status"))));
            BigDecimal days = ctx.get(PARAMS, ParamValues.class) == null ? null
                : ctx.get(PARAMS, ParamValues.class).find(QbParams.ERASURE_CLOSE_ALERT_DAYS, BigDecimal.class).orElse(null);
            if (QbSponsorClosures.WAITING.equals(closure.get("status")) && closure.get("alertedAt") == null
                && ErasureCloseRules.slow((Instant) closure.get("erasedAt"), days, ctx.opTime())) {
                values.put("alertedAt", ctx.opTime());
                ctx.put(ALERT, true);
            }
        }
        ctx.changes().update(QbSponsorClosures.CLOSURE, closure.id(), closure.version(), values);
    }

    static String slowTaskSource(Object sponsorId) {
        return "erasure-close:" + sponsorId;
    }

    static TaskSpec slowTask(ProcessContext ctx) {
        EntityInstance closure = FeeGate.first(ctx, CLOSURE);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sponsor", sponsorId(ctx));
        // The title says why in words, one message per reason (review L7).
        return TaskSpec.forPermission(SLOW_TASK, SLOW_TASK_TITLE + "." + ErasureCloseRules.slowReason(
                (String) ctx.get(OUTCOME)), params, QbPermissions.FEE_MANAGE)
            .about(QbSponsorClosures.CLOSURE, String.valueOf(closure.id())).source(slowTaskSource(sponsorId(ctx)));
    }

    @SuppressWarnings("unchecked")
    static CloseOutput closeOutput(ProcessContext ctx) {
        int issued = ctx.get(ISSUED) instanceof List<?> list ? list.size() : 0;
        int paid = ctx.get(PAID) instanceof List<?> list
            ? (int) ((List<Object>) list).stream().filter(o -> ((FeeAutoPay.ItemPaid) o).paid().signum() > 0).count() : 0;
        int voided = ctx.get(LOTS_VOIDED) instanceof List<?> list
            ? (int) ((List<Object>) list).stream().filter(o -> ((VoidLotOutput) o).marked()).count() : 0;
        int withdrawn = ctx.get(WITHDRAWN) instanceof List<?> list
            ? (int) ((List<Object>) list).stream().filter(o -> ((WithdrawOutput) o).withdrawn()).count() : 0;
        return new CloseOutput(sponsorId(ctx), (String) ctx.get(OUTCOME), issued, paid, voided, withdrawn,
            goingOn(ctx) ? owed(ctx) : BigDecimal.ZERO);
    }

    private static String sponsorId(ProcessContext ctx) {
        return ctx.get(INPUT, CloseInput.class).sponsorId();
    }

    private static EntityQuery nothing(String key) {
        return EntityQuery.builder().where(new QueryPredicate.In(key, List.of())).limit(1).build();
    }

    /** The input of the job's item and of the consumer: the sponsor. */
    static CloseInput closeInput(DomainEvent event) {
        return new CloseInput(String.valueOf(event.payload().get("sponsorId")));
    }

    // ---- step 0: withdrawing what is under review -------------------------------------------------------------------

    /**
     * A manual credit or a platform fee request still under review when its sponsor was erased (Fable M1): its pending
     * approval is withdrawn (the erasure overwrote the reason it was bound to, so a decision could not land) and it
     * ends: the credit {@code CANCELLED}, the request {@code REJECTED}, both with {@code endedCode = ERASED}; nothing is
     * posted or granted. A manual credit may stand for money received: finance gets a task with its amount. A bank
     * top-up's check by finance is not withdrawn (its money arrived; its approval is bound to no erased field).
     */
    public static ProcessDefinition<WithdrawInput, WithdrawOutput, ProcessContext> withdraw() {
        return ProcessDefinition.define(WITHDRAW, 1, WithdrawInput.class, WithdrawOutput.class, ProcessContext.class,
            pb -> pb
                .description("Ends a manual credit or a platform fee request under review when its sponsor's account "
                    + "was deleted (a part of the wind-up).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(ctx -> new WithdrawOutput(withdrawInput(ctx).kind(), withdrawInput(ctx).id(),
                    Boolean.TRUE.equals(ctx.get(WITHDRAWN))))
                .step("Read it", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTopUps.defaultDataset(QbTopUps.TOPUP), ctx -> TOPUP.equals(
                        withdrawInput(ctx).kind()) ? FeeProcesses.byId("topUpId", withdrawInput(ctx).id())
                        : nothing("topUpId"), IN_REVIEW_TOPUPS),
                    QueryEntities.query(QbFees.defaultDataset(QbFees.REQUEST), ctx -> REQUEST.equals(
                        withdrawInput(ctx).kind()) ? FeeProcesses.byId("requestId", withdrawInput(ctx).id())
                        : nothing("requestId"), IN_REVIEW_REQUESTS)))
                .compute("Decide", (metadata, ctx) -> ctx.put(WITHDRAWN, stillInReview(ctx)))
                .step("Withdraw the manual credit's approval", WithdrawApproval.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAWN)) && TOPUP.equals(withdrawInput(ctx).kind()),
                    ManualCredits.APPROVAL_SUBJECT, ctx -> withdrawInput(ctx).id()))
                .step("Withdraw the request's approval", WithdrawApproval.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAWN)) && REQUEST.equals(withdrawInput(ctx).kind()),
                    QbFees.APPROVAL_SUBJECT, ctx -> withdrawInput(ctx).id()))
                .compute("End it", (metadata, ctx) -> endWithdrawn(ctx))
                .step("Tell finance", CreateTask.<ProcessContext>when(
                    ctx -> Boolean.TRUE.equals(ctx.get(WITHDRAWN)) && TOPUP.equals(withdrawInput(ctx).kind()),
                    MoneyClose::withdrawnTask)));
    }

    private static WithdrawInput withdrawInput(ProcessContext ctx) {
        return ctx.get(INPUT, WithdrawInput.class);
    }

    private static EntityInstance withdrawnRow(ProcessContext ctx) {
        return TOPUP.equals(withdrawInput(ctx).kind()) ? FeeGate.first(ctx, IN_REVIEW_TOPUPS)
            : FeeGate.first(ctx, IN_REVIEW_REQUESTS);
    }

    static boolean stillInReview(ProcessContext ctx) {
        EntityInstance row = withdrawnRow(ctx);
        if (row == null) {
            return false;
        }
        return TOPUP.equals(withdrawInput(ctx).kind())
            ? QbTopUps.MANUAL.equals(row.get("method")) && TopUpStatus.REVIEW.equals(row.get("status"))
            : QbFees.REVIEW.equals(row.get("status"));
    }

    static void endWithdrawn(ProcessContext ctx) {
        if (!Boolean.TRUE.equals(ctx.get(WITHDRAWN))) {
            return;
        }
        EntityInstance row = withdrawnRow(ctx);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("endedCode", QbSponsorClosures.ENDED_ERASED);
        if (TOPUP.equals(withdrawInput(ctx).kind())) {
            values.put("status", TopUpStatus.CANCELLED);
            // As a credit rejected (ManualCredits.cancel): the statement's entry is let go.
            values.put("receivedOn", null);
            values.put("bankReference", null);
            ctx.changes().update(QbTopUps.TOPUP, row.id(), row.version(), values);
        } else {
            values.put("status", QbFees.REJECTED);
            values.put("decidedAt", ctx.opTime());
            ctx.changes().update(QbFees.REQUEST, row.id(), row.version(), values);
        }
    }

    static TaskSpec withdrawnTask(ProcessContext ctx) {
        EntityInstance row = withdrawnRow(ctx);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", FeeGate.money(row.get("amount")).toPlainString());
        params.put("sponsor", String.valueOf((Object) row.get("sponsorId")));
        return TaskSpec.forPermission(WITHDRAWN_TASK, WITHDRAWN_TASK_TITLE, params, QbPermissions.FEE_MANAGE)
            .about(QbTopUps.TOPUP, String.valueOf(row.id())).source("erasure-withdrawn:" + row.id());
    }

    // ---- voiding one lot --------------------------------------------------------------------------------------------

    /**
     * Voids what is left of a lot of granted credit on an erased sponsor's wind-up (§5.3), under the wallet lock the
     * caller holds: the remaining amount to 0, added to what was voided, {@code voidedAt} written even when nothing was
     * left (what a prepayment gives back to the lot later is voided on arrival), the wallet's cache, and the posting
     * (debit 2190, credit 5140, the lot as its source, as an expiry). Only the wind-up's last step calls it.
     */
    public static ProcessDefinition<VoidLotInput, VoidLotOutput, ProcessContext> voidLot() {
        return ProcessDefinition.define(VOID_LOT, 1, VoidLotInput.class, VoidLotOutput.class, ProcessContext.class,
            pb -> pb
                .description("Voids what is left of a lot of platform fee Kudos when the sponsor's account was deleted "
                    + "(a part of the wind-up).")
                .permissions(QbPermissions.FEE_SYSTEM)
                .subProcessOnly()
                .contextFactory(FeeProcesses::context)
                .outputMapper(MoneyClose::voidLotOutput)
                .step("Read the lot and the wallet", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbFeeAccount.defaultDataset(QbFeeAccount.GRANT),
                        ctx -> FeeProcesses.byId("grantId", grantId(ctx)), LOT),
                    QueryEntities.query(QbWallets.defaultDataset(QbWallets.WALLET), ctx -> QbWallets.query(
                        QbWallets.FEE_CREDIT, ctx.get(INPUT, VoidLotInput.class).sponsorId()), CREDIT_WALLET)))
                .compute("Void what is left", (metadata, ctx) -> markVoided(ctx))
                .step("Post it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING)));
    }

    static void markVoided(ProcessContext ctx) {
        EntityInstance lot = FeeGate.first(ctx, LOT);
        if (lot == null || !ctx.get(INPUT, VoidLotInput.class).sponsorId().equals(lot.get("sponsorId"))
            || !FeeCreditRules.voidableOnErase((Instant) lot.get("expiresAt"), (Instant) lot.get("voidedAt"),
            ctx.opTime())) {
            return;
        }
        BigDecimal voided = FeeCreditRules.voidedOnErase(FeeGate.money(lot.get("remaining")));
        String sponsorId = lot.get("sponsorId");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("remaining", BigDecimal.ZERO);
        values.put("voidedAmount", FeeGate.money(lot.get("voidedAmount")).add(voided));
        values.put("voidedAt", ctx.opTime());
        ctx.changes().update(QbFeeAccount.GRANT, lot.id(), lot.version(), values);
        ctx.put(VOIDED, voided);
        if (voided.signum() == 0) {
            return;
        }
        QbWallets.change(ctx, FeeGate.first(ctx, CREDIT_WALLET), QbWallets.FEE_CREDIT, sponsorId, voided.negate(), 0,
            BigDecimal.ZERO);
        Map<String, String> party = Map.of(QbLedger.PARTY, sponsorId);
        ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), VOIDED_ON_ERASE, lot.get("source"), List.of(
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT, Direction.DEBIT, voided, null, party),
            new LedgerProcesses.Line(QbLedger.FEE_CREDIT_PROMOTION, Direction.CREDIT, voided, null, party)),
            QbFeeAccount.GRANT, String.valueOf(lot.id())));
    }

    private static String grantId(ProcessContext ctx) {
        return ctx.get(INPUT, VoidLotInput.class).grantId();
    }

    static VoidLotOutput voidLotOutput(ProcessContext ctx) {
        BigDecimal voided = ctx.get(VOIDED) instanceof BigDecimal amount ? amount : null;
        return new VoidLotOutput(grantId(ctx), voided != null, voided == null ? BigDecimal.ZERO : voided);
    }

    private static EntityQuery one(QueryPredicate... predicates) {
        return EntityQuery.builder().where(predicates.length == 1 ? predicates[0]
            : new QueryPredicate.And(List.of(predicates))).limit(1).build();
    }

    private static String userId(ProcessContext ctx) {
        return ctx.get(INPUT, RegisterInput.class).userId();
    }

    /** The input of the registration: the user the platform's event names. */
    static RegisterInput registerInput(DomainEvent event) {
        return new RegisterInput(String.valueOf(event.payload().get("userId")), null);
    }
}
