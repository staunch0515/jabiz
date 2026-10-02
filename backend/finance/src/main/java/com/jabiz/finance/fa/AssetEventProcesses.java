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
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.code;
import static com.jabiz.finance.fa.FaSupport.first;
import static com.jabiz.finance.fa.FaSupport.list;
import static com.jabiz.finance.fa.FaSupport.trim;

/**
 * What happens to an asset after it is depreciated (ROADMAP F6b):
 * <ul>
 *   <li>{@code FIN_FA_CHANGE_ESTIMATE} (FIN-FA-006): a new life or salvage value, prospectively from the asset's next
 *       month not depreciated: what it has accumulated stays, the rest is spread afresh (F6 plan decision D6).</li>
 *   <li>{@code FIN_FA_DISPOSE} (FIN-FA-007): a sale, scrapping or write-off. The months before have been run; a
 *       disposal month not yet run takes the share of it the convention gives. Its entry takes the cost and the
 *       accumulated depreciation off and books the proceeds and the gain or loss (D8).</li>
 *   <li>{@code FIN_FA_USAGE_RECORD}: the units an asset by units of production used in a month, correctable until
 *       the month is run for it (D10).</li>
 * </ul>
 */
public final class AssetEventProcesses {

    public static final String CHANGE = "FIN_FA_CHANGE_ESTIMATE";
    public static final String DISPOSE = "FIN_FA_DISPOSE";
    public static final String USAGE = "FIN_FA_USAGE_RECORD";

    public static final String NOT_FOUND = AssetProcesses.NOT_FOUND;
    public static final String WRONG_TERMS = AssetProcesses.WRONG_TERMS;
    public static final String WRONG_STATUS = "FIN_FA_WRONG_STATUS";
    public static final String NO_CHANGE = "FIN_FA_NO_CHANGE";
    public static final String CHANGE_EXISTS = "FIN_FA_CHANGE_EXISTS";
    public static final String MONTHS_NOT_RUN = "FIN_FA_MONTHS_NOT_RUN";
    public static final String DATE_RUN = "FIN_FA_DISPOSAL_DATE_RUN";
    public static final String WRONG_PROCEEDS = "FIN_FA_PROCEEDS";
    public static final String WRONG_OFFSET = AssetProcesses.WRONG_OFFSET;
    public static final String NO_GAIN_LOSS = "FIN_FA_NO_GAIN_LOSS_ACCOUNT";
    public static final String NOT_BY_USE = "FIN_FA_NOT_BY_USE";
    public static final String USAGE_RUN = "FIN_FA_USAGE_RUN";

    public record ChangeInput(@NotNull UUID assetId, @Min(1) @Max(1200) Integer lifeMonths,
        @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal salvage,
        @NotBlank @Size(max = 500) String reason) {}

    /**
     * @param nextAmount what the asset takes in {@code fromPeriod} by its new terms; null by units of production
     */
    public record ChangeOutput(String changeId, String assetNo, String fromPeriod, BigDecimal nextAmount) {}

    public record DisposeInput(@NotNull UUID assetId, @NotNull LocalDate disposalDate,
        @NotBlank @Size(max = 10) String kind,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal proceeds,
        @Size(max = 20) String proceedsAccount, @NotBlank @Size(max = 500) String reason) {}

    /**
     * @param gainLoss proceeds less net book value: a gain positive, a loss negative
     */
    public record DisposeOutput(String disposalId, String assetNo, String documentNo, BigDecimal monthDepreciation,
        BigDecimal accumulated, BigDecimal gainLoss, String glNo) {}

    public record UsageInput(@NotNull UUID assetId,
        @NotBlank @Pattern(regexp = DepreciationProcesses.PERIOD_KEY) String periodKey,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal units) {}

    public record UsageOutput(String usageId, String assetNo, String periodKey, BigDecimal units) {}

    static final String OUTPUT = "output";
    static final String ASSETS = "assets";
    static final String CLASSES = "classes";
    static final String CHANGES = "changes";
    static final String SETTINGS = "settings";
    static final String ACCOUNTS = "accounts";
    static final String LATEST = "latest";
    static final String OPENING = "opening";
    static final String PERIODS = "periods";
    static final String USAGES = "usages";
    static final String SUB_INPUT = "subInput";
    static final String SUB_OUTPUT = "subOutput";

