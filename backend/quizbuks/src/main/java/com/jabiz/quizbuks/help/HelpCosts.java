package com.jabiz.quizbuks.help;

import com.jabiz.quizbuks.content.ai.AiLimits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;

/**
 * F-M10: the platform's AI dollars of a month and how they compare with {@code qb.help.global-monthly-usd-alert}. The
 * dollars are the help's estimate ({@link HelpOutcomes#cost}, summed in everybody's row of {@code QbHelpUsage}) and the
 * reviewers' translation checks' ({@code QbAiJob.costUsd}); the template {@value #TEMPLATE} reads them.
 */
public final class HelpCosts {

    /** The template: the dollars of a month, the alert in effect and whether the month's task exists. */
    public static final String TEMPLATE = "qb.system.help-cost";

    /** The lock of the daily check, so that two runs never both see no task of the month. */
    static final String LOCK = "qb.help-cost";

    /** The name of the task of a month; it is also the task's source key prefix. */
    static final String TASK = "qb.help.cost-high";

    private HelpCosts() {}

    /** The source key of the month's task (once per month, open or closed). */
    static String source(String month) {
        return "qb.help-cost:" + month;
    }

    /** The template's parameters for a month ({@code 2026-10}) in the zone. */
    public static Map<String, Object> params(String month, ZoneId zone) {
        YearMonth ym = YearMonth.parse(month);
        Instant from = ym.atDay(1).atStartOfDay(zone).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        return Map.of("month", month, "from", from, "to", to);
    }

    /** The month before {@code month}. */
    static String before(String month) {
        return YearMonth.parse(month).minusMonths(1).toString();
    }

    public static String monthOf(Instant at, ZoneId zone) {
        return AiLimits.month(at, zone);
    }

    /** What a month cost: the help's, the translation checks', and the alert in effect. */
    public record Month(BigDecimal usage, BigDecimal checks, BigDecimal alert, boolean taskExists) {

        public static Month of(Map<String, Object> row) {
            if (row == null) {
                return new Month(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
            }
            return new Month(decimal(row.get("usageUsd")), decimal(row.get("checksUsd")), decimal(row.get("alertUsd")),
                decimal(row.get("taskExists")).signum() > 0);
        }

        BigDecimal total() {
            return usage.add(checks);
        }

        /** Whether the month's dollars have reached the alert (an alert of 0 never). */
        boolean reached() {
            return alert.signum() > 0 && total().compareTo(alert) >= 0;
        }

        /** The dollars as a share of the alert, in whole percent, at most 1,000; 0 when the alert is 0. */
        public long percent() {
            return HelpCosts.percent(total(), alert);
        }
    }

    /** {@code dollars} as a share of {@code alert} in whole percent (rounded down), at most 1,000; 0 with no alert. */
    public static long percent(BigDecimal dollars, BigDecimal alert) {
        if (alert == null || alert.signum() <= 0 || dollars == null || dollars.signum() <= 0) {
            return 0;
        }
        return dollars.multiply(BigDecimal.valueOf(100)).divide(alert, 0, RoundingMode.DOWN).min(
            BigDecimal.valueOf(1_000)).longValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }
}
