import { defineExtension } from '@jabiz/admin'
import {
  ArrowLeftRight,
  Banknote,
  BookOpenCheck,
  ClipboardCheck,
  FileQuestion,
  FileSpreadsheet,
  HandCoins,
  Gift,
  Landmark,
  ListChecks,
  Megaphone,
  Rocket,
  Scale,
  ScrollText,
  Settings,
  TrendingUp,
  Sparkles,
  Ticket,
  Users,
  Wallet,
} from 'lucide-react'
import BankTopUpPage from './BankTopUpPage'
import BankSettingsPage from './BankSettingsPage'
import BankTopUpsPage from './BankTopUpsPage'
import BankWithdrawalsPage from './BankWithdrawalsPage'
import ManualCreditPage from './ManualCreditPage'
import ManualCreditsPage from './ManualCreditsPage'
import BroadcastsPage from './BroadcastsPage'
import AiPage from './AiPage'
import AttemptsPage from './AttemptsPage'
import FinanceOverviewPage from './FinanceOverviewPage'
import { PATH, PERMISSION } from './ids'
import JournalMappingPage from './JournalMappingPage'
import JournalPage from './JournalPage'
import LegalPage from './LegalPage'
import { messages } from './messages'
import PublicationReviewPage from './PublicationReviewPage'
import PublicationsPage from './PublicationsPage'
import RedemptionsPage from './RedemptionsPage'
import QuizDetailPage from './QuizDetailPage'
import QuizzesPage from './QuizzesPage'
import ReconciliationPage from './ReconciliationPage'
import ReportsPage from './ReportsPage'
import InvestorMetricsPage from './InvestorMetricsPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import CashRewardsPage from './CashRewardsPage'
import DigitalPrizesPage from './DigitalPrizesPage'
import TopUpsPage from './TopUpsPage'
import TransferReviewPage from './TransferReviewPage'
import TransfersPage from './TransfersPage'
import UserSummaryPage from './UserSummaryPage'
import UsersPage from './UsersPage'

