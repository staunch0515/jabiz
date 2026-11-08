package com.jabiz.quizbuks.tree;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Keeping the knowledge maps up (F-S2b2): the daily check {@value #CHECK_RUN} (job {@value #CHECK_JOB}) reads
 * {@value #HEALTH_TEMPLATE} and asks the platform with a task when a pass lit nothing, a lit record names no node or
 * versions wait too long for review; the daily recheck (job {@value #RECHECK_JOB}, after the check) evaluates each pass
 * that lit nothing again with {@code QB_TREE_EVALUATE}; the reminder {@value #REMIND} (job {@value #REMIND_JOB}) asks
 * the reviewers once more about a version waiting longer than {@code qb.tree.review.remind-after} days, with a task
 * closed when the review ends.
 */
public final class TreeUpkeep {

    public static final String CHECK_RUN = "QB_TREE_CHECK_RUN";
    public static final String CHECK_JOB = "qb.tree-check";
    public static final String HEALTH_TEMPLATE = "qb.admin.tree-health";
    /** Every day at 19:23 UTC (04:23 in Tokyo), after the operators' check; a six-field cron in UTC. */
    public static final String CHECK_CRON = "0 23 19 * * *";
    public static final String CHECK_TASK_TYPE = "qb.tree-health";
    public static final String CHECK_TASK_TITLE = "task.qb.tree-health";

    public static final String RECHECK_JOB = "qb.tree-recheck";
    public static final String RECHECK_TEMPLATE = "qb.system.tree-recheck-due";
    /** Every day at 19:33 UTC, after the check has counted what it finds. */
    public static final String RECHECK_CRON = "0 33 19 * * *";

    public static final String REMIND = "QB_TREE_REVIEW_REMIND";
    public static final String REMIND_JOB = "qb.tree-review-remind";
    public static final String REMIND_TEMPLATE = "qb.system.tree-review-remind-due";
    /** Every day at 00:43 UTC (09:43 in Tokyo): the reviewers' working morning. */
    public static final String REMIND_CRON = "0 43 0 * * *";
    public static final String REMIND_TASK_TYPE = "qb.tree-review";
    public static final String REMIND_TASK_TITLE = "task.qb.tree-review";

    public record CheckInput(@NotNull Instant scheduledTime) {}

    /** @param problems how many kinds of problem the check found */
    public record CheckOutput(int problems) {}

    public record RemindInput(@NotBlank String versionId) {}

    /** @param reminded whether a reminder was sent (no longer in review, or reminded already: not) */
    public record RemindOutput(boolean reminded) {}

    static final String INPUT = "upkeep.input";
    static final String ROWS = "upkeep.rows";
    static final String FOUND = "upkeep.found";
    static final String VERSION = "upkeep.version";
    static final String TREE = "upkeep.tree";
    static final String ASSIGNMENT = "upkeep.assignment";
    static final String TASK = "upkeep.task";

    private TreeUpkeep() {}

    // ---- the daily check -------------------------------------------------------------------------------------------

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK_RUN, 1, CheckInput.class, CheckOutput.class, ProcessContext.class,
            pb -> pb
                .description("Checks the knowledge maps' lighting and review and asks the platform when something is "
                    + "wrong.")
                .permissions(QbPermissions.TREES_READ)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new CheckOutput(rows(ctx).size()))
                .step("Check the maps", RunTemplate.<ProcessContext>of(HEALTH_TEMPLATE, ctx -> Map.of("at",
                    ctx.get(INPUT, CheckInput.class).scheduledTime()), ROWS))
                .step("Ask the platform", CreateTask.<ProcessContext>when(ctx -> !rows(ctx).isEmpty(),
                    ctx -> TaskSpec.forPermission(CHECK_TASK_TYPE, CHECK_TASK_TITLE, Map.of("count",
                            String.valueOf(rows(ctx).size())), QbPermissions.TREES_READ)
                        .source(CHECK_TASK_TYPE + ":" + ctx.get(INPUT, CheckInput.class).scheduledTime()
                            .atZone(ZoneOffset.UTC).toLocalDate()))));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx) {
        Object rows = ctx.get(ROWS);
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }

    // ---- the reminder ----------------------------------------------------------------------------------------------

    /** The reminder's task of a version: closed when its review ends (decided, withdrawn, retired). */
    public static String reminderSource(Object versionId) {
        return REMIND_TASK_TYPE + ":" + versionId;
    }

    public static ProcessDefinition<RemindInput, RemindOutput, ProcessContext> remind() {
        return ProcessDefinition.define(REMIND, 1, RemindInput.class, RemindOutput.class, ProcessContext.class,
            pb -> pb
                .description("Reminds the reviewers, once, of a knowledge map's version waiting too long for review.")
                .permissions(QbPermissions.TREE_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new RemindOutput(ctx.contains(TASK)))
                .step("Find the version", QueryEntities.<ProcessContext>of(QbTrees.defaultDataset(QbTrees.VERSION),
                    ctx -> byId("versionId", ctx.get(INPUT, RemindInput.class).versionId()), FOUND))
                .step("Lock the map", HoldLock.<ProcessContext>exclusive(ctx -> first(ctx, FOUND) == null ? null
                    : QbTrees.lockName(first(ctx, FOUND).get("treeId"))))
                .step("Read the version and the map", QueryEntities.<ProcessContext>all(
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.VERSION), ctx -> byId("versionId",
                        first(ctx, FOUND) == null ? null : first(ctx, FOUND).id()), VERSION),
                    QueryEntities.query(QbTrees.defaultDataset(QbTrees.TREE), ctx -> byId("treeId",
                        first(ctx, FOUND) == null ? null : first(ctx, FOUND).get("treeId")), TREE)))
                .step("Load the Sponsor's operator", QueryEntities.<ProcessContext>of(
                    QbOperators.defaultDataset(QbOperators.ASSIGNMENT), ctx -> {
                        EntityInstance tree = first(ctx, TREE);
                        return EntityQuery.builder().where(new QueryPredicate.In("userId", tree != null
                            && QbTrees.SPONSOR.equals(tree.get("ownerKind"))
                            ? List.of(String.valueOf((Object) tree.get("ownerId"))) : List.of())).limit(1).build();
                    }, ASSIGNMENT))
                .compute("Remind once", (metadata, ctx) -> remindOnce(ctx))
                .step("Ask the reviewers again", CreateTask.<ProcessContext>when(ctx -> ctx.contains(TASK),
                    ctx -> ctx.get(TASK, TaskSpec.class))));
    }

    static void remindOnce(ProcessContext ctx) {
        EntityInstance version = first(ctx, VERSION);
        EntityInstance tree = first(ctx, TREE);
        if (version == null || tree == null || !QbTrees.REVIEW.equals(version.get("status"))
            || version.get("remindedAt") != null) {
            return;
        }
        ctx.changes().update(QbTrees.VERSION, version.id(), version.version(), Map.of("remindedAt", ctx.opTime()));
        String ownerKind = String.valueOf((Object) tree.get("ownerKind"));
        String reference = TreeApprovals.reference(version);
        TaskSpec task;
        if (QbTrees.PLATFORM.equals(ownerKind)) {
            task = TaskSpec.forPermission(REMIND_TASK_TYPE, REMIND_TASK_TITLE, Map.of("map", reference),
                QbPermissions.TREE_PLATFORM_WRITE);
        } else {
            EntityInstance assignment = first(ctx, ASSIGNMENT);
            String operator = QbTrees.OPERATOR.equals(ownerKind) ? String.valueOf((Object) tree.get("ownerId"))
                : assignment == null ? null : String.valueOf((Object) assignment.get("operatorId"));
            task = TaskSpec.forPermission(REMIND_TASK_TYPE, REMIND_TASK_TITLE, Map.of("map", reference),
                QbPermissions.REVIEW_TREE);
            if (operator != null) {
                task = task.scope(QbOperators.SCOPE, operator);
            }
        }
        ctx.put(TASK, task.source(reminderSource(version.id())));
    }

    private static EntityQuery byId(String field, Object id) {
        return EntityQuery.builder().where(new QueryPredicate.In(field, id == null ? List.of()
            : List.of(String.valueOf(id)))).limit(1).build();
    }

    @SuppressWarnings("unchecked")
    private static EntityInstance first(ProcessContext ctx, String key) {
        List<EntityInstance> found = ctx.contains(key) ? (List<EntityInstance>) ctx.get(key) : null;
        return found == null || found.isEmpty() ? null : found.get(0);
    }
}
