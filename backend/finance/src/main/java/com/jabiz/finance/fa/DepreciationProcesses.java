package com.jabiz.finance.fa;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Depreciation;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.finance.gl.SubledgerPosting;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.first;
import static com.jabiz.finance.fa.FaSupport.list;

/**
 * The monthly depreciation run (FIN-FA-005; ROADMAP F6b):
 * <ul>
 *   <li>{@code FIN_FA_DEPRECIATION_RUN}: depreciates every asset in service for a month and posts one entry
 *       ({@code DEP-2601}, source FA) dated the month's last day, its lines summed by expense account, accumulated
 *       depreciation account and department; each asset's share is a {@code FinDepreciationLine}. Months are run in
 *       order from the month after the cutover; a month run already is not run again (F6 plan decision D7). An asset
 *       without a class, or by units of production without the month's units, stops the run.</li>
 *   <li>{@code FIN_FA_DEPRECIATION_REVERSE}: takes back the latest run of an open month, its entry reversed by the
 *       ledger and its assets put back as they were; the month can then be run again. Not once an asset of it has
 *       been changed or disposed of since.</li>
 * </ul>
 */
public final class DepreciationProcesses {

    public static final String RUN = "FIN_FA_DEPRECIATION_RUN";
    public static final String REVERSE = "FIN_FA_DEPRECIATION_REVERSE";

    public static final String NO_START = "FIN_FA_NO_START";
    public static final String OUT_OF_ORDER = "FIN_FA_RUN_OUT_OF_ORDER";
    public static final String UNCLASSIFIED = "FIN_FA_UNCLASSIFIED";
    public static final String USAGE_MISSING = "FIN_FA_USAGE_MISSING";
    public static final String TOO_MANY = "FIN_FA_TOO_MANY_ASSETS";
    public static final String NOT_LATEST = "FIN_FA_RUN_NOT_LATEST";
    public static final String CHANGED_SINCE = "FIN_FA_RUN_CHANGED_SINCE";

    static final String PERIOD_KEY = "\\d{4}-(0[1-9]|1[0-2])";

    /** The assets a run takes at once. */
    static final int MAX_ASSETS = 10_000;
    static final int BATCH = MAX_ASSETS + 1;
    private static final int LISTED = 20;

    public record RunInput(@NotBlank @Pattern(regexp = PERIOD_KEY) String periodKey) {}

    /**
     * @param posted false when the month had been run already: the run returned is that one
     */
    public record RunOutput(String runId, String runNo, String periodKey, boolean posted, BigDecimal total,
        int assetCount, String glNo) {}

    public record ReverseInput(@NotBlank @Pattern(regexp = PERIOD_KEY) String periodKey,
        @NotBlank @Size(max = 500) String reason) {}

    public record ReverseOutput(String runId, String runNo, String periodKey, String glNo) {}

    static final String OUTPUT = "output";
    static final String RUNS = "runs";
    static final String LATEST = "latest";
    static final String OPENING = "opening";
    static final String PERIODS = "periods";
    static final String ASSETS = "assets";
    static final String CLASSES = "classes";
    static final String CHANGES = "changes";
    static final String USAGE = "usage";
    static final String LINES = "lines";
    static final String DISPOSALS = "disposals";
    static final String POSTINGS = "postings";
    static final String SUB_INPUT = "subInput";
    static final String SUB_OUTPUT = "subOutput";
    static final String REVERSALS = "reversals";
    static final String REVERSED = "reversed";
    static final String RUN_ROW = "runRow";

