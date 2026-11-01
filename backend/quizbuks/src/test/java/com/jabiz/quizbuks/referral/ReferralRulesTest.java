package com.jabiz.quizbuks.referral;

import com.jabiz.quizbuks.publishing.RefereeRules;
import com.jabiz.quizbuks.publishing.ReferralPolicy;
import com.jabiz.quizbuks.publishing.RewardMethod;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");

    private static ReferralPolicy policy(List<String> rules) {
        return new ReferralPolicy(3, RewardMethod.PLATFORM, new BigDecimal("300"), null, ReferralPolicy.NONE, rules, 7);
    }

    /** A claim that counts, changed by {@code with}. */
    private static ReferralRules.Claim claim(ReferralPolicy policy) {
        return new ReferralRules.Claim(policy, true, false, "sponsor", "wang", "li", false, false, false, 0,
            List.of(), 3, 10, false, null, true, "JP", "JP", NOW);
    }

    @Test
    void aFriendOfAReferrerCounts() {
        assertThat(ReferralRules.claimRefusal(claim(policy(RefereeRules.ALL)))).isNull();
    }

    @Test
    void theFixedConditionsOfAReferral() {
        ReferralRules.Claim ok = claim(policy(List.of()));
        assertThat(ReferralRules.claimRefusal(new ReferralRules.Claim(null, true, false, "sponsor", "wang", "li",
            false, false, false, 0, List.of(), 3, 10, false, null, true, null, null, NOW)))
            .isEqualTo(ReferralRules.NOT_OPEN);
        assertThat(ReferralRules.claimRefusal(with(ok, "open", false))).isEqualTo(ReferralRules.NOT_OPEN);
        assertThat(ReferralRules.claimRefusal(with(ok, "banned", true))).isEqualTo(ReferralRules.SPONSOR_BANNED);
        assertThat(ReferralRules.claimRefusal(new ReferralRules.Claim(ok.policy(), true, false, "sponsor", "li", "li",
            false, false, false, 0, List.of(), 3, 10, false, null, true, null, null, NOW)))
            .isEqualTo(ReferralRules.SELF);
        assertThat(ReferralRules.claimRefusal(new ReferralRules.Claim(ok.policy(), true, false, "li", "wang", "li",
            false, false, false, 0, List.of(), 3, 10, false, null, true, null, null, NOW)))
            .isEqualTo(ReferralRules.SPONSOR_SIDE);
        assertThat(ReferralRules.claimRefusal(with(ok, "staff", true))).isEqualTo(ReferralRules.SPONSOR_SIDE);
        assertThat(ReferralRules.claimRefusal(with(ok, "attempted", true))).isEqualTo(ReferralRules.ALREADY_ANSWERING);
        assertThat(ReferralRules.claimRefusal(with(ok, "referred", true))).isEqualTo(ReferralRules.ALREADY_REFERRED);
    }

    @Test
    void theQuotaAndTheReferrersLimits() {
        ReferralRules.Claim ok = claim(policy(List.of()));
        assertThat(ReferralRules.claimRefusal(new ReferralRules.Claim(ok.policy(), true, false, "sponsor", "wang",
            "li", false, false, false, 3, List.of(), 3, 10, false, null, true, null, null, NOW)))
            .isEqualTo(ReferralRules.QUOTA_FULL);
        List<Instant> today = List.of(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(2)),
            NOW.minus(Duration.ofHours(23)));
        assertThat(ReferralRules.claimRefusal(dates(ok, today))).isEqualTo(ReferralRules.REFERRER_DAILY);
        List<Instant> yesterday = List.of(NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(1)),
            NOW.minus(Duration.ofHours(2)));
        assertThat(ReferralRules.claimRefusal(dates(ok, yesterday))).isNull();
        List<Instant> ten = java.util.stream.IntStream.range(0, 10)
            .mapToObj(i -> NOW.minus(Duration.ofDays(2 + i))).toList();
        assertThat(ReferralRules.claimRefusal(dates(ok, ten))).isEqualTo(ReferralRules.REFERRER_TOTAL);
    }

    @Test
    void theSponsorsRulesOnlyWhenChosen() {
        // A regular, an old account, not matched and from elsewhere: counted when the sponsor chose no rule.
        assertThat(ReferralRules.ruleRefusal(policy(List.of()), true, NOW.minus(Duration.ofDays(100)), false, "CN",
            "JP", NOW)).isNull();
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.NOT_REGULAR)), true, null, true, "JP", "JP",
            NOW)).isEqualTo(ReferralRules.REGULAR);
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.NEW_TO_PLATFORM)), false,
            NOW.minus(Duration.ofDays(8)), true, "JP", "JP", NOW)).isEqualTo(ReferralRules.NOT_NEW);
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.NEW_TO_PLATFORM)), false,
            NOW.minus(Duration.ofDays(6)), true, "JP", "JP", NOW)).isNull();
        // Without a profile yet: new.
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.NEW_TO_PLATFORM)), false, null, true, null,
            "JP", NOW)).isNull();
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.MATCHES_AUDIENCE)), false, null, false, "JP",
            "JP", NOW)).isEqualTo(ReferralRules.NOT_MATCHED);
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.SAME_REGION_AS_REFERRER)), false, null, true,
            "CN", "JP", NOW)).isEqualTo(ReferralRules.OTHER_REGION);
        assertThat(ReferralRules.ruleRefusal(policy(List.of(RefereeRules.SAME_REGION_AS_REFERRER)), false, null, true,
            null, "JP", NOW)).isEqualTo(ReferralRules.OTHER_REGION);
    }

    @Test
    void earnedAndOver() {
        Instant referred = NOW;
        Instant expires = NOW.plus(Duration.ofDays(7));
        assertThat(ReferralRules.earned(referred, expires, NOW.plus(Duration.ofDays(1)), true)).isTrue();
        assertThat(ReferralRules.earned(referred, expires, NOW.minus(Duration.ofMinutes(1)), true)).isFalse();
        assertThat(ReferralRules.earned(referred, expires, expires, true)).isFalse();
        assertThat(ReferralRules.earned(referred, expires, NOW.plus(Duration.ofDays(1)), false)).isFalse();
        assertThat(ReferralRules.earned(referred, expires, null, false)).isFalse();
        // Waiting: not over; failed: over at once; passed in time: never over (to be paid); time up: over.
        assertThat(ReferralRules.over(referred, expires, null, false, NOW.plus(Duration.ofDays(1)))).isFalse();
        assertThat(ReferralRules.over(referred, expires, NOW.plus(Duration.ofDays(1)), false,
            NOW.plus(Duration.ofDays(1)))).isTrue();
        assertThat(ReferralRules.over(referred, expires, NOW.plus(Duration.ofDays(1)), true,
            NOW.plus(Duration.ofDays(30)))).isFalse();
        assertThat(ReferralRules.over(referred, expires, null, false, expires)).isTrue();
    }

    @Test
    void whatAReferrerMustHaveDone() {
        assertThat(ReferralRules.referrerMeets(ReferralPolicy.NONE, false, false)).isTrue();
        assertThat(ReferralRules.referrerMeets(null, false, false)).isTrue();
        assertThat(ReferralRules.referrerMeets(ReferralPolicy.ANSWERED, false, false)).isFalse();
        assertThat(ReferralRules.referrerMeets(ReferralPolicy.ANSWERED, true, false)).isTrue();
        assertThat(ReferralRules.referrerMeets(ReferralPolicy.PASSED, true, false)).isFalse();
        assertThat(ReferralRules.referrerMeets(ReferralPolicy.PASSED, true, true)).isTrue();
    }

    private static ReferralRules.Claim dates(ReferralRules.Claim c, List<Instant> dates) {
        return new ReferralRules.Claim(c.policy(), true, false, "sponsor", "wang", "li", false, false, false, 0, dates,
            3, 10, false, null, true, null, null, NOW);
    }

    private static ReferralRules.Claim with(ReferralRules.Claim c, String what, boolean value) {
        return new ReferralRules.Claim(c.policy(), what.equals("open") ? value : true,
            what.equals("banned") && value, "sponsor", "wang", "li", what.equals("staff") && value,
            what.equals("attempted") && value, what.equals("referred") && value, 0, List.of(), 3, 10, false, null,
            true, null, null, NOW);
    }
}
