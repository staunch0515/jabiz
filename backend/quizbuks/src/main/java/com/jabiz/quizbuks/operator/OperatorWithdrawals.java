package com.jabiz.quizbuks.operator;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.TemporalRole;
import com.jabiz.entity.Violation;
import com.jabiz.ledger.Direction;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.BankAccounts;
import com.jabiz.quizbuks.wallet.BankTransfers;
import com.jabiz.quizbuks.wallet.QbLedger;
import com.jabiz.quizbuks.wallet.QbPayouts;
import com.jabiz.quizbuks.wallet.TransferProcesses;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.ledger.LedgerProcesses;
import com.jabiz.param.ParamValues;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The operators' accounts (docs/quizbuks/plans/Q48e-operator-isolation.md, Q48e-2): the money stays with the platform;
 * each month's settlement credits what is payable to the operator's account, and the operator withdraws from it to its
 * bank account. The balance is the settlements' payable less the withdrawals not rejected or cancelled (template
 * {@value #BALANCE_TEMPLATE}); a withdrawal is paid by finance and posted only once another administrator approved
 * the payment ({@value #APPROVAL_SUBJECT}). The platform bears the bank's fee: the amount asked for is paid.
 *
 * <p>Locks: {@code qb.operator:<operator>} first, then the caller's membership (the order of every operator's act);
 * the operator's own acts take its id from the session's entry scope, which the membership then confirms.
 */
public final class OperatorWithdrawals {

    public static final String BANK_ACCOUNT = "QbOperatorBankAccount";
    public static final String WITHDRAWAL = "QbOperatorWithdrawal";
    public static final String BANK_ACCOUNT_SAVE = "QB_OPERATOR_BANK_ACCOUNT_SAVE";
    public static final String REQUEST = "QB_OPERATOR_WITHDRAWAL_REQUEST";
    public static final String CANCEL = "QB_OPERATOR_WITHDRAWAL_CANCEL";
    public static final String PAY = "QB_OPERATOR_WITHDRAWAL_PAY";
    public static final String REJECT = "QB_OPERATOR_WITHDRAWAL_REJECT";
    public static final String APPROVAL_RESULT = "QB_OPERATOR_WITHDRAWAL_APPROVAL_RESULT";
    public static final String APPROVAL_SUBJECT = "qb.operator-withdrawal";
    public static final String REVIEW_RULE = "qb.operator-withdrawal.review";
    public static final String BALANCE_TEMPLATE = "qb.system.operator-balance";
    public static final String STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:operator-withdrawal-status";
    static final String PAYMENT_DESCRIPTION = "Operator withdrawal paid";

    public static final String REQUESTED = "REQUESTED";
    public static final String PAYING = "PAYING";
    public static final String PAID = "PAID";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final List<String> STATUSES = List.of(REQUESTED, PAYING, PAID, REJECTED, CANCELLED);
    /** The withdrawals that take from the balance. */
    public static final List<String> TAKEN = List.of(REQUESTED, PAYING, PAID);

    public static final EntityDefinition BANK_ACCOUNT_ENTITY = EntityDefinition.define(BANK_ACCOUNT, eb -> {
        eb.physicalTable("qb_operator_bank_account");
        eb.primaryKey("bankAccountId");
        eb.field("bankAccountId", f -> f.physicalColumn("bank_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-bank-account"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true).processOnly()
            .asReference(QbOperators.OPERATOR));
        QbPayouts.bankFields(eb, false);
        // When the account was last changed: no withdrawal until the cooling-off period is over.
        eb.field("changedAt", f -> f.physicalColumn("changed_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("changedBy", f -> f.physicalColumn("changed_by").required(true).processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_operator_bank_account_operator", "operatorId");
        eb.listView("default", lv -> lv
            .columns("operatorId", "bankName", "branchName", "accountType", "accountNumber", "accountHolder",
                "changedAt")
            .filters("operatorId")
            .sorts("changedAt")
            .defaultSort("changedAt", false));
    });

    public static final EntityDefinition WITHDRAWAL_ENTITY = EntityDefinition.define(WITHDRAWAL, eb -> {
        eb.physicalTable("qb_operator_withdrawal");
        eb.primaryKey("withdrawalId");
        eb.field("withdrawalId", f -> f.physicalColumn("withdrawal_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:operator-withdrawal"));
        eb.field("operatorId", f -> f.physicalColumn("operator_id").immutable(true).required(true)
            .asReference(QbOperators.OPERATOR));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true)
            .asCode(STATUS_DICTIONARY, STATUSES.toArray(String[]::new)));
        eb.field("requestedBy", f -> f.physicalColumn("requested_by").immutable(true).required(true).asText(128));
        eb.field("requestedAt", f -> f.physicalColumn("requested_at").immutable(true).required(true)
            .asTemporal(TemporalRole.EVENT_TIME));
        // The account as it was when asked: a later change does not move money already on its way.
        QbPayouts.bankFields(eb, true);
        eb.field("paidOn", f -> f.physicalColumn("paid_on").asDate());
        eb.field("bankReference", f -> f.physicalColumn("bank_reference").asText(100));
        eb.field("paymentRecordedBy", f -> f.physicalColumn("payment_recorded_by").asText(128));
        eb.field("paymentRecordedAt", f -> f.physicalColumn("payment_recorded_at")
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").asText(36));
        eb.field("paymentTransactionId", f -> f.physicalColumn("payment_transaction_id").asText(36));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("rejectReason", f -> f.physicalColumn("reject_reason").asText(500));
        eb.field("closedAt", f -> f.physicalColumn("closed_at").asTemporal(TemporalRole.EVENT_TIME));
        eb.field("revision", f -> f.physicalColumn("revision").required(true).asNumeric(9, 0));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.listView("default", lv -> lv
            .columns("operatorId", "amount", "status", "requestedAt", "paidOn", "paidAt")
            .filters("operatorId", "status")
            .sorts("requestedAt", "amount")
            .defaultSort("requestedAt", false));
    });

    // ---- inputs and outputs ------------------------------------------------------------------------------------------

    public record BankAccountInput(@NotBlank @Size(max = 8) String bankCode, @NotBlank @Size(max = 60) String bankName,
        @NotBlank @Size(max = 8) String branchCode, @NotBlank @Size(max = 60) String branchName,
        @NotBlank @Size(max = 10) String accountType, @NotBlank @Size(max = 10) String accountNumber,
        @NotBlank @Size(max = 60) String accountHolder) {

        @Override
        public String toString() {
            return "BankAccountInput[" + bankCode + "-" + branchCode + ", " + accountType + ", ***]";
        }
    }

    public record BankAccountOutput(String bankAccountId, Instant usableFrom) {}

    /** @param amount whole yen, at least {@code qb.operator.withdrawal.min}, at most the balance */
    public record RequestInput(@NotNull @DecimalMin("1") BigDecimal amount) {}

    public record WithdrawalRef(@NotBlank @Size(max = 36) String withdrawalId) {}

    /**
     * @param paidOn        the bank statement's day of the payment, in Japan
     * @param bankReference the statement's reference of the transfer
     */
    public record PayInput(@NotBlank @Size(max = 36) String withdrawalId, @NotNull LocalDate paidOn,
        @NotBlank @Size(max = BankTransfers.REFERENCE_LENGTH) String bankReference) {}

    /** @param reason told to the operator */
    public record RejectInput(@NotBlank @Size(max = 36) String withdrawalId, @NotBlank @Size(max = 500) String reason) {}

    public record WithdrawalOutput(String withdrawalId, String status, BigDecimal amount, String approvalRequestId) {}

    static final String INPUT = "input";
    static final String OPERATOR_ID = "operatorId";
    static final String ACCOUNT = "account";
    static final String BALANCE = "balance";
    static final String OPEN = "open";
    static final String PARAMS = "params";
    static final String FOUND = "found";
    static final String ROW = "row";
    static final String NEW_ID = "newId";
    static final String RULES = "rules";
    static final String APPROVAL = "approval";
    static final String REQUEST_ROW = "request";
    static final String POST_INPUT = "postInput";
    static final String POSTING = "posting";
    static final String STATUS_AFTER = "statusAfter";
    static final String SAVED = "saved";

    private OperatorWithdrawals() {}

    // ---- the operator's own: its scope's operator, its lead ----------------------------------------------------------

    /**
     * The operator's lead acts for it: the operator's lock (the session's entry scope names it), then the membership,
     * which must be that operator's lead.
     */
    private static <I, O> void lead(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Lock the operator", HoldLock.<ProcessContext>exclusive(
                ctx -> QbOperators.operatorLockName(scopedOperator(ctx))))
            .steps(OperatorChecks::member)
            .compute("The caller is its lead", (metadata, ctx) -> {
                if (!OperatorChecks.operatorId(ctx).equals(scopedOperator(ctx))) {
                    throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_MEMBER,
                        "The caller is no member of the session's operator", Map.of()));
                }
                if (!Boolean.TRUE.equals(ctx.get(OperatorChecks.LEAD))) {
                    throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_LEAD,
                        "Only the operator's lead handles its money", Map.of()));
                }
                ctx.put(OPERATOR_ID, OperatorChecks.operatorId(ctx));
            });
    }

    /** The operator of the caller's session (the operators' entry); refused from any other entry. */
    static String scopedOperator(ProcessContext ctx) {
        com.jabiz.context.EntryScope scope = ctx.request().entryScope();
        if (scope == null || !scope.is(QbOperators.SCOPE)) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.NOT_MEMBER,
                "An operator's money is handled at the operators' entry", Map.of()));
        }
        return scope.value();
    }

    // ---- the bank account ----------------------------------------------------------------------------------------------

    public static ProcessDefinition<BankAccountInput, BankAccountOutput, ProcessContext> saveBankAccount() {
        return ProcessDefinition.define(BANK_ACCOUNT_SAVE, 1, BankAccountInput.class, BankAccountOutput.class,
            ProcessContext.class, pb -> {
                pb.description("The operator's lead sets the bank account its withdrawals are paid to.")
                    .permissions(QbPermissions.OPERATOR_USE)
                    .requiresVerifiedEmail()
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .contextFactory(OperatorWithdrawals::context)
                    .outputMapper(ctx -> ctx.get(SAVED, BankAccountOutput.class));
                lead(pb);
                pb.step("Load the account and the cooling-off", QueryEntities.<ProcessContext>of(
                        QbOperators.defaultDataset(BANK_ACCOUNT), ctx -> ofOperator(ctx.get(OPERATOR_ID)), ACCOUNT))
                    .step("Load the hours", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS))
                    .compute("Save the account", (metadata, ctx) -> saveAccount(ctx));
            });
    }

    static void saveAccount(ProcessContext ctx) {
        BankAccountInput input = ctx.get(INPUT, BankAccountInput.class);
        BankAccounts.Account account = BankAccounts.normalise(input.bankCode(), input.bankName(), input.branchCode(),
            input.branchName(), input.accountType(), input.accountNumber(), input.accountHolder());
        List<Violation> problems = BankAccounts.problems(account);
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems.stream().map(problem -> new Violation(problem.field(),
                OperatorCodes.BANK_ACCOUNT, problem.message(), problem.params())).toList());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bankCode", account.bankCode());
        values.put("bankName", account.bankName());
        values.put("branchCode", account.branchCode());
        values.put("branchName", account.branchName());
        values.put("accountType", account.accountType());
        values.put("accountNumber", account.accountNumber());
        values.put("accountHolder", account.accountHolder());
        values.put("changedAt", ctx.opTime());
        values.put("changedBy", ctx.request().actorId());
        EntityInstance stored = first(ctx, ACCOUNT);
        String id;
        if (stored == null) {
            values.put("operatorId", ctx.get(OPERATOR_ID));
            id = String.valueOf(ctx.changes().insert(BANK_ACCOUNT, values));
        } else {
            ctx.changes().update(BANK_ACCOUNT, stored.id(), stored.version(), values);
            id = String.valueOf(stored.id());
        }
        ctx.put(SAVED, new BankAccountOutput(id, BankAccounts.usableFrom(ctx.opTime(), cooldown(ctx))));
    }

    private static BigDecimal cooldown(ProcessContext ctx) {
        return ctx.get(PARAMS, ParamValues.class).get(QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS, BigDecimal.class);
    }

    // ---- asking for a withdrawal ---------------------------------------------------------------------------------------

    public static ProcessDefinition<RequestInput, WithdrawalOutput, ProcessContext> request() {
        return ProcessDefinition.define(REQUEST, 1, RequestInput.class, WithdrawalOutput.class, ProcessContext.class,
            pb -> {
                pb.description("The operator's lead asks for a withdrawal of its account's balance to its bank "
                        + "account.")
                    .permissions(QbPermissions.OPERATOR_USE)
                    .requiresVerifiedEmail()
                    .requiresMfa(MfaRequirement.ALWAYS)
                    .contextFactory(OperatorWithdrawals::context)
                    .outputMapper(OperatorWithdrawals::output);
                lead(pb);
                pb.step("Load the account and the open withdrawal", QueryEntities.<ProcessContext>all(
                        QueryEntities.query(QbOperators.defaultDataset(BANK_ACCOUNT),
                            ctx -> ofOperator(ctx.get(OPERATOR_ID)), ACCOUNT),
                        QueryEntities.query(QbOperators.defaultDataset(WITHDRAWAL), ctx -> EntityQuery.builder()
                            .where(new QueryPredicate.And(List.of(
                                new QueryPredicate.In("operatorId", List.of(String.valueOf(ctx.get(OPERATOR_ID)))),
                                new QueryPredicate.In("status", List.of(REQUESTED, PAYING))))).limit(1).build(),
                            OPEN)))
                    .step("Load the least and the hours", LoadParams.<ProcessContext>of(ProcessContext::opTime, PARAMS,
                        QbParams.OPERATOR_WITHDRAWAL_MIN, QbParams.OPERATOR_WITHDRAWAL_COOLDOWN_HOURS))
                    .step("Read the balance", RunTemplate.<ProcessContext>of(BALANCE_TEMPLATE,
                        ctx -> Map.of("operatorId", ctx.get(OPERATOR_ID)), BALANCE))
                    .compute("Ask for it", (metadata, ctx) -> requested(ctx))
                    .step("Save the request", SaveChanges.now())
                    .step("Read it back", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                        ctx -> byId(ctx.contains(NEW_ID) ? (String) ctx.get(NEW_ID) : null), ROW));
            });
    }

    static void requested(ProcessContext ctx) {
        RequestInput input = ctx.get(INPUT, RequestInput.class);
        EntityInstance account = first(ctx, ACCOUNT);
        List<Violation> problems = new ArrayList<>();
        if (account == null) {
            problems.add(new Violation(null, OperatorCodes.WITHDRAWAL_NO_ACCOUNT, "Set the bank account first",
                Map.of()));
        } else {
            Instant from = BankAccounts.usableFrom(instant(account.get("changedAt")), cooldown(ctx));
            if (ctx.opTime().isBefore(from)) {
                problems.add(new Violation(null, OperatorCodes.WITHDRAWAL_COOLDOWN, "The bank account changed "
                    + "lately", Map.of("from", from.toString())));
            }
        }
        if (first(ctx, OPEN) != null) {
            problems.add(new Violation(null, OperatorCodes.WITHDRAWAL_OPEN, "A withdrawal is open already",
                Map.of()));
        }
        BigDecimal amount = input.amount();
        BigDecimal least = ctx.get(PARAMS, ParamValues.class).get(QbParams.OPERATOR_WITHDRAWAL_MIN, BigDecimal.class);
        BigDecimal balance = balance(ctx);
        if (amount.stripTrailingZeros().scale() > 0 || least == null || amount.compareTo(least) < 0
            || amount.compareTo(balance) > 0) {
            problems.add(new Violation("amount", OperatorCodes.WITHDRAWAL_AMOUNT, "Whole yen from the least to the "
                + "balance", Map.of("min", least == null ? "" : least.toPlainString(),
                "balance", balance.toPlainString())));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("operatorId", ctx.get(OPERATOR_ID));
        values.put("amount", amount.setScale(0));
        values.put("status", REQUESTED);
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedAt", ctx.opTime());
        for (String field : List.of("bankCode", "bankName", "branchCode", "branchName", "accountType",
            "accountNumber", "accountHolder")) {
            values.put(field, account.get(field));
        }
        values.put("revision", BigDecimal.ONE);
        ctx.put(NEW_ID, String.valueOf(ctx.changes().insert(WITHDRAWAL, values)));
    }

    /** The balance the template read: the payable credited less the withdrawals that take from it. */
    @SuppressWarnings("unchecked")
    static BigDecimal balance(ProcessContext ctx) {
        Object found = ctx.contains(BALANCE) ? ctx.get(BALANCE) : null;
        List<Map<String, Object>> rows = found instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        Object value = rows.isEmpty() ? null : rows.getFirst().get("balance");
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    // ---- the operator cancels, finance rejects -------------------------------------------------------------------------

    public static ProcessDefinition<WithdrawalRef, WithdrawalOutput, ProcessContext> cancel() {
        return ProcessDefinition.define(CANCEL, 1, WithdrawalRef.class, WithdrawalOutput.class, ProcessContext.class,
            pb -> {
                pb.description("The operator's lead cancels a withdrawal it asked for, before its payment is "
                        + "recorded.")
                    .permissions(QbPermissions.OPERATOR_USE)
                    .requiresVerifiedEmail()
                    .contextFactory(OperatorWithdrawals::context)
                    .outputMapper(OperatorWithdrawals::output);
                lead(pb);
                pb.step("Load the withdrawal", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                        ctx -> byId(ctx.get(INPUT, WithdrawalRef.class).withdrawalId()), ROW))
                    .compute("Cancel it", (metadata, ctx) -> {
                        EntityInstance row = first(ctx, ROW);
                        // Another operator's is not found, as one that does not exist.
                        if (row == null || !String.valueOf(ctx.get(OPERATOR_ID)).equals(row.get("operatorId"))) {
                            throw new EntityNotFoundException(WITHDRAWAL + " "
                                + ctx.get(INPUT, WithdrawalRef.class).withdrawalId() + " not found");
                        }
                        close(ctx, row, CANCELLED, null);
                    });
            });
    }

    public static ProcessDefinition<RejectInput, WithdrawalOutput, ProcessContext> reject() {
        return ProcessDefinition.define(REJECT, 1, RejectInput.class, WithdrawalOutput.class, ProcessContext.class,
            pb -> pb
                .description("Finance rejects a withdrawal before its payment is recorded, saying why to the "
                    + "operator.")
                .permissions(QbPermissions.OPERATOR_SETTLEMENT_PAY)
                .requiresVerifiedEmail()
                .actsOn(WITHDRAWAL, "withdrawalId", a -> a.whenField("status", REQUESTED))
                .contextFactory(OperatorWithdrawals::context)
                .outputMapper(OperatorWithdrawals::output)
                .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, RejectInput.class).withdrawalId()))
                .compute("Reject it", (metadata, ctx) -> close(ctx, first(ctx, ROW), REJECTED,
                    ctx.get(INPUT, RejectInput.class).reason().strip())));
    }

    static void close(ProcessContext ctx, EntityInstance row, String status, String reason) {
        if (!REQUESTED.equals(row.get("status"))) {
            throw new BusinessRuleViolationException(new Violation(null, OperatorCodes.WITHDRAWAL_STATE,
                "Only a withdrawal whose payment is not recorded is closed", Map.of("status",
                String.valueOf((Object) row.get("status")))));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status);
        values.put("closedAt", ctx.opTime());
        if (reason != null) {
            values.put("rejectReason", reason);
        }
        values.put("revision", number(row.get("revision")).add(BigDecimal.ONE));
        ctx.put(STATUS_AFTER, status);
        ctx.changes().update(WITHDRAWAL, row.id(), row.version(), values);
    }

    // ---- the payment ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PayInput, WithdrawalOutput, ProcessContext> pay() {
        return ProcessDefinition.define(PAY, 1, PayInput.class, WithdrawalOutput.class, ProcessContext.class, pb -> pb
            .description("Records the bank payment of an operator's withdrawal, for another administrator's "
                + "approval; nothing is posted before it.")
            .permissions(QbPermissions.OPERATOR_SETTLEMENT_PAY)
            .requiresVerifiedEmail()
            .actsOn(WITHDRAWAL, "withdrawalId", a -> a.whenField("status", REQUESTED))
            .contextFactory(OperatorWithdrawals::context)
            .outputMapper(OperatorWithdrawals::output)
            .steps(b -> lockAndLoad(b, ctx -> ctx.get(INPUT, PayInput.class).withdrawalId()))
            .step("Load the review rule", QueryEntities.<ProcessContext>of(ApprovalEntities.RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("subject", APPROVAL_SUBJECT), new QueryPredicate.Eq("enabled", true))))
                    .limit(1).build(), RULES))
            .compute("Record the payment", (metadata, ctx) -> recordPayment(ctx))
            // Written first, so that the approval binds what is stored (read back, as its result reads it).
            .step("Save the payment", SaveChanges.now())
            .step("Read it back", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                ctx -> byId(ctx.get(INPUT, PayInput.class).withdrawalId()), ROW))
            .step("Ask for another administrator's approval", RequireApproval.<ProcessContext>of(APPROVAL_SUBJECT,
                ctx -> approvalCase(first(ctx, ROW)), APPROVAL))
            .compute("Wait for the approval", (metadata, ctx) -> waitForApproval(ctx)));
    }

    /** Finds the withdrawal, takes its operator's lock and reads it again (404 when there is none). */
    private static <I, O> void lockAndLoad(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        java.util.function.Function<ProcessContext, String> withdrawalId) {
        pb.step("Find the withdrawal", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                ctx -> byId(withdrawalId.apply(ctx)), FOUND))
            .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null ? null
                : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
            .step("Read it again", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                ctx -> byId(first(ctx, FOUND) == null ? null : withdrawalId.apply(ctx)), ROW))
            .compute("Check it", (metadata, ctx) -> {
                if (first(ctx, ROW) == null) {
                    throw new EntityNotFoundException(WITHDRAWAL + " " + withdrawalId.apply(ctx) + " not found");
                }
            });
    }

    static void recordPayment(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        PayInput input = ctx.get(INPUT, PayInput.class);
        List<Violation> problems = new ArrayList<>();
        if (!REQUESTED.equals(row.get("status"))) {
            problems.add(new Violation(null, OperatorCodes.WITHDRAWAL_STATE, "Only a withdrawal asked for is paid",
                Map.of("status", String.valueOf((Object) row.get("status")))));
        }
        Violation future = BankTransfers.futureProblem("paidOn", input.paidOn(), ctx.opTime());
        if (future != null) {
            problems.add(future);
        }
        if (first(ctx, RULES) == null) {
            problems.add(noReview());
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paidOn", input.paidOn());
        values.put("bankReference", BankTransfers.reference(input.bankReference()));
        values.put("paymentRecordedBy", ctx.request().actorId());
        values.put("paymentRecordedAt", ctx.opTime());
        values.put("revision", number(row.get("revision")).add(BigDecimal.ONE));
        ctx.changes().update(WITHDRAWAL, row.id(), row.version(), values);
    }

    private static Violation noReview() {
        return new Violation(null, OperatorCodes.WITHDRAWAL_NO_REVIEW, "No approval rule asks for a second person on "
            + "the operators' withdrawals", Map.of());
    }

    static void waitForApproval(ProcessContext ctx) {
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval == null || approval.status() != ApprovalOutcome.Status.PENDING) {
            // A rule whose condition no longer applies: never paid by one person. Nothing is kept.
            throw new BusinessRuleViolationException(List.of(noReview()));
        }
        EntityInstance row = first(ctx, ROW);
        ctx.put(STATUS_AFTER, PAYING);
        ctx.changes().update(WITHDRAWAL, row.id(), row.version(), Map.of("status", PAYING,
            "approvalRequestId", approval.requestId()));
    }

    /**
     * The case: the rule's facts and the content approved (whose, how much, to which account, the statement's day and
     * reference), from the stored record. The recorder is the preparer: the platform refuses their approval.
     */
    static ApprovalCase approvalCase(EntityInstance row) {
        BigDecimal amount = number(row.get("amount")).setScale(0);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("operatorWithdrawal", true);
        facts.put("amount", amount);
        facts.put("operatorId", row.get("operatorId"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("withdrawalId", String.valueOf(row.id()));
        content.put("operatorId", row.get("operatorId"));
        content.put("amount", amount);
        content.put("bankCode", row.get("bankCode"));
        content.put("branchCode", row.get("branchCode"));
        content.put("accountNumber", row.get("accountNumber"));
        content.put("accountHolder", row.get("accountHolder"));
        Object paidOn = row.get("paidOn");
        content.put("paidOn", paidOn == null ? null : paidOn.toString());
        content.put("bankReference", row.get("bankReference"));
        return ApprovalCase.of(String.valueOf(row.id()), facts, content)
            .preparedBy(row.get("paymentRecordedBy"))
            .reference(amount.toPlainString() + " " + row.get("operatorId"));
    }

    // ---- the approval --------------------------------------------------------------------------------------------------

    public static ProcessDefinition<TransferProcesses.ApprovalResultInput, WithdrawalOutput, ProcessContext>
        approvalResult() {
        return ProcessDefinition.define(APPROVAL_RESULT, 1, TransferProcesses.ApprovalResultInput.class,
            WithdrawalOutput.class, ProcessContext.class, pb -> pb
                .description("Posts an operator's withdrawal once another administrator approved its payment, or "
                    + "returns it to asked for when they rejected it.")
                .permissions(QbPermissions.OPERATOR_SETTLEMENT_SYSTEM)
                .internal()
                .contextFactory(OperatorWithdrawals::context)
                .outputMapper(OperatorWithdrawals::output)
                .step("Find the withdrawal", QueryEntities.<ProcessContext>of(QbOperators.defaultDataset(WITHDRAWAL),
                    ctx -> byId(APPROVAL_SUBJECT.equals(resultInput(ctx).subject()) ? resultInput(ctx).entityId()
                        : null), FOUND))
                .step("Lock the operator", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null
                    ? null : QbOperators.operatorLockName(first(ctx, FOUND).get("operatorId"))))
                .step("Read it again, and the request", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbOperators.defaultDataset(WITHDRAWAL), ctx -> byId(first(ctx, FOUND) == null
                        ? null : String.valueOf(first(ctx, FOUND).id())), ROW),
                    QueryEntities.query(ApprovalEntities.REQUEST_DATASET, ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.In("requestId", resultInput(ctx).requestId() == null
                            ? new ArrayList<>() : new ArrayList<>(List.of(resultInput(ctx).requestId()))))
                        .limit(1).build(), REQUEST_ROW)))
                .step("Check the approval", RequireApproval.<ProcessContext>when(
                    ctx -> ownRequest(ctx) && ApprovalEntities.APPROVED.equals(resultInput(ctx).status()),
                    APPROVAL_SUBJECT, ctx -> approvalCase(first(ctx, ROW)).preparedBy(resultInput(ctx).preparerId()),
                    APPROVAL))
                .compute("Pay or return", (metadata, ctx) -> decided(ctx))
                .step("Post the payment", CallProcess.<ProcessContext>when(ctx -> ctx.contains(POST_INPUT),
                    LedgerProcesses.POST, 1, ctx -> ctx.get(POST_INPUT), POSTING))
                .compute("Mark it paid", (metadata, ctx) -> paid(ctx)));
    }

    private static boolean ownRequest(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        return row != null && PAYING.equals(row.get("status")) && input.requestId() != null
            && input.requestId().equals(row.get("approvalRequestId"));
    }

    static void decided(ProcessContext ctx) {
        if (!ownRequest(ctx)) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        TransferProcesses.ApprovalResultInput input = resultInput(ctx);
        if (ApprovalEntities.APPROVED.equals(input.status())) {
            ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
            if (approval == null || approval.status() != ApprovalOutcome.Status.APPROVED
                || !input.requestId().equals(approval.requestId())) {
                return;
            }
            BigDecimal amount = number(row.get("amount"));
            // The account the operator's balance was owed in (2140) pays out of the bank (1120).
            ctx.put(POST_INPUT, new LedgerProcesses.PostInput(ctx.opTime(), PAYMENT_DESCRIPTION,
                "opw_" + String.valueOf(row.id()).substring(0, 8), List.of(
                    new LedgerProcesses.Line(QbLedger.OPERATOR_SHARES_PAYABLE, Direction.DEBIT, amount),
                    new LedgerProcesses.Line(QbLedger.BANK, Direction.CREDIT, amount)),
                WITHDRAWAL, String.valueOf(row.id())));
        } else if (ApprovalEntities.REJECTED.equals(input.status()) && rejected(ctx, row)) {
            // The reviewer's note stays with the approval; finance records the payment again.
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("status", REQUESTED);
            values.put("paidOn", null);
            values.put("bankReference", null);
            values.put("paymentRecordedBy", null);
            values.put("paymentRecordedAt", null);
            values.put("approvalRequestId", null);
            values.put("revision", number(row.get("revision")).add(BigDecimal.ONE));
            ctx.put(STATUS_AFTER, REQUESTED);
            ctx.changes().update(WITHDRAWAL, row.id(), row.version(), values);
        }
    }

    /** Whether the stored request (not the event) says that the payment, as recorded, was rejected. */
    private static boolean rejected(ProcessContext ctx, EntityInstance row) {
        EntityInstance request = first(ctx, REQUEST_ROW);
        if (request == null) {
            return false;
        }
        String hash = ContentHash.of(approvalCase(row).content());
        return ApprovalEntities.REJECTED.equals(request.get("status"))
            && APPROVAL_SUBJECT.equals(request.get("subject"))
            && String.valueOf(row.id()).equals(request.get("entityId"))
            && hash.equals(String.valueOf((Object) request.get("contentHash")).strip());
    }

    static void paid(ProcessContext ctx) {
        if (!ctx.contains(POSTING)) {
            return;
        }
        EntityInstance row = first(ctx, ROW);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", PAID);
        values.put("paidAt", ctx.opTime());
        values.put("closedAt", ctx.opTime());
        values.put("paymentTransactionId", ctx.get(POSTING, LedgerProcesses.PostOutput.class).transactionId());
        values.put("revision", number(row.get("revision")).add(BigDecimal.ONE));
        ctx.put(STATUS_AFTER, PAID);
        ctx.changes().update(WITHDRAWAL, row.id(), row.version(), values);
    }

    // ---- shared --------------------------------------------------------------------------------------------------------

    static EntityQuery ofOperator(Object operatorId) {
        return EntityQuery.builder().where(new QueryPredicate.In("operatorId", operatorId == null ? List.of()
            : List.of(String.valueOf(operatorId)))).limit(1).build();
    }

    static EntityQuery byId(String withdrawalId) {
        List<Object> ids = new ArrayList<>();
        if (withdrawalId != null) {
            ids.add(withdrawalId);
        }
        return EntityQuery.builder().where(new QueryPredicate.In("withdrawalId", ids)).limit(1).build();
    }

    private static ProcessContext context(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private static TransferProcesses.ApprovalResultInput resultInput(ProcessContext ctx) {
        return ctx.get(INPUT, TransferProcesses.ApprovalResultInput.class);
    }

    private static WithdrawalOutput output(ProcessContext ctx) {
        EntityInstance row = first(ctx, ROW);
        if (row == null) {
            return new WithdrawalOutput(null, null, null, null);
        }
        String status = ctx.contains(STATUS_AFTER) ? (String) ctx.get(STATUS_AFTER) : row.get("status");
        ApprovalOutcome approval = ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class) : null;
        String requestId = approval != null && approval.status() == ApprovalOutcome.Status.PENDING
            ? approval.requestId() : row.get("approvalRequestId");
        return new WithdrawalOutput(String.valueOf(row.id()), status, number(row.get("amount")), requestId);
    }

    static Instant instant(Object value) {
        return value instanceof Instant time ? time : value == null ? null : Instant.parse(value.toString());
    }

    static BigDecimal number(Object value) {
        return value == null ? BigDecimal.ZERO : value instanceof BigDecimal decimal ? decimal
            : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    static EntityInstance first(ProcessContext ctx, String key) {
        Object found = ctx.contains(key) ? ctx.get(key) : null;
        List<EntityInstance> rows = found instanceof List<?> list ? (List<EntityInstance>) list : List.of();
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
