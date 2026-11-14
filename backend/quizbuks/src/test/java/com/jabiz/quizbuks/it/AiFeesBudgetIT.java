package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9 b2-1 (the review's M1): how long the AI fees report takes over a history like a busy platform's in two years:
 * 30 months of 10,000 jobs each (300,000 in all) of 5,000 sponsors under 20 operators, for one month and for the 12
 * months a span may have, within the 5 seconds a query may take. The jobs are bulk-loaded straight in the table (the
 * point is the report's read), with no statistics (autovacuum off, no ANALYZE), as right after a bulk load. Run it alone
 * to see the numbers it prints.
 */
class AiFeesBudgetIT extends AiItSupport {

    /** Measured here at about a tenth of these; the room is for CI's slower machines (twice the time) and no statistics. */
    static final long ONE_MONTH_MILLIS = 1_000;
    static final long TWELVE_MONTHS_MILLIS = 2_000;

    private String financeRead() {
        return TestTokens.bearer(tokens, "fm9b2-budget-finance", QbPermissions.ADMIN_FINANCE_READ);
    }

    private long measure(String name, Map<String, Object> params, long limitMillis) {
        // The first call plans and warms the connection; the number that counts is the second, as a page refresh.
        items("qb.admin.ai-fees", financeRead(), Map.of("params", params));
        long start = System.nanoTime();
        List<Map<String, Object>> rows = items("qb.admin.ai-fees", financeRead(), Map.of("params", params));
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[ai-fees-budget] " + name + ": " + millis + " ms, first page " + rows.size() + " rows");
        assertThat(rows).as(name).isNotEmpty();
        assertThat(millis).as(name + " (ms)").isLessThan(limitMillis);
        return millis;
    }

    @Test
    void aMonthAndAYearOfTheReportOverThreeHundredThousandJobsAreWithinTheQueryTimeout() {
        execute("ALTER TABLE qb_ai_job SET (autovacuum_enabled = false)");
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, zone, requested_at, finished_at, version, fee_mode, fee_operator_id, "
            + "fee_markup, fee_usd_jpy, reserved_yen, cost_usd, billable_usd, unrecorded_usd, fee_yen, absorbed_yen, "
            + "fee_period, fee_charge_id) SELECT gen_random_uuid()::text, gen_random_uuid()::text, "
            + "'budget-' || (i % 5000), gen_random_uuid(), 'QUESTIONS', '{}', 'claude-opus-5-5', 'ACCEPTED', 10, 10, "
            + "'Asia/Tokyo', now(), now(), 1, 'CREDIT', 'budget-op-' || (i % 20), 1.5, 150, 0, 0.1, 0.1, 0.01, "
            + "100 + i % 7, i % 3, to_char(date '2029-01-01' + (i % 30) * interval '1 month', 'YYYY-MM'), "
            + "CASE WHEN i % 2 = 0 THEN 'c' || i END FROM generate_series(1, 300000) AS i");
        long one = measure("one month", Map.of("month", "2030-06"), ONE_MONTH_MILLIS);
        long twelve = measure("12 months", Map.of("month", "2030-01", "toMonth", "2030-12"), TWELVE_MONTHS_MILLIS);
        System.out.println("[ai-fees-budget] 300,000 jobs, 30 months, no statistics: one month " + one
            + " ms, 12 months " + twelve + " ms");
        // The span's 13th month is refused before anything is read.
        assertThat(items("qb.admin.ai-fees", financeRead(), Map.of("params", Map.of("month", "2029-01", "toMonth",
            "2030-01")))).isEmpty();
    }
}
