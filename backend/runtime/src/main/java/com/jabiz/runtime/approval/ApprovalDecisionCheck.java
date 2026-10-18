package com.jabiz.runtime.approval;

import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.EntityInstance;
import reactor.core.publisher.Mono;

/**
 * Platform use: what else a decision on a request of some subjects needs before {@code APPROVAL_DECIDE} records it
 * (docs/design/18-numbering-approvals-tasks.md section 3.4). The protected changes of security (decision D48) are
 * one: the user a change is for cannot approve it, the first administrator is retired once another exists, and the
 * decision needs a recent second factor. A check rejects in {@code ctx} (422) or fails (403, for example).
 */
public interface ApprovalDecisionCheck {

    /** Whether the check is about requests of {@code subject}. */
    boolean covers(String subject);

    /**
     * The permission deciding requests of the subjects this check covers needs instead of {@code approval.decide}
     * (decision D51: {@code security.protected-change.decide} for protected changes); null to keep
     * {@code approval.decide}. Its holders cannot decide other subjects, nor holders of {@code approval.decide} these.
     * {@code APPROVAL_DECIDE} also lets callers with this permission start ({@code orPermissions}).
     */
    default String decidePermission() {
        return null;
    }

    /**
     * @param request the request as loaded, pending
     * @param approve whether the decision approves (else it rejects)
     */
    Mono<Void> check(EntityInstance request, boolean approve, ProcessContext ctx);
}
