package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.CardTiers;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link TierPick} and {@link RewardSplit}. */
class RewardRulesTest {

    static final List<CardTiers.Tier> TIERS = List.of(new CardTiers.Tier("t1", 0, 2, BigDecimal.ZERO),
        new CardTiers.Tier("t2", 3, 4, BigDecimal.TEN), new CardTiers.Tier("t3", 5, 5, BigDecimal.valueOf(100)));

    @Test
    void theTierHoldingTheScoreBothEndsIncluded() {
        assertThat(TierPick.of(TIERS, 0)).map(CardTiers.Tier::tierId).hasValue("t1");
        assertThat(TierPick.of(TIERS, 2)).map(CardTiers.Tier::tierId).hasValue("t1");
        assertThat(TierPick.of(TIERS, 3)).map(CardTiers.Tier::tierId).hasValue("t2");
        assertThat(TierPick.of(TIERS, 4)).map(CardTiers.Tier::tierId).hasValue("t2");
        assertThat(TierPick.of(TIERS, 5)).map(CardTiers.Tier::tierId).hasValue("t3");
        assertThat(TierPick.of(TIERS, 6)).isEmpty();
    }

    @Test
    void cardTiersRoundTripThroughTheirJson() {
        String json = CardTiers.toJson(TIERS);
        assertThat(json).startsWith("[{\"fromScore\":0,\"reward\":\"0\",\"tierId\":\"t1\",\"toScore\":2}");
        assertThat(CardTiers.fromJson(json)).isEqualTo(TIERS);
        assertThat(CardTiers.fromJson(null)).isEmpty();
    }

    @Test
    void theCommissionIsRoundedDownAndTheRestGoesToTheTaker() {
        assertThat(RewardSplit.of(BigDecimal.valueOf(100), BigDecimal.ZERO))
            .isEqualTo(new RewardSplit.Split(BigDecimal.ZERO, BigDecimal.valueOf(100)));
        assertThat(RewardSplit.of(BigDecimal.valueOf(100), new BigDecimal("0.1")))
            .isEqualTo(new RewardSplit.Split(BigDecimal.TEN, BigDecimal.valueOf(90)));
        assertThat(RewardSplit.of(BigDecimal.valueOf(99), new BigDecimal("0.1")))
            .isEqualTo(new RewardSplit.Split(BigDecimal.valueOf(9), BigDecimal.valueOf(90)));
        assertThat(RewardSplit.of(BigDecimal.valueOf(10), new BigDecimal("0.3333")))
            .isEqualTo(new RewardSplit.Split(BigDecimal.valueOf(3), BigDecimal.valueOf(7)));
        assertThat(RewardSplit.of(BigDecimal.valueOf(2), new BigDecimal("0.3333")))
            .isEqualTo(new RewardSplit.Split(BigDecimal.ZERO, BigDecimal.valueOf(2)));
        assertThat(RewardSplit.of(BigDecimal.valueOf(7), null).fee()).isZero();
        assertThat(RewardSplit.of(BigDecimal.valueOf(7), new BigDecimal("0.9999")).net()).isEqualTo(BigDecimal.ONE);
    }

    @Test
    void refusesARateOutsideZeroToBelowOne() {
        assertThat(RewardSplit.validRate(null)).isTrue();
        assertThat(RewardSplit.validRate(BigDecimal.ZERO)).isTrue();
        assertThat(RewardSplit.validRate(new BigDecimal("0.9999"))).isTrue();
        assertThat(RewardSplit.validRate(BigDecimal.ONE)).isFalse();
        assertThat(RewardSplit.validRate(new BigDecimal("-0.0001"))).isFalse();
        assertThatThrownBy(() -> RewardSplit.of(BigDecimal.TEN, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RewardSplit.of(BigDecimal.TEN, new BigDecimal("1.5")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RewardSplit.of(BigDecimal.TEN, new BigDecimal("-0.1")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RewardSplit.of(BigDecimal.TEN.negate(), BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Property(tries = 500)
    void commissionAndTheTakersPartAddUpToTheReward(@ForAll @LongRange(min = 0, max = 1_000_000_000L) long reward,
        @ForAll @IntRange(min = 0, max = 9_999) int basisPoints) {
        BigDecimal rate = BigDecimal.valueOf(basisPoints, 4);
        RewardSplit.Split split = RewardSplit.of(BigDecimal.valueOf(reward), rate);
        assertThat(split.fee().add(split.net())).isEqualByComparingTo(BigDecimal.valueOf(reward));
        assertThat(split.fee().scale()).isZero();
        assertThat(split.fee().signum()).isGreaterThanOrEqualTo(0);
        assertThat(split.net().signum()).isGreaterThanOrEqualTo(0);
        // Rounded down: less than one Kudo below the exact commission.
        BigDecimal exact = BigDecimal.valueOf(reward).multiply(rate);
        assertThat(exact.subtract(split.fee())).isGreaterThanOrEqualTo(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
    }
}
