package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The takers' wallet cache (docs/quizbuks/plans/Q6-payments.md, D-Q6-11) and its reconciliation (D-Q6-8): every reward
 * settled, at once or later, brings the taker's cache to the ledger by an event; repeated, reordered and concurrent syncs
 * agree; the daily reconciliation counts a taker behind only when two runs in a row find them (asking for a resync
 * meanwhile), and checks each transfer's postings against its state.
 */
class TakerWalletSyncIT extends PayoutItSupport {

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    JobRunner.Outcome reconcile(Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.run(reconcileJob, at);
    }

    static Map<String, Object> reconRun(Instant scheduled) {
        return query("SELECT * FROM qb_recon_run_version WHERE scheduled_time = ?",
            java.sql.Timestamp.from(scheduled)).getFirst();
    }

    static List<Map<String, Object>> reconItems(Object runId) {
        return query("SELECT * FROM qb_recon_item_version WHERE run_id = ?::uuid ORDER BY check_code, party_id",
            String.valueOf(runId));
    }

    @Test
    void rewardsPaidAtOnceOrLaterReachTheCache() {
        String sponsor = newSponsor("s-sync");
        String publicationId = livePublication(sponsor, "Sync", Map.of("regions", List.of("GLOBAL")));
        credit(sponsor, 100);
        String first = payee("t-sync-1");
        String second = payee("t-sync-2");
        String firstAttempt = (String) start(taker(first), publicationId).get("attemptId");
        String secondAttempt = (String) start(taker(second), publicationId).get("attemptId");
        assertThat(submit(taker(first), firstAttempt, answers(true, true))).containsEntry("rewardStatus", "PAID");
        assertThat(submit(taker(second), secondAttempt, answers(true, true))).containsEntry("rewardStatus",
            "PENDING");
        deliverEvents();
        assertThat(money(takerWallet(first).get("balance"))).isEqualByComparingTo("100");
        assertThat(takerWallet(second)).isNull();
        // The pending reward is paid once the sponsor tops up: the event of that payment syncs the second taker.
        credit(sponsor, 100);
        deliverEvents();
        assertTakerCacheIsTheLedger(first);
        assertTakerCacheIsTheLedger(second);
        assertThat(money(takerWallet(second).get("balance"))).isEqualByComparingTo("100");
    }

    @Test
    void repeatedAndConcurrentSyncsAgreeWithTheLedger() throws Exception {
        String taker = payee("t-sync-repeat");
        earn(taker, 700);
        execute("UPDATE qb_wallet SET balance = 5 WHERE owner_id = '" + taker + "' AND kind = 'TAKER'");
        List<CompletableFuture<Object>> syncs = IntStream.range(0, 4).mapToObj(i -> CompletableFuture.supplyAsync(
            () -> systemRun("QB_TAKER_WALLET_SYNC", body("userId", taker)))).toList();
        for (CompletableFuture<Object> sync : syncs) {
            sync.get();
        }
        assertTakerCacheIsTheLedger(taker);
        systemRun("QB_TAKER_WALLET_SYNC", body("userId", taker));
        assertTakerCacheIsTheLedger(taker);
        // A user with nothing in the ledger gets no row.
        String nobody = payee("t-sync-nobody");
        systemRun("QB_TAKER_WALLET_SYNC", body("userId", nobody));
        assertThat(takerWallet(nobody)).isNull();
    }

    @Test
    void aTakerBehindCountsWhenTwoRunsInARowFindThem() {
        param(QbParams.TRANSFER_ENABLED, "false");
        String taker = payee("t-sync-recon");
        earn(taker, 400);
        Instant first = now().plus(Duration.ofHours(1));
        // Behind the processes' back: the cache says less than the ledger.
        execute("UPDATE qb_wallet SET balance = 100 WHERE owner_id = '" + taker + "' AND kind = 'TAKER'");
        assertThat(reconcile(first)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> noted = reconRun(first);
        assertThat(noted).containsEntry("status", "OK");
        assertThat(reconItems(noted.get("run_id"))).filteredOn(i -> taker.equals(i.get("party_id")))
            .extracting(i -> i.get("check_code")).containsExactly("TAKER_WALLET_LAG");
        assertThat(query("SELECT 1 FROM sys_outbox_event WHERE event_type = 'qb.taker-wallet.resync' "
            + "AND jsonb_exists(payload::jsonb -> 'userIds', ?)", taker)).isNotEmpty();
        // The resync is not delivered (or does not help): the next run counts it.
        Instant second = first.plus(Duration.ofDays(1));
        advance(Duration.ofDays(1));
        assertThat(reconcile(second)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> counted = reconRun(second);
        assertThat(counted).containsEntry("status", "DIFF");
        assertThat(reconItems(counted.get("run_id"))).filteredOn(i -> taker.equals(i.get("party_id")))
            .extracting(i -> i.get("check_code")).containsExactly("TAKER_WALLET");
        // Delivered, the resync puts it right: the third run is clean for them.
        deliverEvents();
        assertTakerCacheIsTheLedger(taker);
        Instant third = second.plus(Duration.ofDays(1));
        advance(Duration.ofDays(1));
        reconcile(third);
        assertThat(reconItems(reconRun(third).get("run_id"))).noneMatch(i -> taker.equals(i.get("party_id")));
    }

    @Test
    void aTransfersPostingsAreCheckedAgainstItsState() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        String taker = payee("t-sync-transfer");
        enabledPayee(taker);
        earn(taker, 1100);
        runTransfers();
        runJobs();
        String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        param(QbParams.TRANSFER_ENABLED, "false");
        // Behind the processes' back: the record says still sending.
        execute("UPDATE qb_transfer SET status = 'SENDING' WHERE transfer_id = '" + transferId + "'");
        Instant at = now().plus(Duration.ofHours(2));
        reconcile(at);
        assertThat(reconItems(reconRun(at).get("run_id"))).filteredOn(i -> transferId.equals(i.get("reference")))
            .extracting(i -> i.get("check_code")).containsExactly("TRANSFER_IN_TRANSIT");
        execute("UPDATE qb_transfer SET status = 'SENT' WHERE transfer_id = '" + transferId + "'");
    }
}
