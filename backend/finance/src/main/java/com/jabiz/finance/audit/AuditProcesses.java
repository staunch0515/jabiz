package com.jabiz.finance.audit;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.bank.ReconciliationEntities;
import com.jabiz.finance.bank.ReconciliationProcesses;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessStart;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.report.ReportProcesses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit evidence package (FIN-CT-012; ROADMAP F10 decision D5): {@code FIN_AUDIT_PACKAGE} issues, in one
 * operation, the reports an auditor asked for: the manual entries of a period above an amount with their approvals
 * ({@code finance.audit.manual_entries}), optionally the access review as of a time ({@code
 * jabiz.security.access_review}) and a signed-off bank reconciliation as the books were at its sign-off. Every report
 * goes through {@code REPORT_ISSUE}, so each is archived with its content hash, and all carry the operation's time;
 * the answer gives the request for the platform's open export ({@code POST /api/exports/data}) that packs exactly
 * those reports' PDFs with the supporting entries and approvals and a manifest of SHA-256 hashes, which
 * {@code tools/finance/verify-package.py} checks without the system.
 */
public final class AuditProcesses {

    public static final String PACKAGE = "FIN_AUDIT_PACKAGE";
    public static final String MANUAL_ENTRIES = "finance.audit.manual_entries";
    public static final String ACCESS_REVIEW = "jabiz.security.access_review";
    public static final String RECONCILIATION = "finance.bank.reconciliation";

    public static final String INVALID = "FIN_AUDIT_PACKAGE_INVALID";
    public static final String NO_RECONCILIATION = "FIN_AUDIT_NO_RECONCILIATION";

    /** The entries, lines and approvals behind the reports, exported with them. */
    public static final List<String> DATASETS = List.of(JournalEntities.JOURNAL_DATASET,
        JournalEntities.LINE_DATASET, ApprovalEntities.REQUEST_DATASET, ApprovalEntities.DECISION_DATASET,
        ApprovalEntities.EVALUATION_DATASET);

    /**
     * @param request          the auditor's request, as it was put
     * @param from             first posting date of the manual entries
     * @param to               last posting date
     * @param minAmount        the entries above this total
     * @param accessReviewAsOf the access review's time, if one is asked for
     * @param bankCode         the bank account of a reconciliation asked for, with its statement's closing day
     */
    public record PackageInput(@NotBlank @Size(max = 500) String request, @NotNull LocalDate from,
        @NotNull LocalDate to, @NotNull @PositiveOrZero BigDecimal minAmount, Instant accessReviewAsOf,
        @Size(max = 20) String bankCode, LocalDate statementDate) {}

    /** A report of the package. */
    public record PackageReport(String templateId, String runId, String contentHash, int rows) {}

    /** The body of {@code POST /api/exports/data} that packs the package. */
    public record ExportRequest(List<String> datasets, boolean reports, Instant reportsFrom, Instant reportsTo) {}

    public record PackageOutput(String request, Instant issuedTime, List<PackageReport> reports,
        ExportRequest export) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String RECS = "recs";
    static final String ENTRIES_INPUT = "entriesInput";
    static final String REVIEW_INPUT = "reviewInput";
    static final String REC_INPUT = "recInput";
    static final String ENTRIES = "entries";
    static final String REVIEW = "review";
    static final String REC = "rec";

