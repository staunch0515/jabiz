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
     * ({@code QB_SPONSOR_APPROVAL_RESULT}, run for the approval events). Granted to no role, as
     * {@link #PUBLICATION_SYSTEM} (platform decision D41 makes it system-only once implemented).
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
    /** Ask the AI for questions and covers. */
    public static final String AI_USE = "qb.ai.use";
    /** Create, submit, withdraw, pause, resume and copy one's own publications, and read them. */
    public static final String PUBLICATION_WRITE = "qb.publication.write";
    /**
     * The processes only the platform runs, as the system: moving publications on as time passes
     * ({@code QB_PUBLICATION_TICK}, {@code QB_PUBLICATION_ADVANCE}, run by the job) and applying a review's result
     * ({@code QB_PUBLICATION_APPROVAL_RESULT}, run for the approval events). Granted to no role: {@code internal()}
     * only hides a process from the catalog, its permission is still checked, so a role holding a permission it
     * shares (such as {@code qb.review.publication}) could call it directly.
     */
    public static final String PUBLICATION_SYSTEM = "qb.publication.system";
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
    /** FAQ, terms, privacy policy and platform announcements. */
    public static final String ADMIN_CONTENT_WRITE = "qb.admin.content.write";
    /** Read wallets, transfers and the finance reports. */
    public static final String ADMIN_FINANCE_READ = "qb.admin.finance.read";
    /** Review large transfers. */
    public static final String PAYOUT_REVIEW = "qb.payout.review";
    /** See and follow up the daily balance reconciliation. */
    public static final String RECONCILE = "qb.reconcile";

    private QbPermissions() {}
}
