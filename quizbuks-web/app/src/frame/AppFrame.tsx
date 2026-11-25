import { BottomBar, cn, EnvironmentBadge, Fab, type BottomBarItem } from '@jabiz/ui'
import { useAuth } from '@jabiz/client'
import { Ellipsis } from 'lucide-react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useConversationsUnread, useUnread } from '../inbox/useUnread'
import { APP } from '../messages'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { SearchForm } from '../pages/SearchPage'
import { readMyProfile } from '../qb/api'
import { BrandMark } from '../qb/Illustrations'
import { formatKudos } from '../qb/kudos'
import { legalPath } from '../qb/legal'
import { rewardPath } from '../qb/play'
import { QB } from '../qb/messages'
import { Link, useLocation } from '../qb/router'
import { badgeText } from '../inbox/api'
import { isFirstLevel } from './AreaHeader'
import { AREA_TABS, areaOf, BOTTOM_AREAS, MY_PASSES_PATH, PRIZES_PATH, routeOf, SETTINGS_ROW, type Area, type AreaKey } from './areas'
import { DrawerProvider, Initial, useKudosAtHand } from './Drawer'
import { FabScanIcon, GiftIcon } from './icons'
import { FrameLink } from './FrameLink'
import { HeadsProvider, useChromeOffset } from './Header'
import { chromeOpacity } from './scrollChrome'
import { xspecVariables } from './xspec'
import { PRIZES_QUERY, readPrizes, useOpenPrizes, type Prize } from './prizes'
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
    activeIcon: area.solidIcon,
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
      action={{ label: t('frame.areas.scan'), icon: FabScanIcon,
        renderLink: (props) => <FrameLink to={scan.path} {...props} /> }} />
  )
}

/**
 * A row of the left column (the prototype's Desktop): the icon (solid when current, where the bottom menu's is) with
 * its count, and from `xl` on its name, in a pill of 50 px inside a row of 58; the current one bold.
 */
function RailEntry({ area, label, active, count, name, testId }: {
  area: Pick<Area, 'path' | 'icon' | 'solidIcon'>
  label: string
  active: boolean
  count?: number
  /** The name with its count, read once. */
  name?: string
  testId: string
}) {
  const Icon = active && area.solidIcon ? area.solidIcon : area.icon
  return (
    <li className="flex h-(--x-rail-item) items-center">
      <Link to={area.path} current={active} aria-label={name ?? label} data-testid={testId}
        className={cn('hover:bg-accent focus-visible:ring-ring flex h-(--x-rail-pill) items-center gap-(--x-rail-gap) rounded-full',
          'px-(--x-rail-pill-inset) outline-none focus-visible:ring-2', active ? 'font-bold' : 'font-normal')}>
        <span className="relative">
          <Icon aria-hidden className="size-(--x-rail-icon)" strokeWidth={active ? 2.4 : 1.9} />
          {!!count && count > 0 && (
            <span aria-hidden className={cn('bg-primary text-primary-foreground border-background absolute -top-1.5 -right-2 min-w-[18px]',
              'rounded-full border-2 px-1 text-center text-[11px] leading-[14px] font-bold')}>
              {badgeText(count)}
            </span>
          )}
        </span>
        <span aria-hidden className="hidden pr-(--x-side) text-(length:--x-rail-text) leading-6 xl:inline">{label}</span>
      </Link>
    </li>
  )
}

/**
 * The account at the foot of the left column (the prototype's Desktop): the initial, and from `xl` on the name, the
 * Kudos at hand and the "more" mark; it leads to the taker's page.
 */
function RailAccount() {
  const { t } = useTranslation(APP)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const kudos = useKudosAtHand()
  const name = profile.data?.displayName || email || ''
  return (
    <FrameLink to={areaOf('me').path} aria-label={t('frame.drawer.rows.me')} data-testid="side-rail-account"
      className="hover:bg-accent focus-visible:ring-ring flex items-center gap-3 rounded-full p-(--x-rail-pill-inset) text-left outline-none focus-visible:ring-2">
      <Initial className="size-10 text-[15px]" />
      <span className="hidden min-w-0 flex-1 flex-col xl:flex">
        <span className="truncate text-[15px] leading-5 font-bold">{name}</span>
        {kudos !== undefined && (
          <span className="text-muted-foreground truncate text-[15px] leading-5">{kudos} {t('me.numbers.kudos')}</span>
        )}
      </span>
      <Ellipsis aria-hidden className="hidden size-[19px] shrink-0 xl:block" />
    </FrameLink>
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
          <RailEntry key={area.key} area={area} label={t(`frame.areas.${area.key}`)} active={area.key === current}
            count={badges[area.key]} name={nameOf(area.key)} testId={`side-rail-${area.key}`} />
        ))}
        <RailEntry area={areaOf('me')} label={t('frame.drawer.rows.me')} active={current === 'me'} testId="side-rail-me" />
        <RailEntry area={SETTINGS_ROW} label={t(SETTINGS_ROW.title)} active={false} testId="side-rail-settings" />
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

