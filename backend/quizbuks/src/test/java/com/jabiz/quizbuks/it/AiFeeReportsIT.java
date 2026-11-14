package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiAbsorbed;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M9 b2-1: what the AI fee shows the operators and the administrators, and the alert on what the platform took on.
 * The operator's template of a sponsor's credit and month fee (only the sponsors of the caller's operator, also after a
 * reassignment), the administrators' AI jobs with their fee columns, the fees by month, sponsor and operator (sums,
 * filters, export, speed), and the daily check's task for finance (once a month, last month too, nothing at an alert of
 * 0). The jobs are written by hand with the values the fee processes leave on them: those are tested in the AI credit's
 * own tests.
 */
class AiFeeReportsIT extends AiCreditItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired
    JobDefinition<AiAbsorbed.CheckInput> qbAiAbsorbedCheckJob;

    private String hq() {
        return TestTokens.bearer(tokens, "fm9b2-hq", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String finance() {
        return TestTokens.bearer(tokens, "fm9b2-finance", QbPermissions.RECONCILE, QbPermissions.ADMIN_AI_READ,
            "task.read", "task.resolve");
    }

    private void assign(String sponsor, String operatorId) {
        run(OperatorProcesses.ASSIGN, hq(), body("userId", sponsor, "operatorId", operatorId, "reason", "F-M9 b2-1"));
    }

    /** A job whose result was recorded in {@code period}: the values the fee processes leave on it. */
    private String job(String sponsor, String operatorId, String period, String costUsd, long fee, long absorbed,
        String unrecordedUsd, boolean charged) {
        String aiJobId = UUID.randomUUID().toString();
        Timestamp at = Timestamp.from(clock.instant());
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, zone, requested_at, finished_at, version, fee_mode, fee_operator_id, "
            + "fee_markup, fee_usd_jpy, reserved_yen, cost_usd, billable_usd, unrecorded_usd, fee_yen, absorbed_yen, "
            + "fee_period, fee_charge_id) VALUES (?, ?, ?, ?::uuid, 'QUESTIONS', '{}', 'claude-opus-5-5', 'ACCEPTED', 10, "
            + "10, 'Asia/Tokyo', ?, ?, 1, 'CREDIT', ?, 1.5, 150, 0, ?, ?, ?, ?, ?, ?, ?)",
            aiJobId, UUID.randomUUID().toString(), sponsor, UUID.randomUUID().toString(), at, at, operatorId,
            new BigDecimal(costUsd), new BigDecimal(costUsd), new BigDecimal(unrecordedUsd), fee, absorbed, period,
            charged ? UUID.randomUUID().toString() : null);
        return aiJobId;
    }

    private String sponsorWithCredit(String prefix) {
        feeOn();
        String sponsor = newSponsor(unique(prefix));
        cash(sponsor, 5_000);
        buy(sponsor, 5_000);
        return sponsor;
    }

    private static String month(Instant at) {
        return FeeRules.period(at, ZONE);
    }

    private Map<String, Object> request(Map<String, Object> params) {
        return Map.of("params", params);
    }

    // ---- the operator's template -------------------------------------------------------------------------------

    @Test
    void anOperatorSeesTheAiCreditAndTheMonthsFeeOfItsOwnSponsorsOnly() {
        NewOperator north = newOperatorWithLead(hq(), unique("North"), null);
        NewOperator west = newOperatorWithLead(hq(), unique("West"), null);
        String sponsor = sponsorWithCredit("fm9b2-op-");
        assign(sponsor, north.operatorId());
        String m = "2030-05";
        job(sponsor, north.operatorId(), m, "0.10", 300, 0, "0", false);
        job(sponsor, north.operatorId(), m, "0.20", 450, 20, "0", true);
        // A job with no fee in the month, another month's and a free one do not count.
        job(sponsor, north.operatorId(), m, "0.01", 0, 5, "0", false);
        job(sponsor, north.operatorId(), "2030-04", "0.50", 999, 0, "0", true);
        BigDecimal credit = money(aiCredit(sponsor).get("remaining"));
        assertThat(credit).isPositive();

        String northSession = memberSession(north.leadId(), north.operatorId());
        String westSession = memberSession(west.leadId(), west.operatorId());
        Map<String, Object> params = Map.of("sponsorId", sponsor, "month", m);

        assertThat(items("qb.operator.sponsor-ai", northSession, request(params))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("userId", sponsor);
            assertThat(money(row.get("aiCredit"))).isEqualByComparingTo(credit);
            assertThat(money(row.get("monthFee"))).isEqualByComparingTo("750");
            assertThat(money(row.get("monthJobs"))).isEqualByComparingTo("2");
        });
        // Another month: the credit is the same, the fee that month's.
        assertThat(items("qb.operator.sponsor-ai", northSession, request(Map.of("sponsorId", sponsor, "month",
            "2030-04")))).singleElement().satisfies(row -> {
                assertThat(money(row.get("monthFee"))).isEqualByComparingTo("999");
                assertThat(money(row.get("aiCredit"))).isEqualByComparingTo(credit);
            });
        // Nobody else's operator sees the sponsor; a month with no job shows nothing charged.
        assertThat(items("qb.operator.sponsor-ai", westSession, request(params))).isEmpty();
        assertThat(items("qb.operator.sponsor-ai", northSession, request(Map.of("sponsorId", sponsor, "month",
            "2030-06")))).singleElement().satisfies(row -> {
                assertThat(money(row.get("monthFee"))).isEqualByComparingTo("0");
                assertThat(money(row.get("monthJobs"))).isEqualByComparingTo("0");
            });

        // Reassigned: the first operator no longer sees it, the second does.
        assign(sponsor, west.operatorId());
        assertThat(items("qb.operator.sponsor-ai", northSession, request(params))).isEmpty();
        assertThat(items("qb.operator.sponsor-ai", westSession, request(params))).singleElement()
            .satisfies(row -> assertThat(money(row.get("monthFee"))).isEqualByComparingTo("750"));
    }

    @Test
    void aSponsorWithoutAiCreditOrJobsShowsZeros() {
        NewOperator north = newOperatorWithLead(hq(), unique("North"), null);
        String sponsor = newSponsor(unique("fm9b2-none-"));
        assign(sponsor, north.operatorId());
        assertThat(items("qb.operator.sponsor-ai", memberSession(north.leadId(), north.operatorId()),
            request(Map.of("sponsorId", sponsor, "month", "2030-05")))).singleElement().satisfies(row -> {
                assertThat(money(row.get("aiCredit"))).isEqualByComparingTo("0");
                assertThat(money(row.get("monthFee"))).isEqualByComparingTo("0");
                assertThat(money(row.get("monthJobs"))).isEqualByComparingTo("0");
            });
    }

    // ---- the administrators' templates -------------------------------------------------------------------------

    @Test
    void theAiJobsShowTheirFeeColumnsAndTheOperatorsName() {
        NewOperator north = newOperatorWithLead(hq(), unique("North"), null);
        String sponsor = newSponsor(unique("fm9b2-jobs-"));
        String aiJobId = job(sponsor, north.operatorId(), "2030-05", "0.123456", 300, 40, "0.25", false);
        String free = UUID.randomUUID().toString();
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, zone, requested_at, version) VALUES (?, ?, ?, ?::uuid, 'QUESTIONS', '{}', "
            + "'claude-opus-5-5', 'QUEUED', 1, 1, 'Asia/Tokyo', ?, 1)", free, UUID.randomUUID().toString(), sponsor,
            UUID.randomUUID().toString(), Timestamp.from(clock.instant()));

        List<Map<String, Object>> rows = items("qb.admin.ai-jobs", aiAdmin(), Map.of("filters", List.of(Map.of("field",
            "sponsorId", "op", "eq", "value", sponsor)), "limit", 100));
        Map<String, Object> charged = rows.stream().filter(r -> aiJobId.equals(r.get("aiJobId"))).findFirst()
            .orElseThrow();
        assertThat(charged).containsEntry("feeMode", "CREDIT").containsEntry("feeOperatorId", north.operatorId());
        assertThat(String.valueOf(charged.get("feeOperatorName"))).startsWith("North");
        assertThat(money(charged.get("costUsd"))).isEqualByComparingTo("0.123456");
        assertThat(money(charged.get("feeYen"))).isEqualByComparingTo("300");
        assertThat(money(charged.get("absorbedYen"))).isEqualByComparingTo("40");
        assertThat(money(charged.get("unrecordedUsd"))).isEqualByComparingTo("0.25");
        // A job without a fee is still listed, its fee columns empty or "not charged".
        Map<String, Object> none = rows.stream().filter(r -> free.equals(r.get("aiJobId"))).findFirst().orElseThrow();
        assertThat(none.get("feeOperatorName")).isNull();
        assertThat(none.get("feeYen")).isNull();
    }

    @Test
    void theFeesAreSummedByMonthSponsorAndOperatorWithTheAbsorbedTotalAndTheUnbilled() {
        NewOperator north = newOperatorWithLead(hq(), unique("North"), null);
        NewOperator west = newOperatorWithLead(hq(), unique("West"), null);
        String a = sponsorWithCredit("fm9b2-fees-a-");
        String b = newSponsor(unique("fm9b2-fees-b-"));
        String m = "2030-07";
        // Sponsor a under North: 3 jobs. absorbed total = (40 + 0) + (0 + ceil(0.25 * 150) = 38) + (0 + 0)
        job(a, north.operatorId(), m, "0.10", 300, 40, "0", false);
        job(a, north.operatorId(), m, "0.20", 450, 0, "0.25", true);
        job(a, north.operatorId(), m, "0.05", 100, 0, "0", false);
        // The same sponsor in the same month under another operator (reassigned in between): a row of its own.
        job(a, west.operatorId(), m, "0.30", 700, 5, "0.001", false);
        // Another sponsor, no operator (the platform's own), and another month.
        job(b, null, m, "1", 1_000, 0, "0", true);
        job(a, north.operatorId(), "2030-08", "9", 9_999, 0, "0", false);

        String admin = aiAdmin();
        List<Map<String, Object>> rows = items("qb.admin.ai-fees", admin, request(Map.of("month", m)));
        assertThat(rows).hasSize(3);
        Map<String, Object> northRow = rows.stream().filter(r -> a.equals(r.get("sponsorId"))
            && north.operatorId().equals(r.get("operatorId"))).findFirst().orElseThrow();
        assertThat(northRow).containsEntry("month", m);
        assertThat(String.valueOf(northRow.get("operatorName"))).startsWith("North");
        assertThat(northRow.get("company")).isNotNull();
        assertThat(money(northRow.get("jobs"))).isEqualByComparingTo("3");
        assertThat(money(northRow.get("costUsd"))).isEqualByComparingTo("0.35");
        assertThat(money(northRow.get("feeYen"))).isEqualByComparingTo("850");
        assertThat(money(northRow.get("absorbedYen"))).isEqualByComparingTo("40");
        assertThat(money(northRow.get("unrecordedUsd"))).isEqualByComparingTo("0.25");
        assertThat(money(northRow.get("absorbedTotalYen"))).isEqualByComparingTo("78");
        // The two jobs with no charge yet: 300 + 100.
        assertThat(money(northRow.get("unbilledYen"))).isEqualByComparingTo("400");
        assertThat(money(northRow.get("aiCredit"))).isEqualByComparingTo(money(aiCredit(a).get("remaining")));

        Map<String, Object> westRow = rows.stream().filter(r -> a.equals(r.get("sponsorId"))
            && west.operatorId().equals(r.get("operatorId"))).findFirst().orElseThrow();
        // 5 + ceil(0.001 * 150) = 5 + 1.
        assertThat(money(westRow.get("absorbedTotalYen"))).isEqualByComparingTo("6");
        Map<String, Object> platformRow = rows.stream().filter(r -> b.equals(r.get("sponsorId"))).findFirst()
            .orElseThrow();
        assertThat(platformRow.get("operatorId")).isNull();
        assertThat(platformRow.get("operatorName")).isNull();
        assertThat(money(platformRow.get("unbilledYen"))).isEqualByComparingTo("0");
        assertThat(money(platformRow.get("aiCredit"))).isEqualByComparingTo("0");

        // The filters: sponsor, operator and the months together.
        assertThat(items("qb.admin.ai-fees", admin, request(Map.of("sponsorId", a)))).hasSize(3)
            .extracting(r -> r.get("month")).contains(m, "2030-08");
        assertThat(items("qb.admin.ai-fees", admin, request(Map.of("operatorId", west.operatorId())))).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("month", m));
        assertThat(items("qb.admin.ai-fees", admin, request(Map.of("month", "2030-08", "sponsorId", b)))).isEmpty();
        // Newest month first.
        List<Map<String, Object>> all = items("qb.admin.ai-fees", admin, request(Map.of("sponsorId", a)));
        assertThat(all.getFirst()).containsEntry("month", "2030-08");
        // Only the AI page's readers.
        client.post().uri("/api/queries/qb.admin.ai-fees").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, memberSession(north.leadId(), north.operatorId()))
            .bodyValue(request(Map.of())).exchange().expectStatus().isForbidden();
    }

    @Test
    void theFeesAreExportedAsCsvThroughThePlatform() {
        NewOperator north = newOperatorWithLead(hq(), unique("North"), null);
        String sponsor = newSponsor(unique("fm9b2-csv-"));
        job(sponsor, north.operatorId(), "2030-09", "0.40", 1_234, 0, "0", false);
        byte[] bytes = client.post().uri("/api/queries/qb.admin.ai-fees/export?format=csv")
            .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, aiAdmin())
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en").bodyValue(request(Map.of("sponsorId", sponsor)))
            .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        String csv = new String(bytes, StandardCharsets.UTF_8).replace("﻿", "");
        String[] lines = csv.strip().split("\\R");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).contains("Fee month").contains("Taken on in all (JPY)").contains("Not charged yet");
        assertThat(lines[1]).contains("2030-09").contains(sponsor).contains("1234").contains("0.4");
        client.post().uri("/api/queries/qb.admin.ai-fees/export?format=csv").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "fm9b2-nobody", "task.read"))
            .bodyValue(request(Map.of())).exchange().expectStatus().isForbidden();
    }

    @Test
    void aMonthOfThousandsOfJobsIsSummedInUnderASecond() {
        String m = "2031-01";
        // 3,000 jobs of 300 sponsors in one month, with fees, absorbed amounts and estimates.
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, zone, requested_at, finished_at, version, fee_mode, fee_operator_id, "
            + "fee_markup, fee_usd_jpy, reserved_yen, cost_usd, billable_usd, unrecorded_usd, fee_yen, absorbed_yen, "
            + "fee_period, fee_charge_id) SELECT gen_random_uuid()::text, gen_random_uuid()::text, 'perf-' || (i % 300), "
            + "gen_random_uuid(), 'QUESTIONS', '{}', 'claude-opus-5-5', 'ACCEPTED', 10, 10, 'Asia/Tokyo', now(), now(), 1, "
            + "'CREDIT', 'perf-op-' || (i % 5), 1.5, 150, 0, 0.1, 0.1, 0.01, 100 + i % 7, i % 3, ?, "
            + "CASE WHEN i % 2 = 0 THEN 'c' || i END FROM generate_series(1, 3000) AS i", m);
        execute("ANALYZE qb_ai_job");
        String admin = aiAdmin();
        items("qb.admin.ai-fees", admin, request(Map.of("month", m)));
        long start = System.nanoTime();
        List<Map<String, Object>> rows = items("qb.admin.ai-fees", admin, request(Map.of("month", m)));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(rows).isNotEmpty();
        assertThat(millis).as("the fees of a month of 3,000 jobs, in ms").isLessThan(1_000);
    }

    // ---- the alert ---------------------------------------------------------------------------------------------

    /** Two hours into the next month of the deployment's zone: the alert's tests do not share a month. */
    private String freshMonth() {
        ZonedDateTime now = clock.instant().atZone(ZONE);
        ZonedDateTime next = now.withDayOfMonth(1).toLocalDate().plusMonths(1).atStartOfDay(ZONE).plusHours(2);
        advance(Duration.between(now, next));
        return month(clock.instant());
    }

    private JobRunner.JobRun runCheck() {
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        return jobRunner.execute(qbAiAbsorbedCheckJob, clock.instant());
    }

    private void later() {
        advance(Duration.ofHours(1));
    }

    /** Jobs of a month whose absorbed total is {@code yen} (all of it as absorbed yen). */
    private void absorbed(String period, long yen) {
        job("fm9b2-alert-" + period, null, period, "0.1", 100, yen, "0", false);
    }

    @Test
    void underTheAlertThereIsNoTaskAndAboveItExactlyOneForTheMonthHoweverOftenItRuns() {
        String m = freshMonth();
        setParam(QbParams.AI_FEE_ABSORBED_ALERT_YEN, "1000");
        absorbed(m, 600);
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).isEmpty();
        // Exactly at the alert is not above it.
        absorbed(m, 400);
        later();
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).isEmpty();

        // One more yen: finance gets a task, once.
        absorbed(m, 1);
        later();
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).singleElement().satisfies(task ->
            assertThat(task).containsEntry("status", "OPEN").containsEntry("assignee_permission",
                QbPermissions.RECONCILE).containsEntry("title_key", "task.qb.ai.absorbed-high"));
        later();
        runCheck();
        systemRun("QB_AI_ABSORBED_CHECK", body("scheduledTime", clock.instant().toString()));
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).hasSize(1);

        // A person closes it: it is not made again either.
        String taskId = String.valueOf(query("SELECT task_id FROM sys_task_version WHERE source_key = ? LIMIT 1",
            "qb.ai-absorbed:" + m).getFirst().get("task_id"));
        run(com.jabiz.runtime.task.TaskResolution.RESOLVE, finance(), body("taskId", taskId, "outcome", "DONE",
            "note", "Looked at it"));
        later();
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
    }

    @Test
    void theEstimatedCallsWithNoAnswerCountInYenAtTheFrozenRate() {
        String m = freshMonth();
        setParam(QbParams.AI_FEE_ABSORBED_ALERT_YEN, "1000");
        // 500 absorbed + ceil(3.34 dollars * 150) = 501 more: 1,001 in all.
        job("fm9b2-est", null, m, "1", 100, 500, "3.34", false);
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).hasSize(1);
        assertThat(items("qb.system.ai-absorbed", TestTokens.bearer(tokens, "fm9b2-system", QbPermissions.AI_SYSTEM),
            request(Map.of("month", m)))).singleElement().satisfies(row -> {
                assertThat(money(row.get("absorbedYen"))).isEqualByComparingTo("1001");
                assertThat(money(row.get("alertYen"))).isEqualByComparingTo("1000");
                assertThat(money(row.get("taskExists"))).isEqualByComparingTo("1");
            });
    }

    @Test
    void anAlertOfZeroNeverMakesATask() {
        String m = freshMonth();
        absorbed(m, 5_000_000);
        setParam(QbParams.AI_FEE_ABSORBED_ALERT_YEN, "0");
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + m)).isEmpty();
    }

    @Test
    void lastMonthsTotalIsCheckedToo() {
        String crossed = freshMonth();
        setParam(QbParams.AI_FEE_ABSORBED_ALERT_YEN, "1000");
        absorbed(crossed, 2_000);
        // No check ran in that month; the first one of the next finds it.
        String next = freshMonth();
        assertThat(next).isNotEqualTo(crossed);
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + crossed)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + next)).isEmpty();
        // Two months later it is not asked about any more (only this month and the one before are looked at).
        String third = freshMonth();
        absorbed(third, 10);
        freshMonth();
        runCheck();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + third)).isEmpty();
        assertThat(HelpItSupport.tasksOf("qb.ai-absorbed:" + crossed)).hasSize(1);
    }
}
