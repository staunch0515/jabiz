package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.quizbuks.wallet.ReconTasks;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.task.TaskPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14 (docs/quizbuks/plans/impl/F-M14.md §3.1b): the reconciliation's tasks. An item for attention (here an
 * operator's distribution price outside the headquarters' range) asks finance once per check and object: not again
 * while its task is open, again the day after a person closed it while it is still found, and its task is done once it
 * is no longer found, unless the run kept only part of its items. A difference's task is one a day: a later day's
 * cancels the earlier, a run without differences closes it as done. Neither job does anything on a day the
 * reconciliation did not run. Each day of this class is its own (the runs are one per scheduled time).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconTasksIT extends TopUpItSupport {

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    @Autowired
    @Qualifier("qbReconAttentionJob")
    JobDefinition<?> attentionJob;

    @Autowired
    @Qualifier("qbReconTasksCloseJob")
    JobDefinition<?> closeJob;

    /** Day n's reconciliation (18:37 UTC); its tasks' jobs run 15 minutes later. */
    static Instant day(int n) {
        return Instant.parse("2031-03-01T18:37:00Z").plus(Duration.ofDays(n));
    }

    JobRunner.Outcome run(JobDefinition<?> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobs.run(job, at);
    }

    /** The reconciliation of day n, then its tasks' two jobs (closing first, as both run at 18:52). */
    String reconcileDay(int n) {
        run(reconcileJob, day(n));
        afterwards(n);
        return status(n);
    }

    void afterwards(int n) {
        Instant at = day(n).plus(Duration.ofMinutes(15));
        run(closeJob, at);
        run(attentionJob, at);
    }

    static String status(int n) {
        List<Map<String, Object>> runs = query("SELECT status FROM qb_recon_run_version WHERE scheduled_time = ?",
            Timestamp.from(day(n)));
        return runs.isEmpty() ? null : String.valueOf(runs.getFirst().get("status"));
    }

    /** The latest version of each task of a source key. */
    static List<Map<String, Object>> taskRows(String sourceKey) {
        return query("SELECT DISTINCT ON (task_id) task_id, task_type, status, assignee_permission, title_params "
            + "FROM sys_task_version WHERE source_key = ? ORDER BY task_id, version_no DESC", sourceKey);
    }

    static List<Map<String, Object>> openTasks(String sourceKey) {
        return taskRows(sourceKey).stream().filter(t -> "OPEN".equals(t.get("status"))).toList();
    }

    static void price(String operator, String rate) {
        execute("INSERT INTO qb_distribution_price (price_id, operator_id, rate, unit_floor, unit_ceiling, "
            + "minimum_deposit, reason, updated_at, updated_by, version) VALUES (?, ?, ?, 1, 20, 500, 'F-M14', now(), "
            + "'it', 1) ON CONFLICT (operator_id) DO UPDATE SET rate = EXCLUDED.rate, version = "
            + "qb_distribution_price.version + 1", UUID.randomUUID().toString(), operator, new java.math.BigDecimal(rate));
    }

    static String attention(String operator) {
        return ReconTasks.sourceKey(ReconChecks.DISTRIBUTION_PRICE_RANGE, operator, null);
    }

    @Test
    @Order(1)
    void anItemForAttentionAsksOnceAndIsDoneWhenGone() {
        String operator = "op-fm14-attn";
        price(operator, "2");
        // The run stays OK: a price outside the range needs attention, it is not a difference.
        assertThat(reconcileDay(1)).isEqualTo(ReconChecks.OK);
        assertThat(openTasks(attention(operator))).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("task_type", ReconTasks.ATTENTION_TASK)
                .containsEntry("assignee_permission", QbPermissions.RECONCILE);
            assertThat(String.valueOf(task.get("title_params"))).contains(ReconChecks.DISTRIBUTION_PRICE_RANGE)
                .contains(operator);
        });
        assertThat(query("SELECT 1 FROM sys_task_version WHERE source_key LIKE 'recon:%'")).isEmpty();

        // Still found the next day: the open task stands, none more.
        assertThat(reconcileDay(2)).isEqualTo(ReconChecks.OK);
        assertThat(taskRows(attention(operator))).hasSize(1);
        // The attention job run again for the same time asks nothing more either.
        run(attentionJob, day(2).plus(Duration.ofMinutes(16)));
        assertThat(taskRows(attention(operator))).hasSize(1);

        // A person closes it while the price is still outside: asked again the next day.
        Object taskId = openTasks(attention(operator)).getFirst().get("task_id");
        run("TASK_RESOLVE", TestTokens.bearer(tokens, "fm14-finance", TaskPermissions.RESOLVE, QbPermissions.RECONCILE),
            body("taskId", String.valueOf(taskId), "outcome", "DONE", "note", "Looked at it"));
        assertThat(openTasks(attention(operator))).isEmpty();
        reconcileDay(3);
        assertThat(openTasks(attention(operator))).hasSize(1);
        assertThat(taskRows(attention(operator))).hasSize(2);

        // Back within the range: the next run no longer finds it, its task is done.
        price(operator, "0.05");
        reconcileDay(4);
        assertThat(openTasks(attention(operator))).isEmpty();
        assertThat(taskRows(attention(operator))).extracting(t -> t.get("status")).containsOnly("DONE");
    }

    @Test
    @Order(2)
    void aDifferencesTaskIsOneADayAndDoneOnceNoneIsLeft() {
        String sponsor = newSponsor("s-fm14-diff");
        credit(sponsor, 500);
        // A cache changed behind the processes' back: two checks differ (the wallet and the total of 2110).
        execute("UPDATE qb_wallet SET balance = balance + 7 WHERE owner_id = ? AND kind = 'SPONSOR'", sponsor);
        assertThat(reconcileDay(10)).isEqualTo(ReconChecks.DIFF);
        String first = "recon:" + day(10).toString().substring(0, 10);
        assertThat(openTasks(first)).singleElement().satisfies(task -> assertThat(String.valueOf(task.get("title_params")))
            .contains("\"checks\":\"2\""));
        // Still there the next day: that day's task replaces the first (cancelled).
        assertThat(reconcileDay(11)).isEqualTo(ReconChecks.DIFF);
        String second = "recon:" + day(11).toString().substring(0, 10);
        assertThat(taskRows(first)).extracting(t -> t.get("status")).containsExactly("CANCELLED");
        assertThat(openTasks(second)).hasSize(1);
        // Put right: the next run is OK and the open task is done.
        execute("UPDATE qb_wallet SET balance = balance - 7 WHERE owner_id = ? AND kind = 'SPONSOR'", sponsor);
        assertThat(reconcileDay(12)).isEqualTo(ReconChecks.OK);
        assertThat(taskRows(second)).extracting(t -> t.get("status")).containsExactly("DONE");
    }

    @Test
    @Order(3)
    void withoutTheDaysRunNothingIsAskedOrClosed() {
        String operator = "op-fm14-guard";
        price(operator, "3");
        reconcileDay(20);
        assertThat(openTasks(attention(operator))).hasSize(1);
        // Day 21: no reconciliation. The price is put right, but nothing closes; a new one is not asked about.
        price(operator, "0.05");
        price("op-fm14-guard-2", "3");
        afterwards(21);
        assertThat(status(21)).isNull();
        assertThat(openTasks(attention(operator))).hasSize(1);
        assertThat(taskRows(attention("op-fm14-guard-2"))).isEmpty();
        // Day 22 runs again: the first is done, the second asked about.
        reconcileDay(22);
        assertThat(openTasks(attention(operator))).isEmpty();
        assertThat(openTasks(attention("op-fm14-guard-2"))).hasSize(1);
        price("op-fm14-guard-2", "0.05");
        reconcileDay(23);
        assertThat(openTasks(attention("op-fm14-guard-2"))).isEmpty();
    }

    /** Last: it leaves many prices outside the range. */
    @Test
    @Order(4)
    void aRunThatKeptOnlyPartOfItsItemsClosesNothing() {
        String operator = "op-fm14-trunc";
        price(operator, "2");
        reconcileDay(30);
        assertThat(openTasks(attention(operator))).hasSize(1);
        // Put right, while more prices than a run keeps are outside the range: the run kept only part of its items,
        // so not finding this one does not mean it is gone.
        price(operator, "0.05");
        execute("INSERT INTO qb_distribution_price (price_id, operator_id, rate, unit_floor, unit_ceiling, "
            + "minimum_deposit, reason, updated_at, updated_by, version) SELECT gen_random_uuid()::text, "
            + "'op-fm14-many-' || n, 2, 1, 20, 500, 'F-M14', now(), 'it', 1 FROM generate_series(1, 1005) n");
        reconcileDay(31);
        Map<String, Object> run = query("SELECT run_id, item_count FROM qb_recon_run_version WHERE scheduled_time = ?",
            Timestamp.from(day(31))).getFirst();
        assertThat(((Number) run.get("item_count")).intValue()).isGreaterThan(1000);
        assertThat(openTasks(attention(operator))).hasSize(1);
        // At most 200 asked a run.
        assertThat(query("SELECT DISTINCT task_id FROM sys_task_version WHERE source_key LIKE "
            + "'recon-attn:DISTRIBUTION_PRICE_RANGE:op-fm14-many-%'")).hasSize(ReconTasks.MAX_ITEMS);
    }
}
