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
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The analytics job itself (docs/quizbuks/plans/Q9e-analytics.md, D-Q9e-2), with chunks of two publications and at
 * most three attempts read per chunk and run: the backlog is caught up over runs and adds up to a whole recount; a
 * repeated run changes nothing; a stop puts its increments in the hour of the last run, marked as a gap; a publication
 * is recounted and finalized half an hour after it ended, once nothing is in progress and the lag has passed, and then
 * left alone; hours older than 400 days are deleted. The job writes none of answering's tables.
 */
@TestPropertySource(properties = {"quizbuks.analytics.max-attempts-per-chunk=3", "quizbuks.analytics.chunk-size=2"})
class AnalyticsJobIT extends AnalyticsItSupport {

    long audits() {
        return count(query("SELECT count(*) AS n FROM sys_audit_record").getFirst().get("n"));
    }

    long version(String table, String key, String publicationId) {
        return count(query("SELECT max(version) AS v FROM " + table + " WHERE " + key + " = ?::uuid", publicationId)
            .getFirst().get("v"));
    }

    @Test
    void theBacklogIsCaughtUpRepeatsChangeNothingAndAnEndedPublicationIsFinalized() {
        minGroup("3");
        String run = UUID.randomUUID().toString().substring(0, 8);
        String sponsor = newSponsor("s-an-job-" + run);
        credit(sponsor, 100_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        // It ends at its end time, by the minute's job.
        String publication = livePublication(sponsor, "Job " + run, Map.of("regions", List.of("GLOBAL"),
            "endAt", h.plus(Duration.ofMinutes(150)).toString()));
        // Others of the chunks, untouched.
        analysedPublication(sponsor, "Job other A " + run);
        analysedPublication(sponsor, "Job other B " + run);
        // The run before this test's: what the class's other test left is counted then.
        runAnalytics(h.plus(Duration.ofMinutes(2)));
        for (int i = 1; i <= 5; i++) {
            complete("t-an-job-" + i + "-" + run, publication, i % 2 == 0, false);
        }
        // The five finished at the same instant (the clock stood still): the cap of three cuts between equal
        // submission times, and the watermark's tie-break by attempt key reads each exactly once.
        assertThat(query("SELECT count(DISTINCT submitted_at) AS n FROM qb_attempt WHERE publication_id = ?::uuid",
            publication).getFirst().get("n")).satisfies(n -> assertThat(count(n)).isEqualTo(1));
        // At most three attempts a run: two runs read the five.
        runAnalytics(h.plus(Duration.ofMinutes(10)));
        assertThat(count(cursor(publication).get("analysed"))).isEqualTo(3);
        assertThat(count(cursor(publication).get("shown_analysed"))).isEqualTo(3);
        runAnalytics(h.plus(Duration.ofMinutes(20)));
        assertThat(count(cursor(publication).get("analysed"))).isEqualTo(5);
        assertThat(String.valueOf(cursor(publication).get("questions"))).isEqualTo(recount(publication));
        // Two new ones are fewer than the group: the shown figures stay over three.
        assertThat(count(cursor(publication).get("shown_analysed"))).isEqualTo(3);

        // A repeated run (the platform replays the scheduled time) and a run not after the last one: nothing changes.
        long audits = audits();
        long cursorVersion = version("qb_analytics_cursor", "publication_id", publication);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(analyticsJob, h.plus(Duration.ofMinutes(20)))).isIn(JobRunner.Outcome.REPLAYED,
            JobRunner.Outcome.SUCCEEDED);
        runAnalytics(h.plus(Duration.ofMinutes(15)));
        assertThat(audits()).isEqualTo(audits);
        assertThat(version("qb_analytics_cursor", "publication_id", publication)).isEqualTo(cursorVersion);
        // A run on time without news writes nothing of the publication's.
        runAnalytics(h.plus(Duration.ofMinutes(30)));
        assertThat(version("qb_analytics_cursor", "publication_id", publication)).isEqualTo(cursorVersion);
        assertThat(hours(publication)).singleElement().satisfies(row -> {
            assertThat(count(row.get("completions"))).isEqualTo(5);
            assertThat(count(row.get("starts"))).isEqualTo(5);
            assertThat(row).containsEntry("gap", false);
        });

        // The job stops for two and a half hours, while two winners finish and a last taker starts: the first run
        // after it puts the increments in the hour of the last run before it, as a gap.
        at(h.plus(Duration.ofMinutes(40)));
        complete("t-an-job-w1-" + run, publication, true, true);
        at(h.plus(Duration.ofHours(2)));
        complete("t-an-job-w2-" + run, publication, true, true);
        at(h.plus(Duration.ofMinutes(145)));
        String late = startAttempt(newTaker("t-an-job-late-" + run, null), publication);
        at(h.plus(Duration.ofMinutes(151)));
        runTick();
        assertThat(card(publication)).containsEntry("status", "ENDED");
        Instant ended = instant(card(publication).get("ended_at"));
        runAnalytics(h.plus(Duration.ofMinutes(185)));
        assertThat(hours(publication)).singleElement().satisfies(row -> {
            assertThat(counts(row)).containsExactly(0L, 8L, 7L, 2L, 2L, 200L);
            assertThat(row).containsEntry("gap", true);
        });
        // Ended over half an hour ago, but an attempt is still in progress (its expiry not run yet): not recounted.
        assertThat(cursor(publication).get("finalized_at")).isNull();
        assertThat(count(cursor(publication).get("analysed"))).isEqualTo(7);
        assertThat(count(cursor(publication).get("shown_analysed"))).isEqualTo(7);
        at(h.plus(Duration.ofMinutes(186)));
        assertThat(runExpire(clock.instant()).items().succeeded()).isPositive();
        assertThat(attempt(late)).containsEntry("status", "EXPIRED");
        assertThat(count(counter(publication).get("in_progress"))).isZero();
        // Nothing in progress, but the expiry finished within the lag: the recount misses it and waits.
        runAnalytics(h.plus(Duration.ofMinutes(190)));
        assertThat(cursor(publication).get("finalized_at")).isNull();
        // Read whole once the lag passed: finalized.
        runAnalytics(h.plus(Duration.ofMinutes(200)));
        Map<String, Object> finalized = cursor(publication);
        assertThat(instant(finalized.get("finalized_at"))).isEqualTo(h.plus(Duration.ofMinutes(200)));
        assertThat(count(finalized.get("analysed"))).isEqualTo(8);
        assertThat(count(finalized.get("expired"))).isEqualTo(1);
        assertThat(String.valueOf(finalized.get("questions"))).isEqualTo(recount(publication));
        // The shown figures were over 7: the final one is a single new completion, fewer than the group, so they stay
        // (MinGroup: the recount obeys the same rule).
        assertThat(count(finalized.get("shown_analysed"))).isEqualTo(7);
        assertThat(count(overview(sponsorToken(sponsor), publication).get("basedOn"))).isEqualTo(7);
        assertThat(trend(sponsorToken(sponsor), publication, h, h.plus(Duration.ofHours(4)), "HOUR"))
            .extracting(row -> row.get("gap"), row -> count(row.get("completions")))
            .containsExactly(tuple(true, 7L), tuple(false, 0L), tuple(false, 0L), tuple(false, 1L));
        // Finalized publications are not read again; a day after their end not even selected.
        long finalVersion = version("qb_analytics_cursor", "publication_id", publication);
        runAnalytics(h.plus(Duration.ofMinutes(210)));
        assertThat(version("qb_analytics_cursor", "publication_id", publication)).isEqualTo(finalVersion);
        runAnalytics(ended.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(10)));
        assertThat(version("qb_analytics_cursor", "publication_id", publication)).isEqualTo(finalVersion);

