package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationLifecycle;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One publication of the minute's job that fails is left alone (Q5b; platform decision D42; Q4's known issue of the
 * batch in one transaction): the others move on in the same run, the failing one is recorded and tried again with
 * back-off. A class of its own: it plants a bad schedule row.
 */
class PublicationTickFailureIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    @Test
    void aFailingItemHoldsUpNoOtherAndIsTriedAgainWithBackOff() {
        autoApprove(true);
        String versionId = version(sponsorA(), "Failing batch");
        Instant now = clock.instant();
        String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), complete(versionId, "Due",
            now.plus(Duration.ofMinutes(1)), now.plus(Duration.ofDays(1)))).get("publicationId");
        submit(sponsorA(), id);
        // A schedule row naming something that is not a publication (here: the quiz), due first, cannot be moved on.
        Object quizId = row(id).get("quiz_id");
        execute("INSERT INTO qb_publication_schedule (publication_id, quiz_id, version_id, next_due_at, version) "
            + "VALUES (?, NULL, NULL, ?, 0)", quizId, Timestamp.from(now));
        clock.advance(Duration.ofMinutes(2));

        JobRunner.JobRun first = tickRun();
        assertThat(first.outcome()).isEqualTo(JobRunner.Outcome.PARTIAL);
        assertThat(first.items().failed()).isEqualTo(1);
        assertThat(first.items().succeeded()).isEqualTo(1);
        assertThat(status(id)).isEqualTo(PublicationStatus.LIVE);
        assertThat(failures(quizId)).containsExactly("FAILED");

        // The first failure is retried by the next run; after the second, it waits a minute (back-off).
        clock.advance(Duration.ofMinutes(1));
        assertThat(tickRun().items().failed()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        JobRunner.JobRun waiting = tickRun();
        assertThat(waiting.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(waiting.items().deferred()).isEqualTo(1);
        assertThat(waiting.items().failed()).isZero();
        assertThat(failures(quizId)).containsExactly("FAILED", "FAILED");

        // Once the row is gone, nothing is due any more.
        execute("DELETE FROM qb_publication_schedule WHERE publication_id = ?", (UUID) quizId);
        clock.advance(Duration.ofMinutes(1));
        assertThat(tickRun().items().selected()).isZero();
        assertThat(status(id)).isEqualTo(PublicationStatus.LIVE);
    }

    /** The job's failure records of an item, oldest first. */
    private static List<Object> failures(Object itemKey) {
        return query("SELECT kind FROM sys_job_item_failure WHERE job_name = ? AND item_key = ? ORDER BY entry_no",
            PublicationLifecycle.TICK_JOB, String.valueOf(itemKey)).stream().map(row -> row.get("kind")).toList();
    }
}
