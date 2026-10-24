package com.jabiz.quizbuks.wallet;

import java.util.List;

/** The error codes of top-ups (Q6a) and payout accounts (Q6b), each with a text in the three languages. */
public final class WalletCodes {

    /** The amount is not whole Kudos within {@code qb.topup.min} – {@code qb.topup.max} (params {@code min}, {@code max}). */
    public static final String TOPUP_AMOUNT_RANGE = "QB_TOPUP_AMOUNT_RANGE";
    /** The sponsor has {@link WalletLimits#MAX_OPEN_TOPUPS} unfinished top-ups already (param {@code max}). */
    public static final String TOPUP_TOO_MANY_OPEN = "QB_TOPUP_TOO_MANY_OPEN";
    /** Top-ups are not configured here: the sponsor console's address, where Stripe returns, is missing. */
    public static final String TOPUP_UNAVAILABLE = "QB_TOPUP_UNAVAILABLE";
    /**
     * A new sponsor's top-ups in all would pass {@code qb.topup.new-sponsor.max-total} (Q10b, M3; params
     * {@code remaining}, {@code max} and {@code until}, "-" before the first paid top-up).
     */
    public static final String TOPUP_NEW_SPONSOR_LIMIT = "QB_TOPUP_NEW_SPONSOR_LIMIT";
    /** Bank top-ups are not set up here: the platform's receiving account is not set (Q13a). */
    public static final String TOPUP_BANK_UNAVAILABLE = "QB_TOPUP_BANK_UNAVAILABLE";
    /** Q18a: the bank top-up's settings, proposed in one step. */
    public static final String BANK_SETTINGS_RANGE = "QB_BANK_SETTINGS_RANGE";
    public static final String BANK_SETTINGS_OPEN = "QB_BANK_SETTINGS_OPEN";
    public static final String BANK_SETTINGS_UNCHANGED = "QB_BANK_SETTINGS_UNCHANGED";
    public static final String BANK_SETTINGS_NOTHING = "QB_BANK_SETTINGS_NOTHING";
    /** The bank top-up is not in a state for this (param {@code status}; Q13a). */
    public static final String TOPUP_BANK_STATE = "QB_TOPUP_BANK_STATE";
    /** The payer's name is empty, too long or not printable (param {@code max}; Q13a). */
    public static final String TOPUP_BANK_PAYER_NAME = "QB_TOPUP_BANK_PAYER_NAME";
    /** A transfer or receipt day after today (Q13a). */
    public static final String TOPUP_BANK_DATE_FUTURE = "QB_TOPUP_BANK_DATE_FUTURE";
    /**
     * No approval rule asks for another administrator's approval of bank top-ups (Q13a, B-1): without one nothing is
     * recorded, so that money is never credited by one person.
     */
    public static final String TOPUP_BANK_NO_REVIEW = "QB_TOPUP_BANK_NO_REVIEW";
    /** The statement's entry (day and reference) is recorded against another top-up already (Q13a). */
    public static final String TOPUP_BANK_REFERENCE_USED = "QB_TOPUP_BANK_REFERENCE_USED";
    /** An administrator recording a top-up of their own (Q13a). */
    public static final String TOPUP_BANK_OWN = "QB_TOPUP_BANK_OWN";

    /** The taker's country is not the one payout accounts are opened in (param {@code country}; Q18: no cross-border). */
    public static final String PAYOUT_COUNTRY_UNSUPPORTED = "QB_PAYOUT_COUNTRY_UNSUPPORTED";
    /** Payout accounts are not configured here: the app's address, where Stripe returns, is missing. */
    public static final String PAYOUT_UNAVAILABLE = "QB_PAYOUT_UNAVAILABLE";
    /** The taker disconnected their payout account from QuizBuks: finance opens it again. */
    public static final String PAYOUT_ACCOUNT_CLOSED = "QB_PAYOUT_ACCOUNT_CLOSED";
    /** A release of a taker whose transfers are not held (nothing rejected or reversed since the last release). */
    public static final String TRANSFER_NOT_HELD = "QB_TRANSFER_NOT_HELD";
    /** A finance administrator releasing transfers to themselves (four eyes, as with the review). */
    public static final String TRANSFER_RELEASE_OWN = "QB_TRANSFER_RELEASE_OWN";
    /** A finance administrator transferring to themselves at once (Q14): another administrator does it. */
    public static final String TRANSFER_NOW_OWN = "QB_TRANSFER_NOW_OWN";

