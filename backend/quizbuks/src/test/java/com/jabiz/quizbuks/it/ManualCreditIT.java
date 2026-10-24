package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.messaging.MailProcesses;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.ManualCredits;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.quizbuks.wallet.TopUpReceipts;
import com.jabiz.quizbuks.wallet.WalletCodes;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manual credits (docs/quizbuks/plans/Q18-bank-settings-credit.md, Q18b): finance credits a sponsor's wallet by hand,
 * money received outside the system (debit 1120) or a grant (debit 5120), and only another administrator's approval
 * credits it; the sponsor sees it only then, and a grant has no receipt.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManualCreditIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    String finance() {
        return tokenOf(user("manual-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("manual-content"), QbRoles.ADMIN_CONTENT);
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TOKYO);
    }

    Map<String, Object> outside(String sponsorId, long amount, String reference) {
        return run(ManualCredits.RECORD, finance(), body("sponsorId", sponsorId, "kind", "PAID_OUTSIDE", "amount",
            amount, "reason", "Paid by cash at the office", "receivedOn", today().toString(), "bankReference",
            reference));
    }

    Map<String, Object> grant(String sponsorId, long amount) {
        return run(ManualCredits.RECORD, finance(), body("sponsorId", sponsorId, "kind", "GRANT", "amount", amount,
            "reason", "Launch campaign"));
    }

    Map<String, Object> topUp(String topUpId) {
        return query("SELECT * FROM qb_topup WHERE topup_id = ?", topUpId).getFirst();
    }

    void approve(String requestId) {
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
    }

    List<String> postings(String topUpId) {
        return BankTopUpIT.postings(topUpId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    List<Map<String, Object>> sponsorTopUps(String sponsor) {
        return items("qb.sponsor.topups", sponsorToken(sponsor), Map.of());
    }

    Map<String, Object> monthly(String category) {
        YearMonth month = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        return items("qb.admin.monthly", finance(), Map.of("params", Map.of(
            "from", month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
            "to", month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()))).stream()
            .filter(row -> category.equals(row.get("category"))).findFirst().orElseThrow();
    }

    @Test
    @Order(1)
    void moneyReceivedOutsideIsCreditedOnlyOnceAnotherAdministratorApprovesIt() {
        String sponsor = newSponsor("s-manual-outside");
        Map<String, Object> recorded = outside(sponsor, 30_000, "CASH-0001");
        String topUpId = (String) recorded.get("topUpId");
        String requestId = (String) recorded.get("approvalRequestId");
        assertThat(recorded).containsEntry("status", "REVIEW");
        assertThat(requestId).isNotNull();
        assertThat(topUp(topUpId)).containsEntry("method", "MANUAL").containsEntry("manual_kind", "PAID_OUTSIDE")
            .containsEntry("bank_reference", "CASH-0001").containsEntry("sponsor_visible", false)
            .containsEntry("approval_request_id", requestId);
        assertThat((String) topUp(topUpId).get("transfer_code")).matches("[0-9]{7}");
        // Nothing yet: no posting, no Kudos, and the sponsor sees nothing.
        assertThat(postings(topUpId)).isEmpty();
        assertThat(wallet(sponsor)).isNull();
        assertThat(sponsorTopUps(sponsor)).isEmpty();
        assertThat(items("qb.sponsor.transactions", sponsorToken(sponsor), Map.of())).isEmpty();

        // The one who recorded it cannot approve it.
        assertThat(codes(refused("APPROVAL_DECIDE", finance(), body("requestId", requestId, "decision", "APPROVE"),
            422))).containsExactly("APPROVAL_OWN_REQUEST");

        approve(requestId);
        assertThat(topUp(topUpId)).containsEntry("status", "PAID").containsEntry("sponsor_visible", true);
        assertThat(balance(sponsor)).isEqualByComparingTo("30000");
        assertThat(postings(topUpId)).containsExactly("1120 DEBIT 30000", "2110 CREDIT 30000");
        assertThat(BankTopUpIT.postings(topUpId).getFirst())
            .containsEntry("reference", ManualCredits.REFERENCE_PREFIX + topUp(topUpId).get("transfer_code"));
        assertThat(sponsorTopUps(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("method", "MANUAL").containsEntry("status", "PAID"));
        // Money was received: it has a receipt, its reference the credit's code.
        assertThat(items("qb.sponsor.topup-receipt", sponsorToken(sponsor), Map.of("params",
            Map.of("topUpId", topUpId)))).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("paymentReference", topUp(topUpId).get("transfer_code")));

        // The approval's event again: nothing more.
        Map<String, Object> request = query("SELECT DISTINCT ON (request_id) * FROM sys_approval_request_version "
            + "WHERE request_id = ? ORDER BY request_id, version_no DESC", java.util.UUID.fromString(requestId))
            .getFirst();
        systemRun(ManualCredits.APPROVAL_RESULT, body("subject", ManualCredits.APPROVAL_SUBJECT, "entityId", topUpId,
            "status", "APPROVED", "requestId", requestId, "preparerId", request.get("preparer_id"),
            "contentHash", request.get("content_hash")));
        assertThat(balance(sponsor)).isEqualByComparingTo("30000");
        assertThat(postings(topUpId)).hasSize(2);

        assertThat(((MailProcesses.MailOutput) systemRun(MailProcesses.TOPUP, body("topUpId", topUpId, "occurredAt",
            clock.instant().toString()))).template()).isEqualTo("qb.topup.paid");
        Map<String, Object> month = monthly("MANUAL_TOPUP");
        assertThat(((Number) month.get("transactionCount")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(String.valueOf(month.get("bank")))).isEqualByComparingTo("30000");
        assertBooksBalance();
        assertThat(((Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN", body("scheduledTime",
            clock.instant().toString()))).differences()).isZero();
    }

    @Test
    @Order(2)
    void aGrantIsPostedToPromotionExpensesAndHasNoReceipt() {
        String sponsor = newSponsor("s-manual-grant");
        Map<String, Object> recorded = grant(sponsor, 5_000);
        String topUpId = (String) recorded.get("topUpId");
        assertThat(topUp(topUpId)).containsEntry("manual_kind", "GRANT").containsEntry("received_on", null)
            .containsEntry("bank_reference", null).containsEntry("manual_reason", "Launch campaign");
        approve((String) recorded.get("approvalRequestId"));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("5000");
        assertThat(postings(topUpId)).containsExactly("5120 DEBIT 5000", "2110 CREDIT 5000");
        assertThat(sponsorTopUps(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("method", "MANUAL"));

        // No money was received: no receipt.
        assertThat(items("qb.sponsor.topup-receipt", sponsorToken(sponsor), Map.of("params",
            Map.of("topUpId", topUpId)))).isEmpty();
        assertThat(codes(refused(TopUpReceipts.ISSUE, sponsorToken(sponsor), body("topUpId", topUpId, "language",
            "en"), 422))).contains(WalletCodes.RECEIPT_GRANT);

        // The sponsor is told of Kudos granted, not of a payment received.
        assertThat(((MailProcesses.MailOutput) systemRun(MailProcesses.TOPUP, body("topUpId", topUpId, "occurredAt",
            clock.instant().toString()))).template()).isEqualTo("qb.topup.granted");
        Map<String, Object> month = monthly("GRANT");
        assertThat(((Number) month.get("transactionCount")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(String.valueOf(month.get("promotion")))).isEqualByComparingTo("5000");
        assertThat(new BigDecimal(String.valueOf(month.get("sponsorWallets")))).isEqualByComparingTo("-5000");
        assertBooksBalance();
    }

    @Test
    @Order(3)
    void aRejectedCreditIsCancelledAndItsStatementEntryMayBeRecordedAgain() {
        String sponsor = newSponsor("s-manual-reject");
        Map<String, Object> recorded = outside(sponsor, 8_000, "CASH-REJECT");
        String topUpId = (String) recorded.get("topUpId");
        run("APPROVAL_DECIDE", content(), body("requestId", recorded.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Wrong amount"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "CANCELLED").containsEntry("bank_reference", null)
            .containsEntry("sponsor_visible", false);
        assertThat(wallet(sponsor)).isNull();
        assertThat(postings(topUpId)).isEmpty();
        assertThat(sponsorTopUps(sponsor)).isEmpty();

        Map<String, Object> again = outside(sponsor, 8_500, "CASH-REJECT");
        approve((String) again.get("approvalRequestId"));
        assertThat(balance(sponsor)).isEqualByComparingTo("8500");
        assertThat(sponsorTopUps(sponsor)).singleElement().satisfies(row -> assertThat(row)
            .containsEntry("topUpId", again.get("topUpId")));
    }

    @Test
    @Order(4)
    void theCreditIsChecked() {
        String sponsor = newSponsor("s-manual-checks");
        // Not an approved sponsor.
        assertThat(codes(refused(ManualCredits.RECORD, finance(), body("sponsorId", user("manual-not-sponsor"),
            "kind", "GRANT", "amount", 1_000, "reason", "x"), 422))).containsExactly(WalletCodes.TOPUP_MANUAL_SPONSOR);
        // Money received outside needs its entry; a grant has none.
        assertThat(codes(refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "PAID_OUTSIDE",
            "amount", 1_000, "reason", "x"), 422))).containsExactly(WalletCodes.TOPUP_MANUAL_KIND);
        assertThat(codes(refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "GRANT",
            "amount", 1_000, "reason", "x", "receivedOn", today().toString(), "bankReference", "R"), 422)))
            .containsExactly(WalletCodes.TOPUP_MANUAL_KIND);
        assertThat(codes(refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "PAID_OUTSIDE",
            "amount", 1_000, "reason", "x", "receivedOn", today().plusDays(1).toString(), "bankReference", "R"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_DATE_FUTURE);
        refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "OTHER", "amount", 1_000,
            "reason", "x"), 400);
        refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "GRANT", "amount", 0,
            "reason", "x"), 400);
        // A ceiling on what two people create by hand.
        refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "GRANT", "amount", 10_000_001,
            "reason", "x"), 400);
        // One statement entry for one credit: the same entry, typed otherwise, is the same.
        outside(sponsor, 1_000, "CASH-ONCE");
        assertThat(codes(refused(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "PAID_OUTSIDE",
            "amount", 1_000, "reason", "x", "receivedOn", today().toString(), "bankReference", " ｃａｓｈ - once"),
            422))).containsExactly(WalletCodes.TOPUP_BANK_REFERENCE_USED);
        // Nobody credits their own wallet, and a sponsor or a content administrator cannot record.
        String financeSponsor = newSponsor("s-manual-finance-sponsor");
        assertThat(codes(refused(ManualCredits.RECORD, TestTokens.bearer(tokens, financeSponsor,
            QbPermissions.TOPUP_MANUAL_RECORD), body("sponsorId", financeSponsor, "kind", "GRANT", "amount", 1_000,
            "reason", "x"), 422))).containsExactly(WalletCodes.TOPUP_MANUAL_OWN);
        refused(ManualCredits.RECORD, sponsorToken(sponsor), body("sponsorId", sponsor, "kind", "GRANT", "amount",
            1_000, "reason", "x"), 403);
        refused(ManualCredits.RECORD, content(), body("sponsorId", sponsor, "kind", "GRANT", "amount", 1_000,
            "reason", "x"), 403);
    }

    @Test
    @Order(5)
    void aSponsorWhoIsAlsoAnAdministratorDoesNotApproveTheirOwnCredit() {
        String sponsor = newSponsor("s-manual-self");
        Map<String, Object> recorded = grant(sponsor, 2_000);
        String topUpId = (String) recorded.get("topUpId");
        run("APPROVAL_DECIDE", TestTokens.bearer(tokens, sponsor, "approval.decide",
            QbPermissions.TOPUP_BANK_APPROVE, "task.read"), body("requestId", recorded.get("approvalRequestId"),
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "CANCELLED");
        assertThat(wallet(sponsor)).isNull();
        assertThat(postings(topUpId)).isEmpty();
        assertThat(query("SELECT title_key FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY "
            + "task_id, version_no DESC) t WHERE source_key = ?", "topup-manual:" + topUpId)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("title_key", "task.qb.topup-manual.approved-by-sponsor"));
    }

    @Test
    @Order(6)
    void whoRecordsAndWhoApprovesReadTheCreditsAndASponsorDoesNot() {
        String sponsor = newSponsor("s-manual-read");
        String topUpId = (String) grant(sponsor, 3_000).get("topUpId");
        for (String token : List.of(finance(), content())) {
            assertThat(items("qb.admin.manual-credits", token, Map.of("filters", List.of(Map.of("field", "topUpId",
                "op", "eq", "value", topUpId))))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "REVIEW").containsEntry("manualKind", "GRANT")
                .containsEntry("sponsorId", sponsor));
        }
        client.post().uri("/api/queries/qb.admin.manual-credits").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, sponsorToken(sponsor)).bodyValue(Map.of()).exchange().expectStatus()
            .isForbidden();
        // The sponsor's own top-ups do not include it while it is under review, by id either.
        refused(TopUpReceipts.ISSUE, sponsorToken(sponsor), body("topUpId", topUpId, "language", "en"), 404);
    }

    @Test
    @Order(7)
    void theDatabaseKeepsAManualCreditToItsStates() {
        String sponsor = newSponsor("s-manual-db");
        String topUpId = (String) grant(sponsor, 1_000).get("topUpId");
        // A grant with a statement entry, or shown to the sponsor before it is paid, is refused by the database.
        assertThatThrownBy(() -> execute("UPDATE qb_topup SET received_on = CURRENT_DATE, bank_reference = 'X' "
            + "WHERE topup_id = '" + topUpId + "'")).hasMessageContaining("qb_topup_manual_kind");
        // Shown to the sponsor is derived, never written.
        assertThatThrownBy(() -> execute("UPDATE qb_topup SET sponsor_visible = true WHERE topup_id = '" + topUpId
            + "'")).hasMessageContaining("generated");
        assertThatThrownBy(() -> execute("UPDATE qb_topup SET manual_kind = NULL WHERE topup_id = '" + topUpId
            + "'")).hasMessageContaining("qb_topup_method");
    }
}
