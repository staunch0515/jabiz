package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class TierCoverageTest {

    private static TierCoverage.Tier tier(int from, int to, long reward) {
        return new TierCoverage.Tier(from, to, BigDecimal.valueOf(reward));
    }

    private static List<String> codes(List<TierCoverage.Tier> tiers, Integer fullScore) {
        return TierCoverage.check(tiers, fullScore).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void tiersFromZeroToTheFullScoreWithoutGapsPass() {
        assertThat(TierCoverage.check(List.of(tier(0, 5, 0), tier(6, 8, 100), tier(9, 10, 300)), 10)).isEmpty();
        // In any order, and a single tier covering everything.
        assertThat(TierCoverage.check(List.of(tier(9, 10, 300), tier(0, 8, 0)), 10)).isEmpty();
        assertThat(TierCoverage.check(List.of(tier(0, 10, 50)), 10)).isEmpty();
        // A tier of a single score.
        assertThat(TierCoverage.check(List.of(tier(0, 9, 0), tier(10, 10, 1)), 10)).isEmpty();
    }

    @Test
    void eachRuleIsReported() {
        assertThat(codes(List.of(), 10)).containsExactly(PublishingCodes.TIER_NO_REWARD);
        assertThat(codes(List.of(tier(1, 10, 5)), 10)).containsExactly(PublishingCodes.TIER_NOT_FROM_ZERO);
        assertThat(codes(List.of(tier(0, 9, 5)), 10)).containsExactly(PublishingCodes.TIER_NOT_TO_FULL);
        assertThat(codes(List.of(tier(0, 11, 5)), 10)).containsExactly(PublishingCodes.TIER_NOT_TO_FULL);
        assertThat(codes(List.of(tier(0, 4, 0), tier(6, 10, 5)), 10)).containsExactly(PublishingCodes.TIER_GAP);
        assertThat(codes(List.of(tier(0, 5, 0), tier(5, 10, 5)), 10)).containsExactly(PublishingCodes.TIER_OVERLAP);
        assertThat(codes(List.of(tier(0, 10, 0)), 10)).containsExactly(PublishingCodes.TIER_NO_REWARD);
        assertThat(codes(List.of(tier(0, 10, -1)), 10))
            .containsExactly(PublishingCodes.TIER_REWARD_RANGE, PublishingCodes.TIER_NO_REWARD);
        assertThat(codes(List.of(tier(0, 6, 0), tier(7, 5, 3), tier(6, 10, 5)), 10))
            .contains(PublishingCodes.TIER_RANGE);
    }

    @Test
    void allProblemsAtOnceWithThePathsOfTheGivenTiers() {
        List<Violation> problems = TierCoverage.check(List.of(tier(8, 12, 0), tier(2, 5, 0)), 10);
        assertThat(problems).extracting(Violation::field, Violation::ruleCode).containsExactly(
            tuple("tiers[1].fromScore", PublishingCodes.TIER_NOT_FROM_ZERO),
            tuple("tiers[0].fromScore", PublishingCodes.TIER_GAP),
            tuple("tiers[0].toScore", PublishingCodes.TIER_NOT_TO_FULL),
            tuple("tiers", PublishingCodes.TIER_NO_REWARD));
        assertThat(problems.get(1).params()).containsEntry("fromScore", 6L).containsEntry("toScore", 7);
    }

    @Test
    void withoutAVersionTheTopIsNotChecked() {
        assertThat(TierCoverage.check(List.of(tier(0, 3, 10)), null)).isEmpty();
    }

    @Test
    void maxRewardAndMaxSpend() {
        List<TierCoverage.Tier> tiers = List.of(tier(0, 5, 0), tier(6, 10, 300), tier(11, 12, 200));
        assertThat(TierCoverage.maxReward(tiers)).isEqualByComparingTo("300");
        assertThat(TierCoverage.maxReward(List.of())).isNull();
        BigDecimal top = BigDecimal.valueOf(300);
        assertThat(TierCoverage.maxSpend(null, null, top)).isNull();
        assertThat(TierCoverage.maxSpend(BigDecimal.valueOf(5000), null, top)).isEqualByComparingTo("5000");
        assertThat(TierCoverage.maxSpend(null, 10, top)).isEqualByComparingTo("3000");
        assertThat(TierCoverage.maxSpend(BigDecimal.valueOf(5000), 10, top)).isEqualByComparingTo("3000");
        assertThat(TierCoverage.maxSpend(BigDecimal.valueOf(2000), 10, top)).isEqualByComparingTo("2000");
        assertThat(TierCoverage.maxSpend(null, 10, null)).isNull();
    }

    /** The tiers pass exactly when an independent count finds every score from 0 to full covered once. */
    @Property(tries = 500)
    void agreesWithCountingEveryScore(@ForAll("tiers") List<TierCoverage.Tier> tiers,
        @ForAll("fullScores") int fullScore) {
        assertThat(TierCoverage.check(tiers, fullScore).isEmpty()).as(tiers + " up to " + fullScore)
            .isEqualTo(coversOnce(tiers, fullScore));
    }

    private static boolean coversOnce(List<TierCoverage.Tier> tiers, int fullScore) {
        if (tiers.stream().noneMatch(t -> t.reward().signum() > 0)
            || tiers.stream().anyMatch(t -> t.reward().signum() < 0 || t.fromScore() > t.toScore()
            || t.fromScore() < 0 || t.toScore() > fullScore)) {
            return false;
        }
        int[] covered = new int[fullScore + 1];
        for (TierCoverage.Tier t : tiers) {
            for (int s = t.fromScore(); s <= t.toScore(); s++) {
                covered[s]++;
            }
        }
        for (int count : covered) {
            if (count != 1) {
                return false;
            }
        }
        return true;
    }

    @Provide
    Arbitrary<Integer> fullScores() {
        return Arbitraries.integers().between(0, 8);
    }

    @Provide
    Arbitrary<List<TierCoverage.Tier>> tiers() {
        Arbitrary<TierCoverage.Tier> tier = Combinators.combine(Arbitraries.integers().between(-1, 9),
                Arbitraries.integers().between(-1, 9), Arbitraries.longs().between(-1, 3))
            .as((from, to, reward) -> tier(from, to, reward));
        // Valid partitions are rare at random: build some from cut points as well.
        Arbitrary<List<TierCoverage.Tier>> partitions = Arbitraries.integers().between(0, 8).list().ofMaxSize(4)
            .uniqueElements().map(cuts -> {
                List<Integer> sorted = cuts.stream().sorted().toList();
                java.util.ArrayList<TierCoverage.Tier> list = new java.util.ArrayList<>();
                int from = 0;
                for (int cut : sorted) {
                    if (cut >= from) {
                        list.add(tier(from, cut, list.size()));
                        from = cut + 1;
                    }
                }
                return List.copyOf(list);
            });
        return Arbitraries.oneOf(tier.list().ofMaxSize(4), partitions);
    }
}