    /** A bank account field that is not valid: a code's digits, the kind, the number, the holder's katakana (Q13b). */
    public static final String BANK_ACCOUNT_INVALID = "QB_BANK_ACCOUNT_INVALID";
    /** The bank account is another user's already (Q13b): finance is told. */
    public static final String BANK_ACCOUNT_IN_USE = "QB_BANK_ACCOUNT_IN_USE";
    /** Paid by bank without a bank account (Q13b). */
    public static final String BANK_ACCOUNT_MISSING = "QB_BANK_ACCOUNT_MISSING";
    /** The way of being paid changes only with nothing unfinished (Q13b). */
    public static final String PAYOUT_METHOD_OPEN_TRANSFER = "QB_PAYOUT_METHOD_OPEN_TRANSFER";
    /**
     * A withdrawal cannot be asked for now (param {@code reason}: off, banned, held, an unfinished transfer, not paid
     * by bank, the cooling-off period (param {@code until}), no review rule; Q13b).
     */
    public static final String WITHDRAWAL_UNAVAILABLE = "QB_WITHDRAWAL_UNAVAILABLE";
    /** The amount is not whole Kudos from the threshold to what may be withdrawn, above the fee (params; Q13b). */
    public static final String WITHDRAWAL_AMOUNT = "QB_WITHDRAWAL_AMOUNT";
    /** The withdrawal is not in a state for this (param {@code status}; Q13b). */
    public static final String WITHDRAWAL_STATE = "QB_WITHDRAWAL_STATE";
    /** A finance administrator paying or returning their own withdrawal (Q13b). */
    public static final String WITHDRAWAL_OWN = "QB_WITHDRAWAL_OWN";
    /** A finance administrator paying or returning a withdrawal they approved (Q13b, four eyes). */
    public static final String WITHDRAWAL_APPROVER = "QB_WITHDRAWAL_APPROVER";
    /** The statement's entry (day and reference) paid another withdrawal already (Q13b). */
    public static final String WITHDRAWAL_REFERENCE_USED = "QB_WITHDRAWAL_REFERENCE_USED";

    /** A receipt is only for a paid top-up (Q9d, F-09). */
    public static final String RECEIPT_NOT_PAID = "QB_RECEIPT_NOT_PAID";
    /** The receipts' issuer (the operator's name, {@code qb.issuer.name}) is not set yet (Q9d, F-09). */
    public static final String RECEIPT_NO_ISSUER = "QB_RECEIPT_NO_ISSUER";
    /**
     * No first receipt for a top-up refunded (in part) or disputed (open or lost): the money was not, or may not stay,
     * received (Q9d review M2). A receipt issued before stays as issued.
     */
    public static final String RECEIPT_REFUNDED = "QB_RECEIPT_REFUNDED";

    public static final List<String> ALL = List.of(TOPUP_AMOUNT_RANGE, TOPUP_TOO_MANY_OPEN, TOPUP_UNAVAILABLE,
        TOPUP_NEW_SPONSOR_LIMIT, TOPUP_BANK_UNAVAILABLE, TOPUP_BANK_STATE, TOPUP_BANK_PAYER_NAME, TOPUP_BANK_DATE_FUTURE,
        TOPUP_BANK_NO_REVIEW, TOPUP_BANK_REFERENCE_USED, TOPUP_BANK_OWN,
        PAYOUT_COUNTRY_UNSUPPORTED, PAYOUT_UNAVAILABLE, PAYOUT_ACCOUNT_CLOSED, TRANSFER_NOT_HELD, TRANSFER_RELEASE_OWN,
        TRANSFER_NOW_OWN, BANK_ACCOUNT_INVALID, BANK_ACCOUNT_IN_USE, BANK_ACCOUNT_MISSING, PAYOUT_METHOD_OPEN_TRANSFER,
        WITHDRAWAL_UNAVAILABLE, WITHDRAWAL_AMOUNT, WITHDRAWAL_STATE, WITHDRAWAL_OWN, WITHDRAWAL_APPROVER, WITHDRAWAL_REFERENCE_USED,
        RECEIPT_NOT_PAID, RECEIPT_NO_ISSUER, RECEIPT_REFUNDED, BANK_SETTINGS_RANGE, BANK_SETTINGS_OPEN,
        BANK_SETTINGS_UNCHANGED, BANK_SETTINGS_NOTHING);

    private WalletCodes() {}
}
