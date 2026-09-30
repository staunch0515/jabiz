package com.jabiz.approval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Whether a case needs approval (docs/design/18-numbering-approvals-tasks.md section 3.3): the rules of the subject
 * in effect at the case's business time are tried by priority (then code); the first one whose condition holds
 * decides. None applies, or the one that applies has no levels: no approval is needed.
 *
 * @param matched   the rule that decided; null when none applied
 * @param evaluated every rule considered, in the order tried (their versions are recorded)
 */
public record ApprovalEvaluation(ApprovalRule matched, List<ApprovalRule> evaluated) {

    /** Order in which rules are tried. */
    public static final Comparator<ApprovalRule> ORDER = Comparator.comparingInt(ApprovalRule::priority)
        .thenComparing(ApprovalRule::ruleCode);

    public ApprovalEvaluation {
        evaluated = List.copyOf(evaluated);
    }

    /** Evaluates {@code rules} for the normalized {@code facts}. */
    public static ApprovalEvaluation evaluate(List<ApprovalRule> rules, Map<String, Object> facts) {
        Objects.requireNonNull(facts, "facts must not be null");
        List<ApprovalRule> ordered = rules.stream().sorted(ORDER).toList();
        ApprovalRule matched = ordered.stream().filter(rule -> rule.condition().test(facts)).findFirst().orElse(null);
        return new ApprovalEvaluation(matched, ordered);
    }

    /** Whether approval is needed. */
    public boolean required() {
        return matched != null && !matched.levels().isEmpty();
    }

    /** The levels needed; empty when no approval is. */
    public List<ApprovalLevel> levels() {
        return required() ? matched.levels() : List.of();
    }

    /** The versions considered, as recorded: {@code ruleId:versionNo}, in the order tried. */
    public List<String> versionKeys() {
        return evaluated.stream().map(ApprovalRule::versionKey).toList();
    }
}
