package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeCodes;
import com.jabiz.quizbuks.fee.FeeCredits;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFeeAccount;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.KudosExchanges;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account that pays the platform's fees (F-M1, docs/quizbuks/plans/impl/F-M.md §M1): the granted credit first,
 * the earliest to expire first, then the cash in the order it arrived; one posting a payment (debit 2190 and 2160 /
 * credit 1130), one part a lot taken; an invoice paid in part ({@code PARTIAL}) and the rest later; a voided invoice's
 * payment given back to the lots it came from; the Kudos wallet never touched; the daily check finding it all agrees.
 */
class FeeAccountIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static boolean switched;

    @BeforeEach
    void switches() {
        if (!switched) {
            param(QbParams.FEE_MONTHLY, "1000");
            param(QbParams.KUDOS_SELL_ENABLED, "true");
            switched = true;
        }
    }

    void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "F-M1 test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    String finance() {
        return tokenOf(user("fm1-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("fm1-content"), QbRoles.ADMIN_CONTENT);
    }

    String period() {
        return FeeRules.period(clock.instant(), TOKYO);
    }

    /** Money in the cash account: Kudos credited and sold back (a lot of its own, OTHER). */
    void cash(String sponsor, long amount) {
        credit(sponsor, amount);
        run(KudosExchanges.SELL, sponsorToken(sponsor), body("amount", amount));
    }

    /**
     * A grant of fee credit as F-M2 will make it: posted (5120 / 2190 for the sponsor), its lot and the wallet's cache.
     * F-M1 has no granting process; the test writes the lot and the cache as that process will, in one go.
     */
    String grant(String sponsor, long amount, Instant grantedAt, Instant expiresAt) {
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime",
            clock.instant().toString(), "description", "Granted credit (test)", "entries", List.of(
                body("accountCode", "5120", "direction", "DEBIT", "amount", amount),
                body("accountCode", "2190", "direction", "CREDIT", "amount", amount, "dimensions",
                    Map.of("party", sponsor)))));
        String grantId = UUID.randomUUID().toString();
        execute("INSERT INTO qb_fee_credit_grant (grant_id, sponsor_id, source, source_key, amount, remaining, "
            + "granted_at, expires_at, version) VALUES (?, ?, 'MANUAL', ?, ?, ?, ?, ?, 1)", grantId, sponsor,
            "test:" + grantId, amount, amount, Timestamp.from(grantedAt), Timestamp.from(expiresAt));
        execute("INSERT INTO qb_wallet (wallet_id, owner_id, kind, balance, pending_count, pending_amount, pending_out, "
            + "version) VALUES (?, ?, 'FEE_CREDIT', ?, 0, 0, 0, 1) ON CONFLICT (owner_id, kind) DO UPDATE SET "
            + "balance = qb_wallet.balance + EXCLUDED.balance, version = qb_wallet.version + 1",
            UUID.randomUUID().toString(), sponsor, amount);
        return grantId;
    }

    BigDecimal balance(String sponsor, String kind) {
        List<Map<String, Object>> rows = query("SELECT balance FROM qb_wallet WHERE owner_id = ? AND kind = ?",
            sponsor, kind);
        return rows.isEmpty() ? BigDecimal.ZERO : (BigDecimal) rows.getFirst().get("balance");
    }

    String monthly(String sponsor) {
        return ((FeeProcesses.ItemOutput) systemRun(FeeProcesses.MONTHLY_ITEM,
            body("sponsorId", sponsor, "period", period()))).outcome();
    }

    Map<String, Object> invoice(String sponsor) {
        return query("SELECT * FROM qb_fee_invoice WHERE sponsor_id = ? ORDER BY issued_at DESC", sponsor).getFirst();
    }

    static List<String> lines(String sourceEntity, String sourceId) {
        return query("SELECT a.account_code, e.direction, e.amount, e.dimension_1 FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = ? AND t.source_id = ? AND t.reverses_transaction_id IS NULL "
            + "ORDER BY t.process_seq_id, e.line_no", sourceEntity, sourceId).stream()
            .map(p -> p.get("account_code") + " " + p.get("direction") + " "
                + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    /** The parts of the sponsor's payments, {@code subAccount:lot:amount} in the order written. */
    static List<String> parts(String sponsor, Map<String, String> names) {
        return query("SELECT sub_account, coalesce(grant_id, cash_lot_id) AS lot, amount FROM qb_fee_payment_version "
            + "WHERE sponsor_id = ? ORDER BY process_seq_id, row_id", sponsor).stream()
            .map(p -> p.get("sub_account") + ":" + names.getOrDefault(String.valueOf(p.get("lot")), "?") + ":"
                + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    static BigDecimal remaining(String table, String key, String id) {
        return (BigDecimal) query("SELECT remaining FROM " + table + " WHERE " + key + " = ?", id).getFirst()
            .get("remaining");
    }

    static String cashLot(String sponsor) {
        return String.valueOf(query("SELECT lot_id FROM qb_cash_lot WHERE sponsor_id = ? ORDER BY arrived_at",
            sponsor).getFirst().get("lot_id"));
    }

    /** What the daily check finds for the sponsor (none: the account agrees with its lots and payments). */
    List<Map<String, Object>> differences(String sponsor) {
        String system = TestTokens.bearer(tokens, "recon-test", QbPermissions.WALLET_SYSTEM);
        List<Map<String, Object>> found = new java.util.ArrayList<>(feeAccountItems(system, Map.of()));
        found.addAll(items("qb.system.recon-fee-receivable", system, Map.of()));
        return found.stream().filter(row -> sponsor.equals(row.get("sponsorId"))).toList();
    }

    /** F-M13: what the invoice was paid with, as the sponsor reads it and as finance does (the same statement twice). */
    Map<String, Object> sources(String token, Object invoiceId) {
        Map<String, Object> own = items("qb.sponsor.fee-invoice-sources", token,
            Map.of("params", Map.of("invoiceId", String.valueOf(invoiceId)))).getFirst();
        assertThat(items("qb.admin.fee-invoice-sources", finance(),
            Map.of("params", Map.of("invoiceId", String.valueOf(invoiceId)))).getFirst()).isEqualTo(own);
        // The identity: the five add up to the total (a voided invoice owes and holds nothing: 0). The prepayment's own
        // gift part is inside the prepayment's, so it is not added.
        BigDecimal sum = BigDecimal.ZERO;
        for (String key : List.of("sourceGift", "sourcePrepayment", "sourceCash", "sourceCreditNote", "sourceUnpaid")) {
            sum = sum.add(new BigDecimal(String.valueOf(own.get(key))));
        }
        assertThat(sum).isEqualByComparingTo(voided(invoiceId) ? BigDecimal.ZERO
            : new BigDecimal(String.valueOf(invoiceRow(invoiceId).get("total"))));
        return own;
    }

    Map<String, Object> invoiceRow(Object invoiceId) {
        return query("SELECT * FROM qb_fee_invoice WHERE invoice_id = ?", String.valueOf(invoiceId)).getFirst();
    }

    boolean voided(Object invoiceId) {
        return "VOID".equals(invoiceRow(invoiceId).get("status"));
    }

    @Test
    void twoLotsOfCreditAndTheCashPayAFeeInFullTheEarliestToExpireFirst() {
        String sponsor = newSponsor(unique("s-fm1-full"));
        Instant now = clock.instant();
        String late = grant(sponsor, 500, now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(60)));
        String soon = grant(sponsor, 300, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(10)));
        // Expired: never spent.
        String expired = grant(sponsor, 900, now.minus(Duration.ofDays(100)), now.minus(Duration.ofDays(1)));
        cash(sponsor, 1000);
        String lot = cashLot(sponsor);
        BigDecimal kudos = balance(sponsor, QbWallets.SPONSOR);

        assertThat(monthly(sponsor)).isEqualTo("CHARGED");

        String chargeId = String.valueOf(invoice(sponsor).get("charge_id"));
        // One posting of the payment, from the charge: 800 of credit, 300 of cash.
        assertThat(lines(QbFees.CHARGE, chargeId)).containsExactly("1130 DEBIT 1100", "4120 CREDIT 1000",
            "2150 CREDIT 100", "2190 DEBIT 800", "2160 DEBIT 300", "1130 CREDIT 1100");
        assertThat(parts(sponsor, Map.of(soon, "soon", late, "late", lot, "cash")))
            .containsExactly("FEE_CREDIT:soon:300", "FEE_CREDIT:late:500", "CASH:cash:300");
        assertThat(invoice(sponsor)).containsEntry("status", "PAID");
        assertThat((BigDecimal) invoice(sponsor).get("paid_amount")).isEqualByComparingTo("1100");
        assertThat(remaining("qb_fee_credit_grant", "grant_id", soon)).isEqualByComparingTo("0");
        assertThat(remaining("qb_fee_credit_grant", "grant_id", late)).isEqualByComparingTo("0");
        assertThat(remaining("qb_fee_credit_grant", "grant_id", expired)).isEqualByComparingTo("900");
        assertThat(remaining("qb_cash_lot", "lot_id", lot)).isEqualByComparingTo("700");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("900");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("700");
        // The Kudos wallet is not the account that pays the platform's fees.
        assertThat(balance(sponsor, QbWallets.SPONSOR)).isEqualByComparingTo(kudos);

        // The sponsor reads the account: the totals, the lots left in their order, the payment's parts.
        String token = sponsorToken(sponsor);
        assertThat(items("qb.sponsor.wallet", token, Map.of()).getFirst())
            .containsEntry("feeCreditBalance", 900).containsEntry("cashBalance", 700);
        assertThat(items("qb.sponsor.fee-account-lots", token, Map.of())).extracting(row -> row.get("subAccount") + ":"
                + row.get("lotId") + ":" + row.get("remaining"))
            .containsExactly("FEE_CREDIT:" + expired + ":900", "CASH:" + lot + ":700");
        assertThat(items("qb.sponsor.fee-payments", token, Map.of())).hasSize(3)
            .allSatisfy(row -> assertThat(row).containsEntry("invoiceNo", invoice(sponsor).get("invoice_no")));
        // Finance reads the same; another sponsor reads none of it.
        assertThat(items("qb.admin.fee-account", finance(), Map.of("params", Map.of("sponsorId", sponsor))).getFirst())
            .containsEntry("feeCreditBalance", 900).containsEntry("cashBalance", 700);
        assertThat(items("qb.admin.fee-payments", finance(), Map.of("params", Map.of("sponsorId", sponsor))))
            .hasSize(3);
        assertThat(items("qb.sponsor.fee-payments", sponsorToken(newSponsor(unique("s-fm1-else"))), Map.of()))
            .isEmpty();
        // F-M13: the invoice's payment status: 800 of granted credit, 300 of cash, nothing owed, the same for finance.
        assertThat(sources(token, invoice(sponsor).get("invoice_id"))).containsEntry("sourceGift", 800)
            .containsEntry("sourceCash", 300).containsEntry("sourcePrepayment", 0).containsEntry("sourceCreditNote", 0)
            .containsEntry("sourceUnpaid", 0);
        assertThat(differences(sponsor)).isEmpty();
        // The monthly report: the payment is FEE_PAID, its columns what each sub-account paid (debit less credit).
        java.time.YearMonth month = java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneOffset.UTC));
        Map<String, Object> feePaid = items("qb.admin.monthly", finance(), Map.of("params", Map.of(
                "from", month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
                "to", month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()),
            "limit", 100)).stream().filter(row -> "FEE_PAID".equals(row.get("category"))).findFirst().orElseThrow();
        assertThat(new BigDecimal(String.valueOf(feePaid.get("feeCredit")))).isGreaterThanOrEqualTo(new BigDecimal("800"));
        assertThat(new BigDecimal(String.valueOf(feePaid.get("sponsorCash")))).isGreaterThanOrEqualTo(new BigDecimal("300"));
    }

    @Test
    void aFeeThePrepaidAccountCannotCoverIsPaidInPartAndTheRestLater() {
        String sponsor = newSponsor(unique("s-fm1-partial"));
        Instant now = clock.instant();
        String credit = grant(sponsor, 200, now, now.plus(Duration.ofDays(30)));
        cash(sponsor, 300);
        String first = cashLot(sponsor);
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");

        Map<String, Object> invoice = invoice(sponsor);
        String invoiceId = String.valueOf(invoice.get("invoice_id"));
        assertThat(invoice).containsEntry("status", QbFees.PARTIAL);
        assertThat(invoice.get("payment_transaction_id")).isNull();
        assertThat((BigDecimal) invoice.get("paid_amount")).isEqualByComparingTo("500");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("0");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
        String token = sponsorToken(sponsor);
        // What is still owed: 600; nothing to pay it with yet.
        assertThat(items("qb.sponsor.wallet", token, Map.of()).getFirst()).containsEntry("unpaidTotal", 600);
        // F-M13: 200 of granted credit and 300 of cash paid it; 600 is owed.
        assertThat(sources(token, invoiceId)).containsEntry("sourceGift", 200).containsEntry("sourceCash", 300)
            .containsEntry("sourceUnpaid", 600);
        assertThat(codes(refused(FeeProcesses.PAY, token, body("invoiceId", invoiceId), 422)))
            .containsExactly(FeeCodes.FEE_CASH_SHORT);

        cash(sponsor, 400);
        assertThat(run(FeeProcesses.PAY, token, body("invoiceId", invoiceId))).containsEntry("status", QbFees.PARTIAL)
            .containsEntry("paidAmount", 900).containsEntry("paidNow", 400).containsEntry("cashPaid", 400)
            .containsEntry("cashBalance", 0);
        cash(sponsor, 1000);
        assertThat(run(FeeProcesses.PAY, token, body("invoiceId", invoiceId))).containsEntry("status", QbFees.PAID)
            .containsEntry("paidAmount", 1100).containsEntry("paidNow", 200).containsEntry("cashBalance", 800);
        assertThat(sources(token, invoiceId)).containsEntry("sourceGift", 200).containsEntry("sourceCash", 900)
            .containsEntry("sourceUnpaid", 0);
        Map<String, Object> paid = invoice(sponsor);
        assertThat(paid.get("payment_transaction_id")).isNotNull();
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat(lines(QbFees.INVOICE, invoiceId)).containsExactly("2160 DEBIT 400", "1130 CREDIT 400",
            "2160 DEBIT 200", "1130 CREDIT 200");
        assertThat(codes(refused(FeeProcesses.PAY, token, body("invoiceId", invoiceId), 422)))
            .containsExactly(FeeCodes.FEE_INVOICE_STATE);
        // Never below 0: every wallet and lot.
        assertThat(query("SELECT 1 FROM qb_wallet WHERE owner_id = ? AND balance < 0", sponsor)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_cash_lot WHERE sponsor_id = ? AND remaining < 0", sponsor)).isEmpty();
        assertThat(differences(sponsor)).isEmpty();

        // Voided: each part given back to its lot, the credit to the grant, the cash to the lots it came from.
        Map<String, Object> voiding = run(FeeRequests.RECORD, finance(), body("kind", "VOID", "invoiceId", invoiceId,
            "reason", "Charged by mistake"));
        run("APPROVAL_DECIDE", content(), body("requestId", voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(invoice(sponsor)).containsEntry("status", QbFees.VOID);
        // F-M13c: the sponsor is told once, with what went back read from the payments given back; the reason stays inside.
        deliverEvents();
        assertThat(templatesTo(sponsor)).contains("qb.fee.invoice-voided");
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.invoice-voided".equals(row.get("template")))
            .singleElement().satisfies(row -> {
                String params = String.valueOf(row.get("params"));
                assertThat(params).contains("1,100").doesNotContain("Charged by mistake");
            });
        // Voided: what was paid was given back, so nothing is owed and nothing stays paid.
        assertThat(sources(token, invoiceId)).containsEntry("sourceGift", 0).containsEntry("sourceCash", 0)
            .containsEntry("sourceUnpaid", 0);
        assertThat(remaining("qb_fee_credit_grant", "grant_id", credit)).isEqualByComparingTo("200");
        assertThat(remaining("qb_cash_lot", "lot_id", first)).isEqualByComparingTo("300");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("200");
        assertThat(balance(sponsor, QbWallets.CASH)).isEqualByComparingTo("1700");
        assertThat(query("SELECT sum(amount) AS n FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)
            .getFirst().get("n")).satisfies(n -> assertThat((BigDecimal) n).isEqualByComparingTo("0"));
        assertThat(differences(sponsor)).isEmpty();
    }

    @Test
    void feesPaidEarlyTakeTheCreditFirstAndOnlyWhenTheAccountCoversThemAll() {
        String sponsor = newSponsor(unique("s-fm1-early"));
        run(FeeProcesses.BILLING_SET, TestTokens.bearer(tokens, "fm1-hq", QbPermissions.FEE_BILLING), body("userId",
            sponsor, "billingMode", "POSTPAID", "creditLimit", 5000, "reason", "Known company"));
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        Instant now = clock.instant();
        grant(sponsor, 500, now, now.plus(Duration.ofDays(30)));
        cash(sponsor, 500);
        String token = sponsorToken(sponsor);
        // 1000 of 1100: refused, nothing taken.
        assertThat(codes(refused(FeeProcesses.PAY_EARLY, token, body(), 422))).containsExactly(FeeCodes.FEE_CASH_SHORT);
        assertThat(query("SELECT 1 FROM qb_fee_payment_version WHERE sponsor_id = ?", sponsor)).isEmpty();
        cash(sponsor, 1000);
        assertThat(run(FeeProcesses.PAY_EARLY, token, body())).containsEntry("count", 1).containsEntry("total", 1100)
            .containsEntry("cashBalance", 900);
        String chargeId = String.valueOf(invoice(sponsor).get("charge_id"));
        assertThat(lines(QbFees.CHARGE, chargeId)).containsExactly("1130 DEBIT 1100", "4120 CREDIT 1000",
            "2150 CREDIT 100", "2190 DEBIT 500", "2160 DEBIT 600", "1130 CREDIT 1100");
        assertThat(invoice(sponsor)).containsEntry("status", QbFees.PAID);
        assertThat(differences(sponsor)).isEmpty();
    }

    /**
     * F-S4b review M3: an invoice voided after a lot that paid part of it expired and was voided gives that part back
     * to the lot voided again at once (the lot never holds anything again; before, the refund broke the lot's check
     * and the void never went through).
     */
    @Test
    void whatAVoidedInvoiceGivesBackToAnExpiredAndVoidedLotIsVoidedAgain() {
        String sponsor = newSponsor(unique("s-fm1-expired"));
        Instant now = clock.instant();
        String credit = grant(sponsor, 2000, now.minus(Duration.ofDays(1)), now.plus(Duration.ofMinutes(1)));
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        assertThat(remaining("qb_fee_credit_grant", "grant_id", credit)).isEqualByComparingTo("900");
        clock.set(now.plus(Duration.ofMinutes(2)));
        assertThat(((FeeProcesses.ItemOutput) systemRun(FeeCredits.EXPIRE_ITEM, body("grantId", credit))).outcome())
            .isEqualTo("VOIDED");

        String invoiceId = String.valueOf(invoice(sponsor).get("invoice_id"));
        Map<String, Object> voiding = run(FeeRequests.RECORD, finance(), body("kind", "VOID", "invoiceId", invoiceId,
            "reason", "Charged by mistake"));
        run("APPROVAL_DECIDE", content(), body("requestId", voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(invoice(sponsor)).containsEntry("status", QbFees.VOID);
        Map<String, Object> lot = query("SELECT remaining, voided_amount FROM qb_fee_credit_grant WHERE grant_id = ?",
            credit).getFirst();
        assertThat((BigDecimal) lot.get("remaining")).isEqualByComparingTo("0");
        assertThat((BigDecimal) lot.get("voided_amount")).isEqualByComparingTo("2000");
        assertThat(lines(QbFeeAccount.GRANT, credit)).containsExactly("2190 DEBIT 900", "5140 CREDIT 900",
            "2190 DEBIT 1100", "5140 CREDIT 1100");
        assertThat(balance(sponsor, QbWallets.FEE_CREDIT)).isEqualByComparingTo("0");
        assertThat(differences(sponsor)).isEmpty();
    }

    /** F-M13c (review M3): an invoice voided with nothing paid says so, and does not speak of money given back. */
    @Test
    void anInvoiceVoidedUnpaidSaysNothingWasPaidAndNothingWentBack() {
        String sponsor = newSponsor(unique("s-fm13c-void-unpaid"));
        assertThat(monthly(sponsor)).isEqualTo("CHARGED");
        String invoiceId = String.valueOf(invoice(sponsor).get("invoice_id"));
        assertThat(invoice(sponsor)).containsEntry("status", QbFees.OPEN);
        Map<String, Object> voiding = run(FeeRequests.RECORD, finance(), body("kind", "VOID", "invoiceId", invoiceId,
            "reason", "Charged by mistake"));
        run("APPROVAL_DECIDE", content(), body("requestId", voiding.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        deliverEvents();
        assertThat(mailTo(sponsor)).filteredOn(row -> "qb.fee.invoice-voided".equals(row.get("template")))
            .singleElement().satisfies(row -> assertThat(String.valueOf(row.get("params")))
                .contains("Nothing had been paid").doesNotContain("went back").doesNotContain("Charged by mistake"));
    }
}
