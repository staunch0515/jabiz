package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
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

class JobChecksTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> REGISTERED = ProcessDefinition.single("TICK", 1,
        In.class, In.class, (in, ctx) -> in);
    static final ProcessDefinition<In, In, ProcessContext> UNREGISTERED = ProcessDefinition.single("OTHER", 1,
        In.class, In.class, (in, ctx) -> in);

    private static List<CheckProblem> check(JobDefinition<?>... jobs) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tick", REGISTERED);
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
