package com.jabiz.quizbuks.publishing;

import com.jabiz.approval.ContentHash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the review of a publication looks at and is bound to (docs/quizbuks/plans/Q4-publishing.md, D-Q4-2, D-Q4-4).
 * The facts are what the approval rules test: whether publications go without review (the controlled parameter
 * {@code qb.review.publication.auto}, read at each submission; false whatever it says when an administrator took
 * down a publication of the same quiz), whether that is so ({@code takenDown}, Q10b review M-d), the highest reward,
 * the most it can pay out, the publisher (kind and id) and the regions. The content is everything the reviewer approves: the version (by its content hash) and
 * every part of the policy. An approval stays valid while the content hashes the same ({@link ContentHash}: key order
 * and number scale do not count), and a policy is frozen once submitted, so what goes live is what was approved.
 */
public final class PublicationApprovals {

    /** The approval subject of publications; its name in each language is the message {@code approval.subject.<name>}. */
    public static final String SUBJECT = "qb.publication";
    /** The rule {@code QB_SETUP} proposes: publications are reviewed unless the parameter says they need not be. */
    public static final String RULE = "qb.publication.review";

    /** The facts of a submission, as the subject declares them. */
    public static Map<String, Object> facts(boolean autoApprove, BigDecimal maxReward, BigDecimal maxSpend,
        String publisherId, String regions, boolean takenDown) {
        return facts(autoApprove, maxReward, maxSpend, PublisherKind.SPONSOR, publisherId, regions, takenDown,
            RewardMethod.PLATFORM);
    }

    /**
     * @param publisherKind who published (S0, {@link PublisherKind}): a rule may review each merchant system differently
     * @param rewardMethod  how the reward reaches the taker (Q22): a rule may review cash and prizes differently
     */
    public static Map<String, Object> facts(boolean autoApprove, BigDecimal maxReward, BigDecimal maxSpend,
        String publisherKind, String publisherId, String regions, boolean takenDown, String rewardMethod) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("rewardMethod", rewardMethod);
        facts.put("autoApprove", autoApprove);
        facts.put("takenDown", takenDown);
        facts.put("maxReward", maxReward);
        facts.put("maxSpend", maxSpend);
        facts.put("publisherKind", publisherKind);
        facts.put("publisherId", publisherId);
        facts.put("regions", regions);
        return facts;
    }

    /**
     * The content an approval is bound to: the version and the whole policy, the tiers in score order.
     *
     * @param versionContentHash the content hash of the version, so the approval names the content itself
     */
    public static Map<String, Object> content(PublicationReadiness.Draft draft, String versionContentHash,
        List<TierCoverage.Tier> tiers) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("versionId", draft.versionId() == null ? null : draft.versionId().toString());
        content.put("versionContentHash", versionContentHash);
        content.put("name", draft.name());
        content.put("regions", draft.regions());
        content.put("startAt", draft.startAt());
        content.put("endAt", draft.endAt());
        content.put("timeLimitSec", draft.timeLimitSec());
        content.put("passScore", draft.passScore());
        content.put("budgetCap", draft.budgetCap());
        content.put("maxWinners", draft.maxWinners());
        // Q22: only when the sponsor settles the reward themselves, so the publications paid by the platform (all of
        // them before Q22, some still waiting for their review) keep the hash their approval is bound to.
        // Q25: likewise only an invited publication names its audience, so earlier hashes stand.
        if (draft.invited()) {
            content.put("audience", draft.audience());
        }
        if (!RewardMethod.platform(draft.rewardMethod())) {
            content.put("rewardMethod", draft.rewardMethod());
            content.put("redeemDescription", draft.redeemDescription());
        }
        // Q33: what a digital prize is and how it is delivered (never its content: the reviewer sees no code).
        if (RewardMethod.DIGITAL_PRIZE.equals(draft.rewardMethod())) {
            content.put("digitalDelivery", draft.digitalDelivery());
            content.put("digitalDescription", draft.digitalDescription());
        }
        List<Object> ranges = new ArrayList<>();
        tiers.stream().sorted(Comparator.comparingInt(TierCoverage.Tier::fromScore)
                .thenComparingInt(TierCoverage.Tier::toScore))
            .forEach(tier -> {
                Map<String, Object> range = new LinkedHashMap<>();
                range.put("fromScore", tier.fromScore());
                range.put("toScore", tier.toScore());
                range.put("reward", tier.reward());
                ranges.add(range);
            });
        content.put("tiers", ranges);
        return content;
    }

    /** The hash of the content, as the platform computes it for the approval. */
    public static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    private PublicationApprovals() {}
}
