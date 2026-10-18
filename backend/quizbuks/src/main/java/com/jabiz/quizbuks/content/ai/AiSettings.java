package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.setup.QbParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The parameters of AI generation as a request reads them (docs/quizbuks/plans/Q8-ai.md, A-2, A-7, A-9), from their
 * stored text: a parameter missing (a controlled one not yet published), a model or effort not in the lists, or a limit
 * that is not a whole number makes AI unavailable ({@link AiCodes#UNAVAILABLE}) rather than let a request through on a
 * guess. So does the switch while it is off.
 */
public record AiSettings(boolean enabled, String model, String effort, Integer dailyJobs, Long monthlyTokens,
    Long globalMonthlyTokens, int expireDays) {

    /** The keys a request reads. */
    public static final List<String> KEYS = List.of(QbParams.AI_ENABLED, QbParams.AI_MODEL, QbParams.AI_EFFORT,
        QbParams.AI_SPONSOR_DAILY_JOBS, QbParams.AI_SPONSOR_MONTHLY_TOKENS, QbParams.AI_GLOBAL_MONTHLY_TOKENS,
        QbParams.AI_EXPIRE_DAYS);

    /** Candidates wait this many days when the parameter has no usable value. */
    public static final int DEFAULT_EXPIRE_DAYS = 7;

    private static final Pattern WHOLE = Pattern.compile("[0-9]{1,15}");

    /** The settings of the stored values, by key. */
    public static AiSettings of(Map<String, String> values) {
        Integer days = whole(values.get(QbParams.AI_EXPIRE_DAYS)) == null ? null
            : (int) Math.min(365, whole(values.get(QbParams.AI_EXPIRE_DAYS)));
        Long daily = whole(values.get(QbParams.AI_SPONSOR_DAILY_JOBS));
        return new AiSettings("true".equals(values.get(QbParams.AI_ENABLED)), values.get(QbParams.AI_MODEL),
            values.get(QbParams.AI_EFFORT), daily == null ? null : (int) Math.min(10_000, daily),
            whole(values.get(QbParams.AI_SPONSOR_MONTHLY_TOKENS)), whole(values.get(QbParams.AI_GLOBAL_MONTHLY_TOKENS)),
            days == null || days < 1 ? DEFAULT_EXPIRE_DAYS : days);
    }

    /** Why a request cannot be made with these settings; none when it can. */
    public List<Violation> problems() {
        List<String> why = new ArrayList<>();
        if (!enabled) {
            why.add("switched off");
        }
        if (model == null || !ClaudeService.MODELS.contains(model)) {
            why.add("model not one of " + ClaudeService.MODELS);
        }
        if (effort == null || !ClaudeService.EFFORTS.contains(effort)) {
            why.add("effort not one of " + ClaudeService.EFFORTS);
        }
        if (dailyJobs == null || monthlyTokens == null || globalMonthlyTokens == null) {
            why.add("limits not set");
        }
        if (why.isEmpty()) {
            return List.of();
        }
        return List.of(new Violation(null, AiCodes.UNAVAILABLE, "AI generation is unavailable: "
            + String.join(", ", why), Map.of("enabled", enabled)));
    }

    /** The limits, once {@link #problems()} found none. */
    public AiLimits.Settings limits() {
        return new AiLimits.Settings(dailyJobs, monthlyTokens, globalMonthlyTokens);
    }

    private static Long whole(String text) {
        return text != null && WHOLE.matcher(text.strip()).matches() ? Long.parseLong(text.strip()) : null;
    }
}
