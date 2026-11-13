package com.jabiz.quizbuks.content.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M9 (docs/quizbuks/plans/impl/F-M9.md §3, review M7): the most a request may cost, which it reserves. */
class AiCostTest {

    @Test
    void theMostIsEveryAttemptAtTheDearerOfTheModelAndItsFallback() {
        // Sonnet ($2 / $10) falls back to a model at $5 / $25: 10,000 in and 4,000 out an attempt cost at most
        // 50,000 + 100,000 millionths, three attempts $0.45.
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(10_000, 4_000);
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 3, 0, null)).isEqualByComparingTo("0.450000");
        // Its own price alone would be $0.06 an attempt: the reservation is never below what it may fall back to.
        assertThat(AiCost.of(ClaudeService.SONNET, null, attempt, null)).isEqualByComparingTo("0.060000");
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
        // Ten searches an attempt at $0.01 each, two attempts: $0.20 on top of $0.30.
        assertThat(AiCost.maxOf(ClaudeService.SONNET, attempt, 2, 10, null)).isEqualByComparingTo("0.500000");
        assertThat(AiCost.searches(25)).isEqualByComparingTo(ClaudeWebResearch.searchCost(25));
    }

    @Test
    void aModelQuizBuksDoesNotKnowIsPricedAtTheParametersOrItsFallbackWhicheverIsDearer() {
        ClaudeApi.Usage attempt = new ClaudeApi.Usage(1_000_000, 0);
        AiCost.Price set = new AiCost.Price(new BigDecimal("8"), new BigDecimal("40"));
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, set)).isEqualByComparingTo("8.000000");
        AiCost.Price cheap = new AiCost.Price(new BigDecimal("1"), new BigDecimal("1"));
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, cheap)).isEqualByComparingTo("5.000000");
        // No price at all: nothing can be reserved.
        assertThat(AiCost.maxOf("claude-new", attempt, 1, 0, null)).isNull();
    }
}
