package com.jabiz.quizbuks.setup;

import java.util.List;
import java.util.Map;

/**
 * The business parameters of QuizBuks with their kinds and first values (docs/quizbuks/plans/Q1-skeleton.md item 6).
 * {@code QB_SETUP} declares each one that does not exist yet through {@code PARAM_CREATE}; afterwards their values
 * change through the platform's parameter processes, so a value set by an administrator is never reset.
 *
 * <p>The parameters of {@link #CONTROLLED} change only with four eyes (platform decision D40): money (the commission,
 * the transfer threshold and review limit, transfers on or off) and the switches that skip a person's review. Even
 * their first values are created through a controlled change: {@code QB_SETUP} proposes them and another
 * administrator publishes the proposals.
 */
public final class QbParams {

    /** The platform's commission on each reward, as a fraction (0.1000 = 10 %); none for now. */
    public static final String CREATOR_FEE_RATE = "qb.creator-fee-rate";
    /** The balance at which a user's rewards are transferred automatically. */
    public static final String TRANSFER_THRESHOLD = "qb.transfer.threshold";
    /** Transfers above this amount wait for a person's review. */
    public static final String TRANSFER_REVIEW_ABOVE = "qb.transfer.review-above";
    /** Whether the daily automatic transfers run at all. */
    public static final String TRANSFER_ENABLED = "qb.transfer.enabled";
    /** The platform's fee on each transfer, in Kudos; 0 switches it off (Q6b, requirement 5.3-14). */
    public static final String TRANSFER_FEE = "qb.transfer.fee";
    /**
     * How long, in days, a reward is held back from transfers after it is posted (Q10b, review M3): a disputed payment
     * comes to light within it, before the money left.
     */
    public static final String TRANSFER_HOLD_DAYS = "qb.transfer.hold-days";
    /** How long, in days, from a sponsor's first paid top-up, the new sponsors' limit applies (Q10b, M3). */
    public static final String TOPUP_NEW_SPONSOR_DAYS = "qb.topup.new-sponsor.days";
    /** What a new sponsor may top up in all within that time, in Kudos (Q10b, M3). */
    public static final String TOPUP_NEW_SPONSOR_MAX_TOTAL = "qb.topup.new-sponsor.max-total";
    /** The smallest top-up, in Kudos (Q6a). */
    public static final String TOPUP_MIN = "qb.topup.min";
    /** The largest top-up, in Kudos (Q6a). */
    public static final String TOPUP_MAX = "qb.topup.max";
    /** Whether sponsor onboarding is approved without a person's review. */
    public static final String REVIEW_SPONSOR_AUTO = "qb.review.sponsor.auto";
    /** Whether publications are approved without a person's review. */
    public static final String REVIEW_PUBLICATION_AUTO = "qb.review.publication.auto";
    /** The OpenAI model of AI generation. */
    public static final String AI_MODEL = "qb.ai.model";
    /** The oldest version of the app that may still be used. */
    public static final String APP_MIN_VERSION = "qb.app.min-version";
    /**
     * The address signed-in users write to when the help centre does not answer them (Q7b, Q7-8); empty: the help
     * pages show no contact.
     */
    public static final String SUPPORT_EMAIL = "qb.support.email";

    /*
     * The account mapping of the journal export (Q9d, F-01): for each postable account of the ledger (QbLedger), the
     * account code of the accounting system the summary voucher posts it to. Empty until finance sets it; an export
     * moving an account without one is refused. The startup check JournalMappingCheck keeps this list complete.
     */
    public static final String JOURNAL_ACCOUNT_PREFIX = "qb.journal.account.";
    public static final String JOURNAL_ACCOUNT_1110 = JOURNAL_ACCOUNT_PREFIX + "1110";
    public static final String JOURNAL_ACCOUNT_1120 = JOURNAL_ACCOUNT_PREFIX + "1120";
    public static final String JOURNAL_ACCOUNT_2110 = JOURNAL_ACCOUNT_PREFIX + "2110";
    public static final String JOURNAL_ACCOUNT_2120 = JOURNAL_ACCOUNT_PREFIX + "2120";
    public static final String JOURNAL_ACCOUNT_2130 = JOURNAL_ACCOUNT_PREFIX + "2130";
    public static final String JOURNAL_ACCOUNT_4110 = JOURNAL_ACCOUNT_PREFIX + "4110";
    public static final String JOURNAL_ACCOUNT_5110 = JOURNAL_ACCOUNT_PREFIX + "5110";
    public static final List<String> JOURNAL_ACCOUNTS = List.of(JOURNAL_ACCOUNT_1110, JOURNAL_ACCOUNT_1120,
        JOURNAL_ACCOUNT_2110, JOURNAL_ACCOUNT_2120, JOURNAL_ACCOUNT_2130, JOURNAL_ACCOUNT_4110, JOURNAL_ACCOUNT_5110);

    /*
     * The issuer of the top-up receipts (Q9d, F-09, D-4): the operator's name, address and registration number as the
     * receipts show them. Data, not configuration, so that each receipt keeps them as issued; empty until set, and no
     * receipt is issued without a name.
     */
    public static final String ISSUER_NAME = "qb.issuer.name";
    public static final String ISSUER_ADDRESS = "qb.issuer.address";
    public static final String ISSUER_REGISTRATION_NO = "qb.issuer.registration-no";
    public static final List<String> ISSUER = List.of(ISSUER_NAME, ISSUER_ADDRESS, ISSUER_REGISTRATION_NO);

