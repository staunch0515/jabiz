package com.jabiz.quizbuks.fee;

import java.util.List;

/** The rule codes of the platform's fees (Q87), each with a message {@code <code>} in every language. */
public final class FeeCodes {

    /** Unpaid fees beyond what the sponsor's billing allows stop converting and submitting (D-Q87-5, D-Q87-7). */
    public static final String FEE_UNPAID = "QB_FEE_UNPAID";
    /** The cash account does not cover the invoice. */
    public static final String FEE_CASH_SHORT = "QB_FEE_CASH_SHORT";
    /** The invoice is not in a state the act applies to (paid or voided already, or not yet due). */
    public static final String FEE_INVOICE_STATE = "QB_FEE_INVOICE_STATE";
    /** A fee is charged to an approved sponsor only. */
    public static final String FEE_SPONSOR = "QB_FEE_SPONSOR";
    /** The month is not a month, or not one a fee may be charged to. */
    public static final String FEE_PERIOD = "QB_FEE_PERIOD";
    /** The tax rate or the fee parameters cannot be used. */
    public static final String FEE_PARAMS = "QB_FEE_PARAMS";
    /** No approval rule asks for a second administrator on fee requests. */
    public static final String FEE_NO_REVIEW = "QB_FEE_NO_REVIEW";
    /** The billing mode given is not one of the two. */
    public static final String FEE_BILLING_MODE = "QB_FEE_BILLING_MODE";
    /** One's own sponsor account is charged, voided or billed by another administrator. */
    public static final String FEE_OWN = "QB_FEE_OWN";
    /** Nothing to pay early: no postpaid fee is waiting for its invoice. */
    public static final String FEE_NOTHING_DUE = "QB_FEE_NOTHING_DUE";

    /** F-M2: the onboarding platform fee Kudos are switched off ({@code qb.fee-credit.grant.enabled}). */
    public static final String FEE_CREDIT_OFF = "QB_FEE_CREDIT_OFF";
    /** F-M2: platform fee Kudos are granted to an approved sponsor who is not banned only. */
    public static final String FEE_CREDIT_SPONSOR = "QB_FEE_CREDIT_SPONSOR";
    /** F-M2: the sponsor received their onboarding platform fee Kudos already. */
    public static final String FEE_CREDIT_GIVEN = "QB_FEE_CREDIT_GIVEN";
    /** F-M2: a grant by hand above {@code qb.fee-credit.manual.max}. */
    public static final String FEE_CREDIT_MAX = "QB_FEE_CREDIT_MAX";
    /** F-M2: only a lead of the sponsor's operator confirms the sponsor. */
    public static final String FEE_CREDIT_LEAD = "QB_FEE_CREDIT_LEAD";
    /** F-M15 a0: a banned sponsor is granted no platform fee Kudos (a lot their key already has is still given back). */
    public static final String FEE_CREDIT_BANNED = "QB_FEE_CREDIT_BANNED";

    /** F-M5: what a publication sets aside is more than the account (prepaid) or the credit limit (postpaid) allows. */
    public static final String PUBLICATION_FUNDS = "QB_PUBLICATION_FUNDS";

    /** F-M6: a value of an operator's distribution price is missing. */
    public static final String DISTRIBUTION_PRICE_REQUIRED = "QB_DISTRIBUTION_PRICE_REQUIRED";
    /** F-M6: a value of an operator's distribution price is negative or outside the headquarters' range. */
    public static final String DISTRIBUTION_PRICE_RANGE = "QB_DISTRIBUTION_PRICE_RANGE";
    /** F-M6: the floor of the price per start is above its ceiling. */
    public static final String DISTRIBUTION_FLOOR_ABOVE_CEILING = "QB_DISTRIBUTION_FLOOR_ABOVE_CEILING";
    /** F-M6: the cap on the winners times the price per start is more than any fee may be. */
    public static final String DISTRIBUTION_DEPOSIT_RANGE = "QB_DISTRIBUTION_DEPOSIT_RANGE";
    /** F-M6 (review L2): a bound of the headquarters' range (qb.distribution.*) is not published. */
    public static final String DISTRIBUTION_RANGE_MISSING = "QB_DISTRIBUTION_RANGE_MISSING";

