package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A publication on its way (5.3-2, M-35; docs/quizbuks/plans/Q4-publishing.md, D-Q4-1, D-Q4-7): the minute's job
 * starts and ends publications on time, catches up after missed minutes and ends paused ones; pausing stops new
 * attempts at once and resuming goes where the time says. Publications here go on without review (the controlled
 * switch is on), so each is scheduled as soon as it is submitted.
 */
class PublicationLifecycleIT extends PublicationItSupport {

    private String versionId;

    @BeforeEach
    void withoutReview() {
        if (query("SELECT 1 FROM sys_param_version WHERE param_key = 'qb.review.publication.auto' "
            + "AND param_value = 'true'").isEmpty()) {
            autoApprove(true);
        }
        if (versionId == null) {
            versionId = version(sponsorA(), "Lifecycle");
        }
    }

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    /** A publication submitted now, running from {@code start} to {@code end} (from the clock's time). */
    private String scheduled(String name, Duration start, Duration end) {
        Instant now = clock.instant();
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(versionId, name, now.plus(start),
            now.plus(end))).get("publicationId");
        Map<String, Object> submitted = submit(sponsorA(), id);
        assertThat(submitted).containsEntry("approval", "NOT_REQUIRED");
        return id;
    }

    private Map<String, Object> pause(String id) {
        return run("QB_PUBLICATION_PAUSE", sponsorA(), body("publicationId", id));
    }

    private Map<String, Object> resume(String id) {
        return run("QB_PUBLICATION_RESUME", sponsorA(), body("publicationId", id));
    }

    @Test
    void theTickStartsAndEndsAPublicationOnTime() {
        String id = scheduled("On time", Duration.ofHours(1), Duration.ofHours(3));
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);

        clock.advance(Duration.ofMinutes(59));
        assertThat(runTick()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);

        clock.advance(Duration.ofMinutes(1));
        runTick();
        assertThat(status(id)).isEqualTo(PublicationStatus.LIVE);

        clock.advance(Duration.ofHours(2));
        runTick();
        assertThat(row(id)).containsEntry("status", PublicationStatus.ENDED)
            .containsEntry("end_reason", PublicationStatus.EXPIRED)
            .satisfies(r -> assertThat(r.get("ended_at")).isNotNull());
    }

    @Test
    void oneRunAfterMissedMinutesCatchesUp() {
        String ended = scheduled("Missed entirely", Duration.ofMinutes(10), Duration.ofMinutes(20));
        String started = scheduled("Missed start", Duration.ofMinutes(10), Duration.ofDays(1));
        String waiting = scheduled("Not yet", Duration.ofDays(1), Duration.ofDays(2));
        // The system was down for an hour: one run moves everything due.
        clock.advance(Duration.ofHours(1));
        runTick();
        assertThat(status(ended)).isEqualTo(PublicationStatus.ENDED);
        assertThat(status(started)).isEqualTo(PublicationStatus.LIVE);
        assertThat(status(waiting)).isEqualTo(PublicationStatus.SCHEDULED);
        // Nothing left to do on the next minute.
        clock.advance(Duration.ofMinutes(1));
        long rows = count("qb_publication_version");
        runTick();
        assertThat(count("qb_publication_version")).isEqualTo(rows);
    }

    @Test
    void pausingStopsNewAttemptsAndResumingGoesWhereTheTimeSays() {
        String live = scheduled("Paused live", Duration.ofMinutes(-5), Duration.ofHours(2));
        assertThat(status(live)).isEqualTo(PublicationStatus.LIVE);
        assertThat(accepts(live)).isTrue();
        assertThat(pause(live)).containsEntry("status", PublicationStatus.PAUSED);
        assertThat(row(live).get("paused_at")).isNotNull();
        // At once: Q5 starts attempts under the same lock and asks the same rule.
        assertThat(accepts(live)).isFalse();
        assertThat(resume(live)).containsEntry("status", PublicationStatus.LIVE);
        assertThat(row(live)).containsEntry("paused_at", null);
        assertThat(accepts(live)).isTrue();

        // Paused before its start, resumed before its start: scheduled again.
        String early = scheduled("Paused early", Duration.ofHours(1), Duration.ofHours(2));
        pause(early);
        assertThat(resume(early)).containsEntry("status", PublicationStatus.SCHEDULED);

        // Resumed after its end: ended.
        pause(early);
        clock.advance(Duration.ofHours(3));
        assertThat(resume(early)).containsEntry("status", PublicationStatus.ENDED);
        assertThat(row(early)).containsEntry("end_reason", PublicationStatus.EXPIRED);

        // Moves that are not this action's are refused.
        assertThat(codes(refused("QB_PUBLICATION_RESUME", sponsorA(), body("publicationId", live), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
        assertThat(codes(refused("QB_PUBLICATION_PAUSE", sponsorA(), body("publicationId", early), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
        String draft = (String) run("QB_PUBLICATION_SAVE", sponsorA(), body("name", "Draft")).get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_PAUSE", sponsorA(), body("publicationId", draft), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
        pause(live);
        assertThat(codes(refused("QB_PUBLICATION_PAUSE", sponsorA(), body("publicationId", live), 422)))
            .containsExactly("ILLEGAL_TRANSITION");
    }

    @Test
    void aPausedPublicationEndsWithItsTime() {
        String id = scheduled("Paused to the end", Duration.ofMinutes(-5), Duration.ofMinutes(30));
        pause(id);
        clock.advance(Duration.ofMinutes(10));
        runTick();
        assertThat(status(id)).isEqualTo(PublicationStatus.PAUSED);
        clock.advance(Duration.ofMinutes(30));
        runTick();
        assertThat(row(id)).containsEntry("status", PublicationStatus.ENDED)
            .containsEntry("end_reason", PublicationStatus.EXPIRED);
    }

    @Test
    void afterItsEndALivePublicationTakesNoAttemptEvenBeforeTheTick() {
        String id = scheduled("Past its end", Duration.ofMinutes(-5), Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(11));
        assertThat(status(id)).isEqualTo(PublicationStatus.LIVE);
        assertThat(accepts(id)).isFalse();
    }

    @Test
    void aPauseAndTheTickAtOnceAreSerializedByTheLock() throws Exception {
        for (int round = 0; round < 3; round++) {
            String id = scheduled("Race " + round, Duration.ofMinutes(1), Duration.ofDays(1));
            clock.advance(Duration.ofMinutes(2));
            execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Instant at = clock.instant();
                CompletableFuture<JobRunner.Outcome> tickRun = CompletableFuture.supplyAsync(
                    () -> jobs.run(tick, at), pool);
                CompletableFuture<Integer> pauseRun = CompletableFuture.supplyAsync(
                    () -> call("QB_PUBLICATION_PAUSE", sponsorA(), body("publicationId", id)).expectBody(MAP)
                        .returnResult().getStatus().value(), pool);
                assertThat(tickRun.get()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
                // Either the tick went first (LIVE, then paused) or the pause did (paused, the tick leaves it).
                assertThat(pauseRun.get()).isEqualTo(200);
            } finally {
                pool.shutdown();
            }
            assertThat(status(id)).isEqualTo(PublicationStatus.PAUSED);
            assertThat(accepts(id)).isFalse();
        }
    }

    private boolean accepts(String id) {
        Map<String, Object> row = row(id);
        return PublicationRules.acceptsNewAttempts((String) row.get("status"), instant(row.get("start_at")),
            instant(row.get("end_at")), clock.instant());
    }

    private static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant() : value instanceof java.sql.Timestamp t
            ? t.toInstant() : (Instant) value;
    }
}
