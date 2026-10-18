package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analytics job's cost (docs/quizbuks/plans/Q9e-analytics.md section 5, 性能; docs/quizbuks/perf/Q9e-analytics.md):
 * a hot publication of ten questions whose attempts are written straight into the tables (the job reads only them and
 * the counters) - 200,000 finished before the job first sees it, then 30,000 in ten minutes (50 a second) - beside
 * 999 other running publications with ten attempts each. Prints each run's time, attempts read and the audit records
 * it wrote, the full recount after the end, and the sponsor templates' times. Not part of {@code check} (tag
 * {@code perf}): {@code ./gradlew :quizbuks:perfTest --tests '*AnalyticsPerfIT'}.
 */
@Tag("perf")
class AnalyticsPerfIT extends AnalyticsItSupport {

    static final int OTHERS = 999;
    static final int QUESTIONS = 10;

    long audits() {
        return count(query("SELECT count(*) AS n FROM sys_audit_record").getFirst().get("n"));
    }

    /** Finished attempts {@code from}..{@code to} of a publication, submitted evenly between two times. */
    void attempts(String publicationId, int from, int to, Instant first, Instant last) {
        execute("INSERT INTO qb_attempt (attempt_id, user_id, publication_id, sponsor_id, version_id, started_at, "
            + "deadline, status, current_index, option_order, answers, result, submitted_at, score, full_score, passed, "
            + "reward, fee, reward_status, version) "
            + "SELECT lpad(to_hex(n), 12, '0') || '-perf-' || substr(md5(?), 1, 18), 'perf-u-' || n, ?::uuid, "
            + "c.sponsor_id, c.version_id, s.at - interval '90 seconds', s.at + interval '210 seconds', "
            + "CASE WHEN n % 20 = 0 THEN 'EXPIRED' ELSE 'SUBMITTED' END, 0, '[]', '[]', "
            + "(SELECT CAST(jsonb_agg(jsonb_build_object('no', q, 'selected', jsonb_build_array(1), 'correct', "
            + "jsonb_build_array(1), 'points', 1, 'earned', CASE WHEN (n + q) % 3 = 0 THEN 0 ELSE 1 END) ORDER BY q) "
            + "AS text) FROM generate_series(1, " + QUESTIONS + ") q), s.at, 7, 10, true, 0, 0, 'NONE', 1 "
            + "FROM generate_series(?, ?) n "
            + "CROSS JOIN qb_publication_card c "
            + "CROSS JOIN LATERAL (SELECT CAST(? AS timestamptz) + (CAST(? AS timestamptz) - CAST(? AS timestamptz)) "
            + "* (CAST(n - ? AS double precision) / greatest(? - ?, 1)) AS at) s "
            + "WHERE c.publication_id = ?::uuid",
            publicationId, publicationId, from, to, first.toString(), last.toString(), first.toString(), from, to, from,
            publicationId);
        execute("UPDATE qb_publication_start_counter SET starts = starts + ? WHERE publication_id = ?::uuid",
            to - from + 1, publicationId);
        execute("UPDATE qb_publication_counter SET completions = completions + ?, passes = passes + ? "
            + "WHERE publication_id = ?::uuid", to - from + 1, to - from + 1, publicationId);
    }

    /** Runs the job; prints and returns what it took. */
    long timed(String label, Instant at) {
        long audits = audits();
        long began = System.nanoTime();
        runAnalytics(at);
        long millis = (System.nanoTime() - began) / 1_000_000;
        System.out.printf("AnalyticsPerfIT: %-46s %6d ms, %6d audit records%n", label, millis, audits() - audits);
        return millis;
    }

