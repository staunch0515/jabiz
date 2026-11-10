package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.Violation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The shape of a challenge (F-S4a, docs/quizbuks/plans/impl/F-S4.md §2): a publication bound to a node of a map Sponsors
 * light, which the operator's Sponsors answer in the Sponsor app. Passing lights the node and nothing else: it pays
 * nothing (every tier 0, so no wallet moves and no budget is spent), is invited to nobody, welcomes no referrals,
 * charges no entry fee and shows no other Sponsor's shared video. The save reports these with its other problems and
 * the submission refuses them; the other rules (regions, audience criteria) a Sponsor's start does not read.
 */
public final class PublicationChallenges {

    private PublicationChallenges() {}

    /** Why the publication cannot be a challenge as it is ({@code fields}: the draft's or the publication's). */
    public static List<Violation> problems(Map<String, Object> fields, List<TierCoverage.Tier> tiers) {
        List<Violation> problems = new ArrayList<>();
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal reward = tiers.get(i).reward();
            if (reward != null && reward.signum() > 0) {
                problems.add(new Violation("tiers[" + i + "].reward", PublishingCodes.CHALLENGE_REWARD, "A challenge "
                    + "pays nothing: passing it lights the node", Map.of("tier", i + 1)));
            }
        }
        if (Audience.INVITED.equals(Audience.of(fields.get("audience")))) {
            problems.add(new Violation("audience", PublishingCodes.CHALLENGE_INVITED, "A challenge is open to every "
                + "Sponsor of the operator, not invited", Map.of()));
        }
        if (fields.get("referral") != null) {
            problems.add(new Violation("referral", PublishingCodes.CHALLENGE_REFERRAL, "A challenge welcomes no "
                + "referrals", Map.of()));
        }
        Object entryFee = fields.get("entryFee");
        if (entryFee instanceof BigDecimal fee && fee.signum() > 0) {
            problems.add(new Violation("entryFee", PublishingCodes.CHALLENGE_ENTRY_FEE, "A challenge charges no entry "
                + "fee", Map.of()));
        }
        if (fields.get("sharedVideoId") != null) {
            problems.add(new Violation("sharedVideoId", PublishingCodes.CHALLENGE_SHARED_VIDEO, "A challenge shows no "
                + "shared video", Map.of()));
        }
        return List.copyOf(problems);
    }
}
