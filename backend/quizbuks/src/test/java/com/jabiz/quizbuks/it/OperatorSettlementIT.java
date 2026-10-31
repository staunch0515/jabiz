package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorPlans;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.operator.OperatorWithdrawals;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.context.EntryScope;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operators' monthly settlements (docs/quizbuks/plans/Q48-operators.md, Q48b): a publication keeps its sponsor's
 * operator from when it went on; a month's commission of the operator's publications less the platform's share (its
 * plan's, Q48e-2) is its share; what is payable is credited to its account (5130 / 2140) with a statement issued. The
 * operator withdraws from the account: finance records the payment and only another administrator's approval posts it
 * (2140 / 1120); a rejection returns it to asked for. The month's prospecting cost (Q48d) and the plan's fixed fee are
 * deducted, recovered as headquarters' revenue. The monthly report classifies both postings.
 */
class OperatorSettlementIT extends PlayItSupport {

    /** In November (Japan's), a Tuesday. */
    static final Instant NOVEMBER = Instant.parse("2026-11-10T01:00:00Z");
    /** The job's run for November: 16:13 UTC on December 1st is 01:13 on the 2nd in Tokyo. */
    static final Instant RUN_AT = Instant.parse("2026-12-01T16:13:00Z");

    @Autowired
    @Qualifier("qbOperatorSettlementJob")
    JobDefinition<OperatorSettlements.RunInput> settlementJob;

    private String hq() {
        return TestTokens.bearer(tokens, "q48b-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String finance() {
        return tokenOf(user("q48b-finance"), QbRoles.ADMIN_FINANCE);
    }

    private String content() {
        return tokenOf(user("q48b-content"), QbRoles.ADMIN_CONTENT);
    }

    private Map<String, Object> settlement(String operatorId) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = '2026-11'", operatorId)
            .getFirst();
    }

    private static List<String> postings(String settlementId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no",
            settlementId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }

