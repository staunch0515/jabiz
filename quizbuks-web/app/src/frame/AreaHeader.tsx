import { PageTabs } from '@jabiz/ui'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { useConversationsUnreadShown } from '../inbox/useUnread'
import { canGoBack, navigate, useLocation } from '../qb/router'
import { AREA_TABS, AREAS, type AreaKey } from './areas'
import { FrameLink } from './FrameLink'
import { SubHead, TopHead, type Back } from './Header'
import { useOpenPrizes } from './prizes'
import { upOf } from './up'
import './messages'

/**
 * The tabs under the title of an area (F-F4; Q113 §9.1 "标签": 53 px, the bar under the current one as wide as its
 * words): pages of the area, each with its address; the arrow keys move between them.
 */
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
      indicator="label"
      fill
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

/** Whether the page at the address is first-level (Q113 P4): an area's own page, or one of its tabs. */
export function isFirstLevel(path: string): boolean {
  return AREAS.some((area) => area.path === path)
    || Object.values(AREA_TABS).some((tabs) => tabs?.some((tab) => tab.path === path))
}

/** Back from a page reached from elsewhere: the page before within the app, the home when it was opened first. */
export function historyBack() {
  if (canGoBack()) window.history.back()
  else navigate('/')
}

/**
 * The head of a page of the frame (F-F4 section 5.3, Q113 §9.1): a first-level page's (the avatar, the title, the
 * page's buttons, the area's tabs under them) or a page under another's (the way back to the page above it, or back,
 * then the title and a line under it). The title is where focus goes on a change of page (`data-slot="page-title"`).
 */
export function AreaHeader({ area, title, id = 'area-title', note, actions, tab }: {
  area: AreaKey
  title: string
  /** The heading's id, for the page's `aria-labelledby`. */
  id?: string
  note?: ReactNode
  actions?: ReactNode
  /** The area's tab this page is, when the area has tabs. */
  tab?: string
}) {
  const { t } = useTranslation(APP)
  const { path } = useLocation()
  const tabs = tab ? <AreaTabs area={area} current={tab} /> : undefined
  if (isFirstLevel(path)) return <TopHead title={title} id={id} note={note} actions={actions} tabs={tabs} bleed />
  const up = upOf(path)
  const back: Back = up
    ? { to: up.path, label: t('nav.backTo', { name: t(up.name) }) }
    : { onClick: historyBack, label: t('nav.back') }
  return <SubHead title={title} id={id} note={note} back={back} actions={actions} tabs={tabs} bleed />
}
