package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BookingTime;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.ledger.LedgerEntities;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.finance.gl.AccountProcesses.list;
import static com.jabiz.finance.gl.JournalEntities.JOURNAL;
import static com.jabiz.finance.gl.JournalEntities.JOURNAL_DATASET;
import static com.jabiz.finance.gl.JournalEntities.LINE_DATASET;
import static com.jabiz.finance.gl.JournalEntities.POSTED;

/**
 * Entries the books make by themselves, each run by a job and idempotent:
 * <ul>
 *   <li>{@code FIN_RECURRING_RUN}: the entries of the active recurring templates for the period of a day, dated the
 *       period's last day and submitted like any entry, so the approval rules apply (FIN-GL-017). An entry is made
 *       once per template and period: a second run makes nothing.</li>
 *   <li>{@code FIN_AUTO_REVERSE_RUN}: the reversals of posted entries whose reversal date has come, numbered after
 *       the original with "-R" and posted at once into their open period (FIN-GL-018).</li>
 * </ul>
 */
public final class JournalAutomation {

    public static final String RECURRING_RUN = "FIN_RECURRING_RUN";
    public static final String AUTO_REVERSE_RUN = "FIN_AUTO_REVERSE_RUN";
    public static final String AUTO_REVERSE = "FIN_JOURNAL_AUTO_REVERSE";

    public static final String RECURRING_JOB = "fin.recurring";
    public static final String AUTO_REVERSE_JOB = "fin.auto-reverse";

    public static final String NO_PERIOD = "FIN_RECURRING_NO_PERIOD";
    public static final String TOO_MANY_LINES = "FIN_RECURRING_TOO_MANY_LINES";
    public static final String FUTURE_DATE = "FIN_AUTO_REVERSE_FUTURE_DATE";

    /** Most templates or entries one run handles; a run reads at most this many. */
    static final int MAX_PER_RUN = 200;
    /** Most template lines one run reads; a run that would need more is refused rather than cut short. */
    static final int MAX_TEMPLATE_LINES = 10_000;

    /** @param date any day of the period to make the entries of */
    public record RunInput(@NotNull LocalDate date) {}

    /** @param skipped templates whose entry could not be made this time, with the reason */
    public record RecurringOutput(String periodKey, List<JournalProcesses.JournalOutput> entries,
        List<Skipped> skipped) {}

    public record Skipped(String templateCode, String reason) {}

    public record AutoReverseOutput(LocalDate date, List<ReversalOutput> reversals) {}

    /** @param reason why there is no reversal, when there is none */
    public record ReversalOutput(String journalId, String reversalId, String journalNo, boolean posted,
        String reason) {}

    static final String INPUT = "input";
    static final String PERIODS = "periods";
    static final String TEMPLATES = "templates";
    static final String TEMPLATE_LINES = "templateLines";
    static final String EXISTING = "existing";
    static final String TO_SUBMIT = "toSubmit";
    static final String SUBMITTED = "submitted";
    static final String SKIPPED = "skipped";
    static final String DUE = "due";
    static final String REVERSALS = "reversals";
    static final String TO_REVERSE = "toReverse";
    static final String REVERSED = "reversed";
    static final String JOURNAL_ID = "journalId";
    static final String JOURNAL_KEY = "journal";
    static final String LINES = "lines";
    static final String POST_INPUT = "postInput";
    static final String POSTED_OUTPUT = "postedOutput";
    static final String OUTPUT = "output";

    // ---- recurring entries ----------------------------------------------------------------------------------------

