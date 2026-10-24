package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.publishing.PublicationRules;
import com.jabiz.quizbuks.publishing.PublicationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptRulesTest {

    static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    static AttemptRules.Counts counts(long starts, long completions, long winners, long reserved, long spent) {
        return new AttemptRules.Counts(starts, completions, winners, BigDecimal.valueOf(reserved),
            BigDecimal.valueOf(spent));
    }

    static AttemptRules.Limits limits(Integer budget, Integer winners, int maxReward) {
        return new AttemptRules.Limits(budget == null ? null : BigDecimal.valueOf(budget), winners,
            BigDecimal.valueOf(maxReward));
    }

    @Test
    void theBudgetTakesAnAttemptWhoseHighestRewardFitsExactly() {
        // 1000 budget, 600 spent, 300 reserved: 100 more fits exactly, 101 does not.
        assertThat(AttemptRules.limitRefusal(limits(1000, null, 100), counts(5, 2, 2, 300, 600))).isNull();
        assertThat(AttemptRules.limitRefusal(limits(1000, null, 101), counts(5, 2, 2, 300, 600)))
            .isEqualTo(PlayCodes.BUDGET_FULL);
        assertThat(AttemptRules.limitRefusal(limits(null, null, 101), counts(5, 2, 2, 300, 600))).isNull();
    }

    @Test
    void attemptsInProgressHoldAWinnersPlace() {
        // 10 places: 7 winners and 2 in progress leave one; 3 in progress leave none.
        assertThat(AttemptRules.limitRefusal(limits(null, 10, 1), counts(12, 10, 7, 0, 0))).isNull();
        assertThat(AttemptRules.limitRefusal(limits(null, 10, 1), counts(13, 10, 7, 0, 0)))
            .isEqualTo(PlayCodes.QUOTA_FULL);
        assertThat(AttemptRules.limitRefusal(limits(null, 10, 1), counts(10, 10, 10, 0, 0)))
            .isEqualTo(PlayCodes.QUOTA_FULL);
        assertThat(counts(13, 10, 7, 0, 0).inProgress()).isEqualTo(3);
    }

    @Test
    void theDeadlineAndTheGraceToTheMillisecond() {
        Instant deadline = AttemptRules.deadline(NOW, 300);
        assertThat(deadline).isEqualTo(NOW.plusSeconds(300));
        assertThat(AttemptRules.pastDeadline(deadline, deadline)).isFalse();
        assertThat(AttemptRules.pastDeadline(deadline, deadline.plusMillis(1))).isTrue();
        Instant graceEnd = deadline.plus(PlayLimits.SUBMIT_GRACE);
        assertThat(AttemptRules.tooLate(deadline, graceEnd.minusMillis(1))).isFalse();
        assertThat(AttemptRules.tooLate(deadline, graceEnd)).isFalse();
        assertThat(AttemptRules.tooLate(deadline, graceEnd.plusMillis(1))).isTrue();
    }

    @Test
    void theSponsorPaysWithNothingPendingAndEnoughBalance() {
        assertThat(AttemptRules.sponsorPays(BigDecimal.valueOf(100), 0, BigDecimal.valueOf(100))).isTrue();
        assertThat(AttemptRules.sponsorPays(BigDecimal.valueOf(99), 0, BigDecimal.valueOf(100))).isFalse();
        assertThat(AttemptRules.sponsorPays(BigDecimal.valueOf(1000), 1, BigDecimal.valueOf(100))).isFalse();
    }

    static AttemptRules.Card card(String status, String regions) {
        return new AttemptRules.Card("sponsor", status, regions, NOW.minusSeconds(60), NOW.plusSeconds(3600),
            limits(1000, 10, 100));
    }

    /** Q25: an invited card asks for the caller's invitation instead of their regions. */
    @Test
    void anInvitedCardAsksForTheInvitationNotTheRegions() {
        AttemptRules.Card invited = new AttemptRules.Card("sponsor", "LIVE", "GLOBAL", NOW.minusSeconds(60),
            NOW.plusSeconds(3600), limits(1000, 10, 100), "PLATFORM", true);
        AttemptRules.Counts none = counts(0, 0, 0, 0, 0);
        BigDecimal rich = BigDecimal.valueOf(10_000);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,US,NORTH_AMERICA", true, invited, none, rich, 0, NOW))
            .isNull();
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP,ASIA", false, invited, none, rich, 0, NOW))
            .isEqualTo(PlayCodes.NOT_INVITED);
        // The other refusals come first or after as before: the sponsor's own, closed, the limits.
        assertThat(AttemptRules.startRefusal("sponsor", "GLOBAL", true, invited, none, rich, 0, NOW))
            .isEqualTo(PlayCodes.OWN_PUBLICATION);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL", true, invited, counts(10, 0, 0, 0, 0), rich, 0, NOW))
            .isEqualTo(PlayCodes.QUOTA_FULL);
        // A public card never reads an invitation: the regions decide.
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,US,NORTH_AMERICA", true, card("LIVE", "JP"), none,
            rich, 0, NOW)).isEqualTo(PlayCodes.NOT_VISIBLE);
    }

    @Test
    void theFirstRefusalThatApplies() {
        AttemptRules.Counts none = counts(0, 0, 0, 0, 0);
        BigDecimal rich = BigDecimal.valueOf(10_000);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP,ASIA", card("LIVE", "JP"), none, rich, 0, NOW))
            .isNull();
        assertThat(AttemptRules.startRefusal("sponsor", "GLOBAL,JP,ASIA", card("LIVE", "JP"), none, rich, 0, NOW))
            .isEqualTo(PlayCodes.OWN_PUBLICATION);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP,ASIA", card("PAUSED", "JP"), none, rich, 0, NOW))
            .isEqualTo(PlayCodes.NOT_OPEN);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP,ASIA", card("LIVE", "JP"), none, rich, 0,
            NOW.plusSeconds(3600))).isEqualTo(PlayCodes.NOT_OPEN);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,US,NORTH_AMERICA", card("LIVE", "JP,ASIA"), none, rich,
            0, NOW)).isEqualTo(PlayCodes.NOT_VISIBLE);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL", card("LIVE", "GLOBAL"), none, rich, 0, NOW)).isNull();
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", card("LIVE", "JP"), counts(1, 0, 0, 100, 850),
            rich, 0, NOW)).isEqualTo(PlayCodes.BUDGET_FULL);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", card("LIVE", "JP"), counts(10, 0, 0, 0, 0), rich,
            0, NOW)).isEqualTo(PlayCodes.QUOTA_FULL);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", card("LIVE", "JP"), none, BigDecimal.valueOf(99),
            0, NOW)).isEqualTo(PlayCodes.FUNDS_SHORT);
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", card("LIVE", "JP"), none, rich, 1, NOW))
            .isEqualTo(PlayCodes.FUNDS_SHORT);
    }

    @Test
    void aPublicationEndsWhenItsWinnersOrItsBudgetAreUsedUp() {
        assertThat(PublicationRules.endsBy(null, 10, BigDecimal.TEN, 10, BigDecimal.ZERO))
            .isEqualTo(PublicationStatus.QUOTA);
        assertThat(PublicationRules.endsBy(null, 10, BigDecimal.TEN, 9, BigDecimal.ZERO)).isNull();
        // What is left (95) cannot pay the highest reward (100): no attempt could start any more.
        assertThat(PublicationRules.endsBy(BigDecimal.valueOf(1000), null, BigDecimal.valueOf(100), 3,
            BigDecimal.valueOf(905))).isEqualTo(PublicationStatus.BUDGET);
        assertThat(PublicationRules.endsBy(BigDecimal.valueOf(1000), null, BigDecimal.valueOf(100), 3,
            BigDecimal.valueOf(900))).isNull();
        assertThat(PublicationRules.endsBy(null, null, BigDecimal.TEN, 1_000_000, BigDecimal.TEN)).isNull();
    }

    @Test
    void cashAndPrizesNeverWantTheSponsorsWallet() {
        // Q22: an empty wallet refuses a platform-paid card and nobody else.
        AttemptRules.Counts none = counts(0, 0, 0, 0, 0);
        AttemptRules.Card platform = card("LIVE", "JP");
        assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", platform, none, BigDecimal.ZERO, 0, NOW))
            .isEqualTo(PlayCodes.FUNDS_SHORT);
        for (String method : List.of(com.jabiz.quizbuks.publishing.RewardMethod.SPONSOR_CASH,
            com.jabiz.quizbuks.publishing.RewardMethod.OFFLINE_REDEEM)) {
            AttemptRules.Card own = new AttemptRules.Card("sponsor", "LIVE", "JP", NOW.minusSeconds(60),
                NOW.plusSeconds(3600), limits(1000, 10, 100), method);
            assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", own, none, BigDecimal.ZERO, 3, NOW)).isNull();
            // The other limits still hold.
            assertThat(AttemptRules.startRefusal("taker", "GLOBAL,JP", own, counts(10, 0, 0, 1000, 0),
                BigDecimal.ZERO, 0, NOW)).isEqualTo(PlayCodes.BUDGET_FULL);
        }
        assertThat(platform.rewardMethod()).isEqualTo(com.jabiz.quizbuks.publishing.RewardMethod.PLATFORM);
    }
}
