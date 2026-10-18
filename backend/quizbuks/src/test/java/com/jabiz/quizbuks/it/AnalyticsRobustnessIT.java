package com.jabiz.quizbuks.it;

import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analytics job item by item (Q9e1 review M3, L3), with a chunk per publication and a recount bound of two
 * completions: a chunk or a recount that fails (a poison pill: a constraint that refuses its write) leaves the other
 * items, which commit, and is done by a later run once the cause is gone; a publication above the recount's bound is
 * finalized from the totals read so far; one whose attempts never all finish is finalized a day after its end.
 */
@TestPropertySource(properties = {"quizbuks.analytics.chunk-size=1", "quizbuks.analytics.max-recount=2"})
class AnalyticsRobustnessIT extends AnalyticsItSupport {

    String run() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    Instant refreshedAt() {
        return instant(query("SELECT refreshed_at FROM qb_stats_run WHERE job = 'qb.analytics'").getFirst()
            .get("refreshed_at"));
    }

    /** Runs until the run succeeds whole (a failed item may wait one run in the platform's backoff). */
    void runUntilClean(Instant from) {
        Instant at = from;
        for (int i = 0; i < 4; i++) {
            JobRunner.JobRun run = analyticsRun(at);
            if (run.outcome() == JobRunner.Outcome.SUCCEEDED && run.items().deferred() == 0) {
                return;
            }
            at = at.plus(Duration.ofMinutes(10));
        }
        throw new AssertionError("qb.analytics did not run clean from " + from);
    }

    @Test
    void aFailingChunkLeavesTheOthersAndIsDoneLater() {
        String run = run();
        String sponsor = newSponsor("s-an-poison-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String poisoned = analysedPublication(sponsor, "Poisoned " + run);
        String healthy = analysedPublication(sponsor, "Healthy " + run);
        complete("t-an-poison-1-" + run, poisoned, true, true);
        complete("t-an-poison-2-" + run, healthy, true, true);
        execute("ALTER TABLE qb_analytics_hour ADD CONSTRAINT q9e1_poison CHECK (publication_id <> '" + poisoned
            + "'::uuid)");
        try {
            JobRunner.JobRun failing = analyticsRun(h.plus(Duration.ofMinutes(10)));
            assertThat(failing.outcome()).as(failing.toString()).isEqualTo(JobRunner.Outcome.PARTIAL);
            assertThat(failing.items().failed()).isEqualTo(1);
            // The others committed, the run's record among them.
            assertThat(cursor(healthy)).isNotNull();
            assertThat(hours(healthy)).singleElement()
                .satisfies(row -> assertThat(count(row.get("completions"))).isEqualTo(1));
            assertThat(cursor(poisoned)).isNull();
            assertThat(refreshedAt()).isEqualTo(h.plus(Duration.ofMinutes(10)));
        } finally {
            execute("ALTER TABLE qb_analytics_hour DROP CONSTRAINT q9e1_poison");
        }
        // The cause gone, a later run does it: nothing was lost.
        runUntilClean(h.plus(Duration.ofMinutes(20)));
        assertThat(count(cursor(poisoned).get("completions"))).isEqualTo(1);
        assertThat(hours(poisoned)).extracting(row -> count(row.get("completions"))).containsExactly(1L);
    }

    @Test
    void aFailingRecountLeavesTheOthersABigOneIsFinalizedWithoutAndAnUnfinishedOneADayAfterItsEnd() {
        minGroup("3");
        String run = run();
        String sponsor = newSponsor("s-an-recount-" + run);
        credit(sponsor, 100_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        Map<String, Object> ends = Map.of("regions", List.of("GLOBAL"), "endAt",
            h.plus(Duration.ofMinutes(30)).toString());
        String recounted = livePublication(sponsor, "Recounted " + run, ends);
        String big = livePublication(sponsor, "Big " + run, ends);
        String unfinished = livePublication(sponsor, "Unfinished " + run, ends);
        complete("t-an-rc-1-" + run, recounted, true, true);
        for (int i = 0; i < 3; i++) {
            complete("t-an-big-" + i + "-" + run, big, i > 0, true);
        }
        complete("t-an-unf-1-" + run, unfinished, true, false);
        // An attempt that never finishes: its expiry is never run.
        startAttempt(newTaker("t-an-unf-2-" + run, null), unfinished);
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        at(h.plus(Duration.ofMinutes(31)));
        runTick();
        assertThat(card(recounted)).containsEntry("status", "ENDED");

        // The recount's write is refused: the item fails, the others (the big one's finalizing among them) commit.
        execute("ALTER TABLE qb_analytics_cursor ADD CONSTRAINT q9e1_poison CHECK (publication_id <> '" + recounted
            + "'::uuid OR finalized_at IS NULL)");
        try {
            JobRunner.JobRun failing = analyticsRun(h.plus(Duration.ofMinutes(70)));
            assertThat(failing.outcome()).as(failing.toString()).isEqualTo(JobRunner.Outcome.PARTIAL);
            assertThat(failing.items().failed()).isEqualTo(1);
            assertThat(cursor(recounted).get("finalized_at")).isNull();
            // Above the bound (3 completions, at most 2 recounted): finalized with the three read by the chunks.
            assertThat(cursor(big).get("finalized_at")).isNotNull();
            assertThat(count(cursor(big).get("analysed"))).isEqualTo(3);
            assertThat(String.valueOf(cursor(big).get("questions"))).isEqualTo(recount(big));
            // Ended, but an attempt is in progress: not yet.
            assertThat(cursor(unfinished).get("finalized_at")).isNull();
            assertThat(refreshedAt()).isEqualTo(h.plus(Duration.ofMinutes(70)));
        } finally {
            execute("ALTER TABLE qb_analytics_cursor DROP CONSTRAINT q9e1_poison");
        }
        runUntilClean(h.plus(Duration.ofMinutes(80)));
        assertThat(cursor(recounted).get("finalized_at")).isNotNull();
        assertThat(String.valueOf(cursor(recounted).get("questions"))).isEqualTo(recount(recounted));
        assertThat(cursor(unfinished).get("finalized_at")).isNull();

        // A day after its end the unfinished one is finalized anyway (review L3), with its one finished attempt.
        runAnalytics(h.plus(Duration.ofMinutes(31)).plus(Duration.ofDays(1)).plus(Duration.ofMinutes(10)));
        assertThat(cursor(unfinished).get("finalized_at")).isNotNull();
        assertThat(count(cursor(unfinished).get("analysed"))).isEqualTo(1);
        assertThat(count(counter(unfinished).get("in_progress"))).isEqualTo(1);
    }
}
