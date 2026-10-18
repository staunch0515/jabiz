package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NewSponsorLimitTest {

    static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    static final BigDecimal DAYS = BigDecimal.valueOf(30);
    static final BigDecimal MAX = BigDecimal.valueOf(100_000);

    @Test
    void beforeTheFirstPaidTopUpTheLimitApplies() {
        NewSponsorLimit.Standing standing = NewSponsorLimit.standing(DAYS, MAX, null, BigDecimal.ZERO, NOW);
        assertThat(standing.limited()).isTrue();
        assertThat(standing.until()).isNull();
        assertThat(standing.remaining()).isEqualByComparingTo("100000");
        assertThat(NewSponsorLimit.refusal(standing, BigDecimal.valueOf(100_000))).isNull();
        Violation refused = NewSponsorLimit.refusal(standing, BigDecimal.valueOf(100_001));
        assertThat(refused.ruleCode()).isEqualTo(WalletCodes.TOPUP_NEW_SPONSOR_LIMIT);
        assertThat(refused.params()).containsEntry("remaining", "100000").containsEntry("until", "-");
    }

    @Test
    void withinThePeriodWhatIsUsedCounts() {
        Instant first = NOW.minus(Duration.ofDays(10));
        NewSponsorLimit.Standing standing = NewSponsorLimit.standing(DAYS, MAX, first, BigDecimal.valueOf(70_000), NOW);
        assertThat(standing.remaining()).isEqualByComparingTo("30000");
        assertThat(standing.until()).isEqualTo(first.plus(Duration.ofDays(30)));
        assertThat(NewSponsorLimit.refusal(standing, BigDecimal.valueOf(30_001)).params())
            .containsEntry("until", first.plus(Duration.ofDays(30)).toString());
        // Used beyond the limit (the limit lowered meanwhile): nothing remains, never below 0.
        assertThat(NewSponsorLimit.standing(DAYS, MAX, first, BigDecimal.valueOf(150_000), NOW).remaining())
            .isEqualByComparingTo("0");
    }

    @Test
    void afterThePeriodNoLimit() {
        Instant first = NOW.minus(Duration.ofDays(30));
        NewSponsorLimit.Standing standing = NewSponsorLimit.standing(DAYS, MAX, first, BigDecimal.valueOf(900_000),
            NOW);
        assertThat(standing.limited()).isFalse();
        assertThat(NewSponsorLimit.refusal(standing, BigDecimal.valueOf(1_000_000))).isNull();
        assertThat(NewSponsorLimit.standing(DAYS, MAX, first.plusSeconds(1), BigDecimal.ZERO, NOW).limited()).isTrue();
    }

    @Test
    void unusableParametersRefuseEverything() {
        assertThat(NewSponsorLimit.standing(null, MAX, null, BigDecimal.ZERO, NOW)).isNull();
        assertThat(NewSponsorLimit.standing(DAYS, null, null, BigDecimal.ZERO, NOW)).isNull();
        assertThat(NewSponsorLimit.standing(BigDecimal.valueOf(-1), MAX, null, BigDecimal.ZERO, NOW)).isNull();
        assertThat(NewSponsorLimit.standing(new BigDecimal("1.5"), MAX, null, BigDecimal.ZERO, NOW)).isNull();
    }
}
