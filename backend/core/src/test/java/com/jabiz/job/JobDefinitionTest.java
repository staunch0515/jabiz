package com.jabiz.job;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDefinitionTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> PROCESS = ProcessDefinition.single("JOB_TARGET", 1,
        In.class, In.class, (in, ctx) -> in);

    @Test
    void aJobRunsAProcessOnACron() {
        JobDefinition<In> job = JobDefinition.cron("month-close", "0 5 0 1 * *", ZoneId.of("Asia/Tokyo"), PROCESS,
            In::new);

        assertThat(job.lockAtMostFor()).isEqualTo(JobDefinition.DEFAULT_LOCK_AT_MOST_FOR);
        assertThat(job.input().apply(Instant.EPOCH)).isEqualTo(new In(Instant.EPOCH));
        assertThat(job.lockAtMostFor(Duration.ofMinutes(1)).lockAtMostFor()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void declarationsAreChecked() {
        assertThatThrownBy(() -> JobDefinition.cron("has space", "* * * * * *", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> JobDefinition.cron("x".repeat(65), "* * * * * *", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> JobDefinition.cron("ok", " ", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("cron");
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", ZoneOffset.UTC, PROCESS, In::new)
            .lockAtMostFor(Duration.ZERO)).hasMessageContaining("positive");
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", null, PROCESS, In::new))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", ZoneOffset.UTC, null, In::new))
            .isInstanceOf(NullPointerException.class);
    }
}
