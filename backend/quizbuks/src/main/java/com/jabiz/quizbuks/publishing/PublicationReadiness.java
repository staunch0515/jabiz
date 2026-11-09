package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.QuizCompleteness;
import com.jabiz.quizbuks.content.QuizSnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a draft publication may be submitted (M-31 – M-33; docs/quizbuks/plans/Q4-publishing.md, D-Q4-6). A draft
 * may be incomplete while it is edited; a submitted one may not. Every problem is reported at once, with the path of
 * the field; the content rules of the version (Q3's {@link QuizCompleteness}) are checked again on its snapshot,
 * under the path {@code version.}, so that a version made before a rule became stricter is stopped here.
 */
public final class PublicationReadiness {

    /** The policy of a publication as it stands; any part may be missing in a draft. */
    public record Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
        Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
        String redeemDescription, String audience, String digitalDelivery, String digitalDescription,
        boolean hasDigitalContent, ReferralPolicy referral, Integer pickCount, BigDecimal entryFee,
        Map<String, Integer> pickByDifficulty) {

        public Draft {
            pickByDifficulty = pickByDifficulty == null ? Map.of() : Map.copyOf(pickByDifficulty);
        }

        /** As before F-S7: no draw by difficulty. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription, String audience, String digitalDelivery, String digitalDescription,
            boolean hasDigitalContent, ReferralPolicy referral, Integer pickCount, BigDecimal entryFee) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, audience, digitalDelivery, digitalDescription, hasDigitalContent,
                referral, pickCount, entryFee, Map.of());
        }

        /** As before Q88b: no entry fee. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription, String audience, String digitalDelivery, String digitalDescription,
            boolean hasDigitalContent, ReferralPolicy referral, Integer pickCount) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, audience, digitalDelivery, digitalDescription, hasDigitalContent,
                referral, pickCount, null);
        }

        /** As before Q81: every question of the version is asked. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription, String audience, String digitalDelivery, String digitalDescription,
            boolean hasDigitalContent, ReferralPolicy referral) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, audience, digitalDelivery, digitalDescription, hasDigitalContent,
                referral, null, null);
        }

        /** Q88b: whether the publication charges an entry fee. */
        public boolean chargesEntryFee() {
            return entryFee != null && entryFee.signum() > 0;
        }

        /** As before Q70: no referrals. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription, String audience, String digitalDelivery, String digitalDescription,
            boolean hasDigitalContent) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, audience, digitalDelivery, digitalDescription, hasDigitalContent, null);
        }

        /**
         * The codes a pool must hold (Q33, Q70 D-Q70-19): the winners, and the referral quota when its digital
         * prizes come from the same pool; zero when the prize is not pooled.
         */
        public int poolNeeded() {
            int needed = pooled() && maxWinners != null ? maxWinners : 0;
            if (pooled() && referral != null && RewardMethod.DIGITAL_PRIZE.equals(referral.method())) {
                needed += referral.quota();
            }
            return needed;
        }

        /** As before Q33: no digital prize. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription, String audience) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, audience, null, null, false);
        }

        /** Whether the prize is digital and delivered from a pool (Q33): the pool must cover the winners. */
        public boolean pooled() {
            return RewardMethod.DIGITAL_PRIZE.equals(rewardMethod) && DigitalDelivery.POOL.equals(digitalDelivery);
        }

        /** A draft paid by the platform (Q22): what every draft was before. */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                RewardMethod.PLATFORM, null, Audience.PUBLIC);
        }

        /** A draft for everybody in its regions (what every draft was before Q25). */
        public Draft(UUID versionId, String name, String regions, Instant startAt, Instant endAt,
            Integer timeLimitSec, Integer passScore, BigDecimal budgetCap, Integer maxWinners, String rewardMethod,
            String redeemDescription) {
            this(versionId, name, regions, startAt, endAt, timeLimitSec, passScore, budgetCap, maxWinners,
                rewardMethod, redeemDescription, Audience.PUBLIC);
        }

        /** Whether only the invited answer (Q25): then the regions are not asked. */
        public boolean invited() {
            return Audience.invited(audience);
        }
    }

    /**
     * @param version the snapshot of the chosen version; null without one
     * @param now     the operation time: a publication whose end has passed cannot be submitted
     */
    public static List<Violation> check(Draft draft, List<TierCoverage.Tier> tiers, QuizSnapshot version,
        Instant now) {
        return check(draft, tiers, version, now, null);
    }

    /**
     * As {@link #check(Draft, List, QuizSnapshot, Instant)}, with the highest reward one attempt may win (Q28, the
     * parameter {@code qb.reward.max-per-attempt}; null for no cap): a tier paying more is a problem of that tier.
     */
    public static List<Violation> check(Draft draft, List<TierCoverage.Tier> tiers, QuizSnapshot version,
        Instant now, BigDecimal maxRewardPerAttempt) {
        return check(draft, tiers, version, now, maxRewardPerAttempt, false);
    }

    /**
     * As {@link #check(Draft, List, QuizSnapshot, Instant, BigDecimal)}, with whether the publication is bound to a
     * node (F-S2b1): then its tiers may all pay 0 (passing lights the node).
     */
    public static List<Violation> check(Draft draft, List<TierCoverage.Tier> tiers, QuizSnapshot version,
        Instant now, BigDecimal maxRewardPerAttempt, boolean round) {
        List<Violation> problems = new ArrayList<>();
        // Q81 (D-Q81-2): a pick's full score is k times the common points; a pick that cannot stand (reported below)
        // leaves the version's.
        Integer fullScore = version == null ? null : fullScore(version, draft.pickCount());
        problems.addAll(pickProblems(draft.pickCount(), version));
        // F-S7: a draw by difficulty, each level from its own questions.
        if (problems.isEmpty()) {
            problems.addAll(difficultyProblems(draft.pickByDifficulty(), version));
        }
        // Q88b (D-Q88-9): only a quiz charges an entry fee (a survey pays people to answer).
        if (draft.chargesEntryFee() && version != null && version.survey()) {
            problems.add(new Violation("entryFee", PublishingCodes.ENTRY_FEE_NOT_ALLOWED, "A survey charges no "
                + "entry fee", Map.of()));
        }
        if (draft.versionId() == null || version == null) {
            problems.add(new Violation("versionId", PublishingCodes.NO_VERSION, "Choose a version of a quiz",
                Map.of()));
        }
        if (draft.name() == null || draft.name().isBlank()) {
            problems.add(required("name"));
        }
        if (!draft.invited() && (draft.regions() == null || draft.regions().isBlank())) {
            problems.add(required("regions"));
        }
        if (draft.startAt() == null) {
            problems.add(required("startAt"));
        }
        if (draft.endAt() == null) {
            problems.add(required("endAt"));
        } else if (!draft.endAt().isAfter(now)) {
            problems.add(new Violation("endAt", PublishingCodes.END_PASSED, "The end time has passed",
                Map.of("endAt", draft.endAt().toString())));
        }
        if (draft.startAt() != null && draft.endAt() != null) {
            if (!draft.startAt().isBefore(draft.endAt())) {
                problems.add(new Violation("endAt", PublishingCodes.TIME_ORDER, "The end must come after the start",
                    Map.of()));
            } else if (Duration.between(draft.startAt(), draft.endAt()).compareTo(PublishingLimits.MAX_RUNNING) > 0) {
                problems.add(new Violation("endAt", PublishingCodes.TOO_LONG, "A publication runs at most "
                    + PublishingLimits.MAX_RUNNING.toDays() + " days",
                    Map.of("days", PublishingLimits.MAX_RUNNING.toDays())));
            }
        }
        Integer limit = draft.timeLimitSec();
        if (limit == null) {
            problems.add(required("timeLimitSec"));
        } else if (limit < PublishingLimits.MIN_TIME_LIMIT_SEC || limit > PublishingLimits.MAX_TIME_LIMIT_SEC) {
            problems.add(new Violation("timeLimitSec", PublishingCodes.TIME_LIMIT_RANGE, "The time limit must be "
                + "between " + PublishingLimits.MIN_TIME_LIMIT_SEC + " and " + PublishingLimits.MAX_TIME_LIMIT_SEC
                + " seconds", Map.of("min", PublishingLimits.MIN_TIME_LIMIT_SEC,
                "max", PublishingLimits.MAX_TIME_LIMIT_SEC)));
        }
        if (draft.passScore() == null) {
            problems.add(required("passScore"));
        } else if (draft.passScore() < 0 || (fullScore != null && draft.passScore() > fullScore)) {
            problems.add(new Violation("passScore", PublishingCodes.PASS_SCORE_RANGE, "The pass mark must be between "
                + "0 and the full score " + fullScore, Map.of("fullScore", fullScore == null ? 0 : fullScore)));
        }
        problems.addAll(TierCoverage.check(tiers, fullScore, round));
        BigDecimal maxReward = TierCoverage.maxReward(tiers);
        if (draft.budgetCap() != null && maxReward != null && draft.budgetCap().compareTo(maxReward) < 0) {
            problems.add(new Violation("budgetCap", PublishingCodes.BUDGET_BELOW_MAX_REWARD, "The budget "
                + draft.budgetCap() + " is less than the highest reward " + maxReward,
                Map.of("budgetCap", draft.budgetCap(), "maxReward", maxReward)));
        }
        BigDecimal spend = TierCoverage.uncappedSpend(draft.budgetCap(), draft.maxWinners(), maxReward);
        if (spend != null && spend.compareTo(PublishingLimits.MAX_MONEY) > 0) {
            problems.add(new Violation("maxWinners", PublishingCodes.SPEND_RANGE, "The winners times the highest "
                + "reward come to more than " + PublishingLimits.MAX_MONEY + "; set a budget or fewer winners",
                Map.of("max", PublishingLimits.MAX_MONEY)));
        }
        if (maxRewardPerAttempt != null && maxRewardPerAttempt.signum() > 0) {
            for (int i = 0; i < tiers.size(); i++) {
                BigDecimal reward = tiers.get(i).reward();
                if (reward != null && reward.compareTo(maxRewardPerAttempt) > 0) {
                    problems.add(new Violation("tiers[" + i + "].reward", PublishingCodes.REWARD_ABOVE_MAX, "Tier "
                        + (i + 1) + " pays " + reward + ", more than the highest reward allowed, " + maxRewardPerAttempt,
                        Map.of("tier", i + 1, "reward", reward, "max", maxRewardPerAttempt)));
                }
            }
        }
        if (version != null && version.survey()) {
            problems.addAll(survey(draft, tiers, fullScore));
        }
        // Q22: a prize at the sponsor's place is described to the taker (what, where); the other methods say nothing.
        if (RewardMethod.OFFLINE_REDEEM.equals(draft.rewardMethod())
            && (draft.redeemDescription() == null || draft.redeemDescription().isBlank())) {
            problems.add(required("redeemDescription"));
        }
        // Q33: a digital prize says how it is delivered and what it is; a shared one has its content; a pool names
        // how many may win (the codes must cover them: checked at submission with the pool, pool()).
        if (RewardMethod.DIGITAL_PRIZE.equals(draft.rewardMethod())) {
            if (draft.digitalDelivery() == null) {
                problems.add(new Violation("digitalDelivery", PublishingCodes.DIGITAL_DELIVERY_INVALID, "A digital "
                    + "prize is delivered from a pool, shared, or sent by the sponsor", Map.of()));
            }
            if (draft.digitalDescription() == null || draft.digitalDescription().isBlank()) {
                problems.add(required("digitalDescription"));
            }
            if (DigitalDelivery.SHARED.equals(draft.digitalDelivery()) && !draft.hasDigitalContent()) {
                problems.add(required("digitalContent"));
            }
            if (draft.pooled() && draft.maxWinners() == null) {
                problems.add(required("maxWinners"));
            }
            if (draft.pooled() && draft.maxWinners() != null && draft.maxWinners() > PublishingLimits.MAX_POOL_WINNERS) {
                problems.add(new Violation("maxWinners", PublishingCodes.DIGITAL_POOL_SHORT, "A pool covers at most "
                    + PublishingLimits.MAX_POOL_WINNERS + " winners", Map.of("available", 0,
                        "max", PublishingLimits.MAX_POOL_WINNERS)));
            }
        }
        // Q70: "referrals welcome" (D-Q70-10, D-Q70-11). A referral prize from the pool needs a pooled publication.
        problems.addAll(ReferralPolicy.problems(draft.referral(), draft.invited(), version != null && version.survey()));
        if (draft.referral() != null && RewardMethod.DIGITAL_PRIZE.equals(draft.referral().method())
            && !RewardMethod.DIGITAL_PRIZE.equals(draft.rewardMethod())) {
            problems.add(new Violation("referral.method", PublishingCodes.REFERRAL_METHOD_INVALID, "A digital prize "
                + "for referrals is delivered as the publication's: choose a digital prize for it too",
                Map.of("method", RewardMethod.DIGITAL_PRIZE)));
        }
        if (version != null) {
            QuizCompleteness.check(version).forEach(problem -> problems.add(new Violation(
                problem.field() == null ? "version" : "version." + problem.field(), problem.ruleCode(),
                problem.message(), problem.params())));
        }
        return List.copyOf(problems);
    }

    /**
     * A survey pays one reward for a complete response (Q12, D-Q12-4): its pass mark is the full score (every
     * question answered) and only the tier of the full score has a reward. The sponsor console builds these tiers;
     * the coverage itself is {@link TierCoverage}'s.
     */
    /**
     * Q81 (D-Q81-2): the full score an attempt of the version can reach: {@code pickCount} times the common points when
     * each taker is given that many questions and the pick stands ({@link #pickProblems}), else the version's.
     */
    public static int fullScore(QuizSnapshot version, Integer pickCount) {
        Integer points = pickCount == null || !pickProblems(pickCount, version).isEmpty() ? null : commonPoints(version);
        return points == null ? version.fullScore() : pickCount * points;
    }

    /** The points every question of the version scores; null when they differ (or there are no questions). */
    public static Integer commonPoints(QuizSnapshot version) {
        List<QuizSnapshot.Question> questions = version.questions();
        if (questions.isEmpty()) {
            return null;
        }
        int points = questions.getFirst().points();
        return questions.stream().allMatch(question -> question.points() == points) ? points : null;
    }

    /**
     * Q81 (D-Q81-1, D-Q81-2): why {@code pickCount} questions cannot be drawn from the version: not 1..n-1 of its n
     * questions (all of them is left empty), a survey (answered whole), or questions of unequal points (each taker's
     * full score would differ); F-S6: no pick of a version larger than one attempt holds, nor a pick larger than it.
     * None without a version.
     */
    /** F-S6: the refusal of asking all of a version's {@code n} questions when one attempt holds {@code most}. */
    public static Violation pickRequired(int n, int most) {
        return new Violation("pickCount", PublishingCodes.PICK_REQUIRED, "The version has " + n + " questions and one "
            + "attempt gives at most " + most + "; set how many to draw", Map.of("questionCount", n, "max", most));
    }

    public static List<Violation> pickProblems(Integer pickCount, QuizSnapshot version) {
        if (version == null) {
            return List.of();
        }
        int n = version.questions().size();
        int most = com.jabiz.quizbuks.play.PlayLimits.MAX_PER_ATTEMPT_TECH;
        // F-S6: one attempt holds at most this many questions; a larger bank is drawn from.
        if (pickCount == null) {
            return n <= most ? List.of() : List.of(pickRequired(n, most));
        }
        if (pickCount > most && pickCount < n) {
            return List.of(new Violation("pickCount", PublishingCodes.PICK_TOO_MANY, "One attempt gives at most "
                + most + " questions", Map.of("pickCount", pickCount, "max", most)));
        }
        if (pickCount < 1 || pickCount >= n) {
            return List.of(new Violation("pickCount", PublishingCodes.PICK_RANGE, "The pick must be between 1 and "
                + (n - 1) + " of the version's " + n + " questions; leave it empty to ask all of them",
                Map.of("pickCount", pickCount, "questionCount", n)));
        }
        if (version.survey()) {
            return List.of(new Violation("pickCount", PublishingCodes.PICK_SURVEY, "A survey is answered whole",
                Map.of()));
        }
        if (commonPoints(version) == null) {
            return List.of(new Violation("pickCount", PublishingCodes.PICK_UNEQUAL_POINTS, "A pick needs every "
                + "question of the version to score the same", Map.of()));
        }
        return List.of();
    }

    /**
     * F-S7 (§2): why the version cannot give each taker {@code quotas} questions of each difficulty: a level that is
     * not one, a count below 0, a question of the version without a difficulty ({@code PICK_DIFFICULTY_UNSET}), or a
     * level drawn more questions than the version has of it ({@code PICK_DIFFICULTY_SHORT}). The total is the pick's
     * count, checked by {@link #pickProblems}. None for no draw by difficulty or no version.
     */
    public static List<Violation> difficultyProblems(Map<String, Integer> quotas, QuizSnapshot version) {
        if (quotas == null || quotas.isEmpty()) {
            return List.of();
        }
        List<Violation> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> quota : quotas.entrySet()) {
            if (!com.jabiz.quizbuks.content.QuestionDifficulty.known(quota.getKey()) || quota.getValue() == null
                || quota.getValue() < 0) {
                return List.of(new Violation("pickByDifficulty", PublishingCodes.PICK_DIFFICULTY_INVALID,
                    "Each difficulty is drawn 0 or more questions", Map.of()));
            }
        }
        if (version == null) {
            return List.of();
        }
        if (version.survey()) {
            return List.of(new Violation("pickByDifficulty", PublishingCodes.PICK_SURVEY, "A survey is answered "
                + "whole", Map.of()));
        }
        long unset = version.questions().stream().filter(question -> question.difficulty() == null).count();
        if (unset > 0) {
            return List.of(new Violation("pickByDifficulty", PublishingCodes.PICK_DIFFICULTY_UNSET, unset
                + " questions of the version have no difficulty", Map.of("unset", unset)));
        }
        for (Map.Entry<String, Integer> quota : quotas.entrySet()) {
            long available = version.questions().stream()
                .filter(question -> quota.getKey().equals(question.difficulty())).count();
            if (quota.getValue() > available) {
                problems.add(new Violation("pickByDifficulty." + quota.getKey(),
                    PublishingCodes.PICK_DIFFICULTY_SHORT, "The version has " + available + " questions of "
                    + quota.getKey(), Map.of("difficulty", quota.getKey(), "available", available,
                    "count", quota.getValue())));
            }
        }
        return problems;
    }

    private static List<Violation> survey(Draft draft, List<TierCoverage.Tier> tiers, int fullScore) {
        List<Violation> problems = new ArrayList<>();
        if (draft.passScore() != null && draft.passScore() != fullScore) {
            problems.add(new Violation("passScore", PublishingCodes.SURVEY_PASS_SCORE, "A survey's pass mark is "
                + "its number of questions " + fullScore, Map.of("fullScore", fullScore)));
        }
        for (int i = 0; i < tiers.size(); i++) {
            TierCoverage.Tier tier = tiers.get(i);
            if (tier.fromScore() < fullScore && tier.reward().signum() != 0) {
                problems.add(new Violation("tiers[" + i + "].reward", PublishingCodes.SURVEY_TIERS, "Only a "
                    + "complete response is rewarded: tier " + (i + 1) + " pays for an incomplete one",
                    Map.of("tier", i + 1)));
            }
        }
        return problems;
    }

    /**
     * Q33: whether the pool covers the winners of a pool-delivered publication: {@code available} codes not issued
     * nor revoked against {@code maxWinners}; nothing for the other deliveries.
     */
    public static List<Violation> pool(Draft draft, int available) {
        if (!draft.pooled() || draft.maxWinners() == null || available >= draft.poolNeeded()) {
            return List.of();
        }
        if (available >= draft.maxWinners()) {
            // Q70 (D-Q70-19): the winners are covered, the referral quota is not.
            return List.of(new Violation("referral.quota", PublishingCodes.REFERRAL_POOL_SHORT, "The pool has "
                + available + " codes for " + draft.maxWinners() + " winners and " + draft.referral().quota()
                + " referrals: load more or lower the quota", Map.of("available", available,
                    "max", draft.poolNeeded())));
        }
        return List.of(new Violation("maxWinners", PublishingCodes.DIGITAL_POOL_SHORT, "The pool has " + available
            + " codes for " + draft.maxWinners() + " winners: load more or lower the winners",
            Map.of("available", available, "max", draft.maxWinners())));
    }

    private static Violation required(String field) {
        return new Violation(field, PublishingCodes.FIELD_REQUIRED, field + " is required", Map.of("field", field));
    }

    private PublicationReadiness() {}
}
