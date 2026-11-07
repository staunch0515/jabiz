package com.jabiz.quizbuks.operator;

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
 * The daily check of the operators' structure (F-S1): every sponsor belongs to an operator, every active operator has
 * exactly one own sponsor, and every active operator has at least two members who can review (its own sponsor's
 * publications are reviewed by another member). {@value #RUN}, run by the job {@value #JOB}, reads the template
 * {@value #TEMPLATE} (headquarters' operators page shows the same rows) and, when it finds anything, asks
 * headquarters with a task, one a day. It takes no lock and writes nothing else.
 */
public final class OperatorHealth {

    public static final String RUN = "QB_OPERATOR_CHECK_RUN";
    public static final String JOB = "qb.operator-check";
    public static final String TEMPLATE = "qb.admin.operator-health";
    /** Every day at 19:13 UTC (04:13 in Tokyo), after the reconciliation; a six-field cron in UTC. */
    public static final String CRON = "0 13 19 * * *";
    public static final String TASK_TYPE = "qb.operator-health";
    public static final String TASK_TITLE = "task.qb.operator-health";

    /** No active default operator: nobody can sign up as a sponsor until one is created (F-S1). */
    public static final String NO_DEFAULT_OPERATOR = "NO_DEFAULT_OPERATOR";
    public static final String NO_OPERATOR = "NO_OPERATOR";
    public static final String DIRECT_SPONSOR = "DIRECT_SPONSOR";
    public static final String FEW_REVIEWERS = "FEW_REVIEWERS";

    public record RunInput(@NotNull Instant scheduledTime) {}

    /** @param problems how many rows the check found */
    public record RunOutput(int problems) {}

    static final String INPUT = "input";
    static final String ROWS = "rows";

    public static ProcessDefinition<RunInput, RunOutput, ProcessContext> run() {
        return ProcessDefinition.define(RUN, 1, RunInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Checks the operators' structure and asks headquarters when something is wrong.")
            .permissions(QbPermissions.OPERATORS_READ)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new RunOutput(rows(ctx).size()))
            .step("Check the operators", RunTemplate.<ProcessContext>of(TEMPLATE, ctx -> Map.of(), ROWS))
            .step("Ask headquarters", CreateTask.<ProcessContext>when(ctx -> !rows(ctx).isEmpty(),
                ctx -> TaskSpec.forPermission(TASK_TYPE, TASK_TITLE, Map.of("count",
                        String.valueOf(rows(ctx).size())), QbPermissions.OPERATORS_READ)
                    .link("/qb/operators")
                    .source(source(ctx.get(INPUT, RunInput.class).scheduledTime())))));
    }

    /** One task a day: the day of the run (UTC). */
    public static String source(Instant scheduledTime) {
        return TASK_TYPE + ":" + scheduledTime.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(ProcessContext ctx) {
        Object rows = ctx.get(ROWS);
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }

    private OperatorHealth() {}
}
