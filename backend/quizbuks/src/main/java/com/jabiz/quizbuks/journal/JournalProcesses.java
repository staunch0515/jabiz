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
 *   <li>{@value #PREVIEW} (finance): what the next export would be - its month, its lines with their mapping, how big
 *       its detail is, and whether it can be exported now; writes nothing;</li>
 *   <li>{@value #EXPORT} (finance, with a second factor): under the lock {@code qb.journal}, exports the next month
 *       as one summary voucher: checks the order, the reconciliation, the mapping, the size and that no long database
 *       transaction is open, numbers the voucher without gaps ({@code AssignNumber}), keeps its CSV files exactly as
 *       made ({@code FILE_ARCHIVE}: the voucher, and the detail in parts through {@value #DETAIL_PART}) and records the
 *       export, written once;</li>
 *   <li>{@value #CONFIRM} (finance): records the voucher number the accounting system gave an export, once.</li>
 * </ul>
 * Every posting is in exactly one export ({@link JournalPeriods}, templates {@code qb.system.journal-*}): a posting
 * booked in an exported month but recorded after its export comes in the next one, and exported vouchers never
 * change. The database sums the voucher's lines; the detail is read and written part by part, each part one read and
 * one file within the platform's limits ({@link JournalLimits}, review H1).
 */
public final class JournalProcesses {

    public static final String PREVIEW = "QB_JOURNAL_PREVIEW";
    public static final String EXPORT = "QB_JOURNAL_EXPORT";
    public static final String CONFIRM = "QB_JOURNAL_EXPORT_CONFIRM";
    public static final String DETAIL_PART = "QB_JOURNAL_DETAIL_PART";

    /** The key of no transaction: the range of the one, empty, part of a month without postings. */
    static final String NO_TRANSACTION = "00000000-0000-0000-0000-000000000000";

    public record PreviewInput() {}

    /** @param month the month the caller exports ({@code 2026-10}): the one the preview showed, which must be next */
    public record ExportInput(@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month) {}

    /**
     * The next export as it would be now.
     *
     * @param month            the month ({@code 2026-10})
     * @param periodEnded      whether the month has ended (in Japan)
     * @param periodEnd        the month's end, the first instant after it
     * @param previousVoucher  the last export's voucher number; null before the first
     * @param reconStatus      the latest reconciliation's outcome ({@code OK} / {@code DIFF}); null when none ran
     * @param reconTime        when it was scheduled
     * @param reconOk          whether it ran after the month's end and found no differences
     * @param unmapped         accounts moved without an account code of the accounting system
     * @param invalid          accounts whose code is not a valid one
     * @param detailRows       the detail's rows (postings by transaction, account and side)
     * @param detailParts      how many files the detail takes
     * @param withinLimits     whether every part, and the voucher's file, stays within the platform's limits of a read
     *                         and of a file
     * @param nearLimit        whether the voucher or a transaction comes near those limits (four fifths): the
     *                         platform's operators should raise them before an export is refused
     * @param openTransactions other database transactions open for long (none for an export)
     * @param ready            whether {@value #EXPORT} would export it now
     */
    public record PreviewOutput(String month, LocalDate periodFrom, LocalDate periodTo, boolean periodEnded,
        Instant periodEnd, String previousVoucher, Instant knownAt, String reconStatus, Instant reconTime,
        boolean reconOk, List<String> unmapped, List<String> invalid, List<JournalLines.Line> lines,
        int transactionCount, long detailRows, int detailParts, boolean withinLimits, boolean nearLimit,
        int openTransactions,
        BigDecimal debitTotal, BigDecimal creditTotal, boolean balanced, boolean ready) {}

    /** A part of the detail as kept. */
    public record DetailFile(int partNo, String fileId, String sha256, int rows) {}

    public record ExportOutput(String exportId, String voucherNo, LocalDate periodFrom, LocalDate periodTo,
        Instant knownAt, int lineCount, int transactionCount, BigDecimal debitTotal, BigDecimal creditTotal,
        String fileId, String fileSha256, List<DetailFile> detailFiles) {}

    public record ConfirmInput(@NotBlank @Size(max = 36) String exportId,
        @NotBlank @Size(max = JournalLines.EXTERNAL_VOUCHER_LENGTH) String externalVoucherNo) {}

    public record ConfirmOutput(String confirmationId, String exportId, String externalVoucherNo) {}

    /**
     * One part of the detail, as the export gives it: the range of transactions and what the export takes.
     *
     * @param lineNumbers the voucher's line numbers by line key ({@link JournalLines#key})
     */
    public record PartInput(String voucherNo, int partNo, int partCount, String firstTransaction,
        String lastTransaction, Instant to, Instant knownAt, Instant previousTo, Instant previousKnownAt,
        Map<String, Integer> lineNumbers, int expectedRows) {}

    static final String INPUT = "input";
    static final String PREVIOUS = "previous";
    static final String FIRST = "first";
    static final String RECON = "recon";
    static final String MAPPING = "mapping";
    static final String PERIOD = "period";
    static final String KNOWN_AT = "knownAt";
    static final String OPEN = "open";
    static final String GROUPS = "groups";
    static final String PARTS = "parts";
    static final String SUMMARY = "summary";
    static final String PROBLEMS = "problems";
    static final String NEAR_LIMIT = "nearLimit";
    static final String VOUCHER_NO = "voucherNo";
    static final String VOUCHER_CSV = "voucherCsv";
    static final String KEPT = "kept";
    static final String DETAIL = "detail";
    static final String DETAIL_CSV = "detailCsv";
    static final String DETAIL_KEPT = "detailKept";
    static final String EXPORT_ID = "exportId";
    static final String EXPORT_KEY = "export";
    static final String CONFIRMED = "confirmed";
    static final String CONFIRMATION_ID = "confirmationId";

    /** What reading the archived files needs besides {@code file.generated.read}. */
    static final List<String> FILE_PERMISSIONS = List.of(QbPermissions.ADMIN_FINANCE_READ);

    // ---- preview ---------------------------------------------------------------------------------------------------

    public static ProcessDefinition<PreviewInput, PreviewOutput, ProcessContext> preview(JournalLimits limits) {
        return ProcessDefinition.define(PREVIEW, 1, PreviewInput.class, PreviewOutput.class, ProcessContext.class,
            pb -> pb
                .description("Shows the next journal export as it would be now; writes nothing.")
                .permissions(QbPermissions.JOURNAL_EXPORT)
                .steps(CallerChecks::refuseBanned)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(JournalProcesses::previewOutput)
                .steps(builder -> read(builder, limits)));
    }

    // ---- export ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ExportInput, ExportOutput, ProcessContext> export(JournalLimits limits) {
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
                .steps(builder -> read(builder, limits))
                .compute("Refuse what cannot be exported", (metadata, ctx) -> refuseProblems(ctx))
                // After every check that can refuse: the number is drawn only for a voucher that is kept (a failure
                // after it rolls the count back with the rest).
                .step("Number the voucher", AssignNumber.<ProcessContext>of(QbJournal.VOUCHER_SEQUENCE,
                    ctx -> JournalPeriods.voucherScope(period(ctx)), VOUCHER_NO))
                .compute("Make the voucher's file", (metadata, ctx) -> ctx.put(VOUCHER_CSV,
                    JournalLines.voucherCsv((String) ctx.get(VOUCHER_NO), period(ctx), summary(ctx))))
                .step("Keep the voucher", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1,
                    ctx -> new GeneratedFileProcesses.ArchiveInput(ctx.get(VOUCHER_NO) + ".csv", "text/csv",
                        ctx.get(VOUCHER_CSV, byte[].class), FILE_PERMISSIONS, null, null), KEPT))
                // Part by part: each part one read and one file within the platform's limits; only its outcome stays.
                .step("Keep the detail, part by part", CallProcess.<ProcessContext>forEach(DETAIL_PART, 1,
                    JournalProcesses::partInputs, DETAIL))
                .compute("Record the export", (metadata, ctx) -> record(ctx)));
    }

    /**
     * {@value #DETAIL_PART}: one part of an export's detail - its postings read in one read, written as one CSV file and
     * kept. Run only by the export, as its sub-process, in its transaction.
     */
    public static ProcessDefinition<PartInput, DetailFile, ProcessContext> detailPart(JournalLimits limits) {
        return ProcessDefinition.define(DETAIL_PART, 1, PartInput.class, DetailFile.class, ProcessContext.class,
            pb -> pb
                .description("Writes and keeps one part of a journal export's detail.")
                .permissions(QbPermissions.JOURNAL_SYSTEM)
                .subProcessOnly()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> {
                    PartInput input = ctx.get(INPUT, PartInput.class);
                    GeneratedFileProcesses.ArchiveOutput kept = ctx.get(DETAIL_KEPT,
                        GeneratedFileProcesses.ArchiveOutput.class);
                    return new DetailFile(input.partNo(), kept.fileId(), kept.sha256(),
                        ((List<?>) ctx.get(DETAIL)).size());
                })
                .step("Read the part's postings", RunTemplate.<ProcessContext>of(QbJournal.DETAIL_TEMPLATE, ctx -> {
                    PartInput input = ctx.get(INPUT, PartInput.class);
                    Map<String, Object> params = windowParams(input.to(), input.knownAt(), input.previousTo(),
                        input.previousKnownAt());
                    params.put("firstTransaction", input.firstTransaction());
                    params.put("lastTransaction", input.lastTransaction());
                    return params;
                }, DETAIL))
                .compute("Write the part", (metadata, ctx) -> {
                    PartInput input = ctx.get(INPUT, PartInput.class);
                    List<Map<String, Object>> rows = rows(ctx, DETAIL);
                    if (rows.size() != input.expectedRows()) {
                        // The parts were cut from the same postings in this transaction: a difference is a defect.
                        throw new IllegalStateException("Part " + input.partNo() + " of " + input.voucherNo() + " read "
                            + rows.size() + " rows, " + input.expectedRows() + " expected");
                    }
                    byte[] csv = JournalLines.detailCsv(input.voucherNo(), JournalLines.entries(rows),
                        input.lineNumbers());
                    if (csv.length > limits.maxFileBytes()) {
                        throw new BusinessRuleViolationException(tooLarge(rows.size(), csv.length));
                    }
                    ctx.put(DETAIL_CSV, csv);
                })
                .step("Keep the part", CallProcess.<ProcessContext>of(GeneratedFileProcesses.ARCHIVE, 1, ctx -> {
                    PartInput input = ctx.get(INPUT, PartInput.class);
                    return new GeneratedFileProcesses.ArchiveInput(input.voucherNo() + "-detail-" + input.partNo()
                        + ".csv", "text/csv", ctx.get(DETAIL_CSV, byte[].class), FILE_PERMISSIONS, null, null);
                }, DETAIL_KEPT)));
    }

    /** The reads and the voucher both processes share; nothing written. */
    static <I, O> void read(ProcessDefinitionBuilder<I, O, ProcessContext> pb, JournalLimits limits) {
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
            // Before the postings are read: a transaction open for long may have begun a posting before the cut-off
            // that commits after this export's reads (review M1).
            .step("Look for long open transactions", RunTemplate.<ProcessContext>of(
                QbJournal.OPEN_TRANSACTIONS_TEMPLATE,
                ctx -> Map.of("olderThanSeconds", BigDecimal.valueOf(limits.maxOpenTransaction().toSeconds())), OPEN))
            .step("Sum up the voucher", RunTemplate.<ProcessContext>of(QbJournal.SUMMARY_TEMPLATE,
                JournalProcesses::linesParams, GROUPS))
            .step("Cut the detail into parts", RunTemplate.<ProcessContext>of(QbJournal.PARTS_TEMPLATE, ctx -> {
                Map<String, Object> params = linesParams(ctx);
                params.put("partRows", BigDecimal.valueOf(limits.partRows()));
                params.put("partBytes", BigDecimal.valueOf(limits.partBytes()));
                return params;
            }, PARTS))
            .compute("Check the voucher", (metadata, ctx) -> summarize(ctx, limits));
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
        EntityInstance previous = first(ctx, PREVIOUS);
        return windowParams(period(ctx).end(), (Instant) ctx.get(KNOWN_AT),
            previous == null ? null : JournalLines.instant(previous.get("periodEnd")),
            previous == null ? null : JournalLines.instant(previous.get("knownAt")));
    }

    /** What an export takes: before the period's end, by the cut-off, and not taken by the exports before. */
    static Map<String, Object> windowParams(Instant to, Instant knownAt, Instant previousTo, Instant previousKnownAt) {
        Map<String, Object> params = new HashMap<>();
        params.put("to", to);
        params.put("knownAt", knownAt);
        if (previousTo != null) {
            params.put("previousTo", previousTo);
            params.put("previousKnownAt", previousKnownAt);
        }
        return params;
    }

    static void summarize(ProcessContext ctx, JournalLimits limits) {
        Map<String, String> mapping = mapping(ctx);
        JournalLines.Summary summary = JournalLines.summarize(JournalLines.groups(rows(ctx, GROUPS)), mapping);
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
                + "letters, digits, '.', '_' or '-' beginning with a letter or digit: " + String.join(", ", invalid),
                Map.of("accounts", String.join(", ", invalid))));
        }
        if (!summary.balanced()) {
            problems.add(new Violation(null, JournalCodes.UNBALANCED, "The voucher's debits and credits differ",
                Map.of("debit", summary.debitTotal().toPlainString(), "credit",
                    summary.creditTotal().toPlainString())));
        }
        long maxRows = 0;
        long maxBytes = 0;
        for (Map<String, Object> part : rows(ctx, PARTS)) {
            maxRows = Math.max(maxRows, JournalLines.decimal(part.get("maxTransactionRows")).longValue());
            maxBytes = Math.max(maxBytes, JournalLines.decimal(part.get("maxTransactionBytes")).longValue());
        }
        if (!limits.fits(maxRows, maxBytes)) {
            problems.add(tooLarge(maxRows, maxBytes));
        }
        // The voucher is one file, its lines one read (the summary's): as many as accounts, sides and sponsors.
        long voucherBytes = JournalLines.voucherCsv("QBJ-0000-00000", period, summary).length;
        if (voucherBytes > limits.maxFileBytes()) {
            problems.add(tooLarge(summary.lines().size(), voucherBytes));
        }
        ctx.put(NEAR_LIMIT, limits.near(summary.lines().size(), voucherBytes, maxRows, maxBytes));
        int open = openTransactions(ctx);
        if (open > 0) {
            Object seconds = rows(ctx, OPEN).getFirst().get("oldestSeconds");
            problems.add(new Violation(null, JournalCodes.TRANSACTION_OPEN, "Another database transaction has been "
                + "open for " + seconds + " seconds", Map.of("seconds", String.valueOf(seconds))));
        }
        ctx.put(PROBLEMS, List.copyOf(problems));
    }

    static Violation tooLarge(long rows, long bytes) {
        return new Violation(null, JournalCodes.TOO_LARGE, "A transaction's detail is too large to be kept within the "
            + "platform's limits: " + rows + " rows, " + bytes + " bytes", Map.of("rows", rows, "bytes", bytes));
    }

    static int openTransactions(ProcessContext ctx) {
        List<Map<String, Object>> open = rows(ctx, OPEN);
        return open.isEmpty() ? 0 : JournalLines.decimal(open.getFirst().get("openCount")).intValue();
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

    /** One input per part of the detail; a month without postings has one, empty, part. */
    static List<PartInput> partInputs(ProcessContext ctx) {
        EntityInstance previous = first(ctx, PREVIOUS);
        Instant previousTo = previous == null ? null : JournalLines.instant(previous.get("periodEnd"));
        Instant previousKnownAt = previous == null ? null : JournalLines.instant(previous.get("knownAt"));
        Map<String, Integer> lines = Map.copyOf(summary(ctx).lineNumbers());
        String voucherNo = (String) ctx.get(VOUCHER_NO);
        List<Map<String, Object>> parts = rows(ctx, PARTS);
        if (parts.isEmpty()) {
            return List.of(new PartInput(voucherNo, 1, 1, NO_TRANSACTION, NO_TRANSACTION, period(ctx).end(),
                (Instant) ctx.get(KNOWN_AT), previousTo, previousKnownAt, lines, 0));
        }
        List<PartInput> inputs = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            inputs.add(new PartInput(voucherNo, JournalLines.decimal(part.get("partNo")).intValue(), parts.size(),
                JournalLines.text(part.get("firstTransaction")), JournalLines.text(part.get("lastTransaction")),
                period(ctx).end(), (Instant) ctx.get(KNOWN_AT), previousTo, previousKnownAt, lines,
                JournalLines.decimal(part.get("rowCount")).intValue()));
        }
        return inputs;
    }

    static void record(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        GeneratedFileProcesses.ArchiveOutput kept = ctx.get(KEPT, GeneratedFileProcesses.ArchiveOutput.class);
        List<DetailFile> detail = detailFiles(ctx);
        EntityInstance previous = first(ctx, PREVIOUS);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("periodFrom", period.from());
        values.put("periodTo", period.to());
        values.put("periodEnd", period.end());
        values.put("granularity", QbJournal.GRANULARITY_MONTH);
        values.put("voucherNo", ctx.get(VOUCHER_NO));
        values.put("knownAt", ctx.get(KNOWN_AT));
        if (previous != null) {
            values.put("previousExportId", previous.id());
        }
        values.put("lineCount", BigDecimal.valueOf(summary.lines().size()));
        values.put("transactionCount", BigDecimal.valueOf(transactionCount(ctx)));
        values.put("debitTotal", summary.debitTotal());
        values.put("creditTotal", summary.creditTotal());
        values.put("fileId", kept.fileId());
        values.put("fileSha256", kept.sha256());
        values.put("detailPartCount", BigDecimal.valueOf(detail.size()));
        values.put("exportedBy", ctx.request().actorId());
        Object exportId = ctx.changes().insert(QbJournal.EXPORT, values);
        ctx.put(EXPORT_ID, String.valueOf(exportId));
        for (DetailFile file : detail) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("exportId", exportId);
            part.put("partNo", BigDecimal.valueOf(file.partNo()));
            part.put("fileId", file.fileId());
            part.put("sha256", file.sha256());
            part.put("rowCount", BigDecimal.valueOf(file.rows()));
            ctx.changes().insert(QbJournal.PART, part);
        }
    }

    static int transactionCount(ProcessContext ctx) {
        return rows(ctx, PARTS).stream().mapToInt(part -> JournalLines.decimal(part.get("transactions")).intValue())
            .sum();
    }

    static PreviewOutput previewOutput(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        Map<String, String> mapping = mapping(ctx);
        EntityInstance previous = first(ctx, PREVIOUS);
        EntityInstance recon = first(ctx, RECON);
        List<Map<String, Object>> parts = rows(ctx, PARTS);
        long detailRows = parts.stream().mapToLong(part -> JournalLines.decimal(part.get("rowCount")).longValue())
            .sum();
        boolean within = problems(ctx).stream().noneMatch(p -> JournalCodes.TOO_LARGE.equals(p.ruleCode()));
        return new PreviewOutput(period.label(), period.from(), period.to(), !period.end().isAfter(ctx.opTime()),
            period.end(), previous == null ? null : previous.get("voucherNo"), (Instant) ctx.get(KNOWN_AT),
            recon == null ? null : recon.get("status"),
            recon == null ? null : JournalLines.instant(recon.get("scheduledTime")), reconOk(ctx, period),
            JournalLines.unmapped(summary.accounts(), mapping), JournalLines.invalid(summary.accounts(), mapping),
            summary.lines(), transactionCount(ctx), detailRows, Math.max(1, parts.size()), within,
            Boolean.TRUE.equals(ctx.get(NEAR_LIMIT)), openTransactions(ctx), summary.debitTotal(), summary.creditTotal(), summary.balanced(),
            problems(ctx).isEmpty());
    }

    static ExportOutput exportOutput(ProcessContext ctx) {
        JournalPeriods.Period period = period(ctx);
        JournalLines.Summary summary = summary(ctx);
        GeneratedFileProcesses.ArchiveOutput kept = ctx.get(KEPT, GeneratedFileProcesses.ArchiveOutput.class);
        return new ExportOutput((String) ctx.get(EXPORT_ID), (String) ctx.get(VOUCHER_NO), period.from(), period.to(),
            (Instant) ctx.get(KNOWN_AT), summary.lines().size(), transactionCount(ctx), summary.debitTotal(),
            summary.creditTotal(), kept.fileId(), kept.sha256(), detailFiles(ctx));
    }

    @SuppressWarnings("unchecked")
    static List<DetailFile> detailFiles(ProcessContext ctx) {
        List<DetailFile> files = (List<DetailFile>) ctx.get(DETAIL);
        return files == null ? List.of() : List.copyOf(files);
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
