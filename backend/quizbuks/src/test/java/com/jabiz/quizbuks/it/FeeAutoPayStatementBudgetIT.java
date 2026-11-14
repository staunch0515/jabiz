package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c: how many statements one run of the automatic payment sends. A run pays one invoice in one transaction (the
 * chain's next run is another), so the number is the same for the first invoice and the third; a run with nothing to
 * pay only reads. Each case runs once before it is counted (warm caches), as {@link CreditNoteStatementBudgetIT}. A
 * change of these paths changes the numbers on purpose: say why in the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class FeeAutoPayStatementBudgetIT extends PlayItSupport {

    /** The run that pays an invoice in full [14]: the lock, the switch, the one read of invoices, credits and wallet, the settlement's reads, writes and postings, the invoice's state. */
    static final int PAYS_ONE = 14;
    /** The run that pays the later invoices of a chain: the same statements as the first. */
    static final int PAYS_NEXT = 14;
    /** The run with nothing to pay [5]: the lock, the switch and the reads, nothing written (the third invoice of the chain was paid before it). */
    static final int NOTHING = 5;

    private static int seq;
    private static boolean sellOn;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13c budget"));
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

    String invoice(String sponsor, long total, int daysAgo) {
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(daysAgo));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', ?, "
            + "?, ?, ?, 0, 0, 'OPEN', ?, ?::date, ?::date)", id, "AB-" + (++seq) + "-" + id.substring(0, 4), sponsor,
            (2200 + seq / 12) + "-" + String.format("%02d", seq % 12 + 1), total - total / 11, total / 11, total,
            java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    /** The statements of the one transaction a run of the automatic payment sent. */
    List<String> run(String sponsor) {
        SqlStatementLog.clear();
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        List<List<String>> transactions = SqlStatementLog.transactions();
        assertThat(transactions).as("one transaction").hasSize(1);
        return transactions.getFirst();
    }

    @Test
    void aRunPaysOneInvoiceInOneTransactionWhateverItsPlaceInTheChain() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        List<String> first = null;
        List<String> next = null;
        List<String> nothing = null;
        for (int i = 0; i < 2; i++) {
            String sponsor = newSponsor(unique("s-fm13c-budget-" + i));
            invoice(sponsor, 1100, 9);
            invoice(sponsor, 1100, 6);
            invoice(sponsor, 1100, 3);
            cash(sponsor, 3300);
            first = run(sponsor);
            next = run(sponsor);
            run(sponsor);
            nothing = run(sponsor);
        }
        DistributionStatementBudgetIT.print("pays one", first);
        DistributionStatementBudgetIT.print("pays the next", next);
        DistributionStatementBudgetIT.print("nothing to pay", nothing);
        assertThat(first).hasSize(PAYS_ONE);
        assertThat(next).hasSize(PAYS_NEXT);
        assertThat(nothing).hasSize(NOTHING);
    }
}