    public static final ProcessDefinition<RunInput, RecurringOutput, ProcessContext> RECURRING_PROCESS =
        ProcessDefinition.define(RECURRING_RUN, 1, RunInput.class, RecurringOutput.class, ProcessContext.class,
            pb -> pb
                .description("Makes and submits the entries of the recurring templates for a period, once.")
                .permissions(FinancePermissions.JOURNAL_PREPARE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(JournalAutomation::recurringOutput)
                .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> JournalProcesses.periodsOf(ctx.get(INPUT, RunInput.class).date()), PERIODS))
                .step("Load the active templates", QueryEntities.of(JournalEntities.RECURRING_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("active", true)).limit(MAX_PER_RUN)
                        .build(), TEMPLATES))
                .step("Load their lines", QueryEntities.of(JournalEntities.RECURRING_LINE_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("templateId",
                        new ArrayList<>(list(ctx, TEMPLATES).stream().map(EntityInstance::id).toList())))
                        .limit(MAX_TEMPLATE_LINES + 1).build(), TEMPLATE_LINES))
                .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> JournalProcesses
                    .byCodes("accountCode", JournalProcesses.accountCodes(ctx, TEMPLATE_LINES)),
                    JournalProcesses.FIN_ACCOUNTS))
                .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                    ctx -> JournalProcesses.byCodes("accountCode",
                        JournalProcesses.accountCodes(ctx, TEMPLATE_LINES)), JournalProcesses.LEDGER_ACCOUNTS))
                .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                    ctx -> JournalProcesses.byCodes("departmentCode",
                        JournalProcesses.dimensionValues(ctx, TEMPLATE_LINES, "department")),
                    JournalProcesses.DEPARTMENTS))
                .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                    ctx -> JournalProcesses.byCodes("locationCode",
                        JournalProcesses.dimensionValues(ctx, TEMPLATE_LINES, "location")),
                    JournalProcesses.LOCATIONS))
                .step("Load the entries made already", QueryEntities.of(JOURNAL_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("recurringKey",
                        new ArrayList<>(keys(ctx)))).limit(MAX_PER_RUN).build(), EXISTING))
                .compute("Make the entries", (metadata, ctx) -> makeRecurring(ctx))
                .step("Save", SaveChanges.now())
                .step("Submit them", CallProcess.forEach(JournalProcesses.SUBMIT, 1, ctx -> list(ctx, TO_SUBMIT),
                    SUBMITTED)));

    static void makeRecurring(ProcessContext ctx) {
        EntityInstance period = JournalProcesses.period(ctx, false);
        if (period == null) {
            LocalDate date = ctx.get(INPUT, RunInput.class).date();
            ctx.reject(new Violation("date", NO_PERIOD, "No regular fiscal period holds " + date,
                Map.of("date", date.toString())));
            return;
        }
        if (list(ctx, TEMPLATE_LINES).size() > MAX_TEMPLATE_LINES) {
            ctx.reject(new Violation("date", TOO_MANY_LINES, "The active templates have more than "
                + MAX_TEMPLATE_LINES + " lines", Map.of("limit", String.valueOf(MAX_TEMPLATE_LINES))));
            return;
        }
        LocalDate start = period.get("startDate");
        LocalDate end = period.get("endDate");
        Set<String> made = new HashSet<>();
        for (EntityInstance existing : list(ctx, EXISTING)) {
            made.add(existing.get("recurringKey"));
        }
        List<JournalProcesses.JournalId> toSubmit = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<EntityInstance> templates = new ArrayList<>(list(ctx, TEMPLATES));
        templates.sort(Comparator.comparing(t -> t.<String>get("templateCode")));
        for (EntityInstance template : templates) {
            LocalDate from = template.get("startDate");
            LocalDate until = template.get("endDate");
            String key = key(template, period);
            if (from.isAfter(end) || until != null && until.isBefore(start) || made.contains(key)) {
                continue;
            }
            List<JournalValidator.Line> lines = list(ctx, TEMPLATE_LINES).stream()
                .filter(line -> template.id().equals(line.get("templateId")))
                .sorted(Comparator.comparing(line -> line.<BigDecimal>get("lineNo")))
                .map(JournalProcesses::line)
                .toList();
            // A template that cannot post is reported and left out; the others still go (FIN-GL-017).
            List<Violation> problems = new ArrayList<>(JournalValidator.checkLines(lines));
            if (problems.isEmpty()) {
                problems.addAll(JournalValidator.checkForPosting(lines, JournalProcesses.accounts(ctx),
                    JournalProcesses.dimensions(ctx), false));
            }
            if (!problems.isEmpty()) {
                skipped.add(new Skipped(template.get("templateCode"),
                    String.join("; ", problems.stream().map(Violation::message).toList())));
                continue;
            }
            JournalValidator.Totals totals = JournalValidator.totals(lines);
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("postingDate", end);
            header.put("documentDate", end);
            header.put("description", template.get("description"));
            header.put("source", JournalEntities.RECURRING_SOURCE);
            header.put("status", JournalEntities.DRAFT);
            header.put("preparer", ctx.request().actorId());
            header.put("adjusting", false);
            header.put("adjustmentPeriod", false);
            header.put("recurringKey", key);
            header.put("totalDebit", totals.debit());
            header.put("totalCredit", totals.credit());
            Object id = ctx.changes().insert(JOURNAL, header);
            JournalProcesses.insertLines(ctx, id, lines);
            toSubmit.add(new JournalProcesses.JournalId(UUID.fromString(String.valueOf(id))));
        }
        ctx.put(TO_SUBMIT, List.copyOf(toSubmit));
        ctx.put(SKIPPED, List.copyOf(skipped));
        ctx.put(OUTPUT, period.get("periodKey"));
    }

    /** "PREPAID-INS/2026-01": one entry per template and period. */
    private static String key(EntityInstance template, EntityInstance period) {
        return template.get("templateCode") + "/" + period.get("periodKey");
    }

    private static Set<String> keys(ProcessContext ctx) {
        Set<String> keys = new HashSet<>();
        EntityInstance period = JournalProcesses.period(ctx, false);
        if (period != null) {
            for (EntityInstance template : list(ctx, TEMPLATES)) {
                keys.add(key(template, period));
            }
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static RecurringOutput recurringOutput(ProcessContext ctx) {
        List<JournalProcesses.JournalOutput> submitted = ctx.contains(SUBMITTED)
            ? (List<JournalProcesses.JournalOutput>) ctx.get(SUBMITTED) : List.of();
        List<Skipped> skipped = ctx.contains(SKIPPED) ? (List<Skipped>) ctx.get(SKIPPED) : List.of();
        return new RecurringOutput(ctx.contains(OUTPUT) ? ctx.get(OUTPUT, String.class) : null,
            List.copyOf(submitted), skipped);
    }

    // ---- automatic reversals --------------------------------------------------------------------------------------

    public static ProcessDefinition<RunInput, AutoReverseOutput, ProcessContext> autoReverseRunProcess(
        BookingTime booking) {
        return ProcessDefinition.define(AUTO_REVERSE_RUN, 1, RunInput.class, AutoReverseOutput.class,
            ProcessContext.class, pb -> pb
                .description("Posts the reversals of the entries whose reversal date has come.")
                .permissions(FinancePermissions.JOURNAL_PREPARE)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(JournalAutomation::autoReverseOutput)
                .compute("Check the date", (metadata, ctx) -> {
                    // Reversals post without approval: never ahead of their day.
                    LocalDate date = ctx.get(INPUT, RunInput.class).date();
                    LocalDate today = booking.dateOf(ctx.opTime());
                    if (date.isAfter(today)) {
                        ctx.reject(new Violation("date", FUTURE_DATE, "Reversals due on " + date
                            + " are not posted before that day", Map.of("today", today.toString())));
                    }
                })
                // Only entries not reversed yet: those already reversed never crowd the others out.
                .step("Load the entries due", QueryEntities.of(JOURNAL_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("status", POSTED),
                        new QueryPredicate.Lte("autoReverseDate", ctx.get(INPUT, RunInput.class).date()),
                        new QueryPredicate.IsNull("reversedById"))))
                    .limit(MAX_PER_RUN).build(), DUE))
                .step("Load their reversals", QueryEntities.of(JOURNAL_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("reversesJournalId",
                        new ArrayList<>(list(ctx, DUE).stream().map(EntityInstance::id).toList())))
                    .limit(MAX_PER_RUN).build(), REVERSALS))
                .compute("Pick the entries to reverse", (metadata, ctx) -> {
                    Set<Object> reversed = new HashSet<>();
                    for (EntityInstance reversal : list(ctx, REVERSALS)) {
                        reversed.add(String.valueOf((Object) reversal.get("reversesJournalId")));
                    }
                    List<JournalProcesses.JournalId> toReverse = new ArrayList<>();
                    for (EntityInstance journal : list(ctx, DUE)) {
                        if (!reversed.contains(String.valueOf(journal.id()))) {
                            UUID journalId = UUID.fromString(String.valueOf(journal.id()));
                            toReverse.add(new JournalProcesses.JournalId(journalId));
                        }
                    }
                    ctx.put(TO_REVERSE, List.copyOf(toReverse));
                })
                .step("Reverse them", CallProcess.forEach(AUTO_REVERSE, 1, ctx -> list(ctx, TO_REVERSE),
                    REVERSED)));
    }

    @SuppressWarnings("unchecked")
    private static AutoReverseOutput autoReverseOutput(ProcessContext ctx) {
        List<ReversalOutput> reversed = ctx.contains(REVERSED) ? (List<ReversalOutput>) ctx.get(REVERSED) : List.of();
        return new AutoReverseOutput(ctx.get(INPUT, RunInput.class).date(), List.copyOf(reversed));
    }

    public static final ProcessDefinition<JournalProcesses.JournalId, ReversalOutput, ProcessContext>
        AUTO_REVERSE_PROCESS = ProcessDefinition.define(AUTO_REVERSE, 1, JournalProcesses.JournalId.class,
            ReversalOutput.class, ProcessContext.class, pb -> pb
                .description("Posts the automatic reversal of one entry; run by the automatic reversal.")
                .permissions(FinancePermissions.JOURNAL_POST)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(JOURNAL_ID, input.journalId());
                    return ctx;
                })
                .outputMapper(JournalAutomation::reversalOutput)
                .step("Load the entry", LoadEntity.by(JOURNAL_DATASET, JOURNAL_ID, JOURNAL_KEY))
                .step("Load its lines", QueryEntities.of(LINE_DATASET,
                    ctx -> JournalProcesses.linesOf(ctx.get(JOURNAL_ID)), LINES))
                .step("Look for its reversal", QueryEntities.of(JOURNAL_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("reversesJournalId", ctx.get(JOURNAL_ID))).limit(1).build(),
                    REVERSALS))
                .step("Load the period of the reversal", QueryEntities.of(GlEntities.PERIOD_DATASET,
                    ctx -> JournalProcesses.periodsOf(ctx.get(JOURNAL_KEY, EntityInstance.class)
                        .get("autoReverseDate")), PERIODS))
                .step("Load the accounts", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> JournalProcesses
                    .byCodes("accountCode", JournalProcesses.accountCodes(ctx, LINES)), JournalProcesses.FIN_ACCOUNTS))
                .step("Load the ledger accounts", QueryEntities.of(LedgerEntities.ACCOUNT_DATASET,
                    ctx -> JournalProcesses.byCodes("accountCode", JournalProcesses.accountCodes(ctx, LINES)),
                    JournalProcesses.LEDGER_ACCOUNTS))
                .step("Load the departments", QueryEntities.of(GlEntities.DEPARTMENT_DATASET,
                    ctx -> JournalProcesses.byCodes("departmentCode",
                        JournalProcesses.dimensionValues(ctx, LINES, "department")), JournalProcesses.DEPARTMENTS))
                .step("Load the locations", QueryEntities.of(GlEntities.LOCATION_DATASET,
                    ctx -> JournalProcesses.byCodes("locationCode",
                        JournalProcesses.dimensionValues(ctx, LINES, "location")), JournalProcesses.LOCATIONS))
                .compute("Make the reversal", (metadata, ctx) -> makeReversal(ctx))
                .step("Save", SaveChanges.now())
                .step("Post it", CallProcess.when(ctx -> ctx.contains(POST_INPUT), JournalProcesses.POST, 1,
                    ctx -> ctx.get(POST_INPUT), POSTED_OUTPUT)));

    static void makeReversal(ProcessContext ctx) {
        EntityInstance original = ctx.get(JOURNAL_KEY, EntityInstance.class);
        String reason = null;
        EntityInstance period = JournalProcesses.period(ctx, false);
        if (!POSTED.equals(original.get("status")) || original.get("autoReverseDate") == null) {
            reason = "not a posted entry to reverse";
        } else if (!list(ctx, REVERSALS).isEmpty()) {
            reason = "reversed already";
        } else if (period == null) {
            reason = "no fiscal period holds " + original.get("autoReverseDate");
        } else {
            reason = PeriodPolicy.check(JournalProcesses.state(period, PeriodPolicy.Source.GL),
                PeriodPolicy.Source.GL, false, false).map(PeriodPolicy.Refusal::message).orElse(null);
        }
        if (reason == null) {
            // An account closed since: the reversal waits, with the reason, until it is open again.
            List<Violation> problems = JournalValidator.checkForPosting(list(ctx, LINES).stream()
                .map(JournalProcesses::line).toList(), JournalProcesses.accounts(ctx),
                JournalProcesses.dimensions(ctx), true);
            if (!problems.isEmpty()) {
                reason = String.join("; ", problems.stream().map(Violation::message).toList());
            }
        }
        if (reason != null) {
            ctx.put(OUTPUT, new ReversalOutput(String.valueOf(original.id()), null, null, false, reason));
            return;
        }
        String journalNo = original.get("journalNo") + "-R";
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("periodKey", period.get("periodKey"));
        extra.put("fiscalYear", period.get("fiscalYear"));
        Object id = JournalProcesses.insertReversal(ctx, original, list(ctx, LINES), JournalEntities.AUTO_REVERSING,
            original.get("autoReverseDate"), "Automatic reversal of " + original.get("journalNo"), journalNo,
            JournalEntities.APPROVED, ctx.request().actorId(), extra);
        ctx.changes().update(JOURNAL, original.id(), original.version(), Map.of("reversedById", id));
        // The reversal posts on the authority of the original's approval: it only takes it back.
        String hash = JournalProcesses.contentHash(reversalHeader(original, journalNo), JournalEntities.AUTO_REVERSING,
            reversedLines(list(ctx, LINES)));
        ctx.put(POST_INPUT, new JournalProcesses.PostInput(UUID.fromString(String.valueOf(id)), hash));
        ctx.put(OUTPUT, new ReversalOutput(String.valueOf(original.id()), String.valueOf(id), journalNo, false, null));
    }

    private static Map<String, Object> reversalHeader(EntityInstance original, String journalNo) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", original.get("autoReverseDate"));
        header.put("documentDate", original.get("autoReverseDate"));
        header.put("description", "Automatic reversal of " + original.get("journalNo"));
        header.put("adjusting", Boolean.TRUE.equals(original.get("adjusting")));
        header.put("adjustmentPeriod", false);
        header.put("reversesJournalId", original.id());
        return header;
    }

    private static List<Map<String, Object>> reversedLines(List<EntityInstance> lines) {
        return JournalProcesses.lineMaps(lines.stream()
            .sorted(Comparator.comparing(line -> line.<BigDecimal>get("lineNo")))
            .map(line -> new JournalValidator.Line(line.get("accountCode"), line.get("credit"), line.get("debit"),
                line.get("memo"), line.get("department"), line.get("location")))
            .toList());
    }

    private static ReversalOutput reversalOutput(ProcessContext ctx) {
        ReversalOutput made = ctx.get(OUTPUT, ReversalOutput.class);
        if (!ctx.contains(POSTED_OUTPUT)) {
            return made;
        }
        JournalProcesses.PostOutput posted = ctx.get(POSTED_OUTPUT, JournalProcesses.PostOutput.class);
        return new ReversalOutput(made.journalId(), made.reversalId(), made.journalNo(), posted.posted(),
            posted.reason());
    }

    private JournalAutomation() {}
}
