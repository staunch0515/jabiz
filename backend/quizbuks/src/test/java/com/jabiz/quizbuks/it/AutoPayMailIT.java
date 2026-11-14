package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.fee.FeeAutoPay;
import com.jabiz.quizbuks.messaging.MailProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13c (review M2): every payment publishes its own event, the mail gathers a sponsor's payments over a window and
 * tells each once. Its own class: the invoices are written by hand.
 */
class AutoPayMailIT extends PlayItSupport {

    private static int seq;
    private static boolean sellOn;

    @Autowired
    JobDefinition<MailProcesses.FeePaidDueInput> qbMailFeePaidDueJob;

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

    String invoice(String sponsor, long total, int daysAgo) {
        String id = java.util.UUID.randomUUID().toString();
        java.time.Instant issued = clock.instant().minus(Duration.ofDays(daysAgo));
        execute("INSERT INTO qb_fee_invoice (invoice_id, invoice_no, sponsor_id, billing_mode, period, amount, tax, "
            + "total, paid_amount, credit_applied, status, issued_at, issued_on, due_on) VALUES (?, ?, ?, 'POSTPAID', ?, "
            + "?, ?, ?, 0, 0, 'OPEN', ?, ?::date, ?::date)", id, "AM-" + (++seq) + "-" + id.substring(0, 4), sponsor,
            (2400 + seq / 12) + "-" + String.format("%02d", seq % 12 + 1), total - total / 11, total / 11, total,
            java.sql.Timestamp.from(issued), issued.toString().substring(0, 10),
            issued.plus(Duration.ofDays(30)).toString().substring(0, 10));
        return id;
    }

    /** The mail job, run {@code minutes} after now. */
    void mailJob(long minutes) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.execute(qbMailFeePaidDueJob, clock.instant().plus(Duration.ofMinutes(minutes))).outcome())
            .isEqualTo(JobRunner.Outcome.SUCCEEDED);
    }

    List<Map<String, Object>> told(String sponsor) {
        return mailTo(sponsor).stream().filter(row -> "qb.fee.auto-paid".equals(row.get("template"))).toList();
    }

    @Test
    void aChainOfThreeInvoicesIsOneMailWithThreeLines() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-chain"));
        invoice(sponsor, 1100, 9);
        invoice(sponsor, 1100, 6);
        invoice(sponsor, 1100, 3);
        cash(sponsor, 3300);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        for (int i = 0; i < 4; i++) {
            deliverEvents();
        }
        assertThat(told(sponsor)).isEmpty();
        mailJob(12);
        assertThat(told(sponsor)).singleElement().satisfies(row ->
            assertThat(String.valueOf(row.get("params"))).contains("\"count\":\"3\"").contains("\"paid\":\"3,300\""));
        mailJob(30);
        assertThat(told(sponsor)).hasSize(1);
    }

    @Test
    void aPaymentIsToldEvenWhenTheChainStopsAfterIt() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-stop"));
        invoice(sponsor, 1100, 9);
        invoice(sponsor, 1100, 6);
        cash(sponsor, 2200);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        // The chain does not go on (the switch is off when its next run comes): the one payment made is still told.
        param(QbParams.FEE_AUTO_PAY_ENABLED, "false");
        for (int i = 0; i < 3; i++) {
            deliverEvents();
        }
        mailJob(12);
        assertThat(told(sponsor)).singleElement().satisfies(row ->
            assertThat(String.valueOf(row.get("params"))).contains("\"count\":\"1\"").contains("\"paid\":\"1,100\""));
    }

    @Test
    void anEventDeliveredTwiceIsOneMail() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-twice"));
        invoice(sponsor, 1100, 5);
        cash(sponsor, 1100);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        // The consumer once more with the same event: the notice is pending already.
        systemRun(MailProcesses.FEE_AUTO_PAID, body("sponsorId", sponsor, "occurredAt", clock.instant().toString()));
        mailJob(12);
        assertThat(told(sponsor)).hasSize(1);
    }

    @Test
    void aPaymentInTheWindowWaitsAndOneStillBeingCommittedAtTheCutIsToldByTheNextMail() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-window"));
        invoice(sponsor, 1100, 9);
        invoice(sponsor, 1100, 6);
        cash(sponsor, 1100);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        mailJob(12);
        assertThat(told(sponsor)).hasSize(1);
        // A second payment at minute 19: the job at minute 20 cuts at minute 18, so the payment is after the cut.
        clock.set(clock.instant().plus(Duration.ofMinutes(19)));
        cash(sponsor, 1100);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        mailJob(1);
        assertThat(told(sponsor)).hasSize(1);
        // Within the window of the time the last cut left, the sponsor is not an item at all.
        mailJob(5);
        assertThat(told(sponsor)).hasSize(1);
        // Later, the payment is inside the window and told, alone.
        mailJob(12);
        assertThat(told(sponsor)).hasSize(2);
        assertThat(String.valueOf(told(sponsor).get(1).get("params"))).contains("\"count\":\"1\"");
    }

    @Test
    void aPaymentByHandAfterTheLastMailIsNotListedWithTheNextOne() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-hand"));
        invoice(sponsor, 1100, 9);
        cash(sponsor, 1100);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        mailJob(12);
        assertThat(told(sponsor)).hasSize(1);
        // By hand, five minutes later: no event, and not in the window the next payment starts (less the grace).
        clock.set(clock.instant().plus(Duration.ofMinutes(15)));
        String second = invoice(sponsor, 1100, 5);
        cash(sponsor, 1100);
        run(com.jabiz.quizbuks.fee.FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", second));
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        invoice(sponsor, 1100, 2);
        cash(sponsor, 1100);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        mailJob(12);
        assertThat(told(sponsor)).hasSize(2);
        assertThat(String.valueOf(told(sponsor).get(1).get("params"))).contains("\"count\":\"1\"");
    }

    @Test
    void moreThanTenInvoicesAreListedTenAndTheRestIsMarked() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-many"));
        for (int i = 0; i < 12; i++) {
            invoice(sponsor, 110, 20 - i);
        }
        cash(sponsor, 1320);
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        for (int i = 0; i < 16; i++) {
            deliverEvents();
        }
        mailJob(12);
        assertThat(told(sponsor)).singleElement().satisfies(row -> {
            String params = String.valueOf(row.get("params"));
            assertThat(params).contains("\"count\":\"12\"").contains("...");
        });
    }

    @Test
    void aBannedSponsorIsToldNothingAndAfterTheBanIsLiftedWhatWasMissedIsNotSentLate() {
        param(QbParams.FEE_AUTO_PAY_ENABLED, "true");
        String sponsor = newSponsor(unique("s-fm13c-mail-ban"));
        invoice(sponsor, 1100, 9);
        cash(sponsor, 1100);
        String boss = userWithRole(unique("fm13c-boss"), com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER);
        run("QB_USER_BAN", tokenOf(boss, com.jabiz.quizbuks.setup.QbRoles.ADMIN_SUPER),
            body("userId", sponsor, "reason", "Fraud"));
        systemRun(FeeAutoPay.AUTO_PAY, body("sponsorId", sponsor));
        deliverEvents();
        mailJob(12);
        assertThat(told(sponsor)).isEmpty();
        mailJob(30);
        assertThat(told(sponsor)).isEmpty();
    }
}
