package com.jabiz.quizbuks.content;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The quiz content's daily check (F-S6): {@value #CHECK_RUN} (job {@value #CHECK_JOB}) reads {@value #HEALTH_TEMPLATE}
 * and asks the platform with a task when a quiz version's snapshot is near {@link ContentLimits#MAX_SNAPSHOT_BYTES}
 * ({@code SNAPSHOT_NEAR_LIMIT}: over {@value #NEAR_PERCENT}%): a bank that grows further will not save its next version.
 */
public final class ContentHealth {

    public static final String CHECK_RUN = "QB_CONTENT_CHECK_RUN";
    public static final String CHECK_JOB = "qb.content-check";
    public static final String HEALTH_TEMPLATE = "qb.admin.content-health";
    /** Every day at 19:13 UTC (04:13 in Tokyo), before the knowledge maps' check; a six-field cron in UTC. */
    public static final String CHECK_CRON = "0 13 19 * * *";
    public static final String CHECK_TASK_TYPE = "qb.content-health";
    public static final String CHECK_TASK_TITLE = "task.qb.content-health";
    /** A snapshot over this share of the limit is reported. */
    public static final int NEAR_PERCENT = 80;

    public record CheckInput(@NotNull Instant scheduledTime) {}

    /** @param problems how many kinds of problem the check found */
    public record CheckOutput(int problems) {}

    static final String INPUT = "contentHealth.input";
    static final String ROWS = "contentHealth.rows";

    private ContentHealth() {}

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check() {
        return ProcessDefinition.define(CHECK_RUN, 1, CheckInput.class, CheckOutput.class, ProcessContext.class,
            pb -> pb
                .description("Checks the quiz content (versions near the snapshot limit) and asks the platform when "
                    + "something is wrong.")
                .permissions(QbPermissions.ADMIN_QUIZ_READ)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new CheckOutput(rows(ctx).size()))
                .step("Check the content", RunTemplate.<ProcessContext>of(HEALTH_TEMPLATE,
                    ctx -> Map.of("nearBytes", nearBytes()), ROWS))
                .step("Ask the platform", CreateTask.<ProcessContext>when(ctx -> !rows(ctx).isEmpty(),
                    ctx -> TaskSpec.forPermission(CHECK_TASK_TYPE, CHECK_TASK_TITLE, Map.of("count",
                            String.valueOf(rows(ctx).size())), QbPermissions.ADMIN_QUIZ_READ)
                        .source(CHECK_TASK_TYPE + ":" + ctx.get(INPUT, CheckInput.class).scheduledTime()
                            .atZone(ZoneOffset.UTC).toLocalDate()))));
    }

    /** The size in bytes over which a snapshot is near the limit. */
    public static long nearBytes() {
        return (long) ContentLimits.MAX_SNAPSHOT_BYTES * NEAR_PERCENT / 100;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx) {
        Object rows = ctx.get(ROWS);
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }
}
