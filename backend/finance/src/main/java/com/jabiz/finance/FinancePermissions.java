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

    /**
     * Run {@code FIN_JOURNAL_POST} directly; granted to no role. The journal processes call it as their subprocess
     * after their own checks (platform decision D11), so posting happens only through them.
     */
    public static final String JOURNAL_POST = "fin.journal.post";
    /**
     * Run {@code FIN_SUBLEDGER_POST} / {@code FIN_SUBLEDGER_REVERSE} directly; granted to no role. Subledger documents
     * post through their own processes, which call them after their checks (FIN-GL-021).
     */
    public static final String SUBLEDGER_POST = "fin.subledger.post";
    /** Prepare, post and void invoices and credit memos, and apply credits (FIN-AR-003, 004, 006). */
    public static final String INVOICE_PREPARE = "fin.invoice.prepare";
    /**
     * Post credit memos and void invoices (FIN-AR-004, 006): what writes receivables down is apart from preparing
     * documents, and never the preparer's own (FIN-CT-001).
     */
    public static final String INVOICE_CREDIT = "fin.invoice.credit";
    /** Maintain recurring entry templates (FIN-GL-017). */
    public static final String RECURRING_MAINTAIN = "fin.recurring.maintain";
    /** Upload and read the files of the finance imports (FIN-DI-001); each import needs its own permission too. */
    public static final String IMPORT = "fin.import";
    /**
     * Open the books: post the opening entry, record the migration's decisions, close the opening period
     * (FIN-PC-002, FIN-DI-002, FIN-DI-003).
     */
    public static final String MIGRATION = "fin.migration";
    /** Keep the payroll provider's code mapping (FIN-DI-004). */
    public static final String PAYROLL_MAINTAIN = "fin.payroll.maintain";
    /** Import the payroll provider's results as summary journal entries (FIN-DI-004). */
    public static final String PAYROLL_IMPORT = "fin.payroll.import";
    /** Upload supporting documents of journal entries (FIN-GL-016). */
    public static final String JOURNAL_ATTACH = "fin.journal.attach";

    /** Read customers, payment terms, certificates and the receivables settings (F3). */
    public static final String AR_READ = "fin.ar.read";
    /** Create and change customers and their exemption certificates (FIN-AR-001, FIN-TX-004). */
    public static final String CUSTOMER_MAINTAIN = "fin.customer.maintain";
    /**
     * Give a customer a tax code that charges no tax, and record exemption certificates (FIN-TX-002, 004): what makes
     * a customer's sales tax-free is not the clerk's alone (FIN-CT-001).
     */
    public static final String CUSTOMER_TAX = "fin.customer.tax";
    /** Set customers' credit limits (FIN-AR-013): apart from keeping customers. */
    public static final String CUSTOMER_CREDIT = "fin.customer.credit";
    /** Maintain sales tax jurisdictions, rates and codes (FIN-TX-001). */
    public static final String TAX_MAINTAIN = "fin.tax.maintain";
    /** Set the receivables accounts and policies, and payment terms (FIN-AR-002). */
    public static final String AR_SETTINGS = "fin.ar.settings";

    private FinancePermissions() {}
}
