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
    /**
     * AI generation (Q8a, docs/quizbuks/plans/Q8-ai.md, A-2, A-7, A-9): the switch (off until the deployment has a key
     * and the AI notice is published), Claude's model and effort (one of the listed values only: the parameters' kind
     * allows nothing else, and each request checks again), and the cost limits. They spend money, so they are controlled
     * (four eyes, D40), as the money settings are.
     */
    public static final String AI_ENABLED = "qb.ai.enabled";
    /** Claude's model: {@code claude-opus-5-5} (A-2), {@code claude-sonnet-5-5} or {@code claude-haiku-5-5}. */
    public static final String AI_MODEL = "qb.ai.model";
    /** Claude's effort: {@code low}, {@code medium} (A-2) or {@code high}. */
    public static final String AI_EFFORT = "qb.ai.effort";
    public static final String AI_SPONSOR_DAILY_JOBS = "qb.ai.sponsor.daily-jobs";
    public static final String AI_SPONSOR_MONTHLY_TOKENS = "qb.ai.sponsor.monthly-tokens";
    public static final String AI_GLOBAL_MONTHLY_TOKENS = "qb.ai.global.monthly-tokens";
    /** Days candidates wait for the sponsor's review before they are cleared (A-6). Not controlled. */
    public static final String AI_EXPIRE_DAYS = "qb.ai.candidates.expire-days";
    /** Dollars per million tokens, only for the administrators' estimate (A-8: nothing is billed). Not controlled. */
    public static final String AI_PRICE_INPUT = "qb.ai.price.input-per-mtok-usd";
    public static final String AI_PRICE_OUTPUT = "qb.ai.price.output-per-mtok-usd";
    /** The controlled AI parameters, in the order the administrators' page shows them. */
    public static final List<String> AI_CONTROLLED = List.of(AI_ENABLED, AI_MODEL, AI_EFFORT, AI_SPONSOR_DAILY_JOBS,
        AI_SPONSOR_MONTHLY_TOKENS, AI_GLOBAL_MONTHLY_TOKENS);
    /** The oldest version of the app that may still be used. */
    public static final String APP_MIN_VERSION = "qb.app.min-version";
    /**
     * The smallest group the sponsors' analytics show figures a person's answers could be read from (Q9e, E-4): each
     * question's rate, the average time and the timeouts. At least {@code stats.MinGroup#FLOOR} whatever it is set
     * to; not controlled (the super administrator may raise it).
     */
    public static final String STATS_MIN_GROUP = "qb.stats.min-group";
    /**
     * The address signed-in users write to when the help centre does not answer them (Q7b, Q7-8); empty: the help
     * pages show no contact.
     */
    public static final String SUPPORT_EMAIL = "qb.support.email";
    /**
     * How many days ahead a legal text may be announced to take effect (Q7b review M1): an announcement holds the
     * kind's next version back until it takes effect or is withdrawn. Controlled: a longer horizon lets one person
     * hold the terms back longer. 180 while no value is published.
     */
    public static final String LEGAL_MAX_ANNOUNCE_DAYS = "qb.legal.max-announce-days";
    /** The horizon while {@link #LEGAL_MAX_ANNOUNCE_DAYS} has no published value. */
    public static final int LEGAL_MAX_ANNOUNCE_DAYS_DEFAULT = 180;
    /** How many notices a sponsor may send for one publication (Q7c, Q7-10; withdrawn ones count). */
    public static final String BROADCAST_SPONSOR_MAX = "qb.broadcast.sponsor.max-per-publication";
    /** How many hours apart a publication's notices must go out (Q7c, Q7-10). */
    public static final String BROADCAST_SPONSOR_INTERVAL = "qb.broadcast.sponsor.min-interval-hours";
    /** How many days the platform's announcements stay in the inbox unless their writer says otherwise (Q7-9). */
    public static final String BROADCAST_EXPIRES_DAYS = "qb.broadcast.expires-days";
    /** How many days apart a taker is reminded to open a payout account (Q7-16). */
    public static final String PAYOUT_REMINDER_DAYS = "qb.payout.reminder-days";
    /**
     * Below how many people reached a sponsor sees no clicks or conversions of their notice (Q7c review M2: a small
     * audience and the reward rows would tell who clicked).
     */
    public static final String BROADCAST_STATS_MIN_AUDIENCE = "qb.broadcast.stats.min-audience";

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
        JOURNAL_ACCOUNTS, ISSUER, List.of(LEGAL_MAX_ANNOUNCE_DAYS), AI_CONTROLLED).flatMap(List::stream).toList();

    /** A parameter: its key, kind (as the platform writes kinds) and first value. */
    public record Param(String key, Map<String, Object> valueKind, Object value, String description) {
        public Param {
            valueKind = Map.copyOf(valueKind);
        }
    }

    private static final Map<String, Object> KUDOS = Map.of("type", "monetary", "currency", "JPY", "scale", 0);
    private static final Map<String, Object> BOOL = Map.of("type", "bool");
    private static final Map<String, Object> DAYS = Map.of("type", "numeric", "precision", 3, "scale", 0);
    private static final Map<String, Object> TOKENS = Map.of("type", "numeric", "precision", 12, "scale", 0);
    private static final Map<String, Object> USD = Map.of("type", "numeric", "precision", 8, "scale", 4);
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
        new Param(APP_MIN_VERSION, Map.of("type", "text", "maxLength", 20), "1.0.0",
            "Oldest app version that may still be used"),
        new Param(STATS_MIN_GROUP, Map.of("type", "numeric", "precision", 3, "scale", 0), "5",
            "Fewest completions the sponsors' analytics show per-question rates, average time and timeouts for "
                + "(at least 3), and the fewest new completions before they change"),
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
            "Support address shown to signed-in users in the help centre (empty: none)"),
        new Param(LEGAL_MAX_ANNOUNCE_DAYS, DAYS, String.valueOf(LEGAL_MAX_ANNOUNCE_DAYS_DEFAULT),
            "How many days ahead a new version of the terms or the privacy policy may be announced to take effect"),
        // Q7c: neither money nor a review switch, so not controlled (docs/quizbuks/plans/Q7-messages-legal.md §8).
        new Param(BROADCAST_SPONSOR_MAX, Map.of("type", "numeric", "precision", 2, "scale", 0), "3",
            "How many notices a sponsor may send to the takers of one publication (withdrawn ones count)"),
        new Param(BROADCAST_SPONSOR_INTERVAL, Map.of("type", "numeric", "precision", 4, "scale", 0), "24",
            "How many hours apart the notices of one publication must go out"),
        new Param(BROADCAST_EXPIRES_DAYS, DAYS, "30",
            "Days the platform's announcements stay in the inbox unless their writer chooses otherwise"),
        new Param(PAYOUT_REMINDER_DAYS, DAYS, "30",
            "Days between reminders to open a payout account once the rewards reach the transfer threshold"),
        new Param(BROADCAST_STATS_MIN_AUDIENCE, Map.of("type", "numeric", "precision", 6, "scale", 0), "50",
            "Below how many people reached a sponsor sees no clicks or conversions of a notice"),
        // Q8a: AI generation (the controlled ones last, in the order of AI_CONTROLLED).
        new Param(AI_ENABLED, BOOL, false, "Whether sponsors may ask the AI for questions (Claude)"),
        new Param(AI_MODEL, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:ai-model", "allowedValues",
            List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")), "claude-opus-5-5",
            "Claude model that writes the candidate questions"),
        new Param(AI_EFFORT, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:ai-effort", "allowedValues",
            List.of("low", "medium", "high")), "medium", "Claude's effort (thinking depth and cost) per request"),
        new Param(AI_SPONSOR_DAILY_JOBS, Map.of("type", "numeric", "precision", 4, "scale", 0), "20",
            "AI requests one sponsor may make a day"),
        new Param(AI_SPONSOR_MONTHLY_TOKENS, TOKENS, "2000000",
            "AI tokens (input and output) one sponsor may use a month"),
        new Param(AI_GLOBAL_MONTHLY_TOKENS, TOKENS, "50000000", "AI tokens all sponsors together may use a month"),
        new Param(AI_EXPIRE_DAYS, DAYS, "7",
            "Days AI candidates wait for the sponsor's review before they are cleared"),
        new Param(AI_PRICE_INPUT, USD, "4",
            "Dollars per million input tokens, for the administrators' estimate only"),
        new Param(AI_PRICE_OUTPUT, USD, "20",
            "Dollars per million output tokens, for the administrators' estimate only"));

    private static Param journal(String key, String account) {
        return new Param(key, ACCOUNT_CODE, "", "Accounting system's account code for " + account
            + " in the journal export (empty: not mapped)");
    }

    private QbParams() {}
}
