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
    /** F-M6: only a lead of the operator sets its distribution price. */
    public static final String DISTRIBUTION_LEAD = "QB_DISTRIBUTION_LEAD";

    public static final List<String> ALL = List.of(PUBLICATION_FUNDS, FEE_UNPAID, FEE_CASH_SHORT, FEE_INVOICE_STATE, FEE_SPONSOR,
        FEE_PERIOD, FEE_PARAMS, FEE_NO_REVIEW, FEE_BILLING_MODE, FEE_OWN, FEE_NOTHING_DUE, FEE_CREDIT_OFF,
        FEE_CREDIT_SPONSOR, FEE_CREDIT_GIVEN, FEE_CREDIT_MAX, FEE_CREDIT_LEAD, DISTRIBUTION_PRICE_REQUIRED,
        DISTRIBUTION_PRICE_RANGE, DISTRIBUTION_FLOOR_ABOVE_CEILING, DISTRIBUTION_DEPOSIT_RANGE, DISTRIBUTION_LEAD);

    private FeeCodes() {}
}
