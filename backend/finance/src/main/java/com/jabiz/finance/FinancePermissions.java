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
    /** Record customer receipts, apply and unapply them and credits, and move an unapplied receipt (FIN-AR-007, 008). */
    public static final String RECEIPT_RECORD = "fin.receipt.record";
    /**
     * Take back an application of a receipt or credit memo and move a receipt to another customer (FIN-AR-008):
     * apart from recording receipts, so one person cannot move a payment from one customer to another (lapping).
     */
    public static final String RECEIPT_ADJUST = "fin.receipt.adjust";
    /** Void a receipt that bounced or was recorded in error: it takes cash back out of the books. */
    public static final String RECEIPT_VOID = "fin.receipt.void";
    /** Ask for an invoice to be written off, and record what was recovered of one (FIN-AR-012). */
    public static final String WRITE_OFF_REQUEST = "fin.writeoff.request";
    /** Approve write-offs: the level of the write-off approval rule (FIN-AR-012, FIN-CT-001). */
    public static final String WRITE_OFF_APPROVE = "fin.writeoff.approve";
    /** Approve invoices an approval rule stops, such as one over the customer's credit limit (FIN-AR-013). */
    public static final String INVOICE_APPROVE = "fin.invoice.approve";
    /** Keep the recurring invoice templates (FIN-AR-014). */
    public static final String RECURRING_INVOICE_MAINTAIN = "fin.invoice.recurring";
    /** Issue and send the documents of posted invoices and credit memos to customers (FIN-AR-005). */
    public static final String INVOICE_ISSUE = "fin.invoice.issue";

    /** Keep the company's profile: the name, address and remittance instructions its documents show (FIN-AR-005). */
    public static final String COMPANY_MAINTAIN = "fin.company.maintain";

    /** Read vendors, their 1099 settings, the 1099 thresholds and the payables settings (F4). */
    public static final String AP_READ = "fin.ap.read";
    /** Create and change vendors and record their W-9 (FIN-AP-001, 002); the TIN stays masked to the clerk. */
    public static final String VENDOR_MAINTAIN = "fin.vendor.maintain";
    /**
     * Ask for a vendor's bank details to change (FIN-AP-003): they take effect only once another person approves, and
     * whoever keeps them never releases payments (FIN-CT-001).
     */
    public static final String VENDOR_BANK_MAINTAIN = "fin.vendor.bank.maintain";
    /** Approve changes of vendors' bank details: the level of the approval rule {@code FIN_SETUP} proposes. */
    public static final String VENDOR_BANK_APPROVE = "fin.vendor.bank.approve";
    /**
     * Run {@code FIN_VENDOR_BANK_APPROVAL_RESULT} directly; granted to no role. Only the platform's approval events run
     * it, so a bank change takes effect only through an approval by another person.
     */
    public static final String VENDOR_BANK_RESULT = "fin.vendor.bank.result";
    /** See vendors' bank account numbers in plain text, one at a time and on the record (FIN-SC-004). */
    public static final String VENDOR_BANK_READ = "fin.vendor.bank.read";
    /** See taxpayer identification numbers in plain text, one at a time and on the record (FIN-AP-002, FIN-SC-004). */
    public static final String TAX_DATA_READ = "fin.tax.data.read";
    /** Keep the company's own bank accounts: what payments are made from (F4, design decision D3). */
    public static final String BANK_MAINTAIN = "fin.bank.maintain";
    /** See the company's bank account numbers in plain text, one at a time and on the record (FIN-SC-004). */
    public static final String BANK_READ = "fin.bank.read";
    /** Set the payables accounts and the default bank (F4). */
    public static final String AP_SETTINGS = "fin.ap.settings";
    /** Keep the 1099 threshold table (FIN-AP-021). */
    public static final String FORM_1099_MAINTAIN = "fin.1099.maintain";
    /** Enter and post vendor bills and credits (FIN-AP-004; phase F4b). */
    public static final String BILL_PREPARE = "fin.bill.prepare";
    /**
     * Void posted bills and vendor credits (F4b): what takes a liability out of the books is apart from entering it, and
     * never the preparer's own.
     */
    public static final String BILL_VOID = "fin.bill.void";
    /** Approve bills an approval rule stops, such as one above 10,000.00 (FIN-AP-006). */
    public static final String BILL_APPROVE = "fin.bill.approve";
    /**
     * Run {@code FIN_ASSET_CREATE} and {@code FIN_BILL_APPROVAL_RESULT} directly; granted to no role. Assets are made by
     * the bills that capitalize them, approvals of bills come from the platform's approval events.
     */
    public static final String AP_INTERNAL = "fin.ap.internal";
    /** Prepare payment runs (FIN-AP-010; phase F4c). */
    public static final String PAYMENT_PREPARE = "fin.payment.prepare";
    /**
     * Release approved payment runs to the bank (FIN-AP-011; phase F4c): the treasury's alone, never with preparing
     * payables or keeping vendors' bank details (FIN-CT-001).
     */
    public static final String PAYMENT_RELEASE = "fin.payment.release";

    /** Approve payment runs and other payments (FIN-AP-011, 015): the level of the rule {@code FIN_SETUP} proposes. */
    public static final String PAYMENT_APPROVE = "fin.payment.approve";
    /** Void a posted payment, such as a stopped check (FIN-AP-014). */
    public static final String PAYMENT_VOID = "fin.payment.void";
    /** Issue Forms 1099, export them for filing and file corrections; reads the files with TINs in full (FIN-AP-022). */
    public static final String FORM_1099_FILE = "fin.1099.file";

    private FinancePermissions() {}
}
