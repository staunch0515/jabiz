package com.jabiz.quizbuks.journal;

import com.jabiz.entity.Violation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.CallerChecks;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.GeneratedFileProcesses;
import com.jabiz.runtime.numbering.AssignNumber;
import com.jabiz.runtime.param.ParamEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.security.MfaRequirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The journal export (docs/quizbuks/plans/Q9d-journal-receipts.md, F-01 – F-03):
 * <ul>
 *   <li>{@value #PREVIEW} (finance): what the next export would be - its month, its lines with their mapping, and
 *       whether it can be exported (the reconciliation, the mapping, the period's end); writes nothing;</li>
 *   <li>{@value #EXPORT} (finance, with a second factor): under the lock {@code qb.journal}, exports the next month
 *       as one summary voucher: checks the order, the reconciliation and the mapping, numbers the voucher without
 *       gaps ({@code AssignNumber}), keeps its two CSV files exactly as made ({@code FILE_ARCHIVE}) and records the
 *       export, written once;</li>
 *   <li>{@value #CONFIRM} (finance): records the voucher number the accounting system gave an export, once.</li>
 * </ul>
 * Every posting is in exactly one export ({@link JournalPeriods}, template {@code qb.system.journal-lines}): a posting
 * booked in an exported month but recorded after its export comes in the next one, and exported vouchers never
 * change.
 */
public final class JournalProcesses {

    public static final String PREVIEW = "QB_JOURNAL_PREVIEW";
    public static final String EXPORT = "QB_JOURNAL_EXPORT";
    public static final String CONFIRM = "QB_JOURNAL_EXPORT_CONFIRM";

    public record PreviewInput() {}

    /** @param month the month the caller exports ({@code 2026-10}): the one the preview showed, which must be next */
    public record ExportInput(@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    /**
     * The next export as it would be now.
     *
     * @param month            the month ({@code 2026-10}); null when nothing was ever posted
     * @param periodEnded      whether the month has ended (in Japan)
     * @param periodEnd        the month's end, the first instant after it
     * @param previousVoucher  the last export's voucher number; null before the first
     * @param reconStatus      the latest reconciliation's outcome ({@code OK} / {@code DIFF}); null when none ran
     * @param reconTime        when it was scheduled
     * @param reconOk          whether it ran after the month's end and found no differences
     * @param unmapped         accounts moved without an account code of the accounting system
     * @param invalid          accounts whose code is not a valid one
     * @param ready            whether {@value #EXPORT} would export it now
     */
    public record PreviewOutput(String month, LocalDate periodFrom, LocalDate periodTo, boolean periodEnded,
        Instant periodEnd, String previousVoucher, Instant knownAt, String reconStatus, Instant reconTime,
        boolean reconOk, List<String> unmapped, List<String> invalid, List<JournalLines.Line> lines,
        int transactionCount, BigDecimal debitTotal, BigDecimal creditTotal, boolean balanced, boolean ready) {}

    public record ExportOutput(String exportId, String voucherNo, LocalDate periodFrom, LocalDate periodTo,
        Instant knownAt, int lineCount, int transactionCount, BigDecimal debitTotal, BigDecimal creditTotal,
        String fileId, String fileSha256, String detailFileId, String detailSha256) {}

    public record ConfirmInput(@NotBlank @Size(max = 36) String exportId,
        @NotBlank @Size(max = JournalLines.EXTERNAL_VOUCHER_LENGTH) String externalVoucherNo) {}

    public record ConfirmOutput(String confirmationId, String exportId, String externalVoucherNo) {}

    static final String INPUT = "input";
    static final String PREVIOUS = "previous";
    static final String FIRST = "first";
    static final String RECON = "recon";
    static final String MAPPING = "mapping";
    static final String PERIOD = "period";
    static final String KNOWN_AT = "knownAt";
    static final String ENTRIES = "entries";
    static final String SUMMARY = "summary";
    static final String PROBLEMS = "problems";
    static final String VOUCHER_NO = "voucherNo";
    static final String VOUCHER_CSV = "voucherCsv";
    static final String DETAIL_CSV = "detailCsv";
    static final String KEPT = "kept";
    static final String KEPT_DETAIL = "keptDetail";
    static final String EXPORT_ID = "exportId";
    static final String EXPORT_KEY = "export";
    static final String CONFIRMED = "confirmed";
    static final String CONFIRMATION_ID = "confirmationId";

    /** What reading the archived files needs besides {@code file.generated.read}. */
    static final List<String> FILE_PERMISSIONS = List.of(QbPermissions.ADMIN_FINANCE_READ);

    // ---- preview ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PreviewInput, PreviewOutput, ProcessContext> preview() {
        return ProcessDefinition.define(PREVIEW, 1, PreviewInput.class, PreviewOutput.class, ProcessContext.class,
            pb -> pb
                .description("Shows the next journal export as it would be now; writes nothing.")
                .permissions(QbPermissions.JOURNAL_EXPORT)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(JournalProcesses::previewOutput)
                .steps(JournalProcesses::read));
    }

    // ---- export ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExportInput, ExportOutput, ProcessContext> export() {
        return ProcessDefinition.define(EXPORT, 1, ExportInput.class, ExportOutput.class, ProcessContext.class,
            pb -> pb
                .description("Exports the next month of the ledger as a summary voucher in CSV for the accounting "
                    + "system, numbered without gaps and kept exactly as made.")
                .permissions(QbPermissions.JOURNAL_EXPORT)
                .requiresMfa(MfaRequirement.ADMINISTRATION)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(JournalProcesses::exportOutput)
                // One export at a time: each reads the one before under the lock.
                .step("Take the journal's lock", HoldLock.<ProcessContext>exclusive(ctx -> QbJournal.LOCK))
                .steps(JournalProcesses::read)
                .compute("Refuse what cannot be exported", (metadata, ctx) -> refuseProblems(ctx))
                // After every check that can refuse: the number is drawn only for a voucher that is kept.
                .step("Number the voucher", AssignNumber.<ProcessContext>of(QbJournal.VOUCHER_SEQUENCE,
                    ctx -> JournalPeriods.voucherScope(period(ctx)), VOUCHER_NO))
                .compute("Make the files", (metadata, ctx) -> makeFiles(ctx))
                .step("Keep the voucher", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1,
                    ctx -> archive(ctx, (String) ctx.get(VOUCHER_NO) + ".csv", VOUCHER_CSV), KEPT))
                .step("Keep the detail", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1,
                    ctx -> archive(ctx, (String) ctx.get(VOUCHER_NO) + "-detail.csv", DETAIL_CSV), KEPT_DETAIL))
                .compute("Record the export", (metadata, ctx) -> record(ctx)));
    }

    /** The reads and the voucher both processes share; nothing written. */
    static <I, O> void read(ProcessDefinitionBuilder<I, O, ProcessContext> pb) {
        pb.step("Load the last export", QueryEntities.<ProcessContext>of(QbJournal.defaultDataset(QbJournal.EXPORT),
                ctx -> EntityQuery.builder().orderBy("periodFrom", false).limit(1).build(), PREVIOUS))
            .step("Find the earliest posting", RunTemplate.<ProcessContext>of(QbJournal.FIRST_TEMPLATE,
                ctx -> Map.of(), FIRST))
            .step("Load the latest reconciliation", QueryEntities.<ProcessContext>of(
                Reconciliation.defaultDataset(Reconciliation.RUN_ENTITY_NAME),
                ctx -> EntityQuery.builder().orderBy("scheduledTime", false).limit(1).build(), RECON))
            .step("Load the account mapping", QueryEntities.<ProcessContext>of(ParamEntities.DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In(ParamEntities.KEY,
                    new ArrayList<>(QbParams.JOURNAL_ACCOUNTS))).limit(QbParams.JOURNAL_ACCOUNTS.size()).build(),
                MAPPING))
            .compute("Decide the period", (metadata, ctx) -> decidePeriod(ctx))
            .step("Read the postings", RunTemplate.<ProcessContext>of(QbJournal.LINES_TEMPLATE,
                JournalProcesses::linesParams, ENTRIES))
            .compute("Sum up the voucher", (metadata, ctx) -> summarize(ctx));
    }

    static void decidePeriod(ProcessContext ctx) {
        EntityInstance previous = first(ctx, PREVIOUS);
        Instant earliest = rows(ctx, FIRST).isEmpty() ? null
            : JournalLines.instant(rows(ctx, FIRST).getFirst().get("firstBooking"));
        JournalPeriods.Period period = JournalPeriods.next(previous == null ? null : date(previous.get("periodFrom")),
            earliest);
        if (period == null) {
            throw new BusinessRuleViolationException(new Violation(null, JournalCodes.NOTHING_POSTED,
                "Nothing has been posted yet: there is no period to export", Map.of()));
        }
        ctx.put(PERIOD, period);
        ctx.put(KNOWN_AT, JournalPeriods.cutOff(ctx.opTime(),
            previous == null ? null : JournalLines.instant(previous.get("knownAt"))));
    }

    static Map<String, Object> linesParams(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        Map<String, Object> params = new HashMap<>();
        params.put("to", period.end());
        params.put("knownAt", ctx.get(KNOWN_AT));
        EntityInstance previous = first(ctx, PREVIOUS);
        if (previous != null) {
            params.put("previousTo", JournalPeriods.month(java.time.YearMonth.from(
                date(previous.get("periodFrom")))).end());
            params.put("previousKnownAt", JournalLines.instant(previous.get("knownAt")));
        }
        return params;
    }

    static void summarize(ProcessContext ctx) {
        Map<String, String> mapping = mapping(ctx);
        JournalLines.Summary summary = JournalLines.summarize(JournalLines.entries(rows(ctx, ENTRIES)), mapping);
        ctx.put(SUMMARY, summary);
        JournalPeriods.Period period = period(ctx);
        List<Violation> problems = new ArrayList<>();
        if (period.end().isAfter(ctx.opTime())) {
            problems.add(new Violation(null, JournalCodes.PERIOD_OPEN, "The month " + period.label()
                + " has not ended yet", Map.of("month", period.label(), "endsAt", period.end().toString())));
        }
        if (!reconOk(ctx, period)) {
            problems.add(new Violation(null, JournalCodes.RECON_NOT_OK, "The daily reconciliation has not run "
                + "cleanly since the month ended", Map.of()));
        }
        List<String> unmapped = JournalLines.unmapped(summary.accounts(), mapping);
        if (!unmapped.isEmpty()) {
            problems.add(new Violation(null, JournalCodes.ACCOUNT_UNMAPPED, "Accounts without an account code of "
                + "the accounting system: " + String.join(", ", unmapped),
                Map.of("accounts", String.join(", ", unmapped))));
        }
        List<String> invalid = JournalLines.invalid(summary.accounts(), mapping);
        if (!invalid.isEmpty()) {
            problems.add(new Violation(null, JournalCodes.ACCOUNT_INVALID, "Account codes that are not 1 to 20 "
                + "letters, digits, '.', '_' or '-': " + String.join(", ", invalid),
                Map.of("accounts", String.join(", ", invalid))));
        }
        if (!summary.balanced()) {
            problems.add(new Violation(null, JournalCodes.UNBALANCED, "The voucher's debits and credits differ",
                Map.of("debit", summary.debitTotal().toPlainString(), "credit",
                    summary.creditTotal().toPlainString())));
        }
        ctx.put(PROBLEMS, List.copyOf(problems));
    }

    /** The latest run is after the month's end and clean (requirement 7.4: no export over a difference). */
    static boolean reconOk(ProcessContext ctx, JournalPeriods.Period period) {
        EntityInstance run = first(ctx, RECON);
        if (run == null || !"OK".equals(run.get("status"))) {
            return false;
        }
        Instant scheduled = JournalLines.instant(run.get("scheduledTime"));
        return scheduled != null && !scheduled.isBefore(period.end());
    }

    static void refuseProblems(ProcessContext ctx) {
        List<Violation> problems = new ArrayList<>(problems(ctx));
        String asked = ctx.get(INPUT, ExportInput.class).month();
        String next = period(ctx).label();
        if (!next.equals(asked)) {
            problems.addFirst(new Violation("month", JournalCodes.PERIOD_NOT_NEXT, "The next month to export is "
                + next + ", not " + asked, Map.of("month", asked, "next", next)));
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleViolationException(problems);
        }
    }

    static void makeFiles(ProcessContext ctx) {
        String voucherNo = (String) ctx.get(VOUCHER_NO);
        JournalLines.Summary summary = summary(ctx);
        ctx.put(VOUCHER_CSV, JournalLines.voucherCsv(voucherNo, period(ctx), summary));
        ctx.put(DETAIL_CSV, JournalLines.detailCsv(voucherNo, summary));
    }

    static GeneratedFileProcesses.ArchiveInput archive(ProcessContext ctx, String fileName, String contentKey) {
        return new GeneratedFileProcesses.ArchiveInput(fileName, "text/csv", ctx.get(contentKey, byte[].class),
            FILE_PERMISSIONS, null, null);
    }

    static void record(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        GeneratedFileProcesses.ArchiveOutput kept = ctx.get(KEPT, GeneratedFileProcesses.ArchiveOutput.class);
        GeneratedFileProcesses.ArchiveOutput detail = ctx.get(KEPT_DETAIL,
            GeneratedFileProcesses.ArchiveOutput.class);
        EntityInstance previous = first(ctx, PREVIOUS);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("periodFrom", period.from());
        values.put("periodTo", period.to());
        values.put("granularity", QbJournal.GRANULARITY_MONTH);
        values.put("voucherNo", ctx.get(VOUCHER_NO));
        values.put("knownAt", ctx.get(KNOWN_AT));
        if (previous != null) {
            values.put("previousExportId", previous.id());
        }
        values.put("lineCount", BigDecimal.valueOf(summary.lines().size()));
        values.put("transactionCount", BigDecimal.valueOf(summary.transactionCount()));
        values.put("debitTotal", summary.debitTotal());
        values.put("creditTotal", summary.creditTotal());
        values.put("fileId", kept.fileId());
        values.put("fileSha256", kept.sha256());
        values.put("detailFileId", detail.fileId());
        values.put("detailSha256", detail.sha256());
        values.put("exportedBy", ctx.request().actorId());
        ctx.put(EXPORT_ID, String.valueOf(ctx.changes().insert(QbJournal.EXPORT, values)));
    }

    static PreviewOutput previewOutput(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        Map<String, String> mapping = mapping(ctx);
        EntityInstance previous = first(ctx, PREVIOUS);
        EntityInstance recon = first(ctx, RECON);
        return new PreviewOutput(period.label(), period.from(), period.to(), !period.end().isAfter(ctx.opTime()),
            period.end(), previous == null ? null : previous.get("voucherNo"), (Instant) ctx.get(KNOWN_AT),
            recon == null ? null : recon.get("status"),
            recon == null ? null : JournalLines.instant(recon.get("scheduledTime")), reconOk(ctx, period),
            JournalLines.unmapped(summary.accounts(), mapping), JournalLines.invalid(summary.accounts(), mapping),
            summary.lines(), summary.transactionCount(), summary.debitTotal(), summary.creditTotal(),
            summary.balanced(), problems(ctx).isEmpty());
    }

    static ExportOutput exportOutput(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        GeneratedFileProcesses.ArchiveOutput kept = ctx.get(KEPT, GeneratedFileProcesses.ArchiveOutput.class);
        GeneratedFileProcesses.ArchiveOutput detail = ctx.get(KEPT_DETAIL,
            GeneratedFileProcesses.ArchiveOutput.class);
        return new ExportOutput((String) ctx.get(EXPORT_ID), (String) ctx.get(VOUCHER_NO), period.from(), period.to(),
            (Instant) ctx.get(KNOWN_AT), summary.lines().size(), summary.transactionCount(), summary.debitTotal(),
            summary.creditTotal(), kept.fileId(), kept.sha256(), detail.fileId(), detail.sha256());
    }

    // ---- confirmation ----------------------------------------------------------------------------------------------

    public static ProcessDefinition<ConfirmInput, ConfirmOutput, ProcessContext> confirm() {
        return ProcessDefinition.define(CONFIRM, 1, ConfirmInput.class, ConfirmOutput.class, ProcessContext.class,
            pb -> pb
                .description("Records the voucher number the accounting system gave an exported journal voucher.")
                .permissions(QbPermissions.JOURNAL_CONFIRM)
                .requiresVerifiedEmail()
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    ctx.put(EXPORT_ID, input.exportId());
                    return ctx;
                })
                .outputMapper(ctx -> {
                    ConfirmInput input = ctx.get(INPUT, ConfirmInput.class);
                    return new ConfirmOutput((String) ctx.get(CONFIRMATION_ID), input.exportId(),
                        input.externalVoucherNo().strip());
                })
                .step("Take the journal's lock", HoldLock.<ProcessContext>exclusive(ctx -> QbJournal.LOCK))
                .step("Load the export", LoadEntity.by(QbJournal.defaultDataset(QbJournal.EXPORT), EXPORT_ID,
                    EXPORT_KEY))
                .step("Load its confirmation", QueryEntities.<ProcessContext>of(
                    QbJournal.defaultDataset(QbJournal.CONFIRMATION), ctx -> EntityQuery.builder()
                        .where(new QueryPredicate.Eq("exportId", ctx.get(EXPORT_ID))).limit(1).build(), CONFIRMED))
                .compute("Record the voucher number", (metadata, ctx) -> {
                    EntityInstance confirmed = first(ctx, CONFIRMED);
                    if (confirmed != null) {
                        throw new BusinessRuleViolationException(new Violation(null, JournalCodes.ALREADY_CONFIRMED,
                            "The export already has the accounting system's voucher number",
                            Map.of("externalVoucherNo", String.valueOf((Object) confirmed.get("externalVoucherNo")))));
                    }
                    EntityInstance export = ctx.get(EXPORT_KEY, EntityInstance.class);
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("exportId", export.id());
                    values.put("externalVoucherNo", ctx.get(INPUT, ConfirmInput.class).externalVoucherNo().strip());
                    values.put("confirmedBy", ctx.request().actorId());
                    ctx.put(CONFIRMATION_ID, String.valueOf(ctx.changes().insert(QbJournal.CONFIRMATION, values)));
                }));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static JournalPeriods.Period period(ProcessContext ctx) {
        return ctx.get(PERIOD, JournalPeriods.Period.class);
    }

    static JournalLines.Summary summary(ProcessContext ctx) {
        return ctx.get(SUMMARY, JournalLines.Summary.class);
    }

    @SuppressWarnings("unchecked")
    static List<Violation> problems(ProcessContext ctx) {
        return (List<Violation>) ctx.get(PROBLEMS);
    }

    /** Each account's code in the accounting system, as the parameters hold it now (blank: not set). */
    static Map<String, String> mapping(ProcessContext ctx) {
        Map<String, String> mapping = new HashMap<>();
        for (EntityInstance param : instances(ctx, MAPPING)) {
            String key = param.get(ParamEntities.KEY);
            mapping.put(key.substring(QbParams.JOURNAL_ACCOUNT_PREFIX.length()), param.get(ParamEntities.VALUE));
        }
        return mapping;
    }

    static LocalDate date(Object value) {
        return switch (value) {
            case LocalDate date -> date;
            case OffsetDateTime time -> time.toLocalDate();
            default -> LocalDate.parse(String.valueOf(value));
        };
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> instances(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = instances(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> found = (List<Map<String, Object>>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private JournalProcesses() {}
}
