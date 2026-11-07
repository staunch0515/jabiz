package com.jabiz.quizbuks.usage;

import com.jabiz.param.ParamValues;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.PublishEvent;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One item of the daily job {@value QbUsage#JOB} (F-C2): one sponsor's snapshot of a day. Reads every meter with one
 * template ({@value QbUsage#MEASURE_TEMPLATE}), writes them to the day's one row (a run again of the same day overwrites
 * it), deletes the sponsor's rows past {@code qb.usage.retention-days}, and announces
 * {@value QbUsage#FREE_EXCEEDED_EVENT} when the storage just went above {@code qb.usage.storage.free-bytes} (the
 * inbox's consumer makes the one message of that crossing). {@code internal()}; takes no business lock and writes no
 * other table: nothing a sponsor does waits for it, and nothing it does is refused because of it.
 */
public final class UsageSnapshot {

    public static final String ITEM = "QB_USAGE_SNAPSHOT_ITEM";

    /** The most old rows one run deletes (one row a day: a sponsor's rows of almost three years). */
    static final int MAX_OLD_ROWS = 1_000;

    /**
     * @param sponsorId the sponsor (an item of {@value QbUsage#SPONSORS_TEMPLATE})
     * @param day       the day of the snapshot, in the deployment's zone
     * @param at        the run's scheduled time: the parameters are read as of it
     */
    public record ItemInput(@NotBlank @Size(max = 128) String sponsorId, @NotNull LocalDate day, @NotNull Instant at) {}

    /**
     * @param written whether the day's row was inserted or changed
     * @param deleted the old rows deleted
     * @param crossed whether the storage just went above the free amount
     */
    public record ItemOutput(boolean written, int deleted, boolean crossed) {}

    static final String INPUT = "input";
    static final String PARAMS = "params";
    static final String MEASURED = "measured";
    static final String TODAY = "today";
    static final String PREVIOUS = "previous";
    static final String OLD = "old";
    static final String PLAN = "plan";

    private UsageSnapshot() {}

    /** The item's input from the job: the sponsor of the row, the day of the scheduled time in {@code zone}. */
    public static ItemInput input(Instant scheduledTime, Map<String, Object> row, ZoneId zone) {
        return new ItemInput(String.valueOf(column(row, "sponsorId")), UsageRules.dayOf(scheduledTime, zone),
            scheduledTime);
    }

    public static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> item() {
        return ProcessDefinition.define(ITEM, 1, ItemInput.class, ItemOutput.class, ProcessContext.class, pb -> pb
            .description("Records one sponsor's usage of a day (one item of the daily snapshot job).")
            .permissions(QbPermissions.USAGE_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> {
                UsageRules.Plan plan = ctx.contains(PLAN) ? ctx.get(PLAN, UsageRules.Plan.class) : null;
                return plan == null ? new ItemOutput(false, 0, false)
                    : new ItemOutput(plan.write(), plan.deletes().size(), plan.crossed());
            })
            // Optional: before the setup (and before the free amount's controlled change is published) a snapshot is
            // still taken, only without a notice.
            .step("Load the parameters", LoadParams.<ProcessContext>optional(ctx -> input(ctx).at(), PARAMS,
                QbParams.USAGE_FREE_BYTES, QbParams.USAGE_RETENTION_DAYS))
            .step("Measure the sponsor", RunTemplate.<ProcessContext>of(QbUsage.MEASURE_TEMPLATE, ctx -> Map.of(
                "sponsorId", input(ctx).sponsorId(), "month", UsageRules.month(input(ctx).day())), MEASURED))
            .step("Load the day, the day before and the old rows", QueryEntities.<ProcessContext>all(
                QueryEntities.query(QbUsage.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId()),
                        new QueryPredicate.Eq("day", input(ctx).day()))))
                    .limit(1).build(), TODAY),
                QueryEntities.query(QbUsage.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId()),
                        new QueryPredicate.Lt("day", input(ctx).day()))))
                    .orderBy("day", false).limit(1).build(), PREVIOUS),
                QueryEntities.query(QbUsage.DEFAULT_DATASET, ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.And(List.of(new QueryPredicate.Eq("sponsorId", input(ctx).sponsorId()),
                        new QueryPredicate.Lt("day", UsageRules.firstKept(input(ctx).day(), retentionDays(ctx))))))
                    .orderBy("day", true).limit(MAX_OLD_ROWS).build(), OLD)))
            .compute("Write the day", (metadata, ctx) -> write(ctx))
            .step("Announce a crossing", PublishEvent.<ProcessContext>when(
                ctx -> ctx.get(PLAN, UsageRules.Plan.class).crossed(), QbUsage.FREE_EXCEEDED_EVENT, ctx -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("sponsorId", input(ctx).sponsorId());
                    payload.put("day", input(ctx).day().toString());
                    return payload;
                })));
    }

    static void write(ProcessContext ctx) {
        ItemInput input = input(ctx);
        Map<String, BigDecimal> measured = new LinkedHashMap<>();
        for (Map<String, Object> row : rows(ctx, MEASURED)) {
            Object meter = column(row, "meter");
            Object value = column(row, "value");
            if (meter != null) {
                measured.put(String.valueOf(meter), value == null ? BigDecimal.ZERO : decimal(value));
            }
        }
        EntityInstance row = instances(ctx, TODAY).stream().findFirst().orElse(null);
        UsageRules.Row today = row == null ? null
            : new UsageRules.Row(row.id(), row.version(), UsageRules.meters(row.get("meters")));
        List<EntityInstance> previous = instances(ctx, PREVIOUS);
        BigDecimal previousStorage = previous.isEmpty() ? null
            : UsageRules.meters(previous.getFirst().get("meters")).get(UsageMeters.STORAGE_BYTES);
        List<UsageRules.Delete> old = instances(ctx, OLD).stream()
            .map(r -> new UsageRules.Delete(r.id(), r.version())).toList();
        UsageRules.Plan plan = UsageRules.plan(measured, today, previousStorage, freeBytes(ctx), old);
        ctx.put(PLAN, plan);
        if (plan.write() && today == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("sponsorId", input.sponsorId());
            values.put("day", input.day());
            values.put("meters", UsageRules.json(plan.meters()));
            values.put("recordedAt", ctx.opTime());
            ctx.changes().insert(QbUsage.DAY, values);
        } else if (plan.write()) {
            ctx.changes().update(QbUsage.DAY, today.id(), today.version(), Map.of("meters",
                UsageRules.json(plan.meters()), "recordedAt", ctx.opTime()));
        }
        for (UsageRules.Delete delete : plan.deletes()) {
            ctx.changes().delete(QbUsage.DAY, delete.id(), delete.version());
        }
    }

    /** The free amount in effect; null when not set or not a whole number of bytes (then nothing is noticed). */
    static BigDecimal freeBytes(ProcessContext ctx) {
        BigDecimal value = ctx.get(PARAMS, ParamValues.class).find(QbParams.USAGE_FREE_BYTES, BigDecimal.class)
            .orElse(null);
        return value == null || value.signum() < 0 ? null : value;
    }

    /** The retention in effect: the declared value when not set; at least one day. */
    static int retentionDays(ProcessContext ctx) {
        BigDecimal value = ctx.get(PARAMS, ParamValues.class).find(QbParams.USAGE_RETENTION_DAYS, BigDecimal.class)
            .orElse(new BigDecimal(QbParams.defaultOf(QbParams.USAGE_RETENTION_DAYS)));
        return (int) Math.clamp(value.longValue(), 1, 36_500);
    }

    private static ItemInput input(ProcessContext ctx) {
        return ctx.get(INPUT, ItemInput.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> instances(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        return rows == null ? List.of() : (List<EntityInstance>) rows;
    }

    /** A template row's value by its result name (PostgreSQL folds the names to lower case). */
    static Object column(Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return row.get(name);
        }
        return row.get(name.toLowerCase(Locale.ROOT));
    }

    static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }
}
