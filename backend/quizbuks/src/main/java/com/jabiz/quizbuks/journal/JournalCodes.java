package com.jabiz.quizbuks.journal;

import java.util.List;

/** The error codes of the journal export (Q9d), each with a text in the three languages. */
public final class JournalCodes {

    /** Accounts the export moves have no account code of the accounting system (param {@code accounts}). */
    public static final String ACCOUNT_UNMAPPED = "QB_JOURNAL_ACCOUNT_UNMAPPED";
    /** Account codes that are not 1–20 letters, digits, {@code . _ -} (param {@code accounts}). */
    public static final String ACCOUNT_INVALID = "QB_JOURNAL_ACCOUNT_INVALID";
    /**
     * The daily reconciliation has not run since the period ended, or its latest run found differences: the books
     * are not exported until it is clean (requirement 7.4).
     */
    public static final String RECON_NOT_OK = "QB_JOURNAL_RECON_NOT_OK";
    /** The period has not ended yet (params {@code month}, {@code endsAt}). */
    public static final String PERIOD_OPEN = "QB_JOURNAL_PERIOD_OPEN";
    /** The month asked for is not the one after the last export (params {@code month}, {@code next}). */
    public static final String PERIOD_NOT_NEXT = "QB_JOURNAL_PERIOD_NOT_NEXT";
    /** Nothing was ever posted: there is no first period to export. */
    public static final String NOTHING_POSTED = "QB_JOURNAL_NOTHING_POSTED";
    /** The voucher's debits and credits differ (params {@code debit}, {@code credit}): a posting is missing. */
    public static final String UNBALANCED = "QB_JOURNAL_UNBALANCED";
    /** The export already has the accounting system's voucher number (param {@code externalVoucherNo}). */
    public static final String ALREADY_CONFIRMED = "QB_JOURNAL_ALREADY_CONFIRMED";

    /**
     * A single transaction is over half of the platform's read limit or of a generated file's (params {@code rows},
     * {@code bytes}): the detail cannot be cut into parts within them (review H1).
     */
    public static final String TOO_LARGE = "QB_JOURNAL_TOO_LARGE";
    /**
     * Another database transaction has been open for long (param {@code seconds}): a posting it began before the
     * cut-off could commit after the export and be in no export (review M1). Try again once it has ended.
     */
    public static final String TRANSACTION_OPEN = "QB_JOURNAL_TRANSACTION_OPEN";

    public static final List<String> ALL = List.of(ACCOUNT_UNMAPPED, ACCOUNT_INVALID, RECON_NOT_OK, PERIOD_OPEN,
        PERIOD_NOT_NEXT, NOTHING_POSTED, UNBALANCED, ALREADY_CONFIRMED, TOO_LARGE, TRANSACTION_OPEN);

    private JournalCodes() {}
}
