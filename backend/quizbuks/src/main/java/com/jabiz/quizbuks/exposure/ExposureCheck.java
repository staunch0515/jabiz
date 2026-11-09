package com.jabiz.quizbuks.exposure;

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
 * The daily reconciliation of the exposures (F-S13, docs/quizbuks/plans/impl/F-S13.md): {@value #RUN}, run by the job
 * {@value #JOB}, finds the publications whose successes went beyond their purchased exposures by more than the
 * tolerance ({@link ExposureRules#reconciles}), and the ended ones that counted an exposure more than a minute after
 * their end ({@value #DIFFERENCES_TEMPLATE}); with any, it asks headquarters ({@code qb.admin.exposure.monitor}) with a
 * task, one a day. It takes no lock and writes nothing else (what goes beyond the purchase is never charged anyway).
 */
public final class ExposureCheck {

    public static final String RUN = "QB_EXPOSURE_CHECK_RUN";
    public static final String JOB = "qb.exposure-check";
    /** Every day at 19:47 UTC (04:47 in Tokyo), after the region's check; a six-field cron in UTC. */
    public static final String CRON = "0 47 19 * * *";
    public static final String DIFFERENCES_TEMPLATE = "qb.system.exposure-differences";
    public static final String TASK_TYPE = "qb.exposure-check";
    public static final String TASK_TITLE = "task.qb.exposure-check";

    public record RunInput(@NotNull Instant scheduledTime) {}

    /** @param differences how many publications the check found */
    public record RunOutput(int differences) {}

    static final String INPUT = "input";
    static final String DIFFERENCES = "differences";

    private ExposureCheck() {}

    public static ProcessDefinition<RunInput, RunOutput, ProcessContext> run() {
        return ProcessDefinition.define(RUN, 1, RunInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Checks every day that the exposures counted stay within what was bought and stop when a "
                + "publication ends, and asks headquarters when they do not.")
            .permissions(QbPermissions.PUBLICATION_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new RunOutput(differences(ctx).size()))
            .step("Find the differences", RunTemplate.<ProcessContext>of(DIFFERENCES_TEMPLATE, ctx -> Map.of(),
                DIFFERENCES))
            .step("Ask headquarters", CreateTask.<ProcessContext>when(ctx -> !differences(ctx).isEmpty(),
                ctx -> TaskSpec.forPermission(TASK_TYPE, TASK_TITLE, Map.of("count", String.valueOf(differences(ctx).size())),
                        QbPermissions.ADMIN_EXPOSURE_MONITOR)
                    .link("/qb/exposure")
                    .source(source(ctx.get(INPUT, RunInput.class).scheduledTime())))));
    }

    /** One task a day: the day of the run (UTC). */
    public static String source(Instant scheduledTime) {
        return TASK_TYPE + ":" + scheduledTime.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> differences(ProcessContext ctx) {
        Object rows = ctx.contains(DIFFERENCES) ? ctx.get(DIFFERENCES) : null;
        return rows == null ? List.of() : (List<Map<String, Object>>) rows;
    }
}
