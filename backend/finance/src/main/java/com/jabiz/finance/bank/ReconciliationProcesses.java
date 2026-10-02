package com.jabiz.finance.bank;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.report.ReportProcesses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.finance.bank.BankAccountProcesses.code;
import static com.jabiz.finance.bank.BankAccountProcesses.list;

/**
 * Reconciling a bank account (FIN-BK-007, 008), the accountant's and then a reviewer's:
 * <ul>
 *   <li>{@code FIN_BANK_REC_PREPARE}: works out the reconciliation for one of its statements' closing days by the
 *       template {@code finance.bank.reconciliation}; again as often as needed until it is completed.</li>
 *   <li>{@code FIN_BANK_REC_COMPLETE}: by who prepared it, when the difference is zero and every earlier
 *       reconciliation of the account is signed off: the figures are worked out once more and the platform asks the
 *       approval of subject {@code fin.bank.reconciliation} (rule {@code FIN-BANK-REC}: a reviewer of
 *       reconciliations, never the preparer); without a rule nothing is completed.</li>
 *   <li>{@code FIN_BANK_REC_APPROVAL_RESULT}: run by the platform's approval events, trusting only the platform's
 *       request. Approved, and still showing what was approved, the reconciliation is signed off by the reviewer;
 *       when the books moved under it, or it was rejected, it is prepared again.</li>
 *   <li>{@code FIN_BANK_REC_WITHDRAW}: by who completed it, while it waits: prepared again, its request withdrawn.
 *       Completed again with content approved before, that approval stands and it is signed off at once.</li>
 *   <li>{@code FIN_BANK_REC_ISSUE}: issues a signed-off reconciliation once through {@code REPORT_ISSUE}, with its
 *       preparer and reviewer, read as the books were at the sign-off; the archive reprints it exactly however the
 *       books move on. A user issues it: the platform issues reports only for callers holding the template's
 *       permissions, which the system actor running the approval events does not.</li>
 * </ul>
 */
public final class ReconciliationProcesses {

    public static final String PREPARE = "FIN_BANK_REC_PREPARE";
    public static final String COMPLETE = "FIN_BANK_REC_COMPLETE";
    public static final String APPROVAL_RESULT = "FIN_BANK_REC_APPROVAL_RESULT";
    public static final String ISSUE_REPORT = "FIN_BANK_REC_ISSUE";
    public static final String WITHDRAW = "FIN_BANK_REC_WITHDRAW";

    public static final String SUBJECT = "fin.bank.reconciliation";
    public static final String TEMPLATE = "finance.bank.reconciliation";

    public static final String NO_STATEMENT = "FIN_BANK_REC_NO_STATEMENT";
    public static final String NOT_PREPARED = "FIN_BANK_REC_NOT_PREPARED";
    public static final String DIFFERENCE = "FIN_BANK_REC_DIFFERENCE";
    public static final String EARLIER_OPEN = "FIN_BANK_REC_EARLIER_OPEN";
    public static final String NOT_PREPARER = "FIN_BANK_REC_NOT_PREPARER";
    public static final String NO_RULE = "FIN_BANK_REC_NO_RULE";
    public static final String NOT_FOUND = "FIN_BANK_REC_NOT_FOUND";
    public static final String NOT_SIGNED_OFF = "FIN_BANK_REC_NOT_SIGNED_OFF";
    public static final String ISSUED_ALREADY = "FIN_BANK_REC_ISSUED";
    public static final String NOT_SUBMITTED = "FIN_BANK_REC_NOT_SUBMITTED";

    public record PrepareInput(@NotBlank @Size(max = 20) String bankCode, @NotNull LocalDate statementDate) {}

