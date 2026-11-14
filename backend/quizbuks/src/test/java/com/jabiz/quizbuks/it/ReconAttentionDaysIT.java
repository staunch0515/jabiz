package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b, point 6): for how many days an item for attention has been found in
 * a row, as the reconciliation page shows it. An operator's distribution price outside the headquarters' range is
 * found by the daily reconciliation on three days in a row (0 days on the first, 2 on the third), is put right for a
 * day, and found again: 0 days again. Each day of this class is its own (the runs are one per scheduled time).
 */
class ReconAttentionDaysIT extends TopUpItSupport {

    static final String TEMPLATE = "qb.admin.recon-attention-days";

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    /** Day n's reconciliation (18:37 UTC); the days of this class are a month away from those of the task tests. */
    static Instant day(int n) {
        return Instant.parse("2032-03-01T18:37:00Z").plus(Duration.ofDays(n));
    }

    /** The run of day n, made. */
    String reconcile(int n) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobs.run(reconcileJob, day(n));
        return String.valueOf(query("SELECT run_id FROM qb_recon_run_version WHERE scheduled_time = ?",
            Timestamp.from(day(n))).getFirst().get("run_id"));
    }

    static void price(String operator, String rate) {
        execute("INSERT INTO qb_distribution_price (price_id, operator_id, rate, unit_floor, unit_ceiling, "
            + "minimum_deposit, reason, updated_at, updated_by, version) VALUES (?, ?, ?, 1, 20, 500, 'F-M14', now(), "
            + "'it', 1) ON CONFLICT (operator_id) DO UPDATE SET rate = EXCLUDED.rate, version = "
            + "qb_distribution_price.version + 1", UUID.randomUUID().toString(), operator, new BigDecimal(rate));
    }

    /** The days of every object of the checks for attention in a run, by check and object. */
    Map<String, Integer> days(String runId) {
        String token = TestTokens.bearer(tokens, "fm14-days", QbPermissions.RECONCILE);
        List<Map<String, Object>> rows = items(TEMPLATE, token, Map.of("params", Map.of("runId", runId,
            "checks", List.copyOf(ReconChecks.ATTENTION)), "limit", 100));
        return rows.stream().collect(java.util.stream.Collectors.toMap(
            r -> r.get("checkCode") + ":" + r.get("objectKey"), r -> ((Number) r.get("days")).intValue()));
    }

    @Test
    void anItemIsCountedFromTheFirstRunOfItsUnbrokenStreak() {
        String operator = "op-fm14-days";
        String key = ReconChecks.DISTRIBUTION_PRICE_RANGE + ":" + operator;
        price(operator, "2");
        assertThat(days(reconcile(1))).containsEntry(key, 0);
        assertThat(days(reconcile(2))).containsEntry(key, 1);
        assertThat(days(reconcile(3))).containsEntry(key, 2);
        // Put right: the next run does not find it, so it is not listed at all.
        price(operator, "0.05");
        assertThat(days(reconcile(4))).doesNotContainKey(key);
        // Wrong again: a new streak.
        price(operator, "2");
        assertThat(days(reconcile(5))).containsEntry(key, 0);
        assertThat(days(reconcile(6))).containsEntry(key, 1);
        // An earlier run still reads its own streak: the days are those up to the run asked about.
        String third = String.valueOf(query("SELECT run_id FROM qb_recon_run_version WHERE scheduled_time = ?",
            Timestamp.from(day(3))).getFirst().get("run_id"));
        assertThat(days(third)).containsEntry(key, 2);
        // Only the checks for attention are asked about.
        assertThat(days(third).keySet()).allMatch(k -> ReconChecks.ATTENTION.contains(k.substring(0, k.indexOf(':'))));
    }
}
