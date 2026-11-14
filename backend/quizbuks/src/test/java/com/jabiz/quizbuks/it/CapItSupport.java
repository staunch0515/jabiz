package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.OperatorSettlements;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What the ITs of the cap on a settlement's lines (F-M12 b) share: an operator with a sponsor and cash, fees, runs, reads. */
abstract class CapItSupport extends CreditNoteItSupport {

    String hq() {
        return TestTokens.bearer(tokens, "fm12b-cap-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    /** A new operator with a sponsor and cash, in February of {@code year}. */
    String[] start(int year) {
        clock.set(Instant.parse(year + "-02-01T01:00:00Z"));
        param(QbParams.FEE_TAX_RATE, "0.1");
        return another(year);
    }

    /** As {@link #start}, in the same schema and year (the tax rate is set already). */
    String[] another(int year) {
        clock.set(Instant.parse(year + "-02-01T02:00:00Z"));
        String operatorId = newOperator(hq(), unique("Cap " + year));
        String sponsor = newSponsor(unique("fm12b-cap-" + year));
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M12 b"));
        cash(sponsor, 100_000);
        return new String[] {operatorId, sponsor};
    }

    /** {@code count} fees of {@code amount}, a minute apart from {@code first}; their ids, oldest first. */
    List<String> fees(String sponsor, int count, long amount, String first) {
        List<String> ids = new ArrayList<>();
        Instant at = Instant.parse(first);
        for (int i = 0; i < count; i++) {
            clock.set(at.plus(Duration.ofMinutes(i)));
            ids.add(String.valueOf(manualFee(sponsor, amount)));
        }
        return ids;
    }

    Map<String, Object> settle(String operatorId, String month, String at) {
        clock.set(Instant.parse(at));
        systemRun(OperatorSettlements.RUN, body("operatorId", operatorId, "month", month));
        return settlement(operatorId, month);
    }

    static Map<String, Object> settlement(String operatorId, String month) {
        return query("SELECT * FROM qb_operator_settlement WHERE operator_id = ? AND month = ?", operatorId, month)
            .getFirst();
    }

    static List<Object> lineCharges(Object settlementId, String kind) {
        return query("SELECT charge_id FROM qb_operator_settlement_line_version WHERE settlement_id = ? AND line_kind "
            + "= ? ORDER BY occurred_at, charge_id", settlementId, kind).stream().map(r -> r.get("charge_id"))
            .toList();
    }

    static BigDecimal n(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    static List<Map<String, Object>> capTasks(String source) {
        return query("SELECT DISTINCT task_id FROM sys_task_version WHERE source_key = ?", source);
    }
}
