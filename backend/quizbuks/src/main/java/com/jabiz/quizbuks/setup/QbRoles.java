package com.jabiz.quizbuks.setup;

import com.jabiz.quizbuks.QbPermissions;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The roles of QuizBuks and their permissions (docs/quizbuks/02-design.md section 2); {@code QB_SETUP} creates
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
     * An operator's member (Q48a): the operator's back office ({@code /operator/}, entry {@code operator}), with a
     * second factor always. A protected grant (it decides approvals): given through the platform's four eyes.
     */
    public static final String OPERATOR = "QB_OPERATOR";
    /** A staff member of a Shopify shop (QuizBuks for Shopify, S1): the embedded pages only (entry shopify). */
    public static final String SHOPIFY_SHOP = com.jabiz.quizbuks.shopify.Shopify.ROLE;

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

    /**
     * Headquarters' content administrators (F-S1): monitoring, the global rules and emergencies only. The day-to-day
     * running of sponsors (reviews, reward methods, disputes, prospecting, certifications) is the operators': the
     * headquarters staff who do it are members of the platform operator ({@code QB_OPERATOR}).
     */
    private static final List<String> CONTENT = List.of(QbPermissions.COUNTRY_READ, QbPermissions.ADMIN_USERS_READ,
        QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ, QbPermissions.ADMIN_PUBLICATION_READ,
        // The platform's emergency acts: taking a publication down, pausing it (F-S1: platform first).
        QbPermissions.ADMIN_CONTENT_WRITE,
        QbPermissions.SPONSOR_AVATAR_READ, "task.read",
        // The second person on finance's records (bank top-ups, credits by hand, fees by hand: the rules' approvers
        // hold TOPUP_BANK_APPROVE below). Reviews are not theirs: those rules want REVIEW_*, which only operators hold.
        "approval.decide",
        // Q8a: the AI page (usage and failures; the switch changes only through controlled changes).
        QbPermissions.ADMIN_AI_READ,
        // Platform 16y: closing by hand, with a note, a task no process will close (never an approval's).
        "task.resolve",
        // Q13a: the second person on a bank top-up finance recorded.
        QbPermissions.TOPUP_BANK_APPROVE,
        // Q48a: the operators, and the spot checks of the reviews they decided.
        QbPermissions.OPERATORS_READ, QbPermissions.SPOT_CHECK,
        // Q69: reading every operator's certification rounds and records (F-S1: managing them is the operators').
        QbPermissions.CERTIFICATION_ADMIN,
        // Q48c: every owner's prospects (read only: prospecting is the operators').
        QbPermissions.ADMIN_PROSPECT,
        // Q70: every referral (support: who referred whom, and what came of it).
        QbPermissions.REFERRAL_READ,
        // Q82: every conversation between takers and sponsors (disputes and reports).
        QbPermissions.CONVERSATION_READ,
        // Q47c: which kinds of audience rule sponsors may use and which are charged for.
        QbPermissions.AUDIENCE_RULES_WRITE,
        // Q89: every sponsored benefit, conversation between sponsors and purchase order (disputes).
        QbPermissions.BENEFIT_READ);

    /**
     * What an operator's member holds (Q48a, D-Q48-4): reviewing sponsors and publications (final, with the platform's
     * approval panel), reading quizzes and publications, the disputes that move no money (prizes collected at a
     * sponsor's place, digital prizes), and the operator's own acts and pages. Not users and their profiles (only the
     * operator's sponsors, through its own templates), finance, the legal texts, parameters, roles or the investors'
     * figures.
     */
    private static final List<String> OPERATOR_PERMISSIONS = List.of(QbPermissions.COUNTRY_READ,
        QbPermissions.OPERATOR_USE, QbPermissions.ADMIN_QUIZ_READ, QbPermissions.CONTENT_FILE_READ,
        QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.REVIEW_SPONSOR, QbPermissions.REVIEW_PUBLICATION,
        QbPermissions.SPONSOR_AVATAR_READ, "approval.decide", "task.read", QbPermissions.REDEMPTION_VOID,
        QbPermissions.DIGITAL_RESOLVE,
        // F-S1: which reward methods its sponsors may use (headquarters' before).
        QbPermissions.ADMIN_REWARD_METHODS,
        // Q48c: the operator's own AI prospecting (its own directions, drafts and blocks).
        QbPermissions.PROSPECT_USE,
        // Q69: the rounds of its official publisher, and grants and revocations, in its own name.
        QbPermissions.CERTIFICATION_MANAGE,
        // Q82: its sponsors' conversations with takers (disputes and reports).
        QbPermissions.CONVERSATION_READ,
        // Q87: how its sponsors pay the platform's fees (prepaid, or postpaid within a credit limit).
        QbPermissions.FEE_BILLING);

    private static final List<String> FINANCE = List.of(QbPermissions.COUNTRY_READ, QbPermissions.ADMIN_USERS_READ,
        QbPermissions.ADMIN_FINANCE_READ, QbPermissions.ADMIN_PUBLICATION_READ, QbPermissions.PAYOUT_REVIEW,
        QbPermissions.RECONCILE, QbPermissions.SPONSOR_AVATAR_READ, "ledger.read", "ledger.account.read", "report.issue", "approval.decide", "task.read",
        // Q6a: the jobs that call Stripe (their state and errors, never their secrets).
        "external-job.read",
        // Q9c (C-4): finance issues the monthly reports (report.issue above) and reads what was issued.
        "report.archive.read",
        // Q9d: the journal export, its confirmation and its archived CSV files.
        QbPermissions.JOURNAL_EXPORT, QbPermissions.JOURNAL_CONFIRM, "file.generated.read",
        // Q48b: paying the operators' settlements (another administrator approves), and their statements.
        QbPermissions.OPERATOR_SETTLEMENT_PAY, "document.archive.read",
        // Platform 16y: closing by hand, with a note, a reconciliation task that needs nothing (TASK_RESOLVE).
        "task.resolve",
        // Q13a: bank top-ups, recorded from the statement by one administrator and approved by another.
        QbPermissions.TOPUP_BANK_RECORD, QbPermissions.TOPUP_BANK_APPROVE,
        // Q18b: manual credits, recorded by one administrator and approved by another.
        QbPermissions.TOPUP_MANUAL_RECORD,
        // Q14: a taker's transfer at once, with the daily run's checks.
        QbPermissions.TRANSFER_NOW,
        // Q13b: bank withdrawals, paid by hand from the account number revealed one at a time.
        QbPermissions.PAYOUT_BANK_REVEAL, QbPermissions.WITHDRAWAL_PAY,
        // Q70: the referrals, whose rewards the monthly report counts.
        QbPermissions.REFERRAL_READ,
        // Q47c: headquarters' price lists of the audience rules, their own and the operators'.
        QbPermissions.AUDIENCE_PRICES_WRITE,
        // Q87: the platform's fees (charged by hand and voided with another administrator's approval) and the
        // sponsors' billing mode.
        QbPermissions.FEE_MANAGE, QbPermissions.FEE_BILLING);

    /**
     * What the super administrator holds beyond the other two: bans, reading parameters, proposing controlled changes,
     * users and roles. No role writes parameters directly: the controlled ones change only through controlled changes
     * (platform decision D40), and the others (the oldest app version, the analytics' minimum group, the support
     * address, the days AI candidates wait and the AI prices of the estimate) are not written by any role. The super
     * administrator proposes controlled changes but does not publish them: see {@link #PUBLISH}.
     */
    private static final List<String> SUPER = List.of(QbPermissions.SETUP, QbPermissions.ADMIN_USERS_BAN,
        "platform.param.read", "control.propose",
        "security.user.read", "security.user.write", "security.user.create", "security.user.password",
        "security.user.unlock", "security.user.mfa-reset", "security.user.identity.write",
        "security.role.read", "security.role.write", "security.user-role.read", "security.user-role.write",
        "security.menu.read", "security.menu.write", "security.login-record.read", "security.access-review.read",
        // Platform 16u (decision D51): super administrators are the administrators of the four eyes on protected
        // grants (they hold security.user-role.write), so one approves what another requests, with the permission
        // made for that alone (not approval.decide, which QbSodRules#SECURITY keeps from them). approval.read lets
        // them check the facts of the request; it is on no side of a separation of duties.
        "security.protected-change.decide", "approval.read",
        // Q48a: operators, their members and which sponsors they run.
        QbPermissions.OPERATORS_WRITE);

    /**
     * Publishing controlled changes (money parameters, review switches; platform decision D40) belongs to the finance
     * administrator alone, so the super administrator who proposes them is never also the one who publishes. Kept
     * apart from {@link #FINANCE} because the super administrator's role is built from that list. Publishing needs
     * reading the changes too: the controlled changes ({@code SysControlChange}), and the approval rules and limits they
     * change, are read with {@code approval.read}, and without it the change to publish is nowhere to be seen.
     */
    private static final List<String> PUBLISH = List.of("control.publish", "approval.read");

    /**
     * Stripe's events as received (Q6a, D-Q6-14): their bodies may hold payers' names and addresses, so only the finance
     * administrator reads them, not the super administrator built from {@link #FINANCE}.
     */
    private static final List<String> FINANCE_ONLY = List.of("inbound.read");

    private static final Map<String, Role> ROLES = new LinkedHashMap<>();

    static {
        add(TAKER, labels("Taker", "学者", "テイカー"), false, List.of(QbPermissions.COUNTRY_READ,
            QbPermissions.PLAY, QbPermissions.ME, QbPermissions.PAYOUT_ONBOARD, QbPermissions.ACCOUNT_READ,
            QbPermissions.SPONSOR_AVATAR_READ, QbPermissions.CONTENT_FILE_READ,
            // Q68: a sponsor's own figures and publications in the app, and pausing or resuming them.
            QbPermissions.SPONSOR_APP,
            // Q77: the PDFs of their own certificates (the platform shows a reader only what they issued).
            "document.archive.read",
            // Q82: writing to the sponsors of the publications they answered.
            QbPermissions.CONVERSATION_USE));
        add(SPONSOR, labels("Sponsor", "发起方", "主催者"), false, List.of(QbPermissions.COUNTRY_READ,
            QbPermissions.SPONSOR_ME, QbPermissions.CONTENT_WRITE, QbPermissions.CONTENT_FILE_READ,
            QbPermissions.AI_USE, QbPermissions.PUBLICATION_WRITE, QbPermissions.TOPUP,
            QbPermissions.SPONSOR_FINANCE_READ, QbPermissions.BROADCAST_WRITE, QbPermissions.ACCOUNT_READ,
            QbPermissions.SPONSOR_AVATAR_READ,
            // Q9d: the receipts of their own top-ups (the platform shows a sponsor only what they issued).
            "document.archive.read",
            // Q9e: the analytics of their own publications (aggregates only).
            QbPermissions.STATS_READ,
            // Q68: the dashboard, the trend, pausing and resuming are shared with the app (see SPONSOR_APP).
            QbPermissions.SPONSOR_APP,
            // Q82: answering their takers' conversations.
            QbPermissions.CONVERSATION_USE,
            // Q89: sponsored benefits, as a provider or a sponsor (the certifications decide which).
            QbPermissions.BENEFIT_USE));
        add(SHOPIFY_SHOP, labels("Shopify shop staff", "Shopify 店铺员工", "Shopify ストアのスタッフ"), false,
            List.of(com.jabiz.quizbuks.shopify.Shopify.USE));
        add(ADMIN_CONTENT, labels("Content administrator", "内容管理员", "コンテンツ管理者"), true, CONTENT);
        add(ADMIN_FINANCE, labels("Finance administrator", "财务管理员", "財務管理者"), true, FINANCE, PUBLISH,
            FINANCE_ONLY);
        add(ADMIN_SUPER, labels("Super administrator", "超级管理员", "スーパー管理者"), true,
            withoutApprovals(CONTENT), withoutApprovals(FINANCE), SUPER);
        add(OPERATOR, labels("Operator", "运营商", "運営パートナー"), true, OPERATOR_PERMISSIONS);
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
