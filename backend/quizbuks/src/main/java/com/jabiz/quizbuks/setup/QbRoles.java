package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.QbPermissions;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The five roles of QuizBuks and their permissions (docs/quizbuks/02-design.md section 2); {@code QB_SETUP} creates
 * them. Each phase adds the permissions of what it builds here, and running {@code QB_SETUP} again grants them. No
 * role holds {@code ledger.post}, {@code ledger.reverse} or {@code ledger.account.write}: the ledger is written only
 * by the QuizBuks processes, as their subprocesses.
 */
public final class QbRoles {

    public static final String TAKER = "QB_TAKER";
    public static final String SPONSOR = "QB_SPONSOR";
    public static final String ADMIN_CONTENT = "QB_ADMIN_CONTENT";
    public static final String ADMIN_FINANCE = "QB_ADMIN_FINANCE";
    public static final String ADMIN_SUPER = "QB_ADMIN_SUPER";

    /**
     * A role: its code, its name in each language, whether its holders must have passed a second factor, and its
     * permissions.
     */
    public record Role(String code, Map<String, String> labels, boolean requireMfa, List<String> permissions) {
        public Role {
            labels = Map.copyOf(labels);
            permissions = List.copyOf(permissions);
        }
    }

    private static final List<String> CONTENT = List.of(QbPermissions.COUNTRY_READ, QbPermissions.ADMIN_USERS_READ,
        QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ, QbPermissions.ADMIN_PUBLICATION_READ,
        QbPermissions.REVIEW_SPONSOR, QbPermissions.REVIEW_PUBLICATION, QbPermissions.ADMIN_CONTENT_WRITE,
        QbPermissions.SPONSOR_AVATAR_READ, "approval.decide", "task.read",
        // Q8a: the AI page (usage and failures; the switch changes only through controlled changes).
        QbPermissions.ADMIN_AI_READ);

    private static final List<String> FINANCE = List.of(QbPermissions.COUNTRY_READ, QbPermissions.ADMIN_USERS_READ,
        QbPermissions.ADMIN_FINANCE_READ, QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.PAYOUT_REVIEW,
        QbPermissions.RECONCILE, QbPermissions.SPONSOR_AVATAR_READ, "ledger.read", "ledger.account.read", "report.issue", "approval.decide", "task.read",
        // Q6a: the jobs that call Stripe (their state and errors, never their secrets).
        "external-job.read",
        // Q9c (C-4): finance issues the monthly reports (report.issue above) and reads what was issued.
        "report.archive.read",
        // Q9d: the journal export, its confirmation and its archived CSV files.
        QbPermissions.JOURNAL_EXPORT, QbPermissions.JOURNAL_CONFIRM, "file.generated.read");

    /**
     * What the super administrator holds beyond the other two: bans, reading parameters, proposing controlled changes,
     * users and roles. No role writes parameters directly: the controlled ones change only through controlled changes
     * (platform decision D40), and the others are only the AI model and the oldest app version. The super
     * administrator proposes controlled changes but does not publish them: see {@link #PUBLISH}.
     */
    private static final List<String> SUPER = List.of(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN,
        "platform.param.read", "control.propose",
        "security.user.read", "security.user.write", "security.user.create", "security.user.password",
        "security.user.unlock", "security.user.mfa-reset", "security.user.identity.write",
        "security.role.read", "security.role.write", "security.user-role.read", "security.user-role.write",
        "security.menu.read", "security.menu.write", "security.login-record.read", "security.access-review.read");

    /**
     * Publishing controlled changes (money parameters, review switches; platform decision D40) belongs to the finance
     * administrator alone, so the super administrator who proposes them is never also the one who publishes. Kept
     * apart from {@link #FINANCE} because the super administrator's role is built from that list.
     */
    private static final List<String> PUBLISH = List.of("control.publish");

    /**
     * Stripe's events as received (Q6a, D-Q6-14): their bodies may hold payers' names and addresses, so only the finance
     * administrator reads them, not the super administrator built from {@link #FINANCE}.
     */
    private static final List<String> FINANCE_ONLY = List.of("inbound.read");

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        add(TAKER, labels("Quiz taker", "答题人", "回答者"), false, List.of(QbPermissions.COUNTRY_READ,
            QbPermissions.PLAY, QbPermissions.ME, QbPermissions.PAYOUT_ONBOARD, QbPermissions.ACCOUNT_READ,
            QbPermissions.SPONSOR_AVATAR_READ, QbPermissions.CONTENT_FILE_READ));
        add(SPONSOR, labels("Sponsor", "商家", "スポンサー"), false, List.of(QbPermissions.COUNTRY_READ,
            QbPermissions.SPONSOR_ME, QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ,
            QbPermissions.AI_USE, QbPermissions.PUBLICATION_WRITE, QbPermissions.TOPUP,
            QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.BROADCAST_WRITE, QbPermissions.ACCOUNT_READ,
            QbPermissions.SPONSOR_AVATAR_READ,
            // Q9d: the receipts of their own top-ups (the platform shows a sponsor only what they issued).
            "document.archive.read",
            // Q9e: the analytics of their own publications (aggregates only).
            QbPermissions.STATS_READ));
        add(ADMIN_CONTENT, labels("Content administrator", "内容管理员", "コンテンツ管理者"), true, CONTENT);
        add(ADMIN_FINANCE, labels("Finance administrator", "财务管理员", "財務管理者"), true, FINANCE, PUBLISH,
            FINANCE_ONLY);
        add(ADMIN_SUPER, labels("Super administrator", "超级管理员", "スーパー管理者"), true,
            withoutApprovals(CONTENT), withoutApprovals(FINANCE), SUPER);
    }

    /**
     * The super administrator manages roles and their holders, so they do not decide approvals (Q10b, review L3:
     * {@link QbSodRules#SECURITY}); the content and finance administrators do.
     */
    private static List<String> withoutApprovals(List<String> permissions) {
        return permissions.stream().filter(permission -> !APPROVAL_DECIDE.equals(permission)).toList();
    }

    private static final String APPROVAL_DECIDE = "approval.decide";

    private static Map<String, String> labels(String en, String zh, String ja) {
        return Map.of("en", en, "zh", zh, "ja", ja);
    }

    @SafeVarargs
    private static void add(String code, Map<String, String> labels, boolean requireMfa, List<String>... groups) {
        ROLES.put(code, new Role(code, labels, requireMfa,
            Arrays.stream(groups).flatMap(List::stream).distinct().toList()));
    }

    /** All roles, in the order of the design. */
    public static List<Role> all() {
        return List.copyOf(ROLES.values());
    }

    /** One role by its code. */
    public static Role of(String code) {
        Role role = ROLES.get(code);
        if (role == null) {
            throw new IllegalArgumentException("No QuizBuks role " + code);
        }
        return role;
    }

    private QbRoles() {}
}
