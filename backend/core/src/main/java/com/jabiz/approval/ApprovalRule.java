package com.jabiz.approval;

import java.util.List;
import java.util.Objects;

/**
 * One version of an approval rule, as the evaluation sees it (docs/design/18-numbering-approvals-tasks.md
 * section 3.2).
 *
 * @param ruleId    the rule entity's id
 * @param versionNo the version evaluated; recorded with every evaluation
 * @param ruleCode  the rule's code; breaks ties of priority
 * @param condition when the rule applies
 * @param levels    the approvals needed when it applies, in order; none: no approval is needed
 * @param priority  lower first; the first rule that applies decides
 */
public record ApprovalRule(String ruleId, long versionNo, String ruleCode, ApprovalCondition condition,
    List<ApprovalLevel> levels, int priority) {

    public ApprovalRule {
        Objects.requireNonNull(ruleId, "ruleId must not be null");
        Objects.requireNonNull(ruleCode, "ruleCode must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        levels = List.copyOf(levels);
    }

    /** {@code ruleId:versionNo}, as recorded. */
    public String versionKey() {
        return ruleId + ":" + versionNo;
    }
}
