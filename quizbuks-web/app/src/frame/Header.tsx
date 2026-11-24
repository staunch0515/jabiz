import { Button, cn } from '@jabiz/ui'
import { useAuth } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { ACCOUNT_PATH } from '../pages/accountPaths'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { readMyProfile } from '../qb/api'
import { FrameLink } from './FrameLink'
import { nextScrollChrome, SHOWN } from './scrollChrome'
import { XSPEC } from './xspec'
import './messages'

/*
 * The heads of the taker app's pages (Q113 §9.1, P4): three kinds and no other. A first-level page (an area's own page
 * or one of its tabs) has the taker's avatar, the title and the page's icon buttons; the home has the mark in the
 * middle instead of a title. A page under another has the way back, the title and a line under it. A page that takes
 * the whole screen (answering) has only its own way out. All stick to the top, white at 85 % over the page blurred
 * under them, and on a phone leave with the scrolling (§9.1a, `scrollChrome.ts`).
 */

/** The platform's `md`: from this width on the frame is the wide one, whose heads never leave (§9.1a "宽屏"). */
const WIDE = '(min-width: 48rem)'

const ChromeOffset = createContext(0)

/** How far the heads are moved out by the scrolling (0 on a wide screen and at the top). */
export function useChromeOffset(): number {
  return useContext(ChromeOffset)
}

/** Follows the document's scrolling on a phone with the rule of `scrollChrome.ts`. */
function useScrollChromeOffset(): number {
  const [offset, setOffset] = useState(0)
  useEffect(() => {
    const wide = window.matchMedia?.(WIDE)
    let state = SHOWN
    const update = () => {
      state = wide?.matches ? SHOWN : nextScrollChrome(state, window.scrollY, performance.now())
      setOffset(state.offset)
    }
    window.addEventListener('scroll', update, { passive: true })
    wide?.addEventListener('change', update)
    return () => {
      window.removeEventListener('scroll', update)
      wide?.removeEventListener('change', update)
    }
  }, [])
  return offset
}

/** Whether a page draws its own head, so that the frame's own (the home's, a quiz's) stands back. */
const OwnHead = createContext<{ own: boolean; claim: () => () => void }>({ own: false, claim: () => () => {} })

/** The frame's part of the heads: the scrolling, and which head is the page's. */
export function HeadsProvider({ children }: { children: ReactNode }) {
  const offset = useScrollChromeOffset()
  const [claims, setClaims] = useState(0)
  const claim = useCallback(() => {
    setClaims((n) => n + 1)
    return () => setClaims((n) => n - 1)
  }, [])
  const own = useMemo(() => ({ own: claims > 0, claim }), [claims, claim])
  return (
    <ChromeOffset.Provider value={offset}>
      <OwnHead.Provider value={own}>{children}</OwnHead.Provider>
    </ChromeOffset.Provider>
  )
}

/** Whether the page shown draws its own head. */
export function usePageHasOwnHead(): boolean {
  return useContext(OwnHead).own
}

/** A page's head says so before it is painted, so the frame's head never shows above it. */
function useClaimHead() {
  const { claim } = useContext(OwnHead)
  useLayoutEffect(() => claim(), [claim])
}

/**
 * The sticky band of a head: the row of 53 px and the page's tabs under it, moved out by the scrolling at most its own
 * height. `bleed` takes it across the page's padding at the top and the sides (a head inside the page's column).
 */
export function StickyHead({ children, tabs, bleed = false, testId }: {
  children: ReactNode
  tabs?: ReactNode
  bleed?: boolean
  testId?: string
}) {
  const offset = useChromeOffset()
  const height = XSPEC.headerHeight + (tabs ? XSPEC.tabsHeight : 0)
  const style: CSSProperties = {
    transform: `translateY(-${Math.min(offset, height)}px)`,
    transition: 'transform var(--x-header-transition) cubic-bezier(0, 0, 0, 1)',
    backgroundColor: 'color-mix(in srgb, var(--background) var(--x-header-opacity), transparent)',
    backdropFilter: 'blur(var(--x-header-blur))',
    WebkitBackdropFilter: 'blur(var(--x-header-blur))',
  }
  return (
    <header data-slot="x-head" data-testid={testId} style={style}
      className={cn('border-border sticky top-0 z-10 border-b', bleed && '-mx-4 -mt-4')}>
      <div className="flex h-(--x-header) items-center">{children}</div>
      {tabs}
    </header>
  )
}

