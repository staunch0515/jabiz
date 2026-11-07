import { ApiError, changeLanguage, languages, LANGUAGE_NAMES, type Language } from '@jabiz/client'
import { Check } from 'lucide-react'
import {
  createContext, useContext, useEffect, useState, useSyncExternalStore, type ComponentProps, type MouseEvent,
  type ReactNode,
} from 'react'
import { useTranslation } from 'react-i18next'
import { useHasAppearance } from '../components/appearance'
import { EnvironmentBanner } from '../components/environment-banner'
import { ThemeToggle } from '../components/theme-toggle'
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select'
import { useIsMobile } from '../hooks/use-mobile'
import { cn } from '../lib/utils'
import { AUTH_NAMESPACE } from './messages'

/**
 * What the left column of the entry pages says on a wide screen (suggestion ②): one sentence, up to three points and
 * an illustration, in the page's language (the application passes texts of its own messages). On a phone only the
 * sentence remains, as one line above the form.
 */
export interface EntryIntro {
  /** One sentence: what the application is for. */
  sentence: ReactNode
  /** A few points (three, by convention); wide screens only. */
  points?: readonly ReactNode[]
  /** A picture (an <img> with its alternative text, or a decorative one with an empty one); wide screens only. */
  illustration?: ReactNode
}

/**
 * A notice over the whole page (the environment banner comes first by itself): offline, the service not answering,
 * a new version, "open this in your browser" inside another app's browser, and the like — the application's nodes.
 * On a phone only the first active one is shown in full; the other active ones collapse to their one-line `summary`.
 */
export interface EntryNotice {
  key: string
  content: ReactNode
  /** One line standing for the notice when it is collapsed on a phone; never collapsed without one. */
  summary?: ReactNode
  /**
   * Whether it shows anything now (default true): only an active notice takes the one place expanded on a phone. An
   * inactive one is still rendered (a notice may watch for something and open a dialog by itself).
   */
  active?: boolean
}

/**
 * The way to the other side of the application under the sign-in and sign-up forms (suggestion ①, gap 7): "Publishing
 * quizzes? Go there →". The texts are the application's own messages; nothing is assumed about the other side's name.
 */
export interface EntryOtherEntry {
  /** What the other side is for, as the person would say it. */
  text: ReactNode
  /** The other side's address; a full page load (it is usually another frontend). */
  href: string
  /** The link's text; "Go there" when absent. */
  link?: ReactNode
}

/** What every entry page of a frontend shares (set once around them with {@link EntryLayoutProvider}). */
export interface EntryLayoutSettings {
  /** The application's name (and mark), the page's heading (h1); a page's own `brand` wins. */
  brand?: ReactNode
  intro?: EntryIntro
  notices?: readonly EntryNotice[]
  /** The links of the footer: help, terms, privacy (gap 8). */
  links?: ReactNode
  otherEntry?: EntryOtherEntry
  /** Small print in the footer: the build the page is from, say. */
  footer?: ReactNode
  /** Offers the appearance (light, dark, system) when an AppearanceProvider is around; default true. */
  appearance?: boolean
}

const EntryLayoutContext = createContext<EntryLayoutSettings>({})

/**
 * Sets what the entry pages share (phase 17zk): the brand column, the notices, the footer's links, the way to the
 * other side. The platform's blocks (sign-in, sign-up, the second step, password reset, the identity provider's
 * return, every page of a mailed link, unsubscribing) take it from here; what a page is given itself wins.
 */
export function EntryLayoutProvider({ children, ...settings }: EntryLayoutSettings & { children: ReactNode }) {
  const outer = useContext(EntryLayoutContext)
  return <EntryLayoutContext.Provider value={{ ...outer, ...settings }}>{children}</EntryLayoutContext.Provider>
}

/** The shared settings of the entry pages around this one. */
export function useEntryLayout(): EntryLayoutSettings {
  return useContext(EntryLayoutContext)
}

