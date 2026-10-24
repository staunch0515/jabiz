package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class PublicationReadinessTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
    private static final UUID VERSION = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");

    /** A complete quiz of full score 5: two questions worth 2 and 3. */
    private static QuizSnapshot quiz() {
        return new QuizSnapshot("Capitals", null, null, 300, false, List.of(), List.of(
            new QuizSnapshot.Question(1, "France?", null, 2, List.of(new QuizSnapshot.Option(1, "Paris", null, true),
                new QuizSnapshot.Option(2, "Lyon", null, false))),
            new QuizSnapshot.Question(2, "Japan?", null, 3, List.of(new QuizSnapshot.Option(1, "Tokyo", null, true),
                new QuizSnapshot.Option(2, "Osaka", null, false)))));
    }

    private static PublicationReadiness.Draft ready() {
        return new PublicationReadiness.Draft(VERSION, "Autumn quiz", "GLOBAL", NOW.plus(Duration.ofDays(1)),
            NOW.plus(Duration.ofDays(8)), 300, 3, BigDecimal.valueOf(10_000), 50);
    }

    private static List<TierCoverage.Tier> tiers() {
        return List.of(new TierCoverage.Tier(0, 2, BigDecimal.ZERO), new TierCoverage.Tier(3, 4, BigDecimal.TEN),
            new TierCoverage.Tier(5, 5, BigDecimal.valueOf(100)));
    }

    @Test
    void aCompletePublicationIsReady() {
        assertThat(PublicationReadiness.check(ready(), tiers(), quiz(), NOW)).isEmpty();
        // The start may lie in the past (question Q4-7): it goes live at once.
        PublicationReadiness.Draft started = new PublicationReadiness.Draft(VERSION, "Now", "JP,ASIA",
            NOW.minus(Duration.ofDays(1)), NOW.plusSeconds(1), 30, 0, null, null);
        assertThat(PublicationReadiness.check(started, tiers(), quiz(), NOW)).isEmpty();
    }

    @Test
    void aDraftWithOnlyANameReportsEveryProblemAtOnce() {
        PublicationReadiness.Draft empty = new PublicationReadiness.Draft(null, "Only a name", null, null, null,
            null, null, null, null);
        assertThat(PublicationReadiness.check(empty, List.of(), null, NOW))
            .extracting(Violation::field, Violation::ruleCode).containsExactly(
                tuple("versionId", PublishingCodes.NO_VERSION),
                tuple("regions", PublishingCodes.FIELD_REQUIRED),
                tuple("startAt", PublishingCodes.FIELD_REQUIRED),
                tuple("endAt", PublishingCodes.FIELD_REQUIRED),
                tuple("timeLimitSec", PublishingCodes.FIELD_REQUIRED),
                tuple("passScore", PublishingCodes.FIELD_REQUIRED),
                tuple("tiers", PublishingCodes.TIER_NO_REWARD));
    }

    @Test
    void timesPassMarkBudgetAndTiers() {
        PublicationReadiness.Draft bad = new PublicationReadiness.Draft(VERSION, "Bad", "GLOBAL",
            NOW.minus(Duration.ofDays(400)), NOW, 10, 6, BigDecimal.valueOf(50), 1);
        assertThat(PublicationReadiness.check(bad, tiers(), quiz(), NOW))
            .extracting(Violation::field, Violation::ruleCode).containsExactly(
                tuple("endAt", PublishingCodes.END_PASSED),
                tuple("endAt", PublishingCodes.TOO_LONG),
                tuple("timeLimitSec", PublishingCodes.TIME_LIMIT_RANGE),
                tuple("passScore", PublishingCodes.PASS_SCORE_RANGE),
                tuple("budgetCap", PublishingCodes.BUDGET_BELOW_MAX_REWARD));
        PublicationReadiness.Draft backwards = new PublicationReadiness.Draft(VERSION, "Backwards", "GLOBAL",
            NOW.plus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(2)), 60, 0, null, null);
        assertThat(PublicationReadiness.check(backwards, tiers(), quiz(), NOW)).extracting(Violation::ruleCode)
            .containsExactly(PublishingCodes.TIME_ORDER);
        // Tiers are checked against the version's full score.
        assertThat(PublicationReadiness.check(ready(), List.of(new TierCoverage.Tier(0, 4, BigDecimal.ONE)), quiz(),
            NOW)).extracting(Violation::ruleCode).containsExactly(PublishingCodes.TIER_NOT_TO_FULL);
        // Exactly 366 days is allowed.
        PublicationReadiness.Draft year = new PublicationReadiness.Draft(VERSION, "Year", "GLOBAL", NOW,
            NOW.plus(PublishingLimits.MAX_RUNNING), 60, 0, null, null);
        assertThat(PublicationReadiness.check(year, tiers(), quiz(), NOW)).isEmpty();
    }

    @Test
    void theVersionIsCheckedAgainOnItsSnapshot() {
        QuizSnapshot incomplete = new QuizSnapshot("Old", null, null, 300, false, List.of(), List.of(
            new QuizSnapshot.Question(1, "One option?", null, 5, List.of(
                new QuizSnapshot.Option(1, "Only", null, true)))));
        assertThat(PublicationReadiness.check(ready(), tiers(), incomplete, NOW))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("version.questions[0].options", ContentCodes.OPTION_COUNT));
    }

    /** A survey of two questions: full score 2. */
    private static QuizSnapshot survey() {
        return new QuizSnapshot("Habits", null, null, 300, false, List.of(), List.of(
            new QuizSnapshot.Question(1, "Often?", null, 1, List.of(new QuizSnapshot.Option(1, "Yes", null, false),
                new QuizSnapshot.Option(2, "No", null, false)), QbContent.SINGLE),
            new QuizSnapshot.Question(2, "Which?", null, 1, List.of(new QuizSnapshot.Option(1, "Tea", null, false),
                new QuizSnapshot.Option(2, "Coffee", null, false)), QbContent.MULTIPLE)), true);
    }

    private static PublicationReadiness.Draft surveyDraft(int passScore) {
        return new PublicationReadiness.Draft(VERSION, "Habits", "GLOBAL", NOW.plus(Duration.ofDays(1)),
            NOW.plus(Duration.ofDays(8)), 300, passScore, BigDecimal.valueOf(10_000), 50);
    }

    @Test
    void aSurveyPaysOneRewardForACompleteResponseOnly() {
        List<TierCoverage.Tier> one = List.of(new TierCoverage.Tier(0, 1, BigDecimal.ZERO),
            new TierCoverage.Tier(2, 2, BigDecimal.valueOf(50)));
        assertThat(PublicationReadiness.check(surveyDraft(2), one, survey(), NOW)).isEmpty();
        assertThat(PublicationReadiness.check(surveyDraft(1), one, survey(), NOW))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("passScore", PublishingCodes.SURVEY_PASS_SCORE));
        List<TierCoverage.Tier> partial = List.of(new TierCoverage.Tier(0, 0, BigDecimal.ZERO),
            new TierCoverage.Tier(1, 1, BigDecimal.TEN), new TierCoverage.Tier(2, 2, BigDecimal.valueOf(50)));
        assertThat(PublicationReadiness.check(surveyDraft(2), partial, survey(), NOW))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("tiers[1].reward", PublishingCodes.SURVEY_TIERS));
        List<TierCoverage.Tier> wide = List.of(new TierCoverage.Tier(0, 0, BigDecimal.ZERO),
            new TierCoverage.Tier(1, 2, BigDecimal.valueOf(50)));
        assertThat(PublicationReadiness.check(surveyDraft(2), wide, survey(), NOW))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("tiers[1].reward", PublishingCodes.SURVEY_TIERS));
        // A scored quiz may still reward any tier.
        assertThat(PublicationReadiness.check(ready(), tiers(), quiz(), NOW)).isEmpty();
    }

    @Test
    void aPrizeAtTheSponsorsPlaceIsDescribed() {
        // Q22: OFFLINE_REDEEM needs the description; the other methods never do.
        PublicationReadiness.Draft base = ready();
        PublicationReadiness.Draft prize = new PublicationReadiness.Draft(base.versionId(), base.name(),
            base.regions(), base.startAt(), base.endAt(), base.timeLimitSec(), base.passScore(), base.budgetCap(),
            base.maxWinners(), RewardMethod.OFFLINE_REDEEM, "  ");
        assertThat(PublicationReadiness.check(prize, tiers(), quiz(), NOW))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(tuple("redeemDescription", PublishingCodes.FIELD_REQUIRED));
        PublicationReadiness.Draft described = new PublicationReadiness.Draft(base.versionId(), base.name(),
            base.regions(), base.startAt(), base.endAt(), base.timeLimitSec(), base.passScore(), base.budgetCap(),
            base.maxWinners(), RewardMethod.OFFLINE_REDEEM, "A coffee at the counter, 9-18h");
        assertThat(PublicationReadiness.check(described, tiers(), quiz(), NOW)).isEmpty();
        PublicationReadiness.Draft cash = new PublicationReadiness.Draft(base.versionId(), base.name(),
            base.regions(), base.startAt(), base.endAt(), base.timeLimitSec(), base.passScore(), base.budgetCap(),
            base.maxWinners(), RewardMethod.SPONSOR_CASH, null);
        assertThat(PublicationReadiness.check(cash, tiers(), quiz(), NOW)).isEmpty();
        assertThat(base.rewardMethod()).isEqualTo(RewardMethod.PLATFORM);
    }
}
