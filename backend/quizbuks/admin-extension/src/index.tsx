import { defineExtension } from '@jabiz/admin'
import { ArrowLeftRight, BookOpenCheck, ClipboardCheck, FileSpreadsheet, Landmark, Scale, Users, Wallet } from 'lucide-react'
import FinanceOverviewPage from './FinanceOverviewPage'
import { PATH, PERMISSION } from './ids'
import JournalMappingPage from './JournalMappingPage'
import JournalPage from './JournalPage'
import { messages } from './messages'
import PublicationReviewPage from './PublicationReviewPage'
import ReconciliationPage from './ReconciliationPage'
import ReportsPage from './ReportsPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import TopUpsPage from './TopUpsPage'
import TransferReviewPage from './TransferReviewPage'
import TransfersPage from './TransfersPage'
import UserSummaryPage from './UserSummaryPage'
import UsersPage from './UsersPage'

/**
 * QuizBuks's own pages in the platform's admin frontend (docs/quizbuks/02-design.md section 7.3,
 * docs/quizbuks/plans/Q9a-admin-extension.md and Q9c-admin-finance.md; platform decision D22): the review of sponsor
 * applications and submitted publications with everything the decision is about, the user summary (P-01), and
 * finance's pages (the overview, transfers and their review, top-ups, the reconciliation, the monthly reports and the
 * journal export with its account mapping, Q9d).
 * The generated pages still list and show the entities; these are what the metadata cannot express. Every page reads
 * through the platform's generic endpoints, where the server checks each call; the menu's permissions only decide
 * what is offered.
 */
export default defineExtension({
  routes: [
    { path: PATH.reviews, element: <ReviewsPage /> },
    { path: '/qb/reviews/publications/:publicationId', element: <PublicationReviewPage /> },
    { path: '/qb/reviews/sponsors/:userId', element: <SponsorReviewPage /> },
    { path: PATH.users, element: <UsersPage /> },
    { path: '/qb/users/:userId', element: <UserSummaryPage /> },
    { path: PATH.finance, element: <FinanceOverviewPage /> },
    { path: PATH.transfers, element: <TransfersPage /> },
    { path: '/qb/finance/transfers/:transferId/review', element: <TransferReviewPage /> },
    { path: PATH.topUps, element: <TopUpsPage /> },
    { path: PATH.reconciliation, element: <ReconciliationPage /> },
    { path: PATH.reports, element: <ReportsPage /> },
    { path: PATH.journal, element: <JournalPage /> },
    { path: PATH.journalMapping, element: <JournalMappingPage /> },
  ],
  menu: [
    {
      key: 'quizbuks',
      label: 'menu.group',
      children: [
        {
          key: 'qb-reviews',
          label: 'menu.reviews',
          path: PATH.reviews,
          icon: ClipboardCheck,
          permission: PERMISSION.reviewPublication,
        },
        { key: 'qb-users', label: 'menu.users', path: PATH.users, icon: Users, permission: PERMISSION.usersRead },
      ],
    },
    {
      key: 'quizbuks-finance',
      label: 'menu.finance',
      children: [
        { key: 'qb-finance', label: 'menu.financeOverview', path: PATH.finance, icon: Landmark,
          permission: PERMISSION.financeRead },
        { key: 'qb-transfers', label: 'menu.transfers', path: PATH.transfers, icon: ArrowLeftRight,
          permission: PERMISSION.financeRead },
        { key: 'qb-topups', label: 'menu.topUps', path: PATH.topUps, icon: Wallet, permission: PERMISSION.financeRead },
        { key: 'qb-reconciliation', label: 'menu.reconciliation', path: PATH.reconciliation, icon: Scale,
          permission: PERMISSION.reconcile },
        { key: 'qb-reports', label: 'menu.reports', path: PATH.reports, icon: FileSpreadsheet,
          permission: PERMISSION.financeRead },
        { key: 'qb-journal', label: 'menu.journal', path: PATH.journal, icon: BookOpenCheck,
          permission: PERMISSION.financeRead },
      ],
    },
  ],
  messages,
})
