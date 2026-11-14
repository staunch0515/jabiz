package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M12 review M5: the monthly report classifies the settlement's two postings, however the 4110 line falls: the share
 * (5130 / 2140, 4110 either way) is OPERATOR_SHARE, the take-back of granted credit (2140 / 5140, 4110 when given back)
 * is FEE_CREDIT_RECOVERED; none of them is left in "other".
 */
class OperatorSettlementMonthlyIT extends CreditNoteItSupport {

    private List<Map<String, Object>> monthly(String from, String to) {
        return items("qb.admin.monthly", TestTokens.bearer(tokens, "fm12-monthly", QbPermissions.ADMIN_FINANCE_READ),
            Map.of("params", Map.of("from", from, "to", to, "knownAt", "2030-01-01T00:00:00Z"), "limit", 100));
    }

    private static Map<String, Object> category(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(r -> name.equals(r.get("category"))).findFirst().orElseThrow();
    }

    private static BigDecimal m(Map<String, Object> row, String column) {
        return new BigDecimal(String.valueOf(row.get(column)));
    }

    private void post(String sourceId, String when, String reference, Object... lines) {
        List<Map<String, Object>> entries = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 3) {
            entries.add(body("accountCode", lines[i], "direction", lines[i + 1], "amount", lines[i + 2]));
        }
        run("LEDGER_POST", TestTokens.bearer(tokens, "ledger-test", "ledger.post"), body("bookingTime", when,
            "description", "Settlement (test)", "reference", reference, "sourceEntity", "QbOperatorSettlement",
            "sourceId", sourceId, "entries", entries));
    }

    private static long settlementTransactions(String from, String to) {
        return ((Number) query("SELECT count(*) AS n FROM ledger_transaction_version WHERE source_entity = "
            + "'QbOperatorSettlement' AND booking_time >= ?::timestamptz AND booking_time < ?::timestamptz",
            from, to).getFirst().get("n")).longValue();
    }

    @Test
    void theSharePostingAndTheTakeBackAreClassifiedIncludingARefundAndA4110Debit() {
        clock.set(Instant.parse("2028-03-01T00:00:00Z"));
        param(QbParams.FEE_TAX_RATE, "0.1");
        param(QbParams.operatorGiftTreatment("MANUAL"), "OPERATOR");
        clock.set(Instant.parse("2028-03-10T01:00:00Z"));
        String hq = TestTokens.bearer(tokens, "fm12-monthly-hq", QbPermissions.OPERATORS_WRITE,
            QbPermissions.OPERATORS_READ);
        String operatorId = newOperator(hq, unique("Monthly"));
        String sponsor = newSponsor(unique("fm12m-monthly"));
        run(OperatorProcesses.ASSIGN, hq, body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12"));
        grant(sponsor, 1_100, Instant.parse("2030-01-01T00:00:00Z"));
        Object chargeId = manualFee(sponsor, 1_000);
        clock.set(Instant.parse("2028-04-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2028-03"));
        // By hand, the shapes the process rarely makes: the share's 4110 a debit, a share without 5130, and the
        // take-back with a 4110 line.
        String settlementId = String.valueOf(query("SELECT settlement_id FROM qb_operator_settlement WHERE "
            + "operator_id = ? AND month = '2028-03'", operatorId).getFirst().get("settlement_id"));
        post(settlementId, "2028-04-03T00:00:00Z", "op_2028-03_manual01", "5130", "DEBIT", 300, "4110", "DEBIT", 100, "2140",
            "CREDIT", 400);
        post(settlementId, "2028-04-03T00:00:01Z", "op_2028-03_manual02", "4110", "DEBIT", 500, "2140", "CREDIT", 500);
        post(settlementId, "2028-04-03T00:00:02Z", "opg_2028-03_manual0", "5140", "DEBIT", 200, "2140", "CREDIT", 100, "4110",
            "CREDIT", 100);

        // April's report: the settlement's postings are in their two classes, none in "other".
        List<Map<String, Object>> april = monthly("2028-04-01T00:00:00Z", "2028-05-01T00:00:00Z");
        Map<String, Object> share = category(april, "OPERATOR_SHARE");
        Map<String, Object> taken = category(april, "FEE_CREDIT_RECOVERED");
        assertThat(((Number) share.get("transactionCount")).intValue()).isEqualTo(3);
        assertThat(((Number) taken.get("transactionCount")).intValue()).isEqualTo(2);
        assertThat(m(share, "operatorShares")).isEqualByComparingTo("800");
        // The process's 2140 credit of 500 (its share, nothing recovered), the two by hand: 400 and 500.
        assertThat(m(share, "operatorPayable")).isEqualByComparingTo("-1400");
        // The process's credit of 500 and the debit of 200 by hand.
        assertThat(m(taken, "feeCreditPromotion")).isEqualByComparingTo("-300");
        assertThat(((Number) share.get("transactionCount")).longValue()
            + ((Number) taken.get("transactionCount")).longValue())
            .isEqualTo(settlementTransactions("2028-04-01T00:00:00Z", "2028-05-01T00:00:00Z"));

        // May: the credit comes back with a note; the refund is a negative take-back (5140 debit, 4110 credit).
        clock.set(Instant.parse("2028-04-10T01:00:00Z"));
        creditNote(chargeId, 1_000);
        clock.set(Instant.parse("2028-05-02T00:00:00Z"));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", "2028-04"));
        List<Map<String, Object>> may = monthly("2028-05-01T00:00:00Z", "2028-06-01T00:00:00Z");
        Map<String, Object> refund = category(may, "FEE_CREDIT_RECOVERED");
        assertThat(((Number) refund.get("transactionCount")).intValue()).isEqualTo(1);
        assertThat(m(refund, "feeCreditPromotion")).isEqualByComparingTo("500");
        assertThat(m(refund, "revenue")).isEqualByComparingTo("-500");
        assertThat(((Number) category(may, "OPERATOR_SHARE").get("transactionCount")).intValue()).isZero();
        assertThat(((Number) refund.get("transactionCount")).longValue())
            .isEqualTo(settlementTransactions("2028-05-01T00:00:00Z", "2028-06-01T00:00:00Z"));
        // F-M14b (the fourth case of F-M14 §3.2 point 4): the guard over the whole ledger, the take-back and its
        // negative after a credit note among it.
        MonthlyKinds.assertGuard(monthly("2000-01-01T00:00:00Z", "2100-01-01T00:00:00Z"),
            List.of("OPERATOR_SHARE", "FEE_CREDIT_RECOVERED", "FEE_CREDIT_NOTE"), query(MonthlyKinds.SOURCES_SQL),
            ((Number) query(MonthlyKinds.NO_SOURCE_SQL).getFirst().get("n")).longValue(),
            query(MonthlyKinds.TOTALS_SQL).getFirst());
        assertBooksBalance();
    }
}
