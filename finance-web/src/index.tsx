import { BankOutlined, BookOutlined, BuildOutlined, CalendarOutlined, DollarOutlined, GlobalOutlined, ImportOutlined,
  ShoppingCartOutlined } from '@ant-design/icons'
import { defineExtension, paths } from '@jabiz/admin'
import { DATASETS as BANK_DATASETS, IMPORTS as BANK_IMPORTS, PERMISSIONS as BANK_PERMISSIONS, QUERIES as BANK_QUERIES }
  from './bank/api'
import { DATASETS as FA_DATASETS, IMPORTS as FA_IMPORTS, PERMISSIONS as FA_PERMISSIONS, PROCESSES as FA_PROCESSES,
  QUERIES as FA_QUERIES } from './assets/api'
import AssetListPage from './assets/AssetListPage'
import AssetPage from './assets/AssetPage'
import DepreciationPage from './assets/DepreciationPage'
import { ASSETS_PATH, DEPRECIATION_PATH } from './assets/paths'
import MatchingPage from './bank/MatchingPage'
import { DATASETS as CLOSE_DATASETS, PERMISSIONS as CLOSE_PERMISSIONS, PROCESSES as CLOSE_PROCESSES,
  QUERIES as CLOSE_QUERIES } from './close/api'
import ClosePage from './close/ClosePage'
import { CLOSE_PATH } from './close/paths'
import { MATCHING_PATH, RECONCILIATIONS_PATH } from './bank/paths'
import ReconciliationListPage from './bank/ReconciliationListPage'
import ReconciliationPage from './bank/ReconciliationPage'
import { DATASETS as FX_DATASETS, PERMISSIONS as FX_PERMISSIONS, QUERIES as FX_QUERIES } from './fx/api'
import { REVALUATIONS_PATH } from './fx/paths'
import RevaluationPage from './fx/RevaluationPage'
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
 * register and bill entry, payment runs and the payment register; bank matching and reconciliations; the assets and
 * the depreciation runs; the foreign currency revaluations; the close workspace. Accounts,
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
    { path: MATCHING_PATH, element: <MatchingPage /> },
    { path: RECONCILIATIONS_PATH, element: <ReconciliationListPage /> },
    { path: `${RECONCILIATIONS_PATH}/:reconciliationId`, element: <ReconciliationPage /> },
    { path: ASSETS_PATH, element: <AssetListPage /> },
    { path: DEPRECIATION_PATH, element: <DepreciationPage /> },
    { path: `${ASSETS_PATH}/:assetId`, element: <AssetPage /> },
    { path: REVALUATIONS_PATH, element: <RevaluationPage /> },
    { path: CLOSE_PATH, element: <ClosePage /> },
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
        { key: 'periodTrialBalance', label: 'menu.periodTrialBalance', path: paths.report('finance.report.trial_balance'),
          permission: 'ledger.read' },
        { key: 'glRegister', label: 'menu.glRegister', path: paths.report('finance.gl.posting_register'),
          permission: 'ledger.read' },
        { key: 'glDetail', label: 'menu.glDetail', path: paths.report('finance.gl.detail'), permission: 'ledger.read' },
        // The reconciliation needs the receivables, payables and asset reads too.
        { key: 'subledgerReconciliation', label: 'menu.subledgerReconciliation',
          path: paths.report('finance.gl.subledger_reconciliation'), permission: 'fin.fa.read' },
        { key: 'trialBalanceCompare', label: 'menu.trialBalanceCompare',
          path: paths.report('finance.gl.trial_balance_compare'), permission: 'ledger.read' },
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
      key: 'bank',
      label: 'menu.bank',
      icon: <BankOutlined />,
      children: [
        { key: 'bankMatching', label: 'menu.bankMatching', path: MATCHING_PATH, permission: BANK_PERMISSIONS.read },
        { key: 'reconciliations', label: 'menu.reconciliations', path: RECONCILIATIONS_PATH,
          permission: BANK_PERMISSIONS.read },
        { key: 'statementImport', label: 'menu.statementImport', path: paths.importRun(BANK_IMPORTS.csv),
          permission: BANK_PERMISSIONS.statementImport },
        { key: 'statementImportBai2', label: 'menu.statementImportBai2', path: paths.importRun(BANK_IMPORTS.bai2),
          permission: BANK_PERMISSIONS.statementImport },
        { key: 'statementImportCamt', label: 'menu.statementImportCamt', path: paths.importRun(BANK_IMPORTS.camt053),
          permission: BANK_PERMISSIONS.statementImport },
        { key: 'statements', label: 'menu.statements', path: paths.dataset(BANK_DATASETS.statement),
          permission: BANK_PERMISSIONS.read },
        { key: 'transfers', label: 'menu.transfers', path: paths.dataset(BANK_DATASETS.transfer),
          permission: BANK_PERMISSIONS.read },
        { key: 'newTransfer', label: 'menu.newTransfer', path: paths.process('FIN_BANK_TRANSFER_POST', 1),
          permission: BANK_PERMISSIONS.transfer },
        { key: 'cashPosition', label: 'menu.cashPosition', path: paths.report(BANK_QUERIES.cashPosition),
          permission: BANK_PERMISSIONS.read },
        { key: 'staleChecks', label: 'menu.staleChecks', path: paths.report(BANK_QUERIES.staleChecks),
          permission: BANK_PERMISSIONS.read },
        { key: 'matchHistory', label: 'menu.matchHistory', path: paths.report(BANK_QUERIES.matchHistory),
          permission: BANK_PERMISSIONS.read },
        { key: 'recArchive', label: 'menu.recArchive', path: paths.reportArchive(BANK_QUERIES.reconciliation),
          permission: BANK_PERMISSIONS.archive },
        { key: 'entryRules', label: 'menu.entryRules', path: paths.dataset(BANK_DATASETS.rule),
          permission: BANK_PERMISSIONS.master },
        { key: 'bankSettings', label: 'menu.bankSettings', path: paths.dataset(BANK_DATASETS.settings),
          permission: BANK_PERMISSIONS.settings },
        { key: 'openingItemsImport', label: 'menu.openingItemsImport', path: paths.importRun(BANK_IMPORTS.opening),
          permission: BANK_PERMISSIONS.migration },
      ],
    },
    {
      key: 'fa',
      label: 'menu.fa',
      icon: <BuildOutlined />,
      children: [
        { key: 'assets', label: 'menu.assets', path: ASSETS_PATH, permission: FA_PERMISSIONS.read },
        { key: 'depreciation', label: 'menu.depreciation', path: DEPRECIATION_PATH, permission: FA_PERMISSIONS.read },
        { key: 'acquireAsset', label: 'menu.acquireAsset', path: paths.process(FA_PROCESSES.acquire, 1),
          permission: FA_PERMISSIONS.maintain },
        { key: 'assetRegister', label: 'menu.assetRegister', path: paths.report(FA_QUERIES.register),
          permission: FA_PERMISSIONS.read },
        { key: 'rollForward', label: 'menu.rollForward', path: paths.report(FA_QUERIES.rollForward),
          permission: FA_PERMISSIONS.read },
        { key: 'depreciationSchedule', label: 'menu.depreciationSchedule', path: paths.report(FA_QUERIES.schedule),
          permission: FA_PERMISSIONS.read },
        { key: 'assetRecords', label: 'menu.assetRecords', path: paths.dataset(FA_DATASETS.asset),
          permission: FA_PERMISSIONS.read },
        { key: 'assetClasses', label: 'menu.assetClasses', path: paths.dataset(FA_DATASETS.assetClass),
          permission: FA_PERMISSIONS.read },
        { key: 'faSettings', label: 'menu.faSettings', path: paths.dataset(FA_DATASETS.settings),
          permission: FA_PERMISSIONS.maintain },
        { key: 'assetImport', label: 'menu.assetImport', path: paths.importRun(FA_IMPORTS.register),
          permission: FA_PERMISSIONS.migration },
      ],
    },
    {
      key: 'fx',
      label: 'menu.fx',
      icon: <GlobalOutlined />,
      children: [
        { key: 'revaluations', label: 'menu.revaluations', path: REVALUATIONS_PATH, permission: FX_PERMISSIONS.read },
        { key: 'fxItems', label: 'menu.fxItems', path: paths.report(FX_QUERIES.items), permission: FX_PERMISSIONS.run },
        { key: 'fxGainsLosses', label: 'menu.fxGainsLosses', path: paths.report(FX_QUERIES.gainsLosses),
          permission: FX_PERMISSIONS.ledger },
        { key: 'fxRates', label: 'menu.fxRates', path: paths.dataset(FX_DATASETS.rate), permission: FX_PERMISSIONS.read },
        { key: 'fxSettings', label: 'menu.fxSettings', path: paths.dataset(FX_DATASETS.settings),
          permission: FX_PERMISSIONS.settings },
      ],
    },
    {
      key: 'close',
      label: 'menu.close',
      icon: <CalendarOutlined />,
      children: [
        { key: 'closeWorkspace', label: 'menu.closeWorkspace', path: CLOSE_PATH, permission: CLOSE_PERMISSIONS.read },
        { key: 'closeOverview', label: 'menu.closeOverview', path: paths.report(CLOSE_QUERIES.overview),
          permission: CLOSE_PERMISSIONS.read },
        // Both reports need the journal, receivables and payables reads; the roles with the receivables read have all three.
        { key: 'closeExceptions', label: 'menu.closeExceptions', path: paths.report(CLOSE_QUERIES.exceptions),
          permission: 'fin.ar.read' },
        { key: 'closeArtifacts', label: 'menu.closeArtifacts', path: paths.report(CLOSE_QUERIES.artifacts),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'priorPeriodItems', label: 'menu.priorPeriodItems', path: paths.report(CLOSE_QUERIES.priorPeriod),
          permission: 'fin.ar.read' },
        { key: 'periods', label: 'menu.periods', path: paths.dataset(CLOSE_DATASETS.period),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'fiscalYears', label: 'menu.fiscalYears', path: paths.dataset(CLOSE_DATASETS.fiscalYear),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'reopenRequests', label: 'menu.reopenRequests', path: paths.dataset(CLOSE_DATASETS.reopen),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'yearCloses', label: 'menu.yearCloses', path: paths.dataset(CLOSE_DATASETS.yearClose),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'closeChecklist', label: 'menu.closeChecklist', path: paths.dataset(CLOSE_DATASETS.template),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'closeTemplateSave', label: 'menu.closeTemplateSave', path: paths.process(CLOSE_PROCESSES.templateSave, 1),
          permission: CLOSE_PERMISSIONS.close },
        { key: 'closeSettings', label: 'menu.closeSettings', path: paths.dataset(CLOSE_DATASETS.settings),
          permission: CLOSE_PERMISSIONS.read },
        { key: 'closeSettingsSet', label: 'menu.closeSettingsSet', path: paths.process(CLOSE_PROCESSES.settings, 1),
          permission: CLOSE_PERMISSIONS.close },
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
