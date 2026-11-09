import type { ReactNode } from 'react'
import { AccountPage, ACCOUNT_PATH } from '../pages/AccountPage'
import { DashboardPage } from '../pages/DashboardPage'
import { analyticsIdOf } from '../analytics/api'
import { PublicationAnalyticsPage } from '../analytics/PublicationAnalyticsPage'
import { AssetsPage, tabOf } from '../assets/AssetsPage'
import {
  BENEFIT_CONVERSATIONS_PATH,
  BENEFITS_PATH,
  idOf,
  NEW_PURCHASE,
  OFFERS_PATH,
  PURCHASES_PATH,
  sideOf,
} from '../benefits/api'
import {
  BenefitConversationPage,
  BenefitConversationsPage,
  CatalogPage,
  OffersPage,
  PurchasePage,
  PurchasesPage,
} from '../benefits/BenefitsPages'
import { QuizEditorPage } from '../content/QuizEditorPage'
import { QuizListPage } from '../content/QuizListPage'
import { quizIdOf, QUIZZES_PATH } from '../content/paths'
import { conversationOf } from '../conversations/api'
import { ConversationPage } from '../conversations/ConversationsPages'
import { MessagesPage } from '../notices/MessagesPage'
import { SponsorPagePage } from '../page/SponsorPageEditor'
import { PreviewPage } from '../preview/PreviewPage'
import { previewOf } from '../preview/paths'
import { BasicsPage } from '../profile/BasicsPage'
import { OverviewPage } from '../profile/OverviewPage'
import { profileSectionOf } from '../profile/paths'
import { ProfileLayout, PublicPageParts } from '../profile/ProfileLayout'
import { CashRewardsPage } from '../publishing/CashRewardsPage'
import { DigitalPrizesPage } from '../publishing/DigitalPrizesPage'
import { MailServerPage } from '../publishing/MailServerPage'
import { PublicationListPage } from '../publishing/PublicationListPage'
import { PublicationPage } from '../publishing/PublicationPage'
import { publicationIdOf, PUBLICATIONS_PATH } from '../publishing/paths'
import { RedeemPage } from '../publishing/RedeemPage'
import { StaffPage } from '../publishing/StaffPage'
import { StatsPage } from '../stats/StatsPage'
import { TransactionsPage } from '../transactions/TransactionsPage'
import { TreePage } from '../tree/TreePage'
import { Redirect } from '../qb/router'
import { BankTopUpPage } from '../wallet/BankTopUpPage'
import { bankTopUpIdOf, topUpIdOf } from '../wallet/api'
import { TopUpPage } from '../wallet/TopUpPage'
import { AiCreditPage, BillsPage, FeeAccountPage, WalletPage } from '../wallet/WalletPage'
import { areaShown, placeOf, rootOf, shownTabs, AREAS, type ShellState } from './areas'
import {
  AI_CREDIT_PATH,
  BILLS_PATH,
  CASH_REWARDS_PATH,
  CONVERSATIONS_PATH,
  DIGITAL_PRIZES_PATH,
  FEE_ACCOUNT_PATH,
  HOME_PATH,
  MAP_PATH,
  NEW_PARAM,
  NOTICES_PATH,
  REDEEM_PATH,
  STATS_PATH,
  TRANSACTIONS_PATH,
  WALLET_PATH,
} from './paths'

/** The areas' own addresses that are no tab (`/prizes`, `/money`): they open the area's first tab that shows. */
const ROOTS: Readonly<Record<string, string>> = { '/prizes': 'prizes', '/money': 'money' }

/**
 * The page of a signed-in Sponsor's path (F-F3: the one route table; a new page adds one line here and, when it is a
 * tab, one in `areas.ts`). Old addresses are led on before this (`legacyTarget`). An area's tab that does not show
 * (the Kudos wallet without Kudos) and the pages under it lead to the area's first tab that does; an area that does not
 * show (the benefits without a benefit role) keeps its addresses: the server decides what may be done there.
 * Null: no such page.
 */
