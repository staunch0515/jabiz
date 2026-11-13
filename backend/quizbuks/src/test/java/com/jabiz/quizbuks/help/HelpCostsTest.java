package com.jabiz.quizbuks.help;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.content.ai.AiCost;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.content.ai.ClaudeService;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M10: the estimate with the cache-read discount, the dollar alert and the month's window. */
class HelpCostsTest {

    private static BigDecimal usd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void aCacheReadCostsATenthOfTheInputPriceSoTheDiscountIsNineTenths() {
        // Per million tokens: Haiku 0.10, Sonnet 2, Opus 4 at the input price.
        assertThat(AiCost.cacheReadDiscount(ClaudeService.HAIKU, 1_000_000)).isEqualByComparingTo(usd("0.09"));
        assertThat(AiCost.cacheReadDiscount(ClaudeService.SONNET, 1_000_000)).isEqualByComparingTo(usd("1.8"));
        assertThat(AiCost.cacheReadDiscount(ClaudeService.OPUS, 1_000_000)).isEqualByComparingTo(usd("3.6"));
        assertThat(AiCost.cacheReadDiscount(ClaudeService.HAIKU, 0)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(AiCost.cacheReadDiscount(ClaudeService.HAIKU, -5)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(AiCost.cacheReadDiscount("a-model-nobody-knows", 100)).isNull();
        assertThat(AiCost.cacheReadDiscount(null, 100)).isNull();
    }

    @Test
    void theCostOfAJobTakesTheCacheReadsDiscountOffAndKeepsTheWritePremium() {
        // 60,000 input tokens, all read from the cache, 500 out; Haiku: list 0.10 / 0.50 per million.
        ClaudeApi.Usage reads = new ClaudeApi.Usage(60_000, 500).withCache(0, 60_000);
        BigDecimal listPrice = AiCost.of(ClaudeService.HAIKU, null, reads, null);
        BigDecimal cost = HelpOutcomes.cost(ClaudeService.HAIKU, null, reads);
        // The reads cost a tenth of the list price: 6,000 tokens' worth plus the output.
        assertThat(cost).isEqualByComparingTo(usd("0.00085"));
        assertThat(cost).isLessThan(listPrice);

        // Cache writes cost a quarter more than the list price they are counted at; no reads: no discount.
        ClaudeApi.Usage writes = new ClaudeApi.Usage(1_000_000, 0).withCache(1_000_000, 0);
        assertThat(HelpOutcomes.cost(ClaudeService.HAIKU, null, writes)).isEqualByComparingTo(usd("0.125"));
    }

    @Test
    void theCostNeverGoesBelowZeroAndAnUnknownModelHasNone() {
        // More reads than input tokens are clamped to the input tokens (as the monthly count does).
        ClaudeApi.Usage odd = new ClaudeApi.Usage(100, 0).withCache(0, 5_000_000);
        assertThat(HelpOutcomes.cost(ClaudeService.HAIKU, null, odd)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(HelpOutcomes.cost("a-model-nobody-knows", null, new ClaudeApi.Usage(10, 10))).isNull();
        assertThat(HelpOutcomes.cost(null, null, new ClaudeApi.Usage(10, 10))).isNull();
        assertThat(HelpOutcomes.cost(ClaudeService.HAIKU, null, new ClaudeApi.Usage(0, 0)))
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theDollarsAsAShareOfTheAlertAreWholePercentRoundedDownAtMostAThousand() {
        assertThat(HelpCosts.percent(usd("4"), usd("8"))).isEqualTo(50);
        assertThat(HelpCosts.percent(usd("7.99"), usd("8"))).isEqualTo(99);
        assertThat(HelpCosts.percent(usd("8"), usd("8"))).isEqualTo(100);
        assertThat(HelpCosts.percent(usd("80000"), usd("8"))).isEqualTo(1_000);
        // No alert (0): no share, however much was spent; nothing spent: 0.
        assertThat(HelpCosts.percent(usd("50"), BigDecimal.ZERO)).isZero();
        assertThat(HelpCosts.percent(BigDecimal.ZERO, usd("8"))).isZero();
        assertThat(HelpCosts.percent(null, usd("8"))).isZero();
    }

    @Test
    void aMonthReachesTheAlertWhenTheHelpAndTheChecksTogetherDoAndNeverWithoutAnAlert() {
        HelpCosts.Month month = HelpCosts.Month.of(Map.of("usageUsd", "5.5", "checksUsd", "2.5", "alertUsd", "8",
            "taskExists", 0));
        assertThat(month.total()).isEqualByComparingTo(usd("8"));
        assertThat(month.reached()).isTrue();
        assertThat(month.taskExists()).isFalse();
        assertThat(HelpCosts.Month.of(Map.of("usageUsd", "5.5", "checksUsd", "2.49", "alertUsd", "8")).reached())
            .isFalse();
        // The alert 0 is "no alert".
        assertThat(HelpCosts.Month.of(Map.of("usageUsd", "500", "checksUsd", "0", "alertUsd", "0")).reached()).isFalse();
        assertThat(HelpCosts.Month.of(Map.of("usageUsd", "1", "checksUsd", "0", "alertUsd", "8", "taskExists", 1))
            .taskExists()).isTrue();
        assertThat(HelpCosts.Month.of(null).reached()).isFalse();
    }

    @Test
    void theMonthsWindowIsTheDeploymentsZoneAndTheTaskIsNamedByTheMonth() {
        Map<String, Object> window = HelpCosts.params("2026-10", ZoneId.of("Asia/Tokyo"));
        assertThat(window.get("from")).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(window.get("to")).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        assertThat(HelpCosts.before("2026-01")).isEqualTo("2025-12");
        assertThat(HelpCosts.source("2026-10")).isEqualTo("qb.help-cost:2026-10");
    }

    @Test
    void theDailyQuestionsAreControlledAndTheAlertIsNot() {
        assertThat(QbParams.CONTROLLED).contains(QbParams.HELP_USER_DAILY).doesNotContain(
            QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT);
        // The last of the controlled parameters: the proposals before it keep their places.
        assertThat(QbParams.CONTROLLED.getLast()).isEqualTo(QbParams.HELP_USER_DAILY);
        assertThat(QbParams.HELP).contains(QbParams.HELP_GLOBAL_MONTHLY_USD_ALERT);
    }
}
