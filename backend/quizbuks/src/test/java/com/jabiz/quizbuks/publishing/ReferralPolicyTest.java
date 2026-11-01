package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralPolicyTest {

    private static ReferralPolicy policy(int quota, String method, String amount, String description,
        String requires, List<String> rules, int days) {
        return new ReferralPolicy(quota, method, amount == null ? null : new BigDecimal(amount), description, requires,
            rules, days);
    }

    private static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void roundTripsAsCanonicalJsonWithSortedRules() {
        ReferralPolicy policy = policy(20, RewardMethod.PLATFORM, "300", null, ReferralPolicy.ANSWERED,
            List.of(RefereeRules.NEW_TO_PLATFORM, RefereeRules.NOT_REGULAR, RefereeRules.NOT_REGULAR), 7);
        assertThat(policy.refereeRules()).containsExactly(RefereeRules.NEW_TO_PLATFORM, RefereeRules.NOT_REGULAR);
        String json = ReferralPolicy.toJson(policy);
        assertThat(json).isEqualTo("{\"amount\":\"300\",\"days\":7,\"description\":null,\"method\":\"PLATFORM\","
            + "\"quota\":20,\"refereeRules\":[\"NEW_TO_PLATFORM\",\"NOT_REGULAR\"],\"referrerRequires\":\"ANSWERED\"}");
        assertThat(ReferralPolicy.fromJson(json)).isEqualTo(policy);
        assertThat(ReferralPolicy.fromJson(null)).isNull();
        assertThat(ReferralPolicy.fromJson(" ")).isNull();
        assertThat(ReferralPolicy.toJson(null)).isNull();
        assertThat(policy.maxSpend()).isEqualByComparingTo("6000");
    }

    @Test
    void aGoodPolicyHasNoProblemsAndNoneIsNone() {
        assertThat(ReferralPolicy.problems(null, true, true)).isEmpty();
        assertThat(ReferralPolicy.problems(policy(1, RewardMethod.SPONSOR_CASH, "1", null, null, List.of(), 1),
            false, false)).isEmpty();
        assertThat(ReferralPolicy.problems(policy(1000, RewardMethod.OFFLINE_REDEEM, "500", "A coffee", "PASSED",
            RefereeRules.ALL, 30), false, false)).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        List<Violation> problems = ReferralPolicy.problems(policy(0, "BITCOIN", "1.5", null, "MAYBE",
            List.of("FRIENDS_ONLY"), 31), true, false);
        assertThat(codes(problems)).containsExactly(PublishingCodes.REFERRAL_INVITED,
            PublishingCodes.REFERRAL_QUOTA_RANGE, PublishingCodes.REFERRAL_DAYS_RANGE,
            PublishingCodes.REFERRAL_AMOUNT_RANGE, PublishingCodes.REFERRAL_METHOD_INVALID,
            PublishingCodes.REFERRAL_REQUIREMENT_INVALID, PublishingCodes.REFERRAL_RULE_INVALID);
        assertThat(problems).extracting(Violation::field).contains("referral", "referral.quota", "referral.days");
    }

    @Test
    void aPrizeIsDescribedASurveyRequiresNoPassAndTheSpendIsBounded() {
        assertThat(codes(ReferralPolicy.problems(policy(5, RewardMethod.DIGITAL_PRIZE, "100", " ", null, List.of(),
            7), false, false))).containsExactly(PublishingCodes.FIELD_REQUIRED);
        assertThat(codes(ReferralPolicy.problems(policy(5, RewardMethod.PLATFORM, "100", null,
            ReferralPolicy.PASSED, List.of(), 7), false, true))).containsExactly(PublishingCodes.REFERRAL_SURVEY_PASS);
        assertThat(codes(ReferralPolicy.problems(policy(1000, RewardMethod.PLATFORM, "1000000001", null, null,
            List.of(), 7), false, false))).containsExactly(PublishingCodes.SPEND_RANGE);
        assertThat(codes(ReferralPolicy.problems(policy(5, RewardMethod.PLATFORM, "-1", null, null, List.of(), 7),
            false, false))).containsExactly(PublishingCodes.REFERRAL_AMOUNT_RANGE);
        assertThat(codes(ReferralPolicy.problems(policy(5, RewardMethod.PLATFORM, null, null, null, List.of(), 7),
            false, false))).containsExactly(PublishingCodes.REFERRAL_AMOUNT_RANGE);
    }

    @Test
    void aPoolCoversTheWinnersAndAReferralQuotaFromIt() {
        ReferralPolicy digital = policy(10, RewardMethod.DIGITAL_PRIZE, "100", "A voucher", null, List.of(), 7);
        PublicationReadiness.Draft pooled = new PublicationReadiness.Draft(null, "n", "GLOBAL", null, null, 60, 1,
            null, 5, RewardMethod.DIGITAL_PRIZE, null, Audience.PUBLIC, DigitalDelivery.POOL, "A voucher", false,
            digital);
        assertThat(pooled.poolNeeded()).isEqualTo(15);
        assertThat(PublicationReadiness.pool(pooled, 15)).isEmpty();
        assertThat(codes(PublicationReadiness.pool(pooled, 10))).containsExactly(PublishingCodes.REFERRAL_POOL_SHORT);
        assertThat(codes(PublicationReadiness.pool(pooled, 4))).containsExactly(PublishingCodes.DIGITAL_POOL_SHORT);
        PublicationReadiness.Draft cash = new PublicationReadiness.Draft(null, "n", "GLOBAL", null, null, 60, 1,
            null, 5, RewardMethod.DIGITAL_PRIZE, null, Audience.PUBLIC, DigitalDelivery.POOL, "A voucher", false,
            policy(10, RewardMethod.PLATFORM, "100", null, null, List.of(), 7));
        assertThat(cash.poolNeeded()).isEqualTo(5);
    }
}
