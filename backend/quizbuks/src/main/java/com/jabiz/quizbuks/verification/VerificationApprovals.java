package com.jabiz.quizbuks.verification;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.runtime.EntityInstance;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the review of a company verification looks at and is bound to (F-M3): every verification is reviewed, by
 * another member of the Sponsor's operator (the rule {@value #RULE} {@code QB_SETUP} proposes, approvers holding
 * {@code qb.review.sponsor}; the case is in the operator's entry scope, the Sponsor prepared it). The one fact,
 * {@code verification}, is always true: nothing skips the review. The content is the company's name and its domain,
 * with the Sponsor and how the domain was proven.
 */
public final class VerificationApprovals {

    /** The approval subject; its name in each language is the message {@code approval.subject.qb.sponsor-verification}. */
    public static final String SUBJECT = "qb.sponsor-verification";
    public static final String RULE = "qb.sponsor-verification.review";

    static ApprovalSubject subject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(QbVerifications.VERIFICATION).bool("verification"));
    }

    static Map<String, Object> facts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("verification", true);
        return facts;
    }

    static Map<String, Object> content(EntityInstance row) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : new String[] {"sponsorId", "companyName", "domain", "method"}) {
            Object value = row.get(field);
            content.put(field, value == null ? null : String.valueOf(value));
        }
        return content;
    }

    static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    private VerificationApprovals() {
    }
}
