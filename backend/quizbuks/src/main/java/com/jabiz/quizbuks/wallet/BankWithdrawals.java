package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.mail.MailCategory;
import com.jabiz.mail.MailRecipient;
import com.jabiz.mail.MailTemplate;
import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.BanRules;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jabiz.quizbuks.wallet.TopUpCrediting.first;
import static com.jabiz.quizbuks.wallet.TopUpCrediting.list;
import static com.jabiz.quizbuks.wallet.TransferProcesses.APPROVAL;
import static com.jabiz.quizbuks.wallet.TransferProcesses.BANK_ACCOUNT;
import static com.jabiz.quizbuks.wallet.TransferProcesses.HOLDS;
import static com.jabiz.quizbuks.wallet.TransferProcesses.INPUT;
import static com.jabiz.quizbuks.wallet.TransferProcesses.LATEST;
import static com.jabiz.quizbuks.wallet.TransferProcesses.NEW_ID;
import static com.jabiz.quizbuks.wallet.TransferProcesses.PARAMS;
import static com.jabiz.quizbuks.wallet.TransferProcesses.RULES;
import static com.jabiz.quizbuks.wallet.TransferProcesses.SETTLE;
import static com.jabiz.quizbuks.wallet.TransferProcesses.SETTLE_INPUT;
import static com.jabiz.quizbuks.wallet.TransferProcesses.STATUS_AFTER;
import static com.jabiz.quizbuks.wallet.TransferProcesses.STRIPE_TRANSFER;
import static com.jabiz.quizbuks.wallet.TransferProcesses.TRANSFER;

/**
 * Withdrawals to a bank account (docs/quizbuks/plans/Q13-bank-transfer.md, Q13b). A taker keeps one Japanese bank
 * account ({@value #ACCOUNT_SAVE}; a change mails them and starts a cooling-off period) and chooses how they are paid
 * ({@value #METHOD_SET}): the daily Stripe transfer, or withdrawals they ask for. A withdrawal is a
 * {@code QbTransfer} of method {@code BANK}:
 * <ul>
 *   <li>{@value #REQUEST}: under the taker's wallet lock, the amount (from the threshold to what is not held back,
 *       above the fixed fee), always for a review (the payee as the preparer, so never their own approval), with a copy
 *       of the account. Nothing is posted yet.</li>
 *   <li>{@code QB_TRANSFER_APPROVAL_RESULT} (the transfers'): approved, the sending is posted as for Stripe (2120 to
 *       2130 and the fee) and the transfer waits in {@code SENDING} for finance, who get a task to pay it; rejected, it
 *       holds the taker as any rejected transfer.</li>
 *   <li>{@value #PAID}: finance paid it in the bank: the statement's day and reference (each entry pays one withdrawal),
 *       2130 to 1120, {@code SENT}.</li>
 *   <li>{@value #RETURNED}: the bank returned it (a wrong account): the sending is reversed, {@code FAILED}.</li>
 * </ul>
 * Finance never pays or returns a withdrawal of their own.
 */
public final class BankWithdrawals {

    public static final String ACCOUNT_SAVE = "QB_BANK_ACCOUNT_SAVE";
    public static final String METHOD_SET = "QB_PAYOUT_METHOD_SET";
    public static final String REQUEST = "QB_WITHDRAWAL_REQUEST";
    public static final String PAID = "QB_WITHDRAWAL_PAID";
    public static final String RETURNED = "QB_WITHDRAWAL_RETURNED";

    /** The failure code of a withdrawal the bank returned. */
    public static final String RETURNED_CODE = "BANK_RETURNED";
    public static final int REASON_LENGTH = 200;

    static final String PAY_TASK = "qb.withdrawal-pay";
    static final String IN_USE_TASK = "qb.bank-account-in-use";

    /** A taker's bank account was saved or changed: when they may withdraw to it (transactional, not unsubscribable). */
    public static final MailTemplate ACCOUNT_CHANGED_MAIL = MailTemplate.define("qb.bank-account.changed", t -> t
        .category(MailCategory.TRANSACTIONAL).param("bank", "account", "usableFrom"));

