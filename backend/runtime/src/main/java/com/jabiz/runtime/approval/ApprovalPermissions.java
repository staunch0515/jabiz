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
    /** Read SoD rules and the conflict report. */
    public static final String SOD_READ = "sod.read";

    private ApprovalPermissions() {}
}
