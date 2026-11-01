package com.jabiz.quizbuks.page;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.runtime.EntityInstance;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the review of a sponsor's page looks at and is bound to (Q60a). The one fact is whether pages go without review
 * (the onboarding's switch {@code qb.review.sponsor.auto}; never for a page taken down). The content is everything the
 * page shows of its own, and when it was last taken down, so that a page taken down is never approved by an earlier
 * approval of the same text. An approval counts only while the stored page hashes the same.
 *
 * <p>Since Q65b the page's logo and "About us" are the onboarding's avatar and introduction, reviewed with the
 * onboarding (docs/quizbuks/plans/Q65-start-and-profile.md section 1): the page's content holds only its own fields.
 */
public final class SponsorPageApprovals {

    /** The approval subject; its name in each language is the message {@code approval.subject.qb.sponsor-page}. */
    public static final String SUBJECT = "qb.sponsor-page";
    /** The rule {@code QB_SETUP} proposes: pages are reviewed unless the switch says they need not be. */
    public static final String RULE = "qb.sponsor-page.review";

    static ApprovalSubject subject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(QbSponsorPages.PAGE).bool("autoApprove"));
    }

    static Map<String, Object> facts(boolean autoApprove) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("autoApprove", autoApprove);
        return facts;
    }

    /** The content of the page as stored (files as their keys, the time as its instant). */
    static Map<String, Object> content(EntityInstance page) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : new String[] {"handle", "language", "headline", "cover", "website"}) {
            Object value = page.get(field);
            content.put(field, value == null ? null : String.valueOf(value));
        }
        // Q73c: the other languages' headlines are part of what is approved, only when the page offers any (so a page
        // of one language hashes as before).
        if (page.get("languages") != null) {
            content.put("languages", String.valueOf((Object) page.get("languages")));
            content.put("translations", page.get("translations") == null ? null
                : String.valueOf((Object) page.get("translations")));
        }
        Object takenDown = page.get("takenDownAt");
        content.put("takenDownAt", takenDown == null ? null : instant(takenDown).toString());
        return content;
    }

    static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    private static Instant instant(Object value) {
        return value instanceof Instant instant ? instant
            : value instanceof java.time.OffsetDateTime offset ? offset.toInstant() : Instant.parse(value.toString());
    }

    private SponsorPageApprovals() {
    }
}
