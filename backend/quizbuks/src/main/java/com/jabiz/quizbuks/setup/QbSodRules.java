package com.jabiz.quizbuks.setup;

import java.util.List;

/**
 * The separation of duties of QuizBuks (Q10b, review L3; platform docs/design/18-numbering-approvals-tasks.md section
 * 4): nobody holds a permission of both sides of a rule. Rules change only through controlled changes, so
 * {@code QB_SETUP} proposes each one that has never existed, as it proposes the approval rules, and another
 * administrator publishes it. {@link QbRoles} keeps its own roles apart accordingly: the super administrator, who
 * manages users and roles and proposes controlled changes, neither publishes them nor decides approvals.
 */
public final class QbSodRules {

    /** A rule: its code, the two sides (no permission on both) and its description. */
    public record Rule(String code, List<String> left, List<String> right, String description) {
        public Rule {
            left = List.copyOf(left);
            right = List.copyOf(right);
        }
    }

    /** Who proposes a controlled change does not publish one (four eyes on money and review switches). */
    public static final Rule CONTROL = new Rule("qb.sod.control-changes", List.of("control.propose"),
        List.of("control.publish"), "Who proposes controlled changes does not publish them");

    /**
     * Who manages roles and their holders neither publishes controlled changes nor decides approvals: otherwise they
     * could give themselves the other side.
     */
    public static final Rule SECURITY = new Rule("qb.sod.security-administration",
        List.of("security.role.write", "security.user-role.write"), List.of("control.publish", "approval.decide"),
        "Who manages roles and role assignments neither publishes controlled changes nor decides approvals");

    public static final List<Rule> ALL = List.of(CONTROL, SECURITY);

    private QbSodRules() {}
}
