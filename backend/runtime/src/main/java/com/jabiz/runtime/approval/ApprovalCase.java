package com.jabiz.runtime.approval;

import com.jabiz.context.EntryScope;

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
 * @param reference    how people know the document (a journal entry's number, at most 100 characters), shown in
 *                     the approvers' task instead of the id; null: the id
 * @param scope        the entry scope the request belongs to (decision D64): only that scope's sessions, and sessions
 *                     without a scope, see and decide it; null: the preparing session's scope, if it has one
 */
public record ApprovalCase(String entityId, Map<String, ?> facts, Map<String, ?> content, Instant businessTime,
    String preparerId, String reference, EntryScope scope) {

    public ApprovalCase {
        if (entityId == null || entityId.isBlank() || entityId.length() > 100) {
            throw new IllegalArgumentException("entityId must be 1 to 100 characters");
        }
        Objects.requireNonNull(facts, "facts must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (reference != null && (reference.isBlank() || reference.length() > 100)) {
            throw new IllegalArgumentException("reference must be 1 to 100 characters");
        }
    }

    public ApprovalCase(String entityId, Map<String, ?> facts, Map<String, ?> content, Instant businessTime,
        String preparerId, String reference) {
        this(entityId, facts, content, businessTime, preparerId, reference, null);
    }

    public ApprovalCase(String entityId, Map<String, ?> facts, Map<String, ?> content, Instant businessTime,
        String preparerId) {
        this(entityId, facts, content, businessTime, preparerId, null);
    }

    public static ApprovalCase of(Object entityId, Map<String, ?> facts, Map<String, ?> content) {
        return new ApprovalCase(String.valueOf(entityId), facts, content, null, null);
    }

    public ApprovalCase at(Instant time) {
        return new ApprovalCase(entityId, facts, content, time, preparerId, reference, scope);
    }

    public ApprovalCase preparedBy(String preparer) {
        return new ApprovalCase(entityId, facts, content, businessTime, preparer, reference, scope);
    }

    /** The case known to people as {@code reference} (see the parameter). */
    public ApprovalCase reference(String reference) {
        return new ApprovalCase(entityId, facts, content, businessTime, preparerId, reference, scope);
    }

    /** The request belongs to the entry scope {@code name}'s value {@code value} (decision D64). */
    public ApprovalCase scope(String name, String value) {
        return new ApprovalCase(entityId, facts, content, businessTime, preparerId, reference,
            new EntryScope(name, value));
    }
}
