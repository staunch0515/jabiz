package com.jabiz.quizbuks.setup;

import com.jabiz.param.ParamValues;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    /** The smallest and largest bank top-up, in Kudos (Q13a). */
    public static final String TOPUP_BANK_MIN = "qb.topup.bank.min";
    public static final String TOPUP_BANK_MAX = "qb.topup.bank.max";
    /*
     * The platform's account sponsors transfer to (Q13a): the bank and branch (names with their codes), the kind of
     * account, its number and holder, as the sponsor console shows them. Empty until finance sets them; bank top-ups
     * are refused until all are set. Controlled: changed by one person, sponsors' money would go to another account.
     */
    public static final String BANK_RECEIVING_PREFIX = "qb.bank.receiving.";
    public static final String BANK_RECEIVING_BANK = BANK_RECEIVING_PREFIX + "bank";
    public static final String BANK_RECEIVING_BRANCH = BANK_RECEIVING_PREFIX + "branch";
    public static final String BANK_RECEIVING_TYPE = BANK_RECEIVING_PREFIX + "account-type";
    public static final String BANK_RECEIVING_NUMBER = BANK_RECEIVING_PREFIX + "account-number";
    public static final String BANK_RECEIVING_HOLDER = BANK_RECEIVING_PREFIX + "account-holder";
    public static final List<String> BANK_RECEIVING = List.of(BANK_RECEIVING_BANK, BANK_RECEIVING_BRANCH,
        BANK_RECEIVING_TYPE, BANK_RECEIVING_NUMBER, BANK_RECEIVING_HOLDER);
    /** Whether takers may withdraw to a bank account (Q13b). */
    public static final String WITHDRAWAL_BANK_ENABLED = "qb.withdrawal.bank.enabled";
    /** The platform's fixed fee on each bank withdrawal, in Kudos, taken from the amount (Q13b, B-4). */
    public static final String WITHDRAWAL_BANK_FEE = "qb.withdrawal.bank.fee";
    /** Hours after a bank account is saved or changed before anything may be withdrawn to it (Q13b). */
    public static final String WITHDRAWAL_BANK_COOLDOWN_HOURS = "qb.withdrawal.bank.cooldown-hours";
    public static final List<String> WITHDRAWAL_BANK = List.of(WITHDRAWAL_BANK_ENABLED, WITHDRAWAL_BANK_FEE,
        WITHDRAWAL_BANK_COOLDOWN_HOURS);
    /**
     * S2 (docs/quizbuks/shopify/S2-storefront.md section 2, I-8): the most discount codes one shop's storefront hands
     * out a day (UTC), whatever its pool holds: the most a shop loses a day to a script that keeps changing its visitor
     * key. Controlled (it bounds what the shops give away), but not created by {@code QB_SETUP}: the storefront reads
     * it as optional and takes {@code Shopify.DAILY_CODES_DEFAULT} (100) until an administrator creates it through a
     * controlled change. Kept out of {@link #CONTROLLED} and {@link #ALL}: a new parameter of the setup would move every
     * key the setup hands out, and with them the order of every scenario's options.
     */
    public static final String SHOPIFY_DAILY_CODES = com.jabiz.quizbuks.shopify.Shopify.DAILY_CODES;
    /**
     * S3a (docs/quizbuks/shopify/S3-billing.md section 3): the attempts a Shopify shop's storefront completes a billing
     * period on each plan. Controlled, read as optional and kept out of the setup like {@link #SHOPIFY_DAILY_CODES}
     * (the defaults are {@code QbsBilling.DEFAULT_LIMITS}).
     */
    public static final List<String> SHOPIFY_PLAN_LIMITS = com.jabiz.quizbuks.shopify.QbsBilling.LIMIT_PARAMS;
    /** Whether sponsor onboarding is approved without a person's review. */
    public static final String REVIEW_SPONSOR_AUTO = "qb.review.sponsor.auto";
    /** Whether publications are approved without a person's review. */
    public static final String REVIEW_PUBLICATION_AUTO = "qb.review.publication.auto";
    /**
     * The reward methods every sponsor may publish with (Q28, docs/quizbuks/plans/Q28-phase1-defaults.md), besides their
     * own setting and what a lit node opens (F-S4b): the methods' names, comma-separated. F-S4b: empty (the declared
     * value, the first launch's) gives none.
     */
    public static final String REWARD_DEFAULT_METHODS = "qb.reward.default-methods";
    /** The highest reward one attempt may win, in Kudos; 0 for no cap (Q28: the premiums law's limit). */
    public static final String REWARD_MAX_PER_ATTEMPT = "qb.reward.max-per-attempt";
    public static final List<String> REWARD = List.of(REWARD_DEFAULT_METHODS, REWARD_MAX_PER_ATTEMPT);
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
    /**
     * AI prospecting (Q48c, docs/quizbuks/plans/Q48-operators.md): the switch every prospecting job is held by
     * ({@code enabledBy}), and the model and effort its requests name. Controlled: prospecting writes to businesses
     * and spends money.
     */
    public static final String PROSPECT_ENABLED = "qb.prospect.enabled";
    public static final String PROSPECT_MODEL = "qb.prospect.model";
    public static final String PROSPECT_EFFORT = "qb.prospect.effort";
    /**
     * Q48d: the most an operator's prospecting may cost a month (its own setting is capped by it: the cost is deducted
     * from its settlement), and the yen of a dollar the deduction is reckoned at.
     */
    public static final String PROSPECT_OPERATOR_MONTHLY_CAP = "qb.prospect.operator-monthly-cap-usd";
    public static final String PROSPECT_USD_JPY = "qb.prospect.usd-jpy";
    public static final List<String> PROSPECT_CONTROLLED = List.of(PROSPECT_ENABLED, PROSPECT_MODEL, PROSPECT_EFFORT,
        PROSPECT_OPERATOR_MONTHLY_CAP, PROSPECT_USD_JPY);
    /**
     * The operators' plans (Q48e-2, docs/quizbuks/plans/Q48e-operator-isolation.md): each plan's monthly fixed fee in
     * yen and the platform's share of the commission (the operator is paid the rest); each operator is on one
     * ({@code QbOperatorPlan}). Controlled: they are money. They replace Q48b's one share for every operator.
     */
    public static String operatorPlanFee(String plan) {
        return "qb.operator.plan." + plan + ".fee";
    }

    public static String operatorPlanShare(String plan) {
        return "qb.operator.plan." + plan + ".share";
    }

    /** The plans' first terms (the requester's, 2026-10-31): fee in yen, the platform's share. */
    private static final String[][] PLAN_TERMS = {{"1", "0", "0.5"}, {"2", "20000", "0.4"}, {"3", "40000", "0.3"},
        {"4", "60000", "0.2"}, {"5", "100000", "0.1"}};

    public static final List<String> OPERATOR_PLANS = java.util.Arrays.stream(PLAN_TERMS)
        .flatMap(terms -> java.util.stream.Stream.of(operatorPlanFee(terms[0]), operatorPlanShare(terms[0]))).toList();
    /**
     * Operators' withdrawals (Q48e-2): the least an operator withdraws at once, in yen, and the hours after a change
     * of its bank account before it may withdraw. The platform bears the bank's fee.
     */
    public static final String OPERATOR_WITHDRAWAL_MIN = "qb.operator.withdrawal.min";
    public static final String OPERATOR_WITHDRAWAL_COOLDOWN_HOURS = "qb.operator.withdrawal.cooldown-hours";
    public static final List<String> OPERATOR_WITHDRAWAL = List.of(OPERATOR_WITHDRAWAL_MIN,
        OPERATOR_WITHDRAWAL_COOLDOWN_HOURS);
    /** The controlled AI parameters, in the order the administrators' page shows them. */
    public static final List<String> AI_CONTROLLED = List.of(AI_ENABLED, AI_MODEL, AI_EFFORT, AI_SPONSOR_DAILY_JOBS,
        AI_SPONSOR_MONTHLY_TOKENS, AI_GLOBAL_MONTHLY_TOKENS);
    /**
     * Rewards settled outside the wallet (Q22, docs/quizbuks/plans/Q22-reward-methods.md): days the taker has to
     * confirm or dispute a cash reward the sponsor marked paid before they are stopped from starting new attempts;
     * days the sponsor has to pay a cash reward before they are reminded; days a prize may be collected after the
     * attempt. Not money and no review switch, so not controlled.
     */
    public static final String CASH_CONFIRM_DAYS = "qb.reward.cash.confirm-days";
    public static final String CASH_PAY_DAYS = "qb.reward.cash.pay-days";
    public static final String REDEEM_DAYS = "qb.reward.redeem-days";
    /** Q33: days a sponsor has to send a digital prize by hand when the publication names none. */
    public static final String DIGITAL_SEND_DAYS = "qb.reward.digital.send-days";
    /** Q70 (D-Q70-19b): how many referees one referrer brings to a publication in 24 hours, and in all. */
    public static final String REFERRAL_PER_REFERRER_DAILY = "qb.referral.per-referrer-daily";
    public static final String REFERRAL_PER_REFERRER_TOTAL = "qb.referral.per-referrer-total";
    /** Q82 (D-Q82-4): how many messages one side sends in one conversation in 24 hours. */
    public static final String CONVERSATION_DAILY_CAP = "qb.conversation.daily-cap";
    /** Q82 (D-Q82-5): days without a message after which the daily job closes a conversation. */
    public static final String CONVERSATION_CLOSE_DAYS = "qb.conversation.close-days";
    /** Q47c: whether publications pay for the audience rules they use (controlled; off: everything is free). */
    public static final String AUDIENCE_BILLING_ENABLED = "qb.audience.billing.enabled";
    /** Q47c: whether the unused days are refunded when a publication ends early (Q47c-2 charges; controlled). */
    public static final String AUDIENCE_REFUND_UNUSED_DAYS = "qb.audience.fee.refund-unused-days";
    /**
     * Q87a (D-Q87-2): where a top-up may go - {@code KUDOS} (straight into the Kudos wallet, as before) and
     * {@code CASH} (the sponsor's cash account) - as a comma-separated list, and where it goes when the sponsor does
     * not choose. Controlled.
     */
    public static final String TOPUP_TARGETS = "qb.topup.targets";
    public static final String TOPUP_DEFAULT_TARGET = "qb.topup.default-target";
    /** Q87a (D-Q87-3): whether sponsors may buy Kudos with their cash (on) and sell Kudos back to cash (off). */
    public static final String KUDOS_BUY_ENABLED = "qb.kudos.buy.enabled";
    public static final String KUDOS_SELL_ENABLED = "qb.kudos.sell.enabled";
    /** Q87a (D-Q87-0): the platform's share of each transfer, as a fraction, added to the fixed fee (rounded down). */
    public static final String TRANSFER_FEE_RATE = "qb.transfer.fee-rate";
    /**
     * Q87b (D-Q87-4, D-Q87-5, D-Q87-7): the platform's fees - the monthly fee (0: none), the consumption tax rate,
     * the billing mode of a sponsor without one of their own, whether unpaid fees block submitting and converting
     * (all controlled); and (ordinary) the days an invoice is due in.
     */
    public static final String FEE_MONTHLY = "qb.fee.monthly";
    public static final String FEE_TAX_RATE = "qb.fee.tax-rate";
    public static final String FEE_DEFAULT_BILLING_MODE = "qb.fee.default-billing-mode";
    public static final String FEE_OVERDUE_BLOCK = "qb.fee.overdue.block-submit";
    public static final String FEE_DUE_DAYS = "qb.fee.due-days";
    /**
     * F-M2: the platform fee Kudos granted to sponsors - whether the onboarding grant is given, its two amounts (an
     * uncertified sponsor's, a certified one's), the days a lot is valid (all controlled); the most one manual grant
     * may be (controlled, not created by {@code QB_SETUP}, read as optional: absent = no cap, as the spec says); and
     * (ordinary) how many days before a lot expires its sponsor is reminded.
     */
    public static final String FEE_CREDIT_GRANT_ENABLED = "qb.fee-credit.grant.enabled";
    public static final String FEE_CREDIT_GRANT_BASIC = "qb.fee-credit.grant.basic";
    public static final String FEE_CREDIT_GRANT_CERTIFIED = "qb.fee-credit.grant.certified";
    public static final String FEE_CREDIT_VALID_DAYS = "qb.fee-credit.valid-days";
    public static final String FEE_CREDIT_MANUAL_MAX = "qb.fee-credit.manual.max";
    public static final String FEE_CREDIT_EXPIRY_NOTICE_DAYS = "qb.fee-credit.expiry-notice-days";
    /** F-M3: how many hours the link that proves a company's mailbox stays good (ordinary; 1 to 168). */
    public static final String VERIFICATION_LINK_HOURS = "qb.sponsor-verification.link-hours";
    /** F-M5 (M3 of its review): days after its end an open prepayment is a difference of the daily check. */
    public static final String PREPAYMENT_RELEASE_OVERDUE_DAYS = "qb.prepayment.release.overdue-days";
    /** F-M7 (N9): after how many hours of its end an audience fee not decided is reported by the daily check. */
    public static final String AUDIENCE_REFUND_MISSING_AFTER_HOURS = "qb.audience.refund.missing-after-hours";
    /**
     * F-M6 (docs/quizbuks/plans/impl/F-M.md §9): the distribution fee. Whether a submission sets its deposit aside and
     * freezes its price per start (read at the submission only: turning it off does not touch what was submitted);
     * the headquarters' range of the operators' prices (the rate in yen per Kudos of the highest tier, the floor and
     * ceiling of the price per start, the least deposit); how long after its end a publication's final settlement
     * waits (the starts in its start lock by then are committed); after how many hours of a month a publication
     * still unsettled for the month before, or one ended without its final settlement, is reported. All controlled.
     */
    public static final String DISTRIBUTION_BILLING_ENABLED = "qb.distribution.billing.enabled";
    public static final String DISTRIBUTION_RATE_MIN = "qb.distribution.rate.min";
    public static final String DISTRIBUTION_RATE_MAX = "qb.distribution.rate.max";
    public static final String DISTRIBUTION_UNIT_FLOOR_MIN = "qb.distribution.unit-floor.min";
    public static final String DISTRIBUTION_UNIT_FLOOR_MAX = "qb.distribution.unit-floor.max";
    public static final String DISTRIBUTION_UNIT_CEILING_MAX = "qb.distribution.unit-ceiling.max";
    public static final String DISTRIBUTION_MINIMUM_DEPOSIT_MAX = "qb.distribution.minimum-deposit.max";
    public static final String DISTRIBUTION_FINAL_END_GRACE_SEC = "qb.distribution.final.end-grace-sec";
    public static final String DISTRIBUTION_UNSETTLED_AFTER_HOURS = "qb.distribution.unsettled-after-hours";
    public static final String DISTRIBUTION_FINAL_MISSING_AFTER_HOURS = "qb.distribution.final-missing-after-hours";
    /** The headquarters' range of the operators' distribution prices, in the order the price check reads them. */
    public static final List<String> DISTRIBUTION_RANGE = List.of(DISTRIBUTION_RATE_MIN, DISTRIBUTION_RATE_MAX,
        DISTRIBUTION_UNIT_FLOOR_MIN, DISTRIBUTION_UNIT_FLOOR_MAX, DISTRIBUTION_UNIT_CEILING_MAX,
        DISTRIBUTION_MINIMUM_DEPOSIT_MAX);
    /** The controlled ones, in the order of {@link #CONTROLLED} (before {@link #REWARD_KUDOS}). */
    public static final List<String> DISTRIBUTION_CONTROLLED = java.util.stream.Stream.of(
        List.of(DISTRIBUTION_BILLING_ENABLED), DISTRIBUTION_RANGE, List.of(DISTRIBUTION_FINAL_END_GRACE_SEC,
            DISTRIBUTION_UNSETTLED_AFTER_HOURS, DISTRIBUTION_FINAL_MISSING_AFTER_HOURS))
        .flatMap(List::stream).toList();
    /**
     * F-M8 (docs/quizbuks/plans/impl/F-M8.md §5): the exposure fee. Whether a submission buys exposures and freezes their
     * price is read at the submission only (turning it off does not touch a publication that was submitted while on); the
     * headquarters' range of the operators' prices, the least number bought, the grace after an end, the daily check's
     * hours and the biggest top-up are controlled; when the reminder says a purchase is running out is not.
     */
    public static final String EXPOSURE_BILLING_ENABLED = "qb.exposure.billing.enabled";
    public static final String EXPOSURE_UNIT_MIN = "qb.exposure.unit.min";
    public static final String EXPOSURE_UNIT_MAX = "qb.exposure.unit.max";
    public static final String EXPOSURE_MIN_PURCHASE = "qb.exposure.min-purchase";
    /** Not {@link #EXPOSURE_END_GRACE_SEC} (the counting check's, not controlled, 60): this one is the final settlement's. */
    public static final String EXPOSURE_FINAL_END_GRACE_SEC = "qb.exposure.final.end-grace-sec";
    public static final String EXPOSURE_UNSETTLED_AFTER_HOURS = "qb.exposure.unsettled-after-hours";
    public static final String EXPOSURE_FINAL_MISSING_AFTER_HOURS = "qb.exposure.final-missing-after-hours";
    public static final String EXPOSURE_TOP_UP_MAX = "qb.exposure.top-up.max";
    /** The share of what was bought at or below which the sponsor is reminded (0: never); not controlled. */
    public static final String EXPOSURE_LOW_RATIO = "qb.exposure.low-ratio";
    /** The headquarters' range of the operators' exposure prices, in the order the price check reads them. */
    public static final List<String> EXPOSURE_RANGE = List.of(EXPOSURE_UNIT_MIN, EXPOSURE_UNIT_MAX);
    /** The controlled ones, in the order of {@link #CONTROLLED} (before {@link #REWARD_KUDOS}). */
    public static final List<String> EXPOSURE_CONTROLLED = List.of(EXPOSURE_BILLING_ENABLED, EXPOSURE_UNIT_MIN,
        EXPOSURE_UNIT_MAX, EXPOSURE_MIN_PURCHASE, EXPOSURE_FINAL_END_GRACE_SEC, EXPOSURE_UNSETTLED_AFTER_HOURS,
        EXPOSURE_FINAL_MISSING_AFTER_HOURS, EXPOSURE_TOP_UP_MAX);
    /** The cap's kind, which the controlled change that first sets it gives (yen, whole). */
    public static final Map<String, Object> FEE_CREDIT_MANUAL_MAX_KIND = Map.of("type", "monetary", "currency", "JPY",
        "scale", 0);
    /**
     * Q88b (D-Q88-14): whether sponsors may set an entry fee on a quiz (controlled; read only when a draft is saved:
     * off refuses a fee, what is live keeps charging).
     */
    public static final String ENTRY_FEE_ENABLED = "qb.play.entry-fee.enabled";
    /** F-C1: the most assets one sponsor keeps in their library (not controlled: no money, no review). */
    public static final String ASSETS_MAX_COUNT = "qb.assets.max-count";
    /** F-C1: the most tags one asset has, and the most characters of one tag. */
    public static final String ASSETS_MAX_TAGS = "qb.assets.max-tags";
    public static final String ASSETS_TAG_LENGTH = "qb.assets.tag-length";
    /**
     * F-C2: the storage a sponsor uses free of charge, in bytes (controlled: it is what charging will start from; in
     * the first launch it only shows on the asset library and sends one notice when crossed, never refuses).
     */
    public static final String USAGE_FREE_BYTES = "qb.usage.storage.free-bytes";
    /** F-C2: how many days the daily usage snapshots are kept (not controlled). */
    public static final String USAGE_RETENTION_DAYS = "qb.usage.retention-days";
    /** F-C11: the most products one sponsor has (not controlled: no money, no review); the profile package's too. */
    public static final String PRODUCTS_MAX = "qb.products.max";
    /** F-S2: the most nodes one knowledge graph has (not controlled). */
    public static final String TREE_MAX_NODES = "qb.tree.max-nodes";
    /** F-S6: the most questions one quiz (its bank) has (not controlled; at most ContentLimits.MAX_QUESTIONS_TECH). */
    public static final String CONTENT_MAX_QUESTIONS = "qb.content.max-questions";
    /** F-S6: the most questions one attempt gives (not controlled; at most PlayLimits.MAX_PER_ATTEMPT_TECH). */
    public static final String PLAY_MAX_PER_ATTEMPT = "qb.play.max-questions-per-attempt";
    /** F-S6: the most questions one import batch adds to a quiz (not controlled). */
    public static final String CONTENT_IMPORT_BATCH = "qb.content.import.batch-size";
    /**
     * F-F11a: the most bytes of one import request's body (not controlled; from ContentLimits.IMPORT_BATCH_BYTES_MIN
     * to ContentLimits.IMPORT_BATCH_BYTES_TECH): the page cuts a large bank by it as well as by the question count.
     */
    public static final String CONTENT_IMPORT_BATCH_BYTES = "qb.content.import.batch-bytes";
    /** F-S2: the most items (nodes and the pass) one node's condition names, at every level (not controlled). */
    public static final String TREE_MAX_CONDITIONS = "qb.tree.max-conditions-per-node";
    /** F-S2: the longest chain of nodes naming nodes, which bounds the lighting's chained evaluation (not controlled). */
    public static final String TREE_MAX_DEPTH = "qb.tree.max-depth";
    /** F-S2: how many graphs of their own one Sponsor has (not controlled). */
    public static final String TREE_SPONSOR_MAX_TREES = "qb.tree.sponsor.max-trees";
    /** F-S2: after how many days a version waiting for review is reminded to its reviewers once (not controlled). */
    public static final String TREE_REVIEW_REMIND_AFTER = "qb.tree.review.remind-after";
    /**
     * F-S2c: the platform features a Taker unlocks by lighting a node (A2-11): each names the node as
     * {@code <treeId>/<nodeId>} of a platform map Takers light ({@code tree.TreeGates}). Controlled (they open what
     * the old certifications opened), but not created by {@code QB_SETUP} and read as optional, like
     * {@link #SHOPIFY_DAILY_CODES}: an absent parameter is the specification's "empty by default = closed" (nobody may
     * refer, nobody may offer or sponsor benefits). Kept out of {@link #CONTROLLED} and {@link #ALL} for that reason.
     */
    public static final String TREE_GATE_SPONSOR_APPLY = "qb.tree.gate.sponsor-apply-node";
    public static final String TREE_GATE_REFERRER = "qb.tree.gate.referrer-node";
    public static final String TREE_GATE_BENEFIT_PROVIDER = "qb.tree.gate.benefit-provider-node";
    public static final String TREE_GATE_BENEFIT_SPONSOR = "qb.tree.gate.benefit-sponsor-node";
    public static final List<String> TREE_GATES = List.of(TREE_GATE_SPONSOR_APPLY, TREE_GATE_REFERRER,
        TREE_GATE_BENEFIT_PROVIDER, TREE_GATE_BENEFIT_SPONSOR);
    /**
     * F-S4b: the reward methods a Sponsor unlocks by lighting a node of a platform template Sponsors light, in any
     * operator's copy of it ({@code tree.SponsorGates}): {@code DIGITAL_PRIZE} and {@code OFFLINE_REDEEM}. As
     * {@link #TREE_GATES}: controlled, not created by {@code QB_SETUP}, read as optional, absent = closed.
     */
    public static final String TREE_GATE_DIGITAL_PRIZE = "qb.tree.gate.digital-prize-node";
    public static final String TREE_GATE_OFFLINE_REDEEM = "qb.tree.gate.offline-redeem-node";
    public static final List<String> SPONSOR_TREE_GATES = List.of(TREE_GATE_DIGITAL_PRIZE, TREE_GATE_OFFLINE_REDEEM);
    /**
     * F-S4b: the most platform-fee Kudos one node of a platform template Sponsors light may grant for lighting it.
     * Controlled (money), not created by {@code QB_SETUP}, read as optional: absent, no grant can be set (as
     * {@link #FEE_CREDIT_MANUAL_MAX}).
     */
    public static final String TREE_GRANT_MAX_PER_NODE = "qb.tree.grant.max-per-node";
    /** The kind the controlled change that creates a gate parameter gives: two UUIDs and a slash. */
    public static final Map<String, Object> TREE_GATE_KIND = Map.of("type", "text", "maxLength", 73);
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
    public static final String JOURNAL_ACCOUNT_5120 = JOURNAL_ACCOUNT_PREFIX + "5120";
    /** Q48b: the operators' shares payable and their expense. */
    public static final String JOURNAL_ACCOUNT_2140 = JOURNAL_ACCOUNT_PREFIX + "2140";
    public static final String JOURNAL_ACCOUNT_5130 = JOURNAL_ACCOUNT_PREFIX + "5130";
    /** Q87a: the sponsors' cash accounts. */
    public static final String JOURNAL_ACCOUNT_2160 = JOURNAL_ACCOUNT_PREFIX + "2160";
    /** Q87b: the fees receivable, the fee revenue and the tax on it. */
    public static final String JOURNAL_ACCOUNT_1130 = JOURNAL_ACCOUNT_PREFIX + "1130";
    public static final String JOURNAL_ACCOUNT_4120 = JOURNAL_ACCOUNT_PREFIX + "4120";
    public static final String JOURNAL_ACCOUNT_2150 = JOURNAL_ACCOUNT_PREFIX + "2150";
    /** Q88b: the entry fees unsettled. */
    public static final String JOURNAL_ACCOUNT_2170 = JOURNAL_ACCOUNT_PREFIX + "2170";
    /** F-M1: the sponsors' platform fee Kudos (the fee credit). */
    public static final String JOURNAL_ACCOUNT_2190 = JOURNAL_ACCOUNT_PREFIX + "2190";
    /** F-M2: the promotion expenses of the platform fee Kudos granted. */
    public static final String JOURNAL_ACCOUNT_5140 = JOURNAL_ACCOUNT_PREFIX + "5140";
    /** F-M5: the platform fee prepayments. */
    public static final String JOURNAL_ACCOUNT_2180 = JOURNAL_ACCOUNT_PREFIX + "2180";
    public static final List<String> JOURNAL_ACCOUNTS = List.of(JOURNAL_ACCOUNT_1110, JOURNAL_ACCOUNT_1120,
        JOURNAL_ACCOUNT_2110, JOURNAL_ACCOUNT_2120, JOURNAL_ACCOUNT_2130, JOURNAL_ACCOUNT_4110, JOURNAL_ACCOUNT_5110,
        JOURNAL_ACCOUNT_5120, JOURNAL_ACCOUNT_2140, JOURNAL_ACCOUNT_5130, JOURNAL_ACCOUNT_2160, JOURNAL_ACCOUNT_1130,
        JOURNAL_ACCOUNT_4120, JOURNAL_ACCOUNT_2150, JOURNAL_ACCOUNT_2170, JOURNAL_ACCOUNT_2190, JOURNAL_ACCOUNT_5140,
        JOURNAL_ACCOUNT_2180);

    /*
     * The issuer of the top-up receipts (Q9d, F-09, D-4): the operator's name, address and registration number as the
     * receipts show them. Data, not configuration, so that each receipt keeps them as issued; empty until set, and no
     * receipt is issued without a name.
     */
    public static final String ISSUER_NAME = "qb.issuer.name";
    public static final String ISSUER_ADDRESS = "qb.issuer.address";
    public static final String ISSUER_REGISTRATION_NO = "qb.issuer.registration-no";
    public static final List<String> ISSUER = List.of(ISSUER_NAME, ISSUER_ADDRESS, ISSUER_REGISTRATION_NO);

    /** Q47c: the audience rules' charging (controlled, last, in this order). */
    public static final List<String> AUDIENCE_BILLING_PARAMS = List.of(AUDIENCE_BILLING_ENABLED,
        AUDIENCE_REFUND_UNUSED_DAYS);


    /**
     * F-S11 (docs/quizbuks/plans/impl/F-S11.md): the country a request whose address the address database does not know
     * counts as for the rule on foreign videos ({@code ClientCountry} unknown: a private address, no database). Mainland
     * China ({@code CN}) by default: rather refuse than show a video that does not play; empty counts as no country (not
     * mainland China). Not controlled (neither money nor a review switch); the end-to-end tests set another.
     */
    public static final String REGION_UNKNOWN_IP_COUNTRY = "qb.region.unknown-ip-country";
    /**
     * F-S11: the video sites mainland China plays ({@code VideoEmbeds.Site} keys, comma-separated; F-S15: and host names,
     * for video files and other pages there); a video of any other site, or of none recognised, is foreign ({@code content.ForeignVideos}). Controlled: taking a site off hides
     * publications from mainland China, adding one shows them videos that may not play. Read as optional: the setup's
     * value ({@code ForeignVideos.DEFAULT_SITES}) until its proposal is published.
     */
    public static final String VIDEO_MAINLAND_SITES = "qb.video.mainland-accessible-sites";
    /**
     * F-S10: after which cards of the App's home a shelf of vertical short videos is shown (comma-separated places, the
     * first card being 1), and how many publications each shelf shows; not controlled. The App reads them through the
     * home's template ({@code qb.app.feed}).
     */
    public static final String FEED_SHORTS_POSITIONS = "qb.feed.shorts.positions";
    public static final String FEED_SHORTS_PER_SHELF = "qb.feed.shorts.per-shelf";
    /**
     * F-S9: the most topics of a quiz or a publication (words of the list and custom words together; at most
     * {@code TopicRules.HARD_MAX}), and the most characters of one custom word (at most
     * {@code TopicRules.CUSTOM_HARD_MAX_LENGTH}); not controlled. Read as optional: the declared values before the
     * setup made them.
     */
    public static final String TOPIC_MAX_PER_QUIZ = "qb.topic.max-per-quiz";
    public static final String TOPIC_CUSTOM_MAX_LENGTH = "qb.topic.custom-max-length";
    /** F-S8: the most blocks one taker has, and the smallest group of blockers a sponsor is shown a count of. */
    public static final String BLOCK_MAX_PER_USER = "qb.block.max-per-user";
    public static final String BLOCK_COUNT_MIN_GROUP = "qb.block.count-min-group";
    /**
     * F-S13: the seconds of watching that count one exposure, the most exposures one taker counts a UTC day, every how
     * many screens of the vertical mode a push stands, and how many reports the App gathers and how long at most before
     * it sends them; not controlled. Read as optional ({@code ExposureRules} bounds them).
     */
    public static final String EXPOSURE_SECONDS = "qb.exposure.seconds";
    public static final String EXPOSURE_DAILY_CAP = "qb.exposure.daily-cap-per-user";
    public static final String EXPOSURE_PUSH_EVERY = "qb.exposure.push.every";
    public static final String EXPOSURE_REPORT_BATCH = "qb.exposure.report.batch";
    public static final String EXPOSURE_REPORT_INTERVAL = "qb.exposure.report.interval-sec";
    /**
     * F-S13 (the requester: every number a parameter): the most reports one call takes; the daily reconciliation's
     * tolerance beyond the purchase (at least so many, else so many percent of it); how long after its end an exposure
     * may still be counted (a report on its way when the publication ended) before the reconciliation reports it.
     */
    public static final String EXPOSURE_REPORT_MAX_PER_CALL = "qb.exposure.report.max-per-call";
    public static final String EXPOSURE_RECONCILE_MIN_TOLERANCE = "qb.exposure.reconcile.min-tolerance";
    public static final String EXPOSURE_RECONCILE_TOLERANCE_PERCENT = "qb.exposure.reconcile.tolerance-percent";
    public static final String EXPOSURE_END_GRACE_SEC = "qb.exposure.reconcile.end-grace-sec";
    /**
     * F-S14: after how many days a shared video's use waiting for its provider reminds both sides (the requester: every
     * number a parameter); not controlled. Read as optional ({@code VideoUseRules.remindDays} bounds it).
     */
    public static final String VIDEO_USE_REMIND_DAYS = "qb.video-use.remind-days";
    /** F-S14 (the controller: every number a parameter): the longest period one use covers, in days; not controlled. */
    public static final String VIDEO_USE_MAX_PERIOD_DAYS = "qb.video-use.max-period-days";
    /** F-C4: above how many bytes the export of a sponsor package or a quiz file asks before it is saved. */
    public static final String PACKAGE_CONFIRM_BYTES = "qb.package.confirm-bytes";
    /** F-C4: how many days the record of a sponsor package's import is kept after its last change. */
    public static final String PACKAGE_IMPORT_RETENTION_DAYS = "qb.package.import-retention-days";

    /*
     * F-C10 (docs/quizbuks/first-launch-spec.md F-C10, plans/impl/F-C10.md): the AI help. The switch and the platform's
     * monthly tokens are controlled (they decide what the platform pays); the rest ordinary. The help's usage is its own
     * (QbHelpUsage), never a Sponsor's AI usage, fees or limits.
     */
    public static final String HELP_ENABLED = "qb.help.enabled";
    /** The model the help asks: only one the platform allows (the parameter's kind, and again per request). */
    public static final String HELP_MODEL = "qb.help.model";
    /** The questions to the AI one person asks a day (the deployment's zone). */
    public static final String HELP_USER_DAILY = "qb.help.user-daily";
    /** The tokens the help uses a month, everybody together; judged from the usage recorded (it may go a little over). */
    public static final String HELP_GLOBAL_MONTHLY_TOKENS = "qb.help.global-monthly-tokens";
    /** The latest turns of a conversation sent with a question. */
    public static final String HELP_HISTORY_TURNS = "qb.help.history-turns";
    /** Days after its last activity a conversation is deleted. */
    public static final String HELP_RETENTION_DAYS = "qb.help.retention-days";
    /** The largest materials (FAQ, terms, manual) one request's prompt takes, in bytes; above it the startup check fails. */
    public static final String HELP_MANUAL_MAX_BYTES = "qb.help.manual-max-bytes";
    /** How often the pages ask for an answer, in seconds, and for how many minutes at most. */
    public static final String HELP_POLL_SECONDS = "qb.help.poll-seconds";
    public static final String HELP_POLL_MAX_MINUTES = "qb.help.poll-max-minutes";
    /** Days a conversation handed to a person waits before its handler is reminded. */
    public static final String HELP_ESCALATION_REMIND_DAYS = "qb.help.escalation-remind-days";
    /** Days after a person's reply without a new question before the conversation closes by itself. */
    public static final String HELP_AUTO_CLOSE_DAYS = "qb.help.auto-close-days";
    /** The longest question (and words handed to a person) a user writes, in characters (100 to 4000). */
    public static final String HELP_QUESTION_MAX_LENGTH = "qb.help.question-max-length";
    /** The longest AI answer kept, in characters (500 to 8000); a longer one is cut with an ellipsis. */
    public static final String HELP_ANSWER_MAX_LENGTH = "qb.help.answer-max-length";
    /** The most links of the site one answer keeps (0 to 10). */
    public static final String HELP_MAX_LINKS = "qb.help.max-links";
    /**
     * F-C10b: how much one input token read from the provider's prompt cache counts towards
     * {@code qb.help.global-monthly-tokens} (0 to 1; the provider bills such a token at about a tenth). Controlled: it
     * changes what the help may spend.
     */
    public static final String HELP_CACHE_READ_WEIGHT = "qb.help.cache-read-weight";
    /** The help's controlled parameters (last, in this order). */
    public static final List<String> HELP_CONTROLLED = List.of(HELP_ENABLED, HELP_GLOBAL_MONTHLY_TOKENS,
        HELP_CACHE_READ_WEIGHT);
    public static final List<String> HELP = List.of(HELP_ENABLED, HELP_MODEL, HELP_USER_DAILY,
        HELP_GLOBAL_MONTHLY_TOKENS, HELP_HISTORY_TURNS, HELP_RETENTION_DAYS, HELP_MANUAL_MAX_BYTES, HELP_POLL_SECONDS,
        HELP_POLL_MAX_MINUTES, HELP_ESCALATION_REMIND_DAYS, HELP_AUTO_CLOSE_DAYS, HELP_QUESTION_MAX_LENGTH,
        HELP_ANSWER_MAX_LENGTH, HELP_MAX_LINKS, HELP_CACHE_READ_WEIGHT);
    /**
     * F-F3: the areas of the Sponsor app's bottom menu on a phone, comma-separated area keys (home, quizzes,
     * publications, prizes, messages, benefits, money, assets, hub; "More" follows them); not controlled. The Sponsor app
     * reads it through {@code qb.sponsor.layout}, leaves out unknown keys and repeats, and starts from the same list as
     * the declared value ({@code quizbuks-web/sponsor/src/shell/bottom-bar.default.json}, {@code
     * SponsorLayoutParamsTest}). Changing it means changing the help's sentence about the bottom menu.
     */
    public static final String SPONSOR_BOTTOM_BAR = "qb.sponsor.bottom-bar";

    /** Q87a: the cash account's switches and the transfers' rate (controlled, last, in this order). */
    public static final List<String> CASH_ACCOUNT_PARAMS = List.of(TOPUP_TARGETS, TOPUP_DEFAULT_TARGET,
        KUDOS_BUY_ENABLED, KUDOS_SELL_ENABLED, TRANSFER_FEE_RATE);
    /** Q87b: the platform fees' switches (controlled, after the cash account's, in this order). */
    public static final List<String> FEE_PARAMS = List.of(FEE_MONTHLY, FEE_TAX_RATE, FEE_DEFAULT_BILLING_MODE,
        FEE_OVERDUE_BLOCK);
    /** F-M2: the grants' switch, amounts and validity (controlled, last, in this order); the cap is not set up. */
    public static final List<String> FEE_CREDIT_PARAMS = List.of(FEE_CREDIT_GRANT_ENABLED, FEE_CREDIT_GRANT_BASIC,
        FEE_CREDIT_GRANT_CERTIFIED, FEE_CREDIT_VALID_DAYS);

    /**
     * F-C7: drafting a profile package and a quiz from the sponsor's website. The switch and the monthly requests spend
     * money, so they are controlled (four eyes; the switch stays off until the AI credit can be charged, F-M9); the
     * rest are limits of one request, ordinary.
     */
    public static final String SITE_PROFILE_ENABLED = "qb.ai.site-profile.enabled";
    public static final String SITE_PROFILE_MONTHLY = "qb.ai.site-profile.monthly";
    public static final String SITE_PROFILE_DAILY = "qb.ai.site-profile.daily";
    public static final String SITE_PROFILE_MAX_PAGES = "qb.ai.site-profile.max-pages";
    public static final String SITE_PROFILE_QUESTIONS = "qb.ai.site-profile.questions";
    public static final String SITE_PROFILE_OPTIONS = "qb.ai.site-profile.options";
    public static final String SITE_PROFILE_MATERIALS_MAX = "qb.ai.site-profile.materials-max";
    public static final String SITE_PROFILE_HANDLE_ALTERNATIVES = "qb.ai.site-profile.handle-alternatives";
    /** The controlled ones, in the order of {@link #CONTROLLED} (before {@link #REWARD_KUDOS}). */
    public static final List<String> SITE_PROFILE_CONTROLLED = List.of(SITE_PROFILE_ENABLED, SITE_PROFILE_MONTHLY);

    /**
     * F-F10a: whether a publication may be published with the reward method {@code PLATFORM} (Kudos paid by QuizBuks).
     * The declared value is today's behaviour (true); the first launch's value ({@code FirstLaunchSwitches}) is false,
     * so no publication can offer a Kudos reward however a sponsor is set up. Not there at all (before setup):
     * not blocked on a system that does not say it is production or staging (as {@code qb.reward.default-methods} not
     * there gives the platform's Kudos, F-S4b); on production and staging it counts as off (default-deny, so a system
     * whose proposals were never published pays no Kudos reward).
     */
    public static final String REWARD_KUDOS_ENABLED = "qb.reward.kudos.enabled";
    /** F-F10a: the Kudos reward switch (controlled, the last of {@link #CONTROLLED}). */
    public static final List<String> REWARD_KUDOS = List.of(REWARD_KUDOS_ENABLED);

    /**
     * The parameters that change only through controlled changes (proposed by one administrator, published by
     * another): the money settings, and the review switches, since turning one on removes a person's review.
     */
    public static final List<String> CONTROLLED = java.util.stream.Stream.of(List.of(CREATOR_FEE_RATE,
        TRANSFER_THRESHOLD, TRANSFER_REVIEW_ABOVE, TRANSFER_ENABLED, REVIEW_SPONSOR_AUTO, REVIEW_PUBLICATION_AUTO,
        TOPUP_MIN, TOPUP_MAX, TRANSFER_FEE, TRANSFER_HOLD_DAYS, TOPUP_NEW_SPONSOR_DAYS, TOPUP_NEW_SPONSOR_MAX_TOTAL),
        REWARD, JOURNAL_ACCOUNTS, ISSUER, List.of(LEGAL_MAX_ANNOUNCE_DAYS), AI_CONTROLLED, PROSPECT_CONTROLLED,
        OPERATOR_PLANS, OPERATOR_WITHDRAWAL, List.of(TOPUP_BANK_MIN, TOPUP_BANK_MAX), BANK_RECEIVING, WITHDRAWAL_BANK, AUDIENCE_BILLING_PARAMS,
        CASH_ACCOUNT_PARAMS, FEE_PARAMS,
        List.of(ENTRY_FEE_ENABLED), List.of(USAGE_FREE_BYTES), List.of(VIDEO_MAINLAND_SITES), HELP_CONTROLLED, FEE_CREDIT_PARAMS,
        SITE_PROFILE_CONTROLLED, DISTRIBUTION_CONTROLLED, EXPOSURE_CONTROLLED, REWARD_KUDOS)
        .flatMap(List::stream).toList();

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
    /** F-M6: a rate or a price per start, in yen with four decimals. */
    public static final Map<String, Object> DISTRIBUTION_RATE_KIND = Map.of("type", "numeric", "precision", 12,
        "scale", 4);
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
        // The declared value keeps what the platform did before Q28; the launch phase publishes OFFLINE_REDEEM.
        new Param(REWARD_DEFAULT_METHODS, Map.of("type", "text", "maxLength", 50), "",
            "Reward methods every Sponsor may publish with, besides their own setting and what a lit node opens: "
                + "PLATFORM, SPONSOR_CASH, OFFLINE_REDEEM, DIGITAL_PRIZE, comma-separated (empty: none)"),
        new Param(REWARD_MAX_PER_ATTEMPT, KUDOS, "0", "The highest reward one attempt may win, in Kudos (0: no cap)"),
        journal(JOURNAL_ACCOUNT_1110, "1110 Stripe balance"),
        journal(JOURNAL_ACCOUNT_1120, "1120 Bank deposits"),
        journal(JOURNAL_ACCOUNT_2110, "2110 Sponsor prepayments"),
        journal(JOURNAL_ACCOUNT_2120, "2120 Rewards payable"),
        journal(JOURNAL_ACCOUNT_2130, "2130 Transfers in transit"),
        journal(JOURNAL_ACCOUNT_4110, "4110 Service revenue"),
        journal(JOURNAL_ACCOUNT_5110, "5110 Payment fees"),
        journal(JOURNAL_ACCOUNT_5120, "5120 Promotion expenses"),
        journal(JOURNAL_ACCOUNT_2140, "2140 Operator shares payable"),
        journal(JOURNAL_ACCOUNT_5130, "5130 Operator shares"),
        journal(JOURNAL_ACCOUNT_2160, "2160 Sponsor cash accounts"),
        journal(JOURNAL_ACCOUNT_1130, "1130 Platform fees receivable"),
        journal(JOURNAL_ACCOUNT_4120, "4120 Platform fee revenue"),
        journal(JOURNAL_ACCOUNT_2150, "2150 Consumption tax payable"),
        journal(JOURNAL_ACCOUNT_2170, "2170 Entry fees unsettled"),
        journal(JOURNAL_ACCOUNT_2190, "2190 Sponsor platform fee Kudos"),
        journal(JOURNAL_ACCOUNT_5140, "5140 Promotion expenses (platform fee Kudos)"),
        journal(JOURNAL_ACCOUNT_2180, "2180 Platform fee prepayments"),
        new Param(ISSUER_NAME, Map.of("type", "text", "maxLength", 200), "",
            "Name of the operator issuing the top-up receipts"),
        new Param(ISSUER_ADDRESS, Map.of("type", "text", "maxLength", 500), "",
            "Address of the operator, as the top-up receipts show it"),
        new Param(ISSUER_REGISTRATION_NO, Map.of("type", "text", "maxLength", 50), "",
            "Registration number of the operator (such as the qualified invoice issuer number), on the receipts"),
        new Param(SUPPORT_EMAIL, Map.of("type", "text", "maxLength", 200), Brand.current().supportEmail(),
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
        // Q22: rewards settled outside the wallet; not controlled (neither money nor a review switch).
        new Param(CASH_CONFIRM_DAYS, DAYS, "7",
            "Days a taker has to confirm or dispute a cash reward the sponsor marked paid before they may start no "
                + "new quiz"),
        new Param(CASH_PAY_DAYS, DAYS, "14",
            "Days a sponsor has to pay a cash reward before they are reminded"),
        new Param(REDEEM_DAYS, DAYS, "30", "Days a prize may be collected at the sponsor's place after the quiz"),
        new Param(DIGITAL_SEND_DAYS, DAYS, "3",
            "Days a sponsor has to send a digital prize by hand unless the publication says otherwise"),
        // Q70: so that referring is a friend's link, not a mailing list; not controlled (no money, no review).
        new Param(REFERRAL_PER_REFERRER_DAILY, DAYS, "3",
            "How many referees one referrer may bring to a publication in 24 hours"),
        new Param(REFERRAL_PER_REFERRER_TOTAL, DAYS, "10", "How many referees one referrer may bring to a publication"),
        // Q82: the conversations between takers and sponsors.
        new Param(CONVERSATION_DAILY_CAP, DAYS, "20", "How many messages one side may send in a conversation a day"),
        new Param(CONVERSATION_CLOSE_DAYS, DAYS, "90", "Days without a message before a conversation is closed"),
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
        // Q48c: AI prospecting.
        new Param(PROSPECT_ENABLED, BOOL, false, "Whether AI prospecting runs (finding and researching businesses)"),
        new Param(PROSPECT_MODEL, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:ai-model",
            "allowedValues", List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")), "claude-opus-5-5",
            "Claude model that finds and researches businesses"),
        new Param(PROSPECT_EFFORT, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:ai-effort",
            "allowedValues", List.of("low", "medium", "high")), "medium",
            "Claude's effort (thinking depth and cost) when prospecting"),
        new Param(PROSPECT_OPERATOR_MONTHLY_CAP, USD, "100",
            "The most an operator's AI prospecting may cost a month, in US dollars (deducted from its settlement)"),
        new Param(PROSPECT_USD_JPY, Map.of("type", "numeric", "precision", 8, "scale", 2), "150",
            "Yen per US dollar the prospecting cost is deducted from an operator's settlement at"),
        // Q48e-2: the operators' plans (a fixed fee and the platform's share) and withdrawals.
        planParam(PLAN_TERMS[0], true), planParam(PLAN_TERMS[0], false),
        planParam(PLAN_TERMS[1], true), planParam(PLAN_TERMS[1], false),
        planParam(PLAN_TERMS[2], true), planParam(PLAN_TERMS[2], false),
        planParam(PLAN_TERMS[3], true), planParam(PLAN_TERMS[3], false),
        planParam(PLAN_TERMS[4], true), planParam(PLAN_TERMS[4], false),
        new Param(OPERATOR_WITHDRAWAL_MIN, KUDOS, "10000",
            "The least an operator withdraws from its account at once, in yen"),
        new Param(OPERATOR_WITHDRAWAL_COOLDOWN_HOURS, Map.of("type", "numeric", "precision", 4, "scale", 0), "24",
            "Hours after a change of an operator's bank account before it may withdraw"),
        new Param(AI_EXPIRE_DAYS, DAYS, "7",
            "Days AI candidates wait for the sponsor's review before they are cleared"),
        new Param(AI_PRICE_INPUT, USD, "4",
            "Dollars per million input tokens, for the administrators' estimate only"),
        new Param(AI_PRICE_OUTPUT, USD, "20",
            "Dollars per million output tokens, for the administrators' estimate only"),
        // Q13a: bank top-ups (controlled, last, in the order of CONTROLLED).
        new Param(TOPUP_BANK_MIN, KUDOS, "1000", "The smallest bank top-up a sponsor may ask for, in Kudos"),
        new Param(TOPUP_BANK_MAX, KUDOS, "10000000", "The largest bank top-up a sponsor may ask for, in Kudos"),
        new Param(BANK_RECEIVING_BANK, Map.of("type", "text", "maxLength", 100), "",
            "Bank sponsors transfer to, with its code (such as Mizuho Bank (0001))"),
        new Param(BANK_RECEIVING_BRANCH, Map.of("type", "text", "maxLength", 100), "",
            "Branch of the receiving account, with its code"),
        new Param(BANK_RECEIVING_TYPE, Map.of("type", "text", "maxLength", 20), "",
            "Kind of the receiving account (such as ordinary)"),
        new Param(BANK_RECEIVING_NUMBER, Map.of("type", "text", "maxLength", 20), "",
            "Number of the receiving account"),
        new Param(BANK_RECEIVING_HOLDER, Map.of("type", "text", "maxLength", 100), "",
            "Holder of the receiving account, as the bank shows it (katakana)"),
        // Q13b: bank withdrawals (controlled, last, in the order of CONTROLLED).
        new Param(WITHDRAWAL_BANK_ENABLED, BOOL, false, "Whether takers may withdraw to a bank account"),
        new Param(WITHDRAWAL_BANK_FEE, KUDOS, "0", "The platform's fee on each bank withdrawal, in Kudos (0: none)"),
        new Param(WITHDRAWAL_BANK_COOLDOWN_HOURS, Map.of("type", "numeric", "precision", 4, "scale", 0), "72",
            "Hours after a bank account is saved or changed before anything may be withdrawn to it"),
        // Q47c: the audience rules' charging (controlled, last, in the order of AUDIENCE_BILLING_PARAMS).
        new Param(AUDIENCE_BILLING_ENABLED, BOOL, false, "Whether publications pay for the audience rules they use"),
        new Param(AUDIENCE_REFUND_UNUSED_DAYS, BOOL, false,
            "Whether the unused days of the audience rules are refunded when a publication ends early"),
        // Q87a: the cash account (controlled, last, in the order of CASH_ACCOUNT_PARAMS). The defaults keep what was:
        // a top-up goes straight into the Kudos wallet unless the sponsor chooses the cash account.
        new Param(TOPUP_TARGETS, Map.of("type", "text", "maxLength", 20), "KUDOS,CASH",
            "Where a top-up may go, comma-separated: KUDOS (the Kudos wallet) and CASH (the cash account)"),
        new Param(TOPUP_DEFAULT_TARGET, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:topup-target",
            "allowedValues", List.of("KUDOS", "CASH")), "KUDOS", "Where a top-up goes when the sponsor does not choose"),
        new Param(KUDOS_BUY_ENABLED, BOOL, true, "Whether sponsors may turn cash into Kudos"),
        new Param(KUDOS_SELL_ENABLED, BOOL, false, "Whether sponsors may turn Kudos back into cash"),
        new Param(TRANSFER_FEE_RATE, Map.of("type", "numeric", "precision", 4, "scale", 4), "0",
            "The platform's share of each transfer, as a fraction from 0 to below 1, added to the fixed fee "
                + "(rounded down; 0: none)"),
        // Q87b: the platform's fees. The due days are ordinary; the rest controlled (last, in the order of
        // FEE_PARAMS). The declared values keep what the platform did before: no fee, prepaid.
        new Param(FEE_DUE_DAYS, DAYS, "30", "Days after a platform fee invoice is issued that it is due in"),
        new Param(FEE_MONTHLY, KUDOS, "0", "The platform's monthly fee of each approved sponsor, in yen (0: none)"),
        new Param(FEE_TAX_RATE, Map.of("type", "numeric", "precision", 4, "scale", 4), "0.10",
            "The consumption tax rate on the platform's fees (0.1 = 10%)"),
        new Param(FEE_DEFAULT_BILLING_MODE, Map.of("type", "text", "maxLength", 10), "PREPAID",
            "How a sponsor without a billing mode of their own pays the platform's fees: PREPAID or POSTPAID"),
        new Param(FEE_OVERDUE_BLOCK, BOOL, true,
            "Whether unpaid platform fees stop a sponsor from converting cash to Kudos and submitting publications"),
        // Q88b: entry fees (controlled, last). Off keeps what the platform did before; what is live keeps charging.
        new Param(ENTRY_FEE_ENABLED, BOOL, false,
            "Whether sponsors may charge an entry fee in Kudos for answering a quiz (Q88)"),
        // F-C1: the sponsors' asset library (ordinary; last, so that the setup's earlier keys stay as they were).
        new Param(ASSETS_MAX_COUNT, Map.of("type", "numeric", "precision", 6, "scale", 0), "1000",
            "The most assets one sponsor keeps in their library"),
        new Param(ASSETS_MAX_TAGS, Map.of("type", "numeric", "precision", 3, "scale", 0), "10",
            "The most tags one asset of the library has"),
        new Param(ASSETS_TAG_LENGTH, Map.of("type", "numeric", "precision", 3, "scale", 0), "30",
            "The most characters of one tag of the asset library"),
        // F-C2: the usage metering (last). The free amount is controlled; 500 MB (1 MB = 1,048,576 bytes).
        new Param(USAGE_FREE_BYTES, Map.of("type", "numeric", "precision", 15, "scale", 0), "524288000",
            "The storage a sponsor uses free of charge, in bytes (only noticed in the first launch, never refused)"),
        new Param(USAGE_RETENTION_DAYS, Map.of("type", "numeric", "precision", 5, "scale", 0), "800",
            "How many days the daily usage snapshots are kept"),
        // F-C11: the products' limit (ordinary; last after F-C2, so that the setup's earlier keys stay as they were).
        new Param(PRODUCTS_MAX, Map.of("type", "numeric", "precision", 6, "scale", 0), "50",
            "The most products one sponsor has, on the products page and in a profile package"),
        // F-S11: foreign videos and mainland China (the controlled one last, in the order of CONTROLLED).
        new Param(REGION_UNKNOWN_IP_COUNTRY, Map.of("type", "text", "maxLength", 2), "CN",
            "The country a request whose address the address database does not know counts as for foreign videos "
                + "(CN: mainland China; empty: none)"),
        new Param(VIDEO_MAINLAND_SITES, Map.of("type", "text", "maxLength", 200),
            com.jabiz.quizbuks.content.ForeignVideos.DEFAULT_SITES,
            "Video sites and hosts mainland China plays, comma-separated (youtube, vimeo, bilibili, niconico, "
                + "dailymotion, youku, tencent, or a host name such as videos.example.com for video files and pages "
                + "there); a video of any other site is foreign and not answerable from mainland China"),
        // F-S10: the home's shelves of vertical short videos (ordinary; last, so that the setup's earlier keys stay).
        new Param(FEED_SHORTS_POSITIONS, Map.of("type", "text", "maxLength", 200), "3,12",
            "After which cards of the App's home a shelf of vertical short videos is shown, comma-separated (the first "
                + "card is 1); empty: no shelf"),
        new Param(FEED_SHORTS_PER_SHELF, Map.of("type", "numeric", "precision", 3, "scale", 0), "10",
            "How many vertical short videos one shelf of the App's home shows"),
        // F-S2: knowledge graphs (ordinary; last).
        new Param(TREE_MAX_NODES, Map.of("type", "numeric", "precision", 4, "scale", 0), "200",
            "The most nodes one knowledge graph has"),
        new Param(TREE_MAX_CONDITIONS, Map.of("type", "numeric", "precision", 3, "scale", 0), "20",
            "The most items one node's condition names"),
        new Param(TREE_MAX_DEPTH, Map.of("type", "numeric", "precision", 3, "scale", 0), "20",
            "The longest chain of nodes that name nodes"),
        new Param(TREE_SPONSOR_MAX_TREES, Map.of("type", "numeric", "precision", 3, "scale", 0), "1",
            "How many knowledge graphs of their own one Sponsor has"),
        new Param(TREE_REVIEW_REMIND_AFTER, Map.of("type", "numeric", "precision", 3, "scale", 0), "3",
            "After how many days a knowledge graph's version waiting for review is reminded to its reviewers"),
        // F-S9: topics (ordinary; last, so that the setup's earlier keys stay as they were).
        new Param(TOPIC_MAX_PER_QUIZ, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.topic.TopicRules.DEFAULT_MAX_PER_QUIZ),
            "The most topics of a quiz or a publication, words of the list and custom words together (at most 30)"),
        new Param(TOPIC_CUSTOM_MAX_LENGTH, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.topic.TopicRules.DEFAULT_CUSTOM_MAX_LENGTH),
            "The most characters of one custom topic (at most 60)"),
        // F-S6: the question bank's limits (ordinary; last, so that the setup's earlier keys stay as they were).
        new Param(CONTENT_MAX_QUESTIONS, Map.of("type", "numeric", "precision", 6, "scale", 0), "1000",
            "The most questions one quiz has (its question bank; at most 2000)"),
        new Param(PLAY_MAX_PER_ATTEMPT, Map.of("type", "numeric", "precision", 6, "scale", 0), "100",
            "The most questions one attempt gives; a larger bank is drawn from (at most 200)"),
        new Param(CONTENT_IMPORT_BATCH, Map.of("type", "numeric", "precision", 6, "scale", 0), "200",
            "The most questions one import batch adds to a quiz"),
        // F-S8: blocking (ordinary; last, so that the setup's earlier keys stay as they were).
        new Param(BLOCK_MAX_PER_USER, Map.of("type", "numeric", "precision", 5, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.block.BlockRules.DEFAULT_MAX_PER_USER),
            "The most blocks one taker has (publications, quizzes, Sponsors and video providers together; at most "
                + "10000)"),
        new Param(BLOCK_COUNT_MIN_GROUP, Map.of("type", "numeric", "precision", 4, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.block.BlockRules.DEFAULT_COUNT_MIN_GROUP),
            "Below how many blockers a Sponsor is told only \"fewer than\" this number (at least 5); above it the "
                + "count is rounded down to tens"),
        // F-S13: the exposures and the pushes (ordinary; last, so that the setup's earlier keys stay as they were).
        new Param(EXPOSURE_SECONDS, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_SECONDS),
            "How many seconds of watching without a break count one exposure of another's shared video (a shorter "
                + "video: all of it; at most 600)"),
        new Param(EXPOSURE_DAILY_CAP, Map.of("type", "numeric", "precision", 5, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_DAILY_CAP),
            "The most exposures one Taker counts a day (UTC; at most 10000); beyond it nothing counts"),
        new Param(EXPOSURE_PUSH_EVERY, Map.of("type", "numeric", "precision", 2, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_PUSH_EVERY),
            "Every how many screens of the App's vertical mode a push (another's shared video) stands (1 to 50)"),
        new Param(EXPOSURE_REPORT_BATCH, Map.of("type", "numeric", "precision", 2, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_REPORT_BATCH),
            "How many reports of exposures the App gathers before it sends them (1 to 50)"),
        new Param(EXPOSURE_REPORT_INTERVAL, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_REPORT_INTERVAL_SEC),
            "How many seconds at most the App keeps reports of exposures before it sends them (5 to 600)"),
        // F-S13 (the requester, 2026-11-09: every number a parameter; ordinary, last).
        new Param(EXPOSURE_REPORT_MAX_PER_CALL, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_MAX_PER_CALL),
            "The most reports of exposures one call of the App takes (1 to 200); the App sends no more at once"),
        new Param(EXPOSURE_RECONCILE_MIN_TOLERANCE, Map.of("type", "numeric", "precision", 9, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_MIN_TOLERANCE),
            "The daily check of exposures lets the counted go beyond the purchased by at least this many"),
        new Param(EXPOSURE_RECONCILE_TOLERANCE_PERCENT, Map.of("type", "numeric", "precision", 3, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_TOLERANCE_PERCENT),
            "... or by this percentage of the purchased, rounded up, whichever is more (0 to 100)"),
        new Param(EXPOSURE_END_GRACE_SEC, Map.of("type", "numeric", "precision", 5, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.exposure.ExposureRules.DEFAULT_END_GRACE_SEC),
            "How many seconds after a publication ended an exposure may still be counted (a report on its way) "
                + "before the daily check reports it (0 to 86400)"),
        // F-S14: the reminder of a use waiting for its provider (ordinary; last).
        new Param(VIDEO_USE_REMIND_DAYS, Map.of("type", "numeric", "precision", 2, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.videouse.VideoUseRules.DEFAULT_REMIND_DAYS),
            "After how many days a shared video's use waiting for its provider reminds both sides (1 to 90)"),
        // F-S14: the longest period of a use (ordinary; last).
        new Param(VIDEO_USE_MAX_PERIOD_DAYS, Map.of("type", "numeric", "precision", 5, "scale", 0),
            String.valueOf(com.jabiz.quizbuks.videouse.VideoUseRules.DEFAULT_MAX_PERIOD_DAYS),
            "The longest period, in days, one ledger entry of a shared video's use covers (1 to 36600)"),
        // F-C4: the sponsor package (ordinary).
        new Param(PACKAGE_CONFIRM_BYTES, Map.of("type", "numeric", "precision", 12, "scale", 0), "104857600",
            "Above how many bytes the export of a sponsor package or a quiz file asks before it is saved (100 MB by "
                + "default)"),
        new Param(PACKAGE_IMPORT_RETENTION_DAYS, Map.of("type", "numeric", "precision", 5, "scale", 0), "90",
            "Days the record of a sponsor package's import is kept after its last change"),
        // F-C10: the AI help (the ordinary ones, then the controlled ones in the order of HELP_CONTROLLED; last).
        new Param(HELP_MODEL, Map.of("type", "code", "dictUrn", "urn:jabiz:dict:quizbuks:ai-model", "allowedValues",
            List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")), "claude-haiku-5-5",
            "The model the AI help asks (a smaller, faster one)"),
        new Param(HELP_USER_DAILY, Map.of("type", "numeric", "precision", 4, "scale", 0), "20",
            "The questions to the AI help one person asks a day (0: none)"),
        new Param(HELP_HISTORY_TURNS, Map.of("type", "numeric", "precision", 2, "scale", 0), "6",
            "The latest turns of a conversation sent to the AI with a question (0 to 20)"),
        new Param(HELP_RETENTION_DAYS, DAYS, "180", "Days after its last activity an AI help conversation is deleted"),
        new Param(HELP_MANUAL_MAX_BYTES, Map.of("type", "numeric", "precision", 9, "scale", 0), "204800",
            "The largest materials (FAQ, terms, manual) one AI help request takes, in bytes; above it the startup "
                + "check fails"),
        new Param(HELP_POLL_SECONDS, Map.of("type", "numeric", "precision", 3, "scale", 0), "3",
            "How often, in seconds, the pages ask for the AI help's answer"),
        new Param(HELP_POLL_MAX_MINUTES, Map.of("type", "numeric", "precision", 3, "scale", 0), "3",
            "For how many minutes at most the pages wait for the AI help's answer"),
        new Param(HELP_ESCALATION_REMIND_DAYS, DAYS, "2",
            "Days an AI help conversation handed to a person waits before its handler is reminded"),
        new Param(HELP_AUTO_CLOSE_DAYS, DAYS, "14",
            "Days after a person's reply without a new question before an AI help conversation closes by itself"),
        new Param(HELP_QUESTION_MAX_LENGTH, Map.of("type", "numeric", "precision", 4, "scale", 0), "2000",
            "The longest question to the AI help (and words handed to a person), in characters (100 to 4000)"),
        new Param(HELP_ANSWER_MAX_LENGTH, Map.of("type", "numeric", "precision", 4, "scale", 0), "8000",
            "The longest AI help answer kept, in characters (500 to 8000); a longer one is cut"),
        new Param(HELP_MAX_LINKS, Map.of("type", "numeric", "precision", 2, "scale", 0), "5",
            "The most links of the site one AI help answer keeps (0 to 10)"),
        new Param(HELP_ENABLED, BOOL, false, "Whether Takers and Sponsors may ask the AI help (Claude)"),
        new Param(HELP_GLOBAL_MONTHLY_TOKENS, TOKENS, "20000000",
            "The tokens the AI help may use a month, everybody together"),
        new Param(HELP_CACHE_READ_WEIGHT, Map.of("type", "numeric", "precision", 3, "scale", 2), "0.10",
            "How much an input token read from the prompt cache counts towards the AI help's monthly tokens (0 to 1)"),
        // F-M2: platform fee Kudos granted (the reminder ordinary, the rest controlled; last). Off keeps what the
        // platform did before: nothing is granted.
        new Param(FEE_CREDIT_EXPIRY_NOTICE_DAYS, DAYS, "14",
            "How many days before a lot of platform fee Kudos expires its sponsor is reminded (1 to 365)"),
        new Param(FEE_CREDIT_GRANT_ENABLED, BOOL, false,
            "Whether an operator's lead may give an approved sponsor the onboarding platform fee Kudos (F-M2)"),
        new Param(FEE_CREDIT_GRANT_BASIC, KUDOS, "1000",
            "The onboarding platform fee Kudos of a sponsor not certified, in yen"),
        new Param(FEE_CREDIT_GRANT_CERTIFIED, KUDOS, "10000",
            "The onboarding platform fee Kudos of a certified sponsor, in yen (a sponsor certified later is topped up "
                + "to it)"),
        new Param(FEE_CREDIT_VALID_DAYS, DAYS, "90",
            "How many days a lot of platform fee Kudos is valid for from when it is granted (1 to 999)"),
        // F-F3: the Sponsor app's bottom menu (ordinary; last).
        new Param(SPONSOR_BOTTOM_BAR, Map.of("type", "text", "maxLength", 200), "home,publications,prizes,messages",
            "The areas of the Sponsor app's bottom menu on a phone, comma-separated (home, quizzes, publications, "
                + "prizes, messages, benefits, money, assets, hub; at most four, \"More\" follows; empty: this "
                + "default)"),
        // F-C7: drafting from a website (the ordinary ones, then the controlled ones in the order of
        // SITE_PROFILE_CONTROLLED; before F-F10a's Kudos switch).
        new Param(SITE_PROFILE_DAILY, Map.of("type", "numeric", "precision", 3, "scale", 0), "3",
            "How many times a sponsor may ask the AI to draft from their website a day"),
        new Param(SITE_PROFILE_MAX_PAGES, Map.of("type", "numeric", "precision", 3, "scale", 0), "10",
            "The most pages of a website the AI reads for one draft"),
        new Param(SITE_PROFILE_QUESTIONS, Map.of("type", "numeric", "precision", 3, "scale", 0), "5",
            "How many questions the \"get to know us\" quiz drafted from a website has"),
        new Param(SITE_PROFILE_OPTIONS, Map.of("type", "numeric", "precision", 2, "scale", 0), "4",
            "How many options each question of the \"get to know us\" quiz has"),
        new Param(SITE_PROFILE_MATERIALS_MAX, Map.of("type", "numeric", "precision", 2, "scale", 0), "5",
            "The most articles the \"get to know us\" quiz drafted from a website has"),
        new Param(SITE_PROFILE_HANDLE_ALTERNATIVES, Map.of("type", "numeric", "precision", 2, "scale", 0), "3",
            "How many other link names are offered when the one drafted from a website is another sponsor's"),
        new Param(SITE_PROFILE_ENABLED, BOOL, false,
            "Whether sponsors may ask the AI to draft a profile package and a quiz from their website"),
        new Param(SITE_PROFILE_MONTHLY, Map.of("type", "numeric", "precision", 3, "scale", 0), "10",
            "How many times a sponsor may ask the AI to draft from their website a month"),
        // F-M6: the distribution fee (controlled, before the Kudos reward switch: that one stays the last proposal).
        new Param(DISTRIBUTION_BILLING_ENABLED, BOOL, false,
            "Whether a publication's submission freezes its price per start and sets its distribution fee's deposit "
                + "aside (read at the submission only; publications submitted while on are settled to their end)"),
        new Param(DISTRIBUTION_RATE_MIN, DISTRIBUTION_RATE_KIND, "0.0000",
            "The least rate an operator may set: yen per start for each Kudos of the publication's highest tier"),
        new Param(DISTRIBUTION_RATE_MAX, DISTRIBUTION_RATE_KIND, "1.0000",
            "The highest rate an operator may set: yen per start for each Kudos of the publication's highest tier"),
        new Param(DISTRIBUTION_UNIT_FLOOR_MIN, DISTRIBUTION_RATE_KIND, "1.0000",
            "The least floor of the price per start an operator may set, in yen (a placeholder: confirmed by the "
                + "requester before qb.distribution.billing.enabled is turned on; a tier set too low lands on it)"),
        new Param(DISTRIBUTION_UNIT_FLOOR_MAX, DISTRIBUTION_RATE_KIND, "100.0000",
            "The highest floor of the price per start an operator may set, in yen"),
        new Param(DISTRIBUTION_UNIT_CEILING_MAX, DISTRIBUTION_RATE_KIND, "1000.0000",
            "The highest ceiling of the price per start an operator may set, in yen"),
        new Param(DISTRIBUTION_MINIMUM_DEPOSIT_MAX, KUDOS, "100000",
            "The highest least deposit an operator may set (a publication without a cap on its winners sets it "
                + "aside), in yen"),
        new Param(DISTRIBUTION_FINAL_END_GRACE_SEC, Map.of("type", "numeric", "precision", 5, "scale", 0), "3600",
            "How many seconds after a publication ended its final distribution settlement waits, so that every "
                + "start before the end is counted (60 to 86400; less counts as 60)"),
        new Param(DISTRIBUTION_UNSETTLED_AFTER_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0), "30",
            "After how many hours of a month a publication not settled for the month before is reported by the "
                + "daily check (the monthly settlement runs on the 2nd at 00:37)"),
        new Param(DISTRIBUTION_FINAL_MISSING_AFTER_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0),
            "48", "After how many hours of its end a publication without its final distribution settlement is "
                + "reported by the daily check"),
        // F-M8: the exposure fee (the ordinary reminder first, then the controlled ones before the Kudos reward switch).
        new Param(EXPOSURE_LOW_RATIO, Map.of("type", "numeric", "precision", 4, "scale", 4), "0.1",
            "The sponsor is reminded when what is left of the exposures bought is at most this share of them (0 to 0.5; "
                + "0: never)"),
        new Param(EXPOSURE_BILLING_ENABLED, BOOL, false,
            "Whether a publication that uses another's shared video buys exposures at its submission (read at the "
                + "submission only; publications submitted while on are settled to their end)"),
        new Param(EXPOSURE_UNIT_MIN, DISTRIBUTION_RATE_KIND, "1.0000",
            "The least price per exposure an operator may set, in yen (a placeholder: confirmed by the requester before "
                + "qb.exposure.billing.enabled is turned on)"),
        new Param(EXPOSURE_UNIT_MAX, DISTRIBUTION_RATE_KIND, "100.0000",
            "The highest price per exposure an operator may set, in yen (a placeholder: confirmed by the requester)"),
        new Param(EXPOSURE_MIN_PURCHASE, Map.of("type", "numeric", "precision", 9, "scale", 0), "100",
            "The fewest exposures a submission buys (1 to 999,999,999; a placeholder: set by the requester before "
                + "qb.exposure.billing.enabled is turned on; a top-up has no least)"),
        new Param(EXPOSURE_FINAL_END_GRACE_SEC, Map.of("type", "numeric", "precision", 5, "scale", 0), "3600",
            "How many seconds after a publication ended its final exposure settlement waits, so that the reports on "
                + "the way are counted (60 to 86400; less counts as 60). Not qb.exposure.reconcile.end-grace-sec, which "
                + "the check of the counting uses"),
        new Param(EXPOSURE_UNSETTLED_AFTER_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0), "30",
            "After how many hours of a month a publication not settled for the month before is reported by the daily "
                + "check (1 to 720; the monthly exposure settlement runs on the 2nd at 00:47)"),
        new Param(EXPOSURE_FINAL_MISSING_AFTER_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0), "48",
            "After how many hours of its end a publication without its final exposure settlement is reported by the "
                + "daily check (1 to 720)"),
        new Param(EXPOSURE_TOP_UP_MAX, Map.of("type", "numeric", "precision", 9, "scale", 0), "1000000",
            "The most exposures one top-up buys (1 to 999,999,999; a placeholder; keeps the parts of a deposit within "
                + "what it can be shared into)"),
        // F-F10a: the Kudos reward switch (controlled, after the F-C7 ones: the last of CONTROLLED, and so the last proposal of QB_SETUP, which follows this list).
        new Param(REWARD_KUDOS_ENABLED, BOOL, true,
            "Whether a publication may offer the reward method PLATFORM (Kudos paid by QuizBuks); off at the first "
                + "launch (F-F10a)"),
        // F-M3: company verification (ordinary; last).
        new Param(VERIFICATION_LINK_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0), "48",
            "How many hours the link mailed to a company address to verify a sponsor's company stays good (1 to 168)"),
        // F-M5 (M3 of its review): the daily check (ordinary; last).
        new Param(PREPAYMENT_RELEASE_OVERDUE_DAYS, DAYS, "2",
            "How many days after a publication ends a prepayment still open is reported by the daily check (1 to 30; "
                + "the daily job gives it back within a day)"),
        // F-F11a: the bytes of one import request (ordinary).
        new Param(CONTENT_IMPORT_BATCH_BYTES, Map.of("type", "numeric", "precision", 7, "scale", 0), "3145728",
            "The most bytes one import request carries; the page cuts a large bank into batches by it as well as by "
                + "the question count (65536 to 3500000)"),
        // F-M7 (N9): the daily check (ordinary; last).
        new Param(AUDIENCE_REFUND_MISSING_AFTER_HOURS, Map.of("type", "numeric", "precision", 3, "scale", 0), "30",
            "After how many hours of a publication's end an audience fee whose unused days are not decided yet is "
                + "reported by the daily check (the daily job decides at 03:37)"));

    /** The declared value of a parameter: what {@code QB_SETUP} writes. */
    public static String defaultOf(String key) {
        return ALL.stream().filter(param -> param.key().equals(key)).map(param -> String.valueOf(param.value()))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("No parameter " + key));
    }

    /**
     * A number of days a parameter gives, as read at a time: the declared value when the parameter is not there yet
     * (before setup), whole days (a fraction is dropped), never negative and at most ten years, so a mistyped value
     * neither fails the process nor sets a date in the far future.
     */
    /** A count parameter (Q70), read as {@link #days} reads days. */
    public static int count(ParamValues params, String key) {
        return days(params, key);
    }

    /**
     * A limit a parameter gives (F-C1), read as {@link #days} reads days but without its ceiling of ten years: whole
     * numbers, never negative.
     */
    public static int limit(ParamValues params, String key) {
        BigDecimal value = params.get(key, BigDecimal.class);
        if (value == null) {
            value = new BigDecimal(defaultOf(key));
        }
        return (int) Math.clamp(value.setScale(0, RoundingMode.DOWN).longValue(), 0, Integer.MAX_VALUE);
    }

    public static int days(ParamValues params, String key) {
        BigDecimal days = params.get(key, BigDecimal.class);
        if (days == null) {
            days = new BigDecimal(defaultOf(key));
        }
        return Math.clamp(days.setScale(0, RoundingMode.DOWN).longValue(), 0, 3650);
    }

    /**
     * An amount of Kudos a parameter gives, as read at a time: the declared value when the parameter is not there yet
     * (before setup); never negative.
     */
    public static BigDecimal kudos(ParamValues params, String key) {
        BigDecimal amount = params == null ? null : params.find(key, BigDecimal.class).orElse(null);
        if (amount == null) {
            amount = new BigDecimal(defaultOf(key));
        }
        return amount.max(BigDecimal.ZERO);
    }

    /**
     * A text a parameter gives, as read at a time: the declared value when the parameter is not there yet (before
     * setup).
     */
    public static String text(ParamValues params, String key) {
        String value = params == null ? null : params.find(key, String.class).orElse(null);
        return value == null ? defaultOf(key) : value;
    }

    /** A plan's fixed fee ({@code fee}) or the platform's share of its commission. */
    private static Param planParam(String[] terms, boolean fee) {
        return fee
            ? new Param(operatorPlanFee(terms[0]), KUDOS, terms[1], "Plan " + terms[0] + " of the operators: the "
                + "monthly fixed fee, in yen, deducted from the operator's settlement")
            : new Param(operatorPlanShare(terms[0]), Map.of("type", "numeric", "precision", 4, "scale", 4), terms[2],
                "Plan " + terms[0] + " of the operators: the platform's share of the commission, as a fraction from "
                + "0 to 1 (0.5 = 50%)");
    }

    private static Param journal(String key, String account) {
        return new Param(key, ACCOUNT_CODE, "", "Accounting system's account code for " + account
            + " in the journal export (empty: not mapped)");
    }

    private QbParams() {}
}
