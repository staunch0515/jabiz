package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.publishing.PublicationStatus;
import com.jabiz.quizbuks.publishing.PublishingLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The minute's job moves at most {@link PublishingLimits#TICK_BATCH} publications per run, by key; the rest wait for
 * the next minute (docs/quizbuks/plans/Q4-publishing.md, D-Q4-7). A class of its own: it counts every publication.
 */
class PublicationTickBatchIT extends PublicationItSupport {

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    private static long live() {
        return ((Number) latest("qb_publication_version", "publication_id", "status = 'LIVE'").size())
            .longValue();
    }

    @Test
    void moreThanABatchDueIsMovedOverTwoRuns() {
        autoApprove(true);
        String versionId = version(sponsorA(), "Batch");
        Instant now = clock.instant();
        Map<String, Object> policy = complete(versionId, "Batch", now.plus(Duration.ofMinutes(1)),
            now.plus(Duration.ofDays(1)));
        int total = PublishingLimits.TICK_BATCH + 1;
        for (int i = 0; i < total; i++) {
            policy.put("name", "Batch " + i);
            String id = (String) run("QB_PUBLICATION_SAVE", sponsorA(), policy).get("publicationId");
            assertThat(submit(sponsorA(), id)).containsEntry("status", PublicationStatus.SCHEDULED);
        }
        clock.advance(Duration.ofMinutes(2));
        runTick();
        assertThat(live()).isEqualTo(PublishingLimits.TICK_BATCH);
        clock.advance(Duration.ofMinutes(1));
        runTick();
        assertThat(live()).isEqualTo(total);
    }
}
