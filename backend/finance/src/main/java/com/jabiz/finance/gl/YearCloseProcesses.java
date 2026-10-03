package com.jabiz.finance.gl;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.CloseChecks;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import com.jabiz.runtime.report.ReportProcesses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The year-end close (FIN-PC-008; docs/finance/00-design.md section 7.3; ROADMAP F8c):
 * <ul>
 *   <li>{@code FIN_CLOSE_SETTINGS_SET}: the retained earnings account, an equity account that is no control
 *       account.</li>
 *   <li>{@code FIN_YEAR_CLOSE}: once periods 1 to 12 are closed and the adjustment period 13 is not, the closing entry
 *       ({@code CLS-2026}, source CLS) carries the balance of every income and expense account, adjustments of period
 *       13 included, to retained earnings, dated the year's last day in period 13; period 13 closes with its artifact.
 *       Closed again (period 13 reopened for an adjustment), the earlier closing entry is reversed
 *       ({@code CLS-2026-R}) and a new one posted ({@code CLS-2026-2}), and the new artifact supersedes the earlier.
 *       The next year needs no opening entry: balance-sheet balances carry forward, income and expense start at
 *       zero.</li>
 * </ul>
 */
public final class YearCloseProcesses {

    public static final String SETTINGS_SET = "FIN_CLOSE_SETTINGS_SET";
    public static final String YEAR_CLOSE = "FIN_YEAR_CLOSE";

    public static final String SETTINGS_ACCOUNT = "FIN_CLOSE_SETTINGS_ACCOUNT";
    public static final String NO_SETTINGS = "FIN_YEAR_CLOSE_NO_SETTINGS";
    public static final String NO_YEAR = "FIN_YEAR_CLOSE_NO_YEAR";
    public static final String NO_ADJUSTMENT_PERIOD = "FIN_YEAR_CLOSE_NO_ADJUSTMENT_PERIOD";
    public static final String PERIODS_OPEN = "FIN_YEAR_CLOSE_PERIODS_OPEN";
    public static final String CLOSED_ALREADY = "FIN_YEAR_CLOSE_CLOSED";
    public static final String EARLIER_OPEN = "FIN_YEAR_CLOSE_EARLIER_OPEN";
    public static final String NOT_POSTED = "FIN_YEAR_CLOSE_NOT_POSTED";

    /** The finance types of the income statement: closed to retained earnings at the year's end. */
    public static final Set<String> INCOME_TYPES = Set.of("REVENUE", "EXPENSE", "TAX", "OTHER");

    public record SettingsInput(@NotBlank @Size(max = 20) String retainedEarningsAccount) {}

    public record SettingsOutput(String retainedEarningsAccount) {}

    public record YearInput(@NotNull @Min(2000) @Max(2999) Integer fiscalYear) {}

    /**
     * @param netIncome   the year's net income (a loss negative), carried to retained earnings
     * @param journalNo   the closing entry; none when the income and expense accounts were at zero
     * @param reversalNo  the reversal of the year's earlier closing entry, when closed again
     */
    public record YearOutput(int fiscalYear, int seq, String journalNo, String reversalNo, BigDecimal netIncome,
        String retainedEarningsAccount, String artifactId, String reportRunId, String trialBalanceHash) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";
    static final String YEARS = "years";
    static final String PERIODS = "periods";
    static final String EARLIER = "earlier";
    static final String EARLIER_CLOSES = "earlierCloses";
    static final String EARLIER_13 = "earlier13";
    static final String CLOSES = "closes";
    static final String OLD_LINES = "oldLines";
    static final String OLD_JOURNALS = "oldJournals";
    static final String BEFORE = "before";
    static final String AFTER = "after";
    static final String ARTIFACTS = "artifacts";
    static final String POSTS = "posts";
    static final String POSTED = "posted";
    static final String MADE = "made";
    static final String ISSUE = "issue";
    static final String ISSUED = "issued";

