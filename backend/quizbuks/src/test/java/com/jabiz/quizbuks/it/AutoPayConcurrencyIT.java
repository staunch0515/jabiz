package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c: the automatic payment and the sponsor paying by hand at the same moment take the same wallet lock, so the
 * invoice is paid once and the account is charged once, whoever comes second finds it paid. Its own class: the invoices
 * are written by hand.
 */
class AutoPayConcurrencyIT extends PlayItSupport {

    private static int seq;
    private static boolean sellOn;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    void cash(String sponsor, long amount) {
        if (!sellOn) {
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            sellOn = true;
        }
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    String invoice(String sponsor, long total) {
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(5));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', ?, "
            + "?, ?, ?, 0, 0, 'OPEN', ?, ?::date, ?::date)", id, "AC-" + (++seq) + "-" + id.substring(0, 4), sponsor,
            (2300 + seq / 12) + "-" + String.format("%02d", seq % 12 + 1), total - total / 11, total / 11, total,
            java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?", sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    @Test
    void theInvoiceIsPaidOnceWhenTheSponsorPaysAtTheMomentTheAutomaticPaymentRuns() throws Exception {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 4; round++) {
                String sponsor = newSponsor(unique("s-fm13c-race-" + round));
                String invoiceId = invoice(sponsor, 1100);
                // Money for one payment of the invoice and then some: a second payment would show as a lower balance.
                cash(sponsor, 1500);
                // The cash arrival may already have paid it by the event (not delivered here): nothing is paid yet.
                String token = sponsorToken(sponsor);
                CountDownLatch go = new CountDownLatch(1);
                Callable<Object> manual = () -> {
                    go.await();
                    try {
                        return run(FeeProcesses.PAY, token, body("invoiceId", invoiceId));
                    } catch (AssertionError | RuntimeException refused) {
                        return refused;
                    }
                };
                Callable<Object> auto = () -> {
                    go.await();
                    return systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
                };
                Future<Object> a = pool.submit(manual);
                Future<Object> b = pool.submit(auto);
                go.countDown();
                a.get();
                b.get();
                assertThat(query("SELECT status, paid_amount FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId))
                    .singleElement().satisfies(row -> {
                        assertThat(row).containsEntry("status", QbFees.PAID);
                        assertThat((BigDecimal) row.get("paid_amount")).isEqualByComparingTo("1100");
                    });
                assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("400");
                assertThat(query("SELECT sum(amount) AS n FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)
                    .getFirst().get("n")).satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("1100"));
            }
        } finally {
            pool.shutdownNow();
            param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
        }
    }
}
