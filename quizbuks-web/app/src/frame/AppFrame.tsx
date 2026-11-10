import { BottomBar, cn, type BottomBarItem } from '@jabiz/ui'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useConversationsUnread, useUnread } from '../inbox/useUnread'
import { APP } from '../messages'
import { Link, useLocation } from '../qb/router'
import { badgeText } from '../inbox/api'
import { AREA_TABS, AREAS, routeOf, type AreaKey } from './areas'
import { FrameLink } from './FrameLink'
import { PRIZES_QUERY, useOpenPrizes } from './prizes'
import './messages'

type Badges = Partial<Record<AreaKey, number>>

/**
 * The numbers on the areas: prizes waiting under "Answered", what is unread under "Messages" (inbox and
 * conversations). Asked once, by the frame, for the bottom menu and the left column together: each asking hook
 * listens for the return to the front, and two would ask twice.
 */
function useAreaBadges(enabled: boolean): Badges {
  const prizes = useOpenPrizes(enabled)
  const inbox = useUnread(enabled)
  const conversations = useConversationsUnread(enabled)
  return { history: prizes, messages: inbox + conversations }
}

/** The area the page at the address belongs to (null outside the areas). */
function useCurrentArea(): AreaKey | null {
  const { path, search } = useLocation()
  return routeOf(path, search)?.area ?? null
}

/**
 * The bottom menu of a phone (platform `BottomBar`): the five areas, scanning raised in the middle. A badge is also in
 * the entry's name ("Messages, 3 unread"), so that the number is read once.
 */
function BottomMenu({ badges }: { badges: Badges }) {
  const { t } = useTranslation(APP)
  const current = useCurrentArea()
  const items: BottomBarItem[] = AREAS.map((area) => ({
    key: area.key,
    label: t(`frame.areas.${area.key}`),
    icon: area.icon,
    path: area.path,
    raised: area.raised,
    badge: badges[area.key],
  }))
  const nameOf = (key: string) => {
    const count = badges[key as AreaKey] ?? 0
    if (count <= 0) return undefined
    return key === 'messages' ? t('frame.messagesUnread', { count: badgeText(count) })
      : key === 'history' ? t('frame.historyToCollect', { count: badgeText(count) }) : undefined
  }
  return (
    <div className="md:hidden" data-testid="bottom-menu">
      <BottomBar items={items} label={t('nav.label')} isActive={(path) => AREAS.find((a) => a.path === path)?.key === current}
        renderLink={(item, props) => <FrameLink to={item.path} aria-label={nameOf(item.key)} {...props} />} />
    </div>
  )
}

/**
 * The left column of a wide screen (F-F4 decision 1: the app's own few lines, not the platform's `AppShell`, whose
 * header would add a menu button to a phone and which swaps the whole tree when the width crosses its breakpoint).
 * The same five areas as the bottom menu.
 */
function SideRail({ badges }: { badges: Badges }) {
  const { t } = useTranslation(APP)
  const current = useCurrentArea()
  return (
    <nav aria-label={t('frame.sideNav')} data-testid="side-rail"
      className={cn('bg-sidebar text-sidebar-foreground fixed inset-y-0 left-0 z-20 hidden w-[var(--sidebar-width,16rem)]',
        'flex-col gap-1 border-r p-3 md:flex')}>
      <ul className="flex flex-col gap-1">
        {AREAS.map((area) => {
          const Icon = area.icon
          const active = area.key === current
          const count = badges[area.key] ?? 0
          const name = area.key === 'messages' && count > 0 ? t('frame.messagesUnread', { count: badgeText(count) })
            : area.key === 'history' && count > 0 ? t('frame.historyToCollect', { count: badgeText(count) }) : undefined
          return (
            <li key={area.key}>
              <Link to={area.path} current={active} aria-label={name} data-testid={`side-rail-${area.key}`}
                className={cn('hover:bg-sidebar-accent flex min-h-11 items-center gap-3 rounded-md px-3 py-2 text-sm',
                  'focus-visible:ring-ring outline-none focus-visible:ring-2',
                  active ? 'bg-sidebar-accent text-sidebar-accent-foreground font-semibold' : 'text-muted-foreground')}>
                <Icon aria-hidden className="size-5 shrink-0" />
                <span className="flex-1 truncate">{t(`frame.areas.${area.key}`)}</span>
                {count > 0 && (
                  <span aria-hidden className="bg-destructive text-destructive-foreground min-w-5 rounded-full px-1.5 text-center text-xs leading-5 font-semibold">
                    {badgeText(count)}
                  </span>
                )}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

/**
 * The frame of the signed-in pages (F-F4): the page in the middle (centred in what the left column leaves), the left
 * column on a wide screen and the bottom menu on a narrow one, both drawn and switched by the width in CSS, so that
 * the page below is never rebuilt when a phone is turned. On a change of page the focus moves to the new page's title.
 */
export function AppFrame({ nav, children }: { nav: boolean; children: ReactNode }) {
  const { path, search } = useLocation()
  const queryClient = useQueryClient()
  const content = useRef<HTMLDivElement>(null)
  const badges = useAreaBadges(nav)
  const shown = useRef<{ path: string; area: AreaKey | null }>({ path, area: routeOf(path, search)?.area ?? null })
  useEffect(() => {
    const area = routeOf(path, search)?.area ?? null
    const before = shown.current
    if (before.path === path) return
    shown.current = { path, area }
    // The prizes may have changed on the page just left (collected, confirmed): asked again on entering an area.
    if (area !== before.area) void queryClient.invalidateQueries({ queryKey: PRIZES_QUERY })
    // Between the tabs of one area the keyboard stays on the tab; on any other page it goes to the title.
    if (area && area === before.area && AREA_TABS[area]) return
    content.current?.querySelector<HTMLElement>('[data-slot="page-title"]')?.focus()
  }, [path, search, queryClient])
  return (
    <>
      <div ref={content} className={cn('flex min-h-full flex-col', nav && 'md:pl-[var(--sidebar-width,16rem)]')}>
        {children}
      </div>
      {nav && <SideRail badges={badges} />}
      {nav && <BottomMenu badges={badges} />}
    </>
  )
}
