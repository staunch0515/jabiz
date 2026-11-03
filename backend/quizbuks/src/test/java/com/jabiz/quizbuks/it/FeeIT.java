package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's fees (docs/quizbuks/plans/Q87-fee-account.md, D-Q87-4 – D-Q87-8): the monthly fee charged once a
 * month, paid from the cash account at once by a prepaid sponsor (an open invoice stopping them otherwise, while the
 * switch says so), invoiced monthly to a postpaid one within their credit limit and reminded when overdue; a fee by
 * hand and an invoice voided with another administrator's approval; the billing an operator sets for its own
 * sponsors only.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FeeIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static boolean switched;

    String finance() {
        return tokenOf(user("fee-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("fee-content"), QbRoles.ADMIN_CONTENT);
    }

    String hq() {
        return TestTokens.bearer(tokens, "fee-hq", QbPermissions.FEE_BILLING, QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
    }

    String period() {
        return FeeRules.period(clock.instant(), TOKYO);
    }

    /** The monthly fee and selling Kudos on, once for the class. */
    void switches() {
        if (!switched) {
            param(QbParams.FEE_MONTHLY, "1000");
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            // The invoice's document names the issuer (the receipts' parameters, Q9d).
            param(QbParams.ISSUER_NAME, "QuizBuks Operations K.K.");
            param(QbParams.ISSUER_ADDRESS, "1-2-3 Chiyoda, Tokyo");
            switched = true;
        }
    }

    /** Money in the sponsor's cash account: Kudos credited and sold back (the dev credit knows no cash). */
    void cash(String sponsor, long amount) {
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    Map<String, Object> monthly(String sponsor) {
        FeeProcesses.ItemOutput out = (FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM,
            body("sponsorId", sponsor, "period", period()));
        return Map.of("sponsorId", out.sponsorId(), "outcome", out.outcome(), "id", String.valueOf(out.id()));
    }

    /** The outcome of one item of a fee job, run as the system. */
    String item(String process, Map<String, ?> body) {
        return ((FeeProcesses.ItemOutput) systemRun(process, body)).outcome();
    }

    List<Map<String, Object>> charges(String sponsor) {
        return latest("qb_fee_charge_version", "charge_id", "sponsor_id = '" + sponsor + "'");
    }

    List<Map<String, Object>> invoices(String sponsor) {
        return query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY issued_at", sponsor);
    }

    static List<String> lines(String sourceEntity, String sourceId) {
        return query("SELECT a.account_code, e.direction, e.amount, e.dimension_1 FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no", sourceEntity,
            sourceId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()
            + (p.get("dimension_1") == null ? "" : " @" + p.get("dimension_1"))).toList();
    }

    static List<Map<String, Object>> openTasks(String permission, String source) {
        return query("SELECT status FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY task_id, "
            + "version_no DESC) t WHERE assignee_permission = ? AND source_key = ? AND NOT is_deleted", permission, source);
    }

    void approve(String requestId) {
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Q87 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    @Test
    @Order(1)
    void aPrepaidSponsorPaysTheMonthlyFeeFromTheCashAccountAtOnce() {
        switches();
        String sponsor = newSponsor(unique("s-q87-prepaid"));
        cash(sponsor, 5000);
        Map<String, Object> charged = monthly(sponsor);
        assertThat(charged).containsEntry("outcome", "CHARGED");
        Map<String, Object> charge = charges(sponsor).getFirst();
        assertThat(charge).containsEntry("kind", "MONTHLY").containsEntry("period", period())
            .containsEntry("billing_mode", "PREPAID");
        assertThat((BigDecimal) charge.get("amount")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) charge.get("tax")).isEqualByComparingTo("100");
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo("1100");
        String chargeId = String.valueOf(charge.get("charge_id"));
        // Receivable, then paid, both posted from the charge; the invoice is paid already.
        assertThat(lines(QbFees.CHARGE, chargeId)).containsExactly("1130 DEBIT 1100 @" + sponsor, "4120 CREDIT 1000",
            "2150 CREDIT 100", "2160 DEBIT 1100 @" + sponsor, "1130 CREDIT 1100 @" + sponsor);
        Map<String, Object> invoice = invoices(sponsor).getFirst();
        assertThat(invoice).containsEntry("status", "PAID").containsEntry("charge_id", chargeId);
        assertThat(String.valueOf(invoice.get("invoice_no"))).matches("QBF-\\d{4}-\\d{5}");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("3900");
        // Once a month: the item finds the fee and charges nothing.
        assertThat(monthly(sponsor)).containsEntry("outcome", "NONE");
        assertThat(charges(sponsor)).hasSize(1);
        // The sponsor sees the charge and its invoice, and the invoice's document on request, the same one after.
        assertThat(items("qb.sponsor.fee-charges", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("invoiceStatus", "PAID").containsEntry("total", 1100));
        Map<String, Object> document = run(FeeProcesses.DOCUMENT, sponsorToken(sponsor),
            body("invoiceId", invoice.get("invoice_id"), "language", "en"));
        assertThat(document.get("documentRunId")).isNotNull();
        assertThat(run(FeeProcesses.DOCUMENT, sponsorToken(sponsor), body("invoiceId", invoice.get("invoice_id"))))
            .containsEntry("documentRunId", document.get("documentRunId"));
        // Another sponsor's invoice is not found.
        refused(FeeProcesses.DOCUMENT, sponsorToken(newSponsor(unique("s-q87-other"))),
            body("invoiceId", invoice.get("invoice_id")), 404);
    }

    @Test
    @Order(2)
    void anUnpaidPrepaidInvoiceStopsBuyingKudosAndSubmittingUntilItIsPaid() {
        switches();
        String sponsor = newSponsor(unique("s-q87-short"));
        assertThat(monthly(sponsor)).containsEntry("outcome", "CHARGED");
        Map<String, Object> invoice = invoices(sponsor).getFirst();
        assertThat(invoice).containsEntry("status", "OPEN");
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("unpaidTotal", 1100).containsEntry("unpaidCount", 1);
        cash(sponsor, 2000);
        // Buying is stopped, submitting too (D-Q87-5).
        assertThat(codes(refused(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 100), 422)))
            .contains(FeeCodes.FEE_UNPAID);
        String token = sponsorToken(sponsor);
        String versionId = version(token, "Q87 gated quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Q87 gated", now, now.plus(Duration.ofDays(7)));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId), 422)))
            .contains(FeeCodes.FEE_UNPAID);
        // The switch off: nothing is stopped.
        param(QbParams.FEE_OVERDUE_BLOCK, "false");
        try {
            run(KudosExchanges.BUY, token, body("amount", 100));
        } finally {
            param(QbParams.FEE_OVERDUE_BLOCK, "true");
        }
        // Paid from the cash account: both open again.
        assertThat(codes(refused(FeeProcesses.PAY, sponsorToken(newSponsor(unique("s-q87-else"))),
            body("invoiceId", invoice.get("invoice_id")), 404))).isEmpty();
        Map<String, Object> paid = run(FeeProcesses.PAY, token, body("invoiceId", invoice.get("invoice_id")));
        assertThat(paid).containsEntry("status", "PAID").containsEntry("cashBalance", 800);
        assertThat(lines(QbFees.INVOICE, String.valueOf(invoice.get("invoice_id"))))
            .containsExactly("2160 DEBIT 1100 @" + sponsor, "1130 CREDIT 1100 @" + sponsor);
        assertThat(codes(refused(FeeProcesses.PAY, token, body("invoiceId", invoice.get("invoice_id")), 422)))
            .containsExactly(FeeCodes.FEE_INVOICE_STATE);
        run(KudosExchanges.BUY, token, body("amount", 100));
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isIn("LIVE", "SCHEDULED");
    }

    @Test
    @Order(3)
    void aPostpaidSponsorIsInvoicedForTheMonthAndRemindedWhenOverdue() {
        switches();
        String sponsor = newSponsor(unique("s-q87-postpaid"));
        Map<String, Object> billing = run(FeeProcesses.BILLING_SET, hq(), body("userId", sponsor,
            "billingMode", "POSTPAID", "creditLimit", 5000, "reason", "Known company"));
        assertThat(billing).containsEntry("changed", true);
        assertThat(run(FeeProcesses.BILLING_SET, hq(), body("userId", sponsor, "billingMode", "POSTPAID",
            "creditLimit", 5000, "reason", "Again"))).containsEntry("changed", false);
        assertThat(codes(refused(FeeProcesses.BILLING_SET, hq(), body("userId", sponsor, "billingMode", "PREPAID",
            "creditLimit", 10, "reason", "x"), 422))).containsExactly(FeeCodes.FEE_BILLING_MODE);
        assertThat(monthly(sponsor)).containsEntry("outcome", "CHARGED");
        // No invoice yet: the month's comes with the job; the fee is within the credit, nothing is stopped.
        assertThat(invoices(sponsor)).isEmpty();
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("billingMode", "POSTPAID").containsEntry("creditLimit", 5000);
        assertThat(item(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", period()))).isEqualTo("ISSUED");
        assertThat(item(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", period()))).isEqualTo("NONE");
        Map<String, Object> invoice = invoices(sponsor).getFirst();
        assertThat(invoice).containsEntry("status", "OPEN").containsEntry("billing_mode", "POSTPAID");
        assertThat(invoice.get("charge_id")).isNull();
        assertThat((BigDecimal) invoice.get("total")).isEqualByComparingTo("1100");
        String invoiceId = String.valueOf(invoice.get("invoice_id"));
        assertThat(items("qb.sponsor.fee-invoice-lines", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", invoiceId)))).hasSize(1);
        // Within the credit limit: buying goes on. Overdue: stopped, reminded (once a week, three times), a task.
        cash(sponsor, 3000);
        run(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 100));
        String source = QbFees.overdueTaskSource(invoiceId);
        assertThat(item(FeeProcesses.OVERDUE_ITEM, body("invoiceId", invoiceId))).isEqualTo("NONE");
        clock.set(clock.instant().plus(Duration.ofDays(31)));
        assertThat(item(FeeProcesses.OVERDUE_ITEM, body("invoiceId", invoiceId))).isEqualTo("REMINDED");
        assertThat(query("SELECT status, reminders_sent FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId)
            .getFirst()).containsEntry("status", "OVERDUE").containsEntry("reminders_sent", new BigDecimal("1"));
        assertThat(openTasks(QbPermissions.FEE_MANAGE, source)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
        assertThat(item(FeeProcesses.OVERDUE_ITEM, body("invoiceId", invoiceId))).isEqualTo("NONE");
        assertThat(codes(refused(KudosExchanges.BUY, sponsorToken(sponsor), body("amount", 100), 422)))
            .contains(FeeCodes.FEE_UNPAID);
        // Paid: the task is done.
        run(FeeProcesses.PAY, sponsorToken(sponsor), body("invoiceId", invoiceId));
        assertThat(openTasks(QbPermissions.FEE_MANAGE, source)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1800");
    }

    @Test
    @Order(4)
    void aFeeByHandAndAnInvoiceVoidedTakeAnotherAdministratorsApproval() {
        switches();
        String sponsor = newSponsor(unique("s-q87-manual"));
        cash(sponsor, 4000);
        Map<String, Object> requested = run(FeeRequests.RECORD, finance(), body("kind", "MANUAL", "sponsorId", sponsor,
            "amount", 2000, "reason", "Custom report"));
        assertThat(requested).containsEntry("status", QbFees.REVIEW);
        assertThat(charges(sponsor)).isEmpty();
        approve((String) requested.get("approvalRequestId"));
        Map<String, Object> request = query("SELECT * FROM qb_fee_request WHERE request_id = ?",
            requested.get("requestId")).getFirst();
        assertThat(request).containsEntry("status", QbFees.DONE);
        Map<String, Object> charge = charges(sponsor).getFirst();
        assertThat(charge).containsEntry("kind", "MANUAL").containsEntry("reason", "Custom report");
        assertThat((BigDecimal) charge.get("total")).isEqualByComparingTo("2200");
        assertThat(request.get("charge_id")).isEqualTo(String.valueOf(charge.get("charge_id")));
        // Prepaid and covered: paid at once.
        Map<String, Object> invoice = invoices(sponsor).getFirst();
        assertThat(invoice).containsEntry("status", "PAID");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1800");
        // Voided: the fee's postings and the payment reversed, the cash given back.
        String invoiceId = String.valueOf(invoice.get("invoice_id"));
        Map<String, Object> voiding = run(FeeRequests.RECORD, finance(), body("kind", "VOID", "invoiceId", invoiceId,
            "reason", "Charged twice"));
        approve((String) voiding.get("approvalRequestId"));
        assertThat(query("SELECT status, void_reason FROM qb_fee_invoice WHERE invoice_id = ?", invoiceId).getFirst())
            .containsEntry("status", "VOID").containsEntry("void_reason", "Charged twice");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("4000");
        assertThat(query("SELECT count(*) AS n FROM ledger_transaction_version WHERE reverses_transaction_id IN "
            + "(SELECT transaction_id FROM ledger_transaction_version WHERE source_entity = 'QbFeeCharge' AND source_id = ?)",
            String.valueOf(charge.get("charge_id"))).getFirst().get("n")).isEqualTo(2L);
        // A rejection changes nothing; one's own account is another administrator's.
        Map<String, Object> again = run(FeeRequests.RECORD, finance(), body("kind", "MANUAL", "sponsorId", sponsor,
            "amount", 500, "reason", "Nope"));
        run("APPROVAL_DECIDE", content(), body("requestId", again.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Not agreed"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_fee_request WHERE request_id = ?", again.get("requestId")).getFirst())
            .containsEntry("status", QbFees.REJECTED);
        assertThat(charges(sponsor)).hasSize(1);
        assertThat(codes(refused(FeeRequests.RECORD, finance(), body("kind", "VOID", "invoiceId", invoiceId,
            "reason", "Again"), 422))).containsExactly(FeeCodes.FEE_INVOICE_STATE);
    }

    @Test
    @Order(5)
    void anOperatorSetsTheBillingOfItsOwnSponsorsOnly() {
        String operator = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("Fee Ops"), "contactName",
            "Owner", "contactEmail", "ops@fee.example")).get("operatorId");
        String member = createUser(unique("q87-op"));
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            member);
        run(OperatorProcesses.MEMBER_ADD, hq(), body("operatorId", operator, "userId", member, "lead", true));
        Actor actor = new Actor(member, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operator));
        String session = "Bearer " + tokens.issue(actor).token();
        String mine = newSponsor(unique("s-q87-ops-own"));
        String other = newSponsor(unique("s-q87-ops-other"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", mine, "operatorId", operator, "reason", "Q87"));
        assertThat(run(FeeProcesses.BILLING_SET, session, body("userId", mine, "billingMode", "POSTPAID",
            "creditLimit", 20000, "reason", "Trusted"))).containsEntry("changed", true);
        refused(FeeProcesses.BILLING_SET, session, body("userId", other, "billingMode", "POSTPAID",
            "creditLimit", 20000, "reason", "Not ours"), 404);
        assertThat(latest("qb_sponsor_version", "onboarding_id", "user_id = '" + mine + "'").getFirst())
            .containsEntry("billing_mode", "POSTPAID").containsEntry("billing_set_by", member);
        // The operator sees the billing on its sponsors' page, and its own sponsors only on the billing page.
        assertThat(items("qb.operator.sponsors", session, Map.of("params", Map.of("userId", mine)))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("billingMode", "POSTPAID").containsEntry("creditLimit", 20000));
        assertThat(items("qb.operator.sponsor-billing", session, Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("userId", mine).containsEntry("billingMode", "POSTPAID")
                .containsEntry("own", true).containsEntry("creditLimit", 20000).containsEntry("unpaidTotal", 0)
                .containsEntry("uninvoicedTotal", 0).containsEntry("billingReason", "Trusted")
                .containsEntry("billingSetBy", member));
    }

    @Test
    @Order(6)
    void aPostpaidSponsorPaysTheMonthsFeesEarlyEachOnAnInvoiceOfItsOwn() {
        switches();
        String sponsor = newSponsor(unique("s-q87-early"));
        run(FeeProcesses.BILLING_SET, hq(), body("userId", sponsor, "billingMode", "POSTPAID", "creditLimit", 5000,
            "reason", "Known company"));
        assertThat(monthly(sponsor)).containsEntry("outcome", "CHARGED");
        String chargeId = String.valueOf(charges(sponsor).getFirst().get("charge_id"));
        // The wallet shows what waits for the month's invoice; without the cash, paying it early is refused.
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("uninvoicedTotal", 1100).containsEntry("unpaidTotal", 0);
        assertThat(items("qb.sponsor.fee-uninvoiced", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("total", 1100).containsEntry("kind", "MONTHLY"));
        assertThat(codes(refused(FeeProcesses.PAY_EARLY, sponsorToken(sponsor), body(), 422)))
            .containsExactly(FeeCodes.FEE_CASH_SHORT);
        cash(sponsor, 3000);
        Map<String, Object> paid = run(FeeProcesses.PAY_EARLY, sponsorToken(sponsor), body());
        assertThat(paid).containsEntry("count", 1).containsEntry("total", 1100).containsEntry("cashBalance", 1900);
        // The fee got an invoice of its own, paid from the charge as a prepaid one's is.
        Map<String, Object> invoice = invoices(sponsor).getFirst();
        assertThat(invoice).containsEntry("status", "PAID").containsEntry("charge_id", chargeId)
            .containsEntry("billing_mode", "POSTPAID");
        assertThat(String.valueOf(invoice.get("invoice_no"))).isEqualTo(((List<?>) paid.get("invoiceNos")).getFirst());
        assertThat(lines(QbFees.CHARGE, chargeId)).containsExactly("1130 DEBIT 1100 @" + sponsor, "4120 CREDIT 1000",
            "2150 CREDIT 100", "2160 DEBIT 1100 @" + sponsor, "1130 CREDIT 1100 @" + sponsor);
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1900");
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("uninvoicedTotal", 0);
        assertThat(items("qb.sponsor.fee-charges", sponsorToken(sponsor), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("invoiceStatus", "PAID")
                .containsEntry("invoiceNo", invoice.get("invoice_no")));
        assertThat(items("qb.sponsor.fee-invoice-lines", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", String.valueOf(invoice.get("invoice_id")))))).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("chargeId"))).isEqualTo(chargeId));
        // Nothing waits any more: paying early again is refused, and the month's invoice has nothing to cover.
        assertThat(codes(refused(FeeProcesses.PAY_EARLY, sponsorToken(sponsor), body(), 422)))
            .containsExactly(FeeCodes.FEE_NOTHING_DUE);
        assertThat(item(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", period()))).isEqualTo("NONE");
        assertThat(invoices(sponsor)).hasSize(1);
        // The next month's fee is invoiced by the job as before, the early-paid one left out.
        clock.set(clock.instant().plus(Duration.ofDays(31)));
        assertThat(monthly(sponsor)).containsEntry("outcome", "CHARGED");
        assertThat(item(FeeProcesses.INVOICE_ITEM, body("sponsorId", sponsor, "period", period()))).isEqualTo("ISSUED");
        Map<String, Object> monthInvoice = invoices(sponsor).getLast();
        assertThat(monthInvoice).containsEntry("status", "OPEN");
        assertThat(monthInvoice.get("charge_id")).isNull();
        assertThat((BigDecimal) monthInvoice.get("total")).isEqualByComparingTo("1100");
        assertThat(items("qb.sponsor.fee-invoice-lines", sponsorToken(sponsor), Map.of("params",
            Map.of("invoiceId", String.valueOf(monthInvoice.get("invoice_id")))))).hasSize(1);
        // The day's reconciliation agrees: nothing is receivable beyond the open invoice.
        assertThat(items("qb.sponsor.wallet", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("uninvoicedTotal", 0).containsEntry("unpaidTotal", 1100);
    }
}
