package com.jabiz.quizbuks.identity;

import com.jabiz.approval.ContentHash;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the review of a sponsor's onboarding looks at and is bound to (docs/quizbuks/plans/Q2-accounts.md, D-Q2-3).
 * The one fact is whether onboarding goes without review (the controlled parameter {@code qb.review.sponsor.auto},
 * read at each application). The content is everything the reviewer approves, all of it on the onboarding itself: the
 * sponsor's name ({@code contactName}), the company, the introduction, the avatar, when the terms were first accepted
 * and which version of the sponsors' terms was accepted (Q7b). An approval counts only while the content hashes the
 * same ({@link ContentHash}). The same application again under the same terms is the same request.
 */
public final class SponsorApprovals {

    /** The approval subject; its name in each language is the message {@code approval.subject.qb.sponsor}. */
    public static final String SUBJECT = "qb.sponsor";
    /** The rule {@code QB_SETUP} proposes: onboarding is reviewed unless the parameter says it need not be. */
    public static final String RULE = "qb.sponsor.review";

    /** The facts of an application, as the subject declares them. */
    public static Map<String, Object> facts(boolean autoApprove) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("autoApprove", autoApprove);
        return facts;
    }

    /**
     * The content an approval is bound to. The time is written as its instant and the avatar as its key, so the
     * content read back from storage hashes as the content given.
     */
    public static Map<String, Object> content(String contactName, String company, String intro, UUID avatar,
        Instant termsAcceptedAt, Integer termsVersionNo) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("contactName", contactName);
        content.put("company", company);
        content.put("intro", intro);
        content.put("avatar", avatar == null ? null : avatar.toString());
        content.put("termsAcceptedAt", termsAcceptedAt == null ? null : termsAcceptedAt.toString());
        // The version of the sponsors' terms accepted with the application (Q7b). Without one (no version in effect,
        // or an application of before V12) the key is left out, so that the content hashes as before Q7b and the
        // requests then pending still bind it (review L2).
        if (termsVersionNo != null) {
            content.put("termsVersionNo", termsVersionNo);
        }
        return content;
    }

    /** The hash of the content, as the platform computes it for the approval. */
    public static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    private SponsorApprovals() {}
}
