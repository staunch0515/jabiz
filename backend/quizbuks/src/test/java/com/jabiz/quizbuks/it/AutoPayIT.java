package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13b: unpaid platform invoices are paid by themselves from the fee account when the switch is published, the
 * earliest first, as far as the account goes, through the same payment as the sponsor's own.
 */
class AutoPayIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13b test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private static boolean sellOn;

    void cash(String sponsor, long amount) {
        if (!sellOn) {
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            sellOn = true;
        }
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    /** An unpaid monthly invoice of 1100 (1000 and 10 % tax): the sponsor has nothing yet to pay it with. */
    String unpaidInvoice(String sponsor) {
        param(QbParams.FEE_MONTHLY, "1000");
        Object outcome = ((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM,
            body("sponsorId", sponsor, "period", FeeRules.period(clock.instant(), TOKYO)))).outcome();
        assertThat(outcome).isEqualTo("CHARGED");
        Map<String, Object> invoice = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ?", sponsor).getFirst();
        assertThat(QbFees.UNPAID).contains(String.valueOf(invoice.get("status")));
        return String.valueOf(invoice.get("invoice_id"));
    }

    Map<String, Object> invoice(String id) {
        return query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", id).getFirst();
    }

    FeeAutoPay.AutoPayOutput autoPay(String sponsor) {
        return (FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
    }

    @Test
    void nothingIsPaidUntilTheSwitchIsPublishedThenTheAccountPaysAsFarAsItGoes() {
        String sponsor = newSponsor(unique("s-fm13b-pay"));
        String invoiceId = unpaidInvoice(sponsor);
        cash(sponsor, 400);

        // Not published: a parameter nobody turned on has no value, nothing is paid.
        assertThat(autoPay(sponsor).outcome()).isEqualTo("OFF");
        assertThat(invoice(invoiceId).get("paid_amount")).satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("0"));

        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        FeeAutoPay.AutoPayOutput first = autoPay(sponsor);
        assertThat(first.outcome()).isEqualTo("PAID");
        assertThat(first.total()).isEqualByComparingTo("400");
        assertThat(invoice(invoiceId)).containsEntry("status", QbFees.PARTIAL);
        // Nothing left to pay with: a second run does nothing.
        assertThat(autoPay(sponsor).outcome()).isEqualTo("NONE");

        cash(sponsor, 1000);
        assertThat(autoPay(sponsor).total()).isEqualByComparingTo("700");
        assertThat(invoice(invoiceId)).containsEntry("status", QbFees.PAID);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("300");
        // Paid in full: the invoice is not paid twice.
        assertThat(autoPay(sponsor).outcome()).isEqualTo("NONE");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("300");
    }

    @Test
    void theDailyJobsSponsorsAreThoseWithSomethingUnpaidAndSomethingToPayItWith() {
        String sponsor = newSponsor(unique("s-fm13b-due"));
        unpaidInvoice(sponsor);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String system = com.jabiz.runtime.test.TestTokens.bearer(tokens, "fm13b-job", "qb.fee.system");
        assertThat(items(FeeAutoPay.DUE_TEMPLATE, system, Map.of("params", Map.of("at", clock.instant().toString()))))
            .noneMatch(row -> sponsor.equals(row.get("sponsorId")));
        cash(sponsor, 100);
        assertThat(items(FeeAutoPay.DUE_TEMPLATE, system, Map.of("params", Map.of("at", clock.instant().toString()))))
            .anyMatch(row -> sponsor.equals(row.get("sponsorId")));
    }

    BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }
}