/**
 * QuizBuks's own pages in the platform's admin frontend (docs/quizbuks/02-design.md section 7.3,
 * docs/quizbuks/plans/Q9a-admin-extension.md and Q9c-admin-finance.md; platform decision D22): every quiz and every publication (Q17), the review of sponsor
 * applications and submitted publications with everything the decision is about, the user summary (P-01), and
 * the legal texts (publishing the drafts and withdrawing announced versions, Q7b), the announcements and notices (Q7c), finance's pages (the overview, transfers and their review, top-ups, the reconciliation, the monthly reports and the
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
    { path: PATH.attempts, element: <AttemptsPage /> },
    { path: PATH.quizzes, element: <QuizzesPage /> },
    { path: '/qb/quizzes/:quizId', element: <QuizDetailPage /> },
    { path: PATH.publications, element: <PublicationsPage /> },
    { path: PATH.cashRewards, element: <CashRewardsPage /> },
    { path: PATH.redemptions, element: <RedemptionsPage /> },
    { path: PATH.digitalPrizes, element: <DigitalPrizesPage /> },
    { path: PATH.finance, element: <FinanceOverviewPage /> },
    { path: PATH.transfers, element: <TransfersPage /> },
    { path: '/qb/finance/transfers/:transferId/review', element: <TransferReviewPage /> },
    { path: PATH.topUps, element: <TopUpsPage /> },
    { path: PATH.bankTopUps, element: <BankTopUpsPage /> },
    { path: PATH.bankSettings, element: <BankSettingsPage /> },
    { path: '/qb/finance/bank-topups/:topUpId', element: <BankTopUpPage /> },
    { path: PATH.bankWithdrawals, element: <BankWithdrawalsPage /> },
    { path: PATH.manualCredits, element: <ManualCreditsPage /> },
    { path: '/qb/finance/manual-credits/:topUpId', element: <ManualCreditPage /> },
    { path: PATH.reconciliation, element: <ReconciliationPage /> },
    { path: PATH.reports, element: <ReportsPage /> },
    { path: PATH.investorMetrics, element: <InvestorMetricsPage /> },
    { path: PATH.journal, element: <JournalPage /> },
    { path: PATH.journalMapping, element: <JournalMappingPage /> },
    { path: PATH.legal, element: <LegalPage /> },
    { path: PATH.broadcasts, element: <BroadcastsPage /> },
    { path: PATH.ai, element: <AiPage /> },
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
        // Q17: every quiz and every publication, whatever became of them (the review queue shows only the waiting).
        { key: 'qb-quizzes', label: 'menu.quizzes', path: PATH.quizzes, icon: FileQuestion,
          permission: PERMISSION.quizRead },
        { key: 'qb-publications', label: 'menu.publications', path: PATH.publications, icon: Rocket,
          permission: PERMISSION.publicationRead },
        { key: 'qb-users', label: 'menu.users', path: PATH.users, icon: Users, permission: PERMISSION.usersRead },
        // Q22: the cash rewards sponsors pay takers themselves, with the disputes to decide.
        { key: 'qb-cash-rewards', label: 'menu.cashRewards', path: PATH.cashRewards, icon: HandCoins,
          permission: PERMISSION.publicationRead },
        // Q22b: the prizes takers collect at a sponsor's place.
        { key: 'qb-redemptions', label: 'menu.redemptions', path: PATH.redemptions, icon: Gift,
          permission: PERMISSION.publicationRead },
        // Q33: codes, vouchers and links sponsors give winners, with the disputes to decide.
        { key: 'qb-digital-prizes', label: 'menu.digitalPrizes', path: PATH.digitalPrizes, icon: Ticket,
          permission: PERMISSION.publicationRead },
        { key: 'qb-attempts', label: 'menu.attempts', path: PATH.attempts, icon: ListChecks,
          permission: PERMISSION.usersRead },
        { key: 'qb-legal', label: 'menu.legal', path: PATH.legal, icon: ScrollText,
          permission: PERMISSION.contentWrite },
        { key: 'qb-broadcasts', label: 'menu.broadcasts', path: PATH.broadcasts, icon: Megaphone,
          permission: PERMISSION.contentWrite },
        { key: 'qb-ai', label: 'menu.ai', path: PATH.ai, icon: Sparkles, permission: PERMISSION.aiRead },
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
        // Q13a: both who record (finance) and who approve (content or another finance administrator).
        { key: 'qb-bank-topups', label: 'menu.bankTopUps', path: PATH.bankTopUps, icon: Banknote,
          permission: PERMISSION.bankApprove },
        // Q18a: the receiving account and limits, proposed by one administrator and published by another.
        { key: 'qb-bank-settings', label: 'menu.bankSettings', path: PATH.bankSettings, icon: Settings,
          permission: PERMISSION.financeRead },
        // Q18b: finance records manual credits; the bank top-ups' approvers approve them.
        { key: 'qb-manual-credits', label: 'menu.manualCredits', path: PATH.manualCredits, icon: HandCoins,
          permission: PERMISSION.bankApprove },
        // Q13b: finance reviews (the transfers' review), pays and records them.
        { key: 'qb-bank-withdrawals', label: 'menu.bankWithdrawals', path: PATH.bankWithdrawals, icon: Landmark,
          permission: PERMISSION.financeRead },
        { key: 'qb-reconciliation', label: 'menu.reconciliation', path: PATH.reconciliation, icon: Scale,
          permission: PERMISSION.reconcile },
        { key: 'qb-reports', label: 'menu.reports', path: PATH.reports, icon: FileSpreadsheet,
          permission: PERMISSION.financeRead },
        // Q27: the figures investors ask for, by month.
        { key: 'qb-investor-metrics', label: 'menu.investorMetrics', path: PATH.investorMetrics, icon: TrendingUp,
          permission: PERMISSION.financeRead },
        { key: 'qb-journal', label: 'menu.journal', path: PATH.journal, icon: BookOpenCheck,
          permission: PERMISSION.financeRead },
      ],
    },
  ],
  messages,
})
