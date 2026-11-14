package com.jabiz.quizbuks.content.ai;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M9 (docs/quizbuks/plans/impl/F-M9.md §3, review M7): the most a request may cost, which it reserves. */
class AiCostTest {

    @Test
    void theMostIsEveryAttemptOnTheModelAndAgainOnItsFallback() {
        // Sonnet ($2 / $10) falls back to a model at $5 / $25 (#692 review M1): an attempt of 10,000 in and 4,000 out may
        // be refused after its own $0.06 and run again at the fallback's $0.15; three attempts $0.63.
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(10_000, 4_000);
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 3, 0, null)).isEqualByComparingTo("0.630000");
        assertThat(AiCost.of(ClaudeService.SONNET, null, attempt, null)).isEqualByComparingTo("0.060000");
    }

    @Test
    void aRequestThatMayNotFallBackIsCountedOnItsOwnModel() {
        // A request with PDF documents goes without the provider's fallback (#692 re-review L4): its own $0.06 a time.
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(10_000, 4_000);
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 3, 0, null, false)).isEqualByComparingTo("0.180000");
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 3, 0, null, true))
            .isEqualByComparingTo(AiCost.maxOf(ClaudeService.SONNET, attempt, 3, 0, null));
    }

    @Test
    void theWebReadersAttemptIsEveryCallWithTheOutputBeforeIt() {
        // Four calls (#692 review M1): 100,000 + k x 30,000 in (k from 0) and 30,000 out each.
        ClaudeApi.Usage attempt = ClaudeWebResearch.mostOfAttempt(ClaudeService.SONNET, 100_000, 30_000);
        assertThat(ClaudeWebResearch.MAX_CALLS).isEqualTo(4);
        assertThat(attempt.inputTokens()).isEqualTo(4 * 100_000 + 6 * 30_000);
        assertThat(attempt.outputTokens()).isEqualTo(4 * 30_000);
        assertThat(attempt.longPromptInputTokens()).isZero();
        // The smallest model: each call above the long-prompt line at its rate.
        ClaudeApi.Usage haiku = ClaudeWebResearch.mostOfAttempt(ClaudeService.HAIKU, 90_000, 8_000);
        assertThat(haiku.longPromptInputTokens()).isEqualTo((90_000 + 16_000) + (90_000 + 24_000));
        assertThat(haiku.longPromptOutputTokens()).isEqualTo(2 * 8_000);
    }

    @Test
    void theSmallestModelHasNoFallbackAndIsPricedAtTheLongRateAboveTheLine() {
        // Haiku above the long-prompt line ($0.50 / $2.50): 150,000 in and 3,000 out, two attempts.
        ClaudeApi.Usage attempt = ClaudeCalls.priced(ClaudeService.HAIKU, new ClaudeApi.Usage(150_000, 3_000));
        assertThat(AiCost.maxOf(ClaudeService.HAIKU, attempt, 2, 0, null)).isEqualByComparingTo("0.165000");
    }

    @Test
    void theSearchesAreAddedAtTheirPrice() {
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(10_000, 4_000);
        // Ten searches an attempt at $0.01 each, two attempts: $0.20 on top of $0.42.
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 2, 10, null)).isEqualByComparingTo("0.620000");
        assertThat(AiCost.searches(25)).isEqualByComparingTo(ClaudeWebResearch.searchCost(25));
    }

    @Test
    void aModelQuizBuksDoesNotKnowIsPricedAtTheParametersAndAgainAtItsFallback() {
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(1_000_000, 0);
        AiCost.Price set = new AiCost.Price(new BigDecimal("8"), new BigDecimal("40"));
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, set)).isEqualByComparingTo("13.000000");
        AiCost.Price cheap = new AiCost.Price(new BigDecimal("1"), new BigDecimal("1"));
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, cheap)).isEqualByComparingTo("6.000000");
        // No price at all: nothing can be reserved.
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, null)).isNull();
    }

    /**
     * #692 review M1: the reservation is a strict bound. Whatever calls an attempt makes within its limits (each call's
     * input at most the reserved input and the output before it, each refused call run again whole on the fallback), and
     * however many of its attempts run, the dollars the job's usage comes to are never above {@link AiCost#maxOf} of
     * {@link ClaudeWebResearch#mostOfAttempt}.
     */
    @Property
    void whatAWebReadersJobUsesIsNeverAboveWhatItReserves(@ForAll("models") String model,
        @ForAll @IntRange(min = 1_000, max = 200_000) int reservedInput,
        @ForAll @IntRange(min = 100, max = 32_000) int output,
        @ForAll @Size(min = 1, max = 2) List<@Size(min = 1, max = 4) List<@From("fractions") List<Integer>>> attempts) {
        long in = 0;
        long out = 0;
        long fallbackIn = 0;
        long fallbackOut = 0;
        long longIn = 0;
        long longOut = 0;
        for (List<List<Integer>> attempt : attempts) {
            long before = 0;
            for (List<Integer> f : attempt) {
                // f: the share (per thousand) of the input, of the output, whether it fell back, of the fallback's output.
                long callIn = (reservedInput + before) * f.get(0) / 1_000;
                long callOut = (long) output * f.get(1) / 1_000;
                boolean fell = f.get(2) % 2 == 1 && !ClaudeService.HAIKU.equals(model);
                long fbOut = fell ? (long) output * f.get(3) / 1_000 : 0;
                ClaudeApi.Usage own = ClaudeCalls.priced(model, new ClaudeApi.Usage(callIn, callOut));
                in += callIn + (fell ? callIn : 0);
                out += callOut + fbOut;
                fallbackIn += fell ? callIn : 0;
                fallbackOut += fbOut;
                longIn += own.longPromptInputTokens();
                longOut += own.longPromptOutputTokens();
                before += fell ? fbOut : callOut;
            }
        }
        ClaudeApi.Usage used = new ClaudeApi.Usage(in, out, fallbackIn, fallbackOut, longIn, longOut);
        BigDecimal most = AiCost.maxOf(model, ClaudeWebResearch.mostOfAttempt(model, reservedInput, output),
            attempts.size(), 0, null);
        for (String served : new String[] {null, model, "claude-opus-5", "claude-opus-4-8"}) {
            assertThat(AiCost.of(model, served, used, null)).isLessThanOrEqualTo(most);
        }
    }

    /** A request's single call (the questions, a batch of translation): its attempt at most what it reserved. */
    @Property
    void whatAOneCallJobUsesIsNeverAboveWhatItReserves(@ForAll("models") String model,
        @ForAll @IntRange(min = 1_000, max = 200_000) int reservedInput,
        @ForAll @IntRange(min = 100, max = 32_000) int output,
        @ForAll @Size(min = 1, max = 3) List<@From("fractions") List<Integer>> attempts) {
        ClaudeApi.Usage attempt = ClaudeCalls.priced(model, ClaudeApi.unrecorded(reservedInput, output));
        BigDecimal most = AiCost.maxOf(model, attempt, attempts.size(), 0, null);
        BigDecimal used = BigDecimal.ZERO;
        for (List<Integer> f : attempts) {
            long callIn = (long) reservedInput * f.get(0) / 1_000;
            long callOut = (long) output * f.get(1) / 1_000;
            boolean fell = f.get(2) % 2 == 1 && !ClaudeService.HAIKU.equals(model);
            long fbOut = fell ? (long) output * f.get(3) / 1_000 : 0;
            ClaudeApi.Usage own = ClaudeCalls.priced(model, new ClaudeApi.Usage(callIn, callOut));
            used = used.add(AiCost.of(model, fell ? "claude-opus-5" : null, new ClaudeApi.Usage(
                callIn + (fell ? callIn : 0), callOut + fbOut, fell ? callIn : 0, fbOut,
                own.longPromptInputTokens(), own.longPromptOutputTokens()), null));
        }
        assertThat(used).isLessThanOrEqualTo(most);
    }

    @Provide
    Arbitrary<String> models() {
        return Arbitraries.of(ClaudeService.OPUS, ClaudeService.SONNET, ClaudeService.HAIKU);
    }

    @Provide
    Arbitrary<List<Integer>> fractions() {
        Arbitrary<Integer> share = Arbitraries.integers().between(0, 1_000);
        return Combinators.combine(share, share, Arbitraries.integers().between(0, 1), share).as(List::of);
    }
}
