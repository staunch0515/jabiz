package com.jabiz.quizbuks.fee;

import java.util.List;

/** The platform fees' error and rule codes (Q87b); their texts are in the three languages' messages. */
public final class FeeCodes {

    /** A prepaid sponsor has unpaid fees: no Kudos bought and no publication submitted until they are paid. */
    public static final String UNPAID = "QB_FEE_UNPAID";
    /** A postpaid sponsor owes more than their credit. */
    public static final String CREDIT_EXCEEDED = "QB_FEE_CREDIT_EXCEEDED";
    /** An invoice is past its due date (while {@code qb.fee.overdue.block-submit} is on). */
    public static final String OVERDUE = "QB_FEE_OVERDUE";
    /** The cash account does not cover a fee that must be paid at once. */
    public static final String FUNDS = "QB_FEE_FUNDS";
    /** The invoice is not the caller's, or not one that can be paid or voided now. */
    public static final String INVOICE_STATE = "QB_FEE_INVOICE_STATE";
    /** The cash account does not cover the invoice's due amount. */
    public static final String INVOICE_FUNDS = "QB_FEE_INVOICE_FUNDS";
    /** An amount out of range, or a kind that is not charged this way. */
    public static final String AMOUNT = "QB_FEE_AMOUNT";
    /** A billing mode or credit limit that is not accepted. */
    public static final String BILLING = "QB_FEE_BILLING";

    public static final List<String> ALL = List.of(UNPAID, CREDIT_EXCEEDED, OVERDUE, FUNDS, INVOICE_STATE, INVOICE_FUNDS,
        AMOUNT, BILLING);

    private FeeCodes() {}
}
