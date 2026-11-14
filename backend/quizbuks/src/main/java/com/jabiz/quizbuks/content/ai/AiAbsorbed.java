package com.jabiz.quizbuks.content.ai;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.RunTemplate;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * F-M9 (docs/quizbuks/plans/impl/F-M9.md §6, §7): the alert on what the platform took on of the AI fees. The total of a
 * fee month is the jobs' {@code absorbedYen} (the overrun and the credit that fell short, with the fee's markup) and
 * the estimated dollars of the calls with no answer in yen at each job's frozen rate ({@code unrecordedUsd}, a cost
 * with no markup); the template {@value #TEMPLATE} reads it with the alert {@code qb.ai.fee.absorbed-alert-yen} and
 * whether the month's task exists. The daily check {@value #JOB} asks finance ({@code qb.reconcile}) to look once for
 * each month that is above the alert: this month and the two before it. A month's total can still grow after it ends
 * (a job's outcome is recorded in the month it ends in, but the monthly charge, the reconciliation and the late
 * calls of retried jobs follow in the next weeks), and a check that did not run (a missed run is run again by hand,
 * a deployment in between) must not let a month that crossed the alert go without its task: three months are read, each
 * with its own task, so two missed months are found too and older ones are left to finance's own monthly look. Modelled on the AI help's cost alert ({@code HelpCosts}).
 */
public final class AiAbsorbed {

    public static final String PROCESS = "QB_AI_ABSORBED_CHECK";
    public static final String JOB = "qb.ai-absorbed-check";
    /** 04:17 in the fiscal year's zone, after the night's AI credit expiry ({@code quizbuks.ai-absorbed-check.cron}). */
    public static final String CRON = "0 17 4 * * *";
    public static final String TEMPLATE = "qb.system.ai-absorbed";

    /** The lock of the check, so that two runs never both see no task of the month. */
    static final String LOCK = "qb.ai-absorbed";
    /** The task's name; its message is {@code task.qb.ai.absorbed-high}. */
    static final String TASK = "qb.ai.absorbed-high";

    private static final String NOW = "absorbedNow";
    private static final String BEFORE = "absorbedBefore";
    private static final String BEFORE_THAT = "absorbedBeforeThat";

    public record CheckInput(@NotNull Instant scheduledTime) {}

    /** @param tasks how many months were asked about by this run (0 to 3) */
    public record CheckOutput(int tasks) {}

    private AiAbsorbed() {}

    /** The source key of the month's task (once per month, open or closed). */
    static String source(String month) {
        return "qb.ai-absorbed:" + month;
    }

    static String before(String month) {
        return YearMonth.parse(month).minusMonths(1).toString();
    }

    /** What a month took on, the alert in effect and whether the month's task exists. */
    record Month(BigDecimal absorbed, BigDecimal alert, boolean taskExists) {

        static Month of(Map<String, Object> row) {
            if (row == null) {
                return new Month(BigDecimal.ZERO, BigDecimal.ZERO, false);
            }
            return new Month(decimal(row.get("absorbedYen")), decimal(row.get("alertYen")),
                decimal(row.get("taskExists")).signum() > 0);
        }

        /** Whether the month is above the alert (an alert of 0 never) and has no task yet (open or closed). */
        boolean due() {
            return alert.signum() > 0 && absorbed.compareTo(alert) > 0 && !taskExists;
        }

        private static BigDecimal decimal(Object value) {
            return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        }
    }

    public static ProcessDefinition<CheckInput, CheckOutput, ProcessContext> check(ZoneId zone) {
        return ProcessDefinition.define(PROCESS, 1, CheckInput.class, CheckOutput.class, ProcessContext.class, pb -> pb
            .description("Asks finance to look when what the platform took on of a month's AI fees is above the alert.")
            .permissions(QbPermissions.AI_SYSTEM)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new CheckOutput((due(ctx, NOW) ? 1 : 0) + (due(ctx, BEFORE) ? 1 : 0)
                + (due(ctx, BEFORE_THAT) ? 1 : 0)))
            .step("Take the check's turn", HoldLock.<ProcessContext>exclusive(ctx -> LOCK))
            .step("Read this month's total", RunTemplate.<ProcessContext>of(TEMPLATE,
                ctx -> Map.of("month", month(ctx, zone)), NOW))
            .step("Read last month's total", RunTemplate.<ProcessContext>of(TEMPLATE,
                ctx -> Map.of("month", before(month(ctx, zone))), BEFORE))
            .step("Read the total of the month before last", RunTemplate.<ProcessContext>of(TEMPLATE,
                ctx -> Map.of("month", before(before(month(ctx, zone)))), BEFORE_THAT))
            .step("Ask finance about this month", CreateTask.<ProcessContext>when(ctx -> due(ctx, NOW),
                ctx -> task(month(ctx, zone), total(ctx, NOW))))
            .step("Ask finance about last month", CreateTask.<ProcessContext>when(ctx -> due(ctx, BEFORE),
                ctx -> task(before(month(ctx, zone)), total(ctx, BEFORE))))
            .step("Ask finance about the month before last", CreateTask.<ProcessContext>when(
                ctx -> due(ctx, BEFORE_THAT), ctx -> task(before(before(month(ctx, zone))), total(ctx, BEFORE_THAT)))));
    }

    /** The month of the run's time (opTime, the injected clock's), in the fiscal year's zone, as {@code feePeriod}. */
    private static String month(ProcessContext ctx, ZoneId zone) {
        return FeeRules.period(ctx.opTime(), zone);
    }

    private static Month total(ProcessContext ctx, String key) {
        return Month.of(first(ctx, key));
    }

    private static boolean due(ProcessContext ctx, String key) {
        return total(ctx, key).due();
    }

    private static TaskSpec task(String month, Month total) {
        Map<String, String> params = Map.of("month", month, "yen", total.absorbed().toPlainString(),
            "alert", total.alert().toPlainString());
        return TaskSpec.forPermission(TASK, "task." + TASK, params, QbPermissions.RECONCILE)
            .link("/qb/ai")
            .source(source(month));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> first(ProcessContext ctx, String key) {
        Object rows = ctx.contains(key) ? ctx.get(key) : null;
        List<Map<String, Object>> list = rows == null ? List.of() : (List<Map<String, Object>>) rows;
        return list.isEmpty() ? null : list.getFirst();
    }
}
