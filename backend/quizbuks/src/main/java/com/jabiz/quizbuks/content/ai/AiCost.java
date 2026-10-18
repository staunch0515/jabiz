package com.jabiz.quizbuks.content.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * What a job's tokens cost, in US dollars, for the administrators' page (docs/quizbuks/plans/Q8-ai.md, A-8: an
 * estimate only, nothing is billed to sponsors). Each token is priced at the model that used it: the requested model's
 * tokens at its price, the provider's fallback model's (A-10) at that model's. The prices of the models QuizBuks knows
 * are here (Anthropic's list prices per million tokens, without cache discounts, so the estimate errs high); a model
 * it does not know is priced at the parameters {@code qb.ai.price.input-per-mtok-usd} and
 * {@code qb.ai.price.output-per-mtok-usd}, and nothing is estimated while those are not set.
 */
public final class AiCost {

    /** Dollars per million tokens. */
    public record Price(BigDecimal input, BigDecimal output) {
        static Price of(String input, String output) {
            return new Price(new BigDecimal(input), new BigDecimal(output));
        }
    }

    private static final Map<String, Price> PRICES = Map.of(
        ClaudeService.OPUS, Price.of("4", "20"),
        ClaudeService.SONNET, Price.of("2", "10"),
        // Up to 100,000 input tokens a request; above (PDF materials, Q8b) HAIKU_LONG.
        ClaudeService.HAIKU, Price.of("0.10", "0.50"),
        "claude-opus-5", Price.of("5", "25"),
        "claude-opus-4-8", Price.of("5", "25"));

    /**
     * The fallback models' price when the answer does not say which ran (a failed job): Opus 5.5 and Sonnet 5.5 fall
     * back to Claude Opus 5 or Claude Opus 4.8, both at this price; Haiku 5.5 has no fallback.
     */
    private static final Price FALLBACK = Price.of("5", "25");

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /** Haiku 5.5's price of a request of more than {@value #HAIKU_LONG_FROM} input tokens. */
    private static final Price HAIKU_LONG = Price.of("0.50", "2.50");
    static final long HAIKU_LONG_FROM = 100_000;

    /**
     * As {@link #of(String, String, ClaudeApi.Usage, Price)}, knowing how many attempts the tokens came from: a Haiku
     * request of more than {@value #HAIKU_LONG_FROM} input tokens on average is priced at the long prompts' price.
     */
    public static BigDecimal of(String model, String servedModel, ClaudeApi.Usage usage, Price fallback,
        Integer attempts) {
        int calls = attempts == null || attempts < 1 ? 1 : attempts;
        if (ClaudeService.HAIKU.equals(model) && usage.inputTokens() / calls > HAIKU_LONG_FROM) {
            return of(model, servedModel, usage, fallback, HAIKU_LONG);
        }
        return of(model, servedModel, usage, fallback);
    }

    /** The price of a model QuizBuks knows; null otherwise. */
    public static Price known(String model) {
        return model == null ? null : PRICES.get(model);
    }

    /**
     * The dollars of a job, to six places; null when a price is missing.
     *
     * @param model       the model requested
     * @param servedModel the model that answered, when it is known (a completed job)
     * @param usage       the tokens of every attempt, with the fallback model's part
     * @param fallback    the price of a model QuizBuks does not know (the parameters), or null
     */
    public static BigDecimal of(String model, String servedModel, ClaudeApi.Usage usage, Price fallback) {
        return of(model, servedModel, usage, fallback, known(model) != null ? known(model) : fallback);
    }

    private static BigDecimal of(String model, String servedModel, ClaudeApi.Usage usage, Price fallback,
        Price requested) {
        long fallbackIn = usage.fallbackInputTokens();
        long fallbackOut = usage.fallbackOutputTokens();
        Price fellBack = fallbackIn == 0 && fallbackOut == 0 ? requested
            : servedModel != null && !servedModel.equals(model) && known(servedModel) != null ? known(servedModel)
            : FALLBACK;
        if (requested == null || fellBack == null) {
            return null;
        }
        BigDecimal total = requested.input().multiply(BigDecimal.valueOf(usage.inputTokens() - fallbackIn))
            .add(requested.output().multiply(BigDecimal.valueOf(usage.outputTokens() - fallbackOut)))
            .add(fellBack.input().multiply(BigDecimal.valueOf(fallbackIn)))
            .add(fellBack.output().multiply(BigDecimal.valueOf(fallbackOut)));
        return total.divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private AiCost() {}
}