/** A prize waiting in the right column: the gift, its worth, the Sponsor and the date, the pass or the way to it. */
function AsidePrize({ prize }: { prize: Prize }) {
  const { t, i18n } = useTranslation(APP)
  const pass = prize.kind === 'REDEEM' && prize.status === 'DUE'
  const by = prize.expiresAt ?? prize.dueAt ?? prize.confirmBy
  return (
    <li className="flex items-center gap-3 px-(--x-side) py-3" data-testid="aside-prize">
      <span aria-hidden className="bg-qb-reward/25 text-qb-reward-foreground flex size-10 shrink-0 items-center justify-center rounded-full">
        <GiftIcon className="size-5" strokeWidth={2} />
      </span>
      <span className="flex min-w-0 flex-1 flex-col">
        <b className="truncate">{t('frame.aside.worth', { amount: formatKudos(prize.amount) })}</b>
        <span className="text-muted-foreground truncate">
          {[prize.sponsorCompany ?? prize.publicationName, by && t('frame.aside.by', { date: shortDate(by, i18n.language) })]
            .filter(Boolean).join(' · ')}
        </span>
      </span>
      <FrameLink to={pass ? MY_PASSES_PATH : rewardPath({ attemptId: prize.attemptId, sourceKind: prize.sourceKind ?? undefined })}
        data-testid="aside-prize-open"
        className="bg-foreground text-background focus-visible:ring-ring h-8 shrink-0 rounded-full px-4 text-sm leading-8 font-bold outline-none focus-visible:ring-2 focus-visible:ring-offset-2">
        {pass ? t('frame.aside.pass') : t('frame.aside.open')}
      </FrameLink>
    </li>
  )
}

/** A day as the prototype writes it beside a prize: the month and the day ("1月1日", "Jan 1"). */
function shortDate(at: string, language: string): string {
  return new Date(at).toLocaleDateString(language, { month: 'short', day: 'numeric' })
}

/** How many waiting prizes the right column lists before "See all". */
const ASIDE_PRIZES = 3

/**
 * The right column of a wide screen (Q113 §9.1 "宽屏"; the prototype's Desktop): the search (a filled pill), the
 * prizes waiting with their pass, and the help and the terms. Modules with a border of the dividers and round
 * corners; "Sponsors to follow" needs a query of its own (not Q113a).
 */
function Aside() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { path } = useLocation()
  const prizes = useQuery({ queryKey: [...PRIZES_QUERY, 'aside'], queryFn: () => readPrizes(true, 0, ASIDE_PRIZES) })
  const rows = prizes.data?.items ?? []
  return (
    <aside aria-label={t('frame.aside.label')} data-testid="side-aside"
      className="sticky top-0 ml-(--x-aside-gap) hidden w-(--x-aside) shrink-0 flex-col gap-4 self-start pt-1.5 pb-6 xl:flex">
      {path !== areaOf('search').path && <SearchForm testId="aside-search" />}
      <section aria-labelledby="aside-prizes" className="overflow-hidden rounded-2xl border" data-testid="aside-prizes">
        <h2 id="aside-prizes" className="px-(--x-side) py-3 text-xl leading-6 font-extrabold">{t('frame.aside.prizesTitle')}</h2>
        {rows.length > 0 ? (
          <ul>{rows.map((prize) => <AsidePrize key={`${prize.kind}:${prize.prizeId}`} prize={prize} />)}</ul>
        ) : (
          <p className="text-muted-foreground px-(--x-side) pb-3">{t('frame.aside.prizesNone')}</p>
        )}
        {(prizes.data?.total ?? 0) > rows.length && (
          <FrameLink to={PRIZES_PATH} data-testid="aside-prizes-more"
            className="text-primary hover:bg-accent focus-visible:ring-ring block px-(--x-side) py-3 outline-none focus-visible:ring-2 focus-visible:ring-inset">
            {t('frame.aside.more')}
          </FrameLink>
        )}
      </section>
      <nav aria-label={t('frame.aside.links')} className="text-muted-foreground mx-(--x-side) text-(length:--x-note) leading-4">
        {ASIDE_LINKS.map((link, i) => (
          <span key={link.path}>
            {i > 0 && ' · '}
            <FrameLink to={link.path} className="outline-none hover:underline focus-visible:underline">{tQb(link.title)}</FrameLink>
          </span>
        ))}
      </nav>
    </aside>
  )
}

/** The links under the right column (the prototype's): the help centre and the texts a taker accepts. */
const ASIDE_LINKS = [
  { path: '/help', title: 'help.title' },
  { path: legalPath('TERMS_APP'), title: 'legal.kinds.TERMS_APP' },
  { path: legalPath('PRIVACY'), title: 'legal.kinds.PRIVACY' },
  { path: legalPath('CONTEST_RULES'), title: 'legal.kinds.CONTEST_RULES' },
]

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
