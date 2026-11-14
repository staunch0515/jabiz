package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c: an erased sponsor's account is wound up by {@code MoneyClose}; the automatic payment leaves it alone, on the
 * event's path ({@code QB_FEE_AUTO_PAY}) and on the daily job's (its items leave the sponsor out). Its own class: the
 * invoices and closure rows are written by hand.
 */
class AutoPayClosureIT extends PlayItSupport {

    @Autowired
    JobDefinition<FeeAutoPay.AutoPayInput> qbFeeAutoPayJob;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M13c test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    String invoice(String sponsor) {
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(5));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', "
            + "'2097-01', 1000, 100, 1100, 0, 0, 'OPEN', ?, ?::date, ?::date)", id, "AZ-" + id.substring(0, 8), sponsor,
            java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    String status(String invoiceId) {
        return String.valueOf(query("SELECT status FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId).getFirst()
            .get("status"));
    }

    @Test
    void anErasedSponsorIsNotPaidForByTheEventNorByTheDailyJob() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        try {
            String erased = newSponsor(unique("s-fm13c-erased"));
            String other = newSponsor(unique("s-fm13c-live"));
            String erasedInvoice = invoice(erased);
            String otherInvoice = invoice(other);
            for (String sponsor : new String[] {erased, other}) {
                credit(sponsor, 1100);
                run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", 1100));
            }
            execute("INSERT INTO qb_sponsor_closure (closure_id, sponsor_id, status, erased_at) VALUES (?, ?, 'WAITING', ?)",
                java.util.UUID.randomUUID().toString(), erased, java.sql.Timestamp.from(clock.instant()));
            // The event's path: nothing paid.
            assertThat(((FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", erased))).outcome())
                .isEqualTo("NONE");
            assertThat(status(erasedInvoice)).isEqualTo("OPEN");
            // The job's path: the live sponsor is paid, the erased one is not an item.
            execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
            assertThat(jobRunner.execute(qbFeeAutoPayJob, clock.instant()).outcome())
                .isEqualTo(JobRunner.Outcome.SUCCEEDED);
            assertThat(status(otherInvoice)).isEqualTo("PAID");
            assertThat(status(erasedInvoice)).isEqualTo("OPEN");
            assertThat((BigDecimal) query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'", erased)
                .getFirst().get("balance")).isEqualByComparingTo("1100");
        } finally {
            param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
        }
    }

    @Test
    void aSponsorWoundUpWithFeesOwedIsNotPaidByTheEventEitherTheWindUpPaysWhatArrivesLater() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        param(QbParams.KUDOS_SELL_ENABLED, "true");
        try {
            String erased = newSponsor(unique("s-fm13c-closed"));
            String invoiceId = invoice(erased);
            execute("INSERT INTO qb_sponsor_closure (closure_id, sponsor_id, status, reason_code, erased_at, closed_at) "
                + "VALUES (?, ?, 'CLOSED', 'FEE_OWED', ?, ?)", java.util.UUID.randomUUID().toString(), erased,
                java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
            // Money arrives afterwards (a top-up confirmed late): the event's path leaves it, the wind-up's job takes it
            // up (its items include a sponsor closed with fees owed and cash that arrived since).
            credit(erased, 1100);
            run(KudosExchanges.SELL, sponsorToken(erased), body("amount", 1100));
            assertThat(((FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", erased))).outcome())
                .isEqualTo("NONE");
            assertThat(status(invoiceId)).isEqualTo("OPEN");
            assertThat(items("qb.system.money-close-due", TestTokens.bearer(tokens, "closure-test",
                QbPermissions.FEE_SYSTEM), Map.of())).anySatisfy(row -> assertThat(row).containsEntry("sponsorId", erased));
        } finally {
            param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
        }
    }
}