        // The job writes only its own tables: none of answering's.
        assertThat(count(query("SELECT count(*) AS n FROM sys_audit_record WHERE entity_type IN ('QbAttempt', "
            + "'QbPublicationCounter', 'QbPublicationStartCounter', 'QbPublicationView', 'QbPublicationCard') "
            + "AND process_seq_id IN (SELECT process_seq_id FROM op_process WHERE process_name LIKE "
            + "'QB_ANALYTICS%')").getFirst().get("n"))).isZero();
    }

    @Test
    void overlappingRunsCountEveryIncrementOnce() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String sponsor = newSponsor("s-an-overlap-" + run);
        credit(sponsor, 100_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Overlap " + run);
        runAnalytics(h.plus(Duration.ofMinutes(2)));
        for (int i = 0; i < 4; i++) {
            complete("t-an-overlap-" + i + "-" + run, publication, true, i > 1);
        }
        // Two runs asked for at once (two instances, or a slow run and the next): the cluster lock lets one run, the
        // other does nothing; then a run catches up whatever is left.
        at(h.plus(Duration.ofMinutes(20)));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> jobRunner.execute(analyticsJob, h.plus(Duration.ofMinutes(10))));
            var second = pool.submit(() -> jobRunner.execute(analyticsJob, h.plus(Duration.ofMinutes(20))));
            List<JobRunner.Outcome> outcomes = List.of(first.get().outcome(), second.get().outcome());
            assertThat(outcomes).as(outcomes.toString()).contains(JobRunner.Outcome.SUCCEEDED)
                .allMatch(outcome -> outcome == JobRunner.Outcome.SUCCEEDED || outcome == JobRunner.Outcome.LOCKED);
        } finally {
            pool.shutdown();
        }
        runAnalytics(h.plus(Duration.ofMinutes(30)));
        long completions = count(counter(publication).get("completions"));
        assertThat(completions).isEqualTo(4);
        assertThat(hours(publication).stream().mapToLong(row -> count(row.get("completions"))).sum())
            .isEqualTo(completions);
        assertThat(hours(publication).stream().mapToLong(row -> count(row.get("starts"))).sum()).isEqualTo(4);
        assertThat(count(cursor(publication).get("analysed"))).isEqualTo(completions);
        assertThat(String.valueOf(cursor(publication).get("questions"))).isEqualTo(recount(publication));
    }

    @Test
    void hoursOlderThan400DaysAreDeleted() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String sponsor = newSponsor("s-an-purge-" + run);
        credit(sponsor, 10_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String publication = analysedPublication(sponsor, "Purge " + run);
        Instant at = h.plus(Duration.ofMinutes(10));
        for (int days : List.of(401, 400, 399)) {
            execute("INSERT INTO qb_analytics_hour (hour_id, publication_id, publisher_kind, publisher_id, hour_start, "
                + "views, starts, completions, passes, winners, spent, gap, version) VALUES (?, ?::uuid, 'SPONSOR', ?, "
                + "?::timestamptz, 1, 1, 1, 0, 0, 0, false, 1)", UUID.randomUUID().toString(), publication, sponsor,
                h.minus(Duration.ofDays(days)).toString());
        }
        runAnalytics(at);
        // Older than 400 days before the run: the first two (400 days before h is before at - 400 days).
        assertThat(hours(publication)).extracting(row -> instant(row.get("hour_start")))
            .containsExactly(h.minus(Duration.ofDays(399)));
    }
}
