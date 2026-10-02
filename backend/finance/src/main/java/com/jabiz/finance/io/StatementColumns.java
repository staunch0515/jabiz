package com.jabiz.finance.io;

import java.util.List;

/**
 * The columns the statement parsers produce, one record per statement line, each record carrying its statement's
 * account and balances as well (an import hands a group of rows, not the file's header, to its process).
 */
final class StatementColumns {

    static final String DATE = "date";
    static final String REFERENCE = "reference";
    static final String DESCRIPTION = "description";
    static final String AMOUNT = "amount";
    static final String TYPE = "type";
    /** The last four characters of the account number: enough to tell the account, not to reveal it. */
    static final String ACCOUNT = "account";
    static final String FROM = "from";
    static final String TO = "to";
    static final String OPENING = "opening";
    static final String CLOSING = "closing";
    static final String CURRENCY = "currency";

    static final List<String> ALL = List.of(DATE, REFERENCE, DESCRIPTION, AMOUNT, TYPE, ACCOUNT, FROM, TO, OPENING,
        CLOSING, CURRENCY);

    private StatementColumns() {}
}
