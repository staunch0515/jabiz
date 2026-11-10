import { PageTabs } from '@jabiz/ui'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { useConversationsUnreadShown } from '../inbox/useUnread'
import { TitleBadge } from '../pages/TitleBadge'
import { AREA_TABS, areaOf, type AreaKey } from './areas'
import { FrameLink } from './FrameLink'
import { useOpenPrizes } from './prizes'
import './messages'

/** The tabs under the title of an area (F-F4): pages of the area, each with its address; the arrow keys move between them. */
export function AreaTabs({ area, current }: { area: AreaKey; current: string }) {
  const { t } = useTranslation(APP)
  const tabs = AREA_TABS[area]
  // The badges come from the same queries as the bottom menu's and "Me": one request each.
  const prizes = useOpenPrizes(area === 'history')
  const conversations = useConversationsUnreadShown()
  if (!tabs) return null
  return (
    <PageTabs
      label={t(`frame.tabsLabel.${area}`)}
      current={current}
      tabs={tabs.map((tab) => ({
        key: tab.key,
        path: tab.path,
        label: t(`frame.tabs.${tab.key}`),
        badge: tab.key === 'prizes' ? prizes : tab.key === 'conversations' ? conversations : undefined,
      }))}
      renderLink={(tab, props) => <FrameLink to={tab.path} {...props} />}
    />
  )
}

/**
 * The head of a first-level page (F-F4 section 5.3): the round badge with the area's icon (the same object as the
 * bottom menu's, `AREAS`), the title, an optional line under it, the page's own buttons, and the area's tabs under
 * them. The title is where focus goes on a change of page (`data-slot="page-title"`).
 */
export function AreaHeader({ area, title, id = 'area-title', note, badge, actions, tab }: {
  area: AreaKey
  title: string
  /** The heading's id, for the page's `aria-labelledby`. */
  id?: string
  note?: ReactNode
  /** In place of the area's icon (the account's initial, a Sponsor's avatar). */
  badge?: ReactNode
  actions?: ReactNode
  /** The area's tab this page is, when the area has tabs. */
  tab?: string
}) {
  return (
    <header className="flex flex-col gap-3" data-testid="area-header">
      <div className="flex items-center gap-3">
        {badge ?? <TitleBadge icon={areaOf(area).icon} />}
        <div className="mr-auto min-w-0">
          <h1 id={id} data-slot="page-title" tabIndex={-1} className="text-xl font-semibold outline-none">{title}</h1>
          {note}
        </div>
        {actions}
      </div>
      {tab && <AreaTabs area={area} current={tab} />}
    </header>
  )
}
