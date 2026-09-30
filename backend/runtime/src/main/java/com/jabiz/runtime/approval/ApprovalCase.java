package com.jabiz.runtime.approval;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One case of an approval subject, as a process gives it to {@link RequireApproval}.
 *
 * @param entityId     the document the approval is for (a journal entry's id)
 * @param facts        the facts the rules test; only facts the subject declares
 * @param content      what the approval is bound to: the approval stays valid while the content hashes the same
 *                     ({@link com.jabiz.approval.ContentHash})
 * @param businessTime when the case happened in business terms; the rules in effect then apply. Null: the
 *                     operation time
 * @param preparerId   who prepared the case (may not approve it); null: the acting user. A process continuing
 *                     after an approval, running as the system, passes the preparer of the approved request
 */
public record ApprovalCase(String entityId, Map<String, ?> facts, Map<String, ?> content, Instant businessTime,
    String preparerId) {

    public ApprovalCase {
        if (entityId == null || entityId.isBlank() || entityId.length() > 100) {
            throw new IllegalArgumentException("entityId must be 1 to 100 characters");
        }
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public static ApprovalCase of(Object entityId, Map<String, ?> facts, Map<String, ?> content) {
        return new ApprovalCase(String.valueOf(entityId), facts, content, null, null);
    }

    public ApprovalCase at(Instant time) {
        return new ApprovalCase(entityId, facts, content, time, preparerId);
    }

    public ApprovalCase preparedBy(String preparer) {
        return new ApprovalCase(entityId, facts, content, businessTime, preparer);
    }
}