    public static final ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> CHANGE_PROCESS =
        ProcessDefinition.define(CHANGE, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class, pb -> pb
            .description("Changes an asset's useful life or salvage value from its next month on.")
            .permissions(FinancePermissions.FA_MAINTAIN)
            .actsOn(AssetEntities.ASSET, "assetId", a -> a.whenField("status", AssetEntities.IN_SERVICE,
                AssetEntities.FULLY_DEPRECIATED))
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
            .step("Load the asset", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, ChangeInput.class).assetId()), 1), ASSETS))
            .step("Load its changes", QueryEntities.of(DepreciationEntities.CHANGE_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, ChangeInput.class).assetId()), 500), CHANGES))
            .compute("Change it", (metadata, ctx) -> change(ctx)));

    public static final ProcessDefinition<DisposeInput, DisposeOutput, ProcessContext> DISPOSE_PROCESS =
        ProcessDefinition.define(DISPOSE, 1, DisposeInput.class, DisposeOutput.class, ProcessContext.class, pb -> pb
            .description("Sells, scraps or writes off an asset and posts the disposal.")
            .permissions(FinancePermissions.FA_MAINTAIN)
            .actsOn(AssetEntities.ASSET, "assetId", a -> a.whenField("status", AssetEntities.IN_SERVICE,
                AssetEntities.FULLY_DEPRECIATED))
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, DisposeOutput.class))
            .step("Load the asset", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, DisposeInput.class).assetId()), 1), ASSETS))
            .step("Load its class", QueryEntities.of(AssetEntities.ASSET_CLASS_DATASET, ctx -> {
                EntityInstance asset = first(ctx, ASSETS);
                return FaSupport.eq("classCode", asset == null ? null : asset.get("classCode"));
            }, CLASSES))
            .step("Load its changes", QueryEntities.of(DepreciationEntities.CHANGE_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, DisposeInput.class).assetId()), 500), CHANGES))
            .step("Load its units used", QueryEntities.of(DepreciationEntities.USAGE_DATASET,
                ctx -> usageOf(ctx.get(INPUT, DisposeInput.class).assetId(),
                    YearMonth.from(ctx.get(INPUT, DisposeInput.class).disposalDate()).toString()), USAGES))
            .step("Load the settings", QueryEntities.of(AssetEntities.SETTINGS_DATASET,
                ctx -> FaSupport.eq("settingsKey", AssetEntities.SETTINGS_KEY), SETTINGS))
            .step("Load the proceeds account", QueryEntities.of(GlEntities.ACCOUNT_DATASET,
                ctx -> FaSupport.eq("accountCode", trim(ctx.get(INPUT, DisposeInput.class).proceedsAccount())),
                ACCOUNTS))
            .step("Load the latest run", QueryEntities.of(DepreciationEntities.RUN_DATASET,
                ctx -> DepreciationProcesses.latestPosted(), LATEST))
            .step("Load the opening entry", QueryEntities.of(JournalEntities.JOURNAL_DATASET,
                ctx -> DepreciationProcesses.openingEntry(), OPENING))
            .step("Load the period", QueryEntities.of(GlEntities.PERIOD_DATASET,
                ctx -> SubledgerPosting.periodsOn(ctx.get(INPUT, DisposeInput.class).disposalDate()), PERIODS))
            .compute("Dispose of it", (metadata, ctx) -> dispose(ctx))
            // The ledger checks the source document exists: the disposal is saved before it is booked.
            .step("Save the disposal", SaveChanges.now())
            .step("Book it", CallProcess.when(ctx -> ctx.contains(SUB_INPUT), SubledgerPosting.POST, 1,
                ctx -> ctx.get(SUB_INPUT), SUB_OUTPUT))
            .compute("Answer", (metadata, ctx) -> {
                if (ctx.contains(SUB_OUTPUT)) {
                    DisposeOutput out = ctx.get(OUTPUT, DisposeOutput.class);
                    ctx.put(OUTPUT, new DisposeOutput(out.disposalId(), out.assetNo(), out.documentNo(),
                        out.monthDepreciation(), out.accumulated(), out.gainLoss(),
                        ctx.get(SUB_OUTPUT, SubledgerPosting.PostOutput.class).glNo()));
                }
            }));

    public static final ProcessDefinition<UsageInput, UsageOutput, ProcessContext> USAGE_PROCESS =
        ProcessDefinition.define(USAGE, 1, UsageInput.class, UsageOutput.class, ProcessContext.class, pb -> pb
            .description("Records the units an asset depreciated by use used in a month.")
            .permissions(FinancePermissions.FA_RUN)
            .actsOn(AssetEntities.ASSET, "assetId", a -> a.whenField("method", AssetEntities.UOP))
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, UsageOutput.class))
            .step("Load the asset", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, UsageInput.class).assetId()), 1), ASSETS))
            .step("Load the month's units", QueryEntities.of(DepreciationEntities.USAGE_DATASET,
                ctx -> usageOf(ctx.get(INPUT, UsageInput.class).assetId(), ctx.get(INPUT, UsageInput.class)
                    .periodKey()), USAGES))
            .compute("Record them", (metadata, ctx) -> usage(ctx)));

    static void change(ProcessContext ctx) {
        ChangeInput input = ctx.get(INPUT, ChangeInput.class);
        EntityInstance asset = depreciable(ctx, "assetId", input.assetId());
        if (asset == null) {
            return;
        }
        BigDecimal oldSalvage = asset.get("salvage") == null ? BigDecimal.ZERO.setScale(2) : asset.get("salvage");
        int oldLife = asset.<BigDecimal>get("lifeMonths").intValueExact();
        int newLife = input.lifeMonths() == null ? oldLife : input.lifeMonths();
        BigDecimal newSalvage = input.salvage() == null ? oldSalvage : input.salvage().setScale(2);
        if (newLife == oldLife && newSalvage.compareTo(oldSalvage) == 0) {
            ctx.reject(new Violation("lifeMonths", NO_CHANGE, "Give a new useful life or salvage value", Map.of()));
            return;
        }
        boolean byUse = AssetPlans.isByUse(asset);
        if (byUse && newLife != oldLife) {
            ctx.reject(new Violation("lifeMonths", WRONG_TERMS, "An asset depreciated by use has no life in months: "
                + "change its salvage value", Map.of()));
            return;
        }
        YearMonth from = AssetPlans.nextPeriod(asset);
        String fromKey = from.toString();
        if (list(ctx, CHANGES).stream().anyMatch(c -> fromKey.equals(c.get("fromPeriod")))) {
            ctx.reject(new Violation("assetId", CHANGE_EXISTS, "Asset " + asset.get("assetNo") + " was changed from "
                + fromKey + " already: run the month before changing it again", Map.of("fromPeriod", fromKey)));
            return;
        }
        BigDecimal cost = asset.get("cost");
        BigDecimal accumulated = AssetPlans.accumulated(asset);
        if (newSalvage.compareTo(cost) > 0 || cost.subtract(newSalvage).compareTo(accumulated) < 0) {
            ctx.reject(new Violation("salvage", WRONG_TERMS, "A salvage value leaves at least what asset "
                + asset.get("assetNo") + " has accumulated, " + accumulated.toPlainString(),
                Map.of("accumulated", accumulated)));
            return;
        }
        Depreciation.Terms terms = null;
        if (!byUse) {
            terms = new Depreciation.Terms(cost, newSalvage, asset.get("inServiceDate"), newLife,
                Depreciation.Method.valueOf(asset.get("method")),
                Depreciation.Convention.valueOf(asset.get("convention")));
            if (from.isAfter(terms.lastMonth())) {
                ctx.reject(new Violation("lifeMonths", WRONG_TERMS, "A life of " + newLife + " months ends before "
                    + fromKey + ", the month the change takes effect", Map.of("fromPeriod", fromKey)));
                return;
            }
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("assetId", asset.id());
        change.put("assetNo", asset.get("assetNo"));
        change.put("fromPeriod", fromKey);
        change.put("oldLifeMonths", BigDecimal.valueOf(oldLife));
        change.put("newLifeMonths", BigDecimal.valueOf(newLife));
        change.put("oldSalvage", oldSalvage);
        change.put("newSalvage", newSalvage);
        change.put("accumulatedAt", accumulated);
        change.put("reason", input.reason().trim());
        change.put("actor", ctx.request().actorId());
        change.put("changeTime", ctx.opTime());
        Object id = ctx.changes().insert(DepreciationEntities.CHANGE, change);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("lifeMonths", BigDecimal.valueOf(newLife));
        values.put("salvage", newSalvage);
        values.put("status", cost.subtract(newSalvage).compareTo(accumulated) > 0 ? AssetEntities.IN_SERVICE
            : AssetEntities.FULLY_DEPRECIATED);
        ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), values);
        BigDecimal next = null;
        if (terms != null) {
            Depreciation.Start start = Depreciation.restart(from, accumulated);
            next = AssetPlans.plannedThrough(terms, start, from).subtract(accumulated);
        }
        ctx.put(OUTPUT, new ChangeOutput(String.valueOf(id), asset.get("assetNo"), fromKey, next));
    }

    static void dispose(ProcessContext ctx) {
        DisposeInput input = ctx.get(INPUT, DisposeInput.class);
        EntityInstance asset = depreciable(ctx, "assetId", input.assetId());
        if (asset == null) {
            return;
        }
        String assetNo = asset.get("assetNo");
        EntityInstance assetClass = first(ctx, CLASSES);
        String kind = code(input.kind());
        if (!DepreciationEntities.DISPOSAL_KIND_VALUES.contains(kind)) {
            ctx.reject(new Violation("kind", WRONG_TERMS, "A disposal is one of "
                + DepreciationEntities.DISPOSAL_KIND_VALUES, Map.of()));
            return;
        }
        LocalDate date = input.disposalDate();
        if (date.isBefore(asset.get("inServiceDate"))) {
            ctx.reject(new Violation("disposalDate", WRONG_TERMS, "Asset " + assetNo + " is disposed of on or after "
                + "it was placed in service", Map.of()));
            return;
        }
        BigDecimal proceeds = input.proceeds().setScale(2);
        if (DepreciationEntities.SALE.equals(kind) && proceeds.signum() == 0
            || DepreciationEntities.WRITE_OFF.equals(kind) && proceeds.signum() > 0) {
            ctx.reject(new Violation("proceeds", WRONG_PROCEEDS, "A sale has proceeds; a write-off has none",
                Map.of("kind", kind)));
            return;
        }
        EntityInstance proceedsAccount = first(ctx, ACCOUNTS);
        if (proceeds.signum() > 0 && (proceedsAccount == null || proceedsAccount.get("controlClass") != null
            && !"BANK".equals(proceedsAccount.get("controlClass")))) {
            ctx.reject(new Violation("proceedsAccount", WRONG_OFFSET, "Proceeds go to a bank account or an account "
                + "that is no control account", Map.of("accountCode", String.valueOf(trim(input.proceedsAccount())))));
            return;
        }
        var closed = SubledgerPosting.periodRefusal(list(ctx, PERIODS), "FA", date, "disposalDate");
        if (closed.isPresent()) {
            ctx.reject(closed.get());
            return;
        }
        YearMonth month = YearMonth.from(date);
        String through = asset.get("depreciatedThrough");
        if (through != null && YearMonth.parse(through).isAfter(month)) {
            ctx.reject(new Violation("disposalDate", DATE_RUN, "Asset " + assetNo + " was depreciated through "
                + through + ": it is disposed of in that month or later", Map.of("depreciatedThrough", through)));
            return;
        }
        // The months before are run, for the disposal to take what is left of the plan exactly (D8).
        EntityInstance latest = first(ctx, LATEST);
        EntityInstance opening = first(ctx, OPENING);
        YearMonth firstPeriod = opening == null ? null
            : YearMonth.from(opening.<LocalDate>get("postingDate")).plusMonths(1);
        YearMonth ran = latest == null ? null : YearMonth.parse(latest.get("periodKey"));
        // The asset's own months: one placed in service in a month run already was not depreciated by that run.
        boolean monthRun = through != null && !YearMonth.parse(through).isBefore(month);
        boolean before = ran != null && !ran.isBefore(month.minusMonths(1))
            || ran == null && firstPeriod != null && !month.isAfter(firstPeriod);
        if (!before) {
            YearMonth next = ran == null ? firstPeriod : ran.plusMonths(1);
            ctx.reject(new Violation("disposalDate", MONTHS_NOT_RUN, "Run depreciation through the month before "
                + month + " first; the next month to run is " + next,
                Map.of("next", String.valueOf(next))));
            return;
        }
        BigDecimal accumulated = AssetPlans.accumulated(asset);
        BigDecimal monthAmount = BigDecimal.ZERO.setScale(2);
        BigDecimal units = null;
        boolean left = AssetPlans.depreciable(asset).compareTo(accumulated) > 0;
        if (!monthRun && left) {
            if (AssetPlans.isByUse(asset)) {
                EntityInstance usage = first(ctx, USAGES);
                if (usage == null || asset.get("totalUnits") == null) {
                    // As a run would: the month's units first, or its depreciation would be lost.
                    ctx.reject(new Violation("disposalDate", DepreciationProcesses.USAGE_MISSING, "Record the units "
                        + "asset " + assetNo + " used in " + month + " first", Map.of("assets", assetNo, "count", 1)));
                    return;
                }
                units = usage.get("units");
                BigDecimal used = asset.get("unitsUsed") == null ? BigDecimal.ZERO : asset.get("unitsUsed");
                monthAmount = Depreciation.byUse(AssetPlans.depreciable(asset).subtract(accumulated),
                    asset.<BigDecimal>get("totalUnits").subtract(used), units);
            } else {
                monthAmount = AssetPlans.disposalMonth(asset, list(ctx, CHANGES), month);
            }
        }
        BigDecimal after = accumulated.add(monthAmount);
        BigDecimal cost = asset.get("cost");
        BigDecimal gainLoss = proceeds.subtract(cost.subtract(after));
        EntityInstance settings = first(ctx, SETTINGS);
        String gainLossAccount = settings == null ? null : settings.get("gainLossAccount");
        if (gainLoss.signum() != 0 && gainLossAccount == null) {
            ctx.reject(new Violation("assetId", NO_GAIN_LOSS, "The disposal has a gain or loss and the asset settings "
                + "name no account for it", Map.of()));
            return;
        }
        String documentNo = "DSP-" + assetNo;
        Map<String, Object> disposal = new LinkedHashMap<>();
        disposal.put("assetId", asset.id());
        disposal.put("assetNo", assetNo);
        disposal.put("disposalDate", date);
        disposal.put("kind", kind);
        disposal.put("proceeds", proceeds);
        disposal.put("proceedsAccount", proceeds.signum() > 0 ? proceedsAccount.get("accountCode") : null);
        disposal.put("cost", cost);
        disposal.put("accumulated", after);
        disposal.put("monthDepreciation", monthAmount);
        disposal.put("gainLoss", gainLoss);
        disposal.put("reason", input.reason().trim());
        disposal.put("documentNo", documentNo);
        disposal.put("actor", ctx.request().actorId());
        disposal.put("disposalTime", ctx.opTime());
        Object id = ctx.changes().insert(DepreciationEntities.DISPOSAL, disposal);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", AssetEntities.DISPOSED);
        values.put("accumulated", after);
        if (monthAmount.signum() > 0) {
            values.put("depreciatedThrough", month.toString());
        }
        if (units != null) {
            BigDecimal used = asset.get("unitsUsed") == null ? BigDecimal.ZERO : asset.get("unitsUsed");
            values.put("unitsUsed", used.add(units).setScale(2));
        }
        ctx.changes().update(AssetEntities.ASSET, asset.id(), asset.version(), values);
        ctx.put(OUTPUT, new DisposeOutput(String.valueOf(id), assetNo, documentNo, monthAmount, after, gainLoss,
            null));
        String department = asset.get("department");
        String memo = cut(assetNo + " " + kind.toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ": "
            + input.reason().trim(), 200);
        List<JournalProcesses.LineInput> lines = new ArrayList<>();
        if (monthAmount.signum() > 0) {
            lines.add(new JournalProcesses.LineInput(assetClass.get("expenseAccount"), monthAmount, null, memo,
                department, null));
            lines.add(new JournalProcesses.LineInput(assetClass.get("accumulatedAccount"), null, monthAmount, memo,
                department, null));
        }
        if (after.signum() > 0) {
            lines.add(new JournalProcesses.LineInput(assetClass.get("accumulatedAccount"), after, null, memo,
                department, null));
        }
        if (proceeds.signum() > 0) {
            lines.add(new JournalProcesses.LineInput(proceedsAccount.get("accountCode"), proceeds, null, memo, null,
                null));
        }
        lines.add(new JournalProcesses.LineInput(asset.get("costAccount"), null, cost, memo, department, null));
        if (gainLoss.signum() > 0) {
            lines.add(new JournalProcesses.LineInput(gainLossAccount, null, gainLoss, memo, department, null));
        } else if (gainLoss.signum() < 0) {
            lines.add(new JournalProcesses.LineInput(gainLossAccount, gainLoss.negate(), null, memo, department,
                null));
        }
        List<String> controls = new ArrayList<>(List.of("FA_COST", "FA_ACCUM"));
        if (proceeds.signum() > 0 && "BANK".equals(proceedsAccount.get("controlClass"))) {
            controls.add("BANK");
        }
        ctx.put(SUB_INPUT, new SubledgerPosting.PostInput("FA", date, cut("Disposal of " + assetNo + " ("
            + kind.toLowerCase(java.util.Locale.ROOT) + "): " + input.reason().trim(), 500), documentNo,
            DepreciationEntities.DISPOSAL, String.valueOf(id), List.copyOf(lines), List.copyOf(controls)));
    }

    static void usage(ProcessContext ctx) {
        UsageInput input = ctx.get(INPUT, UsageInput.class);
        EntityInstance asset = depreciable(ctx, "assetId", input.assetId());
        if (asset == null) {
            return;
        }
        if (!AssetPlans.isByUse(asset)) {
            ctx.reject(new Violation("assetId", NOT_BY_USE, "Asset " + asset.get("assetNo") + " is not depreciated "
                + "by use", Map.of()));
            return;
        }
        YearMonth period = YearMonth.parse(input.periodKey());
        String through = asset.get("depreciatedThrough");
        if (through != null && !YearMonth.parse(through).isBefore(period)) {
            ctx.reject(new Violation("periodKey", USAGE_RUN, "Asset " + asset.get("assetNo") + " was depreciated "
                + "through " + through + " already", Map.of("depreciatedThrough", through)));
            return;
        }
        if (period.isBefore(YearMonth.from(asset.<LocalDate>get("inServiceDate")))) {
            ctx.reject(new Violation("periodKey", WRONG_TERMS, "Asset " + asset.get("assetNo") + " was not in service "
                + "in " + period, Map.of()));
            return;
        }
        BigDecimal units = input.units().setScale(2);
        EntityInstance found = first(ctx, USAGES);
        Object id;
        if (found == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("assetId", asset.id());
            values.put("assetNo", asset.get("assetNo"));
            values.put("periodKey", period.toString());
            values.put("units", units);
            values.put("actor", ctx.request().actorId());
            values.put("recordTime", ctx.opTime());
            id = ctx.changes().insert(DepreciationEntities.USAGE, values);
        } else {
            id = found.id();
            if (found.<BigDecimal>get("units").compareTo(units) != 0) {
                ctx.changes().update(DepreciationEntities.USAGE, found.id(), found.version(), Map.of(
                    "units", units, "actor", ctx.request().actorId(), "recordTime", ctx.opTime()));
            }
        }
        ctx.put(OUTPUT, new UsageOutput(String.valueOf(id), asset.get("assetNo"), period.toString(), units));
    }

    /** The asset, if it is one that depreciates: found, active, classified with terms and not disposed of. */
    private static EntityInstance depreciable(ProcessContext ctx, String field, UUID assetId) {
        EntityInstance asset = first(ctx, ASSETS);
        if (asset == null) {
            ctx.reject(new Violation(field, NOT_FOUND, "There is no asset " + assetId, Map.of()));
            return null;
        }
        if (!Boolean.TRUE.equals(asset.get("active")) || AssetEntities.DISPOSED.equals(asset.get("status"))
            || asset.get("status") == null) {
            ctx.reject(new Violation(field, WRONG_STATUS, "Asset " + asset.get("assetNo") + " is "
                + (AssetEntities.DISPOSED.equals(asset.get("status")) ? "disposed of" : "not in service"),
                Map.of("status", String.valueOf((Object) asset.get("status")))));
            return null;
        }
        if (asset.get("classCode") == null || asset.get("method") == null) {
            ctx.reject(new Violation(field, DepreciationProcesses.UNCLASSIFIED, "Asset " + asset.get("assetNo")
                + " has no class yet: give it one first", Map.of()));
            return null;
        }
        return asset;
    }

    private static EntityQuery usageOf(UUID assetId, String periodKey) {
        return EntityQuery.builder().where(new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("assetId", assetId),
            new QueryPredicate.Eq("periodKey", periodKey)))).limit(1).build();
    }

    private static String cut(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    private AssetEventProcesses() {}
}
