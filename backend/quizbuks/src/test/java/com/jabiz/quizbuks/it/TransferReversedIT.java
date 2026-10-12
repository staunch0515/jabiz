package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers Stripe reversed (docs/quizbuks/plans/Q6-payments.md, D-Q6-12): what was reversed comes back to the taker's
 * balance (debit 1110 / credit 2120), counted from Stripe's running total so that repeated and late events add nothing;
 * a reversal of a transfer whose settlement is not processed yet waits for it; what does not fit is a task.
 */
class TransferReversedIT extends PayoutItSupport {

    @BeforeEach
    void transfers() {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
    }

    String sentTransfer(String taker, long amount) {
        enabledPayee(taker);
        earn(taker, amount);
        runTransfers();
        String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        return transferId;
    }

    Map<String, Object> reversed(String transferId, long amountReversed) {
        Map<String, Object> transfer = new LinkedHashMap<>(StripeSim.TRANSFERS.get("qb-" + transferId));
        transfer.put("amount_reversed", amountReversed);
        transfer.put("reversed", amountReversed > 0);
        return event(eventId(), "transfer.reversed", transfer);
    }

    @Test
    void whatStripeReversedComesBackOnce() {
        String taker = payee("t-reversed");
        String transferId = sentTransfer(taker, 2000);
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
        Map<String, Object> part = reversed(transferId, 500);
        deliver(part);
        assertThat(ledger("2120", taker)).isEqualByComparingTo("500");
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        assertThat(money(transfer(transferId).get("reversed_amount"))).isEqualByComparingTo("500");
        assertThat(tasksAbout("stripe:" + part.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.REVERSED");
        assertTakerCacheIsTheLedger(taker);
        // Again, or late: nothing more.
        deliver(reversed(transferId, 500));
        deliver(reversed(transferId, 300));
        assertThat(ledger("2120", taker)).isEqualByComparingTo("500");
        // The rest: all of it reversed.
        deliver(reversed(transferId, 2000));
        assertThat(ledger("2120", taker)).isEqualByComparingTo("2000");
        assertThat(transfer(transferId)).containsEntry("status", "REVERSED");
        assertThat(transferPostings(transferId)).filteredOn(l -> "1110".equals(l.get("account_code"))
            && "DEBIT".equals(l.get("direction"))).hasSize(2);
        assertTakerCacheIsTheLedger(taker);
        // More than was sent: a task, nothing returned.
        Map<String, Object> over = reversed(transferId, 2001);
        deliver(over);
        assertThat(tasksAbout("stripe:" + over.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.OVER_REVERSAL");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("2000");
        assertBooksBalance();
        // What came back is not sent again until finance has looked: the taker is held.
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(1);
        releaseHold(taker);
        runTransfers();
        assertThat(transfersOf(taker)).hasSize(2);
    }

    @Test
    void reversalsThatDoNotFitAreTasks() {
        String taker = payee("t-reversed-odd");
        String transferId = sentTransfer(taker, 1500);
        Map<String, Object> live = reversed(transferId, 100);
        live.put("livemode", true);
        deliver(live);
        assertThat(tasksAbout("stripe:" + live.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.LIVEMODE");
        // Another transfer of Stripe naming this record in its metadata: not this record's transfer.
        Map<String, Object> other = reversed(transferId, 100);
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) ((Map<String, Object>) other.get("data")).get("object");
        Map<String, Object> forged = new LinkedHashMap<>(object);
        forged.put("id", "tr_someoneelse");
        Map<String, Object> event = event(eventId(), "transfer.reversed", forged);
        deliver(event);
        assertThat(tasksAbout("stripe:" + event.get("id"))).extracting(t -> t.get("title_key"))
            .containsExactly("task.qb.transfer-check.UNKNOWN_TRANSFER");
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
    }

    @Test
    void aReversalBeforeTheSettlementWaitsForIt() {
        String taker = payee("t-reversed-early");
        enabledPayee(taker);
        earn(taker, 1200);
        runTransfers();
        String transferId = (String) transfersOf(taker).getLast().get("transfer_id");
        // Stripe made the transfer (and reversed part of it) before QuizBuks processed the result.
        StripeSim.target = "qb-" + transferId;
        StripeSim.LOSE_ANSWER.set(1);
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENDING");
        deliver(reversed(transferId, 200));
        assertThat(query("SELECT 1 FROM sys_outbox_attempt WHERE consumer = 'qb.transfer-reversed'")).isNotEmpty();
        assertThat(ledger("2120", taker)).isEqualByComparingTo("0");
        // The next attempt finds the transfer and settles it; the reversal, tried again, then applies.
        advance(Duration.ofMinutes(10));
        runJobs();
        assertThat(transfer(transferId)).containsEntry("status", "SENT");
        // Within the wait for an unknown transfer (half an hour from its arrival), as the outbox retries it.
        for (int minute = 0; minute < 15 && ledger("2120", taker).signum() == 0; minute++) {
            advance(Duration.ofMinutes(1));
            deliverEvents();
        }
        assertThat(ledger("2120", taker)).isEqualByComparingTo("200");
        assertTakerCacheIsTheLedger(taker);
    }
}
