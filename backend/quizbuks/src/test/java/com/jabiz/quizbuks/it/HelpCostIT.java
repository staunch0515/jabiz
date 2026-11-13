package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ai.AiLimits;
import com.jabiz.quizbuks.help.QbHelp;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.task.TaskResolution;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M10 (docs/quizbuks/plans/impl/F-M10.md): the AI costs the platform pays. The dollars of the AI help and of the
 * reviewers' translation checks together against {@code qb.help.global-monthly-usd-alert}: the gauge (with its month),
 * the daily check's task for finance (once a month, last month's too, never closed by itself), headquarters' page, and
 * everybody's row of the usage against the people's in the reconciliation. Each test begins in a month of its own.
 */
class HelpCostIT extends HelpItSupport {

    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    @BeforeEach
    void freshMonth() {
        helpOn();
        setParam(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT, "8");
        advanceToNextMonth();
    }

    /** Two hours into the next month of the deployment's zone: the tests do not share a month. */
    private void advanceToNextMonth() {
        ZonedDateTime now = clock.instant().atZone(ZONE);
        ZonedDateTime next = now.withDayOfMonth(1).toLocalDate().plusMonths(1).atStartOfDay(ZONE).plusHours(2);
        advance(Duration.between(now, next));
    }

    /** An hour later in the same month: the same scheduled time would only replay the earlier run. */
    private void later() {
        advance(Duration.ofHours(1));
    }

    private String month() {
        return AiLimits.month(clock.instant(), ZONE);
    }

    /** A reviewer's translation check that finished now and cost {@code usd}. */
    private void translationCheck(String usd) {
        execute("INSERT INTO qb_ai_job (ai_job_id, external_job_id, sponsor_id, quiz_id, kind, params, model, status, "
            + "input_bytes, estimated_tokens, cost_usd, zone, requested_at, finished_at, version) VALUES (?, ?, ?, ?::uuid, "
            + "'TRANSLATION_CHECK', '{}', 'claude-haiku-5-5', 'EXPIRED', 1, 1, ?, 'Asia/Tokyo', ?, ?, 1)",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), "reviewer-fm10", UUID.randomUUID().toString(),
            new BigDecimal(usd), java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
    }

    private String finance() {
        return TestTokens.bearer(tokens, "fm10-finance", QbPermissions.RECONCILE, QbPermissions.ADMIN_AI_READ,
            "task.read", "task.resolve");
    }

    private void oneHelpQuestion() {
        ask(taker(newTaker("t-fm10-" + UUID.randomUUID(), "JP")), null, QbHelp.APP, "How do I withdraw?", "wallet");
        runJobs();
    }

