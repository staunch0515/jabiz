package com.jabiz.quizbuks;

/**
 * The permission codes of QuizBuks ({@code qb.<module>.<action>}, docs/quizbuks/02-design.md section 2). They are
 * granted only through the roles {@code QB_SETUP} creates ({@link com.jabiz.quizbuks.setup.QbRoles}); later phases
 * use them on their processes, datasets and templates.
 */
public final class QbPermissions {

    /** Run {@code QB_SETUP}: create the roles, ledger accounts, countries and business parameters. */
    public static final String SETUP = "qb.setup";
    /** Read the country dictionary (every role: profiles, publication regions, the admin pages). */
    public static final String COUNTRY_READ = "qb.country.read";

    /** Browse publications and answer quizzes. */
    public static final String PLAY = "qb.play";
    /** One's own profile, wallet, transactions and messages. */
    public static final String ME = "qb.me";
    /** Open one's own payout account. */
    public static final String PAYOUT_ONBOARD = "qb.payout.onboard";

    /** A sponsor's own sponsor profile and onboarding. */
    public static final String SPONSOR_ME = "qb.sponsor.me";
    /** Read one's own account: profile and sign-in records (takers and sponsors; Q2). */
    public static final String ACCOUNT_READ = "qb.account.read";
    /**
     * Read sponsors' avatars: the sponsor, the takers (the publisher of a quiz, Q5) and the administrators. A file
     * policy names one read permission, so the three groups share this one.
     */
    public static final String SPONSOR_AVATAR_READ = "qb.sponsor.avatar.read";
    /**
     * The process only the platform runs, as the system: applying an onboarding review
     * ({@code QB_SPONSOR_APPROVAL_RESULT}, run for the approval events; {@code internal()}, platform decision D41).
     * Granted to no role, as {@link #PUBLICATION_SYSTEM}.
     */
    public static final String SPONSOR_SYSTEM = "qb.sponsor.system";
    /** Edit quizzes, materials, questions and versions. */
    public static final String CONTENT_WRITE = "qb.content.write";
    /** Read the files of quiz content: covers, question and option images, material images, PDFs and audio. */
    public static final String CONTENT_FILE_READ = "qb.content.file.read";
    /**
     * The write permission of the QuizBuks datasets only processes write: the sponsors' read-only datasets and the
     * default ones of content and publications. A dataset must declare one; this one is granted to no role, so the
     * datasets stay unwritable through their API even if their policy were relaxed.
     */
    public static final String SPONSOR_VIEW_WRITE = "qb.content.view.write";
    /**
     * The content process run only as a sub-process: releasing the version files of a removed quiz
     * ({@code QB_QUIZ_RELEASE_FILES}, the subprocess of {@code QB_QUIZ_DELETE}; {@code subProcessOnly()}, platform
     * decision D41, so no sponsor starts it directly). Granted to no role (subprocesses are not checked for
     * permissions).
     */
    public static final String CONTENT_SYSTEM = "qb.content.system";
    /** Ask the AI for questions and covers. */
    public static final String AI_USE = "qb.ai.use";
    /** Create, submit, withdraw, pause, resume and copy one's own publications, and read them. */
    public static final String PUBLICATION_WRITE = "qb.publication.write";
    /**
     * The processes only the platform runs, as the system: moving publications on as time passes
     * ({@code QB_PUBLICATION_ADVANCE}, run for each item of the minute's job) and applying a review's result
     * ({@code QB_PUBLICATION_APPROVAL_RESULT}, run for the approval events). They are {@code internal()}: only the
     * system runs them (platform decision D41), whatever a caller's permissions. Granted to no role besides.
     */
    public static final String PUBLICATION_SYSTEM = "qb.publication.system";
    /**
     * The answering processes only the platform runs, as the system (Q5): finishing an attempt whose time ran out
     * ({@code QB_ATTEMPT_EXPIRE}), paying pending rewards ({@code QB_PENDING_SETTLE}, {@code QB_PENDING_PAY}) and
     * refreshing the completion counts ({@code QB_PUBLICATION_STATS_REFRESH}). Granted to no role, as
     * {@link #PUBLICATION_SYSTEM}: each of them reads its state again and is idempotent, so a call by a holder of
     * {@code *} changes nothing a run by the platform would not.
     */
    public static final String PLAY_SYSTEM = "qb.play.system";
    /**
     * Credit a sponsor's wallet without a payment, for development and tests before top-ups exist (Q5, D-Q5-8). The
     * process is registered only with {@code quizbuks.wallet.dev-credit.enabled=true}; granted to no role.
     */
    public static final String WALLET_DEV_CREDIT = "qb.wallet.dev-credit";
    /**
     * The top-up and reconciliation processes only the platform runs, as the system (Q6a): Stripe's events, the
     * results of the jobs that call Stripe, the stale top-ups' check and the daily reconciliation. They are
     * {@code internal()} (platform decision D41); granted to no role, as {@link #PLAY_SYSTEM}.
     */
    public static final String WALLET_SYSTEM = "qb.wallet.system";
    /**
     * The payout account, taker wallet and transfer processes only the platform runs, as the system (Q6b): Stripe's
     * events, the jobs' results, the daily transfers and their checks. {@code internal()}; granted to no role.
     */
    public static final String PAYOUT_SYSTEM = "qb.payout.system";
    /** Top up the sponsor wallet. */
    public static final String TOPUP = "qb.topup";
    /** A sponsor's own transactions and reports. */
    public static final String SPONSOR_FINANCE_READ = "qb.sponsor.finance.read";
    /** Notices to the takers of a sponsor's publications. */
    public static final String BROADCAST_WRITE = "qb.broadcast.write";

