import { BottomBar, cn, EnvironmentBadge, Fab, type BottomBarItem } from '@jabiz/ui'
import { useAuth } from '@jabiz/client'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useConversationsUnread, useUnread } from '../inbox/useUnread'
import { APP } from '../messages'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { SearchForm } from '../pages/SearchPage'
import { readMyProfile } from '../qb/api'
import { BrandMark } from '../qb/Illustrations'
import { QB } from '../qb/messages'
import { Link, useLocation } from '../qb/router'
import { badgeText } from '../inbox/api'
import { isFirstLevel } from './AreaHeader'
import { AREA_TABS, areaOf, BOTTOM_AREAS, PRIZES_PATH, routeOf, type Area, type AreaKey } from './areas'
import { DrawerProvider, Initial, useDrawer } from './Drawer'
import { FrameLink } from './FrameLink'
import { HeadsProvider, useChromeOffset } from './Header'
import { chromeOpacity } from './scrollChrome'
import { xspecVariables } from './xspec'
import { PRIZES_QUERY, useOpenPrizes } from './prizes'
import './messages'

type Badges = Partial<Record<AreaKey, number>>

/**
 * The numbers on the areas: prizes waiting under "Answered", what is unread under "Notifications" (inbox and
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

/** An entry's name with its badge ("Notifications, 3 unread"), so that the number is read once; none without one. */
function useBadgedName(badges: Badges) {
  const { t } = useTranslation(APP)
  return (key: AreaKey) => {
    const count = badges[key] ?? 0
    if (count <= 0) return undefined
    return key === 'messages' ? t('frame.messagesUnread', { count: badgeText(count) })
      : key === 'history' ? t('frame.historyToCollect', { count: badgeText(count) }) : undefined
  }
}

/**
 * The bottom menu of a phone (Q113 §9.1 "底部栏", §9.2): the four areas as icons alone (the platform's `BottomBar`
 * `variant="icons"`, 53 px), the current one bold. It fades with the head as the page scrolls (§9.1a) and stays usable.
 */
function BottomMenu({ badges }: { badges: Badges }) {
  const { t } = useTranslation(APP)
  const current = useCurrentArea()
  const offset = useChromeOffset()
  const nameOf = useBadgedName(badges)
  const items: BottomBarItem[] = BOTTOM_AREAS.map((area) => ({
    key: area.key,
    label: t(`frame.areas.${area.key}`),
    icon: area.icon,
    path: area.path,
    badge: badges[area.key],
  }))
  return (
    <div className="md:hidden" data-testid="bottom-menu">
      <BottomBar items={items} label={t('nav.label')} variant="icons" dim={chromeOpacity(offset)}
        isActive={(path) => BOTTOM_AREAS.find((a) => a.path === path)?.key === current}
        renderLink={(item, props) => <FrameLink to={item.path} aria-label={nameOf(item.key as AreaKey)} {...props} />} />
    </div>
  )
}

/**
 * Scanning (Q113 §9.2): the floating button of a phone's first-level pages (the platform's `Fab` `variant="timeline"`:
 * 56 px, 20 px from the side and from the bottom menu), fading with the bottom menu.
 */
function ScanButton() {
  const { t } = useTranslation(APP)
  const offset = useChromeOffset()
  const scan = areaOf('scan')
  return (
    <Fab variant="timeline" dim={chromeOpacity(offset)} className="md:hidden"
      action={{ label: t('frame.areas.scan'), icon: scan.icon,
        renderLink: (props) => <FrameLink to={scan.path} {...props} /> }} />
  )
}

/** A row of the left column: the icon, and from `xl` on its name, in a pill of 50 px inside a row of 58. */
function RailEntry({ area, active, name }: { area: Area; active: boolean; name?: string }) {
  const { t } = useTranslation(APP)
  const Icon = area.icon
  return (
    <li className="flex h-(--x-rail-item) items-center">
      <Link to={area.path} current={active} aria-label={name ?? t(`frame.areas.${area.key}`)}
        data-testid={`side-rail-${area.key}`}
        className={cn('hover:bg-accent focus-visible:ring-ring flex h-(--x-rail-pill) items-center gap-(--x-rail-gap) rounded-full',
          'px-(--x-rail-pill-inset) outline-none focus-visible:ring-2', active ? 'font-bold' : 'font-normal')}>
        <span className="relative">
          <Icon aria-hidden className="size-(--x-rail-icon)" strokeWidth={active ? 2.6 : 1.9} />
          {name && <span aria-hidden className="bg-primary absolute -top-0.5 -right-0.5 size-2 rounded-full" />}
        </span>
        <span aria-hidden className="hidden pr-(--x-side) text-(length:--x-rail-text) leading-6 xl:inline">
          {t(`frame.areas.${area.key}`)}
        </span>
      </Link>
    </li>
  )
}

/** The account at the foot of the left column: the initial, and from `xl` on the name and address; it opens the drawer. */
function RailAccount() {
  const { t } = useTranslation(APP)
  const drawer = useDrawer()
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const name = profile.data?.displayName || email || ''
  return (
    <button type="button" aria-label={t('frame.heads.avatar')} aria-haspopup="dialog" aria-expanded={drawer?.open}
      onClick={() => drawer?.setOpen(true)} data-testid="side-rail-account"
      className="hover:bg-accent focus-visible:ring-ring flex items-center gap-3 rounded-full p-(--x-rail-pill-inset) text-left outline-none focus-visible:ring-2">
      <Initial className="size-10 text-base" />
      <span className="hidden min-w-0 flex-col xl:flex">
        <span className="truncate text-[15px] leading-5 font-bold">{name}</span>
        {email && name !== email && <span className="text-muted-foreground truncate text-[15px] leading-5">{email}</span>}
      </span>
    </button>
  )
}

