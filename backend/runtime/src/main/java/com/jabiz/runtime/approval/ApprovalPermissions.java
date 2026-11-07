package com.jabiz.runtime.approval;

/** Permission codes of approvals and controlled changes (docs/design/18-numbering-approvals-tasks.md section 3). */
public final class ApprovalPermissions {

    /** Read rules, limits, requests, decisions and evaluations; preview the impact of a draft rule. */
    public static final String READ = "approval.read";
    /** Run {@code APPROVAL_DECIDE}; each level also needs its own permission. */
    public static final String DECIDE = "approval.decide";
    /** Propose a change of an approval rule, approver limit or SoD rule ({@code CONTROL_CHANGE_PROPOSE}). */
    public static final String CONTROL_PROPOSE = "control.propose";
    /** Publish another person's proposed change ({@code CONTROL_CHANGE_PUBLISH}). */
    public static final String CONTROL_PUBLISH = "control.publish";
    /**
     * Move pending approval requests and their open tasks to another value of their entry scope
     * ({@code APPROVAL_SCOPE_MOVE}, decision D76). The process is {@code subProcessOnly()}: an application's own
     * reassignment process calls it, and its caller needs this permission as well as the application's own.
     */
    public static final String SCOPE_MOVE = "approval.scope.move";
    /** Read SoD rules and the conflict report. */
    public static final String SOD_READ = "sod.read";

    private ApprovalPermissions() {}
}
