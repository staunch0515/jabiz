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
    /** Edit quizzes, materials, questions and versions. */
    public static final String CONTENT_WRITE = "qb.content.write";
    /** Read the files of quiz content: covers, question and option images, material images, PDFs and audio. */
    public static final String CONTENT_FILE_READ = "qb.content.file.read";
    /**
     * The write permission of the sponsors' read-only content datasets. A dataset must declare one; this one is
     * granted to no role, so the datasets stay unwritable even if their read-only policy were dropped.
     */
    public static final String SPONSOR_VIEW_WRITE = "qb.content.view.write";
    /** Ask the AI for questions and covers. */
    public static final String AI_USE = "qb.ai.use";
    /** Create, submit, pause and resume publications. */
    public static final String PUBLICATION_WRITE = "qb.publication.write";
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
    /** Review sponsor onboarding. */
    public static final String REVIEW_SPONSOR = "qb.review.sponsor";
    /** Review publications. */
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