    public static final ProcessDefinition<RunInput, RunOutput, ProcessContext> RUN_PROCESS =
        ProcessDefinition.define(RUN, 1, RunInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Depreciates the assets in service for a month and posts the entry.")
            .permissions(FinancePermissions.FA_RUN)
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, RunOutput.class))
            .step("Load the month's runs", QueryEntities.of(DepreciationEntities.RUN_DATASET,
                ctx -> FaSupport.in("periodKey", List.of(period(ctx).toString()), 100), RUNS))
            .step("Load the latest run", QueryEntities.of(DepreciationEntities.RUN_DATASET, ctx -> latestPosted(),
                LATEST))
            .step("Load the opening entry", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                ctx -> openingEntry(), OPENING))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(period(ctx).atEndOfMonth()), PERIODS))
            .step("Load the assets in service", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.Eq("active", true),
                    new QueryPredicate.Eq("status", AssetEntities.IN_SERVICE),
                    new QueryPredicate.Lte("inServiceDate", period(ctx).atEndOfMonth()))))
                    .orderBy("assetNo", true).limit(BATCH).build(), ASSETS))
            .step("Load their classes", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET,
                ctx -> FaSupport.in("classCode", list(ctx, ASSETS).stream().map(a -> (Object) a.get("classCode"))
                    .filter(Objects::nonNull).distinct().toList(), 500), CLASSES))
            .step("Load the changes in estimate", QueryEntities.of(DepreciationEntities.CHANGE_DATASET,
                // A month's key is text: the changes in force by the month are picked out in the plan.
                ctx -> FaSupport.in("assetId", ids(list(ctx, ASSETS)), BATCH), CHANGES))
            .step("Load the units used", QueryEntities.of(DepreciationEntities.USAGE_DATASET,
                ctx -> FaSupport.in("periodKey", List.of(period(ctx).toString()), BATCH), USAGE))
            .compute("Depreciate", (metadata, ctx) -> run(ctx))
            // The ledger checks the source document exists: the run is saved before it is booked.
            .step("Save the run", SaveChanges.now())
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Answer", (metadata, ctx) -> {
                if (ctx.contains(SUB_OUTPUT)) {
                    RunOutput run = ctx.get(OUTPUT, RunOutput.class);
                    ctx.put(OUTPUT, new RunOutput(run.runId(), run.runNo(), run.periodKey(), true, run.total(),
                        run.assetCount(), ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
                }
            }));

    public static final ProcessDefinition<ReverseInput, ReverseOutput, ProcessContext> REVERSE_PROCESS =
        ProcessDefinition.define(REVERSE, 1, ReverseInput.class, ReverseOutput.class, ProcessContext.class, pb -> pb
            .description("Takes back the latest depreciation run of an open month.")
            .permissions(FinancePermissions.FA_RUN)
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ReverseOutput.class))
            .step("Load the latest run", QueryEntities.of(DepreciationEntities.RUN_DATASET, ctx -> latestPosted(),
                LATEST))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(reversePeriod(ctx).atEndOfMonth()), PERIODS))
            .step("Load its lines", QueryEntities.of(DepreciationEntities.LINE_DATASET,
                ctx -> FaSupport.in("runId", latestId(ctx), BATCH), LINES))
            .step("Load its assets", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", assetIds(list(ctx, LINES)), BATCH), ASSETS))
            .step("Load changes since", QueryEntities.of(DepreciationEntities.CHANGE_DATASET,
                ctx -> FaSupport.in("assetId", assetIds(list(ctx, LINES)), BATCH), CHANGES))
            .step("Load disposals since", QueryEntities.of(DepreciationEntities.DISPOSAL_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                    new QueryPredicate.In("assetId", assetIds(list(ctx, LINES))),
                    new QueryPredicate.Gte("disposalDate", reversePeriod(ctx).atDay(1))))).limit(1).build(),
                DISPOSALS))
            .step("Load its entry", QueryEntities.of(JournalEntities.POSTING_DATASET, ctx -> {
                EntityInstance run = first(ctx, LATEST);
                List<Object> numbers = run == null ? List.of() : List.of((Object) run.get("runNo"));
                return EntityQuery.builder().where(new QueryPredicate.In("documentNo", numbers)).limit(10).build();
            }, POSTINGS))
            .compute("Check it and put the assets back", (metadata, ctx) -> reverse(ctx))
            .step("Reverse its entry", CallProcess.forEach(SubledgerPosting.REVERSE, 1,
                ctx -> ctx.contains(REVERSALS) ? (List<?>) ctx.get(REVERSALS) : List.of(), REVERSED))
            .compute("Answer", (metadata, ctx) -> {
                if (!ctx.contains(RUN_ROW)) {
                    return;
                }
                EntityInstance run = ctx.get(RUN_ROW, EntityInstance.class);
                @SuppressWarnings("unchecked")
                List<SubledgerPosting.PostOutput> reversed = (List<SubledgerPosting.PostOutput>) ctx.get(REVERSED);
                String glNo = reversed == null || reversed.isEmpty() ? null : reversed.getFirst().glNo();
                ctx.put(OUTPUT, new ReverseOutput(String.valueOf(run.id()), run.get("runNo"), run.get("periodKey"),
                    glNo));
            }));

    /** The run number of a month: {@code DEP-2601}, a second round after a reversal {@code DEP-2601-2}. */
    static String runNo(YearMonth period, int round) {
        String number = String.format("DEP-%02d%02d", period.getYear() % 100, period.getMonthValue());
        return round == 1 ? number : number + "-" + round;
    }

    static void run(ProcessContext ctx) {
        YearMonth period = period(ctx);
        String key = period.toString();
        EntityInstance done = list(ctx, RUNS).stream()
            .filter(r -> DepreciationEntities.POSTED.equals(r.get("status"))).findFirst().orElse(null);
        if (done != null) {
            // Run already: nothing is posted twice (D7).
            ctx.put(OUTPUT, new RunOutput(String.valueOf(done.id()), done.get("runNo"), key, false, done.get("total"),
                done.<BigDecimal>get("assetCount").intValue(), null));
            return;
        }
        EntityInstance opening = first(ctx, OPENING);
        if (opening == null) {
            ctx.reject(new Violation("periodKey", NO_START, "The opening entry is not posted yet: depreciation runs "
                + "from the month after the cutover", Map.of()));
            return;
        }
        YearMonth firstPeriod = YearMonth.from(opening.<LocalDate>get("postingDate")).plusMonths(1);
        EntityInstance latest = first(ctx, LATEST);
        YearMonth expected = latest == null ? firstPeriod : YearMonth.parse(latest.get("periodKey")).plusMonths(1);
        if (!period.equals(expected)) {
            ctx.reject(new Violation("periodKey", OUT_OF_ORDER, "Months are depreciated in order: the next to run is "
                + expected, Map.of("expected", expected.toString())));
            return;
        }
        LocalDate postingDate = period.atEndOfMonth();
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "FA", postingDate, "periodKey");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        List<EntityInstance> assets = list(ctx, ASSETS);
        // Every change of every asset is needed to find the one in force: a list cut short would be wrong.
        if (assets.size() > MAX_ASSETS || list(ctx, CHANGES).size() >= BATCH) {
            ctx.reject(new Violation("periodKey", TOO_MANY, "A run takes up to " + MAX_ASSETS + " assets",
                Map.of("max", MAX_ASSETS)));
            return;
        }
        Map<String, EntityInstance> classes = new HashMap<>();
        list(ctx, CLASSES).forEach(c -> classes.put(c.get("classCode"), c));
        Map<Object, EntityInstance> usage = new HashMap<>();
        list(ctx, USAGE).forEach(u -> usage.put(u.get("assetId"), u));
        List<String> unclassified = new ArrayList<>();
        List<String> noUsage = new ArrayList<>();
        for (EntityInstance asset : assets) {
            if (asset.get("classCode") == null || !classes.containsKey(asset.<String>get("classCode"))
                || asset.get("method") == null) {
                unclassified.add(asset.get("assetNo"));
            } else if (AssetPlans.isByUse(asset) && (asset.get("totalUnits") == null
                || !usage.containsKey(asset.id()))) {
                noUsage.add(asset.get("assetNo"));
            }
        }
        // An asset that cannot be depreciated stops the month: no month is posted short (D4).
        if (!unclassified.isEmpty()) {
            ctx.reject(new Violation("periodKey", UNCLASSIFIED, "Assets without a class or terms are not depreciated: "
                + listed(unclassified), Map.of("assets", listed(unclassified), "count", unclassified.size())));
        }
        if (!noUsage.isEmpty()) {
            ctx.reject(new Violation("periodKey", USAGE_MISSING, "Assets depreciated by use need their units used "
                + "in " + key + " (and over their life): " + listed(noUsage),
                Map.of("assets", listed(noUsage), "count", noUsage.size())));
        }
        if (ctx.hasViolations()) {
            return;
        }
        int round = list(ctx, RUNS).size() + 1;
        String runNo = runNo(period, round);
        List<EntityInstance> changes = list(ctx, CHANGES);
        List<Map<String, Object>> lines = new ArrayList<>();
        // Summed by what the entry posts to: expense, accumulated depreciation, department.
        Map<List<String>, BigDecimal> entry = new TreeMap<>(Comparator.comparing((List<String> k) -> k.get(0))
            .thenComparing(k -> k.get(1)).thenComparing(k -> k.get(2)));
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (EntityInstance asset : assets) {
            EntityInstance assetClass = classes.get(asset.<String>get("classCode"));
            BigDecimal accumulated = AssetPlans.accumulated(asset);
            BigDecimal amount;
            BigDecimal units = null;
            Map<String, Object> values = new LinkedHashMap<>();
            if (AssetPlans.isByUse(asset)) {
                units = usage.get(asset.id()).get("units");
                BigDecimal used = asset.get("unitsUsed") == null ? BigDecimal.ZERO : asset.get("unitsUsed");
                amount = Depreciation.byUse(AssetPlans.depreciable(asset).subtract(accumulated),
                    asset.<BigDecimal>get("totalUnits").subtract(used), units);
                values.put("unitsUsed", used.add(units).setScale(2));
            } else {
                amount = AssetPlans.due(asset, changes, period);
            }
            if (amount.signum() == 0 && units == null) {
                continue;
            }
            BigDecimal after = accumulated.add(amount);
            values.put("accumulated", after);
            values.put("depreciatedThrough", key);
            if (after.compareTo(AssetPlans.depreciable(asset)) >= 0) {
                values.put("status", AssetEntities.FULLY_DEPRECIATED);
            }
            ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), values);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("periodKey", key);
            line.put("assetId", asset.id());
            line.put("assetNo", asset.get("assetNo"));
            line.put("classCode", asset.get("classCode"));
            line.put("expenseAccount", assetClass.get("expenseAccount"));
            line.put("accumulatedAccount", assetClass.get("accumulatedAccount"));
            line.put("department", asset.get("department"));
            line.put("amount", amount);
            line.put("accumulated", after);
            line.put("units", units == null ? null : units.setScale(2));
            line.put("previousThrough", asset.get("depreciatedThrough"));
            lines.add(line);
            total = total.add(amount);
            if (amount.signum() > 0) {
                List<String> account = List.of(assetClass.<String>get("expenseAccount"),
                    assetClass.<String>get("accumulatedAccount"), Objects.toString(asset.get("department"), ""));
                entry.merge(account, amount, BigDecimal::add);
            }
        }
        Map<String, Object> runValues = new LinkedHashMap<>();
        runValues.put("periodKey", key);
        runValues.put("round", BigDecimal.valueOf(round));
        runValues.put("runNo", runNo);
        runValues.put("postingDate", postingDate);
        runValues.put("total", total);
        runValues.put("assetCount", BigDecimal.valueOf(lines.size()));
        runValues.put("status", DepreciationEntities.POSTED);
        runValues.put("actor", ctx.request().actorId());
        runValues.put("runTime", ctx.opTime());
        Object runId = ctx.changes().insert(DepreciationEntities.RUN, runValues);
        for (Map<String, Object> line : lines) {
            line.put("runId", runId);
            ctx.changes().insert(DepreciationEntities.LINE, line);
        }
        ctx.put(OUTPUT, new RunOutput(String.valueOf(runId), runNo, key, true, total, lines.size(), null));
        if (entry.isEmpty()) {
            // Nothing to depreciate this month: the run is recorded, so the next month follows, and nothing posted.
            return;
        }
        List<JournalProcesses.LineInput> posting = new ArrayList<>();
        entry.forEach((account, amount) -> {
            String department = account.get(2).isEmpty() ? null : account.get(2);
            String memo = "Depreciation " + key;
            posting.add(new JournalProcesses.LineInput(account.get(0), amount, null, memo, department, null));
            posting.add(new JournalProcesses.LineInput(account.get(1), null, amount, memo, department, null));
        });
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("FA", postingDate, "Depreciation for " + key, runNo,
            DepreciationEntities.RUN, String.valueOf(runId), List.copyOf(posting), List.of("FA_ACCUM")));
    }

    static void reverse(ProcessContext ctx) {
        ReverseInput input = ctx.get(INPUT, ReverseInput.class);
        YearMonth period = reversePeriod(ctx);
        EntityInstance run = first(ctx, LATEST);
        if (run == null || !period.toString().equals(run.get("periodKey"))) {
            ctx.reject(new Violation("periodKey", NOT_LATEST, "Only the latest run is taken back"
                + (run == null ? "; there is none" : ": " + run.get("periodKey")), Map.of()));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "FA", period.atEndOfMonth(), "periodKey");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        String key = period.toString();
        boolean changed = list(ctx, CHANGES).stream().anyMatch(c -> c.<String>get("fromPeriod").compareTo(key) > 0);
        if (changed || !list(ctx, DISPOSALS).isEmpty()) {
            ctx.reject(new Violation("periodKey", CHANGED_SINCE, "An asset of run " + run.get("runNo") + " has been "
                + "changed or disposed of since: the run stands", Map.of("runNo", (Object) run.get("runNo"))));
            return;
        }
        Map<Object, EntityInstance> assets = new HashMap<>();
        list(ctx, ASSETS).forEach(a -> assets.put(a.id(), a));
        for (EntityInstance line : list(ctx, LINES)) {
            EntityInstance asset = assets.get(line.get("assetId"));
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("accumulated", AssetPlans.accumulated(asset).subtract(line.get("amount")));
            values.put("depreciatedThrough", line.get("previousThrough"));
            values.put("status", AssetEntities.IN_SERVICE);
            if (line.get("units") != null) {
                values.put("unitsUsed", asset.<BigDecimal>get("unitsUsed").subtract(line.get("units")));
            }
            ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), values);
        }
        ctx.changes().update(DepreciationEntities.RUN, run.id(), run.version(), Map.of(
            "status", DepreciationEntities.REVERSED, "reversedBy", ctx.request().actorId(),
            "reason", input.reason().trim()));
        String runId = String.valueOf(run.id());
        List<SubledgerPosting.ReverseInput> reversals = new ArrayList<>();
        list(ctx, POSTINGS).stream()
            .filter(p -> DepreciationEntities.RUN.equals(p.get("sourceEntity")) && runId.equals(p.get("sourceId")))
            .forEach(p -> reversals.add(new SubledgerPosting.ReverseInput("FA",
                String.valueOf((Object) p.get("transactionId")), period.atEndOfMonth(),
                "Reversal of " + run.get("runNo") + ": " + input.reason().trim(), run.get("runNo"),
                DepreciationEntities.RUN, runId)));
        ctx.put(REVERSALS, List.copyOf(reversals));
        ctx.put(RUN_ROW, run);
    }

    static EntityQuery latestPosted() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("status", DepreciationEntities.POSTED))
            .orderBy("periodKey", false).limit(1).build();
    }

    static EntityQuery openingEntry() {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("source", JournalEntities.OPENING),
            new QueryPredicate.Eq("status", "POSTED")))).limit(1).build();
    }

    private static YearMonth period(ProcessContext ctx) {
        return YearMonth.parse(ctx.get(INPUT, RunInput.class).periodKey());
    }

    private static YearMonth reversePeriod(ProcessContext ctx) {
        return YearMonth.parse(ctx.get(INPUT, ReverseInput.class).periodKey());
    }

    private static List<Object> latestId(ProcessContext ctx) {
        EntityInstance run = first(ctx, LATEST);
        return run == null ? List.of() : List.of(run.id());
    }

    private static List<Object> ids(List<EntityInstance> rows) {
        return rows.stream().map(r -> (Object) r.id()).toList();
    }

    private static List<Object> assetIds(List<EntityInstance> lines) {
        return lines.stream().map(l -> (Object) l.get("assetId")).distinct().toList();
    }

    private static String listed(List<String> numbers) {
        return String.join(", ", numbers.subList(0, Math.min(LISTED, numbers.size())))
            + (numbers.size() > LISTED ? " and " + (numbers.size() - LISTED) + " more" : "");
    }

    private DepreciationProcesses() {}
}
