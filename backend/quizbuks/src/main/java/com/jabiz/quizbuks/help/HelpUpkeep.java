package com.jabiz.quizbuks.help;

import com.jabiz.param.ParamValues;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadParams;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI help's daily upkeep (F-C10), each an {@code internal()} item of a daily job ({@code JobDefinition.forEach},
 * platform decision D42), its conversation read again under its lock:
 * <ul>
 *   <li>{@value #RETENTION}: a conversation past {@code qb.help.retention-days} days since its last activity is deleted
 *       with its messages ({@value #DELETE_BATCH} messages a run; a longer one is finished the next day), its tasks
 *       cancelled;</li>
 *   <li>{@value #AUTO_CLOSE}: a conversation handed to a person whose reply the user left unanswered for
 *       {@code qb.help.auto-close-days} days closes, and its tasks;</li>
 *   <li>{@value #REMIND}: a conversation waiting for a person longer than {@code qb.help.escalation-remind-days} days
 *       gives its handlers a reminder (once per wait: the reminder's source names when the wait began);</li>
 *   <li>{@value #USAGE_CHECK}: the month's share of everybody's tokens on the gauge ({@link HelpUsageGauge}), for the
 *       alert when the help nears its monthly tokens.</li>
 * </ul>
 */
public final class HelpUpkeep {

    public static final String RETENTION = "QB_HELP_RETENTION";
    public static final String AUTO_CLOSE = "QB_HELP_AUTO_CLOSE";
    public static final String REMIND = "QB_HELP_REMIND";
    public static final String USAGE_CHECK = "QB_HELP_USAGE_CHECK";

    public static final String RETENTION_JOB = "qb.help-retention";
    public static final String AUTO_CLOSE_JOB = "qb.help-auto-close";
    public static final String REMIND_JOB = "qb.help-remind";
    public static final String USAGE_JOB = "qb.help-usage";
    /** 19:13 UTC (04:13 in Tokyo): the night's deletions. */
    public static final String RETENTION_CRON = "0 13 19 * * *";
    /** 19:23 UTC (04:23 in Tokyo). */
    public static final String AUTO_CLOSE_CRON = "0 23 19 * * *";
    /** 00:37 UTC (09:37 in Tokyo): the handlers' working morning. */
    public static final String REMIND_CRON = "0 37 0 * * *";
    /** 23:53 UTC (08:53 in Tokyo). */
    public static final String USAGE_CRON = "0 53 23 * * *";

    /** The messages one retention run deletes of a conversation. */
    public static final int DELETE_BATCH = 500;

    static final String INPUT = "input";
    static final String THREAD = "thread";
    static final String MESSAGES = "messages";
    static final String DUE = "due";
    static final String PARAMS = "params";
    static final String EVERYBODY = "everybody";
    static final String CANCEL = "cancel";

    public record ItemInput(@NotBlank @Size(max = QbHelp.ID_LENGTH) String threadId) {}

    /** @param done whether the item was still due and done */
    public record ItemOutput(boolean done) {}

    public record UsageCheckInput(@NotNull Instant scheduledTime) {}

    /** @param percent the share of everybody's monthly tokens used */
    public record UsageCheckOutput(long percent) {}

    private HelpUpkeep() {}

    // ---- retention -------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> retention() {
        return item(RETENTION, "Deletes an AI help conversation past its retention, with its messages.",
            QbHelp.RETENTION_DUE, pb -> pb
                .step("Load its messages", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.MESSAGE),
                    ctx -> EntityQuery.builder().where(new QueryPredicate.In("threadId",
                        due(ctx) == null ? List.of() : List.of(threadId(ctx)))).limit(DELETE_BATCH).build(), MESSAGES))
                .compute("Delete it", (metadata, ctx) -> {
                    if (due(ctx) == null) {
                        return;
                    }
                    List<EntityInstance> messages = HelpProcesses.list(ctx, MESSAGES);
                    for (EntityInstance message : messages) {
                        ctx.changes().delete(QbHelp.MESSAGE, message.id(), message.version());
                    }
                    if (messages.size() < DELETE_BATCH) {
                        EntityInstance thread = thread(ctx);
                        ctx.changes().delete(QbHelp.THREAD, thread.id(), thread.version());
                        ctx.put(CANCEL, waitingSource(thread));
                    }
                })
                .step("Cancel its task", CloseTasks.<ProcessContext>cancel(ctx -> ctx.contains(CANCEL)
                    ? QbHelp.taskSource(threadId(ctx)) : "qb.help:none"))
                .step("Cancel its reminder", CloseTasks.<ProcessContext>cancel(ctx -> ctx.contains(CANCEL)
                    ? (String) ctx.get(CANCEL) : "qb.help-remind:none")));
    }

    // ---- auto-close ------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> autoClose() {
        return item(AUTO_CLOSE, "Closes an AI help conversation whose person's reply was left unanswered.",
            QbHelp.AUTO_CLOSE_DUE, pb -> {
                pb.compute("Close it", (metadata, ctx) -> {
                    if (due(ctx) == null) {
                        return;
                    }
                    EntityInstance thread = thread(ctx);
                    Map<String, Object> values = new LinkedHashMap<>();
                    HelpProcesses.closing(ctx, thread, values, QbHelp.CLOSED_AUTO, ctx.opTime());
                    ctx.changes().update(QbHelp.THREAD, thread.id(), thread.version(), values);
                });
                HelpProcesses.closeTasks(pb);
            });
    }

    // ---- remind ----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> remind() {
        return item(REMIND, "Reminds the handlers of an AI help conversation waiting too long for a person.",
            QbHelp.REMIND_DUE, pb -> pb
                .step("Remind its handlers", CreateTask.<ProcessContext>when(ctx -> due(ctx) != null, ctx -> {
                    EntityInstance thread = thread(ctx);
                    Map<String, Object> due = due(ctx);
                    TaskSpec task = TaskSpec.forPermission(QbHelp.REMIND_TASK, "task.qb.help.remind",
                            Map.of("days", String.valueOf(due.get("days"))), QbPermissions.ADMIN_HELP)
                        .about(QbHelp.THREAD, String.valueOf(thread.id()))
                        .link("/qb/help/" + thread.id())
                        .source(waitingSource(thread));
                    Object operator = thread.get("operatorId");
                    return operator == null ? task : task.scope(com.jabiz.quizbuks.operator.QbOperators.SCOPE,
                        String.valueOf(operator));
                })));
    }

    // ---- usage -----------------------------------------------------------------------------------------------------

    public static ProcessDefinition<UsageCheckInput, UsageCheckOutput, ProcessContext> usageCheck(ZoneId zone) {
        return ProcessDefinition.define(USAGE_CHECK, 1, UsageCheckInput.class, UsageCheckOutput.class,
            ProcessContext.class, pb -> pb
                .description("Shows the AI help's share of its monthly tokens and dollars on the gauges, and asks "
                    + "finance to look when a month's estimated AI dollars have reached the alert.")
                .permissions(QbPermissions.HELP_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new UsageCheckOutput(ctx.contains(HelpUsageGauge.PERCENT)
                    ? ((Number) ctx.get(HelpUsageGauge.PERCENT)).longValue() : 0))
                .step("Load the monthly tokens", LoadParams.<ProcessContext>optional(ProcessContext::opTime, PARAMS,
                    QbParams.HELP_GLOBAL_MONTHLY_TOKENS, QbParams.HELP_CACHE_READ_WEIGHT))
                .step("Load everybody's usage", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.USAGE),
                    ctx -> HelpProcesses.usageOf(QbHelp.EVERYBODY, AiLimits.month(ctx.opTime(), zone)), EVERYBODY))
                // F-M10: this month's dollars (the gauge) and last month's (a month that crossed the alert after the
                // month's last check still gets its task on the first check of the next one).
                .step("Read this month's dollars", RunTemplate.<ProcessContext>of(HelpCosts.TEMPLATE,
                    ctx -> HelpCosts.params(AiLimits.month(ctx.opTime(), zone), zone), COST_NOW))
                .step("Read last month's dollars", RunTemplate.<ProcessContext>of(HelpCosts.TEMPLATE,
                    ctx -> HelpCosts.params(HelpCosts.before(AiLimits.month(ctx.opTime(), zone)), zone), COST_BEFORE))
                .compute("Work out the shares", (metadata, ctx) -> {
                    EntityInstance row = HelpProcesses.first(ctx, EVERYBODY);
                    HelpSettings settings = HelpSettings.of(ctx.contains(PARAMS) ? ctx.get(PARAMS, ParamValues.class)
                        : null);
                    long used = row == null ? 0 : settings.counted(HelpProcesses.number(row.get("inputTokens")),
                        HelpProcesses.number(row.get("outputTokens")), HelpProcesses.number(row.get("cacheReadTokens")));
                    ctx.put(HelpUsageGauge.PERCENT, HelpOutcomes.percent(used, settings.globalMonthlyTokens()));
                    ctx.put(HelpUsageGauge.COST_PERCENT, costNow(ctx).percent());
                    ctx.put(HelpUsageGauge.MONTH, AiLimits.month(ctx.opTime(), zone));
                })
                .step("Ask finance about this month", CreateTask.<ProcessContext>when(
                    ctx -> due(costNow(ctx)), ctx -> costTask(AiLimits.month(ctx.opTime(), zone), costNow(ctx))))
                .step("Ask finance about last month", CreateTask.<ProcessContext>when(
                    ctx -> due(costBefore(ctx)),
                    ctx -> costTask(HelpCosts.before(AiLimits.month(ctx.opTime(), zone)), costBefore(ctx))))
                .afterCommit("Show the usage on the gauge", HelpUsageGauge.class, NoMetadata.INSTANCE));
    }

    static final String COST_NOW = "costNow";
    static final String COST_BEFORE = "costBefore";

    private static HelpCosts.Month costNow(ProcessContext ctx) {
        return HelpCosts.Month.of(first(ctx, COST_NOW));
    }

    private static HelpCosts.Month costBefore(ProcessContext ctx) {
        return HelpCosts.Month.of(first(ctx, COST_BEFORE));
    }

    /** Whether a month's task is due: the alert reached and no task of the month yet (open or closed by a person). */
    private static boolean due(HelpCosts.Month month) {
        return month.reached() && !month.taskExists();
    }

    /**
     * The task of a month whose estimated AI dollars reached the alert (F-M10): for finance ({@code qb.reconcile};
     * the super administrator holds it too), once a month, and closed only by a person who has looked at the usage.
     */
    private static TaskSpec costTask(String month, HelpCosts.Month cost) {
        Map<String, String> params = Map.of("month", month,
            "usd", cost.total().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            "alert", cost.alert().toPlainString());
        return TaskSpec.forPermission(HelpCosts.TASK, "task." + HelpCosts.TASK, params, QbPermissions.RECONCILE)
            .link("/qb/ai")
            .source(HelpCosts.source(month));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        List<Map<String, Object>> list = rows == null ? List.of() : (List<Map<String, Object>>) rows;
        return list.isEmpty() ? null : list.getFirst();
    }

    // ---- shared ----------------------------------------------------------------------------------------------------

    /**
     * An item: the conversation's lock, the conversation and whether it is still due ({@code dueTemplate} read again
     * for it), then {@code rest}.
     */
    private static ProcessDefinition<ItemInput, ItemOutput, ProcessContext> item(String name, String description,
        String dueTemplate, java.util.function.Consumer<com.jabiz.process.ProcessDefinitionBuilder<ItemInput,
            ItemOutput, ProcessContext>> rest) {
        return ProcessDefinition.define(name, 1, ItemInput.class, ItemOutput.class, ProcessContext.class, pb -> {
            pb.description(description)
                .permissions(QbPermissions.HELP_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ItemOutput(due(ctx) != null))
                .step("Take the conversation's turn", HoldLock.<ProcessContext>exclusive(
                    ctx -> QbHelp.threadLock(threadId(ctx))))
                .step("Load the conversation", QueryEntities.<ProcessContext>of(QbHelp.defaultDataset(QbHelp.THREAD),
                    ctx -> HelpProcesses.byId("threadId", threadId(ctx)), THREAD))
                .step("Read whether it is due", RunTemplate.<ProcessContext>when(ctx -> thread(ctx) != null,
                    dueTemplate, ctx -> Map.of("threadId", threadId(ctx), "at", ctx.opTime()), DUE));
            rest.accept(pb);
        });
    }

    private static String threadId(ProcessContext ctx) {
        return ctx.get(INPUT, ItemInput.class).threadId();
    }

    private static EntityInstance thread(ProcessContext ctx) {
        return HelpProcesses.first(ctx, THREAD);
    }

    /** The due row, or none. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> due(ProcessContext ctx) {
        Object rows = ctx.contains(DUE) ? ctx.get(DUE) : null;
        List<Map<String, Object>> list = rows == null ? List.of() : (List<Map<String, Object>>) rows;
        return list.isEmpty() ? null : list.getFirst();
    }

    /** The source of the reminder of what the conversation waits for now. */
    static String waitingSource(EntityInstance thread) {
        Instant since = HelpProcesses.instant(thread.get("waitingSince"));
        return QbHelp.remindSource(thread.id(), since == null ? 0 : since.getEpochSecond());
    }
}
