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
    /**
     * The results of the AI jobs and the daily expiry of candidates (Q8a): {@code internal()}, run by the platform as
     * the system. Granted to no role.
     */
    public static final String AI_SYSTEM = "qb.ai.system";
    /** The administrators' AI page (Q8a): usage by sponsor and month, failures, the switch and limits in force. */
    public static final String ADMIN_AI_READ = "qb.admin.ai.read";
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
     * Matching (Q47): reading every taker's profile attributes and counting them exactly. Granted to no role: the
     * count process ({@code QB_AUDIENCE_COUNT}) runs the count template itself and gives the sponsor a rounded number
     * only, so neither the template nor the attributes' default dataset is open to anybody directly.
     */
    public static final String AUDIENCE_SYSTEM = "qb.audience.system";
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
    /** Top up the sponsor wallet (by card, or by bank transfer: ask for one, report it sent, withdraw it). */
    public static final String TOPUP = "qb.topup";
    /** Record the money of a bank top-up found on the bank statement, for another's approval (Q13a). Finance. */
    public static final String TOPUP_BANK_RECORD = "qb.topup.bank.record";
    /**
     * Approve a recorded bank top-up: the permission of the level of the rule {@code qb.topup-bank.review} (Q13a; with
     * the platform's {@code approval.decide}). Content and finance administrators, never the one who recorded it.
     */
    public static final String TOPUP_BANK_APPROVE = "qb.topup.bank.approve";
    /**
     * Credit a sponsor's wallet by hand (Q18b): money received outside the system, or a grant, for another
     * administrator's approval (the rule {@code qb.topup-manual.review}, whose level is {@link #TOPUP_BANK_APPROVE}).
     * Finance.
     */
    public static final String TOPUP_MANUAL_RECORD = "qb.topup.manual.record";
    /** A sponsor's own transactions and reports. */
    public static final String SPONSOR_FINANCE_READ = "qb.sponsor.finance.read";
    /** Notices to the takers of a sponsor's publications. */
    public static final String BROADCAST_WRITE = "qb.broadcast.write";

    /** Read users and their profiles. */
    public static final String ADMIN_USERS_READ = "qb.admin.users.read";
    /** Ban and unban users. */
    public static final String ADMIN_USERS_BAN = "qb.admin.users.ban";
    /** Set the reward methods a sponsor may publish with (Q22). */
    public static final String ADMIN_REWARD_METHODS = "qb.admin.reward-methods";
    /** Resolve a disputed cash reward (Q22): confirm it paid, send it back to the sponsor, or void it. */
    public static final String CASH_RESOLVE = "qb.cash.resolve";
    /** Void a prize not yet collected (Q22b). */
    public static final String REDEMPTION_VOID = "qb.redemption.void";
    /** Resolve a disputed digital prize (Q33): delivered, not delivered (owed again), or void. */
    public static final String DIGITAL_RESOLVE = "qb.digital.resolve";
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
    /**
     * Transfer a taker's balance at once instead of waiting for the daily run, with the same checks (Q14). Finance.
     */
    public static final String TRANSFER_NOW = "qb.transfer.now";
    /**
     * See a taker's bank account number in plain text, one value at a time (Q13b: the platform's reveal, recorded), to
     * pay a withdrawal. Finance.
     */
    public static final String PAYOUT_BANK_REVEAL = "qb.payout.bank.reveal";
    /** Record an approved bank withdrawal as paid by the bank, or as returned (Q13b). Finance. */
    public static final String WITHDRAWAL_PAY = "qb.withdrawal.pay";
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
     * The analytics job's item process ({@code QB_ANALYTICS_ITEM}, {@code internal()}), its templates and its own
     * datasets. Granted to no role.
     */
    public static final String STATS_SYSTEM = "qb.stats.system";

    /**
     * The messaging templates only processes run (Q7b: {@code qb.system.consent-state}, the server's check of consent
     * before money and contract actions) and the mail processes run for QuizBuks's events (Q7a: {@code internal()}).
     * Granted to no role.
     */
    public static final String MESSAGING_SYSTEM = "qb.messaging.system";

    /**
     * An operator's member (Q48a, docs/quizbuks/plans/Q48-operators.md): the operator's own pages and templates, and
     * the operator's acts ({@code QB_OPERATOR_*}: taking publications down, banning sponsors, the lead's removal of a
     * member), each of which checks the caller's membership. The role {@code QB_OPERATOR} only.
     */
    public static final String OPERATOR_USE = "qb.operator.use";
    /** Read the operators, their members and which sponsors they run (headquarters). */
    public static final String OPERATORS_READ = "qb.admin.operators.read";
    /** Create and change operators, name and remove their members, move sponsors between them. Super administrator. */
    public static final String OPERATORS_WRITE = "qb.admin.operators.write";
    /** Spot-check the reviews operators decided (D-Q48-5). Content administrator (and the super administrator). */
    public static final String SPOT_CHECK = "qb.admin.spot-check";

    /**
     * AI prospecting (Q48c): the owner's settings, directions of search, approving or discarding the drafted mails,
     * and blocking addresses. The owner is the caller's operator, or headquarters for those holding
     * {@link #ADMIN_PROSPECT}. Operators' members and the content administrator.
     */
    public static final String PROSPECT_USE = "qb.prospect.use";
    /** Headquarters' own prospecting, and reading every owner's prospects, usage and blocks. Content administrator. */
    public static final String ADMIN_PROSPECT = "qb.admin.prospect";
    /** The prospecting jobs' items and outcomes ({@code internal()}). Granted to no role. */
    public static final String PROSPECT_SYSTEM = "qb.prospect.system";

    private QbPermissions() {}
}
