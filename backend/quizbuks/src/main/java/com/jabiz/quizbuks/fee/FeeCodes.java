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

    public static final List<String> ALL = List.of(FEE_UNPAID, FEE_CASH_SHORT, FEE_INVOICE_STATE, FEE_SPONSOR,
        FEE_PERIOD, FEE_PARAMS, FEE_NO_REVIEW, FEE_BILLING_MODE, FEE_OWN, FEE_NOTHING_DUE, FEE_CREDIT_OFF,
        FEE_CREDIT_SPONSOR, FEE_CREDIT_GIVEN, FEE_CREDIT_MAX, FEE_CREDIT_LEAD);

    private FeeCodes() {}
}
