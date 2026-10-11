package com.jabiz.quizbuks.identity;

import java.util.List;

/**
 * The rule and error codes of accounts (docs/quizbuks/plans/Q2-accounts.md), each with a message in the three
 * languages. Field rules are refused with 400, the processes' own refusals with 422.
 */
public final class AccountCodes {

    // Field rules (400).
    public static final String NAME_BLANK = "QB_PROFILE_NAME_BLANK";
    public static final String COUNTRY_FORMAT = "QB_PROFILE_COUNTRY_FORMAT";
    public static final String COMPANY_BLANK = "QB_SPONSOR_COMPANY_BLANK";

    // Profiles (422).
    public static final String COUNTRY_UNKNOWN = "QB_COUNTRY_UNKNOWN";
    public static final String PROFILE_CHANGED = "QB_PROFILE_CHANGED";

    // Sponsor onboarding (422).
    public static final String SPONSOR_ALREADY_APPROVED = "QB_SPONSOR_ALREADY_APPROVED";
    public static final String SPONSOR_NOT_APPROVED = "QB_SPONSOR_NOT_APPROVED";
    public static final String SPONSOR_TERMS_REQUIRED = "QB_SPONSOR_TERMS_REQUIRED";
    public static final String SPONSOR_CHANGED = "QB_SPONSOR_CHANGED";
    public static final String FILE_NOT_OWN = "QB_FILE_NOT_OWN";

    // Bans (422). A banned user's valuable actions are refused in the process too (D-Q2-4).
    public static final String USER_BANNED = "QB_USER_BANNED";
    public static final String BAN_SELF = "QB_BAN_SELF";
    public static final String BAN_ADMIN = "QB_BAN_ADMIN";
    public static final String BAN_STATE = "QB_BAN_STATE";

    /** Every code above. */
    public static final List<String> ALL = List.of(NAME_BLANK, COUNTRY_FORMAT, COMPANY_BLANK, COUNTRY_UNKNOWN,
        PROFILE_CHANGED, SPONSOR_ALREADY_APPROVED, SPONSOR_NOT_APPROVED, SPONSOR_TERMS_REQUIRED, SPONSOR_CHANGED,
        FILE_NOT_OWN, USER_BANNED, BAN_SELF, BAN_ADMIN, BAN_STATE);

    private AccountCodes() {}
}
