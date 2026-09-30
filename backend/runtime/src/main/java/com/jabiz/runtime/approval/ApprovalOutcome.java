package com.jabiz.runtime.approval;

import java.util.Arrays;

/**
 * What {@link RequireApproval} found for a case, put into the process context.
 *
 * @param status    the finding
 * @param requestId the request that is pending or approved; null when none is needed
 */
public record ApprovalOutcome(Status status, String requestId) {

    public enum Status {
        /** No rule requires approval (or the rule that applies has no levels). */
        NOT_REQUIRED,
        /** Approval is needed and the request (new, or earlier with the same content) awaits it. */
        PENDING,
        /** A request with the same content has been approved: the process may go on. */
        APPROVED;

        static String[] codes() {
            return Arrays.stream(values()).map(Enum::name).toArray(String[]::new);
        }
    }

    /** Whether the process may do what needs approval: approved, or no approval needed. */
    public boolean mayProceed() {
        return status != Status.PENDING;
    }
}
