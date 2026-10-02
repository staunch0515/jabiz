import { BookOutlined, DollarOutlined, ImportOutlined, ShoppingCartOutlined } from '@ant-design/icons'
import { defineExtension, paths } from '@jabiz/admin'
import JournalEntryRoute from './journal/JournalEntryRoute'
import JournalListPage from './journal/JournalListPage'
import { JOURNALS_PATH, NEW_JOURNAL_PATH } from './journal/paths'
import { messages } from './messages'
import { DATASETS as AP_DATASETS, PERMISSIONS as AP_PERMISSIONS, QUERIES as AP_QUERIES } from './payables/api'
import BillListPage from './payables/BillListPage'
import BillRoute from './payables/BillRoute'
import PaymentListPage from './payables/PaymentListPage'
import PaymentRunPage from './payables/PaymentRunPage'
import { BILLS_PATH, NEW_BILL_PATH, NEW_RUN_PATH, PAYMENTS_PATH, RUNS_PATH } from './payables/paths'
import ProposeRunPage from './payables/ProposeRunPage'
import RunListPage from './payables/RunListPage'
import { DATASETS, PERMISSIONS, QUERIES } from './receivables/api'
import InvoiceListPage from './receivables/InvoiceListPage'
import InvoiceRoute from './receivables/InvoiceRoute'
import { INVOICES_PATH, NEW_INVOICE_PATH, NEW_RECEIPT_PATH, RECEIPTS_PATH } from './receivables/paths'
import ReceiptListPage from './receivables/ReceiptListPage'
import ReceiptRoute from './receivables/ReceiptRoute'

/**
 * The finance application's own admin pages (decision D22, docs/finance/00-design.md section 17): the journal
 * register and the journal entry grid; the invoice and receipt registers, invoice and receipt entry; the bill
 * register and bill entry, payment runs and the payment register. Accounts,
 * customers, dimensions, periods and the other master data keep the generated pages; the reports run on the
 * platform's report page and the imports in its import wizard (section 13).
 */
