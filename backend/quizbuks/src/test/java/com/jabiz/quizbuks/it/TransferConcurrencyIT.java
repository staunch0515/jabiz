package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transfers, syncs and rewards at the same time (docs/quizbuks/plans/Q6-payments.md, D-Q6-11, D-Q6-15): every transfer
 * process holds the taker's wallet lock and nothing else, so creations of the same taker run one after the other and
 * never wait in a circle; at most one transfer of a taker is unfinished (also a unique index); what a transfer takes
 * is the ledger's balance at that moment, never more; and the cache ends where the ledger is.
 */
class TransferConcurrencyIT extends PayoutItSupport {

    @Test
    void creationsSyncsAndRewardsAtOnceNeverTransferTwiceOrMoreThanTheBalance() throws Exception {
        param(QbParams.TRANSFER_FEE, "0");
        param(QbParams.TRANSFER_REVIEW_ABOVE, "50000");
        transfersOn();
        // One of each first, alone: the first use of a type is not the subject here.
        String warm = payee("t-race-warm");
        enabledPayee(warm);
        earn(warm, 1000);
        systemRun("QB_TRANSFER_CREATE", body("userId", warm));

        List<String> takers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String taker = payee("t-race-" + i);
            enabledPayee(taker);
            earn(taker, 1000);
            takers.add(taker);
        }
        List<CompletableFuture<?>> all = new ArrayList<>();
        for (String taker : takers) {
            for (int i = 0; i < 3; i++) {
                all.add(CompletableFuture.runAsync(() -> systemRun("QB_TRANSFER_CREATE", body("userId", taker))));
            }
            all.add(CompletableFuture.runAsync(() -> systemRun("QB_TAKER_WALLET_SYNC", body("userId", taker))));
            all.add(CompletableFuture.runAsync(() -> earn(taker, 500)));
        }
        for (CompletableFuture<?> future : all) {
            future.get();
        }
        for (String taker : takers) {
            List<java.util.Map<String, Object>> transfers = transfersOf(taker);
            assertThat(transfers).as(taker).hasSize(1);
            BigDecimal amount = money(transfers.getFirst().get("amount"));
            // What it took is what the ledger had at its moment: 1000, or 1500 if the reward was in by then.
            assertThat(amount).as(taker).isIn(new BigDecimal("1000"), new BigDecimal("1500"));
            assertThat(ledger("2120", taker).add(amount)).as(taker).isEqualByComparingTo("1500");
            assertThat(ledger("2120", taker).signum()).isNotNegative();
            systemRun("QB_TAKER_WALLET_SYNC", body("userId", taker));
            assertTakerCacheIsTheLedger(taker);
        }
        // The index stands behind the processes: a second unfinished transfer cannot be written at all.
        String taker = takers.getFirst();
        assertThat(query("SELECT count(*) AS n FROM qb_transfer WHERE user_id = ? AND status IN "
            + "('REVIEW', 'SENDING', 'CHECK')", taker).getFirst().get("n")).isEqualTo(1L);
        runJobs();
        for (String each : takers) {
            assertThat(transfersOf(each).getFirst()).containsEntry("status", "SENT");
            assertTakerCacheIsTheLedger(each);
        }
        assertBooksBalance();
    }
}
