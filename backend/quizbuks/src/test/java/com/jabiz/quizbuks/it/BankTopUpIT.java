package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.BankTopUps;
import com.jabiz.quizbuks.wallet.Reconciliation;
import com.jabiz.quizbuks.wallet.WalletCodes;
import com.jabiz.runtime.job.JobRunner;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Top-ups by bank transfer (docs/quizbuks/plans/Q13-bank-transfer.md, Q13a): the sponsor asks and reports, finance
 * records what the statement shows, and only another administrator's approval credits the wallet (B-1) — at what was
 * received (B-2), posted debit 1120 / credit 2110, once.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BankTopUpIT extends PlayItSupport {

    static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired
    @Qualifier("qbBankTopUpExpireJob")
    JobDefinition<?> expireJob;

    private static boolean bankSetUp;

    /** The receiving account and the limits (four eyes), once per class. */
    void bankSetUp() {
        if (bankSetUp) {
            return;
        }
        bankSetUp = true;
        for (Map.Entry<String, String> param : Map.of(QbParams.BANK_RECEIVING_BANK, "Mizuho Bank (0001)",
            QbParams.BANK_RECEIVING_BRANCH, "Head office (100)", QbParams.BANK_RECEIVING_TYPE, "Ordinary",
            QbParams.BANK_RECEIVING_NUMBER, "1234567", QbParams.BANK_RECEIVING_HOLDER, "クイズブックス（カ",
            QbParams.TOPUP_BANK_MIN, "1000", QbParams.TOPUP_BANK_MAX, "1000000").entrySet()) {
            Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
                "values", Map.of("paramKey", param.getKey(), "value", param.getValue()), "reason", "Bank"));
            run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        }
    }

    String finance() {
        return tokenOf(user("bank-finance"), QbRoles.ADMIN_FINANCE);
    }

    String content() {
        return tokenOf(user("bank-content"), QbRoles.ADMIN_CONTENT);
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TOKYO);
    }

    Map<String, Object> ask(String sponsorId, long amount) {
        return run(BankTopUps.START, sponsorToken(sponsorId), body("amount", amount));
    }

    Map<String, Object> record(String token, String topUpId, long amount, String reference) {
        return run(BankTopUps.RECORD, token, body("topUpId", topUpId, "amount", amount, "receivedOn",
            today().toString(), "bankReference", reference));
    }

    Map<String, Object> topUp(String topUpId) {
        return query("SELECT * FROM qb_topup WHERE topup_id = ?", topUpId).getFirst();
    }

    List<Map<String, Object>> tasksAbout(String topUpId) {
        return query("SELECT status, assignee_permission FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version "
            + "ORDER BY task_id, version_no DESC) t WHERE source_key = ?", "topup-bank:" + topUpId);
    }

    static List<Map<String, Object>> postings(String topUpId) {
        return query("SELECT t.booking_time, t.reference, a.account_code, e.direction, e.amount, e.dimension_1 "
            + "FROM ledger_transaction_version t JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbTopUp' AND t.source_id = ? ORDER BY e.line_no", topUpId);
    }

    @Test
    @Order(1)
    void withoutTheReceivingAccountNothingIsAskedFor() {
        assertThat(codes(refused(BankTopUps.START, sponsorToken(sponsorA()), body("amount", 5_000), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_UNAVAILABLE);
    }

    @Test
    @Order(2)
    void aTransferIsCreditedOnlyOnceAnotherAdministratorApprovesWhatFinanceRecorded() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-credit");
        Map<String, Object> asked = ask(sponsor, 50_000);
        String topUpId = (String) asked.get("topUpId");
        assertThat(asked).containsEntry("status", "AWAITING");
        assertThat((String) asked.get("transferCode")).matches("[0-9]{7}");
        assertThat(items("qb.sponsor.bank-account", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("accountNumber", "1234567");

        run(BankTopUps.REPORT, sponsorToken(sponsor), body("topUpId", topUpId, "payerName",
            asked.get("transferCode") + " カ）エスバンク", "transferDate", today().toString()));
        assertThat(topUp(topUpId)).containsEntry("status", "REPORTED");
        assertThat(tasksAbout(topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("status", "OPEN").containsEntry("assignee_permission", "qb.topup.bank.record"));

        // The bank kept its fee: what arrived is credited, not what was asked for.
        Map<String, Object> recorded = record(finance(), topUpId, 49_560, "ST-0001");
        assertThat(recorded).containsEntry("status", "REVIEW");
        String requestId = (String) recorded.get("approvalRequestId");
        assertThat(requestId).isNotNull();
        assertThat(topUp(topUpId)).containsEntry("amount", new BigDecimal("49560"))
            .containsEntry("requested_amount", new BigDecimal("50000"));
        assertThat(wallet(sponsor)).isNull();
        assertThat(postings(topUpId)).isEmpty();
        assertThat(tasksAbout(topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("status", "DONE"));

        // The one who recorded it cannot approve it.
        assertThat(codes(refused("APPROVAL_DECIDE", finance(), body("requestId", requestId, "decision", "APPROVE"),
            422))).containsExactly("APPROVAL_OWN_REQUEST");
        assertThat(topUp(topUpId)).containsEntry("status", "REVIEW");

        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("49560");
        assertThat(postings(topUpId)).extracting(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString())
            .containsExactly("1120 DEBIT 49560", "2110 CREDIT 49560");
        assertThat(postings(topUpId).getFirst()).containsEntry("reference", asked.get("transferCode"));
        assertThat(ledger("2110", sponsor)).isEqualByComparingTo("49560");

        // The approval's event again: nothing more.
        Map<String, Object> request = query("SELECT DISTINCT ON (request_id) * FROM sys_approval_request_version "
            + "WHERE request_id = ? ORDER BY request_id, version_no DESC", java.util.UUID.fromString(requestId))
            .getFirst();
        systemRun(BankTopUps.APPROVAL_RESULT, body("subject", BankTopUps.APPROVAL_SUBJECT, "entityId", topUpId,
            "status", "APPROVED", "requestId", requestId, "preparerId", request.get("preparer_id"),
            "contentHash", request.get("content_hash")));
        assertThat(balance(sponsor)).isEqualByComparingTo("49560");
        assertThat(postings(topUpId)).hasSize(2);
        assertThat(items("qb.sponsor.topups", sponsorToken(sponsor), Map.of()).getFirst())
            .containsEntry("method", "BANK").containsEntry("status", "PAID");
        assertBooksBalance();
        assertThat(((Reconciliation.ReconcileOutput) systemRun("QB_RECONCILE_RUN", body("scheduledTime",
            clock.instant().toString()))).differences()).isZero();
        // The monthly report (F-08) has a kind of its own for them.
        java.time.YearMonth month = java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneOffset.UTC));
        Map<String, Object> monthly = items("qb.admin.monthly", finance(), Map.of("params", Map.of(
            "from", month.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString(),
            "to", month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()))).stream()
            .filter(row -> "BANK_TOPUP".equals(row.get("category"))).findFirst().orElseThrow();
        assertThat(((Number) monthly.get("transactionCount")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(String.valueOf(monthly.get("bank")))).isEqualByComparingTo("49560");
    }

    @Test
    @Order(3)
    void aRejectedRecordGoesBackAndTheRightEntryIsRecordedAgain() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-reject");
        String topUpId = (String) ask(sponsor, 20_000).get("topUpId");
        String first = (String) record(finance(), topUpId, 2_000, "ST-WRONG").get("approvalRequestId");
        run("APPROVAL_DECIDE", content(), body("requestId", first, "decision", "REJECT", "reason", "Not this one"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "AWAITING").containsEntry("amount", new BigDecimal("20000"))
            .containsEntry("bank_reference", null).containsEntry("recorded_by", null)
            .containsEntry("approval_request_id", null);
        // Finance hears of it, to record the right entry.
        assertThat(query("SELECT title_key, status FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY "
            + "task_id, version_no DESC) t WHERE source_key = ?", "topup-bank:" + topUpId + ":" + first))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("title_key",
                "task.qb.topup-bank.rejected").containsEntry("status", "OPEN"));

        String second = (String) record(finance(), topUpId, 20_000, "ST-RIGHT").get("approvalRequestId");
        assertThat(second).isNotEqualTo(first);
        run("APPROVAL_DECIDE", content(), body("requestId", second, "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("20000");
        assertBooksBalance();
    }

    @Test
    @Order(4)
    void theRequestAndTheRecordAreChecked() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-checks");
        String other = newSponsor("s-bank-other");
        assertThat(codes(refused(BankTopUps.START, sponsorToken(sponsor), body("amount", 500), 422)))
            .containsExactly(WalletCodes.TOPUP_AMOUNT_RANGE);
        String topUpId = (String) ask(sponsor, 10_000).get("topUpId");
        ask(sponsor, 10_000);
        ask(sponsor, 10_000);
        assertThat(codes(refused(BankTopUps.START, sponsorToken(sponsor), body("amount", 10_000), 422)))
            .containsExactly(WalletCodes.TOPUP_TOO_MANY_OPEN);

        // Another sponsor's request is not found.
        refused(BankTopUps.REPORT, sponsorToken(other), body("topUpId", topUpId, "payerName", "カ）アザー",
            "transferDate", today().toString()), 404);
        refused(BankTopUps.CANCEL, sponsorToken(other), body("topUpId", topUpId), 404);
        assertThat(codes(refused(BankTopUps.REPORT, sponsorToken(sponsor), body("topUpId", topUpId, "payerName",
            "カ）エス\u0007", "transferDate", today().plusDays(1).toString()), 422)))
            .containsExactlyInAnyOrder(WalletCodes.TOPUP_BANK_PAYER_NAME, WalletCodes.TOPUP_BANK_DATE_FUTURE);

        // One statement entry for one top-up; a paid one is not recorded again.
        String paid = (String) ask(other, 10_000).get("topUpId");
        String requestId = (String) record(finance(), paid, 10_000, "ST-ONCE").get("approvalRequestId");
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", topUpId, "amount", 10_000,
            "receivedOn", today().toString(), "bankReference", "ST-ONCE"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_REFERENCE_USED);
        // The same entry typed otherwise (case, width, spaces) is the same entry.
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", topUpId, "amount", 10_000,
            "receivedOn", today().toString(), "bankReference", " ｓｔ - once"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_REFERENCE_USED);
        assertThat(topUp(paid)).containsEntry("bank_reference", "ST-ONCE");
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", paid, "amount", 10_000,
            "receivedOn", today().toString(), "bankReference", "ST-TWICE"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_STATE);
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", paid, "amount", 10_000,
            "receivedOn", today().toString(), "bankReference", "ST-AGAIN"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_STATE);
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", topUpId, "amount", 10_000,
            "receivedOn", today().plusDays(1).toString(), "bankReference", "ST-LATER"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_DATE_FUTURE);

        // A sponsor cannot record, and withdrawing ends the request (and finance's task).
        refused(BankTopUps.RECORD, sponsorToken(sponsor), body("topUpId", topUpId, "amount", 10_000, "receivedOn",
            today().toString(), "bankReference", "ST-SELF"), 403);
        run(BankTopUps.REPORT, sponsorToken(sponsor), body("topUpId", topUpId, "payerName", "カ）エス",
            "transferDate", today().toString()));
        run(BankTopUps.CANCEL, sponsorToken(sponsor), body("topUpId", topUpId));
        assertThat(topUp(topUpId)).containsEntry("status", "CANCELLED");
        assertThat(tasksAbout(topUpId)).singleElement().satisfies(task -> assertThat(task)
            .containsEntry("status", "CANCELLED"));
        assertThat(codes(refused(BankTopUps.CANCEL, sponsorToken(sponsor), body("topUpId", topUpId), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_STATE);
    }

    @Test
    @Order(5)
    void aRequestNoMoneyCameForExpiresAndMoneyThatComesLaterIsStillRecorded() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-expiry");
        String topUpId = (String) ask(sponsor, 30_000).get("topUpId");
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.execute(expireJob, clock.instant().plus(Duration.ofDays(29))).outcome())
            .isEqualTo(JobRunner.Outcome.SUCCEEDED);
        assertThat(topUp(topUpId)).containsEntry("status", "AWAITING");
        clock.advance(Duration.ofDays(31));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.execute(expireJob, clock.instant());
        assertThat(topUp(topUpId)).containsEntry("status", "EXPIRED");

        String requestId = (String) record(finance(), topUpId, 30_000, "ST-LATE").get("approvalRequestId");
        run("APPROVAL_DECIDE", content(), body("requestId", requestId, "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        assertThat(balance(sponsor)).isEqualByComparingTo("30000");
    }

    @Test
    @Order(6)
    void whoRecordsAndWhoApprovesReadTheBankTopUpsAndASponsorDoesNot() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-read");
        String topUpId = (String) ask(sponsor, 15_000).get("topUpId");
        for (String token : List.of(finance(), content())) {
            assertThat(items("qb.admin.bank-topups", token, Map.of("filters", List.of(Map.of("field", "topUpId",
                "op", "eq", "value", topUpId))))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "AWAITING").containsEntry("sponsorId", sponsor));
        }
        client.post().uri("/api/queries/qb.admin.bank-topups").contentType(org.springframework.http.MediaType
                .APPLICATION_JSON).header(org.springframework.http.HttpHeaders.AUTHORIZATION, sponsorToken(sponsor))
            .bodyValue(Map.of()).exchange().expectStatus().isForbidden();
    }

    @Test
    @Order(7)
    void aSponsorWhoIsAlsoAnAdministratorDoesNotApproveTheirOwnMoneyIn() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-self");
        String topUpId = (String) ask(sponsor, 25_000).get("topUpId");
        String requestId = (String) record(finance(), topUpId, 25_000, "ST-SELF-1").get("approvalRequestId");
        // The platform refuses only the recorder's own decision: the sponsor's is refused here, by not crediting.
        run("APPROVAL_DECIDE", com.jabiz.runtime.test.TestTokens.bearer(tokens, sponsor, "approval.decide",
            com.jabiz.quizbuks.QbPermissions.TOPUP_BANK_APPROVE, "task.read"), body("requestId", requestId,
            "decision", "APPROVE"));
        deliverEvents();
        assertThat(topUp(topUpId)).containsEntry("status", "AWAITING").containsEntry("approval_request_id", null);
        assertThat(balance(sponsor)).isEqualByComparingTo("0");
        assertThat(postings(topUpId)).isEmpty();
        assertThat(query("SELECT title_key FROM (SELECT DISTINCT ON (task_id) * FROM sys_task_version ORDER BY "
            + "task_id, version_no DESC) t WHERE source_key = ?", "topup-bank:" + topUpId + ":" + requestId))
            .singleElement().satisfies(task -> assertThat(task).containsEntry("title_key",
                "task.qb.topup-bank.approved-by-sponsor"));
        // Recorded again (later: a recording of its own) and approved by someone else: credited.
        clock.advance(Duration.ofMinutes(5));
        String again = (String) record(finance(), topUpId, 25_000, "ST-SELF-1").get("approvalRequestId");
        run("APPROVAL_DECIDE", content(), body("requestId", again, "decision", "APPROVE"));
        deliverEvents();
        assertThat(balance(sponsor)).isEqualByComparingTo("25000");
    }

    @Test
    @Order(8)
    void withoutTheApprovalRuleNothingIsRecorded() {
        bankSetUp();
        String sponsor = newSponsor("s-bank-no-rule");
        String topUpId = (String) ask(sponsor, 10_000).get("topUpId");
        Object ruleId = query("SELECT DISTINCT ON (rule_id) rule_id FROM sys_approval_rule_version WHERE rule_code = ? "
            + "ORDER BY rule_id, version_no DESC", BankTopUps.REVIEW_RULE).getFirst().get("rule_id");
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity",
            "SysApprovalRule", "targetId", String.valueOf(ruleId), "values", Map.of("enabled", false), "reason",
            "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
        assertThat(codes(refused(BankTopUps.RECORD, finance(), body("topUpId", topUpId, "amount", 10_000,
            "receivedOn", today().toString(), "bankReference", "ST-NO-RULE"), 422)))
            .containsExactly(WalletCodes.TOPUP_BANK_NO_REVIEW);
        assertThat(topUp(topUpId)).containsEntry("status", "AWAITING");
    }
}
