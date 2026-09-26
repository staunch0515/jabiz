package com.jabiz.runtime.ledger;

/** Permission codes of the ledger (docs/design/11-ledger-events-jobs.md section 1). */
public final class LedgerPermissions {

    /** Read accounts through their dataset. */
    public static final String ACCOUNT_READ = "ledger.account.read";
    /** Open, rename, close and reopen accounts through their dataset. */
    public static final String ACCOUNT_WRITE = "ledger.account.write";
    /** Read transactions, entries and balances. */
    public static final String READ = "ledger.read";
    /** Run {@code LEDGER_POST}; also the declared write permission of the transaction and entry datasets. */
    public static final String POST = "ledger.post";
    /** Run {@code LEDGER_REVERSE}. */
    public static final String REVERSE = "ledger.reverse";

    private LedgerPermissions() {}
}
