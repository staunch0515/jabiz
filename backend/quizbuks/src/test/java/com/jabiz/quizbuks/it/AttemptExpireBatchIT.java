package com.jabiz.quizbuks.it;

import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One run of the expiry job finishes at most {@code maxItems} attempts (2 000, {@code PlayLimits.EXPIRE_BATCH}; two
 * here), earliest deadline first, not first started; the rest are left to the next minute (Q5b, D-Q5-9). A class of
 * its own: the cap is a property of the application context.
 */
@TestPropertySource(properties = "quizbuks.attempt-expire.max-items=2")
class AttemptExpireBatchIT extends PlayItSupport {

    @Test
    void aRunFinishesTheEarliestDeadlinesUpToItsCap() {
        String sponsor = newSponsor("s-expire-batch");
        String slow = livePublication(sponsor, "Five minutes", Map.of("regions", List.of("GLOBAL"),
            "timeLimitSec", 300));
        String quick = livePublication(sponsor, "One minute", Map.of("regions", List.of("GLOBAL"),
            "timeLimitSec", 60));
        credit(sponsor, 1_000);
        // Started first but due last: deadlines in another order than the attempts' (time-ordered) keys.
        String first = (String) start(taker(newTaker("t-batch-first", null)), slow).get("attemptId");
        clock.advance(Duration.ofSeconds(10));
        String second = (String) start(taker(newTaker("t-batch-second", null)), quick).get("attemptId");
        clock.advance(Duration.ofSeconds(10));
        String third = (String) start(taker(newTaker("t-batch-third", null)), quick).get("attemptId");
        clock.advance(Duration.ofMinutes(10));

        JobRunner.JobRun run = runExpire(clock.instant());
        assertThat(run.items()).isEqualTo(new JobRunner.ItemCounts(2, 2, 0, 0, 0, 0, true));
        assertThat(attempt(second)).containsEntry("status", "EXPIRED");
        assertThat(attempt(third)).containsEntry("status", "EXPIRED");
        assertThat(attempt(first)).containsEntry("status", "IN_PROGRESS");

        clock.advance(Duration.ofMinutes(1));
        assertThat(runExpire(clock.instant()).items()).isEqualTo(new JobRunner.ItemCounts(1, 1, 0, 0, 0, 0, false));
        assertThat(attempt(first)).containsEntry("status", "EXPIRED");
        assertBooksBalance();
    }
}