    /** Read users and their profiles. */
    public static final String ADMIN_USERS_READ = "qb.admin.users.read";
    /** Ban and unban users. */
    public static final String ADMIN_USERS_BAN = "qb.admin.users.ban";
    /** Read every sponsor's quizzes, their content and their versions (read only). */
    public static final String ADMIN_QUIZ_READ = "qb.admin.quiz.read";
    /** Read every sponsor's publications, their tiers and counters (read only). */
    public static final String ADMIN_PUBLICATION_READ = "qb.admin.publication.read";
    /** Review sponsor onboarding. */
    public static final String REVIEW_SPONSOR = "qb.review.sponsor";
    /** Review publications: the permission of the review level of the rule {@code qb.publication.review}. */
    public static final String REVIEW_PUBLICATION = "qb.review.publication";
    /**
     * Taking publications down ({@code QB_PUBLICATION_ADMIN_PAUSE / _RESUME / _END}, Q10b, review M2; with a second
     * factor always); later the FAQ, terms, privacy policy and platform announcements.
     */
    public static final String ADMIN_CONTENT_WRITE = "qb.admin.content.write";
    /** Read wallets, transfers and the finance reports. */
    public static final String ADMIN_FINANCE_READ = "qb.admin.finance.read";
    /** Review large transfers. */
    public static final String PAYOUT_REVIEW = "qb.payout.review";
    /** See and follow up the daily balance reconciliation. */
    public static final String RECONCILE = "qb.reconcile";
    /**
     * Export the next month's summary voucher of the ledger to the accounting system (Q9d, F-02 / F-03), and preview
     * it. Finance; the export asks for a second factor.
     */
    public static final String JOURNAL_EXPORT = "qb.journal.export";
    /** Record the voucher number the accounting system gave an exported voucher (Q9d, F-03). Finance. */
    public static final String JOURNAL_CONFIRM = "qb.journal.confirm";
    /**
     * The journal process run only as a sub-process of the export: one part of the detail
     * ({@code QB_JOURNAL_DETAIL_PART}, {@code subProcessOnly()}). Granted to no role.
     */
    public static final String JOURNAL_SYSTEM = "qb.journal.system";
    /**
     * A sponsor's analytics of their own publications (Q9e: the dashboard, trends, funnel and question statistics,
     * aggregates only). Sponsors; no administrator role (E-10).
     */
    public static final String STATS_READ = "qb.stats.read";
    /**
     * The analytics job's processes ({@code QB_ANALYTICS_REFRESH}, {@code internal()}; {@code QB_ANALYTICS_CHUNK},
     * {@code subProcessOnly()}) and their templates. Granted to no role.
     */
    public static final String STATS_SYSTEM = "qb.stats.system";

    /**
     * The messaging templates only processes run (Q7b: {@code qb.system.consent-state}, the server's check of consent
     * before money and contract actions). Granted to no role.
     */
    public static final String MESSAGING_SYSTEM = "qb.messaging.system";

    private QbPermissions() {}
}