export interface EntryLayoutProps extends EntryLayoutSettings {
  /** What the page is for (its title, h2): "Sign in", "Reset your password". */
  title: string
  children: ReactNode
  /** The test id of the form's card. */
  testId?: string
  /** Classes of the page's `<main>`, as `AuthFrame`'s were. */
  className?: string
  /** In place of the title at the top of the card: the tabs of signing in and signing up ({@link EntryTabs}). */
  tabs?: ReactNode
  /** Under the form, inside its card: a hint about this page (the sponsor's "already answering quizzes?"). */
  below?: ReactNode
  /** Shows the way to the other side (`otherEntry`) under the card: on the sign-in and sign-up forms. */
  showOtherEntry?: boolean
  /** The content brings its own heading (`h2`, say a `PageState`): the card shows no title of its own. */
  ownHeading?: boolean
}

/**
 * The template of the pages before a session (phase 17zk; docs/design/12-frontend.md section 14): the notices over
 * the page (the environment banner first), a top bar with the brand and the language at the top right, on a wide
 * screen a column about the application beside the form (one line above it on a phone, the form staying on the first
 * screen), the form's card with its title or tabs, a hint under the form, the way to the other side, and a footer with
 * the application's links, the appearance and the build. Without an intro the card stands alone in the middle.
 */
