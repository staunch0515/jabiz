package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A publication's "referrals welcome" (Q70, docs/quizbuks/plans/Q70-referrals-and-rounds.md, D-Q70-10): set in the
 * draft, frozen at submission with the rest of the policy, copied with the publication and onto its card. A referrer
 * whose friend passes the publication within {@code days} of claiming the referral gets a one-off referral reward of
 * {@code amount} paid the {@code method} way, at most {@code quota} times. Kept as one canonical JSON column (keys
 * sorted) on the publication and the card; none means referrals are not welcome.
 *
 * @param quota            how many referrals may be rewarded (referred and rewarded ones count)
 * @param method           how the referral reward reaches the referrer ({@link RewardMethod#ALL})
 * @param amount           the Kudos (platform, cash) or the prize's value (redeem, digital prize), for the budget
 * @param description      what the prize is and where to collect it (redeem and digital prize)
 * @param referrerRequires what the referrer must have done on the publication first ({@link #REFERRER_REQUIREMENTS})
 * @param refereeRules     the sponsor's rules of who may be referred (codes of {@link RefereeRules})
 * @param days             how long after claiming the referee has to pass
 */
public record ReferralPolicy(int quota, String method, BigDecimal amount, String description,
    String referrerRequires, List<String> refereeRules, int days) {

    public static final String NONE = "NONE";
    public static final String ANSWERED = "ANSWERED";
    public static final String PASSED = "PASSED";
    public static final List<String> REFERRER_REQUIREMENTS = List.of(NONE, ANSWERED, PASSED);

    public static final int MIN_QUOTA = 1;
    public static final int MAX_QUOTA = 1000;
    public static final int DEFAULT_DAYS = 7;
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 30;

    public static final String REQUIREMENT_DICTIONARY = "urn:jabiz:dict:quizbuks:referrer-requirement";

    public ReferralPolicy {
        refereeRules = refereeRules == null ? List.of() : List.copyOf(refereeRules.stream().distinct().sorted()
            .toList());
        referrerRequires = referrerRequires == null ? NONE : referrerRequires;
    }

    /** The referral rewards at most paid out: quota times amount (D-Q70-13). */
    public BigDecimal maxSpend() {
        return amount == null ? BigDecimal.ZERO : amount.multiply(BigDecimal.valueOf(quota));
    }

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** The canonical JSON of the policy; null for none. */
    public static String toJson(ReferralPolicy policy) {
        if (policy == null) {
            return null;
        }
        return JSON.writeValueAsString(toMap(policy));
    }

    /** The policy as plain values (also the approval's content). */
    public static Map<String, Object> toMap(ReferralPolicy policy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("quota", policy.quota());
        map.put("method", policy.method());
        map.put("amount", policy.amount() == null ? null : policy.amount().toPlainString());
        map.put("description", policy.description());
        map.put("referrerRequires", policy.referrerRequires());
        map.put("refereeRules", policy.refereeRules());
        map.put("days", policy.days());
        return map;
    }

    /** The policy of a JSON column; null for none (an empty or missing column). */
    public static ReferralPolicy fromJson(Object json) {
        if (json == null || json.toString().isBlank()) {
            return null;
        }
        Map<String, Object> map = JSON.readValue(json.toString(), MAP);
        Object amount = map.get("amount");
        @SuppressWarnings("unchecked")
        List<String> rules = map.get("refereeRules") instanceof List<?> list ? (List<String>) list : List.of();
        return new ReferralPolicy(((Number) map.get("quota")).intValue(), (String) map.get("method"),
            amount == null ? null : new BigDecimal(amount.toString()), (String) map.get("description"),
            (String) map.get("referrerRequires"), rules, ((Number) map.get("days")).intValue());
    }

    /**
     * The problems of a policy as a submission sees them, under the paths {@code referral.<field>}: a quota and a
     * number of days in range, an amount above zero, a known method (the sponsor's allowance is checked like the
     * publication's, by the caller), a description for a prize, a known requirement and known rules; not for an
     * invited publication; a survey cannot require a pass (it has none).
     *
     */
    public static List<Violation> problems(ReferralPolicy policy, boolean invited, boolean survey) {
        List<String> knownRules = RefereeRules.codes();
        if (policy == null) {
            return List.of();
        }
        List<Violation> problems = new ArrayList<>();
        if (invited) {
            problems.add(new Violation("referral", PublishingCodes.REFERRAL_INVITED, "Referrals are for public "
                + "publications only", Map.of()));
        }
        if (policy.quota() < MIN_QUOTA || policy.quota() > MAX_QUOTA) {
            problems.add(new Violation("referral.quota", PublishingCodes.REFERRAL_QUOTA_RANGE, "The referral quota "
                + "must be between " + MIN_QUOTA + " and " + MAX_QUOTA, Map.of("min", MIN_QUOTA, "max", MAX_QUOTA)));
        }
        if (policy.days() < MIN_DAYS || policy.days() > MAX_DAYS) {
            problems.add(new Violation("referral.days", PublishingCodes.REFERRAL_DAYS_RANGE, "A referral lasts "
                + "between " + MIN_DAYS + " and " + MAX_DAYS + " days", Map.of("min", MIN_DAYS, "max", MAX_DAYS)));
        }
        if (policy.amount() == null || policy.amount().signum() <= 0
            || policy.amount().stripTrailingZeros().scale() > 0) {
            problems.add(new Violation("referral.amount", PublishingCodes.REFERRAL_AMOUNT_RANGE, "The referral "
                + "reward is a whole number of Kudos above zero", Map.of()));
        } else if (policy.maxSpend().compareTo(PublishingLimits.MAX_MONEY) > 0) {
            problems.add(new Violation("referral.amount", PublishingCodes.SPEND_RANGE, "The referral quota times "
                + "the reward come to more than " + PublishingLimits.MAX_MONEY, Map.of("max", PublishingLimits.MAX_MONEY)));
        }
        if (policy.method() == null || !RewardMethod.ALL.contains(policy.method())) {
            problems.add(new Violation("referral.method", PublishingCodes.REFERRAL_METHOD_INVALID, "Unknown referral "
                + "reward method " + policy.method(), Map.of("method", String.valueOf(policy.method()))));
        } else if ((RewardMethod.OFFLINE_REDEEM.equals(policy.method())
            || RewardMethod.DIGITAL_PRIZE.equals(policy.method()))
            && (policy.description() == null || policy.description().isBlank())) {
            problems.add(new Violation("referral.description", PublishingCodes.FIELD_REQUIRED,
                "referral.description is required", Map.of("field", "referral.description")));
        }
        if (!REFERRER_REQUIREMENTS.contains(policy.referrerRequires())) {
            problems.add(new Violation("referral.referrerRequires", PublishingCodes.REFERRAL_REQUIREMENT_INVALID,
                "Unknown referrer requirement " + policy.referrerRequires(), Map.of("referrerRequires",
                    policy.referrerRequires())));
        } else if (survey && PASSED.equals(policy.referrerRequires())) {
            problems.add(new Violation("referral.referrerRequires", PublishingCodes.REFERRAL_SURVEY_PASS, "A survey "
                + "has no pass: require an answer instead", Map.of()));
        }
        for (String rule : policy.refereeRules()) {
            if (!knownRules.contains(rule)) {
                problems.add(new Violation("referral.refereeRules", PublishingCodes.REFERRAL_RULE_INVALID, "Unknown "
                    + "referee rule " + rule, Map.of("rule", rule)));
            }
        }
        return List.copyOf(problems);
    }
}
