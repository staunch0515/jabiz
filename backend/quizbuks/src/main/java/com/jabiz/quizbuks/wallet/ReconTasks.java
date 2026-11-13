package com.jabiz.quizbuks.wallet;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.task.CloseTasks;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskEntities;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b): the reconciliation's tasks after each run. An item for attention
 * asks its check's owner once per check and object (the source key {@code recon-attn:<check>:<object>}, no date): while
 * its task is open nothing more is asked; once the item is no longer found its task is done. A difference's task (one
 * a day, {@code recon:<day>}, from the run) is done once a run has none and cancelled when a later day's replaces it.
 * Two daily jobs ({@value #ATTENTION_JOB}, {@value #CLOSE_JOB}), an item a transaction ({@code CreateTask} does not
 * look for a task of the same source, so the items' templates leave out those already open, G14). Both do nothing
 * when the reconciliation has no run within the day before (qb-reconcile-missing reports that).
 */
public final class ReconTasks {

    public static final String ATTENTION_ITEM = "QB_RECON_ATTENTION_TASK";
    public static final String CLOSE_ITEM = "QB_RECON_TASK_CLOSE";
    public static final String ATTENTION_JOB = "qb.recon-attention";
    public static final String CLOSE_JOB = "qb.recon-tasks-close";
    /** 18:52 UTC (03:52 in Tokyo), after the reconciliation's 18:37; a six-field cron in UTC. */
    public static final String CRON = "0 52 18 * * *";
    public static final String ATTENTION_DUE_TEMPLATE = "qb.system.recon-attention-due";
    public static final String CLOSE_DUE_TEMPLATE = "qb.system.recon-tasks-to-close";
    public static final String ATTENTION_TASK = "qb.recon-attention";
    /** The title's message: this followed by {@code .<check>}, one for each check for attention. */
    public static final String ATTENTION_TITLE = "task.qb.recon-attention";
    /** The administration's reconciliation page, where the task leads. */
    public static final String RECONCILIATION_PAGE = "/qb/finance/reconciliation";
    /** At most this many items a run of either job; the rest the next day. */
    public static final int MAX_ITEMS = 200;
    /** The source key's prefix of a task for attention. */
    public static final String ATTENTION_SOURCE = "recon-attn:";

    /**
     * An item for attention of the latest run.
     *
     * @param sourceKey {@code recon-attn:<check>:<object>}
     */
    public record AttentionInput(@NotBlank @Size(max = 200) String sourceKey, @NotBlank @Size(max = 30) String check,
        @Size(max = 128) String partyId, @Size(max = 100) String reference, BigDecimal expected, BigDecimal actual) {}

    /** @param outcome {@code DONE} or {@code CANCELLED} */
    public record CloseInput(@NotBlank @Size(max = 200) String sourceKey,
        @NotBlank @Pattern(regexp = "DONE|CANCELLED") String outcome) {}

    /** @param asked whether a task was made (or closed) */
    public record ItemOutput(String sourceKey, boolean asked) {}

    static final String INPUT = "input";
    static final String OPEN = "open";
    /** A source key no task has: the close step that does not apply closes nothing. */
    static final String NONE = "recon-none:";

    /** The source key of an item for attention: its check and its reference, else its party, else '-'. */
    public static String sourceKey(String check, String partyId, String reference) {
        String object = reference != null ? reference : partyId != null ? partyId : "-";
        return ATTENTION_SOURCE + check + ":" + object;
    }

    public static ProcessDefinition<AttentionInput, ItemOutput, ProcessContext> attentionItem() {
        return ProcessDefinition.define(ATTENTION_ITEM, 1, AttentionInput.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Asks the owner of a reconciliation check for attention about one object, once while "
                    + "its task is open.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ItemOutput(input(ctx).sourceKey(), !open(ctx)))
                // Read again in the item's own transaction: a run of the job retried does not ask twice.
                .step("Find its open task", QueryEntities.<ProcessContext>of(TaskEntities.TASK_DATASET,
                    ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("sourceKey", input(ctx).sourceKey()),
                        new QueryPredicate.Eq("status", TaskEntities.OPEN)))).limit(1).build(), OPEN))
                .step("Ask the check's owner", CreateTask.<ProcessContext>when(ctx -> !open(ctx),
                    ReconTasks::attentionTask)));
    }

    static TaskSpec attentionTask(ProcessContext ctx) {
        AttentionInput input = input(ctx);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("check", input.check());
        params.put("object", input.reference() != null ? input.reference()
            : input.partyId() != null ? input.partyId() : "-");
        params.put("expected", plain(input.expected()));
        params.put("actual", plain(input.actual()));
        // F-M14 review L6: the title names the check in words (one message per check for attention), and the task
        // leads to the reconciliation page.
        return TaskSpec.forPermission(ATTENTION_TASK, ATTENTION_TITLE + "." + input.check(), params,
                ReconChecks.owner(input.check()))
            .link(ReconChecks.link(input.check())).source(input.sourceKey());
    }

    public static ProcessDefinition<CloseInput, ItemOutput, ProcessContext> closeItem() {
        return ProcessDefinition.define(CLOSE_ITEM, 1, CloseInput.class, ItemOutput.class, ProcessContext.class,
            pb -> pb
                .description("Closes the reconciliation's open tasks of one source the latest run settled.")
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ItemOutput(close(ctx).sourceKey(), true))
                .step("Close them as done", CloseTasks.<ProcessContext>done(
                    ctx -> TaskEntities.DONE.equals(close(ctx).outcome()) ? close(ctx).sourceKey() : NONE))
                .step("Close them as no longer needed", CloseTasks.<ProcessContext>cancel(
                    ctx -> TaskEntities.CANCELLED.equals(close(ctx).outcome()) ? close(ctx).sourceKey() : NONE)));
    }

    private static boolean open(ProcessContext ctx) {
        List<?> rows = (List<?>) ctx.get(OPEN);
        return rows != null && !rows.isEmpty();
    }

    private static AttentionInput input(ProcessContext ctx) {
        return ctx.get(INPUT, AttentionInput.class);
    }

    private static CloseInput close(ProcessContext ctx) {
        return ctx.get(INPUT, CloseInput.class);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private ReconTasks() {}
}
