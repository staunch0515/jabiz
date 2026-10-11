package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One item of the minute's batch that fails takes the whole batch with it (platform gap 6 of
 * docs/quizbuks/plans/Q4-publishing.md): nothing of the batch is written, and every item is tried again on the next
 * run. Once the failing item is gone, the next run moves the rest. A class of its own: it plants a bad schedule row.
 */
class PublicationTickFailureIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    @Test
    void aFailingItemRollsTheBatchBackAndTheNextRunRetries() {
        autoApprove(true);
        String versionId = version(sponsorA(), "Failing batch");
        Instant now = clock.instant();
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(versionId, "Due",
            now.plus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1)))).get("publicationId");
        submit(sponsorA(), id);
        // A schedule row naming something that is not a publication (here: the quiz) cannot be moved on.
        Object quizId = row(id).get("quiz_id");
        execute("INSERT INTO qb_publication_schedule (publication_id, quiz_id, version_id, next_due_at, version) "
            + "VALUES (?, NULL, NULL, ?, 0)", quizId, Timestamp.from(now));
        clock.advance(Duration.ofMinutes(2));

        assertThat(runTick()).isEqualTo(JobRunner.Outcome.FAILED);
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);
        clock.advance(Duration.ofMinutes(1));
        assertThat(runTick()).isEqualTo(JobRunner.Outcome.FAILED);
        assertThat(status(id)).isEqualTo(PublicationStatus.SCHEDULED);

        execute("DELETE FROM qb_publication_schedule WHERE publication_id = ?", (UUID) quizId);
        clock.advance(Duration.ofMinutes(1));
        assertThat(runTick()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(status(id)).isEqualTo(PublicationStatus.LIVE);
    }
}
