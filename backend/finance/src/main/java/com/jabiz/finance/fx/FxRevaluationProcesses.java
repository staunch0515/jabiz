package com.jabiz.finance.fx;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.ap.ApEntities;
import com.jabiz.finance.ap.ApSettingsProcesses;
import com.jabiz.finance.ar.ArEntities;
import com.jabiz.finance.ar.ArSettingsProcesses;
import com.jabiz.finance.calc.PeriodPolicy;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Period-end remeasurement of foreign currency items (FIN-FX-005, FX-006; F7 plan decisions D6, D7, D9):
 * <ul>
 *   <li>{@code FIN_FX_REVALUE}: the open receivables and payables in a foreign currency and the bank accounts in one
 *       (template {@code finance.fx.revaluation_items}) remeasured at the period's last day ({@code FXR-2601}): each
 *       subledger's control account takes the net difference against the unrealized account, and the same entries
 *       reverse on the next day ({@code FXR-2601-R}), so the documents keep the dollars they carry. A period has one
 *       run: asked again, the run made is returned and nothing is posted. A closed period refuses it (the subledger
 *       posting checks), as does a currency without a rate.</li>
 *   <li>{@code FIN_FX_REVALUE_SIMULATE}: the same computation, written nowhere, beside the period's run if it has
 *       one: with today's items and rates (a corrected rate shows its difference), or as recorded when the run was
 *       made, which gives the run's lines again.</li>
 * </ul>
 */
public final class FxRevaluationProcesses {

    public static final String REVALUE = "FIN_FX_REVALUE";
    public static final String SIMULATE = "FIN_FX_REVALUE_SIMULATE";
    public static final String ITEMS_TEMPLATE = "finance.fx.revaluation_items";

    public static final String NO_ACCOUNT = "FIN_FX_REVALUE_NO_ACCOUNT";
    public static final String TOO_MANY = "FIN_FX_REVALUE_TOO_MANY";
    public static final String NO_STATEMENT = "FIN_FX_REVALUE_NO_STATEMENT";
    public static final String NEXT_PERIOD = "FIN_FX_REVALUE_NEXT_PERIOD";

    public record RevalueInput(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String periodKey) {}

    /**
     * @param asRecorded as recorded when the period's run was made: its lines again
     */
    public record SimulateInput(@NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String periodKey,
        Boolean asRecorded) {}

    public record RunOutput(String runId, String runNo, String periodKey, LocalDate revaluationDate,
        LocalDate reversalDate, BigDecimal total, int lineCount, boolean created) {}

    /**
     * An item as computed now beside the run's line of it (null when the run has none, or there is no run).
     */
    public record SimulatedLine(String kind, String documentId, String documentNo, String currency,
        BigDecimal openAmount, BigDecimal carryingUsd, BigDecimal rate, BigDecimal revaluedUsd, BigDecimal difference,
        BigDecimal originalRate, BigDecimal originalDifference, BigDecimal change) {}

    public record SimulateOutput(String periodKey, LocalDate revaluationDate, String runNo, BigDecimal total,
        BigDecimal originalTotal, BigDecimal change, List<SimulatedLine> lines) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String FX_SETTINGS = "fxSettings";
    static final String AR_SETTINGS = "arSettings";
    static final String AP_SETTINGS = "apSettings";
    static final String RUNS = "runs";
    static final String RUN_LINES = "runLines";
    static final String ITEMS = "items";
    static final String POSTINGS = "postings";
    static final String REVERSALS = "reversals";
    static final String POSTED = "posted";
    static final String REVERSED = "reversed";
    static final String PERIODS = "periods";

    /** The most items a run takes; more is refused rather than cut short. */
    static final int MAX_LINES = 5000;

