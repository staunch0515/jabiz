package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JobChecksTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> REGISTERED = ProcessDefinition.single("TICK", 1,
        In.class, In.class, (in, ctx) -> in).asInternal();
    static final ProcessDefinition<In, In, ProcessContext> UNREGISTERED = ProcessDefinition.single("OTHER", 1,
        In.class, In.class, (in, ctx) -> in).asInternal();
    static final ProcessDefinition<In, In, ProcessContext> OPEN = ProcessDefinition.single("BY_HAND", 1,
        In.class, In.class, (in, ctx) -> in);
    static final ProcessDefinition<In, In, ProcessContext> AT_ENTRY = ProcessDefinition.single("AT_ENTRY", 1,
        In.class, In.class, (in, ctx) -> in).withAccess(ProcessAccess.ENTRY_ONLY);
    static final ProcessDefinition<In, In, ProcessContext> UNDER = ProcessDefinition.single("UNDER", 1,
        In.class, In.class, (in, ctx) -> in).withAccess(ProcessAccess.SUB_PROCESS_ONLY);

    private static List<CheckProblem> check(JobDefinition<?>... jobs) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tick", REGISTERED);
        beans.addBean("byHand", OPEN);
        beans.addBean("atEntry", AT_ENTRY);
        beans.addBean("under", UNDER);
        for (int i = 0; i < jobs.length; i++) {
            beans.addBean("job" + i, jobs[i]);
        }
        ProcessRegistry processes = new ProcessRegistry(
            beans.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
        JobRegistry registry = new JobRegistry(beans.getBeanProvider(ResolvableType.forClass(JobDefinition.class)));
        return new JobChecks(registry, processes).check();
    }

    @Test
    void wellDeclaredJobsPass() {
        assertThat(check(JobDefinition.cron("tick", "0 5 0 1 * *", ZoneOffset.UTC, REGISTERED, In::new))).isEmpty();
    }

    @Test
    void everyProblemIsReported() {
        List<CheckProblem> problems = check(
            JobDefinition.cron("tick", "0 5 0 1 * *", ZoneOffset.UTC, REGISTERED, In::new),
            JobDefinition.cron("tick", "every day", ZoneOffset.UTC, REGISTERED, In::new),
            JobDefinition.cron("other", "0 0 * * * *", ZoneOffset.UTC, UNREGISTERED, In::new));

        assertThat(problems).extracting(CheckProblem::format).containsExactly(
            "JOB | job tick | is declared more than once",
            "JOB | job tick | cron expression 'every day' is not valid (six fields: second minute hour day month "
                + "weekday)",
            "JOB | job other | runs process OTHER v1, which is not registered as a bean");
    }

    /** The system runs a job's process on its own: internal(), or open when also run by hand (decision D41). */
    @Test
    void theProcessIsInternalOrDeclaredAsAlsoRunByHand() {
        List<CheckProblem> problems = check(
            JobDefinition.cron("open", "0 0 * * * *", ZoneOffset.UTC, OPEN, In::new),
            JobDefinition.cron("manual", "0 0 * * * *", ZoneOffset.UTC, OPEN, In::new).manualToo(),
            JobDefinition.cron("entry", "0 0 * * * *", ZoneOffset.UTC, AT_ENTRY, In::new),
            JobDefinition.cron("under", "0 0 * * * *", ZoneOffset.UTC, UNDER, In::new));

        assertThat(problems).extracting(CheckProblem::severity, CheckProblem::location)
            .containsExactly(
                tuple(CheckProblem.Severity.WARNING, "job open"),
                tuple(CheckProblem.Severity.ERROR, "job entry"),
                tuple(CheckProblem.Severity.ERROR, "job under"));
        assertThat(problems.getFirst().message()).isEqualTo("runs process BY_HAND v1, which is open: declare it "
            + "internal() (decision D41), or manualToo() if it is also run by hand");
    }

    @Test
    void nextTimesFollowTheCronInItsZone() {
        JobDefinition<In> monthly = JobDefinition.cron("close", "0 5 0 1 * *", ZoneId.of("Asia/Tokyo"), REGISTERED,
            In::new);

        assertThat(JobScheduler.nextTime(monthly, Instant.parse("2026-01-31T09:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-31T15:05:00Z"));
        assertThat(JobRunner.idempotencyKey("close", Instant.parse("2026-01-31T15:05:00Z")))
            .isEqualTo("job:close:2026-01-31T15:05:00Z");
    }
}
