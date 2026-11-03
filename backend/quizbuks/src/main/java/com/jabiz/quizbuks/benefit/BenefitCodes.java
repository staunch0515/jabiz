package com.jabiz.quizbuks.benefit;

/** The codes of the sponsored benefits' refusals (Q89); their texts are the messages of the same names. */
public final class BenefitCodes {

    /** The caller does not hold the certification the act needs ({@code certification} names it). */
    public static final String NOT_CERTIFIED = "QB_BENEFIT_NOT_CERTIFIED";
    public static final String NAME_BLANK = "QB_BENEFIT_NAME_BLANK";
    public static final String DESCRIPTION_BLANK = "QB_BENEFIT_DESCRIPTION_BLANK";
    /** A market value below 1 or with decimals. */
    public static final String VALUE = "QB_BENEFIT_VALUE";
    /** Goods and services say where and when they are collected. */
    public static final String PICKUP_REQUIRED = "QB_BENEFIT_PICKUP_REQUIRED";
    /** More than {@value QbBenefits#MAX_IMAGES} images. */
    public static final String IMAGES = "QB_BENEFIT_IMAGES";
    /** A provider lists at most {@value QbBenefits#MAX_OFFERS} benefits. */
    public static final String OFFER_LIMIT = "QB_BENEFIT_OFFER_LIMIT";
    /** The benefit changed since the caller read it. */
    public static final String OFFER_CHANGED = "QB_BENEFIT_OFFER_CHANGED";
    /** The benefit is not shown in the catalog now. */
    public static final String OFFER_UNAVAILABLE = "QB_BENEFIT_OFFER_UNAVAILABLE";
    /** A sponsor does not buy its own benefit. */
    public static final String OWN_OFFER = "QB_BENEFIT_OWN_OFFER";
    public static final String BODY_REQUIRED = "QB_BENEFIT_BODY_REQUIRED";
    public static final String BODY_TOO_LONG = "QB_BENEFIT_BODY_TOO_LONG";
    /** The caller sent {@code qb.conversation.daily-cap} messages in this conversation in 24 hours. */
    public static final String DAILY_CAP = "QB_BENEFIT_DAILY_CAP";
    /** The other side's account is banned: nothing is sent to it or bought from it. */
    public static final String UNAVAILABLE = "QB_BENEFIT_UNAVAILABLE";
    /** A quantity below 1 or above {@value BenefitRules#MAX_QUANTITY}. */
    public static final String QUANTITY = "QB_BENEFIT_QUANTITY";
    /** A paid amount below 0 or with decimals. */
    public static final String PAID_AMOUNT = "QB_BENEFIT_PAID_AMOUNT";
    /** A payment dated after today. */
    public static final String PAID_ON_FUTURE = "QB_BENEFIT_PAID_ON_FUTURE";
    /** More than {@value QbBenefits#MAX_PROOFS} proofs. */
    public static final String PROOFS = "QB_BENEFIT_PROOFS";
    /** A purchase goes to the provider with at least one proof of the payment. */
    public static final String PROOF_REQUIRED = "QB_BENEFIT_PROOF_REQUIRED";
    /** The purchase's status does not allow the act ({@code status}). */
    public static final String PURCHASE_STATE = "QB_BENEFIT_PURCHASE_STATE";
    /** The purchase changed since the caller read it: the provider confirms what it read. */
    public static final String PURCHASE_CHANGED = "QB_BENEFIT_PURCHASE_CHANGED";

    /** Q89b-1: the purchase a publication draws on is not a confirmed purchase of its sponsor's. */
    public static final String PURCHASE_NOT_USABLE = "QB_BENEFIT_PURCHASE_NOT_USABLE";
    /** Q89b-1: goods and services are prizes collected at the provider's (OFFLINE_REDEEM). */
    public static final String METHOD = "QB_BENEFIT_METHOD";
    /** Q89b-1: one tier pays, and it pays the benefit's value (one prize per winner). */
    public static final String ONE_TIER = "QB_BENEFIT_ONE_TIER";
    /** Q89b-1: a publication drawing on a purchase says how many winners it has. */
    public static final String QUOTA_REQUIRED = "QB_BENEFIT_QUOTA_REQUIRED";
    /** Q89b-1: fewer of the purchase are left than the publication asks for. */
    public static final String STOCK_SHORT = "QB_BENEFIT_STOCK_SHORT";

    private BenefitCodes() {}
}
