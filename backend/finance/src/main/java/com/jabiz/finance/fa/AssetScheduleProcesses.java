package com.jabiz.finance.fa;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.Depreciation;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.finance.fa.FaSupport.INPUT;
import static com.jabiz.finance.fa.FaSupport.first;
import static com.jabiz.finance.fa.FaSupport.list;

/**
 * {@code FIN_FA_SCHEDULE_PROJECT}: the months ahead of an asset's depreciation schedule (FIN-FA-009; F6 plan decision
 * D12), from the month the next run takes it in, by the plan the runs follow — changes in estimate and what was brought
 * over included — so the projection and the runs to come agree. It writes nothing. The months taken are the template
 * {@code finance.fa.depreciation_schedule}. An asset by units of production has no months ahead: it depreciates by
 * use.
 */
public final class AssetScheduleProcesses {

    public static final String PROJECT = "FIN_FA_SCHEDULE_PROJECT";

    public record ProjectInput(@NotNull UUID assetId, @Min(1) @Max(1200) Integer months) {}

    public record Month(String periodKey, BigDecimal amount, BigDecimal accumulated, BigDecimal netBookValue) {}

    /**
     * @param byUse true for an asset by units of production, whose months ahead are not known
     */
    public record ProjectOutput(String assetNo, String fromPeriod, boolean byUse, BigDecimal accumulated,
        List<Month> months) {}

    static final String OUTPUT = "output";
    static final String ASSETS = "assets";
    static final String CHANGES = "changes";
    static final String LATEST = "latest";

    public static final ProcessDefinition<ProjectInput, ProjectOutput, ProcessContext> PROJECT_PROCESS =
        ProcessDefinition.define(PROJECT, 1, ProjectInput.class, ProjectOutput.class, ProcessContext.class, pb -> pb
            .description("Projects an asset's depreciation for the months ahead.")
            .permissions(FinancePermissions.FA_READ)
            .actsOn(AssetEntities.ASSET, "assetId", a -> a.whenField("status", AssetEntities.IN_SERVICE))
            .contextFactory(FaSupport::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ProjectOutput.class))
            .step("Load the asset", QueryEntities.of(AssetEntities.ASSET_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, ProjectInput.class).assetId()), 1), ASSETS))
            .step("Load its changes", QueryEntities.of(DepreciationEntities.CHANGE_DATASET,
                ctx -> FaSupport.in("assetId", List.of(ctx.get(INPUT, ProjectInput.class).assetId()), 500), CHANGES))
            .step("Load the latest run", QueryEntities.of(DepreciationEntities.RUN_DATASET,
                ctx -> DepreciationProcesses.latestPosted(), LATEST))
            .compute("Project it", (metadata, ctx) -> project(ctx)));

    static void project(ProcessContext ctx) {
        ProjectInput input = ctx.get(INPUT, ProjectInput.class);
        EntityInstance asset = first(ctx, ASSETS);
        if (asset == null) {
            ctx.reject(new Violation("assetId", AssetProcesses.NOT_FOUND, "There is no asset " + input.assetId(),
                Map.of()));
            return;
        }
        if (asset.get("classCode") == null || asset.get("method") == null) {
            ctx.reject(new Violation("assetId", DepreciationProcesses.UNCLASSIFIED, "Asset " + asset.get("assetNo")
                + " has no class yet", Map.of()));
            return;
        }
        BigDecimal accumulated = AssetPlans.accumulated(asset);
        // The month the next run takes it in: after its own last month, and after the latest run, which a month
        // placed in service before missed.
        YearMonth from = AssetPlans.nextPeriod(asset);
        EntityInstance latest = first(ctx, LATEST);
        if (latest != null && !YearMonth.parse(latest.<String>get("periodKey")).isBefore(from)) {
            from = YearMonth.parse(latest.<String>get("periodKey")).plusMonths(1);
        }
        List<Month> months = new ArrayList<>();
        boolean byUse = AssetPlans.isByUse(asset);
        boolean going = !AssetEntities.DISPOSED.equals(asset.get("status")) && Boolean.TRUE.equals(asset.get("active"));
        if (!byUse && going) {
            Depreciation.Terms terms = AssetPlans.terms(asset);
            Depreciation.Start start = AssetPlans.start(asset, terms, AssetPlans.change(list(ctx, CHANGES), asset.id(),
                from));
            // Each month takes what the plan has by then less what was taken: the first makes good any month not run.
            BigDecimal running = accumulated;
            int limit = input.months() == null ? 12 : input.months();
            List<Depreciation.Month> plan = Depreciation.scheduleFrom(terms, start);
            for (Depreciation.Month month : plan) {
                if (month.period().isBefore(from)) {
                    continue;
                }
                if (months.size() == limit) {
                    break;
                }
                BigDecimal amount = month.accumulated().subtract(running).max(BigDecimal.ZERO);
                running = running.add(amount);
                months.add(new Month(month.period().toString(), amount, running,
                    asset.<BigDecimal>get("cost").subtract(running)));
            }
            // The plan ended before the next run: that run takes all that is left.
            BigDecimal left = plan.isEmpty() ? BigDecimal.ZERO : plan.getLast().accumulated().subtract(running);
            if (months.isEmpty() && left.signum() > 0) {
                running = running.add(left);
                months.add(new Month(from.toString(), left, running, asset.<BigDecimal>get("cost").subtract(running)));
            }
        }
        ctx.put(OUTPUT, new ProjectOutput(asset.get("assetNo"), from.toString(), byUse, accumulated,
            List.copyOf(months)));
    }

    private AssetScheduleProcesses() {}
}
