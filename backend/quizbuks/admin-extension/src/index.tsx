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
  Handshake,
  House,
  Landmark,
  ListChecks,
  Megaphone,
  PiggyBank,
  FileText,
  Rocket,
  Scale,
  ScrollText,
  Settings,
  TrendingUp,
  Sparkles,
  Ticket,
  Users,
  Wallet,
  Radar,
  Receipt,
  Globe,
  ShoppingBag, UserRoundPlus, Flag, SlidersHorizontal, Tags, HardDrive, Network, Tag, Clapperboard, Ban, Eye, NotebookTabs, LifeBuoy,
  MessageCircleQuestion, BookOpen } from 'lucide-react'
import TopicsPage from './TopicsPage'
import BlocksPage from './BlocksPage'
import ExposurePage from './ExposurePage'
import VideoUsesPage from './VideoUsesPage'

import BankTopUpPage from './BankTopUpPage'
import TreesPage from './TreesPage'
import TreeReviewPage from './TreeReviewPage'
import BankSettingsPage from './BankSettingsPage'
import BankTopUpsPage from './BankTopUpsPage'
import BankWithdrawalsPage from './BankWithdrawalsPage'
import ManualCreditPage from './ManualCreditPage'
import FeesPage from './FeesPage'
import FeeRequestPage from './FeeRequestPage'
import ManualCreditsPage from './ManualCreditsPage'
import BroadcastsPage from './BroadcastsPage'
import { AudiencePricesPage, AudienceRulesPage, OperatorAudiencePricesPage } from './AudiencePricingPages'
import ReferralsPage from './ReferralsPage'
import RegionPage from './RegionPage'
import SharedVideosPage from './SharedVideosPage'
import SharedVideoPage from './SharedVideoPage'
import AiPage from './AiPage'
import { HelpThreadPage, HelpThreadsPage } from './HelpDeskPages'
import { HelpGapsPage, HelpManualsPage } from './HelpContentPages'
import AttemptsPage from './AttemptsPage'
import { BRAND } from './brand'
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
import SponsorPagesPage from './SponsorPagesPage'
import SponsorPageReviewPage from './SponsorPageReviewPage'
import SponsorProductsPage from './SponsorProductsPage'
import UsagePage from './UsagePage'
import AbuseReportsPage from './AbuseReportsPage'
import ConversationPage from './ConversationPage'
import CashRewardsPage from './CashRewardsPage'
import DigitalPrizesPage from './DigitalPrizesPage'
import TopUpsPage from './TopUpsPage'
import TransferReviewPage from './TransferReviewPage'
import TransfersPage from './TransfersPage'
import UserSummaryPage from './UserSummaryPage'
import UsersPage from './UsersPage'
import OperatorHomePage from './OperatorHomePage'
import HomePage from './HomePage'
import OperatorPage from './OperatorPage'
import OperatorReviewsPage from './OperatorReviewsPage'
import OperatorsPage from './OperatorsPage'
import OperatorSponsorsPage from './OperatorSponsorsPage'
import OperatorBillingPage from './OperatorBillingPage'
import ProspectPage from './ProspectPage'
import ProspectSettingsPage from './ProspectSettingsPage'
import ProspectsPage from './ProspectsPage'
import SettlementsPage from './SettlementsPage'
import OperatorAccountPage from './OperatorAccountPage'
import OperatorWithdrawalsPage from './OperatorWithdrawalsPage'

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
  // Q55b: the brand profile's name, mark and icon in place of the platform's.
  brand: BRAND,
  // F-S1b: where a session lands: the operator's home in the Operator Management System, the operators' structure
  // and workload in the platform's.
  home: PATH.home,
  routes: [
    { path: PATH.home, element: <HomePage /> },
    { path: PATH.reviews, element: <ReviewsPage /> },
    { path: '/qb/reviews/publications/:publicationId', element: <PublicationReviewPage /> },
    { path: '/qb/reviews/sponsors/:userId', element: <SponsorReviewPage /> },
    // Q60a-3: sponsors' pages, for headquarters and the operators (their own sponsors').
    { path: PATH.sponsorPages, element: <SponsorPagesPage /> },
    { path: '/qb/sponsor-pages/:userId', element: <SponsorPageReviewPage /> },
    // Q60b-3: sponsors' products.
    { path: PATH.sponsorProducts, element: <SponsorProductsPage /> },
    // F-C2: the sponsors' usage by month (headquarters every sponsor, an operator its own).
    { path: PATH.usage, element: <UsagePage /> },
    // Q49a: takers' reports of quizzes, for headquarters and the operators (their own sponsors').
    { path: PATH.abuseReports, element: <AbuseReportsPage /> },
    { path: '/qb/conversations/:conversationId', element: <ConversationPage /> },
    // F-S11: foreign videos and mainland China (headquarters' exceptions, the refused starts).
    { path: PATH.region, element: <RegionPage /> },
    // F-S2b3a: the knowledge maps and the review of a map's version (headquarters every map, an operator its own).
    { path: PATH.trees, element: <TreesPage /> },
    { path: '/qb/trees/:treeId', element: <TreeReviewPage /> },
    // F-S12: the videos Sponsors share: an operator's own Sponsors' to review and take down, the platform's all.
    { path: PATH.sharedVideos, element: <SharedVideosPage /> },
    { path: '/qb/shared-videos/:sharedVideoId', element: <SharedVideoPage /> },
    // F-S9: the word list of topics (headquarters).
    { path: PATH.topics, element: <TopicsPage /> },
    // F-S8: the figures of blocking (headquarters, aggregates only).
    { path: PATH.blocks, element: <BlocksPage /> },
    // F-S13: the suspicious inflation of exposures (headquarters and the operators, scoped).
    { path: PATH.exposure, element: <ExposurePage /> },
    // F-S14: the ledger of shared videos' uses (headquarters and the operators, scoped; read only).
    { path: PATH.videoUses, element: <VideoUsesPage /> },
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
    // Q87: the platform's fees, their invoices, and the requests another administrator approves.
    { path: PATH.fees, element: <FeesPage /> },
    { path: '/qb/finance/fees/requests/:requestId', element: <FeeRequestPage /> },
    { path: PATH.reconciliation, element: <ReconciliationPage /> },
    { path: PATH.reports, element: <ReportsPage /> },
    { path: PATH.investorMetrics, element: <InvestorMetricsPage /> },
    { path: PATH.journal, element: <JournalPage /> },
    { path: PATH.journalMapping, element: <JournalMappingPage /> },
    { path: PATH.legal, element: <LegalPage /> },
    { path: PATH.broadcasts, element: <BroadcastsPage /> },
    { path: PATH.ai, element: <AiPage /> },
    // F-C10c: the AI help's conversations handed to a person (headquarters and the operators, scoped), its gaps by
    // month and its usage manuals (content administrators).
    { path: PATH.help, element: <HelpThreadsPage /> },
    { path: '/qb/help/:threadId', element: <HelpThreadPage /> },
    { path: PATH.helpGaps, element: <HelpGapsPage /> },
    { path: PATH.helpManuals, element: <HelpManualsPage /> },
    // Q48a: headquarters' operators and spot checks, and the operators' own pages in their back office.
    { path: PATH.operators, element: <OperatorsPage /> },
    { path: '/qb/operators/:operatorId', element: <OperatorPage /> },
    { path: PATH.operatorReviews, element: <OperatorReviewsPage /> },
    { path: PATH.operatorHome, element: <OperatorHomePage /> },
    { path: PATH.operatorSponsors, element: <OperatorSponsorsPage /> },
    // Q87b: how the operator's sponsors pay the platform's fees.
    { path: PATH.operatorBilling, element: <OperatorBillingPage /> },
    // Q48c: AI prospecting, for an operator's members and headquarters alike (each reads its own templates).
    { path: PATH.prospects, element: <ProspectsPage /> },
    { path: PATH.prospectSettings, element: <ProspectSettingsPage /> },
    { path: '/qb/prospects/:prospectId', element: <ProspectPage /> },
    // Q48b: the operators' settlements, for finance and for the operator.
    { path: PATH.settlements, element: <SettlementsPage /> },
    { path: PATH.operatorSettlements, element: <SettlementsPage /> },
    // Q48e-2: the operator's account and withdrawals, and finance's view of the withdrawals.
    { path: PATH.operatorAccount, element: <OperatorAccountPage /> },
    { path: PATH.operatorWithdrawals, element: <OperatorWithdrawalsPage /> },
    { path: PATH.referrals, element: <ReferralsPage /> },
    // Q47c: the audience rules and their prices (headquarters), and an operator's own prices.
    { path: PATH.audienceRules, element: <AudienceRulesPage /> },
    { path: PATH.audiencePrices, element: <AudiencePricesPage /> },
    { path: PATH.operatorAudiencePrices, element: <OperatorAudiencePricesPage /> },
  ],
  menu: [
    // F-S1b: the operator's own pages (only its members hold qb.operator.use): what an operator runs for its sponsors
    // besides the shared lists below.
    {
      key: 'quizbuks-operator',
      label: 'menu.operatorGroup',
      children: [
        { key: 'qb-operator-home', label: 'menu.operatorHome', path: PATH.operatorHome, icon: House,
          permission: PERMISSION.operatorUse },
        { key: 'qb-operator-sponsors', label: 'menu.operatorSponsors', path: PATH.operatorSponsors, icon: Users,
          permission: PERMISSION.operatorUse },
        // Q48c: the operator's own prospecting.
        { key: 'qb-operator-prospects', label: 'menu.prospects', path: PATH.prospects, icon: Radar,
          permission: PERMISSION.prospectUse },
        // Q69: the rounds of the operator's own sponsor, and grants and revocations by hand.
        // Q87b: how the operator's sponsors pay the platform's fees.
        { key: 'qb-operator-billing', label: 'menu.operatorBilling', path: PATH.operatorBilling, icon: Receipt,
          permission: PERMISSION.operatorUse },
        // Q48b: the operator's own settlements.
        { key: 'qb-operator-settlements', label: 'menu.operatorSettlements', path: PATH.operatorSettlements,
          icon: Receipt, permission: PERMISSION.operatorUse },
        // Q48e-2: the operator's account: balance, plan, bank account and withdrawals.
        { key: 'qb-operator-account', label: 'menu.operatorAccount', path: PATH.operatorAccount,
          icon: PiggyBank, permission: PERMISSION.operatorUse },
        // Q47c: the operator's own prices of the audience rules.
        { key: 'qb-operator-audience-prices', label: 'menu.audiencePrices', path: PATH.operatorAudiencePrices,
          icon: Tags, permission: PERMISSION.operatorUse },
        // F-S2b3a: the operator's and its Sponsors' knowledge maps, and their reviews.
        { key: 'qb-operator-trees', label: 'menu.operatorTrees', path: PATH.trees, icon: Network,
          permission: PERMISSION.operatorUse },
      ],
    },
    // F-S1b: the sponsors' day-to-day, shared by both: an operator's session reads and runs its own sponsors' (the
    // server's scope); the platform's reads every operator's, filtered by operator, and runs only its emergencies.
    {
      key: 'quizbuks',
      label: 'menu.group',
      children: [
        { key: 'qb-reviews', label: 'menu.reviews', path: PATH.reviews, icon: ClipboardCheck,
          permission: PERMISSION.publicationRead },
        // Q17: every quiz and every publication, whatever became of them (the review queue shows only the waiting).
        { key: 'qb-quizzes', label: 'menu.quizzes', path: PATH.quizzes, icon: FileQuestion,
          permission: PERMISSION.quizRead },
        { key: 'qb-publications', label: 'menu.publications', path: PATH.publications, icon: Rocket,
          permission: PERMISSION.publicationRead },
        // Q60a-3, Q60b-3: sponsors' pages and products.
        { key: 'qb-sponsor-pages', label: 'menu.sponsorPages', path: PATH.sponsorPages, icon: Globe,
          permission: PERMISSION.publicationRead },
        { key: 'qb-sponsor-products', label: 'menu.sponsorProducts', path: PATH.sponsorProducts, icon: ShoppingBag,
          permission: PERMISSION.publicationRead },
        // F-C2: one entry for both management systems (support, finance and the operators' members read it).
        { key: 'qb-usage', label: 'menu.usage', path: PATH.usage, icon: HardDrive,
          permission: PERMISSION.publicationRead },
        // Q49a: takers' reports of quizzes.
        { key: 'qb-abuse-reports', label: 'menu.abuseReports', path: PATH.abuseReports, icon: Flag,
          permission: PERMISSION.publicationRead },
        // F-S11: the exceptions to the rule on foreign videos and mainland China, one entry for both (the requester,
        // 2026-11-07): an operator's session reads and sets its own sponsors', the platform's every operator's,
        // filtered by operator; the platform-wide refusal counts show for headquarters only.
        { key: 'qb-region', label: 'menu.region', path: PATH.region, icon: Globe, permission: PERMISSION.regionRead },
        // F-S12: the videos Sponsors share, one entry for both: an operator's session reviews and takes down its own
        // Sponsors', the platform's reads every operator's (filtered by operator) and takes down in an emergency.
        { key: 'qb-shared-videos', label: 'menu.sharedVideos', path: PATH.sharedVideos, icon: Clapperboard,
          permission: PERMISSION.sharedVideoRead },
        // F-S13: the exposures of shared videos, one entry for both: the Takers the daily cap refused (an operator's
        // session reads its own Sponsors' publications only).
        { key: 'qb-exposure', label: 'menu.exposure', path: PATH.exposure, icon: Eye,
          permission: PERMISSION.exposureRead },
        // F-S14: the ledger of shared videos' uses, read only (an operator's session reads its own Sponsors').
        { key: 'qb-video-uses', label: 'menu.videoUses', path: PATH.videoUses, icon: NotebookTabs,
          permission: PERMISSION.videoUseRead },
        // F-C10c: the AI help's conversations handed to a person, one entry for both: an operator's session answers its
        // own Sponsors', the platform's the Takers' and the direct Sponsors' (and reads every one).
        { key: 'qb-help', label: 'menu.help', path: PATH.help, icon: LifeBuoy, permission: PERMISSION.adminHelp },
        // Q22: the cash rewards sponsors pay takers themselves (F-S1: nobody here decides their disputes).
        { key: 'qb-cash-rewards', label: 'menu.cashRewards', path: PATH.cashRewards, icon: HandCoins,
          permission: PERMISSION.publicationRead },
        // Q22b: the prizes takers collect at a sponsor's place.
        { key: 'qb-redemptions', label: 'menu.redemptions', path: PATH.redemptions, icon: Gift,
          permission: PERMISSION.publicationRead },
        // Q33: codes, vouchers and links sponsors give winners, with the disputes the operators decide.
        { key: 'qb-digital-prizes', label: 'menu.digitalPrizes', path: PATH.digitalPrizes, icon: Ticket,
          permission: PERMISSION.publicationRead },
      ],
    },
    // F-S1b: the platform's monitoring, global rules and emergencies, and its read-only views across operators.
    {
      key: 'quizbuks-platform',
      label: 'menu.platformGroup',
      children: [
        { key: 'qb-home', label: 'menu.home', path: PATH.home, icon: House, permission: PERMISSION.operatorsRead },
        { key: 'qb-users', label: 'menu.users', path: PATH.users, icon: Users, permission: PERMISSION.usersRead },
        // F-S9: the word list of topics, kept by headquarters, with its figures by topic.
        { key: 'qb-topics', label: 'menu.topics', path: PATH.topics, icon: Tag, permission: PERMISSION.topicRead },
        // F-S8: the figures of blocking (totals by kind, the Sponsors most blocked, rounded).
        { key: 'qb-blocks', label: 'menu.blocks', path: PATH.blocks, icon: Ban, permission: PERMISSION.blockRead },
        { key: 'qb-attempts', label: 'menu.attempts', path: PATH.attempts, icon: ListChecks,
          permission: PERMISSION.usersRead },
        // Q48a: the operators, and the spot checks of the reviews they decided.
        { key: 'qb-operators', label: 'menu.operators', path: PATH.operators, icon: Handshake,
          permission: PERMISSION.operatorsRead },
        { key: 'qb-operator-reviews', label: 'menu.operatorReviews', path: PATH.operatorReviews, icon: ClipboardCheck,
          permission: PERMISSION.spotCheck },
        // Q48c, F-S1b: every operator's prospecting, read only.
        { key: 'qb-prospects', label: 'menu.prospects', path: PATH.prospects, icon: Radar,
          permission: PERMISSION.adminProspect },
        // Q70: every referral (content and finance administrators).
        { key: 'qb-referrals', label: 'menu.referrals', path: PATH.referrals, icon: UserRoundPlus,
          permission: PERMISSION.referralsRead },
        { key: 'qb-legal', label: 'menu.legal', path: PATH.legal, icon: ScrollText,
          permission: PERMISSION.contentWrite },
        { key: 'qb-broadcasts', label: 'menu.broadcasts', path: PATH.broadcasts, icon: Megaphone,
          permission: PERMISSION.contentWrite },
        { key: 'qb-ai', label: 'menu.ai', path: PATH.ai, icon: Sparkles, permission: PERMISSION.aiRead },
        // F-C10c: what the AI help did not solve (to add to the FAQ and the manuals), and the manuals.
        { key: 'qb-help-gaps', label: 'menu.helpGaps', path: PATH.helpGaps, icon: MessageCircleQuestion,
          permission: PERMISSION.aiRead },
        { key: 'qb-help-manuals', label: 'menu.helpManuals', path: PATH.helpManuals, icon: BookOpen,
          permission: PERMISSION.contentWrite },
        // Q47c: the audience rules (content administrators) and every owner's prices (finance).
        { key: 'qb-audience-rules', label: 'menu.audienceRules', path: PATH.audienceRules, icon: SlidersHorizontal,
          permission: PERMISSION.audienceRulesWrite },
        { key: 'qb-audience-prices', label: 'menu.audiencePrices', path: PATH.audiencePrices, icon: Tags,
          permission: PERMISSION.audiencePricesWrite },
        // F-S2b3a: every knowledge map, its versions' reviews and its lit nodes.
        { key: 'qb-trees', label: 'menu.trees', path: PATH.trees, icon: Network, permission: PERMISSION.treesRead },
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
        // Q87: the platform's fees charged to sponsors and their invoices.
        { key: 'qb-fees', label: 'menu.fees', path: PATH.fees, icon: FileText,
          permission: PERMISSION.financeRead },
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
        // Q48b: what is owed to the operators, and its payments.
        { key: 'qb-settlements', label: 'menu.settlements', path: PATH.settlements, icon: Receipt,
          permission: PERMISSION.financeRead },
        // Q48e-2: the operators' withdrawals from their accounts.
        { key: 'qb-operator-withdrawals', label: 'menu.operatorWithdrawals', path: PATH.operatorWithdrawals,
          icon: HandCoins, permission: PERMISSION.financeRead },
      ],
    },
  ],
  messages,
})
