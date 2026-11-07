package com.jabiz.runtime.job;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which past scheduled times may run again by hand, and which are offered (decision D77). */
class JobRunProcessesTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> TICK = ProcessDefinition.single("TICK", 1,
        In.class, In.class, (in, ctx) -> in).asInternal();
    static final Instant NOW = Instant.parse("2026-02-10T09:30:00Z");
    static final Duration MAX_AGE = Duration.ofDays(90);

    static JobDefinition<In> job(String cron, ZoneId zone) {
        return JobDefinition.cron("it.tick", cron, zone, TICK, In::new);
    }

    private static String code(JobDefinition<?> job, Instant time) {
        return JobRunProcesses.problem(job, time, NOW, MAX_AGE).map(Violation::ruleCode).orElse(null);
    }

    @Test
    void onlyPastTimesOfTheScheduleWithinTheMaxAgeMayRunAgain() {
        // 03:10 in Tokyo is 18:10 UTC of the day before.
        JobDefinition<In> daily = job("0 10 3 * * *", ZoneId.of("Asia/Tokyo"));
        assertThat(code(daily, Instant.parse("2026-02-09T18:10:00Z"))).isNull();
        assertThat(code(daily, Instant.parse("2026-02-09T03:10:00Z")))
            .isEqualTo(PlatformErrorCodes.JOB_RUN_NOT_SCHEDULED);
        assertThat(code(daily, Instant.parse("2026-02-09T18:10:00.001Z")))
            .isEqualTo(PlatformErrorCodes.JOB_RUN_NOT_SCHEDULED);
        assertThat(code(daily, Instant.parse("2026-02-10T18:10:00Z"))).isEqualTo(PlatformErrorCodes.JOB_RUN_NOT_DUE);
        assertThat(code(daily, NOW.minus(Duration.ofDays(91)).atZone(ZoneId.of("Asia/Tokyo")).withHour(3)
            .withMinute(10).withSecond(0).toInstant())).isEqualTo(PlatformErrorCodes.JOB_RUN_TOO_OLD);
        // A time of now itself is due.
        JobDefinition<In> halfHourly = job("0 0/30 * * * *", ZoneOffset.UTC);
        assertThat(code(halfHourly, NOW)).isNull();
        assertThat(JobRunProcesses.problem(halfHourly, Instant.parse("2026-02-10T09:15:00Z"), NOW, MAX_AGE))
            .hasValueSatisfying(v -> assertThat(v.params()).containsEntry("job", "it.tick")
                .containsEntry("scheduledTime", "2026-02-10T09:15:00Z"));
    }

    @Test
    void theLatestTimesAndThoseOfRecentRunsAreOfferedNewestFirst() {
        JobDefinition<In> everyMinute = job("0 * * * * *", ZoneOffset.UTC);
        List<Instant> offered = JobRunProcesses.offeredTimes(everyMinute, NOW, MAX_AGE,
            List.of(Instant.parse("2026-02-10T08:00:00Z"), Instant.parse("2025-11-01T00:00:00Z")));
        assertThat(offered).hasSize(JobRunProcesses.OFFERED_TIMES + 1);
        assertThat(offered.getFirst()).isEqualTo(NOW);
        assertThat(offered.get(JobRunProcesses.OFFERED_TIMES - 1)).isEqualTo(NOW.minus(Duration.ofMinutes(19)));
        // A run's time older than the max age is not offered.
        assertThat(offered.getLast()).isEqualTo(Instant.parse("2026-02-10T08:00:00Z"));

        // A run's time the schedule no longer names (its cron changed) is not offered: it would be refused.
        assertThat(JobRunProcesses.offeredTimes(everyMinute, NOW, MAX_AGE,
            List.of(Instant.parse("2026-02-10T08:00:30Z")))).doesNotContain(Instant.parse("2026-02-10T08:00:30Z"));

        // A schedule of a few times a year: the search widens up to the max age.
        JobDefinition<In> monthly = job("0 0 0 1 * *", ZoneOffset.UTC);
        assertThat(JobRunProcesses.offeredTimes(monthly, NOW, MAX_AGE, List.of())).containsExactly(
            Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2025-12-01T00:00:00Z"));
    }
}
