package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13b: the switch is never published here (this class publishes nothing of it): a parameter nobody turned on has no
 * value, and no value is off. The run pays nothing and the daily job has no item.
 */
class AutoPayOffIT extends PlayItSupport {

    @Test
    void aSwitchNobodyPublishedPaysNothingAndTheDailyJobTakesNoItem() {
        String sponsor = newSponsor(unique("s-fm13b-off"));
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(5));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', '2026-01', 1000, "
            + "100, 1100, 0, 'OPEN', ?, ?::date, ?::date)", id, "OFF-" + id.substring(0, 6), sponsor,
            java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.KUDOS_SELL_ENABLED, "value", "true"), "reason", "F-M13b test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        credit(sponsor, 5000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 5000));

        assertThat(((FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor))).outcome())
            .isEqualTo("OFF");
        assertThat(query("SELECT status, paid_amount FROM qb_fee_invoice WHERE invoice_id = ?", id).getFirst())
            .containsEntry("status", "OPEN");
        assertThat((BigDecimal) query("SELECT paid_amount FROM qb_fee_invoice WHERE invoice_id = ?", id).getFirst()
            .get("paid_amount")).isEqualByComparingTo("0");
        // The daily job's template reads the switch as the run does: nothing, whoever has money and an unpaid invoice.
        String system = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm13b-off", "qb.fee.system");
        assertThat(items(FeeAutoPay.DUE_TEMPLATE, system, Map.of("params", Map.of("at", clock.instant().toString()))))
            .isEmpty();
        assertThat(QbFees.UNPAID).contains("OPEN");
    }
}
