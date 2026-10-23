package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.identity.SponsorApprovals;
import com.jabiz.quizbuks.publishing.PublicationApprovals;
import com.jabiz.quizbuks.wallet.BankTopUps;
import com.jabiz.quizbuks.wallet.QbPayouts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval rules of QuizBuks (docs/quizbuks/plans/Q4-publishing.md, D-Q4-4). Rules change only through
 * controlled changes (platform docs/design/18-numbering-approvals-tasks.md section 3.5), so {@code QB_SETUP} proposes
 * each one that has never existed, as it proposes the controlled parameters, and another administrator publishes it.
 * Once proposed, a rule is not proposed again: an administrator may change, disable or delete it.
 */
public final class QbApprovalRules {

    /** A rule: its code, subject, condition and levels (as the platform writes them), priority and description. */
    public record Rule(String code, String subject, Map<String, Object> condition, List<Map<String, Object>> levels,
        int priority, String description) {
        // In a fixed order: the proposal stores them as text, and Map.of iterates differently in each JVM.
        public Rule {
            condition = ordered(condition);
            levels = levels.stream().map(QbApprovalRules::ordered).toList();
        }
    }

    /**
     * Publications are reviewed by a content administrator unless the controlled parameter
     * {@code qb.review.publication.auto} says they need not be (its value is the fact {@code autoApprove}).
     */
    public static final Rule PUBLICATION_REVIEW = new Rule(PublicationApprovals.RULE, PublicationApprovals.SUBJECT,
        entries("fact", "autoApprove", "op", "eq", "value", false),
        List.of(entries("permission", QbPermissions.REVIEW_PUBLICATION)), 100,
        "Publications are reviewed unless qb.review.publication.auto is on");

    /**
     * Sponsor onboarding is reviewed by a content administrator unless the controlled parameter
     * {@code qb.review.sponsor.auto} says it need not be (Q2, D-Q2-3).
     */
    public static final Rule SPONSOR_REVIEW = new Rule(SponsorApprovals.RULE, SponsorApprovals.SUBJECT,
        entries("fact", "autoApprove", "op", "eq", "value", false),
        List.of(entries("permission", QbPermissions.REVIEW_SPONSOR)), 100,
        "Sponsor onboarding is reviewed unless qb.review.sponsor.auto is on");

    /**
     * Transfers above {@code qb.transfer.review-above} wait for a finance administrator's review (Q6b, P-03); the
     * payee prepares the case, so they cannot approve their own.
     */
    public static final Rule TRANSFER_REVIEW = new Rule(QbPayouts.REVIEW_RULE, QbPayouts.APPROVAL_SUBJECT,
        entries("fact", "needsReview", "op", "eq", "value", true),
        List.of(entries("permission", QbPermissions.PAYOUT_REVIEW)), 100,
        "Transfers above qb.transfer.review-above are reviewed");

    /**
     * Every bank top-up finance records waits for another administrator's approval before it is credited (Q13a, B-1):
     * the recorder prepares the case, so they cannot approve their own.
     */
    public static final Rule BANK_TOPUP_REVIEW = new Rule(BankTopUps.REVIEW_RULE, BankTopUps.APPROVAL_SUBJECT,
        entries("fact", "bank", "op", "eq", "value", true),
        List.of(entries("permission", QbPermissions.TOPUP_BANK_APPROVE)), 100,
        "Bank top-ups are credited only after another administrator approves what finance recorded");

    public static final List<Rule> ALL = List.of(PUBLICATION_REVIEW, SPONSOR_REVIEW, TRANSFER_REVIEW,
        BANK_TOPUP_REVIEW);

    private static Map<String, Object> entries(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> ordered(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private QbApprovalRules() {}
}
