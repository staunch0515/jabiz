package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers that do not simply succeed (docs/quizbuks/plans/Q6-payments.md, D-Q6-12): a creation Stripe refuses is
 * certain (no transfer): the sending is reversed and the money is back on the taker's balance; an insufficient balance
 * is tried again until it passes; anything else leaves the transfer in doubt ({@code CHECK}, a task, nothing undone)
 * until Stripe is asked by the group: found, it is settled (once); certainly not there, it is reversed. A creation whose
 * answer was lost is found by the next attempt's lookup and never made twice. After three failures in a row the taker
 * is left for a person.
 */
class TransferFailureIT extends PayoutItSupport {

    @BeforeEach
    void transfers() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
    }

    /** Runs the jobs until none is left, waiting out their retries (at most a few hours apart). */
    void exhaustJobs() {
        for (int round = 0; round < 12; round++) {
            runJobs();
            advance(Duration.ofHours(7));
        }
        runJobs();
    }

    String sendingTransfer(String taker, long amount) {
        enabledPayee(taker);
        earn(taker, amount);
        runTransfers();
        Map<String, Object> created = transfersOf(taker).getLast();
        assertThat(created).containsEntry("status", "SENDING");
        return (String) created.get("transfer_id");
    }

    @Test
    void aRefusedCreationIsReversedAndTheMoneyIsBack() {
        String taker = payee("t-fail-declined");
        String transferId = sendingTransfer(taker, 2000);
        StripeSim.target = "qb-" + transferId;
        StripeSim.REFUSE.set(1);
        runJobs();
        Map<String, Object> failed = transfer(transferId);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("failure_code", "STRIPE_DECLINED");
        assertThat(failed.get("reversal_transaction_id")).isNotNull();
        assertThat(ledger("2120", taker)).isEqualByComparingTo("2000");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);
        assertThat(outboxEvents("qb.transfer.failed", "transferId", transferId)).isEqualTo(1);
        assertBooksBalance();
    }

    @Test
    void anInsufficientBalanceIsTriedAgainUntilItPasses() {
        String taker = payee("t-fail-balance");
        String transferId = sendingTransfer(taker, 1200);
        StripeSim.target = "qb-" + transferId;
        StripeSim.refuseCode = "balance_insufficient";
        StripeSim.REFUSE.set(2);
        exhaustJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        assertThat(transferCalls(transferId).stream().filter(r -> "POST".equals(r.method()))).hasSize(3);
        assertThat(StripeSim.TRANSFERS).containsKey("qb-" + transferId);
        assertTakerCacheIsTheLedger(taker);
    }

    @Test
    void aLostAnswerIsFoundByTheNextAttemptAndNeverSentTwice() {
        String taker = payee("t-fail-lost");
        String transferId = sendingTransfer(taker, 1300);
        StripeSim.target = "qb-" + transferId;
        StripeSim.LOSE_ANSWER.set(1);
        exhaustJobs();
        Map<String, Object> sent = transfer(transferId);
        assertThat(sent).containsEntry("status", "SENT");
        // Created once: the second attempt looked the group up and found it.
        assertThat(transferCalls(transferId)).extracting(r -> r.method())
            .containsExactly("GET", "POST", "GET");
        assertThat(sent.get("stripe_transfer_id")).isEqualTo(StripeSim.TRANSFERS.get("qb-" + transferId).get("id"));
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);
    }

    @Test
    void aTransferInDoubtIsAskedAboutAndSettledWhenStripeHasIt() {
        String taker = payee("t-fail-doubt-found");
        String transferId = sendingTransfer(taker, 1400);
        StripeSim.target = "qb-" + transferId;
        // Made, its answer lost, and every lookup after it fails: the attempts run out in doubt.
        StripeSim.LOSE_ANSWER.set(1);
        runJobs();
        assertThat(StripeSim.TRANSFERS).containsKey("qb-" + transferId);
        StripeSim.LOOKUP_FAILS.set(100);
        exhaustJobs();
        Map<String, Object> doubt = transfer(transferId);
        assertThat(doubt).containsEntry("status", "CHECK");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("1400");
        assertThat(tasksAbout("transfer:" + transferId)).extracting(t -> t.get("title_key"))
            .contains("task.qb.transfer-check.UNCERTAIN");
        // The hourly check asks Stripe by the group: there it is, settled once.
        StripeSim.LOOKUP_FAILS.set(0);
        runStaleTransfers(now());
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        assertThat(StripeSim.TRANSFERS.values()).filteredOn(t -> ("qb-" + transferId).equals(t.get("transfer_group")))
            .hasSize(1);
        assertThat(ledger("2130", taker)).isEqualByComparingTo("0");
        assertTakerCacheIsTheLedger(taker);
        assertBooksBalance();
    }

    @Test
    void aTransferInDoubtThatStripeDoesNotHaveIsReversed() {
        String taker = payee("t-fail-doubt-none");
        String transferId = sendingTransfer(taker, 1500);
        StripeSim.target = "qb-" + transferId;
        StripeSim.UNAVAILABLE.set(100);
        exhaustJobs();
        assertThat(transfer(transferId)).containsEntry("status", "CHECK");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
        StripeSim.UNAVAILABLE.set(0);
        runStaleTransfers(now());
        runJobs();
        Map<String, Object> failed = transfer(transferId);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("failure_code", "NOT_AT_STRIPE");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("1500");
        assertThat(StripeSim.TRANSFERS).doesNotContainKey("qb-" + transferId);
        assertTakerCacheIsTheLedger(taker);
        // A transfer only just sending is not asked about yet.
        String other = payee("t-fail-just-sending");
        String fresh = sendingTransfer(other, 1000);
        runStaleTransfers(now());
        assertThat(transfer(fresh).get("check_requested_at")).isNull();
    }

    @Test
    void afterThreeFailuresInARowThePersonLooksFirst() {
        String taker = payee("t-fail-streak");
        enabledPayee(taker);
        earn(taker, 1000);
        for (int i = 0; i < 3; i++) {
            runTransfers();
            StripeSim.target = "qb-" + transfersOf(taker).getLast().get("transfer_id");
            StripeSim.REFUSE.set(1);
            runJobs();
            assertThat(transfersOf(taker).getLast()).containsEntry("status", "FAILED");
        }
        String last = (String) transfersOf(taker).getLast().get("transfer_id");
        assertThat(tasksAbout("transfer:" + last)).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.FAILURE_STREAK");
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(3);
        assertThat(ledger("2120", taker)).isEqualByComparingTo("1000");
    }
}