export function EntryLayout(props: EntryLayoutProps) {
  const shared = useEntryLayout()
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  // A page without a brand of its own or around it names itself (the heading is never empty).
  const brand = props.brand ?? shared.brand ?? props.title
  const intro = props.intro ?? shared.intro
  const notices = props.notices ?? shared.notices ?? []
  const links = props.links ?? shared.links
  const otherEntry = props.otherEntry ?? shared.otherEntry
  const footer = props.footer ?? shared.footer
  const offerAppearance = useHasAppearance() && (props.appearance ?? shared.appearance ?? true)
  const { title, children, testId, className, tabs, below, showOtherEntry, ownHeading } = props
  const phone = useIsMobile()
  const expanded = phone ? notices.find((n) => n.active !== false)?.key : undefined
  const anyActive = notices.some((n) => n.active !== false)

  return (
    <div
      data-slot="entry-layout"
      data-layout={intro ? 'columns' : 'single'}
      className="bg-background text-foreground flex min-h-screen flex-col"
    >
      {/* The notices belong to the page's banner landmark, above its top bar. */}
      <header data-slot="entry-header" className="flex flex-col">
        <div data-slot="entry-notices" className="flex flex-col">
          {/* A test system says so above everything (phase 16zd). */}
          <EnvironmentBanner />
          {notices.length > 0 && (
            // No strip of its own while no notice shows anything.
            <div className={cn('mx-auto flex w-full max-w-6xl flex-col gap-2 px-4', anyActive && 'pt-3')}>
              {notices.map((notice) => phone && notice.summary && notice.active !== false && notice.key !== expanded ? (
                <details key={notice.key} data-slot="entry-notice" data-collapsed="" className="text-sm">
                  <summary className="text-muted-foreground cursor-pointer truncate py-1">{notice.summary}</summary>
                  {notice.content}
                </details>
              ) : (
                <div key={notice.key} data-slot="entry-notice" className="empty:hidden">{notice.content}</div>
              ))}
            </div>
          )}
        </div>
        <div data-slot="entry-top" className="mx-auto flex w-full max-w-6xl items-center justify-between gap-3 px-4 py-3">
          {/* A long name wraps, as the card's heading did. */}
          <h1 data-slot="entry-brand" className="min-w-0 text-xl font-semibold tracking-tight break-words">{brand}</h1>
          {languages.length > 1 && (
            <Select value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
              <SelectTrigger size="sm" aria-label={t('common.language')} data-testid="entry-language" className="shrink-0">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {languages.map((lang) => (
                  <SelectItem key={lang} value={lang} lang={lang}>
                    {LANGUAGE_NAMES[lang]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </div>
      </header>
      <main
        data-slot="entry-main"
        className={cn('mx-auto flex w-full max-w-6xl flex-1 flex-col gap-4 px-4 pb-8',
          intro ? 'lg:grid lg:grid-cols-2 lg:items-center lg:gap-16 lg:py-8' : 'items-center pt-4 sm:pt-12', className)}
      >
        {intro && (
          <section data-slot="entry-intro" aria-label={t('entry.about')} className="flex min-w-0 flex-col gap-6">
            {/* One line on a phone, the column's lead on a wide screen. */}
            <p data-slot="entry-intro-sentence"
              className="text-muted-foreground truncate text-sm lg:text-foreground lg:text-3xl lg:font-semibold lg:tracking-tight lg:whitespace-normal">
              {intro.sentence}
            </p>
            {intro.points && intro.points.length > 0 && (
              <ul data-slot="entry-intro-points" className="hidden flex-col gap-3 lg:flex">
                {intro.points.map((point, index) => (
                  <li key={index} className="flex items-start gap-3">
                    <Check aria-hidden className="text-primary mt-0.5 size-5 shrink-0" />
                    <span>{point}</span>
                  </li>
                ))}
              </ul>
            )}
            {intro.illustration && (
              <div data-slot="entry-intro-illustration" className="hidden lg:block">{intro.illustration}</div>
            )}
          </section>
        )}
        <div data-slot="entry-form" className="flex w-full flex-col items-center gap-4">
          <Card className="w-full max-w-sm" data-testid={testId}>
            {(!ownHeading || tabs) && (
              <CardHeader className="text-center">
                {!ownHeading && (
                  <CardTitle className={tabs ? 'sr-only' : undefined}>
                    <h2 data-slot="entry-title" className="text-xl font-semibold tracking-tight">{title}</h2>
                  </CardTitle>
                )}
                {tabs}
              </CardHeader>
            )}
            <CardContent className="flex flex-col gap-4">
              {children}
              {below && <div data-slot="entry-below" className="text-muted-foreground text-sm">{below}</div>}
            </CardContent>
          </Card>
          {showOtherEntry && otherEntry && (
            <p data-testid="entry-other" className="text-muted-foreground w-full max-w-sm text-center text-sm">
              {otherEntry.text}{' '}
              <a href={otherEntry.href} className="text-primary font-medium underline-offset-4 hover:underline">
                {otherEntry.link ?? t('entry.goThere')}
              </a>
            </p>
          )}
        </div>
      </main>
      {(links || offerAppearance || footer) && (
        <footer data-slot="entry-footer"
          className="text-muted-foreground mx-auto flex w-full max-w-6xl flex-wrap items-center justify-center gap-x-4 gap-y-2 px-4 py-4 text-sm">
          {links && <nav aria-label={t('entry.links')} data-testid="entry-links" className="flex flex-wrap gap-x-4 gap-y-1">{links}</nav>}
          {offerAppearance && <ThemeToggle />}
          {footer && <p className="text-xs" data-testid="auth-footer">{footer}</p>}
        </footer>
      )}
    </div>
  )
}

/** The current query of the address, kept when switching between signing in and signing up (`?next=`, `?src=`). */
function currentQuery(): string {
  return typeof window === 'undefined' ? '' : window.location.search
}

export interface EntryTabsProps {
  /** Which tab this page is. */
  current: 'signIn' | 'signUp'
  /** The sign-in and the sign-up page, as paths of this frontend; the current one may be left out (this page). */
  signInPath?: string
  signUpPath?: string
  /** Goes to a path of this frontend; a full page load when absent. */
  onNavigate?: (path: string) => void
  /** The address of a path of this frontend, as links show it. */
  hrefOf?: (path: string) => string
}

/**
 * Signing in and signing up as two tabs of one page (suggestion ④): each is a link to its own path, so the old sign-up
 * address keeps working and either can be bookmarked; the query (`?next=`, `?src=`, `?ref=`) goes along. The current
 * one says so (`aria-current="page"`).
 */
export function EntryTabs({ current, signInPath, signUpPath, onNavigate, hrefOf }: EntryTabsProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const query = currentQuery()
  const tab = (which: 'signIn' | 'signUp', path: string | undefined, label: string, testId: string) => {
    const active = which === current
    const to = (path ?? '') + (path?.includes('?') ? '' : query)
    // This page, as the address bar has it (already under the frontend's base).
    const href = path === undefined ? window.location.pathname + query : hrefOf ? hrefOf(to) : to
    const follow = (event: MouseEvent<HTMLAnchorElement>) => {
      if (active) {
        event.preventDefault()
        return
      }
      if (!onNavigate || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
      event.preventDefault()
      onNavigate(to)
    }
    return (
      <a href={href} onClick={follow} aria-current={active ? 'page' : undefined}
        data-testid={testId} data-state={active ? 'active' : 'inactive'}
        className={cn('flex-1 rounded-md px-3 py-1.5 text-center text-sm font-medium transition-colors',
          'focus-visible:ring-ring/50 outline-none focus-visible:ring-[3px]',
          active ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}>
        {label}
      </a>
    )
  }
  return (
    <nav aria-label={t('entry.tabs')} data-slot="entry-tabs" className="bg-muted flex w-full gap-1 rounded-lg p-1">
      {tab('signIn', signInPath, t('entry.signInTab'), 'sign-in-link')}
      {tab('signUp', signUpPath, t('entry.signUpTab'), 'sign-up-link')}
    </nav>
  )
}

/**
 * Seconds still to wait after the server said "too many requests" (429 RATE_LIMITED with `Retry-After`; gap 5),
 * counting down every second. `hold` starts it from a failure (true when the failure asked to wait) and keeps what to
 * say meanwhile: the server's words, or `fallback` when the answer explained nothing (never "HTTP 429"). The text goes
 * when the wait is over, with the button's hold.
 */
export function useRetryWait(fallback?: string): {
  seconds: number
  hold: (error: unknown) => boolean
  message: string | null
} {
  const [until, setUntil] = useState<number | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => {
    if (until === null) return undefined
    const timer = window.setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (current >= until) {
        setUntil(null)
        setMessage(null)
      }
    }, 1000)
    return () => window.clearInterval(timer)
  }, [until])
  const hold = (error: unknown) => {
    if (!(error instanceof ApiError) || error.status !== 429 || !error.retryAfter) return false
    const start = Date.now()
    setNow(start)
    setUntil(start + error.retryAfter * 1000)
    setMessage(explained(error) ? error.display : fallback ?? null)
    return true
  }
  return { seconds: until === null ? 0 : Math.max(0, Math.ceil((until - now) / 1000)), hold, message }
}

/** Whether a failure says something of its own (violations, or a detail or title), not just its status. */
function explained(error: ApiError): boolean {
  const said = (value: unknown) => typeof value === 'string' && value.trim() !== ''
  return error.violations.length > 0 || said(error.problem.detail) || said(error.problem.title)
}

function subscribeWide(onChange: () => void) {
  if (typeof window.matchMedia !== 'function') return () => {}
  const query = window.matchMedia(WIDE_QUERY)
  query.addEventListener?.('change', onChange)
  return () => query.removeEventListener?.('change', onChange)
}

const WIDE_QUERY = '(min-width: 1024px)'

/** Whether the window is a wide screen (the `lg` breakpoint, where the entry pages have two columns). */
export function useIsWide(): boolean {
  return useSyncExternalStore(subscribeWide, () => window.innerWidth >= 1024, () => false)
}

/** A plain link of the footer (help, terms, privacy). */
export function EntryLink({ className, ...props }: ComponentProps<'a'>) {
  return <a {...props} className={cn('hover:text-foreground underline-offset-4 hover:underline', className)} />
}