    private JobRunner.JobRun runSettlements() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(settlementJob, RUN_AT);
    }

    @Test
    void anOperatorsMonthIsSettledPostedAndPaidWithFourEyes() {
        clock.set(NOVEMBER);
        // Q59c: an operator of Chinese-speaking sponsors gets its statements in Chinese.
        String operatorId = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("Kyushu"), "contactName",
            "Kei", "contactEmail", "ops@kyushu.example", "language", "zh")).get("operatorId");
        String sponsor = newSponsor(unique("q48b-s"));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason",
            "Signed by the operator"));
        String direct = newSponsor(unique("q48b-direct"));
        credit(sponsor, 10_000);
        credit(direct, 10_000);
        feeRate("0.2");

        // The publication keeps its sponsor's operator from when it went on; the direct sponsor's names none.
        String publication = livePublication(sponsor, "Operator's");
        String directPublication = livePublication(direct, "Direct");
        assertThat(card(publication)).containsEntry("operator_id", operatorId);
        assertThat(card(directPublication).get("operator_id")).isNull();

        // Commission: 20 of the full marks' 100 and 2 of the 10; the direct sponsor's 20 is headquarters'.
        play(newTaker(unique("q48b-t1"), "JP"), publication, true, true);
        play(newTaker(unique("q48b-t2"), "JP"), publication, false, true);
        play(newTaker(unique("q48b-t3"), "JP"), directPublication, true, true);

        // A month is settled once it has ended (in Japan).
        assertThatThrownBy(() -> systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month",
            "2026-11"))).isInstanceOfSatisfying(com.jabiz.runtime.BusinessRuleViolationException.class,
                e -> assertThat(e.violations()).extracting(com.jabiz.entity.Violation::ruleCode)
                    .containsExactly(OperatorCodes.MONTH_OPEN));

        // Q48d: November's prospecting cost 0.05 dollars, at 150 yen: 7.5, rounded up to 8.
        execute("INSERT INTO qb_prospect_usage (usage_id, owner, month, input_tokens, output_tokens, searches, jobs, "
            + "cost_usd, version) VALUES (gen_random_uuid()::text, ?, '2026-11', 0, 0, 5, 1, 0.05, 0)", operatorId);

        clock.set(Instant.parse("2026-12-02T00:00:00Z"));
        JobRunner.JobRun settling = runSettlements();
        assertThat(settling.items().succeeded()).as(settling.toString()).isGreaterThanOrEqualTo(1);
        Map<String, Object> settled = settlement(operatorId);
        // Q48e-2: plan 1 (no plan chosen): the platform keeps 50%. 22 less it: 11; less the prospecting cost, 3 is
        // payable, credited to the operator's account.
        assertThat((BigDecimal) settled.get("revenue")).isEqualByComparingTo("22");
        assertThat(settled).containsEntry("plan", "1");
        assertThat((BigDecimal) settled.get("platform_share")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) settled.get("operator_share")).isEqualByComparingTo("11");
        assertThat((BigDecimal) settled.get("fixed_fee")).isEqualByComparingTo("0");
        assertThat((BigDecimal) settled.get("prospecting_cost")).isEqualByComparingTo("8");
        assertThat((BigDecimal) settled.get("payable")).isEqualByComparingTo("3");
        assertThat((BigDecimal) settled.get("carried_out")).isEqualByComparingTo("0");
        assertThat(settled).containsEntry("status", OperatorSettlements.CREDITED);
        String settlementId = String.valueOf(settled.get("settlement_id"));
        // The cost the share recovers is headquarters' revenue.
        assertThat(postings(settlementId)).containsExactly("5130 DEBIT 11", "2140 CREDIT 3", "4110 CREDIT 8");
        // Finance issues the statement, kept as issued; asking again gives the same.
        assertThat(settled.get("document_run_id")).isNull();
        Map<String, Object> statement = run(OperatorSettlements.STATEMENT, finance(), body("settlementId",
            settlementId));
        assertThat(statement.get("documentNo")).isNotNull();
        assertThat(run(OperatorSettlements.STATEMENT, finance(), body("settlementId", settlementId)))
            .containsEntry("documentNo", statement.get("documentNo"));
        settled = settlement(operatorId);
        assertThat(settled.get("document_no")).isEqualTo(statement.get("documentNo"));
        assertThat(query("SELECT layout_id, language FROM sys_document_run WHERE run_id = ?::uuid",
            String.valueOf(settled.get("document_run_id")))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("layout_id", OperatorSettlements.LAYOUT_ID)
                .containsEntry("language", "zh"));
        // Settled once: the next run finds it.
        runSettlements();
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).hasSize(1);
        // A settlement credited to the account is not paid on its own.
        assertThat(codes(refused(OperatorSettlements.PAY, finance(), body("settlementId", settlementId, "paidOn",
            "2026-12-02", "bankReference", "OP-0001"), 422))).containsExactly(OperatorCodes.SETTLEMENT_STATE);

        // The operator sees its own, without who records the payment, and its balance.
        String member = user(unique("q48b-member"));
        execute("INSERT INTO qb_operator_member (member_id, operator_id, user_id, lead, operator_active, added_at, "
            + "added_by, version) VALUES (gen_random_uuid()::text, ?, ?::uuid, true, true, now(), 'it', 0)",
            operatorId, member);
        assertThat(items("qb.operator.settlements", tokenOf(member, QbRoles.OPERATOR), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("month", "2026-11")
                .doesNotContainKeys("paymentRecordedBy", "approvalRequestId"));
        String session = operatorSession(member, operatorId);
        assertThat(items("qb.operator.account", session, Map.of())).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("3"));

        // The operator withdraws (the least lowered to 1 for this test): its bank account first, then, once its
        // cooling-off is over, the balance.
        param(QbParams.OPERATOR_WITHDRAWAL_MIN, "1");
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, session, body("amount", 3), 422)))
            .containsExactly(OperatorCodes.WITHDRAWAL_NO_ACCOUNT);
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, session, bankAccount());
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, session, body("amount", 3), 422)))
            .containsExactly(OperatorCodes.WITHDRAWAL_COOLDOWN);
        clock.set(Instant.parse("2026-12-03T01:00:00Z"));
        session = operatorSession(member, operatorId);
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, session, body("amount", 4), 422)))
            .containsExactly(OperatorCodes.WITHDRAWAL_AMOUNT);
        Map<String, Object> asked = run(OperatorWithdrawals.REQUEST, session, body("amount", 3));
        String withdrawalId = String.valueOf(asked.get("withdrawalId"));
        assertThat(asked).containsEntry("status", OperatorWithdrawals.REQUESTED);
        assertThat(codes(refused(OperatorWithdrawals.REQUEST, session, body("amount", 1), 422)))
            .contains(OperatorCodes.WITHDRAWAL_OPEN);
        assertThat(items("qb.operator.account", session, Map.of())).singleElement()
            .satisfies(row -> assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("0"));

        // Finance records the payment; nothing is posted until another administrator approves it.
        Map<String, Object> paying = run(OperatorWithdrawals.PAY, finance(), body("withdrawalId", withdrawalId,
            "paidOn", "2026-12-03", "bankReference", "OP-0001"));
        assertThat(paying).containsEntry("status", OperatorWithdrawals.PAYING);
        assertThat(withdrawalPostings(withdrawalId)).isEmpty();
        assertThat(codes(refused(OperatorWithdrawals.CANCEL, session, body("withdrawalId", withdrawalId), 422)))
            .containsExactly(OperatorCodes.WITHDRAWAL_STATE);

        // Rejected: back to asked for, the payment's record cleared.
        run("APPROVAL_DECIDE", content(), body("requestId", paying.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Wrong reference"));
        deliverEvents();
        assertThat(withdrawal(withdrawalId)).containsEntry("status", OperatorWithdrawals.REQUESTED)
            .containsEntry("bank_reference", null);

        // Recorded again and approved: paid from the bank, out of the operator's account.
        Map<String, Object> again = run(OperatorWithdrawals.PAY, finance(), body("withdrawalId", withdrawalId,
            "paidOn", "2026-12-03", "bankReference", "OP-0002"));
        run("APPROVAL_DECIDE", content(), body("requestId", again.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(withdrawal(withdrawalId)).containsEntry("status", OperatorWithdrawals.PAID);
        assertThat(withdrawalPostings(withdrawalId)).containsExactly("2140 DEBIT 3", "1120 CREDIT 3");
        assertBooksBalance();

        // The monthly report (UTC months) classifies both.
        List<Map<String, Object>> monthly = items("qb.admin.monthly", finance(), Map.of("params", Map.of(
            "from", "2026-12-01T00:00:00Z", "to", "2027-01-01T00:00:00Z")));
        assertThat(monthly).filteredOn(r -> "OPERATOR_SHARE".equals(r.get("category"))).singleElement()
            .satisfies(r -> assertThat(((Number) r.get("transactionCount")).intValue()).isGreaterThanOrEqualTo(1));
        assertThat(monthly).filteredOn(r -> "OPERATOR_PAID".equals(r.get("category"))).singleElement()
            .satisfies(r -> assertThat(((Number) r.get("transactionCount")).intValue()).isEqualTo(1));
    }

    /**
     * Q48e-2: headquarters proposes another plan from a later month and another administrator approves it; a month
     * is settled on the plan in effect then, its fixed fee deducted and what the share does not cover carried. The
     * operator's money is its own: another operator, a member who is not the lead and headquarters' sessions do not
     * withdraw it.
     */
    @Test
    void aPlanChangesFromALaterMonthWithFourEyesAndItsFixedFeeIsDeducted() {
        clock.set(Instant.parse("2027-01-10T01:00:00Z"));
        String operatorId = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("Tohoku"), "contactName",
            "Toru", "contactEmail", "ops@tohoku.example")).get("operatorId");
        // Not this month, which is settled on the plan it began with.
        assertThat(codes(refused(OperatorPlans.SET, hq(), body("operatorId", operatorId, "plan", "3",
            "effectiveMonth", "2027-01", "reason", "Signed"), 422))).containsExactly(OperatorCodes.PLAN_MONTH);
        Map<String, Object> proposed = run(OperatorPlans.SET, hq(), body("operatorId", operatorId, "plan", "3",
            "effectiveMonth", "2027-02", "reason", "Signed for plan 3"));
        assertThat(proposed).containsEntry("status", OperatorPlans.PENDING);
        assertThat(codes(refused(OperatorPlans.SET, hq(), body("operatorId", operatorId, "plan", "2",
            "effectiveMonth", "2027-03", "reason", "Again"), 422))).containsExactly(OperatorCodes.PLAN_PENDING);
        run("APPROVAL_DECIDE", content(), body("requestId", proposed.get("approvalRequestId"), "decision",
            "APPROVE"));
        deliverEvents();
        assertThat(query("SELECT status FROM qb_operator_plan WHERE operator_id = ?", operatorId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("status", OperatorPlans.APPROVED));

        // January: still plan 1 (no fixed fee). February: plan 3, a fixed fee of 40,000 and nothing earned: carried.
        clock.set(Instant.parse("2027-02-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-01"));
        assertThat(query("SELECT plan, fixed_fee, carried_out FROM qb_operator_settlement WHERE operator_id = ? "
            + "AND month = '2027-01'", operatorId)).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("plan", "1");
                assertThat((BigDecimal) row.get("fixed_fee")).isEqualByComparingTo("0");
            });
        clock.set(Instant.parse("2027-03-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2027-02"));
        assertThat(query("SELECT plan, platform_share, fixed_fee, payable, carried_out FROM qb_operator_settlement "
            + "WHERE operator_id = ? AND month = '2027-02'", operatorId)).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("plan", "3");
                assertThat((BigDecimal) row.get("platform_share")).isEqualByComparingTo("0.3");
                assertThat((BigDecimal) row.get("fixed_fee")).isEqualByComparingTo("40000");
                assertThat((BigDecimal) row.get("payable")).isEqualByComparingTo("0");
                assertThat((BigDecimal) row.get("carried_out")).isEqualByComparingTo("40000");
            });

        // Withdrawals: the lead of this operator's session only.
        String lead = member(operatorId, unique("q48e2-lead"), true);
        String staff = member(operatorId, unique("q48e2-staff"), false);
        assertThat(codes(refused(OperatorWithdrawals.BANK_ACCOUNT_SAVE, operatorSession(staff, operatorId),
            bankAccount(), 422))).containsExactly(OperatorCodes.NOT_LEAD);
        assertThat(codes(refused(OperatorWithdrawals.BANK_ACCOUNT_SAVE, hq(), bankAccount(), 422)))
            .containsExactly(OperatorCodes.NOT_MEMBER);
        run(OperatorWithdrawals.BANK_ACCOUNT_SAVE, operatorSession(lead, operatorId), bankAccount());
        String other = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("Other"), "contactName", "O",
            "contactEmail", "ops@other.example")).get("operatorId");
        String otherLead = member(other, unique("q48e2-other"), true);
        execute("INSERT INTO qb_operator_withdrawal (withdrawal_id, operator_id, amount, status, requested_by, "
            + "requested_at, revision, version) VALUES (gen_random_uuid()::text, ?, 5, 'REQUESTED', 'it', now(), 1, 0)",
            operatorId);
        String withdrawalId = String.valueOf(query("SELECT withdrawal_id FROM qb_operator_withdrawal WHERE "
            + "operator_id = ?", operatorId).getFirst().get("withdrawal_id"));
        refused(OperatorWithdrawals.CANCEL, operatorSession(otherLead, other), body("withdrawalId", withdrawalId),
            404);
        run(OperatorWithdrawals.CANCEL, operatorSession(lead, operatorId), body("withdrawalId", withdrawalId));
        assertThat(withdrawal(withdrawalId)).containsEntry("status", OperatorWithdrawals.CANCELLED);
    }

    /** Sets a controlled parameter (four eyes). */
    private void param(String key, String value) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", key, "value", value), "reason", "Test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    /** A session of the operators' entry, as its sign-in issues it: the role's permissions, in its operator. */
    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    /** A verified user made a member of the operator (its lead or not); returns the user id. */
    private String member(String operatorId, String name, boolean lead) {
        String userId = user(name);
        execute("INSERT INTO qb_operator_member (member_id, operator_id, user_id, lead, operator_active, added_at, "
            + "added_by, version) VALUES (gen_random_uuid()::text, ?, ?::uuid, ?, true, now(), 'it', 0)",
            operatorId, userId, lead);
        return userId;
    }

    private static Map<String, Object> bankAccount() {
        return body("bankCode", "0005", "bankName", "MUFG", "branchCode", "001", "branchName", "Head office",
            "accountType", "ORDINARY", "accountNumber", "1234567", "accountHolder", "カ）トウホク");
    }

    private static Map<String, Object> withdrawal(String withdrawalId) {
        return query("SELECT * FROM qb_operator_withdrawal WHERE withdrawal_id = ?", withdrawalId).getFirst();
    }

    private static List<String> withdrawalPostings(String withdrawalId) {
        return query("SELECT a.account_code, e.direction, e.amount FROM ledger_transaction_version t "
            + "JOIN ledger_entry_version e ON e.transaction_id = t.transaction_id "
            + "JOIN ledger_account_version a ON a.account_id = e.account_id "
            + "WHERE t.source_entity = 'QbOperatorWithdrawal' AND t.source_id = ? ORDER BY t.process_seq_id, e.line_no",
            withdrawalId).stream().map(p -> p.get("account_code") + " " + p.get("direction") + " "
            + ((BigDecimal) p.get("amount")).stripTrailingZeros().toPlainString()).toList();
    }
}
