package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorCodes;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operators' monthly settlements (docs/quizbuks/plans/Q48-operators.md, Q48b): a publication keeps its sponsor's
 * operator from when it went on; a month's commission of the operator's publications less the platform's share is
 * its share, posted as owed (5130 / 2140) with a statement issued; finance records the payment and only another
 * administrator's approval posts it (2140 / 1120); a rejection returns it to issued. The month's prospecting cost
 * (Q48d) is deducted, recovered as headquarters' revenue. The monthly report classifies both postings.
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
            + "WHERE t.source_entity = 'QbOperatorSettlement' AND t.source_id = ? ORDER BY t.booking_time, e.line_no",
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
        String operatorId = (String) run(OperatorProcesses.SAVE, hq(), body("name", unique("Kyushu"), "contactName",
            "Kei", "contactEmail", "ops@kyushu.example")).get("operatorId");
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
        // 22 less the platform's 20%: 17.6, rounded down to the yen; less the prospecting cost, 9 is payable.
        assertThat((BigDecimal) settled.get("revenue")).isEqualByComparingTo("22");
        assertThat((BigDecimal) settled.get("platform_share")).isEqualByComparingTo("0.2");
        assertThat((BigDecimal) settled.get("operator_share")).isEqualByComparingTo("17");
        assertThat((BigDecimal) settled.get("prospecting_cost")).isEqualByComparingTo("8");
        assertThat((BigDecimal) settled.get("payable")).isEqualByComparingTo("9");
        assertThat((BigDecimal) settled.get("carried_out")).isEqualByComparingTo("0");
        assertThat(settled).containsEntry("status", OperatorSettlements.ISSUED);
        String settlementId = String.valueOf(settled.get("settlement_id"));
        // The cost the share recovers is headquarters' revenue.
        assertThat(postings(settlementId)).containsExactly("5130 DEBIT 17", "2140 CREDIT 9", "4110 CREDIT 8");
        // Finance issues the statement, kept as issued; asking again gives the same.
        assertThat(settled.get("document_run_id")).isNull();
        Map<String, Object> statement = run(OperatorSettlements.STATEMENT, finance(), body("settlementId",
            settlementId));
        assertThat(statement.get("documentNo")).isNotNull();
        assertThat(run(OperatorSettlements.STATEMENT, finance(), body("settlementId", settlementId)))
            .containsEntry("documentNo", statement.get("documentNo"));
        settled = settlement(operatorId);
        assertThat(settled.get("document_no")).isEqualTo(statement.get("documentNo"));
        assertThat(query("SELECT layout_id FROM sys_document_run WHERE run_id = ?::uuid",
            String.valueOf(settled.get("document_run_id")))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("layout_id", OperatorSettlements.LAYOUT_ID));
        // Settled once: the next run finds it.
        runSettlements();
        assertThat(query("SELECT 1 FROM qb_operator_settlement WHERE operator_id = ?", operatorId)).hasSize(1);

        // The operator sees its own, without who records the payment.
        String member = user(unique("q48b-member"));
        execute("INSERT INTO qb_operator_member (member_id, operator_id, user_id, lead, operator_active, added_at, "
            + "added_by, version) VALUES (gen_random_uuid()::text, ?, ?::uuid, true, true, now(), 'it', 0)",
            operatorId, member);
        assertThat(items("qb.operator.settlements", tokenOf(member, QbRoles.OPERATOR), Map.of())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("month", "2026-11")
                .doesNotContainKeys("paymentRecordedBy", "approvalRequestId"));

        // Finance records the payment; nothing is posted until another administrator approves it.
        Map<String, Object> paying = run(OperatorSettlements.PAY, finance(), body("settlementId", settlementId,
            "paidOn", "2026-12-02", "bankReference", "OP-0001"));
        assertThat(paying).containsEntry("status", OperatorSettlements.PAYING);
        assertThat(postings(settlementId)).hasSize(3);
        assertThat(codes(refused(OperatorSettlements.PAY, finance(), body("settlementId", settlementId, "paidOn",
            "2026-12-02", "bankReference", "OP-0002"), 422))).containsExactly(OperatorCodes.SETTLEMENT_STATE);

        // Rejected: back to issued, the payment's record cleared.
        run("APPROVAL_DECIDE", content(), body("requestId", paying.get("approvalRequestId"), "decision", "REJECT",
            "reason", "Wrong reference"));
        deliverEvents();
        assertThat(settlement(operatorId)).containsEntry("status", OperatorSettlements.ISSUED)
            .containsEntry("bank_reference", null);

        // Recorded again and approved: paid from the bank.
        Map<String, Object> again = run(OperatorSettlements.PAY, finance(), body("settlementId", settlementId,
            "paidOn", "2026-12-02", "bankReference", "OP-0003"));
        run("APPROVAL_DECIDE", content(), body("requestId", again.get("approvalRequestId"), "decision", "APPROVE"));
        deliverEvents();
        assertThat(settlement(operatorId)).containsEntry("status", OperatorSettlements.PAID);
        assertThat(postings(settlementId)).containsExactly("5130 DEBIT 17", "2140 CREDIT 9", "4110 CREDIT 8",
            "2140 DEBIT 9", "1120 CREDIT 9");
        assertBooksBalance();

        // The monthly report (UTC months) classifies both.
        List<Map<String, Object>> monthly = items("qb.admin.monthly", finance(), Map.of("params", Map.of(
            "from", "2026-12-01T00:00:00Z", "to", "2027-01-01T00:00:00Z")));
        assertThat(monthly).filteredOn(r -> "OPERATOR_SHARE".equals(r.get("category"))).singleElement()
            .satisfies(r -> assertThat(((Number) r.get("transactionCount")).intValue()).isGreaterThanOrEqualTo(1));
        assertThat(monthly).filteredOn(r -> "OPERATOR_PAID".equals(r.get("category"))).singleElement()
            .satisfies(r -> assertThat(((Number) r.get("transactionCount")).intValue()).isEqualTo(1));
    }
}
