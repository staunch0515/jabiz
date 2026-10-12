package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.PayoutObjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers that do not simply succeed (docs/quizbuks/plans/Q6-payments.md, D-Q6-12): a creation Stripe refuses is
 * certain (no transfer): the sending is reversed and the money is back on the taker's balance; an insufficient balance
 * too (Stripe keeps the refusal under the key: the next day's transfer has its own); anything else (a 500 kept under the
 * key, a 429) leaves the transfer in doubt ({@code CHECK}, a task, nothing undone) until Stripe is asked by the group:
 * found, it is settled (once); certainly not there, asked well after the sending ended, it is reversed. A creation
 * whose answer was lost is found by the next attempt's lookup and never made twice. After three failures of the
 * account in a row the taker is left for a person; the platform's own failures do not count.
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
    void anInsufficientBalanceIsCertainAndNotTheTakersFailure() {
        String taker = payee("t-fail-balance");
        enabledPayee(taker);
        earn(taker, 1200);
        // Stripe keeps a refusal under the key it answered: the same key can never pass, so it is certain at once and
        // the next day is a new transfer with a key of its own. The platform's balance is not the taker's failure.
        for (int i = 0; i < 3; i++) {
            runTransfers();
            String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
            StripeSim.target = "qb-" + transferId;
            StripeSim.refuseCode = "balance_insufficient";
            StripeSim.REFUSE.set(1);
            exhaustJobs();
            assertThat(transfer(transferId)).containsEntry("status", "FAILED")
                .containsEntry("failure_code", "STRIPE_BALANCE_INSUFFICIENT");
            assertThat(transferCalls(transferId).stream().filter(r -> "POST".equals(r.method()))).hasSize(1);
            assertThat(ledger("2120", taker)).isEqualByComparingTo("1200");
        }
        assertThat(tasksAbout("transfer:")).noneSatisfy(t -> assertThat(t.get("title_key"))
            .isEqualTo("task.qb.transfer-check.FAILURE_STREAK"));
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(4);
        String next = (String) transfersOf(taker).getLast().get("transfer_id");
        runJobs();
        assertThat(transfer(next)).containsEntry("status", "SENT");
        assertThat(StripeSim.TRANSFERS).containsKey("qb-" + next);
        assertTakerCacheIsTheLedger(taker);
    }

    @Test
    void aRateLimitIsNeverCertain() {
        String taker = payee("t-fail-rate");
        String transferId = sendingTransfer(taker, 1250);
        StripeSim.target = "qb-" + transferId;
        // A 429 says nothing about an earlier attempt: run out, it is in doubt, not reversed.
        StripeSim.RATE_LIMITED.set(100);
        exhaustJobs();
        assertThat(transfer(transferId)).containsEntry("status", "CHECK")
            .containsEntry("failure_code", "STRIPE_RATE_LIMITED");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("1250");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
    }

    @Test
    void aCheckWhoseLookupMayPrecedeTheLastAttemptReversesNothing() {
        String taker = payee("t-fail-check-race");
        String transferId = sendingTransfer(taker, 1600);
        StripeSim.target = "qb-" + transferId;
        StripeSim.UNAVAILABLE.set(1);
        exhaustJobs();
        assertThat(transfer(transferId)).containsEntry("status", "CHECK");
        // The hourly check started while the transfer was still sending; its lookup found nothing, and its answer is
        // handled only now, after the sending ended in doubt. Its "none" may predate a creation: nothing is undone.
        completeCheck(transferId, PayoutObjects.TransferLookup.none());
        assertThat(transfer(transferId)).containsEntry("status", "CHECK");
        Instant ended = ((java.sql.Timestamp) transfer(transferId).get("send_ended_at")).toInstant();
        completeCheck(transferId, PayoutObjects.TransferLookup.none(ended.minus(Duration.ofMinutes(5))));
        completeCheck(transferId, PayoutObjects.TransferLookup.none(ended.plus(Duration.ofMinutes(9))));
        assertThat(transfer(transferId)).containsEntry("status", "CHECK");
        assertThat(ledger("2130", taker)).isEqualByComparingTo("1600");
        assertThat(transferPostings(transferId)).hasSize(2);
        // Asked well after the sending ended: final.
        completeCheck(transferId, PayoutObjects.TransferLookup.none(ended.plus(Duration.ofMinutes(10))));
        assertThat(transfer(transferId)).containsEntry("status", "FAILED")
            .containsEntry("failure_code", "NOT_AT_STRIPE");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("1600");
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
        // A 500 is kept under the key too: the same key would only get it again. In doubt at once, asked about later.
        StripeSim.UNAVAILABLE.set(1);
        exhaustJobs();
        assertThat(transfer(transferId)).containsEntry("status", "CHECK")
            .containsEntry("failure_code", "STRIPE_UNAVAILABLE");
        assertThat(transferCalls(transferId).stream().filter(r -> "POST".equals(r.method()))).hasSize(1);
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
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