/** The page's title: 20 px bold, where the focus goes on a change of page. */
function HeadTitle({ id, title, note }: { id?: string; title: string; note?: ReactNode }) {
  return (
    <div className="mr-auto flex min-w-0 flex-col justify-center">
      <h1 id={id} data-slot="page-title" tabIndex={-1}
        className="truncate text-(length:--x-title) leading-6 font-bold outline-none">{title}</h1>
      {note && <div className="text-muted-foreground truncate text-(length:--x-note) leading-4" data-slot="x-head-note">{note}</div>}
    </div>
  )
}

/** The taker's initial in a 32 px circle: the way to "Me" (the drawer's, from Q113a-2). */
export function TakerAvatar() {
  const { t } = useTranslation(APP)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const initial = (profile.data?.displayName || email || '?').trim().charAt(0).toUpperCase()
  return (
    <FrameLink to={ACCOUNT_PATH} aria-label={t('frame.heads.avatar')} data-testid="head-avatar"
      className={cn('bg-primary text-primary-foreground flex size-(--x-avatar) shrink-0 items-center justify-center',
        'focus-visible:ring-ring rounded-full text-sm font-semibold outline-none focus-visible:ring-2')}>
      <span aria-hidden>{initial}</span>
    </FrameLink>
  )
}

/** The head of a first-level page: the avatar, the title, the page's icon buttons; the page's tabs under them. */
export function TopHead({ title, id, note, actions, tabs, bleed }: {
  title: string
  id?: string
  note?: ReactNode
  actions?: ReactNode
  tabs?: ReactNode
  bleed?: boolean
}) {
  useClaimHead()
  return (
    <StickyHead tabs={tabs} bleed={bleed} testId="area-header">
      <div className="flex w-full items-center gap-6 px-(--x-side)" data-head="top">
        <TakerAvatar />
        <HeadTitle id={id} title={title} note={note} />
        {actions}
      </div>
    </StickyHead>
  )
}

/** The way back of a page under another: a link to the page above it, or back in the history. */
export type Back = { to: string; label: string } | { onClick: () => void; label: string }

/** The head of a page under another: the way back, the title and a line under it, the page's icon buttons. */
export function SubHead({ title, id, note, back, actions, tabs, bleed, claim = true }: {
  title?: string
  id?: string
  note?: ReactNode
  back: Back
  actions?: ReactNode
  tabs?: ReactNode
  bleed?: boolean
  /** False for the frame's own head (it stands back for a page's). */
  claim?: boolean
}) {
  return claim
    ? <ClaimedSubHead title={title} id={id} note={note} back={back} actions={actions} tabs={tabs} bleed={bleed} testId="area-header" />
    : <SubHeadBand title={title} id={id} note={note} back={back} actions={actions} tabs={tabs} bleed={bleed} testId="header" />
}

function ClaimedSubHead(props: Parameters<typeof SubHeadBand>[0]) {
  useClaimHead()
  return <SubHeadBand {...props} />
}

function SubHeadBand({ title, id, note, back, actions, tabs, bleed, testId }: {
  testId: string
  title?: string
  id?: string
  note?: ReactNode
  back: Back
  actions?: ReactNode
  tabs?: ReactNode
  bleed?: boolean
}) {
  const button = 'size-(--x-back) shrink-0 rounded-full'
  return (
    <StickyHead tabs={tabs} bleed={bleed} testId={testId}>
      <div className="flex w-full items-center gap-4 pr-(--x-side) pl-(--x-back-inset)" data-head="sub">
        {'to' in back ? (
          <Button asChild variant="ghost" size="icon" className={button}>
            <FrameLink to={back.to} aria-label={back.label} data-testid="header-back"><ArrowLeft aria-hidden /></FrameLink>
          </Button>
        ) : (
          <Button variant="ghost" size="icon" className={button} aria-label={back.label} onClick={back.onClick}
            data-testid="header-back">
            <ArrowLeft aria-hidden />
          </Button>
        )}
        {title ? <HeadTitle id={id} title={title} note={note} /> : <span className="mr-auto" />}
        {actions}
      </div>
    </StickyHead>
  )
}