export default defineExtension({
  routes: [
    { path: JOURNALS_PATH, element: <JournalListPage /> },
    { path: NEW_JOURNAL_PATH, element: <JournalEntryRoute /> },
    { path: `${JOURNALS_PATH}/:journalId`, element: <JournalEntryRoute /> },
    { path: INVOICES_PATH, element: <InvoiceListPage /> },
    { path: NEW_INVOICE_PATH, element: <InvoiceRoute /> },
    { path: `${INVOICES_PATH}/:invoiceId`, element: <InvoiceRoute /> },
    { path: RECEIPTS_PATH, element: <ReceiptListPage /> },
    { path: NEW_RECEIPT_PATH, element: <ReceiptRoute /> },
    { path: `${RECEIPTS_PATH}/:receiptId`, element: <ReceiptRoute /> },
    { path: BILLS_PATH, element: <BillListPage /> },
    { path: NEW_BILL_PATH, element: <BillRoute /> },
    { path: `${BILLS_PATH}/:billId`, element: <BillRoute /> },
    { path: RUNS_PATH, element: <RunListPage /> },
    { path: NEW_RUN_PATH, element: <ProposeRunPage /> },
    { path: `${RUNS_PATH}/:runId`, element: <PaymentRunPage /> },
    { path: PAYMENTS_PATH, element: <PaymentListPage /> },
  ],
  menu: [
    {
      key: 'gl',
      label: 'menu.gl',
      icon: <BookOutlined />,
      children: [
        { key: 'journals', label: 'menu.journals', path: JOURNALS_PATH, permission: 'fin.journal.read' },
        { key: 'newJournal', label: 'menu.newJournal', path: NEW_JOURNAL_PATH, permission: 'fin.journal.prepare' },
        {
          key: 'trialBalance',
          label: 'menu.trialBalance',
          path: paths.report('finance.gl.trial_balance'),
          permission: 'ledger.read',
        },
        {
          key: 'accountInquiry',
          label: 'menu.accountInquiry',
          path: paths.report('finance.gl.account_inquiry'),
          permission: 'ledger.read',
        },
      ],
    },
    {
      key: 'ar',
      label: 'menu.ar',
      icon: <DollarOutlined />,
      children: [
        { key: 'invoices', label: 'menu.invoices', path: INVOICES_PATH, permission: PERMISSIONS.read },
        { key: 'newInvoice', label: 'menu.newInvoice', path: NEW_INVOICE_PATH, permission: PERMISSIONS.prepare },
        { key: 'receipts', label: 'menu.receipts', path: RECEIPTS_PATH, permission: PERMISSIONS.read },
        { key: 'newReceipt', label: 'menu.newReceipt', path: NEW_RECEIPT_PATH, permission: PERMISSIONS.receipt },
        { key: 'customers', label: 'menu.customers', path: paths.dataset(DATASETS.customer), permission: PERMISSIONS.read },
        { key: 'aging', label: 'menu.aging', path: paths.report(QUERIES.aging), permission: PERMISSIONS.read },
        { key: 'statement', label: 'menu.statement', path: paths.report(QUERIES.statement), permission: PERMISSIONS.read },
        { key: 'salesTax', label: 'menu.salesTax', path: paths.report(QUERIES.salesTax), permission: PERMISSIONS.read },
        { key: 'certificates', label: 'menu.certificates', path: paths.report(QUERIES.certificates),
          permission: PERMISSIONS.read },
        { key: 'companyProfile', label: 'menu.companyProfile', path: paths.dataset(DATASETS.company),
          permission: PERMISSIONS.company },
      ],
    },
    {
      key: 'ap',
      label: 'menu.ap',
      icon: <ShoppingCartOutlined />,
      children: [
        { key: 'bills', label: 'menu.bills', path: BILLS_PATH, permission: AP_PERMISSIONS.read },
        { key: 'newBill', label: 'menu.newBill', path: NEW_BILL_PATH, permission: AP_PERMISSIONS.prepare },
        { key: 'paymentRuns', label: 'menu.paymentRuns', path: RUNS_PATH, permission: AP_PERMISSIONS.read },
        { key: 'newPaymentRun', label: 'menu.newPaymentRun', path: NEW_RUN_PATH, permission: AP_PERMISSIONS.payment },
        { key: 'payments', label: 'menu.payments', path: PAYMENTS_PATH, permission: AP_PERMISSIONS.read },
        { key: 'vendors', label: 'menu.vendors', path: paths.dataset(AP_DATASETS.vendor), permission: AP_PERMISSIONS.read },
        { key: 'apAging', label: 'menu.apAging', path: paths.report(AP_QUERIES.aging), permission: AP_PERMISSIONS.read },
        { key: 'vendorStatement', label: 'menu.vendorStatement', path: paths.report(AP_QUERIES.statement),
          permission: AP_PERMISSIONS.read },
        { key: 'form1099', label: 'menu.form1099', path: paths.report(AP_QUERIES.form1099), permission: AP_PERMISSIONS.read },
        { key: 'review1099', label: 'menu.review1099', path: paths.report(AP_QUERIES.review1099),
          permission: AP_PERMISSIONS.form1099 },
        { key: 'apSettings', label: 'menu.apSettings', path: paths.dataset(AP_DATASETS.settings),
          permission: AP_PERMISSIONS.settings },
        { key: 'bankAccounts', label: 'menu.bankAccounts', path: paths.dataset(AP_DATASETS.bank),
          permission: AP_PERMISSIONS.bank },
      ],
    },
    {
      key: 'imports',
      label: 'menu.imports',
      icon: <ImportOutlined />,
      children: [
        { key: 'journalImport', label: 'menu.journalImport', path: paths.importRun('finance.journals'),
          permission: 'fin.journal.prepare' },
        { key: 'payrollImport', label: 'menu.payrollImport', path: paths.importRun('finance.payroll'),
          permission: 'fin.payroll.import' },
        { key: 'openingImport', label: 'menu.openingImport', path: paths.importRun('finance.opening_balances'),
          permission: 'fin.migration' },
        { key: 'chartImport', label: 'menu.chartImport', path: paths.importRun('finance.chart'),
          permission: 'fin.account.maintain' },
        { key: 'vendorImport', label: 'menu.vendorImport', path: paths.importRun('finance.vendors'),
          permission: AP_PERMISSIONS.vendor },
        { key: 'thresholdImport', label: 'menu.thresholdImport', path: paths.importRun('finance.ap_thresholds'),
          permission: AP_PERMISSIONS.form1099 },
        { key: 'openPayablesImport', label: 'menu.openPayablesImport', path: paths.importRun('finance.open_payables'),
          permission: 'fin.migration' },
        {
          key: 'migrationReport',
          label: 'menu.migrationReport',
          path: paths.report('finance.migration.reconciliation'),
          permission: 'fin.journal.read',
        },
        { key: 'importRuns', label: 'menu.importRuns', path: paths.importRuns(), permission: 'fin.import' },
      ],
    },
  ],
  messages,
  home: JOURNALS_PATH,
})
