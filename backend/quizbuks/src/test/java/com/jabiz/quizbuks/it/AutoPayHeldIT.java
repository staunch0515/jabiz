package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c: what the account holds for a publication (the cash held) is not the automatic payment's to spend: it pays as
 * far as the free cash goes and leaves the held part and its lots as they were. Its own class: the invoice is written
 * by hand.
 */
class AutoPayHeldIT extends PlayItSupport {

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    void theCashHeldForAPublicationIsLeftAlone() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-held"));
        String invoiceId = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(5));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', "
            + "'2098-01', 1000, 100, 1100, 0, 0, 'OPEN', ?, ?::date, ?::date)", invoiceId, "AH-" + sponsor.substring(0, 8),
            sponsor, java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        credit(sponsor, 1000);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 1000));
        // 800 of the cash is held (a prepayment's, a dispute's): only 200 is free.
        execute("UPDATE qb_wallet SET held = 800 WHERE owner_id = ? AND kind = 'CASH'", sponsor);
        FeeAutoPay.AutoPayOutput out = (FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        assertThat(out.paid()).isEqualByComparingTo("200");
        Map<String, Object> wallet = query("SELECT balance, held FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'",
            sponsor).getFirst();
        assertThat((BigDecimal) wallet.get("balance")).isEqualByComparingTo("800");
        assertThat((BigDecimal) wallet.get("held")).isEqualByComparingTo("800");
        assertThat(query("SELECT status, paid_amount FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId).getFirst())
            .containsEntry("status", QbFees.PARTIAL);
        // Nothing free is left: a second run pays nothing.
        assertThat(((FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor))).outcome())
            .isEqualTo("NONE");
        param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
    }
}
