package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.setup.QbParams;

import java.math.BigDecimal;
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
    Long globalMonthlyTokens, int expireDays, AiCost.Price price, Fee fee) {

    /**
     * @param price the price of a model {@link AiCost} does not know (the parameters {@code qb.ai.price.*}), or null
     *              while either is not a number
     * @param fee   F-M9: the AI fee's parameters
     */
    public AiSettings {}

    /**
     * F-M9 (docs/quizbuks/plans/impl/F-M9.md §6): the AI fee's parameters as stored; a value that is not a number, or
     * out of its range, is null.
     *
     * @param enabled     whether sponsors' requests are charged ({@code qb.ai.fee.enabled})
     * @param markup      the multiple of the list price, 1 to 10
     * @param usdJpy      the yen of a dollar, 50 to 500
     * @param taxRate     the platform fees' tax rate ({@code qb.fee.tax-rate}), 0 to below 1
     * @param minPurchase the least purchase of credit; null: none (not set)
     */
    public record Fee(boolean enabled, BigDecimal markup, BigDecimal usdJpy, BigDecimal taxRate,
        BigDecimal minPurchase) {}

    /** The keys a request reads. */
    public static final List<String> KEYS = List.of(QbParams.AI_ENABLED, QbParams.AI_MODEL, QbParams.AI_EFFORT,
        QbParams.AI_SPONSOR_DAILY_JOBS, QbParams.AI_SPONSOR_MONTHLY_TOKENS, QbParams.AI_GLOBAL_MONTHLY_TOKENS,
        QbParams.AI_EXPIRE_DAYS, QbParams.AI_PRICE_INPUT, QbParams.AI_PRICE_OUTPUT, QbParams.AI_FEE_ENABLED,
        QbParams.AI_FEE_MARKUP, QbParams.AI_FEE_USD_JPY, QbParams.FEE_TAX_RATE, QbParams.AI_CREDIT_MIN_PURCHASE);

    private static final BigDecimal MARKUP_MIN = BigDecimal.ONE;
    private static final BigDecimal MARKUP_MAX = BigDecimal.TEN;
    private static final BigDecimal USD_JPY_MIN = BigDecimal.valueOf(50);
    private static final BigDecimal USD_JPY_MAX = BigDecimal.valueOf(500);

    private static final Pattern PRICE = Pattern.compile("[0-9]{1,4}(\\.[0-9]{1,4})?");

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
            days == null || days < 1 ? DEFAULT_EXPIRE_DAYS : days,
            price(values.get(QbParams.AI_PRICE_INPUT), values.get(QbParams.AI_PRICE_OUTPUT)),
            new Fee("true".equals(values.get(QbParams.AI_FEE_ENABLED)),
                within(decimal(values.get(QbParams.AI_FEE_MARKUP)), MARKUP_MIN, MARKUP_MAX),
                within(decimal(values.get(QbParams.AI_FEE_USD_JPY)), USD_JPY_MIN, USD_JPY_MAX),
                rate(decimal(values.get(QbParams.FEE_TAX_RATE))),
                whole(values.get(QbParams.AI_CREDIT_MIN_PURCHASE)) == null ? null
                    : BigDecimal.valueOf(whole(values.get(QbParams.AI_CREDIT_MIN_PURCHASE)))));
    }

    /**
     * F-M9: why a sponsor's request cannot be charged while the fee is on (a markup, rate or tax rate not usable), as
     * {@link AiCodes#UNAVAILABLE}; none when the fee is off or can be worked out. A reviewer's check is never charged and
     * does not ask.
     */
    public List<Violation> feeProblems() {
        if (!fee.enabled() || fee.markup() != null && fee.usdJpy() != null && fee.taxRate() != null) {
            return List.of();
        }
        return List.of(new Violation(null, AiCodes.UNAVAILABLE, "AI generation is unavailable: the AI fee's markup, "
            + "exchange rate or tax rate is not set within its range", Map.of("enabled", enabled)));
    }

    private static BigDecimal decimal(String text) {
        if (text == null || !DECIMAL.matcher(text.strip()).matches()) {
            return null;
        }
        return new BigDecimal(text.strip());
    }

    private static BigDecimal within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0 ? null : value;
    }

    private static BigDecimal rate(BigDecimal value) {
        return value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0 ? null : value;
    }

    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,6}(\\.[0-9]{1,6})?");

    private static AiCost.Price price(String input, String output) {
        if (input == null || output == null || !PRICE.matcher(input.strip()).matches()
            || !PRICE.matcher(output.strip()).matches()) {
            return null;
        }
        return new AiCost.Price(new java.math.BigDecimal(input.strip()), new java.math.BigDecimal(output.strip()));
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