    /** F-M8: the exposure price is missing. */
    public static final String EXPOSURE_PRICE_REQUIRED = "QB_EXPOSURE_PRICE_REQUIRED";
    /** F-M8: the exposure price is 0 or negative or outside the headquarters' range. */
    public static final String EXPOSURE_PRICE_RANGE = "QB_EXPOSURE_PRICE_RANGE";
    /** F-M8: a bound of the headquarters' range of the exposure prices (qb.exposure.unit.*) is not published. */
    public static final String EXPOSURE_RANGE_MISSING = "QB_EXPOSURE_RANGE_MISSING";
    /** F-M8: what the exposures bought come to, with tax, is more than any fee may be. */
    public static final String EXPOSURE_DEPOSIT_RANGE = "QB_EXPOSURE_DEPOSIT_RANGE";
    /** F-M8: a submission using another's shared video asks for fewer exposures than the least (or none). */
    public static final String EXPOSURE_PURCHASE_REQUIRED = "QB_EXPOSURE_PURCHASE_REQUIRED";
    /** F-M8: a top-up was asked for against a total bought that is no longer the one the page showed. */
    public static final String EXPOSURE_PURCHASED_CHANGED = "QB_EXPOSURE_PURCHASED_CHANGED";
    /** F-M8: the exposures' prepayment is settled or given back: nothing can be added to it. */
    public static final String EXPOSURE_PREPAYMENT_CLOSED = "QB_EXPOSURE_PREPAYMENT_CLOSED";
    /** F-M8: a top-up of a publication that bought no exposures, or that is not running. */
    public static final String EXPOSURE_NOT_BOUGHT = "QB_EXPOSURE_NOT_BOUGHT";
    /** F-M8: a publication's exposures were topped up so often that its prepayment would have too many parts to give back. */
    public static final String EXPOSURE_PARTS_LIMIT = "QB_EXPOSURE_PARTS_LIMIT";
    /** F-M9: AI credit cannot be bought while the AI fee ({@code qb.ai.fee.enabled}) is off. */
    public static final String AI_FEE_OFF = "QB_AI_FEE_OFF";
    /** F-M9: below the least purchase of AI credit ({@code qb.ai.credit.min-purchase}, detail {@code minPurchase}). */
    public static final String AI_CREDIT_MIN = "QB_AI_CREDIT_MIN";
    /** F-M9: a purchase of AI credit with a key the sponsor already used. */
    public static final String AI_CREDIT_REPEATED = "QB_AI_CREDIT_REPEATED";
    /** F-M9: the account cannot pay the purchase (detail {@code available}). */
    public static final String AI_CREDIT_FUNDS_SHORT = "QB_AI_CREDIT_FUNDS_SHORT";
    /** F-M8: a top-up that asks for more at once than {@code qb.exposure.top-up.max}, or beyond what a publication buys. */
    public static final String EXPOSURE_TOP_UP_RANGE = "QB_EXPOSURE_TOP_UP_RANGE";

    /** F-M7: a credit note on a fee whose invoice is voided (what paid it went back with the void). */
    public static final String CREDIT_NOTE_VOIDED = "QB_FEE_CREDIT_NOTE_VOIDED";
    /** F-M7: only a prepayment decided NONE_NO_PERIOD is closed by finance. */
    public static final String AUDIENCE_REFUND_NOT_OPEN = "QB_AUDIENCE_REFUND_NOT_OPEN";
    /** F-M7 (N8): a credit note for more than the fee may still be credited; never issued for less. */
    public static final String CREDIT_NOTE_EXCEEDS = "QB_FEE_CREDIT_NOTE_EXCEEDS";
    /** F-M7: an invoice with a credit note on a fee of it, or paid by one, is not voided: credit it instead. */
    public static final String INVOICE_HAS_CREDIT_NOTE = "QB_FEE_INVOICE_HAS_CREDIT_NOTE";

    public static final List<String> ALL = List.of(PUBLICATION_FUNDS, FEE_UNPAID, FEE_CASH_SHORT, FEE_INVOICE_STATE, FEE_SPONSOR,
        FEE_PERIOD, FEE_PARAMS, FEE_NO_REVIEW, FEE_BILLING_MODE, FEE_OWN, FEE_NOTHING_DUE, FEE_CREDIT_OFF,
        FEE_CREDIT_SPONSOR, FEE_CREDIT_GIVEN, FEE_CREDIT_MAX, FEE_CREDIT_LEAD, FEE_CREDIT_BANNED, DISTRIBUTION_PRICE_REQUIRED,
        DISTRIBUTION_PRICE_RANGE, DISTRIBUTION_FLOOR_ABOVE_CEILING, DISTRIBUTION_DEPOSIT_RANGE,
        DISTRIBUTION_RANGE_MISSING, CREDIT_NOTE_VOIDED, CREDIT_NOTE_EXCEEDS, INVOICE_HAS_CREDIT_NOTE,
        AUDIENCE_REFUND_NOT_OPEN, EXPOSURE_PRICE_REQUIRED, EXPOSURE_PRICE_RANGE, EXPOSURE_RANGE_MISSING,
        EXPOSURE_DEPOSIT_RANGE, EXPOSURE_PURCHASE_REQUIRED, EXPOSURE_PURCHASED_CHANGED, EXPOSURE_PREPAYMENT_CLOSED,
        EXPOSURE_TOP_UP_RANGE, EXPOSURE_NOT_BOUGHT, EXPOSURE_PARTS_LIMIT, AI_FEE_OFF, AI_CREDIT_MIN, AI_CREDIT_REPEATED,
        AI_CREDIT_FUNDS_SHORT);

    private FeeCodes() {}
}