    long template(String label, Runnable read) {
        read.run();
        long began = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            read.run();
        }
        long millis = (System.nanoTime() - began) / 5_000_000;
        System.out.printf("AnalyticsPerfIT: template %-37s %6d ms%n", label, millis);
        return millis;
    }

    @Test
    void aHotPublicationAmongAThousand() {
        minGroup("5");
        String sponsor = newSponsor("s-an-perf");
        credit(sponsor, 1_000_000);
        Instant h = nextHour();
        at(h.plus(Duration.ofMinutes(1)));
        String hot = livePublication(sponsor, "Hot", Map.of("regions", List.of("GLOBAL"), "maxWinners", 1_000_000,
            "budgetCap", 100_000_000));
        // 999 others: copies of its card and counters (the job reads nothing else of a publication).
        long seq = count(query("SELECT max(process_seq_id) AS s FROM op_process").getFirst().get("s"));
        execute("CREATE TABLE perf_ids AS SELECT gen_random_uuid() AS id FROM generate_series(1, ?)", OTHERS);
        execute("INSERT INTO entity_registry (entity_id, entity_type, created_seq_id) SELECT id, 'QbPublication', ? "
            + "FROM perf_ids", seq);
        execute("INSERT INTO qb_publication_card SELECT p.id, c.sponsor_id, c.status, c.regions, c.start_at, c.end_at, "
            + "c.name, c.quiz_title, c.cover, c.version_id, c.max_reward, c.full_score, c.pass_score, c.time_limit_sec, "
            + "c.question_count, c.budget_cap, c.max_winners, c.tiers, c.sponsor_country, c.went_on_at, c.ended_at, 1 "
            + "FROM perf_ids p CROSS JOIN qb_publication_card c WHERE c.publication_id = ?::uuid", hot);
        // And of its versions, which the analytics tables refer to (and the sponsor's templates read).
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'qb_publication_version' AND column_name <> 'row_id' "
            + "ORDER BY ordinal_position").stream().map(row -> String.valueOf(row.get("column_name"))).toList();
        execute("INSERT INTO qb_publication_version (" + String.join(", ", columns) + ") SELECT "
            + String.join(", ", columns.stream().map(c -> "publication_id".equals(c) ? "p.id" : "v." + c).toList())
            + " FROM perf_ids p CROSS JOIN qb_publication_version v WHERE v.publication_id = ?::uuid", hot);
        execute("INSERT INTO qb_publication_counter (publication_id, completions, passes, winners, released, spent, "
            + "version) SELECT id, 0, 0, 0, 0, 0, 1 FROM perf_ids");
        execute("INSERT INTO qb_publication_start_counter (publication_id, starts, reserved_total, version) "
            + "SELECT id, 0, 0, 1 FROM perf_ids");
        List<String> others = new ArrayList<>();
        for (Map<String, Object> row : query("SELECT id FROM perf_ids")) {
            others.add(String.valueOf(row.get("id")));
        }
        runAnalytics(h.plus(Duration.ofMinutes(2)));

        // Before the job sees the hot one: 200,000 finished within the hour; each other has ten.
        attempts(hot, 1, 200_000, h.plus(Duration.ofMinutes(3)), h.plus(Duration.ofMinutes(50)));
        for (String other : others) {
            attempts(other, 1, 10, h.plus(Duration.ofMinutes(3)), h.plus(Duration.ofMinutes(50)));
        }
        execute("ANALYZE qb_attempt");
        timed("catch-up run 1 (200,000 behind)", h.plus(Duration.ofMinutes(60)));
        int runs = 1;
        Instant at = h.plus(Duration.ofMinutes(60));
        while (count(cursor(hot).get("analysed")) < 200_000 && runs < 10) {
            at = at.plus(Duration.ofMinutes(10));
            runs++;
            timed("catch-up run " + runs, at);
        }
        assertThat(count(cursor(hot).get("analysed"))).isEqualTo(200_000);

        // Steady: 30,000 more in the next ten minutes (50 a second), read by one run.
        attempts(hot, 200_001, 230_000, at.plus(Duration.ofSeconds(1)), at.plus(Duration.ofMinutes(10)));
        timed("hot run (30,000 in ten minutes)", at.plus(Duration.ofMinutes(15)));
        assertThat(count(cursor(hot).get("analysed"))).isEqualTo(230_000);
        timed("quiet run (nothing new)", at.plus(Duration.ofMinutes(25)));
        assertThat(String.valueOf(cursor(hot).get("questions"))).isEqualTo(recount(hot));

        // The sponsor's templates over a thousand publications.
        Instant now = at.plus(Duration.ofMinutes(26));
        template("dashboard", () -> dashboard(sponsorToken(sponsor), now));
        template("trend, all, 7 days by day", () -> trend(sponsorToken(sponsor), null,
            now.minus(Duration.ofDays(7)), now, "DAY"));
        template("trend, hot, 24 hours by hour", () -> trend(sponsorToken(sponsor), hot,
            now.minus(Duration.ofDays(1)), now, "HOUR"));
        template("top", () -> rows(TOP, sponsorToken(sponsor), Map.of("at", now.toString())));
        template("overview", () -> overview(sponsorToken(sponsor), hot));
        template("questions", () -> questions(sponsorToken(sponsor), hot));

        // The end and its recount of all 230,000.
        execute("UPDATE qb_publication_card SET status = 'ENDED', ended_at = ? WHERE publication_id = ?::uuid",
            java.sql.Timestamp.from(now), hot);
        timed("recount of 230,000 after the end", now.plus(Duration.ofMinutes(40)));
        assertThat(cursor(hot).get("finalized_at")).isNotNull();
    }
}
