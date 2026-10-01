package com.jabiz.finance;

/**
 * Permission codes of finance ({@code fin.<module>.<action>}, backend/finance/CLAUDE.md section 2). They are granted
 * only through the roles {@code FIN_SETUP} creates (docs/finance/00-design.md section 4.6).
 */
public final class FinancePermissions {

    /** Run {@code FIN_SETUP}: create the finance roles and base data. */
    public static final String SETUP = "fin.setup";

    /** Read the chart of accounts. */
    public static final String ACCOUNT_READ = "fin.account.read";
    /** Create, change, deactivate and delete accounts; apply the chart template. */
    public static final String ACCOUNT_MAINTAIN = "fin.account.maintain";

    /** Read master data: dimensions, currencies, exchange rates. */
    public static final String MASTER_READ = "fin.master.read";
    /** Maintain the values of the analysis dimensions. */
    public static final String DIMENSION_MAINTAIN = "fin.dimension.maintain";
    /** Maintain currencies and exchange rates. */
    public static final String FX_MAINTAIN = "fin.fx.maintain";

    /** Read fiscal years and periods. */
    public static final String PERIOD_READ = "fin.period.read";
    /** Create fiscal years. */
    public static final String PERIOD_MAINTAIN = "fin.period.maintain";
    /** Change period states; post adjusting entries into soft-closed periods (FIN-PC-003). */
    public static final String PERIOD_CLOSE = "fin.period.close";

    /** Read journal entries. */
    public static final String JOURNAL_READ = "fin.journal.read";
    /** Prepare, submit and reverse journal entries (phase F1b). */
    public static final String JOURNAL_PREPARE = "fin.journal.prepare";
    /** Approve journal entries (the approval rules name it; phase F1b). */
    public static final String JOURNAL_APPROVE = "fin.journal.approve";
    /** Grant a journal entry's exception to post to a control account (FIN-GL-005; phase F1b). */
    public static final String JOURNAL_CONTROL_EXCEPTION = "fin.journal.control-exception";

    private FinancePermissions() {}
}
