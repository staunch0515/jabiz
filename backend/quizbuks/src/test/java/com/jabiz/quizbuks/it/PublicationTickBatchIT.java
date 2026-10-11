package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The minute's job moves at most {@code maxItems} publications per run (2 000, {@code PublishingLimits.TICK_BATCH};
 * two here), earliest due first, each in a transaction of its own; the rest wait for the next minute
 * (docs/quizbuks/plans/Q4-publishing.md, D-Q4-7; Q5b). A class of its own: it counts every publication.
 */
@TestPropertySource(properties = "quizbuks.publication-tick.max-items=2")
class PublicationTickBatchIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    private static long live() {
        return latest("qb_publication_version", "publication_id", "status = 'LIVE'").size();
    }

    @Test
    void moreThanABatchDueIsMovedOverTwoRunsEarliestFirst() {
        autoApprove(true);
        String versionId = version(sponsorA(), "Batch");
        Instant now = clock.instant();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // The later saved, the earlier its start: the run takes the earliest due, not the first saved.
            Map<String, Object> policy = complete(versionId, "Batch " + i, now.plus(Duration.ofMinutes(3 - i)),
                now.plus(Duration.ofDays(1)));
            String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), policy).get("publicationId");
            assertThat(submit(sponsorA(), id)).containsEntry("status", PublicationStatus.SCHEDULED);
            ids.add(id);
        }
        clock.advance(Duration.ofMinutes(4));
        JobRunner.JobRun first = tickRun();
        assertThat(first.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(first.items().succeeded()).isEqualTo(2);
        assertThat(first.items().more()).isTrue();
        assertThat(live()).isEqualTo(2);
        assertThat(status(ids.getFirst())).isEqualTo(PublicationStatus.SCHEDULED);

        clock.advance(Duration.ofMinutes(1));
        JobRunner.JobRun second = tickRun();
        assertThat(second.items().succeeded()).isEqualTo(1);
        assertThat(second.items().more()).isFalse();
        assertThat(live()).isEqualTo(3);
    }
}
