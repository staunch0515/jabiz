package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statistics job in chunks (review of Q5a): a run counts every publication running or ended within the month, in
 * chunks no larger than the configured size (2 here), so that no commit exceeds the statistics' batch; publications
 * that ended early (winners or budget used up) leave it a month after they ended, not after their end time; and the
 * run's time is recorded even when no count changed.
 */
@TestPropertySource(properties = "quizbuks.stats.chunk-size=2")
class StatsChunkIT extends PlayItSupport {

    static Instant instant(Object value) {
        return value instanceof OffsetDateTime time ? time.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    Map<String, Object> stats(String publicationId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_publication_stats WHERE publication_id = ?::uuid",
            publicationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Instant lastRun() {
        return instant(query("SELECT refreshed_at FROM qb_stats_run WHERE job = 'qb.publication-stats'").getFirst()
            .get("refreshed_at"));
    }

    @Test
    void everyPublicationIsCountedInChunksAndEarlyEndsLeaveAfterAMonth() {
        String sponsor = newSponsor("s-chunks");
        credit(sponsor, 10_000);
        Instant t0 = clock.instant();
        List<String> publications = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String publicationId = livePublication(sponsor, "Chunk " + i, Map.of("regions", List.of("GLOBAL"),
                "endAt", t0.plus(Duration.ofDays(200)).toString()));
            play(newTaker("t-chunk-" + i, null), publicationId, true, true);
            publications.add(publicationId);
        }
        // One ends early: its only winner's place is taken.
        String quota = livePublication(sponsor, "Chunk quota", Map.of("regions", List.of("GLOBAL"), "maxWinners", 1,
            "endAt", t0.plus(Duration.ofDays(200)).toString()));
        play(newTaker("t-chunk-quota", null), quota, true, true);
        assertThat(card(quota)).containsEntry("status", "ENDED");
        assertThat(card(quota).get("ended_at")).isNotNull();

        Instant first = t0.plus(Duration.ofMinutes(10));
        assertThat(runStats(first).toString()).contains("SUCCEEDED");
        for (String publicationId : publications) {
            assertThat(stats(publicationId)).as(publicationId).containsEntry("last_24h", BigDecimal.ONE);
        }
        assertThat(stats(quota)).containsEntry("last_30d", BigDecimal.ONE);
        assertThat(lastRun()).isEqualTo(first);

        // Ten minutes on, nothing changed: no row is written, but the run's time is.
        Instant second = first.plus(Duration.ofMinutes(10));
        long version = ((Number) stats(publications.getFirst()).get("version")).longValue();
        assertThat(runStats(second).toString()).contains("SUCCEEDED");
        assertThat(((Number) stats(publications.getFirst()).get("version")).longValue()).isEqualTo(version);
        assertThat(lastRun()).isEqualTo(second);

        // 32 days on: the running ones are counted again (their month is empty now); the one that ended early is
        // left as it was, although its end time is months away.
        Instant later = t0.plus(Duration.ofDays(32));
        clock.set(later);
        assertThat(runStats(later).toString()).contains("SUCCEEDED");
        for (String publicationId : publications) {
            assertThat(stats(publicationId)).as(publicationId).containsEntry("last_30d", BigDecimal.ZERO);
        }
        assertThat(stats(quota)).containsEntry("last_30d", BigDecimal.ONE);
        assertThat(lastRun()).isEqualTo(later);
    }
}
