package com.jabiz.quizbuks.audience;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a publication's audience rules cost (Q47c): days rounded up, the least fee, the lists and the fallback. */
class AudienceBillingTest {

    private static final Instant START = Instant.parse("2026-11-03T00:00:00Z");

    private static AudienceBilling.PriceList list(String owner, long minimum, Map<String, Long> prices) {
        Map<String, BigDecimal> daily = new java.util.HashMap<>();
        prices.forEach((key, price) -> daily.put(key, BigDecimal.valueOf(price)));
        return new AudienceBilling.PriceList(owner, BigDecimal.valueOf(minimum), daily);
    }

    @Test
    void daysAreRoundedUpToWholeDaysAndAtLeastOne() {
        assertThat(AudienceBilling.days(START, START.plus(Duration.ofDays(7)))).isEqualTo(7);
        assertThat(AudienceBilling.days(START, START.plus(Duration.ofDays(7)).plusSeconds(1))).isEqualTo(8);
        assertThat(AudienceBilling.days(START, START.plusSeconds(60))).isEqualTo(1);
        assertThat(AudienceBilling.days(START, START)).isEqualTo(1);
        assertThat(AudienceBilling.days(null, START)).isEqualTo(1);
    }

    @Test
    void theFeeIsTheDailyPricesTimesTheDaysAndAtLeastTheLeastFee() {
        AudienceBilling.PriceList list = list("direct", 1000, Map.of("gender", 100L, "age", 200L));
        Instant end = START.plus(Duration.ofDays(7));
        // A rule counts once however many of its options are chosen.
        AudienceBilling.Quote quote = AudienceBilling.quote(true, List.of("gender", "gender", "age"), Map.of(), list,
            START, end);
        assertThat(quote.days()).isEqualTo(7);
        assertThat(quote.items()).extracting(AudienceBilling.Item::ruleKey).containsExactly("gender", "age");
        assertThat(quote.subtotal()).isEqualByComparingTo("2100");
        assertThat(quote.fee()).isEqualByComparingTo("2100");
        // Below the least fee: the least fee.
        assertThat(AudienceBilling.quote(true, List.of("gender"), Map.of(), list, START, START.plusSeconds(3600))
            .fee()).isEqualByComparingTo("1000");
        // A charged rule without a price costs 0, but the least fee still applies.
        assertThat(AudienceBilling.quote(true, List.of("interest"), Map.of(), list, START, end).fee())
            .isEqualByComparingTo("1000");
        // No charged rule: nothing, not even the least fee.
        assertThat(AudienceBilling.quote(true, List.of(), Map.of(), list, START, end).fee()).isZero();
        assertThat(AudienceBilling.quote(true, List.of("gender"),
            Map.of("gender", new AudienceBilling.Rule(true, false)), list, START, end).fee()).isZero();
        // Switched off, or no list: free.
        assertThat(AudienceBilling.quote(false, List.of("gender"), Map.of(), list, START, end).fee()).isZero();
        assertThat(AudienceBilling.quote(true, List.of("gender"), Map.of(), null, START, end))
            .satisfies(q -> {
                assertThat(q.fee()).isZero();
                assertThat(q.owner()).isNull();
            });
    }

    @Test
    void theOperatorsListAppliesElseTheFallback() {
        AudienceBilling.PriceList direct = list("direct", 0, Map.of());
        AudienceBilling.PriceList north = list("north", 0, Map.of());
        assertThat(AudienceBilling.applicable(null, null, direct, AudienceBilling.FALLBACK_FREE)).isSameAs(direct);
        assertThat(AudienceBilling.applicable("north", north, direct, AudienceBilling.FALLBACK_FREE)).isSameAs(north);
        assertThat(AudienceBilling.applicable("north", null, direct, AudienceBilling.FALLBACK_DIRECT))
            .isSameAs(direct);
        assertThat(AudienceBilling.applicable("north", null, direct, AudienceBilling.FALLBACK_FREE)).isNull();
    }

    @Test
    void closedRulesAreFound() {
        assertThat(AudienceBilling.closed(List.of("gender", "age", "gender"),
            Map.of("gender", new AudienceBilling.Rule(false, true)))).containsExactly("gender");
        assertThat(AudienceBilling.closed(List.of("age"), Map.of())).isEmpty();
    }
}