    public static final ProcessDefinition<RevalueInput, RunOutput, ProcessContext> REVALUE_PROCESS =
        ProcessDefinition.define(REVALUE, 1, RevalueInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Remeasures the open foreign currency items at a period's end and reverses it the next day.")
            .permissions(FinancePermissions.FX_RUN)
            .contextFactory(FxRevaluationProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
            .step("Load the receivables settings", QueryEntities.of(ArEntities.SETTINGS_DATASET,
                ctx -> ArSettingsProcesses.current(), AR_SETTINGS))
            .step("Load the payables settings", QueryEntities.of(ApEntities.SETTINGS_DATASET,
                ctx -> ApSettingsProcesses.current(), AP_SETTINGS))
            .step("Load the period's run", QueryEntities.of(FxEntities.RUN_DATASET,
                ctx -> runOf(ctx.get(INPUT, RevalueInput.class).periodKey()), RUNS))
            .step("Load the period and the next", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> periodsOf(ctx.get(INPUT, RevalueInput.class).periodKey()), PERIODS))
            .step("Load the open items", RunTemplate.of(ITEMS_TEMPLATE, ctx -> params(ctx,
                lastDay(ctx.get(INPUT, RevalueInput.class).periodKey())), ITEMS))
            .compute("Remeasure them", (metadata, ctx) -> revalue(ctx))
            // The ledger checks that the source document exists: the run is saved before it is booked.
            .step("Save the run", SaveChanges.now())
            .step("Post it", CallProcess.forEach(SubledgerPosting.POST, 1,
                ctx -> ctx.contains(POSTINGS) ? (List<?>) ctx.get(POSTINGS) : List.of(), POSTED))
            .step("Reverse it the next day", CallProcess.forEach(SubledgerPosting.POST, 1,
                ctx -> ctx.contains(REVERSALS) ? (List<?>) ctx.get(REVERSALS) : List.of(), REVERSED)));

