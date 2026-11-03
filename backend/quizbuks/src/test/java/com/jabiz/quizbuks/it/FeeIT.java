package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.runtime.context.Actor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform fees (Q87b, docs/quizbuks/plans/Q87-fee-account.md §11): the monthly fee paid from the cash account or
 * owed, the invoices with their tax rounded once, paying from the cash account (an invoice, or early), postpaid credit,
 * overdue invoices and their reminders, what unpaid fees stop, charges by hand and voidings under four eyes, the
 * operators' billing settings and settlement, the reconciliation.
 */
class FeeIT extends TopUpItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    @Qualifier("qbFeeMonthlyJob")
    JobDefinition<?> monthlyJob;

    @Autowired
    @Qualifier("qbFeeInvoiceJob")
    JobDefinition<?> invoiceJob;

    @Autowired
    @Qualifier("qbFeeOverdueJob")
    JobDefinition<?> overdueJob;

    @Autowired
    @Qualifier("qbReconcileJob")
    JobDefinition<?> reconcileJob;

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q87b"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    String finance() {
        return tokenOf(user("fee-finance"), QbRoles.ADMIN_FINANCE);
    }

    String approver() {
        return tokenOf(user("fee-approver"), QbRoles.ADMIN_CONTENT);
    }

    BigDecimal cash(String sponsorId) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = 'CASH'",
            sponsorId);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    void cashTopUp(String sponsor, long amount) {
        String topUpId = (String) run("QB_TOPUP_START", sponsorToken(sponsor), body("amount", amount, "target",
            "CASH")).get("topUpId");
        runJobs();
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
    }

    JobRunner.Outcome job(JobDefinition<?> job, Instant at) {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobs.run(job, at);
    }

    List<Map<String, Object>> charges(String sponsor) {
        return query("SELECT * FROM qb_fee_charge_version WHERE sponsor_id = ? ORDER BY charged_at", sponsor);
    }

    Map<String, Object> invoice(String sponsor) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? "
            + "ORDER BY issued_at DESC", sponsor);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The lines posted for the sponsor (any account), in order, as {@code code:direction:amount}. */
    static List<String> postings(String sponsor, String source) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND e.dimension_1 = ? ORDER BY t.process_seq_id, e.line_no", source, sponsor)
            .stream().map(row -> row.get("account_code") + ":" + row.get("direction") + ":"
                + money(row.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    /** What the sponsor owes: account 1130 for them (an asset: debit less credit). */
    static BigDecimal ledger1130(String sponsor) {
        return ledger("1130", sponsor).negate();
    }

    /** The next month's 2nd in Tokyo, 02:00, from now. */
    Instant nextSecond() {
        YearMonth month = YearMonth.from(clock.instant().atZone(TOKYO)).plusMonths(1);
        return month.atDay(2).atTime(2, 0).atZone(TOKYO).toInstant();
    }

    void advanceTo(Instant at) {
        Duration gap = Duration.between(clock.instant(), at);
        if (!gap.isNegative()) {
            advance(gap);
        }
    }

    void approve(String requestId) {
        run("APPROVAL_DECIDE", approver(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    void reconcileOk() {
        Instant at = clock.instant().plusSeconds(60);
        assertThat(job(reconcileJob, at)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(query("SELECT r.status, r.summary FROM qb_recon_run_version r WHERE r.scheduled_time = ?",
            java.sql.Timestamp.from(at)).getFirst()).containsEntry("status", "OK");
    }

    @Test
    void aPrepaidSponsorPaysTheMonthlyFeeFromCashAndGetsAPaidInvoice() {
        String sponsor = newSponsor("s-q87b-prepaid");
        param(QbParams.FEE_MONTHLY, "1050");
        cashTopUp(sponsor, 5000);
        Instant monthStart = clock.instant();
        String period = FeeProcesses.period(monthStart);

        assertThat(job(monthlyJob, monthStart)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(charges(sponsor)).singleElement().satisfies(charge -> {
            assertThat(charge).containsEntry("kind", "MONTHLY").containsEntry("period", period)
                .containsEntry("settlement", "CASH");
            assertThat(money(charge.get("tax"))).isEqualByComparingTo("105");
        });
        assertThat(cash(sponsor)).isEqualByComparingTo("3845");
        assertThat(postings(sponsor, "QbFeeCharge")).containsExactly("2160:DEBIT:1155", "4120:CREDIT:1050",
            "2150:CREDIT:105");
        // Once a month: the job again charges nothing.
        assertThat(job(monthlyJob, monthStart.plusSeconds(60))).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(charges(sponsor)).hasSize(1);

        // The next month's invoice: all paid when charged, nothing due.
        Instant second = nextSecond();
        advanceTo(second);
        assertThat(job(invoiceJob, second)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        Map<String, Object> invoice = invoice(sponsor);
        assertThat(invoice).containsEntry("status", "PAID").containsEntry("period", period)
            .containsEntry("billing_mode", "PREPAID");
        assertThat((String) invoice.get("invoice_no")).startsWith("QBF-" + period.substring(0, 4) + "-");
        assertThat(money(invoice.get("total"))).isEqualByComparingTo("1155");
        assertThat(money(invoice.get("due"))).isZero();
        assertThat(outboxEvents(FeeProcesses.INVOICE_EVENT, "invoiceId", String.valueOf(invoice.get("invoice_id"))))
            .isEqualTo(1);

        // The sponsor reads and downloads their own; nobody else's.
        String token = sponsorToken(sponsor);
        assertThat(items("qb.sponsor.fee-standing", token, Map.of()).getFirst())
            .containsEntry("billingMode", "PREPAID");
        Map<String, Object> pdf = run(FeeProcesses.DOCUMENT, token, body("invoiceId", invoice.get("invoice_id"),
            "language", "ja"));
        assertThat(pdf).containsEntry("issued", true).containsEntry("invoiceNo", invoice.get("invoice_no"));
        assertThat(run(FeeProcesses.DOCUMENT, token, body("invoiceId", invoice.get("invoice_id"), "language", "ja")))
            .containsEntry("issued", false).containsEntry("runId", pdf.get("runId"));
        assertThat(codes(refused(FeeProcesses.DOCUMENT, sponsorToken(newSponsor("s-q87b-other")),
            body("invoiceId", invoice.get("invoice_id"), "language", "ja"), 422)))
            .containsExactly(FeeCodes.INVOICE_STATE);
        assertBooksBalance();
        reconcileOk();
        param(QbParams.FEE_MONTHLY, "0");
    }

    @Test
    void aPrepaidSponsorShortOfCashOwesTheFeeIsStoppedAndPaysEarly() {
        String sponsor = newSponsor("s-q87b-short");
        param(QbParams.FEE_MONTHLY, "1050");
        Instant now = clock.instant();
        assertThat(job(monthlyJob, now)).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(charges(sponsor)).singleElement().satisfies(charge -> assertThat(charge)
            .containsEntry("settlement", "RECEIVABLE"));
        assertThat(ledger1130(sponsor)).isEqualByComparingTo("1155");
        param(QbParams.FEE_MONTHLY, "0");

        // Cash comes in, but owing stops buying Kudos.
        cashTopUp(sponsor, 2000);
        String token = sponsorToken(sponsor);
        assertThat(codes(refused("QB_KUDOS_BUY", token, body("amount", 100), 422))).containsExactly(FeeCodes.UNPAID);
        // Paid early, before the invoice: then buying goes through.
        Map<String, Object> paid = run(FeeProcesses.PAY, token, body());
        assertThat(money(paid.get("amount"))).isEqualByComparingTo("1155");
        assertThat(cash(sponsor)).isEqualByComparingTo("845");
        assertThat(ledger1130(sponsor)).isZero();
        assertThat(codes(refused(FeeProcesses.PAY, token, body(), 422))).containsExactly(FeeCodes.INVOICE_STATE);
        run("QB_KUDOS_BUY", token, body("amount", 100));

        // The invoice counts the early payment as paid.
        Instant second = nextSecond();
        advanceTo(second);
        job(invoiceJob, second);
        Map<String, Object> invoice = invoice(sponsor);
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat(money(invoice.get("paid_at_charge"))).isEqualByComparingTo("1155");
        assertBooksBalance();
        reconcileOk();
    }

    @Test
    void aPostpaidSponsorOwesUpToTheCreditAndAnOverdueInvoiceIsRemindedAndStopsThem() {
        String sponsor = newSponsor("s-q87b-postpaid");
        String token = sponsorToken(sponsor);
        // Prepaid has no credit; postpaid with 1000.
        assertThat(codes(refused(FeeProcesses.BILLING_SET, finance(), body("sponsorId", sponsor, "billingMode",
            "PREPAID", "creditLimit", 10, "reason", "x"), 422))).containsExactly(FeeCodes.BILLING);
        run(FeeProcesses.BILLING_SET, finance(), body("sponsorId", sponsor, "billingMode", "POSTPAID",
            "creditLimit", 2000, "reason", "Invoiced monthly"));
        cashTopUp(sponsor, 3000);
        param(QbParams.FEE_MONTHLY, "1050");
        assertThat(job(monthlyJob, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        param(QbParams.FEE_MONTHLY, "0");
        // Postpaid owes even with cash; within the credit, buying goes through.
        assertThat(charges(sponsor)).singleElement().satisfies(charge -> assertThat(charge)
            .containsEntry("settlement", "RECEIVABLE"));
        assertThat(cash(sponsor)).isEqualByComparingTo("3000");
        run("QB_KUDOS_BUY", token, body("amount", 100));
        // Above the credit, it is stopped.
        run(FeeProcesses.BILLING_SET, finance(), body("sponsorId", sponsor, "billingMode", "POSTPAID",
            "creditLimit", 1000, "reason", "Lowered"));
        assertThat(codes(refused("QB_KUDOS_BUY", token, body("amount", 100), 422)))
            .containsExactly(FeeCodes.CREDIT_EXCEEDED);
        run(FeeProcesses.BILLING_SET, finance(), body("sponsorId", sponsor, "billingMode", "POSTPAID",
            "creditLimit", 5000, "reason", "Raised"));

        // The invoice: due in 30 days.
        Instant second = nextSecond();
        advanceTo(second);
        job(invoiceJob, second);
        Map<String, Object> invoice = invoice(sponsor);
        assertThat(invoice).containsEntry("status", "ISSUED").containsEntry("billing_mode", "POSTPAID");
        assertThat(money(invoice.get("due"))).isEqualByComparingTo("1155");
        assertThat(invoice.get("due_on").toString())
            .isEqualTo(FeeProcesses.day(clock.instant()).plusDays(30).toString());
        String invoiceId = String.valueOf(invoice.get("invoice_id"));

        // Not before the due date; the day after, overdue: reminded, finance told, and it stops buying.
        job(overdueJob, clock.instant());
        assertThat(invoice(sponsor)).containsEntry("status", "ISSUED");
        advance(Duration.ofDays(31));
        job(overdueJob, clock.instant());
        assertThat(invoice(sponsor)).containsEntry("status", "OVERDUE");
        assertThat(((Number) invoice(sponsor).get("reminders")).intValue()).isEqualTo(1);
        assertThat(outboxEvents(FeeProcesses.OVERDUE_EVENT, "invoiceId", invoiceId)).isEqualTo(1);
        assertThat(query("SELECT assignee_permission FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version "
            + "ORDER BY task_id, version_no DESC) t WHERE source_key = ? AND status = 'OPEN'", "fee-overdue:"
            + invoiceId)).singleElement().satisfies(task -> assertThat(task)
                .containsEntry("assignee_permission", QbPermissions.FEE_RECORD));
        assertThat(codes(refused("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 100), 422))).containsExactly(FeeCodes.OVERDUE);
        // The overdue block can be switched off: within the credit it goes through.
        param(QbParams.FEE_OVERDUE_BLOCK, "false");
        run("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 100));
        param(QbParams.FEE_OVERDUE_BLOCK, "true");

        // Reminded a week later, not sooner; three at most.
        advance(Duration.ofDays(3));
        job(overdueJob, clock.instant());
        assertThat(((Number) invoice(sponsor).get("reminders")).intValue()).isEqualTo(1);
        for (int week = 0; week < 3; week++) {
            advance(Duration.ofDays(7));
            job(overdueJob, clock.instant());
        }
        assertThat(((Number) invoice(sponsor).get("reminders")).intValue()).isEqualTo(3);
        assertThat(outboxEvents(FeeProcesses.OVERDUE_EVENT, "invoiceId", invoiceId)).isEqualTo(3);

        // Paid from the cash account: no longer stopped.
        assertThat(codes(refused(FeeProcesses.PAY, sponsorToken(newSponsor("s-q87b-third")),
            body("invoiceId", invoiceId), 422))).containsExactly(FeeCodes.INVOICE_STATE);
        run(FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", invoiceId));
        assertThat(invoice(sponsor)).containsEntry("status", "PAID");
        assertThat(ledger1130(sponsor)).isZero();
        run("QB_KUDOS_BUY", sponsorToken(sponsor), body("amount", 100));
        assertThat(postings(sponsor, "QbFeePayment")).containsExactly("2160:DEBIT:1155", "1130:CREDIT:1155");
        assertBooksBalance();
        reconcileOk();
    }

    @Test
    void aChargeByHandAndAVoidingTakeEffectOnlyOnceAnotherAdministratorApproves() {
        String sponsor = newSponsor("s-q87b-manual");
        cashTopUp(sponsor, 5000);
        // Asked by finance: nothing is charged before the approval; the asker cannot approve.
        Map<String, Object> asked = run(FeeProcesses.REQUEST, finance(), body("kind", "CHARGE", "sponsorId", sponsor,
            "amount", 1000, "reason", "Setup service"));
        assertThat(asked).containsEntry("status", "REVIEW");
        assertThat(charges(sponsor)).isEmpty();
        refused("APPROVAL_DECIDE", finance(), body("requestId", asked.get("approvalRequestId"), "decision",
            "APPROVE"), 422);
        approve((String) asked.get("approvalRequestId"));
        assertThat(charges(sponsor)).singleElement().satisfies(charge -> assertThat(charge)
            .containsEntry("kind", "MANUAL").containsEntry("settlement", "CASH")
            .containsEntry("source_entity", "QbFeeRequest").containsEntry("note", "Setup service"));
        assertThat(cash(sponsor)).isEqualByComparingTo("3900");
        // A second delivery of the same decision charges nothing more.
        deliverEvents();
        assertThat(charges(sponsor)).hasSize(1);

        // A rejected request charges nothing.
        Map<String, Object> rejected = run(FeeProcesses.REQUEST, finance(), body("kind", "CHARGE", "sponsorId",
            sponsor, "amount", 7, "reason", "Typo"));
        run("APPROVAL_DECIDE", approver(), body("requestId", rejected.get("approvalRequestId"), "decision", "REJECT",
            "reason", "No"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?", rejected.get("requestId"))
            .getFirst()).containsEntry("status", "REJECTED");
        assertThat(charges(sponsor)).hasSize(1);

        // The month's invoice, then voided: every posting of the month reversed, the cash given back.
        Instant second = nextSecond();
        advanceTo(second);
        job(invoiceJob, second);
        Map<String, Object> invoice = invoice(sponsor);
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat(codes(refused(FeeProcesses.REQUEST, finance(), body("kind", "VOID", "sponsorId",
            newSponsor("s-q87b-wrong"), "invoiceId", invoice.get("invoice_id"), "reason", "Wrong"), 422)))
            .contains(FeeCodes.INVOICE_STATE);
        Map<String, Object> voiding = run(FeeProcesses.REQUEST, finance(), body("kind", "VOID", "sponsorId", sponsor,
            "invoiceId", invoice.get("invoice_id"), "reason", "Charged by mistake"));
        // One open voiding per invoice.
        assertThat(codes(refused(FeeProcesses.REQUEST, finance(), body("kind", "VOID", "sponsorId", sponsor,
            "invoiceId", invoice.get("invoice_id"), "reason", "Again"), 422))).contains(FeeCodes.INVOICE_STATE);
        approve((String) voiding.get("approvalRequestId"));
        assertThat(invoice(sponsor)).containsEntry("status", "VOID");
        assertThat(cash(sponsor)).isEqualByComparingTo("5000");
        assertThat(ledger("4120", sponsor)).isZero();
        assertThat(ledger("2150", sponsor)).isZero();
        assertBooksBalance();
        reconcileOk();
    }

    @Test
    void anOperatorSetsItsOwnSponsorsBillingAndItsSettlementCountsTheirFees() {
        String hq = TestTokens.bearer(tokens, "q87b-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
        String operatorId = (String) run(OperatorProcesses.SAVE, hq, body("name", "Q87b Ops " + eventId(),
            "contactName", "Owner", "contactEmail", "ops@q87b.example")).get("operatorId");
        String own = newSponsor("s-q87b-own");
        String elsewhere = newSponsor("s-q87b-elsewhere");
        run(OperatorProcesses.ASSIGN, hq, body("userId", own, "operatorId", operatorId, "reason", "Q87b"));
        String member = createUser("q87b-member-" + eventId().substring(0, 8));
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            member);
        run(OperatorProcesses.MEMBER_ADD, hq, body("operatorId", operatorId, "userId", member, "lead", true));
        Actor actor = new Actor(member, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        String operator = "Bearer " + tokens.issue(actor).token();

        run(FeeProcesses.BILLING_SET, operator, body("sponsorId", own, "billingMode", "POSTPAID", "creditLimit",
            3000, "reason", "Trusted customer"));
        refused(FeeProcesses.BILLING_SET, operator, body("sponsorId", elsewhere, "billingMode", "POSTPAID",
            "creditLimit", 3000, "reason", "Not ours"), 404);
        assertThat(items("qb.operator.sponsor-billing", operator, Map.of())).singleElement().satisfies(row ->
            assertThat(row).containsEntry("sponsorId", own).containsEntry("billingMode", "POSTPAID")
                .containsEntry("own", true));

        // The fee is charged under the operator, and its settlement's revenue counts it (before tax).
        Map<String, Object> asked = run(FeeProcesses.REQUEST, finance(), body("kind", "CHARGE", "sponsorId", own,
            "amount", 2000, "reason", "Consulting"));
        approve((String) asked.get("approvalRequestId"));
        assertThat(charges(own)).singleElement().satisfies(charge -> assertThat(charge)
            .containsEntry("operator_id", operatorId).containsEntry("settlement", "RECEIVABLE"));
        Instant from = clock.instant().minus(Duration.ofDays(1));
        Map<String, Object> revenue = items("qb.system.operator-revenue", TestTokens.bearer(tokens, "q87b-settle",
            QbPermissions.OPERATOR_SETTLEMENT_SYSTEM), Map.of("params", Map.of("operatorId", operatorId,
                "from", from.toString(), "to", clock.instant().plusSeconds(60).toString()))).getFirst();
        assertThat(money(revenue.get("revenue"))).isEqualByComparingTo("2000");
        assertBooksBalance();
    }
}
