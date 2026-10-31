package com.jabiz.quizbuks.identity;

/** The lengths of account data (docs/quizbuks/plans/Q2-accounts.md, 实体与迁移); the migration's columns match. */
public final class AccountLimits {

    /** A person's name as QuizBuks shows it (taker or sponsor). */
    public static final int DISPLAY_NAME_LENGTH = 100;
    public static final int CITY_LENGTH = 100;
    public static final int COMPANY_LENGTH = 100;
    /** A sponsor's introduction: Markdown in one language, rendered by the frontends without raw HTML. */
    public static final int INTRO_LENGTH = 5_000;
    /** A reviewer's reason for sending an onboarding back, a ban's reason, the note of lifting it. */
    public static final int REASON_LENGTH = 500;
    /** User ids (the platform's are UUIDs); as wide as Q4's {@code sponsorId}. */
    public static final int USER_ID_LENGTH = 128;

    private AccountLimits() {}
}
