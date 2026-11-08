package com.jabiz.quizbuks.sharedvideo;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.runtime.EntityInstance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the review of a shared video looks at and is bound to (F-S12): every shared video is reviewed once, by the
 * provider's operator (the rule {@value #RULE} {@code QB_SETUP} proposes, approvers holding
 * {@code qb.review.publication}; the case is in the operator's entry scope). The one fact, {@code shared}, is always
 * true: there is no switch that skips the review. The content is all that was shared (it never changes afterwards).
 */
public final class SharedVideoApprovals {

    /** The approval subject; its name in each language is the message {@code approval.subject.qb.shared-video}. */
    public static final String SUBJECT = "qb.shared-video";
    public static final String RULE = "qb.shared-video.review";

    static ApprovalSubject subject() {
        return ApprovalSubject.define(SUBJECT, s -> s.entity(QbSharedVideos.VIDEO).bool("shared"));
    }

    static Map<String, Object> facts() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("shared", true);
        return facts;
    }

    /** The content of a share (as stored, or as about to be stored). */
    static Map<String, Object> content(Map<String, Object> values) {
        Map<String, Object> content = new LinkedHashMap<>();
        for (String field : new String[] {"assetId", "providerId", "url", "durationSec", "vertical", "topics"}) {
            Object value = values.get(field);
            content.put(field, value == null ? null : String.valueOf(value));
        }
        Object title = values.get("title");
        content.put("title", title instanceof Map<?, ?> map ? new TreeMap<Object, Object>(map) : title == null ? null
            : String.valueOf(title));
        return content;
    }

    static Map<String, Object> content(EntityInstance video) {
        Map<String, Object> values = new LinkedHashMap<>(video.attributes());
        Object duration = values.get("durationSec");
        if (duration instanceof java.math.BigDecimal decimal) {
            values.put("durationSec", decimal.stripTrailingZeros().toPlainString());
        }
        return content(values);
    }

    static String hash(Map<String, ?> content) {
        return ContentHash.of(content);
    }

    private SharedVideoApprovals() {
    }
}
