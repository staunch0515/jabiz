package com.jabiz.quizbuks.help;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.setup.QbParams;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The AI help's parameters as read at a time (F-C10): a parameter not there yet (before the setup, a controlled one not
 * yet published) is its declared value, except the switch, which is off until published. Numbers are whole, never
 * negative, and within the bounds the processes rely on.
 *
 * @param model a model the platform allows, or null when the parameter names another (refused)
 */
public record HelpSettings(boolean enabled, String model, int userDaily, long globalMonthlyTokens, int historyTurns,
    int retentionDays, long manualMaxBytes, int pollSeconds, int pollMaxMinutes, int remindDays, int autoCloseDays,
    int questionMaxLength, int answerMaxLength, int maxLinks, BigDecimal cacheReadWeight,
    BigDecimal globalMonthlyUsdAlert) {

    /** The most turns sent with a question, whatever the parameter says. */
    public static final int MAX_HISTORY_TURNS = 20;

    public static HelpSettings of(ParamValues params) {
        Boolean enabled = params == null ? null : params.find(QbParams.HELP_ENABLED, Boolean.class).orElse(null);
        String model = QbParams.text(params, QbParams.HELP_MODEL);
        return new HelpSettings(Boolean.TRUE.equals(enabled),
            ClaudeService.MODELS.contains(model) ? model : null,
            (int) Math.min(number(params, QbParams.HELP_USER_DAILY), 10_000),
            number(params, QbParams.HELP_GLOBAL_MONTHLY_TOKENS),
            (int) Math.min(number(params, QbParams.HELP_HISTORY_TURNS), MAX_HISTORY_TURNS),
            (int) Math.clamp(number(params, QbParams.HELP_RETENTION_DAYS), 1, 3650),
            number(params, QbParams.HELP_MANUAL_MAX_BYTES),
            (int) Math.clamp(number(params, QbParams.HELP_POLL_SECONDS), 1, 600),
            (int) Math.clamp(number(params, QbParams.HELP_POLL_MAX_MINUTES), 1, 60),
            (int) Math.clamp(number(params, QbParams.HELP_ESCALATION_REMIND_DAYS), 1, 3650),
            (int) Math.clamp(number(params, QbParams.HELP_AUTO_CLOSE_DAYS), 1, 3650),
            (int) Math.clamp(number(params, QbParams.HELP_QUESTION_MAX_LENGTH), 100, QbHelp.TEXT_LENGTH),
            (int) Math.clamp(number(params, QbParams.HELP_ANSWER_MAX_LENGTH), 500, QbHelp.ANSWER_LENGTH),
            (int) Math.clamp(number(params, QbParams.HELP_MAX_LINKS), 0, HelpAnswers.MAX_LINKS),
            weight(params, QbParams.HELP_CACHE_READ_WEIGHT),
            alert(params));
    }

    /**
     * The tokens a usage row counts towards {@code qb.help.global-monthly-tokens} (F-C10b): every output token and
     * input token, except that one read from the prompt cache counts {@code qb.help.cache-read-weight} (rounded up).
     */
    public long counted(long inputTokens, long outputTokens, long cacheReadTokens) {
        long read = Math.clamp(cacheReadTokens, 0, Math.max(0, inputTokens));
        long weighted = cacheReadWeight.multiply(BigDecimal.valueOf(read)).setScale(0, RoundingMode.CEILING)
            .longValue();
        return inputTokens - read + weighted + outputTokens;
    }

    /** The most the dollar alert may be set to. */
    public static final BigDecimal MAX_USD_ALERT = new BigDecimal("1000000");

    /** The dollar threshold (F-M10) in cents, 0 to 1,000,000; its declared value while it is not there; 0 is no alert. */
    static BigDecimal alert(ParamValues params) {
        BigDecimal value = params == null ? null : params.find(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT, BigDecimal.class)
            .orElse(null);
        if (value == null) {
            value = new BigDecimal(QbParams.defaultOf(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT));
        }
        return value.max(BigDecimal.ZERO).min(MAX_USD_ALERT).setScale(2, RoundingMode.DOWN);
    }

    /** A weight between 0 and 1 a parameter gives; its declared value while it is not there. */
    static BigDecimal weight(ParamValues params, String key) {
        BigDecimal value = params == null ? null : params.find(key, BigDecimal.class).orElse(null);
        if (value == null) {
            value = new BigDecimal(QbParams.defaultOf(key));
        }
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    /** A whole, non-negative number a parameter gives; its declared value while it is not there. */
    static long number(ParamValues params, String key) {
        BigDecimal value = params == null ? null : params.find(key, BigDecimal.class).orElse(null);
        if (value == null) {
            value = new BigDecimal(QbParams.defaultOf(key));
        }
        return Math.max(0, value.setScale(0, RoundingMode.DOWN).longValue());
    }
}