export function pageOf(path: string, search: string, state: ShellState): ReactNode | null {
  const params = new URLSearchParams(search)
  const adding = params.get(NEW_PARAM) === '1'
  const root = ROOTS[path.replace(/\/+$/, '')]
  if (root) {
    const area = AREAS.find((a) => a.key === root)!
    return <Redirect to={rootOf(area, state)} />
  }
  const place = placeOf(path)
  if (place && areaShown(place.area, state) && !shownTabs(place.area, state).includes(place.tab)) {
    return <Redirect to={rootOf(place.area, state)} />
  }

  const preview = previewOf(path)
  if (preview) return <PreviewPage key={path} target={preview} />
  if (path === HOME_PATH) return <DashboardPage />
  if (path === QUIZZES_PATH) return <QuizListPage key={search} adding={adding} />
  const quizId = quizIdOf(path)
  if (quizId) return <QuizEditorPage key={quizId} quizId={quizId} />
  if (path === PUBLICATIONS_PATH) return <PublicationListPage />
  if (path === MAP_PATH) return <TreePage key={search} />
  const analyticsId = analyticsIdOf(path)
  if (analyticsId) return <PublicationAnalyticsPage key={path} publicationId={analyticsId} />
  const publicationId = publicationIdOf(path)
  if (publicationId) return <PublicationPage key={path} publicationId={publicationId} />

  const assetTab = tabOf(path)
  // One page for all the tabs: what is chosen on it ("only the unused") stays when the tab changes.
  if (assetTab) return <AssetsPage key={adding ? 'new' : 'library'} tab={assetTab} adding={adding} />

  if (path === REDEEM_PATH) return <RedeemPage />
  if (path === CASH_REWARDS_PATH) return <CashRewardsPage />
  if (path === DIGITAL_PRIZES_PATH) return <DigitalPrizesPage />

  if (path === CONVERSATIONS_PATH) return <MessagesPage key="conversations" tab="conversations" />
  if (path === NOTICES_PATH) return <MessagesPage key={`notices${search}`} tab="notices" adding={adding} />
  const conversationId = conversationOf(path)
  if (conversationId) return <ConversationPage key={path} conversationId={conversationId} />

  // Q89a: sponsored benefits between sponsors (the catalog, one's own, inquiries and purchases, by side).
  if (path === BENEFITS_PATH) return <CatalogPage />
  if (path === OFFERS_PATH) return <OffersPage />
  if (path === BENEFIT_CONVERSATIONS_PATH) return <BenefitConversationsPage key={search} side={sideOf(search)} />
  const benefitConversation = idOf(path, BENEFIT_CONVERSATIONS_PATH)
  if (benefitConversation) {
    return <BenefitConversationPage key={path + search} conversationId={benefitConversation} side={sideOf(search)} />
  }
  if (path === PURCHASES_PATH) return <PurchasesPage key={search} side={sideOf(search)} />
  const purchaseId = idOf(path, PURCHASES_PATH)
  if (purchaseId) {
    return purchaseId === NEW_PURCHASE
      ? <PurchasePage key={path + search} purchaseId={null} side="SPONSOR" offerId={params.get('offer')} />
      : <PurchasePage key={path + search} purchaseId={purchaseId} side={sideOf(search)} />
  }

  if (path === TRANSACTIONS_PATH) return <TransactionsPage key={search} />
  if (path === WALLET_PATH) return <WalletPage />
  const topUpId = topUpIdOf(path)
  if (topUpId) return <TopUpPage key={topUpId} topUpId={topUpId} />
  const bankTopUpId = bankTopUpIdOf(path)
  if (bankTopUpId) return <BankTopUpPage key={bankTopUpId} topUpId={bankTopUpId} />
  if (path === FEE_ACCOUNT_PATH) return <FeeAccountPage />
  if (path === AI_CREDIT_PATH) return <AiCreditPage />
  if (path === BILLS_PATH) return <BillsPage />

  // Q79: the sponsor hub and its sections; the public page's statistics under its page (F-F3).
  if (path === STATS_PATH) {
    return <ProfileLayout section="page"><PublicPageParts current="stats" /><StatsPage /></ProfileLayout>
  }
  const section = profileSectionOf(path)
  if (section) {
    return (
      <ProfileLayout section={section}>
        {section === 'overview' ? <OverviewPage />
          : section === 'basics' ? <BasicsPage />
            : section === 'page' ? <><PublicPageParts current="edit" /><SponsorPagePage /></>
              : section === 'staff' ? <StaffPage />
                : <MailServerPage />}
      </ProfileLayout>
    )
  }
  if (path === ACCOUNT_PATH) return <AccountPage />
  return null
}
