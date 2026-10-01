import { BookOutlined, ImportOutlined } from '@ant-design/icons'
import { defineExtension, paths } from '@jabiz/admin'
import JournalEntryRoute from './journal/JournalEntryRoute'
import JournalListPage from './journal/JournalListPage'
import { JOURNALS_PATH, NEW_JOURNAL_PATH } from './journal/paths'
import { messages } from './messages'

/**
 * The finance application's own admin pages (decision D22, docs/finance/00-design.md section 17): the journal
 * register and the journal entry grid. Accounts, dimensions, periods and the other master data keep the generated
 * pages; the reports run on the platform's report page and the imports in its import wizard (section 13).
 */
export default defineExtension({
  routes: [
    { path: JOURNALS_PATH, element: <JournalListPage /> },
    { path: NEW_JOURNAL_PATH, element: <JournalEntryRoute /> },
    { path: `${JOURNALS_PATH}/:journalId`, element: <JournalEntryRoute /> },
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