/**
 * The left column of a wide screen (Q113 §9.1 "宽屏", §5): the mark, the four areas, scanning as the main button and
 * the account at the foot; icons alone from `md`, 275 px with the names from `xl`. Sticky, so that the page scrolls
 * beside it (F-F4 decision 1: the app's own lines, not the platform's `AppShell`).
 */
function SideRail({ badges }: { badges: Badges }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const current = useCurrentArea()
  const nameOf = useBadgedName(badges)
  const scan = areaOf('scan')
  const ScanIcon = scan.icon
  return (
    <nav aria-label={t('frame.sideNav')} data-testid="side-rail"
      className={cn('sticky top-0 hidden h-dvh shrink-0 flex-col items-center px-2 py-1 md:order-first md:flex',
        'w-(--x-rail-compact) xl:w-(--x-rail) xl:items-stretch')}>
      <Link to="/" aria-label={tQb('brand')} data-testid="side-rail-mark"
        className="hover:bg-accent focus-visible:ring-ring flex size-(--x-rail-mark) items-center justify-center rounded-full outline-none focus-visible:ring-2">
        <BrandMark className="size-(--x-avatar)" />
      </Link>
      <ul className="flex flex-col">
        {BOTTOM_AREAS.map((area) => (
          <RailEntry key={area.key} area={area} active={area.key === current} name={nameOf(area.key)} />
        ))}
        <RailEntry area={areaOf('me')} active={current === 'me'} />
      </ul>
      <FrameLink to={scan.path} aria-label={t('frame.areas.scan')} data-testid="side-rail-scan"
        className={cn('bg-foreground text-background focus-visible:ring-ring mt-4 flex items-center justify-center rounded-full outline-none',
          'focus-visible:ring-2 focus-visible:ring-offset-2 hover:opacity-90',
          'size-(--x-rail-button-height) xl:h-(--x-rail-button-height) xl:w-(--x-rail-button-width)',
          'text-(length:--x-rail-button-text) font-bold')}>
        <ScanIcon aria-hidden className="size-6 xl:hidden" />
        <span aria-hidden className="hidden xl:inline">{t('frame.areas.scan')}</span>
      </FrameLink>
      <div className="mt-auto py-3">
        <RailAccount />
      </div>
    </nav>
  )
}

/**
 * The right column of a wide screen (Q113 §9.1 "宽屏"): the search, and the prizes waiting. Modules on the page's
 * white with a border of the dividers and round corners; "Sponsors to follow" needs a query of its own (not Q113a).
 */
function Aside() {
  const { t } = useTranslation(APP)
  const prizes = useOpenPrizes()
  const { path } = useLocation()
  return (
    <aside aria-label={t('frame.aside.label')} data-testid="side-aside"
      className="sticky top-0 ml-(--x-aside-gap) hidden w-(--x-aside) shrink-0 flex-col gap-4 self-start py-2 xl:flex">
      {path !== areaOf('search').path && <SearchForm testId="aside-search" />}
      <section aria-labelledby="aside-prizes" className="rounded-2xl border" data-testid="aside-prizes">
        <h2 id="aside-prizes" className="px-(--x-side) pt-3 text-xl leading-6 font-extrabold">{t('frame.aside.prizesTitle')}</h2>
        <p className="text-muted-foreground px-(--x-side) py-3 text-[15px] leading-5">
          {prizes ? t('frame.aside.prizes', { count: prizes }) : t('frame.aside.prizesNone')}
        </p>
        {!!prizes && (
          <FrameLink to={PRIZES_PATH} data-testid="aside-prizes-open"
            className="text-primary hover:bg-accent focus-visible:ring-ring block rounded-b-2xl px-(--x-side) py-3 text-[15px] outline-none focus-visible:ring-2">
            {t('frame.aside.prizesOpen')}
          </FrameLink>
        )}
      </section>
    </aside>
  )
}

/**
 * The frame of the signed-in pages (F-F4, Q113 §9): on a phone the page, the bottom menu and on the first-level pages
 * the floating scan button; on a wide screen three columns (the left column, the page in a column of 600 px between
 * two dividers, from `xl` the right column), centred. All drawn at once and switched by the width in CSS, so that the
 * page is never rebuilt when a phone is turned. The drawer is the avatar's. On a change of page the focus moves to the
 * new page's title.
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
    // The spec's lengths (`xspec.ts`) as the variables of the frame's classes; the heads, the scrolling, the drawer.
    // The room the bottom menu takes, for the page's end and the floating button: the bar of icons and its border.
    <HeadsProvider>
      <DrawerProvider>
        <div style={xspecVariables()} data-slot="x-frame"
          className={cn('flex min-h-full justify-center [--bottom-bar-offset:calc(var(--bottom-bar-icons-height)+1px)]')}>
          {/* A test system's mark (platform 17zz27): a corner badge, clear of the bottom menu and the floating
              button of a phone and of the left column of a wide screen. */}
          <EnvironmentBadge className={nav
            ? 'bottom-[calc(var(--bottom-bar-offset)+env(safe-area-inset-bottom,0px)+0.5rem)] md:right-3 md:bottom-3 md:left-auto'
            : undefined} />
          <div ref={content} data-testid="x-column"
            className={cn('flex min-h-dvh w-full min-w-0 flex-col', nav && 'md:w-(--x-column) md:shrink-0 md:border-x')}>
            {children}
          </div>
          {nav && <SideRail badges={badges} />}
          {nav && <Aside />}
          {nav && <BottomMenu badges={badges} />}
          {nav && isFirstLevel(path) && <ScanButton />}
        </div>
      </DrawerProvider>
    </HeadsProvider>
  )
}
