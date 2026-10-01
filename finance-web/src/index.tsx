import { BookOutlined } from '@ant-design/icons'
import { defineExtension, paths } from '@jabiz/admin'
import JournalEntryRoute from './journal/JournalEntryRoute'
import JournalListPage from './journal/JournalListPage'
import { JOURNALS_PATH, NEW_JOURNAL_PATH } from './journal/paths'
import { messages } from './messages'

/**
 * The finance application's own admin pages (decision D22, docs/finance/00-design.md section 17): the journal
 * register and the journal entry grid. Accounts, dimensions, periods and the other master data keep the generated
 * pages; the reports run on the platform's report page.
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
  ],
  messages,
  home: JOURNALS_PATH,
})
