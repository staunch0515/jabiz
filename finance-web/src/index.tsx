import { BookOutlined, DollarOutlined, ImportOutlined } from '@ant-design/icons'
import { defineExtension, paths } from '@jabiz/admin'
import JournalEntryRoute from './journal/JournalEntryRoute'
import JournalListPage from './journal/JournalListPage'
import { JOURNALS_PATH, NEW_JOURNAL_PATH } from './journal/paths'
import { messages } from './messages'
import { DATASETS, PERMISSIONS, QUERIES } from './receivables/api'
import InvoiceListPage from './receivables/InvoiceListPage'
import InvoiceRoute from './receivables/InvoiceRoute'
import { INVOICES_PATH, NEW_INVOICE_PATH, NEW_RECEIPT_PATH, RECEIPTS_PATH } from './receivables/paths'
import ReceiptListPage from './receivables/ReceiptListPage'
import ReceiptRoute from './receivables/ReceiptRoute'

/**
 * The finance application's own admin pages (decision D22, docs/finance/00-design.md section 17): the journal
 * register and the journal entry grid; the invoice and receipt registers, invoice and receipt entry. Accounts,
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
