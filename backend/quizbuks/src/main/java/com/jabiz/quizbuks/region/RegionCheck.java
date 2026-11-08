package com.jabiz.quizbuks.region;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.geo.ClientCountries;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * The daily check of the address database (F-S11; platform decision D74): what the platform's startup check
 * ({@code GEOIP}) and the deployment's ({@code DeploySafetyCheck}) say at startup, again every day, since a database
 * goes stale while the application runs (the platform reads a new file without a restart, but nobody brings it unless
 * reminded). {@value #RUN}, run by the job {@value #JOB}, asks headquarters ({@code qb.admin.region.monitor}) with a task,
 * one a day, when the database is missing, cannot be read, has no ranges or is older than {@code jabiz.geoip.max-age}.
 * It takes no lock and writes nothing else.
 */
public final class RegionCheck {

    public static final String RUN = "QB_REGION_CHECK_RUN";
    public static final String JOB = "qb.region-check";
    /** Every day at 19:23 UTC (04:23 in Tokyo), after the operators' check; a six-field cron in UTC. */
    public static final String CRON = "0 23 19 * * *";
    public static final String TASK_TYPE = "qb.region-check";
    public static final String TASK_TITLE = "task.qb.region-check";

    public record RunInput(@NotNull Instant scheduledTime) {}

    /** @param problems how many problems the check found */
    public record RunOutput(int problems) {}

    static final String INPUT = "input";
    static final String PROBLEMS = "problems";

    private RegionCheck() {
    }

    public static ProcessDefinition<RunInput, RunOutput, ProcessContext> run(ClientCountries countries) {
        return ProcessDefinition.define(RUN, 1, RunInput.class, RunOutput.class, ProcessContext.class, pb -> pb
            .description("Checks the address database and asks headquarters when it is missing or old.")
            .permissions(QbPermissions.ADMIN_REGION_MONITOR)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new RunOutput(problems(ctx).size()))
            .compute("Check the address database", (metadata, ctx) -> ctx.put(PROBLEMS, problems(countries)))
            .step("Ask headquarters", CreateTask.<ProcessContext>when(ctx -> !problems(ctx).isEmpty(),
                ctx -> TaskSpec.forPermission(TASK_TYPE, TASK_TITLE, Map.of("problem", problems(ctx).getFirst()),
                        QbPermissions.ADMIN_REGION_MONITOR)
                    .link("/qb/region")
                    .source(source(ctx.get(INPUT, RunInput.class).scheduledTime())))));
    }

    /** The database's problems, as messages; no database configured is one. */
    static List<String> problems(ClientCountries countries) {
        if (!countries.configured()) {
            return List.of("no address database is configured (jabiz.geoip.database)");
        }
        return countries.check().stream().map(CheckProblem::message).toList();
    }

    /** One task a day: the day of the run (UTC). */
    public static String source(Instant scheduledTime) {
        return TASK_TYPE + ":" + scheduledTime.atZone(ZoneOffset.UTC).toLocalDate();
    }

    @SuppressWarnings("unchecked")
    static List<String> problems(ProcessContext ctx) {
        Object problems = ctx.get(PROBLEMS);
        return problems == null ? List.of() : (List<String>) problems;
    }
}
