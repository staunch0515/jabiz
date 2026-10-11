package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Whether the reward tiers of a publication cover every score from 0 to the full score exactly once
 * (docs/quizbuks/plans/Q4-publishing.md, D-Q4-3). Scores are whole numbers, so tiers meet when the next one starts one
 * point after the previous one ends. Every problem is reported at once, with the path of the tier as the caller gave
 * the tiers ({@code tiers[2].fromScore}, indexes from 0) and the tier numbers people see (from 1).
 */
public final class TierCoverage {

    /** One tier: a score range, both ends included, and its reward in Kudos (JPY, no decimals). */
    public record Tier(int fromScore, int toScore, BigDecimal reward) {
        public Tier {
            Objects.requireNonNull(reward, "reward must not be null");
        }
    }

    /**
     * @param fullScore the version's full score; null without a version (then the top is not checked: the missing
     *                  version is reported on its own)
     */
    public static List<Violation> check(List<Tier> tiers, Integer fullScore) {
        List<Violation> problems = new ArrayList<>();
        if (tiers.isEmpty()) {
            problems.add(new Violation("tiers", PublishingCodes.TIER_NO_REWARD, "A publication needs a tier with a "
                + "reward", Map.of()));
            return List.copyOf(problems);
        }
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            if (tier.reward().signum() < 0) {
                problems.add(new Violation(path(i, "reward"), PublishingCodes.TIER_REWARD_RANGE, "Tier " + (i + 1)
                    + " has a negative reward", Map.of("tier", i + 1, "min", 0)));
            }
            if (tier.fromScore() > tier.toScore()) {
                problems.add(new Violation(path(i, "toScore"), PublishingCodes.TIER_RANGE, "Tier " + (i + 1)
                    + " ends at " + tier.toScore() + ", before its start " + tier.fromScore(),
                    Map.of("tier", i + 1, "fromScore", tier.fromScore(), "toScore", tier.toScore())));
            }
        }
        List<Integer> order = IntStream.range(0, tiers.size()).boxed()
            .sorted(Comparator.<Integer>comparingInt(i -> tiers.get(i).fromScore())
                .thenComparingInt(i -> tiers.get(i).toScore()))
            .toList();
        int first = order.getFirst();
        if (tiers.get(first).fromScore() != 0) {
            problems.add(new Violation(path(first, "fromScore"), PublishingCodes.TIER_NOT_FROM_ZERO, "The lowest "
                + "tier starts at " + tiers.get(first).fromScore() + ", not 0",
                Map.of("tier", first + 1, "fromScore", tiers.get(first).fromScore())));
        }
        for (int k = 1; k < order.size(); k++) {
            int previous = order.get(k - 1);
            int next = order.get(k);
            long expected = (long) tiers.get(previous).toScore() + 1;
            int start = tiers.get(next).fromScore();
            if (start > expected) {
                problems.add(new Violation(path(next, "fromScore"), PublishingCodes.TIER_GAP, "No tier covers the "
                    + "scores " + expected + " to " + (start - 1), Map.of("tier", next + 1, "fromScore", expected,
                    "toScore", start - 1)));
            } else if (start < expected) {
                problems.add(new Violation(path(next, "fromScore"), PublishingCodes.TIER_OVERLAP, "Tiers "
                    + (previous + 1) + " and " + (next + 1) + " both cover score " + start,
                    Map.of("tier", next + 1, "other", previous + 1, "score", start)));
            }
        }
        int last = order.getLast();
        if (fullScore != null && tiers.get(last).toScore() != fullScore) {
            problems.add(new Violation(path(last, "toScore"), PublishingCodes.TIER_NOT_TO_FULL, "The highest tier "
                + "ends at " + tiers.get(last).toScore() + ", not at the full score " + fullScore,
                Map.of("tier", last + 1, "toScore", tiers.get(last).toScore(), "fullScore", fullScore)));
        }
        if (tiers.stream().noneMatch(tier -> tier.reward().signum() > 0)) {
            problems.add(new Violation("tiers", PublishingCodes.TIER_NO_REWARD, "No tier has a reward", Map.of()));
        }
        return List.copyOf(problems);
    }

    /** The highest reward of the tiers; null without tiers. */
    public static BigDecimal maxReward(List<Tier> tiers) {
        return tiers.stream().map(Tier::reward).max(Comparator.naturalOrder()).orElse(null);
    }

    /**
     * The most the publication can pay out (D-Q4-3): its budget, or its winners times the highest reward, whichever
     * is smaller; the one given when only one is; null (no cap) when neither is.
     */
    public static BigDecimal maxSpend(BigDecimal budgetCap, Integer maxWinners, BigDecimal maxReward) {
        BigDecimal byWinners = maxWinners == null || maxReward == null ? null
            : maxReward.multiply(BigDecimal.valueOf(maxWinners));
        if (budgetCap == null) {
            return byWinners;
        }
        return byWinners == null ? budgetCap : budgetCap.min(byWinners);
    }

    private static String path(int index, String field) {
        return "tiers[" + index + "]." + field;
    }

    private TierCoverage() {}
}