    /**
     * The parameters that change only through controlled changes (proposed by one administrator, published by
     * another): the money settings, and the review switches, since turning one on removes a person's review.
     */
    public static final List<String> CONTROLLED = java.util.stream.Stream.of(List.of(CREATOR_FEE_RATE,
        TRANSFER_THRESHOLD, TRANSFER_REVIEW_ABOVE, TRANSFER_ENABLED, REVIEW_SPONSOR_AUTO, REVIEW_PUBLICATION_AUTO,
        TOPUP_MIN, TOPUP_MAX, TRANSFER_FEE, TRANSFER_HOLD_DAYS, TOPUP_NEW_SPONSOR_DAYS, TOPUP_NEW_SPONSOR_MAX_TOTAL),
        JOURNAL_ACCOUNTS, ISSUER).flatMap(List::stream).toList();

    /** A parameter: its key, kind (as the platform writes kinds) and first value. */
    public record Param(String key, Map<String, Object> valueKind, Object value, String description) {
        public Param {
            valueKind = Map.copyOf(valueKind);
        }
    }

    private static final Map<String, Object> KUDOS = Map.of("type", "monetary", "currency", "JPY", "scale", 0);
    private static final Map<String, Object> BOOL = Map.of("type", "bool");
    private static final Map<String, Object> DAYS = Map.of("type", "numeric", "precision", 3, "scale", 0);
    /** An account code of the accounting system: at most 20 characters (letters, digits, {@code . _ -}). */
    private static final Map<String, Object> ACCOUNT_CODE = Map.of("type", "text", "maxLength", 20);

    public static final List<Param> ALL = List.of(
        // numeric(4, 4): below 1 (a taker always keeps part of a reward), refused at the proposal otherwise. Parameters
        // have no lower bound: a negative rate is refused when a reward is paid (play.RewardSplit).
        new Param(CREATOR_FEE_RATE, Map.of("type", "numeric", "precision", 4, "scale", 4), "0",
            "Commission of the platform on each reward, as a fraction from 0 to below 1 (0.1 = 10%)"),
        new Param(TRANSFER_THRESHOLD, KUDOS, "1000",
            "A user's rewards are transferred once they reach this many Kudos"),
        new Param(TRANSFER_REVIEW_ABOVE, KUDOS, "50000", "Transfers above this many Kudos wait for review"),
        new Param(TRANSFER_ENABLED, BOOL, false, "Whether the daily automatic transfers run"),
        new Param(REVIEW_SPONSOR_AUTO, BOOL, false, "Approve sponsor onboarding without review"),
        new Param(REVIEW_PUBLICATION_AUTO, BOOL, false, "Approve publications without review"),
        new Param(AI_MODEL, Map.of("type", "text", "maxLength", 100), "gpt-4o-mini",
            "OpenAI model used to generate questions and covers"),
        new Param(APP_MIN_VERSION, Map.of("type", "text", "maxLength", 20), "1.0.0",
            "Oldest app version that may still be used"),
        new Param(TOPUP_MIN, KUDOS, "1000", "The smallest top-up a sponsor may make, in Kudos"),
        new Param(TOPUP_MAX, KUDOS, "1000000", "The largest top-up a sponsor may make, in Kudos"),
        new Param(TRANSFER_FEE, KUDOS, "0", "The platform's fee on each transfer, in Kudos (0: none)"),
        new Param(TRANSFER_HOLD_DAYS, DAYS, "7", "Days a reward is held back from transfers after it is paid"),
        new Param(TOPUP_NEW_SPONSOR_DAYS, DAYS, "30",
            "Days from a sponsor's first paid top-up during which the new sponsors' limit applies"),
        new Param(TOPUP_NEW_SPONSOR_MAX_TOTAL, KUDOS, "100000",
            "What a new sponsor may top up in all during that time, in Kudos"),
        journal(JOURNAL_ACCOUNT_1110, "1110 Stripe balance"),
        journal(JOURNAL_ACCOUNT_1120, "1120 Bank deposits"),
        journal(JOURNAL_ACCOUNT_2110, "2110 Sponsor prepayments"),
        journal(JOURNAL_ACCOUNT_2120, "2120 Rewards payable"),
        journal(JOURNAL_ACCOUNT_2130, "2130 Transfers in transit"),
        journal(JOURNAL_ACCOUNT_4110, "4110 Service revenue"),
        journal(JOURNAL_ACCOUNT_5110, "5110 Payment fees"),
        new Param(ISSUER_NAME, Map.of("type", "text", "maxLength", 200), "",
            "Name of the operator issuing the top-up receipts"),
        new Param(ISSUER_ADDRESS, Map.of("type", "text", "maxLength", 500), "",
            "Address of the operator, as the top-up receipts show it"),
        new Param(ISSUER_REGISTRATION_NO, Map.of("type", "text", "maxLength", 50), "",
            "Registration number of the operator (such as the qualified invoice issuer number), on the receipts"),
        new Param(SUPPORT_EMAIL, Map.of("type", "text", "maxLength", 200), "",
            "Support address shown to signed-in users in the help centre (empty: none)"));

    private static Param journal(String key, String account) {
        return new Param(key, ACCOUNT_CODE, "", "Accounting system's account code for " + account
            + " in the journal export (empty: not mapped)");
    }

    private QbParams() {}
}
