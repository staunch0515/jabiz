package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.ReconChecks;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c: the daily checks that watch the automatic payment: {@code INVOICE_PAID_SUM} (an invoice's paid amount and
 * credit against its parts and uses) and {@code AUTO_PAY_MISSED} (an unpaid invoice with money on the account while
 * the switch is on). Its own class: the invoices are written by hand, which makes the books differ for the whole schema.
 */
class AutoPayReconIT extends PlayItSupport {

    private static int seq;

    String invoice(String sponsor, long total, int hoursAgo, long creditApplied) {
        String id = UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofHours(hoursAgo));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', ?, "
            + "?, ?, ?, 0, ?, 'OPEN', ?, ?::date, ?::date)", id, "AR-" + (++seq) + "-" + id.substring(0, 4), sponsor,
            (2100 + seq / 12) + "-" + String.format("%02d", seq % 12 + 1), total - total / 11, total / 11, total,
            creditApplied, java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    private static boolean sellOn;

    void cash(String sponsor, long amount) {
        if (!sellOn) {
            Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
                "values", Map.of("paramKey", QbParams.KUDOS_SELL_ENABLED, "value", "true"), "reason", "F-M13c test"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
            sellOn = true;
        }
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    void switchOn(String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", "qb.fee.auto-pay.enabled", "value", value), "reason", "F-M13c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    List<Map<String, Object>> found(String sponsor, String check) {
        return found(sponsor, check, 0);
    }

    /** What the check finds {@code hoursLater} hours from now (the clock stays: only the check's time moves). */
    List<Map<String, Object>> found(String sponsor, String check, int hoursLater) {
        String system = TestTokens.bearer(tokens, "recon-test", QbPermissions.WALLET_SYSTEM);
        return feeAccountItems(system, Map.of("params", Map.of("at",
            clock.instant().plus(java.time.Duration.ofHours(hoursLater)).toString(), "refundHours", 24,
            "missedHours", 48))).stream().filter(row -> sponsor.equals(row.get("sponsorId"))
            && check.equals(row.get("checkCode"))).toList();
    }

    @Test
    void anInvoiceWhoseCreditAppliedHasNoUseIsADifferenceAndOneWithoutPartsOrCreditIsNot() {
        String sponsor = newSponsor(unique("s-fm13c-sum"));
        invoice(sponsor, 1100, 1, 0);
        assertThat(found(sponsor, ReconChecks.INVOICE_PAID_SUM)).isEmpty();
        invoice(sponsor, 1100, 1, 300);
        assertThat(found(sponsor, ReconChecks.INVOICE_PAID_SUM)).singleElement().satisfies(row -> {
            assertThat(String.valueOf(row.get("reference"))).endsWith(":credit");
            assertThat(new BigDecimal(String.valueOf(row.get("expected")))).isEqualByComparingTo("300");
            assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("0");
        });
    }

    @Test
    void anUnpaidInvoiceWithMoneyOnTheAccountIsReportedOnlyWhileTheSwitchIsOnAfterTheHoursAndOfMoneyThatWasThereAsLong() {
        String sponsor = newSponsor(unique("s-fm13c-missed"));
        invoice(sponsor, 1100, 72, 0);
        cash(sponsor, 500);
        // The switch off (the first launch): nothing to expect of the nightly payment.
        assertThat(found(sponsor, ReconChecks.AUTO_PAY_MISSED, 60)).isEmpty();
        switchOn("true");
        try {
            // The money arrived just now: what comes with no event (a release at 03:23) waits for the night's run.
            assertThat(found(sponsor, ReconChecks.AUTO_PAY_MISSED, 30)).isEmpty();
            // The money has been there longer than the hours and the invoice is unpaid: a miss.
            assertThat(found(sponsor, ReconChecks.AUTO_PAY_MISSED, 60)).singleElement().satisfies(row ->
                assertThat(new BigDecimal(String.valueOf(row.get("actual")))).isEqualByComparingTo("1"));
            // Nothing on the account: not the payment's to do.
            String broke = newSponsor(unique("s-fm13c-broke"));
            invoice(broke, 1100, 72, 0);
            assertThat(found(broke, ReconChecks.AUTO_PAY_MISSED, 60)).isEmpty();
            // An invoice younger than the hours: the nightly run may not have come yet.
            String young = newSponsor(unique("s-fm13c-young"));
            invoice(young, 1100, 0, 0);
            cash(young, 500);
            assertThat(found(young, ReconChecks.AUTO_PAY_MISSED, 30)).isEmpty();
        } finally {
            switchOn("false");
        }
    }
}