    /** The taker's bank account as typed; the number is masked wherever the input is recorded. */
    public record AccountInput(@NotBlank @Size(max = 8) String bankCode,
        @NotBlank @Size(max = BankAccounts.NAME_LENGTH) String bankName,
        @NotBlank @Size(max = 6) String branchCode,
        @NotBlank @Size(max = BankAccounts.NAME_LENGTH) String branchName,
        @NotBlank @Size(max = 10) String accountType,
        @Sensitive @NotBlank @Size(max = 14) String bankAccountNumber,
        @NotBlank @Size(max = BankAccounts.HOLDER_LENGTH * 2) String accountHolder) {

        @Override
        public String toString() {
            return "AccountInput[" + bankCode + "-" + branchCode + ", " + accountType + ", ***]";
        }
    }

    /**
     * @param saved      whether the account is the one given now (unchanged counts)
     * @param reason     why not ({@link WalletCodes#BANK_ACCOUNT_IN_USE}: finance is told)
     * @param usableFrom when a withdrawal to it may be asked for
     */
    public record AccountOutput(String bankAccountId, boolean saved, String reason, Instant usableFrom) {}

    public record MethodInput(@NotBlank @Size(max = 10) String method) {}

    public record MethodOutput(String method) {}

    public record RequestInput(@NotNull @DecimalMin("1") BigDecimal amount) {}

    public record PaidInput(@NotBlank @Size(max = 36) String transferId, @NotNull LocalDate paidOn,
        @NotBlank @Size(max = BankTransfers.REFERENCE_LENGTH) String paidReference) {}

    public record ReturnedInput(@NotBlank @Size(max = 36) String transferId,
        @NotBlank @Size(max = REASON_LENGTH) String reason) {}

    private static final String ACCOUNT = "normalisedAccount";
    private static final String SAME = "sameAccount";
    private static final String IN_USE = "inUse";
    private static final String CHANGED_ACCOUNT = "changedAccount";
    private static final String OUTPUT = "output";
    private static final String USED = "usedReference";
    private static final String REFERENCE = "reference";

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'JST'");