    @Test
    void theTranslationChecksAreCountedWithTheHelpAndFinanceGetsOneTaskAMonthWhichNothingClosesByItself() {
        oneHelpQuestion();
        translationCheck("9.50");
        String source = "qb.help-cost:" + month();
        assertThat(tasksOf(source)).isEmpty();

        runDaily(usageJob);
        assertThat(tasksOf(source)).singleElement().satisfies(task -> {
            assertThat(task).containsEntry("status", "OPEN").containsEntry("assignee_permission", QbPermissions.RECONCILE)
                .containsEntry("title_key", "task.qb.help.cost-high");
            assertThat(task.get("scope_name")).isNull();
        });
        // Over the alert: 9.5 and a little of the help against 8 dollars.
        assertThat(gauge.costValue()).isGreaterThanOrEqualTo(118);

        // Once a month, however often it runs; a person closes it, and then it is not made again either.
        later();
        runDaily(usageJob);
        assertThat(tasksOf(source)).hasSize(1);
        String taskId = String.valueOf(query("SELECT task_id FROM sys_task_version WHERE source_key = ? LIMIT 1", source)
            .getFirst().get("task_id"));
        run(TaskResolution.RESOLVE, finance(), body("taskId", taskId, "outcome", "DONE", "note", "Looked at it"));
        assertThat(tasksOf(source)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
        later();
        runDaily(usageJob);
        assertThat(tasksOf(source)).singleElement().satisfies(task -> assertThat(task).containsEntry("status", "DONE"));
    }

    @Test
    void underTheAlertThereIsNoTaskAndWithoutAnAlertNeitherATaskNorAShare() {
        oneHelpQuestion();
        translationCheck("1.00");
        runDaily(usageJob);
        assertThat(tasksOf("qb.help-cost:" + month())).isEmpty();
        assertThat(gauge.costValue()).isBetween(10L, 99L);

        // An alert of 0 is "no alert": whatever was spent, no task and no share on the gauge.
        translationCheck("500");
        setParam(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT, "0");
        later();
        runDaily(usageJob);
        assertThat(tasksOf("qb.help-cost:" + month())).isEmpty();
        assertThat(gauge.costValue()).isZero();
    }

    @Test
    void aMonthThatCrossedTheAlertAfterItsLastCheckStillGetsItsTaskOnTheFirstCheckOfTheNext() {
        oneHelpQuestion();
        String crossed = month();
        translationCheck("20");
        // No daily check ran in that month; the next one finds it.
        advanceToNextMonth();
        runDaily(usageJob);
        assertThat(tasksOf("qb.help-cost:" + crossed)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
        assertThat(tasksOf("qb.help-cost:" + month())).isEmpty();
        // A third month later it is not asked about again (only this month and the one before are looked at), and the
        // task of the month before stays open: nothing closes it by itself.
        advanceToNextMonth();
        advanceToNextMonth();
        runDaily(usageJob);
        assertThat(tasksOf("qb.help-cost:" + crossed)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("status", "OPEN"));
    }

    @Test
    void aGaugeReadsZeroInAMonthItWasNotSetIn() {
        param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "1000");
        try {
            oneHelpQuestion();
            translationCheck("9");
            runDaily(usageJob);
            assertThat(gauge.value()).isPositive();
            assertThat(gauge.costValue()).isPositive();
            // The next month nobody has run a check yet: last month's values are not shown for it.
            advanceToNextMonth();
            assertThat(gauge.value()).isZero();
            assertThat(gauge.costValue()).isZero();
        } finally {
            param(QbParams.HELP_GLOBAL_MONTHLY_TOKENS, "20000000");
        }
    }

    @Test
    void aJobOutcomeUpdatesTheDollarGaugeAfterTheCommitFromWhatTheMonthCostSoFar() {
        translationCheck("7.90");
        // The check alone is 98 % of the alert; the help's estimate comes on top.
        oneHelpQuestion();
        assertThat(gauge.costValue()).isBetween(98L, 100L);
        Map<String, Object> everybody = helpUsage(QbHelp.EVERYBODY);
        assertThat(new BigDecimal(String.valueOf(everybody.get("cost_usd")))).isPositive();
    }

    @Test
    void headquartersPageListsTheChecksTheAlertAndTheShareOfItEmptyWithoutAnAlert() {
        oneHelpQuestion();
        translationCheck("4");
        translationCheck("2");
        List<Map<String, Object>> rows = items(QbHelp.USAGE_TEMPLATE, hqHelp(), Map.of("params", Map.of("month", month())));
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(number(row.get("checkJobs"))).isEqualTo(2);
            assertThat(new BigDecimal(String.valueOf(row.get("checksUsd")))).isEqualByComparingTo("6.00");
            assertThat(new BigDecimal(String.valueOf(row.get("alertUsd")))).isEqualByComparingTo("8");
            assertThat(new BigDecimal(String.valueOf(row.get("totalUsd")))).isGreaterThanOrEqualTo(new BigDecimal("6"));
            assertThat(new BigDecimal(String.valueOf(row.get("percentOfAlert")))).isGreaterThanOrEqualTo(new BigDecimal("75"));
        });
        setParam(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT, "0");
        assertThat(items(QbHelp.USAGE_TEMPLATE, hqHelp(), Map.of("params", Map.of("month", month())))).singleElement()
            .satisfies(row -> {
                assertThat(new BigDecimal(String.valueOf(row.get("alertUsd")))).isEqualByComparingTo("0");
                assertThat(row.get("percentOfAlert")).isNull();
            });
    }

    @Test
    void theTasksOfOtherAdministratorsDoNotSeeTheCostTask() {
        oneHelpQuestion();
        translationCheck("30");
        runDaily(usageJob);
        String source = "qb.help-cost:" + month();
        // For finance (qb.reconcile) only: the content administrators of the help do not hold that permission.
        assertThat(tasksOf(source)).singleElement()
            .satisfies(task -> assertThat(task).containsEntry("assignee_permission", QbPermissions.RECONCILE));
    }
}