    public static final ProcessDefinition<SettingsInput, SettingsOutput, ProcessContext> SETTINGS_PROCESS =
        ProcessDefinition.define(SETTINGS_SET, 1, SettingsInput.class, SettingsOutput.class, ProcessContext.class,
            pb -> pb
                .description("Sets the retained earnings account the year-end close carries net income to.")
                .permissions(FinancePermissions.PERIOD_CLOSE)
                .contextFactory(YearCloseProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, SettingsOutput.class))
                .step("Load the settings", QueryEntities.of(CloseEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
                .step("Load the account", QueryEntities.of(GlEntities.ACCOUNT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.Eq("accountCode", code(ctx.get(INPUT, SettingsInput.class)
                        .retainedEarningsAccount()))).limit(1).build(), ACCOUNTS))
                .compute("Set them", (metadata, ctx) -> setSettings(ctx)));

    /** {@code FIN_YEAR_CLOSE}: the closing entries post through {@code FIN_JOURNAL_POST}. */
    public static final ProcessDefinition<YearInput, YearOutput, ProcessContext> YEAR_CLOSE_PROCESS =
        ProcessDefinition.define(YEAR_CLOSE, 1, YearInput.class, YearOutput.class, ProcessContext.class, pb -> pb
            .description("Closes a fiscal year: its income and expense to retained earnings in period 13.")
            .permissions(FinancePermissions.PERIOD_CLOSE)
            .contextFactory(YearCloseProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, YearOutput.class))
            .step("Load the settings", QueryEntities.of(CloseEntities.SETTINGS_DATASET, ctx -> current(), SETTINGS))
            .step("Load the year", QueryEntities.of(GlEntities.FISCAL_YEAR_DATASET, ctx -> byYear(year(ctx)), YEARS))
            .step("Take the year's lock", PeriodLocks.year(
                ctx -> com.jabiz.finance.calc.FiscalCalendar.key(year(ctx), com.jabiz.finance.calc.FiscalCalendar.ADJUSTMENT)))
            .step("Take the lock of period 13", PeriodLocks.exclusive(
                ctx -> com.jabiz.finance.calc.FiscalCalendar.key(year(ctx), com.jabiz.finance.calc.FiscalCalendar.ADJUSTMENT)))
            .step("Load its periods", QueryEntities.of(GlEntities.PERIOD_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.Eq("fiscalYear", BigDecimal.valueOf(year(ctx)))).limit(20).build(),
                PERIODS))
            .step("Load the year before", QueryEntities.of(GlEntities.FISCAL_YEAR_DATASET,
                ctx -> byYear(year(ctx) - 1), EARLIER))
            .step("Load its closes", QueryEntities.of(CloseEntities.YEAR_CLOSE_DATASET,
                ctx -> byYear(year(ctx) - 1, 100), EARLIER_CLOSES))
            .step("Load its period 13", QueryEntities.of(GlEntities.PERIOD_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("fiscalYear", BigDecimal.valueOf(year(ctx) - 1L)),
                    new QueryPredicate.Eq("adjustment", true)))).limit(1).build(), EARLIER_13))
            .step("Load the year's closes", QueryEntities.of(CloseEntities.YEAR_CLOSE_DATASET,
                ctx -> byYear(year(ctx), 100), CLOSES))
            .step("Load the closing entry to reverse", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                ctx -> byJournal(ctx, 1), OLD_JOURNALS))
            .step("Load its lines", QueryEntities.of(JournalEntities.LINE_DATASET, ctx -> byJournal(ctx,
                JournalValidator.MAX_LINES), OLD_LINES))
            .step("Load the retained earnings account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("accountCode", retained(ctx) == null ? ""
                    : retained(ctx))).limit(1).build(), ACCOUNTS))
            .step("Load the year's balances", RunTemplate.of(CloseProcesses.TRIAL_BALANCE,
                YearCloseProcesses::trialBalanceParams, BEFORE))
            .step("Load period 13's artifacts", QueryEntities.of(CloseEntities.ARTIFACT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("periodKey", year(ctx) + "-13"))
                    .limit(1000).build(), ARTIFACTS))
            .compute("Make the closing entries", (metadata, ctx) -> prepare(ctx))
            .step("Save", SaveChanges.now())
            .step("Post them", CallProcess.forEach(JournalProcesses.POST, 1,
                ctx -> ctx.contains(POSTS) ? (List<?>) ctx.get(POSTS) : List.of(), POSTED))
            .compute("Check they posted", (metadata, ctx) -> checkPosted(ctx))
            .step("Load the year's balances after", RunTemplate.of(CloseProcesses.TRIAL_BALANCE,
                YearCloseProcesses::trialBalanceParams, AFTER))
            .compute("Close period 13", (metadata, ctx) -> closePeriod(ctx))
            .step("Issue the trial balance", CallProcess.when(ctx -> ctx.contains(ISSUE), ReportProcesses.ISSUE, 1,
                ctx -> ctx.get(ISSUE), ISSUED))
            .compute("Keep the artifact", (metadata, ctx) -> record(ctx))
            .step("Keep period 13's balances", PeriodBalances.keep(ctx -> year(ctx) + "-13",
                ctx -> ctx.contains(OUTPUT))));

    // ---- settings --------------------------------------------------------------------------------------------------

    static void setSettings(ProcessContext ctx) {
        String code = code(ctx.get(INPUT, SettingsInput.class).retainedEarningsAccount());
        EntityInstance account = first(ctx, ACCOUNTS);
        if (account == null || !"EQUITY".equals(account.get("financialType"))
            || account.get("controlClass") != null || Boolean.TRUE.equals(account.get("summary"))) {
            ctx.reject(new Violation("retainedEarningsAccount", SETTINGS_ACCOUNT, "Retained earnings is an equity "
                + "account that is no control account; " + code + " is not", Map.of("accountCode", code)));
            return;
        }
        EntityInstance settings = first(ctx, SETTINGS);
        if (settings == null) {
            ctx.changes().insert(CloseEntities.SETTINGS, Map.of("settingsKey", CloseEntities.SETTINGS_KEY,
                "retainedEarningsAccount", code));
        } else {
            ctx.changes().update(CloseEntities.SETTINGS, settings.id(), settings.version(),
                Map.of("retainedEarningsAccount", code));
        }
        ctx.put(OUTPUT, new SettingsOutput(code));
    }

    // ---- the year close --------------------------------------------------------------------------------------------

    static void prepare(ProcessContext ctx) {
        int fiscalYear = year(ctx);
        EntityInstance year = first(ctx, YEARS);
        if (year == null) {
            ctx.reject(new Violation("fiscalYear", NO_YEAR, "There is no fiscal year " + fiscalYear,
                Map.of("fiscalYear", fiscalYear)));
            return;
        }
        String retained = retained(ctx);
        if (retained == null || first(ctx, ACCOUNTS) == null) {
            ctx.reject(new Violation("fiscalYear", NO_SETTINGS, "The close settings name no retained earnings "
                + "account", Map.of()));
            return;
        }
        EntityInstance thirteen = period13(ctx);
        if (thirteen == null) {
            ctx.reject(new Violation("fiscalYear", NO_ADJUSTMENT_PERIOD, "Fiscal year " + fiscalYear + " has no "
                + "adjustment period 13, where the closing entry goes", Map.of("fiscalYear", fiscalYear)));
            return;
        }
        if (PeriodPolicy.Status.CLOSED.name().equals(thirteen.get("status"))) {
            ctx.reject(new Violation("fiscalYear", CLOSED_ALREADY, "Fiscal year " + fiscalYear + " is closed: its "
                + "period 13 opens again only through a reopening", Map.of("fiscalYear", fiscalYear)));
            return;
        }
        List<String> open = list(ctx, PERIODS).stream()
            .filter(p -> !Boolean.TRUE.equals(p.get("adjustment")) && !Boolean.TRUE.equals(p.get("opening")))
            .filter(p -> !PeriodPolicy.Status.CLOSED.name().equals(p.get("status")))
            .map(p -> (String) p.get("periodKey")).sorted().toList();
        if (!open.isEmpty()) {
            ctx.reject(new Violation("fiscalYear", PERIODS_OPEN, "Periods " + String.join(", ", open) + " are not "
                + "closed", Map.of("periods", String.join(", ", open))));
            return;
        }
        // The year before, if the books hold it, is closed first and stays closed: else its income, or an
        // adjustment made since its close, would be carried into this one.
        EntityInstance earlier13 = first(ctx, EARLIER_13);
        if (first(ctx, EARLIER) != null && (list(ctx, EARLIER_CLOSES).isEmpty() || earlier13 == null
            || !PeriodPolicy.Status.CLOSED.name().equals(earlier13.get("status")))) {
            ctx.reject(new Violation("fiscalYear", EARLIER_OPEN, "Fiscal year " + (fiscalYear - 1) + " is not "
                + "closed", Map.of("fiscalYear", fiscalYear - 1)));
            return;
        }
        // Each income and expense account's balance at the year's end, without the earlier closing entry.
        Map<String, BigDecimal> balances = new TreeMap<>();
        for (Map<String, Object> row : rows(ctx, BEFORE)) {
            if (!Boolean.TRUE.equals(row.get("summary")) && INCOME_TYPES.contains(String.valueOf(
                row.get("financialType")))) {
                balances.put((String) row.get("accountCode"), money(row.get("balance")));
            }
        }
        EntityInstance oldJournal = first(ctx, OLD_JOURNALS);
        if (oldJournal != null && oldJournal.get("reversedById") != null) {
            ctx.reject(new Violation("fiscalYear", NOT_POSTED, "The closing entry " + oldJournal.get("journalNo")
                + " is reversed already", Map.of("reason", "reversed already")));
            return;
        }
        List<JournalValidator.Line> reversal = new ArrayList<>();
        for (EntityInstance line : list(ctx, OLD_LINES)) {
            BigDecimal debit = line.get("debit");
            BigDecimal credit = line.get("credit");
            String account = line.get("accountCode");
            if (balances.containsKey(account)) {
                balances.merge(account, money(credit).subtract(money(debit)), BigDecimal::add);
            }
            reversal.add(new JournalValidator.Line(account, credit, debit, "Reversal: " + line.get("memo"), null,
                null));
        }
        List<JournalValidator.Line> closing = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO.setScale(2);
        for (Map.Entry<String, BigDecimal> balance : balances.entrySet()) {
            BigDecimal amount = balance.getValue();
            if (amount.signum() == 0) {
                continue;
            }
            net = net.add(amount);
            String memo = "Close " + balance.getKey() + " to retained earnings";
            closing.add(amount.signum() > 0 ? new JournalValidator.Line(balance.getKey(), null, amount, memo, null, null)
                : new JournalValidator.Line(balance.getKey(), amount.negate(), null, memo, null, null));
        }
        // Debits over credits are a loss: net income is their opposite.
        BigDecimal netIncome = net.negate();
        if (netIncome.signum() != 0) {
            String memo = "Net income of " + fiscalYear;
            closing.add(netIncome.signum() > 0 ? new JournalValidator.Line(retained, null, netIncome, memo, null, null)
                : new JournalValidator.Line(retained, netIncome.negate(), null, memo, null, null));
        }
        int seq = list(ctx, CLOSES).size() + 1;
        LocalDate end = thirteen.get("endDate");
        List<JournalProcesses.PostInput> posts = new ArrayList<>();
        String reversalNo = null;
        if (oldJournal != null) {
            reversalNo = oldJournal.get("journalNo") + "-R";
            Object reversalId = journal(ctx, reversalNo, end, "Reversal of the closing entry "
                + oldJournal.get("journalNo"), reversal, oldJournal.id(), thirteen, posts);
            ctx.changes().update(JournalEntities.JOURNAL, oldJournal.id(), oldJournal.version(),
                Map.of("reversedById", reversalId));
        }
        String journalNo = null;
        Object journalId = null;
        if (!closing.isEmpty()) {
            journalNo = "CLS-" + fiscalYear + (seq == 1 ? "" : "-" + seq);
            journalId = journal(ctx, journalNo, end, "Year-end closing entry " + fiscalYear, closing, null,
                thirteen, posts);
        }
        ctx.put(POSTS, List.copyOf(posts));
        Map<String, Object> made = new LinkedHashMap<>();
        made.put("seq", seq);
        made.put("journalNo", journalNo);
        made.put("journalId", journalId == null ? null : String.valueOf(journalId));
        made.put("reversalNo", reversalNo);
        made.put("netIncome", netIncome);
        ctx.put(MADE, made);
    }

    /** A system journal of the year close, approved by its making and posted at once into period 13. */
    private static Object journal(ProcessContext ctx, String journalNo, LocalDate day, String description,
        List<JournalValidator.Line> lines, Object reverses, EntityInstance period,
        List<JournalProcesses.PostInput> posts) {
        JournalValidator.Totals totals = JournalValidator.totals(lines);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("postingDate", day);
        header.put("documentDate", day);
        header.put("description", description);
        header.put("adjusting", true);
        header.put("adjustmentPeriod", true);
        if (reverses != null) {
            header.put("reversesJournalId", reverses);
        }
        String hash = JournalProcesses.contentHash(header, JournalEntities.CLOSING, JournalProcesses.lineMaps(lines));
        header.put("source", JournalEntities.CLOSING);
        header.put("status", JournalEntities.APPROVED);
        header.put("preparer", ctx.request().actorId());
        header.put("totalDebit", totals.debit());
        header.put("totalCredit", totals.credit());
        header.put("journalNo", journalNo);
        header.put("periodKey", period.get("periodKey"));
        header.put("fiscalYear", period.get("fiscalYear"));
        header.put("contentHash", hash);
        Object id = ctx.changes().insert(JournalEntities.JOURNAL, header);
        JournalProcesses.insertLines(ctx, id, lines);
        posts.add(new JournalProcesses.PostInput(UUID.fromString(String.valueOf(id)), hash));
        return id;
    }

    @SuppressWarnings("unchecked")
    static void checkPosted(ProcessContext ctx) {
        if (!ctx.contains(POSTED)) {
            return;
        }
        for (JournalProcesses.PostOutput posted : (List<JournalProcesses.PostOutput>) ctx.get(POSTED)) {
            if (!posted.posted()) {
                ctx.reject(new Violation("fiscalYear", NOT_POSTED, "The closing entry was not posted: "
                    + posted.reason(), Map.of("reason", String.valueOf(posted.reason()))));
            }
        }
    }

    static void closePeriod(ProcessContext ctx) {
        if (!ctx.contains(MADE) || ctx.hasViolations()) {
            return;
        }
        EntityInstance thirteen = period13(ctx);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", PeriodPolicy.Status.CLOSED.name());
        PeriodProcesses.SUBLEDGER_FIELDS.values().forEach(field -> state.put(field, "CLOSED"));
        ctx.changes().update(GlEntities.PERIOD, thirteen.id(), thirteen.version(), state);
        ctx.put(ISSUE, new ReportProcesses.IssueInput(CloseProcesses.TRIAL_BALANCE, trialBalanceParams(ctx), null,
            null, null));
    }

    @SuppressWarnings("unchecked")
    static void record(ProcessContext ctx) {
        if (!ctx.contains(ISSUE)) {
            return;
        }
        Map<String, Object> made = (Map<String, Object>) ctx.get(MADE);
        EntityInstance thirteen = period13(ctx);
        ReportProcesses.IssueOutput issued = ctx.contains(ISSUED)
            ? ctx.get(ISSUED, ReportProcesses.IssueOutput.class) : null;
        BigDecimal netIncome = (BigDecimal) made.get("netIncome");
        String retained = retained(ctx);
        String result = (made.get("journalNo") == null ? "No income or expense to close"
            : made.get("journalNo") + ": net income " + netIncome.toPlainString() + " to " + retained)
            + (made.get("reversalNo") == null ? "" : "; " + made.get("reversalNo") + " reverses the earlier closing");
        List<List<Object>> items = List.of(java.util.Arrays.asList(CloseEntities.YEAR_END, "YEAR_CLOSE",
            "Year-end closing entry", CloseEntities.DONE, result));
        CloseProcesses.Written written = CloseProcesses.writeArtifact(ctx, thirteen,
            CloseProcesses.accounts(rows(ctx, AFTER)), List.of(), items, issued, list(ctx, ARTIFACTS));
        int seq = (Integer) made.get("seq");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fiscalYear", BigDecimal.valueOf(year(ctx)));
        values.put("seq", BigDecimal.valueOf(seq));
        values.put("journalId", made.get("journalId"));
        values.put("journalNo", made.get("journalNo"));
        values.put("reversalJournalNo", made.get("reversalNo"));
        values.put("netIncome", netIncome);
        values.put("retainedEarningsAccount", retained);
        values.put("artifactId", UUID.fromString(written.artifactId()));
        values.put("closedBy", ctx.request().actorId());
        values.put("closedAt", ctx.opTime());
        ctx.changes().insert(CloseEntities.YEAR_CLOSE, values);
        ctx.put(OUTPUT, new YearOutput(year(ctx), seq, (String) made.get("journalNo"), (String) made.get("reversalNo"),
            netIncome, retained, written.artifactId(), issued == null ? null : issued.runId(),
            written.trialBalanceHash()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The whole year, period 13 included, as known now. */
    private static Map<String, Object> trialBalanceParams(ProcessContext ctx) {
        EntityInstance thirteen = period13(ctx);
        LocalDate end = thirteen == null ? LocalDate.of(year(ctx), 12, 31) : thirteen.get("endDate");
        return Map.of("through", end, "adjustments", true, "knownAt", ctx.opTime());
    }

    private static EntityInstance period13(ProcessContext ctx) {
        return list(ctx, PERIODS).stream().filter(p -> Boolean.TRUE.equals(p.get("adjustment"))).findFirst()
            .orElse(null);
    }

    /** The year's latest closing entry, if it posted one: what a new close reverses. */
    private static String latestJournal(ProcessContext ctx) {
        return list(ctx, CLOSES).stream().max(Comparator.comparing(c -> c.<BigDecimal>get("seq")))
            .map(c -> (String) c.get("journalId")).orElse(null);
    }

    /** The journal of the year's latest closing entry (its header, or its lines): none before the first close. */
    private static EntityQuery byJournal(ProcessContext ctx, int limit) {
        String id = latestJournal(ctx);
        return EntityQuery.builder().where(new QueryPredicate.In("journalId", id == null ? List.of()
            : List.of(UUID.fromString(id)))).limit(limit).build();
    }

    private static String retained(ProcessContext ctx) {
        EntityInstance settings = first(ctx, SETTINGS);
        return settings == null ? null : settings.get("retainedEarningsAccount");
    }

    private static int year(ProcessContext ctx) {
        return ctx.get(INPUT, YearInput.class).fiscalYear();
    }

    private static EntityQuery byYear(int fiscalYear) {
        return byYear(fiscalYear, 1);
    }

    private static EntityQuery byYear(int fiscalYear, int limit) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("fiscalYear", BigDecimal.valueOf(fiscalYear)))
            .limit(limit).build();
    }

    static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("settingsKey", CloseEntities.SETTINGS_KEY)).limit(1)
            .build();
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : new BigDecimal(String.valueOf(value)).setScale(2);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(key);
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        Object found = ctx.get(key);
        return found instanceof List<?> l ? (List<EntityInstance>) l : List.of();
    }

    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = list(ctx, key);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static ProcessContext withInput(com.jabiz.process.ProcessStart start, Object input) {
        ProcessContext ctx = new ProcessContext(start);
        ctx.put(INPUT, input);
        return ctx;
    }

    private YearCloseProcesses() {}
}