    public record RecId(@NotNull UUID reconciliationId) {}

    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    /** The reconciliation as it stands, with its figures. */
    public record RecOutput(String reconciliationId, String bankCode, LocalDate statementDate, String status,
        BigDecimal statementBalance, BigDecimal depositsInTransit, BigDecimal outstandingPayments,
        BigDecimal adjustedBalance, BigDecimal bookBalance, BigDecimal notInBooks, BigDecimal difference,
        String approvalRequestId, String reportRunId) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String BANKS = "banks";
    static final String STATEMENTS = "statements";
    static final String RECS = "recs";
    static final String EARLIER = "earlier";
    static final String PREVIOUS = "previous";
    static final String ROWS = "rows";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String REQUESTS = "requests";
    static final String DECISIONS = "decisions";
    static final String ISSUE = "issue";
    static final String ISSUED = "issued";

    /** The figures of a reconciliation, worked out from the template's rows. */
    record Figures(BigDecimal statementBalance, BigDecimal deposits, BigDecimal payments, BigDecimal adjusted,
        BigDecimal book, BigDecimal notInBooks, BigDecimal difference) {

        Map<String, Object> values() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("statementBalance", statementBalance);
            values.put("depositsInTransit", deposits);
            values.put("outstandingPayments", payments);
            values.put("adjustedBalance", adjusted);
            values.put("bookBalance", book);
            values.put("notInBooks", notInBooks);
            values.put("difference", difference);
            return values;
        }
    }

    public static final ProcessDefinition<PrepareInput, RecOutput, ProcessContext> PREPARE_PROCESS =
        ProcessDefinition.define(PREPARE, 1, PrepareInput.class, RecOutput.class, ProcessContext.class, pb -> pb
            .description("Works out a bank account's reconciliation for a statement's closing day.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RecOutput.class))
            .step("Load the statement", QueryEntities.of(StatementEntities.STATEMENT_DATASET, ctx -> {
                PrepareInput input = ctx.get(INPUT, PrepareInput.class);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("bankCode", String.valueOf(code(input.bankCode()))),
                    new QueryPredicate.Eq("toDate", input.statementDate())))).limit(1).build();
            }, STATEMENTS))
            .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET, ctx -> {
                PrepareInput input = ctx.get(INPUT, PrepareInput.class);
                return byDay(code(input.bankCode()), input.statementDate());
            }, RECS))
            .step("Work it out", RunTemplate.of(TEMPLATE, ctx -> params(code(ctx.get(INPUT, PrepareInput.class)
                .bankCode()), ctx.get(INPUT, PrepareInput.class).statementDate(), null, null), ROWS))
            .compute("Record it", (metadata, ctx) -> prepare(ctx)));

    public static final ProcessDefinition<RecId, RecOutput, ProcessContext> COMPLETE_PROCESS =
        ProcessDefinition.define(COMPLETE, 1, RecId.class, RecOutput.class, ProcessContext.class, pb -> pb
            .description("Completes a reconciliation whose difference is zero and asks a reviewer to sign it off.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .actsOn(ReconciliationEntities.RECONCILIATION, "reconciliationId",
                a -> a.whenField("status", ReconciliationEntities.PREPARED))
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RecOutput.class))
            .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET,
                ctx -> byId(ctx.get(INPUT, RecId.class).reconciliationId()), RECS))
            // The statement before must be reconciled and signed off; by the same rule so is every one before it.
            .step("Load the statement before", QueryEntities.of(StatementEntities.STATEMENT_DATASET, ctx -> {
                EntityInstance rec = first(ctx, RECS);
                return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("bankCode", rec == null ? "" : (String) rec.get("bankCode")),
                    new QueryPredicate.Lt("toDate", rec == null ? LocalDate.EPOCH : rec.get("statementDate")))))
                    .orderBy("toDate", false).limit(1).build();
            }, PREVIOUS))
            .step("Load its reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET, ctx -> {
                EntityInstance previous = first(ctx, PREVIOUS);
                return previous == null ? byId(null) : byDay(previous.get("bankCode"), previous.get("toDate"));
            }, EARLIER))
            .step("Work it out again", RunTemplate.of(TEMPLATE, ctx -> {
                EntityInstance rec = first(ctx, RECS);
                return rec == null ? params("", LocalDate.EPOCH, null, null)
                    : params(rec.get("bankCode"), rec.get("statementDate"), null, null);
            }, ROWS))
            .compute("Check it", (metadata, ctx) -> checkComplete(ctx))
            .step("Ask the reviewer", RequireApproval.when(ctx -> ctx.contains(CASE), SUBJECT,
                ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
            // The same content approved before (the books moved and came back): that approval stands.
            .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET, ctx -> byRequestId(
                ctx.contains(APPROVAL) ? ctx.get(APPROVAL, ApprovalOutcome.class).requestId() : null), DECISIONS))
            .compute("Record it", (metadata, ctx) -> recordComplete(ctx)));

    public static final ProcessDefinition<RecId, RecOutput, ProcessContext> WITHDRAW_PROCESS =
        ProcessDefinition.define(WITHDRAW, 1, RecId.class, RecOutput.class, ProcessContext.class, pb -> pb
            .description("Withdraws a completed reconciliation from its reviewer, to prepare it again.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .actsOn(ReconciliationEntities.RECONCILIATION, "reconciliationId",
                a -> a.whenField("status", ReconciliationEntities.SUBMITTED))
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RecOutput.class))
            .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET,
                ctx -> byId(ctx.get(INPUT, RecId.class).reconciliationId()), RECS))
            .compute("Withdraw it", (metadata, ctx) -> withdraw(ctx))
            .step("Withdraw the approval request", WithdrawApproval.of(SUBJECT,
                ctx -> ctx.get(INPUT, RecId.class).reconciliationId())));

    public static final ProcessDefinition<ApprovalResultInput, RecOutput, ProcessContext> APPROVAL_RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, RecOutput.class,
            ProcessContext.class, pb -> pb
                .description("Signs off and issues an approved reconciliation, or prepares a rejected one again.")
                .permissions(FinancePermissions.BANK_REC_RESULT)
                .internal()
                .contextFactory(BankAccountProcesses::withInput)
                // Nothing to do (not this subject's, or the request does not say so): an empty answer.
                .outputMapper(ctx -> ctx.contains(OUTPUT) ? ctx.get(OUTPUT, RecOutput.class) : new RecOutput(
                    ctx.get(INPUT, ApprovalResultInput.class).entityId(), null, null, null, null, null, null, null,
                    null, null, null, ctx.get(INPUT, ApprovalResultInput.class).requestId(), null))
                .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET,
                    ctx -> {
                        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                        return byId(SUBJECT.equals(input.subject()) ? uuid(input.entityId()) : null);
                    }, RECS))
                .step("Load the approval request", QueryEntities.of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> byRequest(ctx), REQUESTS))
                .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET,
                    ctx -> byRequest(ctx), DECISIONS))
                .step("Work it out again", RunTemplate.of(TEMPLATE, ctx -> {
                    EntityInstance rec = first(ctx, RECS);
                    return rec == null ? params("", LocalDate.EPOCH, null, null)
                        : params(rec.get("bankCode"), rec.get("statementDate"), null, null);
                }, ROWS))
                .compute("Decide", (metadata, ctx) -> decide(ctx)));

    public static final ProcessDefinition<RecId, RecOutput, ProcessContext> ISSUE_PROCESS =
        ProcessDefinition.define(ISSUE_REPORT, 1, RecId.class, RecOutput.class, ProcessContext.class, pb -> pb
            .description("Issues a signed-off reconciliation's report, as the books were at its sign-off.")
            .permissions(FinancePermissions.BANK_RECONCILE)
            .actsOn(ReconciliationEntities.RECONCILIATION, "reconciliationId",
                a -> a.whenField("status", ReconciliationEntities.SIGNED_OFF))
            .contextFactory(BankAccountProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RecOutput.class))
            .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET,
                ctx -> byId(ctx.get(INPUT, RecId.class).reconciliationId()), RECS))
            .compute("Check it", (metadata, ctx) -> checkIssue(ctx))
            .step("Issue it", CallProcess.when(ctx -> ctx.contains(ISSUE), ReportProcesses.ISSUE, 1,
                ctx -> ctx.get(ISSUE), ISSUED))
            .compute("Record it", (metadata, ctx) -> recordIssue(ctx)));

    static void prepare(ProcessContext ctx) {
        PrepareInput input = ctx.get(INPUT, PrepareInput.class);
        String bankCode = code(input.bankCode());
        if (list(ctx, STATEMENTS).isEmpty()) {
            ctx.reject(new Violation("statementDate", NO_STATEMENT, "No statement of " + bankCode + " closes on "
                + input.statementDate(), Map.of("bankCode", String.valueOf(bankCode),
                "statementDate", input.statementDate().toString())));
            return;
        }
        EntityInstance rec = first(ctx, RECS);
        if (rec != null && !ReconciliationEntities.PREPARED.equals(rec.get("status"))) {
            ctx.reject(new Violation("statementDate", NOT_PREPARED, "The reconciliation of " + bankCode + " for "
                + input.statementDate() + " is " + rec.get("status"), Map.of("status", (Object) rec.get("status"))));
            return;
        }
        Figures figures = figures(ctx);
        Map<String, Object> values = figures.values();
        values.put("preparedBy", ctx.request().actorId());
        if (rec == null) {
            values.put("bankCode", bankCode);
            values.put("statementDate", input.statementDate());
            values.put("status", ReconciliationEntities.PREPARED);
            Object id = ctx.changes().insert(ReconciliationEntities.RECONCILIATION, values);
            ctx.put(OUTPUT, output(String.valueOf(id), bankCode, input.statementDate(),
                ReconciliationEntities.PREPARED, figures, null, null));
            return;
        }
        ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
        ctx.put(OUTPUT, output(String.valueOf(rec.id()), bankCode, input.statementDate(),
            ReconciliationEntities.PREPARED, figures, null, null));
    }

    static void checkComplete(ProcessContext ctx) {
        EntityInstance rec = first(ctx, RECS);
        if (rec == null) {
            ctx.reject(new Violation("reconciliationId", NOT_FOUND, "There is no reconciliation "
                + ctx.get(INPUT, RecId.class).reconciliationId(), Map.of()));
            return;
        }
        if (!ReconciliationEntities.PREPARED.equals(rec.get("status"))) {
            ctx.reject(new Violation("reconciliationId", NOT_PREPARED, "The reconciliation is " + rec.get("status"),
                Map.of("status", (Object) rec.get("status"))));
            return;
        }
        if (!Objects.equals(ctx.request().actorId(), rec.get("preparedBy"))) {
            // The approval names the preparer; so whoever completes it prepared it as it stands.
            ctx.reject(new Violation("reconciliationId", NOT_PREPARER, "A reconciliation is completed by who "
                + "prepared it last; prepare it again to complete it", Map.of()));
            return;
        }
        EntityInstance previous = first(ctx, PREVIOUS);
        EntityInstance earlier = first(ctx, EARLIER);
        if (previous != null && (earlier == null
            || !ReconciliationEntities.SIGNED_OFF.equals(earlier.get("status")))) {
            ctx.reject(new Violation("reconciliationId", EARLIER_OPEN, "The reconciliation of "
                + previous.get("toDate") + " is not signed off yet", Map.of("statementDate",
                String.valueOf((Object) previous.get("toDate")))));
            return;
        }
        Figures figures = figures(ctx);
        if (figures.difference().signum() != 0) {
            ctx.reject(new Violation("reconciliationId", DIFFERENCE, "The difference is "
                + figures.difference().toPlainString() + "; a reconciliation is completed at a difference of zero",
                Map.of("difference", figures.difference())));
            return;
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("amount", figures.statementBalance());
        facts.put("bankCode", rec.get("bankCode"));
        ctx.put(CASE, ApprovalCase.of(rec.id(), facts, content(rec, ctx)).preparedBy(rec.get("preparedBy")));
    }

    static void recordComplete(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        EntityInstance rec = first(ctx, RECS);
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() == ApprovalOutcome.Status.APPROVED && approval.requestId() != null
            && reviewer(ctx) != null) {
            Figures figures = figures(ctx);
            Map<String, Object> values = figures.values();
            values.put("status", ReconciliationEntities.SIGNED_OFF);
            values.put("approvalRequestId", approval.requestId());
            values.put("contentHash", ContentHash.of(content(rec, ctx)));
            values.put("reviewedBy", reviewer(ctx));
            values.put("signedOffTime", ctx.opTime());
            ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
            ctx.put(OUTPUT, output(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
                ReconciliationEntities.SIGNED_OFF, figures, approval.requestId(), null));
            return;
        }
        if (approval.status() != ApprovalOutcome.Status.PENDING) {
            // A reconciliation is never signed off by its preparer alone (FIN-BK-008).
            ctx.reject(new Violation("reconciliationId", NO_RULE, "No approval rule of reconciliations applies: "
                + "a controller sets one before any is signed off", Map.of()));
            return;
        }
        Figures figures = figures(ctx);
        Map<String, Object> values = figures.values();
        values.put("status", ReconciliationEntities.SUBMITTED);
        values.put("approvalRequestId", approval.requestId());
        values.put("contentHash", ContentHash.of(content(rec, ctx)));
        ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
        ctx.put(OUTPUT, output(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
            ReconciliationEntities.SUBMITTED, figures, approval.requestId(), null));
    }

    static void decide(ProcessContext ctx) {
        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
        EntityInstance rec = first(ctx, RECS);
        EntityInstance request = first(ctx, REQUESTS);
        if (rec == null || request == null || !ReconciliationEntities.SUBMITTED.equals(rec.get("status"))
            || !Objects.equals(input.requestId(), rec.get("approvalRequestId"))
            || !SUBJECT.equals(request.get("subject"))
            || !String.valueOf(rec.id()).equals(request.get("entityId"))
            || !Objects.equals(rec.get("contentHash"), request.get("contentHash"))
            || !Objects.equals(input.contentHash(), rec.get("contentHash"))
            || !Objects.equals(request.get("status"), input.status())) {
            return;
        }
        String decided = request.get("status");
        boolean approved = ApprovalEntities.APPROVED.equals(decided);
        boolean unchanged = Objects.equals(rec.get("contentHash"), ContentHash.of(content(rec, ctx)));
        if (approved && unchanged) {
            String reviewer = reviewer(ctx);
            if (reviewer == null) {
                return;
            }
            Map<String, Object> values = new HashMap<>();
            values.put("status", ReconciliationEntities.SIGNED_OFF);
            values.put("reviewedBy", reviewer);
            values.put("signedOffTime", ctx.opTime());
            ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
            ctx.put(OUTPUT, output(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
                ReconciliationEntities.SIGNED_OFF, figures(ctx), rec.get("approvalRequestId"), null));
            return;
        }
        if (approved || ApprovalEntities.REJECTED.equals(decided)) {
            // Rejected, or approved but no longer showing what was approved: prepared again.
            Map<String, Object> values = new HashMap<>();
            values.put("status", ReconciliationEntities.PREPARED);
            values.put("approvalRequestId", null);
            values.put("contentHash", null);
            ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
            ctx.put(OUTPUT, output(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
                ReconciliationEntities.PREPARED, figures(ctx), null, null));
        }
    }

    static void withdraw(ProcessContext ctx) {
        EntityInstance rec = first(ctx, RECS);
        if (rec == null) {
            ctx.reject(new Violation("reconciliationId", NOT_FOUND, "There is no reconciliation "
                + ctx.get(INPUT, RecId.class).reconciliationId(), Map.of()));
            return;
        }
        if (!ReconciliationEntities.SUBMITTED.equals(rec.get("status"))) {
            ctx.reject(new Violation("reconciliationId", NOT_SUBMITTED, "The reconciliation is " + rec.get("status")
                + "; only one waiting for its reviewer is withdrawn", Map.of("status", (Object) rec.get("status"))));
            return;
        }
        if (!Objects.equals(ctx.request().actorId(), rec.get("preparedBy"))) {
            ctx.reject(new Violation("reconciliationId", NOT_PREPARER, "A reconciliation is withdrawn by who "
                + "completed it", Map.of()));
            return;
        }
        Map<String, Object> values = new HashMap<>();
        values.put("status", ReconciliationEntities.PREPARED);
        values.put("approvalRequestId", null);
        values.put("contentHash", null);
        ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), values);
        ctx.put(OUTPUT, new RecOutput(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
            ReconciliationEntities.PREPARED, rec.get("statementBalance"), rec.get("depositsInTransit"),
            rec.get("outstandingPayments"), rec.get("adjustedBalance"), rec.get("bookBalance"), rec.get("notInBooks"),
            rec.get("difference"), null, null));
    }

    /** Who decided the request last: its highest level's approver. */
    private static String reviewer(ProcessContext ctx) {
        return list(ctx, DECISIONS).stream()
            .max(Comparator.comparing(d -> new BigDecimal(String.valueOf((Object) d.get("levelNo")))))
            .map(d -> String.valueOf((Object) d.get("approverId"))).orElse(null);
    }

    static void checkIssue(ProcessContext ctx) {
        EntityInstance rec = first(ctx, RECS);
        if (rec == null) {
            ctx.reject(new Violation("reconciliationId", NOT_FOUND, "There is no reconciliation "
                + ctx.get(INPUT, RecId.class).reconciliationId(), Map.of()));
            return;
        }
        if (!ReconciliationEntities.SIGNED_OFF.equals(rec.get("status"))) {
            ctx.reject(new Violation("reconciliationId", NOT_SIGNED_OFF, "The reconciliation is " + rec.get("status")
                + "; only a signed-off one is issued", Map.of("status", (Object) rec.get("status"))));
            return;
        }
        if (rec.get("reportRunId") != null) {
            // Issued once: reprints are the archive's.
            ctx.reject(new Violation("reconciliationId", ISSUED_ALREADY, "The reconciliation is issued already as "
                + rec.get("reportRunId"), Map.of("runId", (Object) rec.get("reportRunId"))));
            return;
        }
        Instant signedOff = rec.get("signedOffTime");
        ctx.put(ISSUE, new ReportProcesses.IssueInput(TEMPLATE, params(rec.get("bankCode"), rec.get("statementDate"),
            rec.get("preparedBy"), rec.get("reviewedBy")), signedOff, signedOff, null));
    }

    static void recordIssue(ProcessContext ctx) {
        if (!ctx.contains(ISSUED)) {
            return;
        }
        EntityInstance rec = first(ctx, RECS);
        ReportProcesses.IssueOutput issued = ctx.get(ISSUED, ReportProcesses.IssueOutput.class);
        ctx.changes().update(ReconciliationEntities.RECONCILIATION, rec.id(), rec.version(), Map.of(
            "reportRunId", issued.runId(), "reportHash", issued.contentHash()));
        ctx.put(OUTPUT, new RecOutput(String.valueOf(rec.id()), rec.get("bankCode"), rec.get("statementDate"),
            ReconciliationEntities.SIGNED_OFF, rec.get("statementBalance"), rec.get("depositsInTransit"),
            rec.get("outstandingPayments"), rec.get("adjustedBalance"), rec.get("bookBalance"), rec.get("notInBooks"),
            rec.get("difference"), rec.get("approvalRequestId"), issued.runId()));
    }

    /** The figures of the template's rows: sections and their amounts. */
    static Figures figures(ProcessContext ctx) {
        BigDecimal statement = BigDecimal.ZERO.setScale(2);
        BigDecimal deposits = BigDecimal.ZERO.setScale(2);
        BigDecimal payments = BigDecimal.ZERO.setScale(2);
        BigDecimal adjusted = BigDecimal.ZERO.setScale(2);
        BigDecimal book = BigDecimal.ZERO.setScale(2);
        BigDecimal notInBooks = BigDecimal.ZERO.setScale(2);
        BigDecimal difference = BigDecimal.ZERO.setScale(2);
        for (Map<String, Object> row : MatchProcesses.rows(ctx, ROWS)) {
            Object raw = row.get("amount");
            if (raw == null) {
                continue;
            }
            BigDecimal amount = MatchProcesses.amount(raw);
            switch (String.valueOf(row.get("section"))) {
                case "STATEMENT_BALANCE" -> statement = amount;
                case "DEPOSIT_IN_TRANSIT" -> deposits = deposits.add(amount);
                case "OUTSTANDING_PAYMENT" -> payments = payments.add(amount);
                case "ADJUSTED_BANK_BALANCE" -> adjusted = amount;
                case "BOOK_BALANCE" -> book = amount;
                case "NOT_IN_BOOKS" -> notInBooks = notInBooks.add(amount);
                case "DIFFERENCE" -> difference = amount;
                default -> { }
            }
        }
        return new Figures(statement, deposits, payments, adjusted, book, notInBooks, difference);
    }

    /** What the reviewer approves: the account, the day and every row as worked out. */
    static Map<String, Object> content(EntityInstance rec, ProcessContext ctx) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("bankCode", rec.get("bankCode"));
        content.put("statementDate", String.valueOf((Object) rec.get("statementDate")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : MatchProcesses.rows(ctx, ROWS)) {
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("section", String.valueOf(row.get("section")));
            kept.put("itemDate", row.get("itemDate") == null ? null : String.valueOf(row.get("itemDate")));
            kept.put("reference", row.get("reference") == null ? null : String.valueOf(row.get("reference")));
            kept.put("amount", row.get("amount") == null ? null : MatchProcesses.amount(row.get("amount")));
            rows.add(kept);
        }
        content.put("rows", rows);
        return content;
    }

    static Map<String, Object> params(String bankCode, LocalDate statementDate, String preparedBy,
        String reviewedBy) {
        Map<String, Object> params = new HashMap<>();
        params.put("bankCode", bankCode);
        params.put("statementDate", statementDate);
        params.put("preparedBy", preparedBy == null ? null : "Prepared by " + preparedBy);
        params.put("reviewedBy", reviewedBy == null ? null : "Reviewed by " + reviewedBy);
        return params;
    }

    private static RecOutput output(String id, String bankCode, LocalDate day, String status, Figures figures,
        String requestId, String runId) {
        return new RecOutput(id, bankCode, day, status, figures.statementBalance(), figures.deposits(),
            figures.payments(), figures.adjusted(), figures.book(), figures.notInBooks(), figures.difference(),
            requestId, runId);
    }

    static EntityQuery byDay(String bankCode, LocalDate day) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("bankCode", String.valueOf(bankCode)),
            new QueryPredicate.Eq("statementDate", day)))).limit(1).build();
    }

    static EntityQuery byId(UUID id) {
        return EntityQuery.builder().where(new QueryPredicate.In("reconciliationId",
            id == null ? List.of() : List.of(id))).limit(1).build();
    }

    private static EntityQuery byRequest(ProcessContext ctx) {
        return byRequestId(ctx.get(INPUT, ApprovalResultInput.class).requestId());
    }

    private static EntityQuery byRequestId(String requestId) {
        UUID id = uuid(requestId);
        return EntityQuery.builder().where(new QueryPredicate.In("requestId", id == null ? List.of() : List.of(id)))
            .limit(20).build();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        return list(ctx, key).stream().findFirst().orElse(null);
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ReconciliationProcesses() {}
}
