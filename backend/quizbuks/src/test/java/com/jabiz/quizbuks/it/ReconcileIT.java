package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The daily reconciliation (F-06; docs/quizbuks/plans/Q6-payments.md, D-Q6-8): the sponsors' wallets, their pending
 * rewards, the totals and each top-up's postings against the ledger. A clean ledger is {@code OK} without a task; a
 * cache changed behind the processes' back is a {@code DIFF} with its items, a task and an event; settlements while it
 * runs make no false difference; a scheduled time runs once; the ledger and the results are only ever inserted into.
 */
class ReconcileIT extends TopUpItSupport {

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    JobRunner.Outcome reconcile(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobs.run(reconcileJob, at);
    }

    static Map<String, Object> run(Instant scheduled) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_recon_run_version WHERE scheduled_time = ?",
            java.sql.Timestamp.from(scheduled));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static List<Map<String, Object>> items(Object runId) {
        return query("SELECT * FROM qb_recon_item_version WHERE run_id = ?::uuid ORDER BY check_code, party_id",
            String.valueOf(runId));
    }

    /** Some of everything: a paid top-up with a fee and a refund, rewards paid and pending. */
    void business(String name) {
        String sponsor = newSponsor("s-recon-" + name);
        String topUpId = openTopUp(sponsor, 1000);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        deliver(event(eventId(), "charge.refunded", Map.of("id", "ch_r" + name, "payment_intent", paymentOf(topUpId),
            "amount", 1000, "amount_refunded", 100, "currency", "jpy", "livemode", false)));
        String publicationId = livePublication(sponsor, "Recon " + name, Map.of("regions", List.of("GLOBAL")));
        String first = (String) start(taker(newTaker("t-recon-" + name + "-1", null)), publicationId)
            .get("attemptId");
        play(newTaker("t-recon-" + name + "-0", null), publicationId, true, true);
        assertThat(attempt(first)).containsEntry("status", "IN_PROGRESS");
        // Settled rewards refresh the takers' wallet caches by event (D-Q6-11); undelivered, the lag is a note.
        deliverEvents();
    }

    @Test
    void aCleanLedgerIsOkAndADamagedCacheIsADifference() {
        business("clean");
        Instant first = now().plus(Duration.ofMinutes(1));
        assertThat(reconcile(first)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> ok = run(first);
        assertThat(ok).containsEntry("status", "OK");
        assertThat(((Number) ok.get("item_count")).intValue()).isZero();
        assertThat(tasksAbout("recon:")).isEmpty();

        // The same scheduled time again: nothing more.
        assertThat(reconcile(first)).isIn(JobRunner.Outcome.SUCCEEDED, JobRunner.Outcome.REPLAYED);
        assertThat(query("SELECT 1 FROM qb_recon_run_version WHERE scheduled_time = ?",
            java.sql.Timestamp.from(first))).hasSize(1);

        // Behind the processes' back: a wallet's balance and pending count, a top-up's refund.
        String sponsor = user("s-recon-clean");
        Map<String, Object> wallet = wallet(sponsor);
        String topUpId = query("SELECT topup_id FROM qb_topup WHERE sponsor_id = ?", sponsor).getFirst()
            .get("topup_id").toString();
        execute("UPDATE qb_wallet SET balance = balance + 7, pending_count = pending_count + 1 WHERE wallet_id = ?",
            wallet.get("wallet_id"));
        execute("UPDATE qb_topup SET refunded_amount = 150 WHERE topup_id = ?", topUpId);
        try {
            Instant second = first.plus(Duration.ofDays(1));
            reconcile(second);
            Map<String, Object> diff = run(second);
            assertThat(diff).containsEntry("status", "DIFF");
            assertThat((String) diff.get("summary")).contains("\"SPONSOR_WALLET\":1", "\"TOTAL_2110\":1",
                "\"SPONSOR_PENDING_COUNT\":1", "\"TOPUP_REFUNDS\":1", "\"LEDGER_ZERO\":0");
            List<Map<String, Object>> found = items(diff.get("run_id"));
            assertThat(found).extracting(i -> i.get("check_code")).containsExactlyInAnyOrder("SPONSOR_WALLET",
                "SPONSOR_PENDING_COUNT", "TOTAL_2110", "TOPUP_REFUNDS");
            assertThat(found).filteredOn(i -> "SPONSOR_WALLET".equals(i.get("check_code"))).singleElement()
                .satisfies(i -> {
                    assertThat(i).containsEntry("party_id", sponsor);
                    assertThat(money(i.get("difference"))).isEqualByComparingTo("7");
                });
            assertThat(found).filteredOn(i -> "TOPUP_REFUNDS".equals(i.get("check_code"))).singleElement()
                .satisfies(i -> assertThat(i).containsEntry("reference", topUpId));
            assertThat(tasksAbout("recon:")).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("title_key", "task.qb.recon-diff").containsEntry("subject_id",
                    String.valueOf(diff.get("run_id"))));
            assertThat(outboxEvents("qb.reconcile.diff", "runId", String.valueOf(diff.get("run_id")))).isEqualTo(1);
            // The administrators' list.
            String finance = tokenOf("recon-finance", com.jabiz.quizbuks.setup.QbRoles.ADMIN_FINANCE);
            assertThat(items("qb.admin.recon-runs", finance, Map.of())).extracting(r -> r.get("status"))
                .contains("OK", "DIFF");
            assertThat(items("qb.admin.recon-items", finance, Map.of("params", Map.of("runId",
                String.valueOf(diff.get("run_id")))))).hasSize(4);
        } finally {
            execute("UPDATE qb_wallet SET balance = balance - 7, pending_count = pending_count - 1 "
                + "WHERE wallet_id = ?", wallet.get("wallet_id"));
            execute("UPDATE qb_topup SET refunded_amount = 100 WHERE topup_id = ?", topUpId);
        }
        assertWriteOnce();
    }

    @Test
    void settlementsWhileItRunsMakeNoFalseDifference() throws Exception {
        business("busy");
        warmUp();
        String sponsor = newSponsor("s-recon-busy-credit");
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writers = new ArrayList<>();
        try {
            for (int t = 0; t < 2; t++) {
                writers.add(pool.submit(() -> {
                    while (!done.get()) {
                        run("QB_WALLET_DEV_CREDIT", system(), body("sponsorId", sponsor, "amount", 1));
                    }
                    return null;
                }));
            }
            Instant at = now().plus(Duration.ofDays(30));
            for (int i = 0; i < 5; i++) {
                Instant scheduled = at.plus(Duration.ofMinutes(i));
                reconcile(scheduled);
                assertThat(run(scheduled)).as("run " + i).containsEntry("status", "OK");
            }
        } finally {
            done.set(true);
            for (Future<?> writer : writers) {
                writer.get();
            }
            pool.shutdown();
        }
        assertThat(balance(sponsor)).isPositive();
        assertBooksBalance();
    }

    /** The results and the ledger were only ever inserted into, and carry the append-only guard. */
    static void assertWriteOnce() {
        for (String table : List.of("qb_recon_run_version", "qb_recon_item_version", "ledger_transaction_version",
            "ledger_entry_version")) {
            assertThat(query("SELECT coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS changed "
                + "FROM pg_stat_user_tables WHERE schemaname = current_schema() AND relname = ?", table))
                .as(table).singleElement()
                .satisfies(row -> assertThat(((Number) row.get("changed")).longValue()).as(table).isZero());
            assertThat(query("SELECT 1 AS guarded FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() "
                + "AND c.relname = ? AND NOT t.tgisinternal", table)).as(table).isNotEmpty();
        }
    }
}
