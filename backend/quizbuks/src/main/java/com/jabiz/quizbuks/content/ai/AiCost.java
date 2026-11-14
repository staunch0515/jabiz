package com.jabiz.quizbuks.content.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * What a job's tokens cost, in US dollars, for the administrators' page (docs/quizbuks/plans/Q8-ai.md, A-8: an
 * estimate only, nothing is billed to sponsors). Each token is priced at the model that used it: the requested model's
 * tokens at its price, the provider's fallback model's (A-10) at that model's. The prices of the models QuizBuks knows
 * are here (Anthropic's list prices per million tokens, without cache discounts, so the estimate errs high; the AI
 * help takes the cache reads' discount off, {@link #cacheReadDiscount}); a model
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
        // Up to 100,000 input tokens a request; above, LONG_PROMPT_PRICES.
        ClaudeService.HAIKU, Price.of("0.10", "0.50"),
        "claude-opus-5", Price.of("5", "25"),
        "claude-opus-4-8", Price.of("5", "25"));

    /**
     * The fallback models' price when the answer does not say which ran (a failed job): Opus 5.5 and Sonnet 5.5 fall
     * back to Claude Opus 5 or Claude Opus 4.8, both at this price; Haiku 5.5 has no fallback.
     */
    private static final Price FALLBACK = Price.of("5", "25");

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * The long prompts' prices (Q8b review L2): Anthropic prices each request of the smallest model above
     * {@value ClaudeCalls#LONG_PROMPT_FROM} input tokens at this rate; the other models have one rate over their whole
     * context window. The calls above the line are recorded apart, per call ({@code ClaudeCalls.record}).
     */
    private static final Map<String, Price> LONG_PROMPT_PRICES = Map.of(ClaudeService.HAIKU, Price.of("0.50", "2.50"));

    /** Whether the model has a second rate for long prompts. */
    public static boolean hasLongPromptRate(String model) {
        return model != null && LONG_PROMPT_PRICES.containsKey(model);
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
     * @param usage       the tokens of every attempt, with the fallback model's and the long prompts' parts
     * @param fallback    the price of a model QuizBuks does not know (the parameters), or null
     */
    public static BigDecimal of(String model, String servedModel, ClaudeApi.Usage usage, Price fallback) {
        Price requested = known(model) != null ? known(model) : fallback;
        long fallbackIn = usage.fallbackInputTokens();
        long fallbackOut = usage.fallbackOutputTokens();
        // At most the requested model's own part: a fallback token is never also a long-prompt one (review L-b).
        long longIn = Math.min(usage.longPromptInputTokens(), Math.max(0, usage.inputTokens() - fallbackIn));
        long longOut = Math.min(usage.longPromptOutputTokens(), Math.max(0, usage.outputTokens() - fallbackOut));
        Price fellBack = fallbackIn == 0 && fallbackOut == 0 ? requested
            : servedModel != null && !servedModel.equals(model) && known(servedModel) != null ? known(servedModel)
            : FALLBACK;
        Price longPrompt = LONG_PROMPT_PRICES.getOrDefault(model, requested);
        if (requested == null || fellBack == null || longPrompt == null) {
            return null;
        }
        // Three disjoint parts, each token priced once: the requested model's at its price, the fallback's at the
        // fallback's, and the requested model's calls at the long-prompt rate.
        BigDecimal total = requested.input().multiply(BigDecimal.valueOf(
                Math.max(0, usage.inputTokens() - fallbackIn - longIn)))
            .add(requested.output().multiply(BigDecimal.valueOf(Math.max(0, usage.outputTokens() - fallbackOut
                - longOut))))
            .add(fellBack.input().multiply(BigDecimal.valueOf(fallbackIn)))
            .add(fellBack.output().multiply(BigDecimal.valueOf(fallbackOut)))
            .add(longPrompt.input().multiply(BigDecimal.valueOf(longIn)))
            .add(longPrompt.output().multiply(BigDecimal.valueOf(longOut)));
        return total.divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /**
     * F-M9 (plan §3, review M7; #692 review M1): the most a request may cost, in dollars to six places, which it
     * reserves of the sponsor's AI credit, a strict bound. Every attempt may run on the requested model and, refused, be
     * handed whole to the model it may fall back to (A-10: the provider charges the refused call's tokens and runs the
     * input again), so an attempt is its usage at the requested model's price (at the long-prompt rate where
     * {@code attempt} carries that part) plus all of it at the fallback's (the smallest model has none); the searches are
     * at their price. Null when a price is missing.
     *
     * @param attempt  the most one attempt may use (every call of it: {@link ClaudeWebResearch#mostOfAttempt} for the web
     *                 reader; its reserved input and all the output it asks for otherwise)
     * @param attempts the attempts the job may make (all of them, those whose lease expired included)
     * @param searches the most searches one attempt may make
     * @param fallback the price of a model QuizBuks does not know (the parameters), or null
     */
    public static BigDecimal maxOf(String model, ClaudeApi.Usage attempt, int attempts, long searches, Price fallback) {
        BigDecimal most = of(model, null, attempt, fallback);
        if (most == null) {
            return null;
        }
        if (!ClaudeService.HAIKU.equals(model)) {
            ClaudeApi.Usage fellBack = new ClaudeApi.Usage(attempt.inputTokens(), attempt.outputTokens(),
                attempt.inputTokens(), attempt.outputTokens(), 0, 0);
            BigDecimal other = of(model, null, fellBack, fallback);
            if (other == null) {
                return null;
            }
            most = most.add(other);
        }
        return most.add(searches(searches)).multiply(BigDecimal.valueOf(Math.max(1, attempts)))
            .setScale(6, RoundingMode.HALF_UP);
    }

    /** F-M9: what web searches cost, at the provider's price ({@link ClaudeWebResearch#SEARCH_PRICE_USD}). */
    public static BigDecimal searches(long searches) {
        return ClaudeWebResearch.searchCost(searches);
    }

    /** What a prompt-cache write costs above the input price it is already counted at: a quarter more (5 minutes). */
    static final BigDecimal CACHE_WRITE_PREMIUM = new BigDecimal("0.25");

    /**
     * F-C10b: the premium of {@code tokens} written to the provider's prompt cache, in dollars to six places; null for
     * a model QuizBuks does not know. {@link #of} prices every input token at the list price, cache reads included
     * (they cost a tenth of it), so the estimate errs high; only the writes cost more than that, and this premium is
     * added so that it never errs low.
     */
    public static BigDecimal cacheWritePremium(String model, long tokens) {
        Price price = known(model);
        if (price == null) {
            return null;
        }
        return price.input().multiply(CACHE_WRITE_PREMIUM).multiply(BigDecimal.valueOf(Math.max(0, tokens)))
            .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /** What a token read from the provider's prompt cache costs, as a fraction of the input price (the provider's price). */
    static final BigDecimal CACHE_READ_FRACTION = new BigDecimal("0.10");

    /**
     * F-M10: how much less than {@link #of} (which prices every input token at the list price) {@code tokens} read from
     * the prompt cache cost: nine tenths of the input price, in dollars to six places; null for a model QuizBuks does
     * not know. The fraction is the provider's price, not the parameter {@code qb.help.cache-read-weight} (which only
     * says how the monthly tokens are counted). The discount is at the requested model's ordinary input price, so a job
     * that fell back to a dearer model or went over the long-prompt line still errs high, never low. A negative count is
     * none.
     */
    public static BigDecimal cacheReadDiscount(String model, long tokens) {
        Price price = known(model);
        if (price == null) {
            return null;
        }
        return price.input().multiply(BigDecimal.ONE.subtract(CACHE_READ_FRACTION))
            .multiply(BigDecimal.valueOf(Math.max(0, tokens))).divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private AiCost() {}
}
