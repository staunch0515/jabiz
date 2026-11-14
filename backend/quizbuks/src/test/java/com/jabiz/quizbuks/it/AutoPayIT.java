package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.job.JobDefinition;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    private static int seq;

    /** An unpaid postpaid invoice of {@code total} (a tenth of it tax), issued {@code daysAgo} days ago. */
    String invoice(String sponsor, long total, int daysAgo, long creditApplied) {
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(java.time.Duration.ofDays(daysAgo));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', ?, "
            + "?, ?, ?, 0, ?, 'OPEN', ?, ?::date, ?::date)", id, "AP-" + (++seq) + "-" + id.substring(0, 4), sponsor,
            (2000 + seq / 12) + "-" + String.format("%02d", seq % 12 + 1), total - total / 11, total / 11, total, creditApplied, java.sql.Timestamp.from(issued),
            issued.toString().substring(0, 10), issued.plus(java.time.Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    Map<String, Object> row(String id) {
        return query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", id).getFirst();
    }

    FeeAutoPay.AutoPayOutput autoPay(String sponsor) {
        return (FeeAutoPay.AutoPayOutput) systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
    }

    @Autowired
    JobDefinition<FeeAutoPay.AutoPayInput> qbFeeAutoPayJob;

    @Test
    void nothingIsPaidUntilTheSwitchIsPublishedThenOneInvoiceIsPaidAsFarAsTheAccountGoes() {
        String sponsor = newSponsor(unique("s-fm13b-pay"));
        String invoiceId = invoice(sponsor, 1100, 5, 0);
        cash(sponsor, 400);

        // Published as false: nothing is paid (a switch nobody published at all is AutoPayOffIT's).
        param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
        assertThat(autoPay(sponsor).outcome()).isEqualTo("OFF");
        assertThat((BigDecimal) row(invoiceId).get("paid_amount")).isEqualByComparingTo("0");
        // The sponsor's wallet says so too (F-M13c): off, then on.
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst()).containsEntry("autoPay", false);

        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst()).containsEntry("autoPay", true);
        FeeAutoPay.AutoPayOutput first = autoPay(sponsor);
        assertThat(first.outcome()).isEqualTo("PAID");
        assertThat(first.paid()).isEqualByComparingTo("400");
        assertThat(row(invoiceId)).containsEntry("status", QbFees.PARTIAL);
        // Nothing left to pay with: a second run does nothing.
        assertThat(autoPay(sponsor).outcome()).isEqualTo("NONE");

        cash(sponsor, 1000);
        assertThat(autoPay(sponsor).paid()).isEqualByComparingTo("700");
        assertThat(row(invoiceId)).containsEntry("status", QbFees.PAID);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("300");
        // Paid in full: the invoice is not paid twice.
        assertThat(autoPay(sponsor).outcome()).isEqualTo("NONE");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("300");
    }

    @Test
    void theEarliestIsPaidFirstAFurtherOneIsAskedForAndTheLimitStopsTheChain() {
        String sponsor = newSponsor(unique("s-fm13b-order"));
        String late = invoice(sponsor, 1100, 5, 0);
        String early = invoice(sponsor, 1100, 20, 0);
        String middle = invoice(sponsor, 1100, 10, 0);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        run("PARAM_SET", admin(), body("key", QbParams.FEE_AUTO_PAY_MAX_INVOICES, "value", "2"));
        cash(sponsor, 2500);
        FeeAutoPay.AutoPayOutput one = autoPay(sponsor);
        assertThat(one.paid()).isEqualByComparingTo("1100");
        assertThat(one.more()).isTrue();
        assertThat(row(early)).containsEntry("status", QbFees.PAID);
        assertThat(row(middle)).containsEntry("status", QbFees.OPEN);
        // The chain: the event of the run asks for the next, which is the second of two the limit allows: 1,400 left, the
        // middle one is paid in full and the late one stays (n = 2 is the limit).
        deliverEvents();
        deliverEvents();
        assertThat(row(middle)).containsEntry("status", QbFees.PAID);
        assertThat(row(late)).containsEntry("status", QbFees.OPEN);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("300");
        // The chain ended at the limit: the mail told both invoices at once, not one mail each.
        deliverEvents();
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.auto-paid".equals(row.get("template"))).hasSize(1);
        run("PARAM_SET", admin(), body("key", QbParams.FEE_AUTO_PAY_MAX_INVOICES, "value", "20"));
    }

    @Test
    void anInvoicePartlyCoveredByACreditNoteOwesOnlyTheRest() {
        String sponsor = newSponsor(unique("s-fm13b-credit"));
        String invoiceId = invoice(sponsor, 1100, 5, 300);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        cash(sponsor, 5000);
        assertThat(autoPay(sponsor).paid()).isEqualByComparingTo("800");
        assertThat(row(invoiceId)).containsEntry("status", QbFees.PAID);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("4200");
    }

    @Test
    void theDailyJobStartsAChainForASponsorAndOneMailTellsAllItPaid() {
        String sponsor = newSponsor(unique("s-fm13b-job"));
        String a = invoice(sponsor, 1100, 8, 0);
        String b = invoice(sponsor, 1100, 4, 0);
        String broke = newSponsor(unique("s-fm13b-job-broke"));
        String unpaid = invoice(broke, 1100, 4, 0);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        cash(sponsor, 2200);
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        JobRunner.JobRun run = jobRunner.execute(qbFeeAutoPayJob, clock.instant());
        assertThat(run.outcome()).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        // One item for the sponsor: the first run pays the earliest, the chain's event asks for the next, then the mail.
        deliverEvents();
        deliverEvents();
        deliverEvents();
        assertThat(row(a)).containsEntry("status", QbFees.PAID);
        assertThat(row(b)).containsEntry("status", QbFees.PAID);
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.auto-paid".equals(row.get("template")))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("params")))
                .contains("\"count\":\"2\"").contains("\"paid\":\"2,200\""));
        // Nothing to pay it with: not an item.
        assertThat(row(unpaid)).containsEntry("status", "OPEN");
    }

    @Test
    void theMailReadsThePaymentsAgainSaysWhatWasPaidAndWhatIsStillOwedAndIsSentOncePerPayment() {
        String sponsor = newSponsor(unique("s-fm13b-mail"));
        String invoiceId = invoice(sponsor, 1100, 5, 0);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        cash(sponsor, 400);
        autoPay(sponsor);
        deliverEvents();
        // Paid 400 of 1,100 (a part): the mail says 400 paid and 700 still owed, never "paid" for the invoice.
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.auto-paid".equals(row.get("template")))
            .singleElement().satisfies(row -> {
                String params = String.valueOf(row.get("params"));
                assertThat(params).contains("\"paid\"").contains("400").contains("\"owed\"").contains("700");
            });
        // What the mail says is read again: a payment that is not the sponsor's is told to nobody.
        String transaction = String.valueOf(query("SELECT transaction_id FROM qb_fee_payment_version WHERE sponsor_id = ?",
            sponsor).getFirst().get("transaction_id"));
        String other = newSponsor(unique("s-fm13b-else"));
        Object told = systemRun("QB_MAIL_FEE_AUTO_PAID", body("sponsorId", other, "transactionIds", List.of(transaction),
            "invoiceIds", List.of(invoiceId), "occurredAt", clock.instant().toString()));
        assertThat(String.valueOf(told)).doesNotContain("qb.fee.auto-paid");
        assertThat(mailTo(other)).filteredOn(row -> "qb.fee.auto-paid".equals(row.get("template"))).isEmpty();
    }

    @Test
    void aBannedSponsorIsStillPaidButToldNothing() {
        String sponsor = newSponsor(unique("s-fm13b-ban"));
        String invoiceId = invoice(sponsor, 1100, 5, 0);
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        cash(sponsor, 5000);
        String boss = userWithRole(unique("fm13b-boss"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER),
            body("userId", sponsor, "reason", "Fraud"));
        assertThat(autoPay(sponsor).outcome()).isEqualTo("PAID");
        deliverEvents();
        assertThat(row(invoiceId)).containsEntry("status", QbFees.PAID);
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.auto-paid".equals(row.get("template"))).isEmpty();
    }

    BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }
}