    // ---- the account ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<AccountInput, AccountOutput, ProcessContext> saveAccount() {
        return ProcessDefinition.define(ACCOUNT_SAVE, 1, AccountInput.class, AccountOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the taker's bank account for withdrawals.")
                .permissions(QbPermissions.PAYOUT_ONBOARD)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(BankWithdrawals::context)
                .outputMapper(ctx -> ctx.get(OUTPUT, AccountOutput.class))
                .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.TAKER, ctx.request().actorId())))
                .step("Load the cooling-off period", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.WITHDRAWAL_BANK_COOLDOWN_HOURS))
                .compute("Check the account", (metadata, ctx) -> {
                    AccountInput input = ctx.get(INPUT, AccountInput.class);
                    BankAccounts.Account account = BankAccounts.normalise(input.bankCode(), input.bankName(),
                        input.branchCode(), input.branchName(), input.accountType(), input.bankAccountNumber(),
                        input.accountHolder());
                    List<Violation> problems = BankAccounts.problems(account);
                    if (!problems.isEmpty()) {
                        throw new BusinessRuleViolationException(problems);
                    }
                    ctx.put(ACCOUNT, account);
                })
                .steps(b -> TransferProcesses.loadBankAccount(b, ctx -> ctx.request().actorId()))
                .step("Look for the account elsewhere", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.BANK_ACCOUNT), ctx -> {
                        BankAccounts.Account account = ctx.get(ACCOUNT, BankAccounts.Account.class);
                        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("bankCode", account.bankCode()),
                            new QueryPredicate.Eq("branchCode", account.branchCode()),
                            new QueryPredicate.Eq("accountType", account.accountType()),
                            new QueryPredicate.Eq("accountNumber", account.accountNumber())))).limit(1).build();
                    }, SAME))
                .compute("Save", (metadata, ctx) -> saved(ctx))
                .step("Tell finance", CreateTask.<ProcessContext>when(ctx -> ctx.contains(IN_USE),
                    ctx -> inUseTask(ctx.request().actorId(), first(ctx, SAME))))
                .step("Tell the taker", com.jabiz.runtime.mail.SendMail.<ProcessContext>when(
                    ctx -> ctx.contains(CHANGED_ACCOUNT), ACCOUNT_CHANGED_MAIL,
                    ctx -> MailRecipient.user(ctx.request().actorId()), Map.of(
                        "bank", ctx -> {
                            BankAccounts.Account account = ctx.get(ACCOUNT, BankAccounts.Account.class);
                            return account.bankName() + " " + account.branchName();
                        },
                        "account", ctx -> "****" + last4(ctx.get(ACCOUNT, BankAccounts.Account.class)
                            .accountNumber()),
                        "usableFrom", ctx -> SHOWN.format(ctx.get(OUTPUT, AccountOutput.class).usableFrom()
                            .atZone(BankTransfers.ZONE))))));
    }

    static void saved(ProcessContext ctx) {
        String userId = ctx.request().actorId();
        BankAccounts.Account account = ctx.get(ACCOUNT, BankAccounts.Account.class);
        EntityInstance own = first(ctx, BANK_ACCOUNT);
        EntityInstance same = first(ctx, SAME);
        BigDecimal cooldown = ctx.get(PARAMS, ParamValues.class).get(QbParams.WITHDRAWAL_BANK_COOLDOWN_HOURS,
            BigDecimal.class);
        if (same != null && !userId.equals(same.get("userId"))) {
            // Never two users on one account: refused without writing, and finance looks at both.
            ctx.put(IN_USE, true);
            ctx.put(OUTPUT, new AccountOutput(own == null ? null : String.valueOf(own.id()), false,
                WalletCodes.BANK_ACCOUNT_IN_USE, null));
            return;
        }
        Map<String, Object> values = accountValues(account);
        if (own != null && values.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(),
            own.get(e.getKey())))) {
            // Saved again as it is: the cooling-off period goes on from the last change.
            ctx.put(OUTPUT, new AccountOutput(String.valueOf(own.id()), true, null,
                BankAccounts.usableFrom(own.get("changedAt"), cooldown)));
            return;
        }
        values.put("changedAt", ctx.opTime());
        Object id;
        if (own == null) {
            values.put("userId", userId);
            values.put("payoutMethod", QbPayouts.STRIPE);
            values.put("createdAt", ctx.opTime());
            id = ctx.changes().insert(QbPayouts.BANK_ACCOUNT, values);
        } else {
            id = own.id();
            ctx.changes().update(QbPayouts.BANK_ACCOUNT, own.id(), own.version(), values);
        }
        ctx.put(CHANGED_ACCOUNT, true);
        ctx.put(OUTPUT, new AccountOutput(String.valueOf(id), true, null,
            BankAccounts.usableFrom(ctx.opTime(), cooldown)));
    }

    private static Map<String, Object> accountValues(BankAccounts.Account account) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", account.bankCode());
        values.put("bankName", account.bankName());
        values.put("branchCode", account.branchCode());
        values.put("branchName", account.branchName());
        values.put("accountType", account.accountType());
        values.put("accountNumber", account.accountNumber());
        values.put("accountHolder", account.accountHolder());
        return values;
    }

    // ---- how the taker is paid -------------------------------------------------------------------------------------

    public static ProcessDefinition<MethodInput, MethodOutput, ProcessContext> setMethod() {
        return ProcessDefinition.define(METHOD_SET, 1, MethodInput.class, MethodOutput.class, ProcessContext.class,
            pb -> pb
                .description("Chooses how the taker is paid: the daily Stripe transfer or withdrawals to the bank.")
                .permissions(QbPermissions.PAYOUT_ONBOARD)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(BankWithdrawals::context)
                .outputMapper(ctx -> new MethodOutput(ctx.get(INPUT, MethodInput.class).method()))
                .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.TAKER, ctx.request().actorId())))
                .steps(b -> TransferProcesses.loadBankAccount(b, ctx -> ctx.request().actorId()))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> TransferProcesses.latest(
                        ctx.request().actorId()), LATEST))
                .compute("Choose", (metadata, ctx) -> {
                    String method = ctx.get(INPUT, MethodInput.class).method();
                    EntityInstance account = first(ctx, BANK_ACCOUNT);
                    if (!QbPayouts.METHODS.contains(method)) {
                        throw refusal("method", WalletCodes.BANK_ACCOUNT_INVALID, "STRIPE or BANK", Map.of());
                    }
                    String current = account == null ? QbPayouts.STRIPE : account.get("payoutMethod");
                    if (method.equals(current)) {
                        return;
                    }
                    if (account == null) {
                        throw refusal("method", WalletCodes.BANK_ACCOUNT_MISSING, "Save a bank account first",
                            Map.of());
                    }
                    // A transfer under way finishes the way it began.
                    if (list(ctx, LATEST).stream().anyMatch(t -> TransferStatus.UNFINISHED.contains(t.get("status")))) {
                        throw refusal("method", WalletCodes.PAYOUT_METHOD_OPEN_TRANSFER,
                            "A transfer is still under way", Map.of());
                    }
                    ctx.changes().update(QbPayouts.BANK_ACCOUNT, account.id(), account.version(),
                        Map.of("payoutMethod", method));
                }));
    }

    // ---- the request -----------------------------------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, TransferProcesses.TransferOutput, ProcessContext> request() {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, TransferProcesses.TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Asks for a withdrawal of the taker's Kudos to their bank account, for a review.")
                .permissions(QbPermissions.PAYOUT_ONBOARD)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(BankWithdrawals::context)
                .outputMapper(TransferProcesses::output)
                .step("Lock the taker's wallet", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbWallets.lockName(QbWallets.TAKER, ctx.request().actorId())))
                .step("Load the parameters", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                    QbParams.TRANSFER_THRESHOLD, QbParams.TRANSFER_HOLD_DAYS, QbParams.WITHDRAWAL_BANK_ENABLED,
                    QbParams.WITHDRAWAL_BANK_FEE, QbParams.WITHDRAWAL_BANK_COOLDOWN_HOURS))
                .steps(b -> TransferProcesses.loadTaker(b, ctx -> ctx.request().actorId()))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> TransferProcesses.latest(
                        ctx.request().actorId()), LATEST))
                .step("Load what holds the taker", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> TransferProcesses.holds(
                        ctx.request().actorId()), HOLDS))
                .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("subject", QbPayouts.APPROVAL_SUBJECT),
                        new QueryPredicate.Eq("enabled", true)))).limit(1).build(), RULES))
                .steps(b -> TakerWallets.load(b, ctx -> ctx.request().actorId()))
                .steps(b -> TransferProcesses.loadBankAccount(b, ctx -> ctx.request().actorId()))
                .compute("Decide", (metadata, ctx) -> requested(ctx))
                .step("Save the withdrawal", SaveChanges.now())
                .step("Read the withdrawal", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> TransferProcesses.byId(
                        (String) ctx.get(NEW_ID)), TRANSFER))
                .step("Ask for a review", RequireApproval.<ProcessContext>when(ctx -> first(ctx, TRANSFER) != null,
                    QbPayouts.APPROVAL_SUBJECT, ctx -> TransferProcesses.approvalCase(first(ctx, TRANSFER)),
                    APPROVAL))
                .compute("Wait for the review", (metadata, ctx) -> {
                    ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
                    if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
                        // A rule is in effect yet did not ask for a person (its condition was changed), or decided at
                        // once: never paid unreviewed. Nothing is kept.
                        throw new IllegalStateException("The transfer review rule did not ask for a review of a "
                            + "withdrawal");
                    }
                    TransferProcesses.write(ctx, first(ctx, TRANSFER), Map.of("approvalRequestId",
                        approval.requestId()));
                    ctx.put(STATUS_AFTER, TransferStatus.REVIEW);
                })
                .steps(TransferProcesses::finish));
    }

    static void requested(ProcessContext ctx) {
        ParamValues params = ctx.get(PARAMS, ParamValues.class);
        EntityInstance ban = first(ctx, TransferProcesses.BAN);
        EntityInstance account = first(ctx, BANK_ACCOUNT);
        BigDecimal transferable = TransferRules.transferable(TransferProcesses.ledgerBalance(ctx),
            TransferProcesses.rewardsHeld(ctx));
        BankAccounts.Facts facts = new BankAccounts.Facts(
            params.get(QbParams.WITHDRAWAL_BANK_ENABLED, Boolean.class),
            params.get(QbParams.TRANSFER_THRESHOLD, BigDecimal.class),
            params.get(QbParams.WITHDRAWAL_BANK_FEE, BigDecimal.class),
            params.get(QbParams.WITHDRAWAL_BANK_COOLDOWN_HOURS, BigDecimal.class),
            BanRules.banned(ban == null ? null : ban.attributes()),
            TransferRules.held(TransferProcesses.past(list(ctx, HOLDS))),
            list(ctx, LATEST).stream().anyMatch(t -> TransferStatus.UNFINISHED.contains(t.get("status"))),
            account == null ? null : account.get("payoutMethod"),
            account == null ? null : account.get("changedAt"),
            first(ctx, RULES) != null, transferable, ctx.opTime());
        // The hold period must be usable for the balance read (as for the daily run).
        String reason = TransferRules.wholeAndNotNegative(params.get(QbParams.TRANSFER_HOLD_DAYS, BigDecimal.class))
            ? BankAccounts.unavailable(facts) : BankAccounts.INVALID_PARAMS;
        if (reason != null) {
            Map<String, Object> shown = new LinkedHashMap<>();
            shown.put("reason", reason);
            if (BankAccounts.COOLDOWN.equals(reason)) {
                shown.put("until", SHOWN.format(BankAccounts.usableFrom(facts.changedAt(), facts.cooldownHours())
                    .atZone(BankTransfers.ZONE)));
            }
            throw refusal(null, WalletCodes.WITHDRAWAL_UNAVAILABLE, "A withdrawal cannot be asked for now: " + reason,
                shown);
        }
        BigDecimal amount = ctx.get(INPUT, RequestInput.class).amount();
        Violation problem = BankAccounts.amountProblem(facts, amount);
        if (problem != null) {
            throw new BusinessRuleViolationException(List.of(problem));
        }
        BigDecimal fee = facts.fee();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userId", ctx.request().actorId());
        values.put("method", QbPayouts.BANK);
        // The account the withdrawal is for, by its id: the copy below is what is paid.
        values.put("externalAccountId", String.valueOf(account.id()));
        for (String field : List.of("bankCode", "bankName", "branchCode", "branchName", "accountType",
            "accountNumber", "accountHolder")) {
            values.put(field, account.get(field));
        }
        values.put("amount", amount);
        values.put("fee", fee);
        values.put("net", amount.subtract(fee));
        values.put("status", TransferStatus.REVIEW);
        values.put("reversedAmount", BigDecimal.ZERO);
        values.put("createdAt", ctx.opTime());
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(QbPayouts.TRANSFER, values)));
    }

    // ---- paying it -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PaidInput, TransferProcesses.TransferOutput, ProcessContext> paid() {
        return ProcessDefinition.define(PAID, 1, PaidInput.class, TransferProcesses.TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records an approved bank withdrawal as paid, with the bank statement's entry.")
                .permissions(QbPermissions.WITHDRAWAL_PAY)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(BankWithdrawals::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> TransferProcesses.lockAndLoad(b, ctx -> ctx.get(INPUT, PaidInput.class).transferId()))
                .compute("Read the reference", (metadata, ctx) -> ctx.put(REFERENCE,
                    BankTransfers.reference(ctx.get(INPUT, PaidInput.class).paidReference())))
                .step("Look for the statement's entry", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> EntityQuery.builder().where(
                        new QueryPredicate.And(List.of(
                            new QueryPredicate.Eq("paidOn", ctx.get(INPUT, PaidInput.class).paidOn()),
                            new QueryPredicate.Eq("paidReference", ctx.get(REFERENCE))))).limit(1).build(), USED))
                .compute("Settle", (metadata, ctx) -> {
                    PaidInput input = ctx.get(INPUT, PaidInput.class);
                    EntityInstance transfer = payable(ctx);
                    List<Violation> problems = new ArrayList<>();
                    String reference = (String) ctx.get(REFERENCE);
                    if (reference.isEmpty()) {
                        problems.add(new Violation("paidReference", WalletCodes.WITHDRAWAL_REFERENCE_USED,
                            "The statement's reference", Map.of()));
                    }
                    Violation future = BankTransfers.futureProblem("paidOn", input.paidOn(), ctx.opTime());
                    if (future != null) {
                        problems.add(future);
                    }
                    if (first(ctx, USED) != null) {
                        problems.add(new Violation("paidReference", WalletCodes.WITHDRAWAL_REFERENCE_USED,
                            "The statement's entry paid another withdrawal", Map.of()));
                    }
                    if (!problems.isEmpty()) {
                        throw new BusinessRuleViolationException(problems);
                    }
                    String userId = transfer.get("userId");
                    BigDecimal net = QbPayouts.money(transfer.get("net"));
                    ctx.put(SETTLE, true);
                    ctx.put(STRIPE_TRANSFER, null);
                    ctx.put(SETTLE_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), "Withdrawal paid",
                        "bank:" + input.paidOn() + ":" + reference, List.of(
                            new LedgerProcesses.Line(QbLedger.TRANSFERS_IN_TRANSIT, Direction.DEBIT, net, null,
                                Map.of(QbLedger.PARTY, userId)),
                            new LedgerProcesses.Line(QbLedger.BANK, Direction.CREDIT, net)),
                        QbPayouts.TRANSFER, String.valueOf(transfer.id())));
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("paidOn", input.paidOn());
                    values.put("paidReference", reference);
                    values.put("paidBy", ctx.request().actorId());
                    TransferProcesses.write(ctx, transfer, values);
                })
                .steps(TransferProcesses::settling)
                .steps(TransferProcesses::finish)
                .step("Close finance's task", CloseTasks.<ProcessContext>done(ctx -> paySource(
                    ctx.get(INPUT, PaidInput.class).transferId()))));
    }

    public static ProcessDefinition<ReturnedInput, TransferProcesses.TransferOutput, ProcessContext> returned() {
        return ProcessDefinition.define(RETURNED, 1, ReturnedInput.class, TransferProcesses.TransferOutput.class,
            ProcessContext.class, pb -> pb
                .description("Records an approved bank withdrawal the bank returned: the Kudos go back to the taker.")
                .permissions(QbPermissions.WITHDRAWAL_PAY)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory(BankWithdrawals::context)
                .outputMapper(TransferProcesses::output)
                .steps(b -> TransferProcesses.lockAndLoad(b, ctx -> ctx.get(INPUT, ReturnedInput.class)
                    .transferId()))
                .step("Load the latest transfers", QueryEntities.<ProcessContext>of(
                    QbPayouts.defaultDataset(QbPayouts.TRANSFER), ctx -> TransferProcesses.latest(
                        TransferProcesses.transferUser(ctx)), LATEST))
                .compute("Reverse", (metadata, ctx) -> {
                    EntityInstance transfer = payable(ctx);
                    String reason = ctx.get(INPUT, ReturnedInput.class).reason().strip();
                    TransferProcesses.failed(ctx, transfer, RETURNED_CODE, reason);
                    TransferProcesses.write(ctx, transfer, Map.of("returnedBy", ctx.request().actorId()));
                })
                .steps(TransferProcesses::reversing)
                .steps(TransferProcesses::finish)
                .step("Close finance's task", CloseTasks.<ProcessContext>done(ctx -> paySource(
                    ctx.get(INPUT, ReturnedInput.class).transferId()))));
    }

    /** The process's withdrawal, approved and waiting to be paid, of someone else; refused otherwise. */
    private static EntityInstance payable(ProcessContext ctx) {
        EntityInstance transfer = first(ctx, TRANSFER);
        if (!TransferProcesses.bank(transfer) || !TransferStatus.SENDING.equals(transfer.get("status"))) {
            throw refusal("transferId", WalletCodes.WITHDRAWAL_STATE, "Not a bank withdrawal waiting to be paid",
                Map.of("status", transfer == null ? "-" : String.valueOf((Object) transfer.get("status"))));
        }
        if (ctx.request().actorId().equals(transfer.get("userId"))) {
            throw refusal("transferId", WalletCodes.WITHDRAWAL_OWN, "Your own withdrawal is paid by someone else",
                Map.of());
        }
        return transfer;
    }

    // ---- tasks -----------------------------------------------------------------------------------------------------

    /** An approved withdrawal to pay in the bank ({@code qb.withdrawal.pay}); closed when paid or returned. */
    static TaskSpec payTask(EntityInstance transfer) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("transfer", TopUpTasks.shown(String.valueOf(transfer.id())));
        params.put("amount", TopUpTasks.shown(QbPayouts.money(transfer.get("net")).toPlainString()));
        params.put("holder", TopUpTasks.shown(transfer.get("accountHolder")));
        return TaskSpec.forPermission(PAY_TASK, "task." + PAY_TASK, params, QbPermissions.WITHDRAWAL_PAY)
            .source(paySource(String.valueOf(transfer.id()))).about(QbPayouts.TRANSFER, String.valueOf(transfer.id()));
    }

    static String paySource(String transferId) {
        return "withdrawal:" + transferId + ":pay";
    }

    /** A taker gave a bank account that is another user's: finance looks at both (the number by its last digits). */
    static TaskSpec inUseTask(String userId, EntityInstance other) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", TopUpTasks.shown(userId));
        params.put("other", TopUpTasks.shown(other.get("userId")));
        params.put("account", "****" + last4(other.get("accountNumber")));
        return TaskSpec.forPermission(IN_USE_TASK, "task." + IN_USE_TASK, params, QbPermissions.RECONCILE)
            .source("bank-account-in-use:" + userId + ":" + other.id())
            .about(QbPayouts.BANK_ACCOUNT, String.valueOf(other.id()));
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    private static BusinessRuleViolationException refusal(String field, String code, String message,
        Map<String, Object> params) {
        return new BusinessRuleViolationException(List.of(new Violation(field, code, message, params)));
    }

    private static String last4(Object number) {
        String text = number == null ? "" : number.toString();
        return text.length() <= 4 ? text : text.substring(text.length() - 4);
    }

    private static ProcessContext context(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private BankWithdrawals() {}
}