    public static final ProcessDefinition<PackageInput, PackageOutput, ProcessContext> PACKAGE_PROCESS =
        ProcessDefinition.define(PACKAGE, 1, PackageInput.class, PackageOutput.class, ProcessContext.class, pb -> pb
            .description("Issues the reports of an audit request and answers how to export them as a package.")
            .permissions(FinancePermissions.AUDIT_PACKAGE)
            .contextFactory(AuditProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, PackageOutput.class))
            .step("Load the reconciliation", QueryEntities.of(ReconciliationEntities.RECONCILIATION_DATASET,
                AuditProcesses::reconciliationQuery, RECS))
            .compute("Check the request", (metadata, ctx) -> check(ctx))
            .step("Issue the manual entries", CallProcess.when(ctx -> ctx.contains(ENTRIES_INPUT),
                ReportProcesses.ISSUE, 1, ctx -> ctx.get(ENTRIES_INPUT), ENTRIES))
            .step("Issue the access review", CallProcess.when(ctx -> ctx.contains(REVIEW_INPUT),
                ReportProcesses.ISSUE, 1, ctx -> ctx.get(REVIEW_INPUT), REVIEW))
            .step("Issue the reconciliation", CallProcess.when(ctx -> ctx.contains(REC_INPUT),
                ReportProcesses.ISSUE, 1, ctx -> ctx.get(REC_INPUT), REC))
            .compute("Answer", (metadata, ctx) -> answer(ctx)));

    static EntityQuery reconciliationQuery(ProcessContext ctx) {
        PackageInput input = ctx.get(INPUT, PackageInput.class);
        if (input.bankCode() == null || input.statementDate() == null) {
            return EntityQuery.builder().where(new QueryPredicate.Eq("bankCode", "")).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("bankCode", input.bankCode().trim()),
            new QueryPredicate.Eq("statementDate", input.statementDate()),
            new QueryPredicate.Eq("status", ReconciliationEntities.SIGNED_OFF)))).limit(1).build();
    }

    static void check(ProcessContext ctx) {
        PackageInput input = ctx.get(INPUT, PackageInput.class);
        if (input.to().isBefore(input.from())) {
            ctx.reject(new Violation("to", INVALID, "The last posting date " + input.to() + " is before the first "
                + input.from(), Map.of()));
        }
        boolean bank = input.bankCode() != null && !input.bankCode().isBlank();
        if (bank != (input.statementDate() != null)) {
            ctx.reject(new Violation("statementDate", INVALID,
                "A reconciliation is asked for by its bank account and its statement's closing day together",
                Map.of()));
        }
        if (input.accessReviewAsOf() != null && input.accessReviewAsOf().isAfter(ctx.opTime())) {
            ctx.reject(new Violation("accessReviewAsOf", INVALID, "The access review's time "
                + input.accessReviewAsOf() + " is in the future", Map.of()));
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> recs = (List<EntityInstance>) ctx.get(RECS);
        if (bank && input.statementDate() != null && recs.isEmpty()) {
            ctx.reject(new Violation("bankCode", NO_RECONCILIATION, "There is no signed-off reconciliation of "
                + input.bankCode() + " for " + input.statementDate(),
                Map.of("bankCode", input.bankCode(), "statementDate", input.statementDate().toString())));
        }
        if (ctx.hasViolations()) {
            return;
        }
        Map<String, Object> entries = new HashMap<>();
        entries.put("from", input.from());
        entries.put("to", input.to());
        entries.put("minAmount", input.minAmount());
        ctx.put(ENTRIES_INPUT, new ReportProcesses.IssueInput(MANUAL_ENTRIES, entries, null, null, null));
        if (input.accessReviewAsOf() != null) {
            ctx.put(REVIEW_INPUT, new ReportProcesses.IssueInput(ACCESS_REVIEW,
                Map.of("asOf", input.accessReviewAsOf()), null, null, null));
        }
        if (bank) {
            // As the reconciliation's own issue does (FIN_BANK_REC_ISSUE): the books as they were at its sign-off,
            // so the package's copy has the content hash of the one archived then.
            EntityInstance rec = recs.getFirst();
            Instant signedOff = rec.get("signedOffTime");
            ctx.put(REC_INPUT, new ReportProcesses.IssueInput(RECONCILIATION,
                ReconciliationProcesses.params(rec.get("bankCode"), rec.get("statementDate"),
                    rec.get("preparedBy"), rec.get("reviewedBy")), signedOff, signedOff, null));
        }
    }

    static void answer(ProcessContext ctx) {
        if (!ctx.contains(ENTRIES)) {
            return;
        }
        List<PackageReport> reports = new ArrayList<>();
        add(reports, ctx, ENTRIES, MANUAL_ENTRIES);
        add(reports, ctx, REVIEW, ACCESS_REVIEW);
        add(reports, ctx, REC, RECONCILIATION);
        Instant issued = ctx.opTime();
        // Every report of the operation is issued at its time: the export's window holds exactly them.
        ctx.put(OUTPUT, new PackageOutput(ctx.get(INPUT, PackageInput.class).request(), issued,
            List.copyOf(reports), new ExportRequest(DATASETS, true, issued, issued.plusMillis(1))));
    }

    private static void add(List<PackageReport> reports, ProcessContext ctx, String key, String templateId) {
        if (ctx.contains(key)) {
            ReportProcesses.IssueOutput issued = ctx.get(key, ReportProcesses.IssueOutput.class);
            reports.add(new PackageReport(templateId, issued.runId(), issued.contentHash(), issued.rowCount()));
        }
    }

    private static ProcessContext withInput(ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private AuditProcesses() {}
}