    public static final ProcessDefinition<SimulateInput, SimulateOutput, ProcessContext> SIMULATE_PROCESS =
        ProcessDefinition.define(SIMULATE, 1, SimulateInput.class, SimulateOutput.class, ProcessContext.class,
            pb -> pb
                .description("Computes a period's remeasurement again, beside its run, writing nothing.")
                .permissions(FinancePermissions.FX_RUN)
                .contextFactory(FxRevaluationProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, SimulateOutput.class))
                .step("Load the settings", QueryEntities.of(FxEntities.SETTINGS_DATASET,
                    ctx -> FxSettingsProcesses.current(), FX_SETTINGS))
                .step("Load the period's run", QueryEntities.of(FxEntities.RUN_DATASET,
                    ctx -> runOf(ctx.get(INPUT, SimulateInput.class).periodKey()), RUNS))
                .step("Load its lines", QueryEntities.of(FxEntities.LINE_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("runId", first(ctx, RUNS) == null ? List.of()
                        : List.of(first(ctx, RUNS).id()))).limit(MAX_LINES).build(), RUN_LINES))
                .step("Load the open items", RunTemplate.at(ITEMS_TEMPLATE,
                    ctx -> params(ctx, lastDay(ctx.get(INPUT, SimulateInput.class).periodKey())), ctx -> null,
                    FxRevaluationProcesses::recordedAt, ITEMS))
                .compute("Compare", (metadata, ctx) -> simulate(ctx)));

    static void revalue(ProcessContext ctx) {
        RevalueInput input = ctx.get(INPUT, RevalueInput.class);
        EntityInstance existing = first(ctx, RUNS);
        if (existing != null) {
            // Once per period: asked again, the run made (D6).
            ctx.put(OUTPUT, output(existing.id(), existing.attributes(), false));
            return;
        }
        // A closed period is never remeasured (D6); the postings check the rest of the period's state.
        EntityInstance period = list(ctx, PERIODS).stream()
            .filter(p -> input.periodKey().equals(p.get("periodKey"))).findFirst().orElse(null);
        String nextKey = YearMonth.parse(input.periodKey()).plusMonths(1).toString();
        EntityInstance nextPeriod = list(ctx, PERIODS).stream()
            .filter(p -> nextKey.equals(p.get("periodKey"))).findFirst().orElse(null);
        if (period == null) {
            ctx.reject(new Violation("periodKey", JournalProcesses.NO_PERIOD, "No fiscal period " + input.periodKey(),
                Map.of("postingDate", input.periodKey())));
            return;
        }
        if (PeriodPolicy.Status.CLOSED.name().equals(period.get("status"))) {
            ctx.reject(new Violation("periodKey", PeriodPolicy.PERIOD_CLOSED, "Period " + input.periodKey()
                + " is closed", Map.of("periodKey", input.periodKey())));
            return;
        }
        // The reversal goes into the next period: it must exist and be open (a December run needs next year's
        // calendar). The subledgers stay open for both until the run is made.
        if (nextPeriod == null || PeriodPolicy.Status.CLOSED.name().equals(nextPeriod.get("status"))) {
            ctx.reject(new Violation("periodKey", NEXT_PERIOD, "The revaluation reverses on the first day of "
                + nextKey + ", which is " + (nextPeriod == null ? "in no fiscal year" : "closed"),
                Map.of("periodKey", nextKey)));
            return;
        }
        EntityInstance settings = first(ctx, FX_SETTINGS);
        if (settings == null || settings.get("unrealizedAccount") == null) {
            ctx.reject(new Violation("periodKey", FxSettingsProcesses.NO_SETTINGS, "The foreign currency settings "
                + "name no account for unrealized exchange gains and losses", Map.of()));
            return;
        }
        List<Map<String, Object>> items = items(ctx);
        if (items.size() > MAX_LINES) {
            ctx.reject(new Violation("periodKey", TOO_MANY, "More than " + MAX_LINES + " items to remeasure",
                Map.of("limit", MAX_LINES)));
            return;
        }
        LocalDate day = lastDay(input.periodKey());
        // A bank account's foreign balance is its statement's: one ending on the day, else the run is refused
        // rather than remeasuring a balance that misses the last days or is none at all.
        for (Map<String, Object> item : items) {
            if (FxEntities.BANK.equals(item.get("kind")) && !day.equals(item.get("statementDate"))) {
                ctx.reject(new Violation("periodKey", NO_STATEMENT, "Bank account " + item.get("documentNo")
                    + " in " + item.get("currency") + " has no statement ending on " + day,
                    Map.of("bankCode", (Object) item.get("documentNo"), "date", day.toString())));
            }
        }
        Set<String> unrated = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            if (item.get("rate") == null) {
                unrated.add((String) item.get("currency"));
            }
        }
        for (String currency : unrated) {
            ctx.reject(FxRates.missing("periodKey", currency, day, settings));
        }
        // The control account each kind of item is carried on.
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        Map<String, String> sourceOf = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String account = account(ctx, item);
            if (account == null) {
                ctx.reject(new Violation("periodKey", NO_ACCOUNT, "The " + item.get("kind") + " settings name no "
                    + "control account for " + item.get("documentNo"), Map.of("kind", (Object) item.get("kind"))));
                return;
            }
            if (item.get("rate") != null) {
                byAccount.merge(account, money(item.get("difference")), BigDecimal::add);
                sourceOf.put(account, source((String) item.get("kind")));
            }
        }
        if (ctx.hasViolations()) {
            return;
        }
        String runNo = "FXR-" + input.periodKey().substring(2, 4) + input.periodKey().substring(5, 7);
        LocalDate next = day.plusDays(1);
        BigDecimal total = byAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("periodKey", input.periodKey());
        run.put("runNo", runNo);
        run.put("revaluationDate", day);
        run.put("reversalDate", next);
        run.put("rateType", settings.get("revaluationRateType"));
        run.put("toleranceDays", BigDecimal.valueOf(FxRates.tolerance(settings)));
        run.put("total", total);
        run.put("lineCount", BigDecimal.valueOf(items.size()));
        run.put("actor", ctx.request().actorId());
        run.put("runTime", ctx.opTime());
        Object runId = ctx.changes().insert(FxEntities.RUN, run);
        for (Map<String, Object> item : items) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("runId", runId);
            line.put("periodKey", input.periodKey());
            for (String field : List.of("kind", "documentId", "documentNo", "partyCode", "currency", "rateDate",
                "rateType")) {
                line.put(field, item.get(field));
            }
            line.put("account", account(ctx, item));
            for (String field : List.of("openAmount", "carryingUsd", "revaluedUsd", "difference")) {
                line.put(field, money(item.get(field)));
            }
            line.put("rate", new BigDecimal(String.valueOf(item.get("rate"))));
            ctx.changes().insert(FxEntities.LINE, line);
        }
        // Each subledger's control account against the unrealized account, on the day and reversed the next.
        List<SubledgerPosting.PostInput> postings = new ArrayList<>();
        List<SubledgerPosting.PostInput> reversals = new ArrayList<>();
        String unrealized = settings.get("unrealizedAccount");
        Map<String, List<JournalProcesses.LineInput>> bySource = new LinkedHashMap<>();
        Map<String, List<JournalProcesses.LineInput>> backBySource = new LinkedHashMap<>();
        byAccount.forEach((account, difference) -> {
            if (difference.signum() == 0) {
                return;
            }
            String source = sourceOf.get(account);
            String memo = "Revaluation " + runNo;
            bySource.computeIfAbsent(source, s -> new ArrayList<>()).addAll(lines(account, unrealized, difference,
                memo));
            backBySource.computeIfAbsent(source, s -> new ArrayList<>()).addAll(lines(account, unrealized,
                difference.negate(), "Reversal of " + memo));
        });
        bySource.forEach((source, lines) -> postings.add(new SubledgerPosting.PostInput(source, day,
            "Foreign currency revaluation " + runNo, runNo, FxEntities.RUN, String.valueOf(runId), lines,
            List.of(source))));
        backBySource.forEach((source, lines) -> reversals.add(new SubledgerPosting.PostInput(source, next,
            "Reversal of foreign currency revaluation " + runNo, runNo + "-R", FxEntities.RUN, String.valueOf(runId),
            lines, List.of(source))));
        ctx.put(POSTINGS, List.copyOf(postings));
        ctx.put(REVERSALS, List.copyOf(reversals));
        ctx.put(OUTPUT, output(runId, run, true));
    }

    static void simulate(ProcessContext ctx) {
        SimulateInput input = ctx.get(INPUT, SimulateInput.class);
        EntityInstance run = first(ctx, RUNS);
        Map<String, EntityInstance> original = new LinkedHashMap<>();
        for (EntityInstance line : list(ctx, RUN_LINES)) {
            original.put(line.get("kind") + "|" + line.get("documentId"), line);
        }
        List<SimulatedLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : items(ctx)) {
            String key = item.get("kind") + "|" + item.get("documentId");
            seen.add(key);
            EntityInstance before = original.get(key);
            BigDecimal difference = item.get("difference") == null ? null : money(item.get("difference"));
            BigDecimal was = before == null ? null : before.get("difference");
            total = total.add(difference == null ? BigDecimal.ZERO : difference);
            lines.add(new SimulatedLine((String) item.get("kind"), (String) item.get("documentId"),
                (String) item.get("documentNo"), (String) item.get("currency"), money(item.get("openAmount")),
                money(item.get("carryingUsd")), item.get("rate") == null ? null
                    : new BigDecimal(String.valueOf(item.get("rate"))),
                item.get("revaluedUsd") == null ? null : money(item.get("revaluedUsd")), difference,
                before == null ? null : before.get("rate"), was,
                change(difference, was)));
        }
        // What the run remeasured that is no longer open as it was then.
        for (Map.Entry<String, EntityInstance> entry : original.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                EntityInstance before = entry.getValue();
                lines.add(new SimulatedLine(before.get("kind"), before.get("documentId"), before.get("documentNo"),
                    before.get("currency"), null, null, null, null, null, before.get("rate"),
                    before.get("difference"), change(null, before.get("difference"))));
            }
        }
        BigDecimal originalTotal = run == null ? null : run.get("total");
        ctx.put(OUTPUT, new SimulateOutput(input.periodKey(), lastDay(input.periodKey()),
            run == null ? null : run.get("runNo"), total, originalTotal, change(total, originalTotal),
            List.copyOf(lines)));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static BigDecimal change(BigDecimal now, BigDecimal was) {
        if (was == null) {
            return null;
        }
        return (now == null ? BigDecimal.ZERO : now).subtract(was);
    }

    /** A gain (positive) debits the control account and credits the unrealized account; a loss the other way. */
    private static List<JournalProcesses.LineInput> lines(String account, String unrealized, BigDecimal difference,
        String memo) {
        BigDecimal amount = difference.abs();
        return difference.signum() > 0
            ? List.of(new JournalProcesses.LineInput(account, amount, null, memo, null, null),
                new JournalProcesses.LineInput(unrealized, null, amount, memo, null, null))
            : List.of(new JournalProcesses.LineInput(unrealized, amount, null, memo, null, null),
                new JournalProcesses.LineInput(account, null, amount, memo, null, null));
    }

    private static String account(ProcessContext ctx, Map<String, Object> item) {
        return switch ((String) item.get("kind")) {
            case FxEntities.RECEIVABLE -> first(ctx, AR_SETTINGS) == null ? null
                : first(ctx, AR_SETTINGS).get("receivableAccount");
            case FxEntities.PAYABLE -> first(ctx, AP_SETTINGS) == null ? null
                : first(ctx, AP_SETTINGS).get("payableAccount");
            default -> (String) item.get("account");
        };
    }

    private static String source(String kind) {
        return switch (kind) {
            case FxEntities.RECEIVABLE -> "AR";
            case FxEntities.PAYABLE -> "AP";
            default -> "BANK";
        };
    }

    /** The template's parameters: the run's own rate type and days when it is reproduced, else the settings'. */
    private static Map<String, Object> params(ProcessContext ctx, LocalDate day) {
        EntityInstance settings = first(ctx, FX_SETTINGS);
        EntityInstance run = first(ctx, RUNS);
        boolean recorded = run != null && ctx.get(INPUT) instanceof SimulateInput s && Boolean.TRUE.equals(
            s.asRecorded());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("revaluationDate", day);
        params.put("rateType", recorded ? run.get("rateType") : settings == null ? FxEntities.CLOSING
            : settings.get("revaluationRateType"));
        params.put("toleranceDays", recorded ? run.get("toleranceDays")
            : BigDecimal.valueOf(FxRates.tolerance(settings)));
        return params;
    }

    private static Instant recordedAt(ProcessContext ctx) {
        SimulateInput input = ctx.get(INPUT, SimulateInput.class);
        EntityInstance run = first(ctx, RUNS);
        return run != null && Boolean.TRUE.equals(input.asRecorded()) ? run.get("runTime") : null;
    }

    static LocalDate lastDay(String periodKey) {
        return YearMonth.parse(periodKey).atEndOfMonth();
    }

    private static EntityQuery periodsOf(String periodKey) {
        return EntityQuery.builder().where(new QueryPredicate.In("periodKey", List.of(periodKey,
            YearMonth.parse(periodKey).plusMonths(1).toString()))).limit(2).build();
    }

    static EntityQuery runOf(String periodKey) {
        return EntityQuery.builder().where(new QueryPredicate.Eq("periodKey", periodKey)).limit(1).build();
    }

    private static RunOutput output(Object id, Map<String, Object> run, boolean created) {
        return new RunOutput(String.valueOf(id), (String) run.get("runNo"), (String) run.get("periodKey"),
            (LocalDate) run.get("revaluationDate"), (LocalDate) run.get("reversalDate"), money(run.get("total")),
            new BigDecimal(String.valueOf(run.get("lineCount"))).intValue(), created);
    }

    private static BigDecimal money(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value)).setScale(2);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ProcessContext ctx) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(ITEMS);
        return rows == null ? List.of() : rows;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
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

    private FxRevaluationProcesses() {}
}
