import { Button } from '@jabiz/ui'
import { ChevronDown, ChevronUp, Play, Volume2, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { CoverPlaceholder } from '../qb/Illustrations'
import { sponsorPagePath } from '../qb/page'
import type { FeedCard } from '../qb/play'
import { Link } from '../qb/router'
import { prefersStillVideo, trackable } from '../qb/video'
import { LazyImage } from '../qb/view/LazyImage'
import { RewardText } from '../qb/view/FeedCard'
import { QBVIEW } from '../qb/view/messages'
import { VideoPlayer } from '../qb/view/VideoPlayer'
import { useVideoFailed, videoFailed } from '../qb/videoFailures'
import { openWithSound, soundOn, stagePlayer, useStage, VideoSlot } from '../video/stage'
import { ExposureWatch } from '../exposure/ExposureWatch'
import { queueReport } from '../exposure/reporter'
import { FileImage } from '../qb/FileImage'
import { FLOW_OTHER, FlowStart } from './FlowStart'

/** The Sponsor's picture over the video (32 px): their own, else their initial. */
function SponsorAvatar({ card }: { card: FeedCard }) {
  const initial = (card.sponsorCompany ?? card.sponsorName ?? card.name).trim().charAt(0).toUpperCase()
  return card.sponsorAvatar
    ? <FileImage fileId={card.sponsorAvatar} variant="w128" alt="" className="size-8 shrink-0 rounded-full" />
    : <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-white/20 text-sm font-bold">{initial}</span>
}

/**
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): the full-screen vertical mode, like YouTube's Shorts. It lies over the
 * home (whose place stays where it was), one short video a screen; a swipe up is the next, down the one before (a
 * vertical scroll that snaps, the screen shown judged by IntersectionObserver), and so are the buttons and the arrow
 * keys (Escape goes back). Only the screen shown has a player, and only one (Q65): YouTube's and Vimeo's are the app's
 * one player (the stage), whose watching counts towards "watch to answer" (Q75) and whose sound, once on, stays on
 * from one video to the next; the other sites play in their own player, whose sound the viewer turns on with one tap
 * on it (A3-18). With less motion asked for, nothing plays by itself: the screen offers a play button. "Answer" opens
 * the start page with the same player.
 *
 * F-S13: with `exposure` (a signed-in taker with a verified address), a screen of another's video (`exposed`: the
 * pushes the home places among the short videos) reports that it was opened, the exposure once it played on for the
 * seconds of the parameter, and going on to answer (`exposure/`).
 *
 * Not tied to the home's layout: it takes the short videos, the one to start on and what to do on leaving or
 * answering, so that it moves into the new frame of Q108 as it is.
 */
export function ShortsViewer({ cards, startId, onClose, onDetails, menu, exposure = false, title, onNearEnd }: {
  cards: readonly FeedCard[]
  startId: string
  onClose: () => void
  /** Q113c: "Details": the publication's start page (its normal mode), the player carried over with its sound. */
  onDetails: (card: FeedCard) => void
  /** The mode's name for assistive technology: the row of shorts' when none (Q113c: the posts' videos have theirs). */
  title?: string
  /** Q113c: told when the screen shown is near the end of the list (the home then reads its next page). */
  onNearEnd?: () => void
  /** F-S8: the screen's menu (blocking), beside "answer". */
  menu?: (card: FeedCard) => ReactNode
  /** F-S13: whether this taker's watching is reported (signed in, address verified). */
  exposure?: boolean
}) {
  const { t } = useTranslation(APP)
  const { t: tView } = useTranslation(QBVIEW)
  const [index, setIndex] = useState(() => Math.max(0, cards.findIndex((card) => card.publicationId === startId)))
  const [still] = useState(prefersStillVideo)
  // With less motion asked for: the screens whose play button was pressed.
  const [played, setPlayed] = useState<ReadonlySet<string>>(() => new Set())
  const scroller = useRef<HTMLDivElement>(null)
  const dialog = useRef<HTMLDivElement>(null)
  const stage = useStage()
  // F-S15: re-rendered when a video file fails.
  useVideoFailed(null)
  // F-S8: the screen shown follows the publication the address names (a block moves it on), and stays within the list
  // when the list is read again shorter: adjusted while rendering (React's derived state), the scroll after it.
  const [seen, setSeen] = useState({ cards, startId })
  const [scrollTo, setScrollTo] = useState<number | null>(null)
  if (seen.cards !== cards || seen.startId !== startId) {
    setSeen({ cards, startId })
    // F-S13: the list read again or placed anew (the pushes, the parameters arriving) keeps the screen shown; a new
    // address (a block moved it on) goes there.
    const shownId = seen.startId === startId ? seen.cards[index]?.publicationId : undefined
    const kept = shownId ? cards.findIndex((card) => card.publicationId === shownId) : -1
    const found = kept >= 0 ? kept : cards.findIndex((card) => card.publicationId === startId)
    const at = found >= 0 ? found : Math.min(index, Math.max(0, cards.length - 1))
    if (at !== index) {
      setIndex(at)
      setScrollTo(at)
    }
  }
  useEffect(() => {
    if (scrollTo === null) return
    scroller.current?.querySelectorAll<HTMLElement>('[data-shorts-screen]')[scrollTo]?.scrollIntoView?.({ block: 'start' })
  }, [scrollTo])

  // The home underneath does not scroll while the mode is open; its place is kept for the way back.
  useEffect(() => {
    const before = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    dialog.current?.focus()
    return () => {
      document.body.style.overflow = before
    }
  }, [])

  // F-S13: a push shown is opened (however long it is watched): it is not pushed to this taker again.
  const current = cards[index]
  // Q113c: the list is the home's posts read so far; two screens before its end the home reads the next page.
  const nearEnd = index >= cards.length - 2
  const latestNearEnd = useRef(onNearEnd)
  useEffect(() => {
    latestNearEnd.current = onNearEnd
  })
  useEffect(() => {
    if (nearEnd) latestNearEnd.current?.()
  }, [nearEnd, cards.length])
  const openedId = exposure && current?.exposed ? current.publicationId : null
  useEffect(() => {
    if (openedId) queueReport({ publicationId: openedId, kind: 'OPEN', surface: 'VERTICAL' })
  }, [openedId])

  const screenAt = (at: number) => scroller.current?.querySelectorAll<HTMLElement>('[data-shorts-screen]')[at]

  // The screen to start on, at once.
  useEffect(() => {
    screenAt(index)?.scrollIntoView?.({ block: 'start' })
    // Only on opening: later moves scroll themselves.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Moves to a screen: its player takes the sound the one before had (YouTube and Vimeo, by the stage). */
  const go = useCallback((to: number) => {
    if (to < 0 || to >= cards.length || to === index) return
    const next = cards[to]
    const now = stagePlayer()
    if (next.video && trackable(next.video) && now.soundOn) openWithSound(next.video)
    setIndex(to)
    screenAt(to)?.scrollIntoView?.({ block: 'start', behavior: still ? 'auto' : 'smooth' })
  }, [cards, index, still])

  // A swipe: the screen most in view is the one shown.
  useEffect(() => {
    const root = scroller.current
    if (!root || typeof IntersectionObserver === 'undefined') return
    const observer = new IntersectionObserver((entries) => {
      const seen = entries.filter((entry) => entry.isIntersecting)
        .map((entry) => Number((entry.target as HTMLElement).dataset.shortsScreen))
      if (seen.length > 0) {
        const at = seen[0]
        const next = cards[at]
        if (next?.video && trackable(next.video) && stagePlayer().soundOn) openWithSound(next.video)
        setIndex(at)
      }
    }, { root, threshold: 0.6 })
    root.querySelectorAll('[data-shorts-screen]').forEach((screen) => observer.observe(screen))
    return () => observer.disconnect()
  }, [cards])

  // The keys work wherever the focus is while the mode is open (a button that became disabled lets it go).
  const keys = (event: globalThis.KeyboardEvent) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight' || event.key === 'PageDown') {
      event.preventDefault()
      go(index + 1)
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft' || event.key === 'PageUp') {
      event.preventDefault()
      go(index - 1)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
    }
  }

  const latestKeys = useRef(keys)
  useEffect(() => {
    latestKeys.current = keys
  })
  useEffect(() => {
    const listener = (event: globalThis.KeyboardEvent) => latestKeys.current(event)
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  const details = (card: FeedCard) => {
    // F-S13: going on to answer from this mode: a vertical video of another's counts it here (its start page does
    // not count vertical ones).
    if (exposure && card.exposed && card.videoVertical) {
      queueReport({ publicationId: card.publicationId, kind: 'GO', surface: 'VERTICAL' })
    }
    // The start page takes over the playing player with its sound (Q65, Q66).
    if (card.video && trackable(card.video)) openWithSound(card.video)
    onDetails(card)
  }

  return (
    // Q113c (§9.3): black, no head and no bottom menu; at the top only the way back (its name is the mode's), where the
    // screen is and the buttons for the one before and after (the arrow keys too).
    <div ref={dialog} role="dialog" aria-modal="true" aria-label={title ?? t('shorts.title')} tabIndex={-1}
      className="fixed inset-0 z-50 flex flex-col bg-black text-white outline-none" data-testid="shorts-viewer">
      <div className="flex h-12 shrink-0 items-center gap-2 px-2">
        <Button variant="ghost" size="icon" onClick={onClose} aria-label={t('shorts.close')} data-testid="shorts-close"
          className="rounded-full text-white hover:bg-white/10 hover:text-white">
          <X aria-hidden />
        </Button>
        <h2 className="sr-only">{title ?? t('shorts.title')}</h2>
        <span className="flex-1" />
        <span className="text-sm text-white/80" aria-live="polite" data-testid="shorts-position">
          {t('shorts.position', { n: index + 1, total: cards.length })}
        </span>
        <Button variant="ghost" size="icon" disabled={index === 0} onClick={() => go(index - 1)}
          aria-label={t('shorts.previous')} data-testid="shorts-previous"
          className="rounded-full text-white hover:bg-white/10 hover:text-white">
          <ChevronUp aria-hidden />
        </Button>
        <Button variant="ghost" size="icon" disabled={index >= cards.length - 1} onClick={() => go(index + 1)}
          aria-label={t('shorts.next')} data-testid="shorts-next"
          className="rounded-full text-white hover:bg-white/10 hover:text-white">
          <ChevronDown aria-hidden />
        </Button>
      </div>
      {/* The app's player stays within the screens (the stage's `data-video-area`): a screen's player moving in from
          above must not cover the header, whose buttons would then miss a tap made while the scroll travels. */}
      <div ref={scroller} className="min-h-0 flex-1 snap-y snap-mandatory overflow-y-auto overscroll-contain"
        data-testid="shorts-screens" data-video-area="">
        {cards.map((card, at) => {
          const shown = at === index
          // F-S15: a video file that failed to play shows its cover, and a link to the file.
          const failed = videoFailed(card.video)
          const plays = shown && !!card.video && !failed && (!still || played.has(card.publicationId))
          const sponsor = card.sponsorCompany ?? card.sponsorName
          return (
            <section key={card.publicationId} data-shorts-screen={at} data-testid="shorts-screen"
              data-publication={card.publicationId} data-current={shown || undefined}
              aria-roledescription={t('shorts.slide')} aria-label={t('shorts.position', { n: at + 1, total: cards.length })}
              aria-hidden={shown ? undefined : true} inert={!shown || undefined}
              className="flex h-full snap-start flex-col items-center gap-3 pb-4">
              {/* F-S13: the screen shown of another's video counts its exposure while it plays. */}
              {shown && exposure && card.exposed && (
                <ExposureWatch key={card.publicationId} publicationId={card.publicationId} surface="VERTICAL"
                  video={card.video} durationSec={card.videoDurationSec} active={plays} />
              )}
              {/* A vertical video fills the height; any other is across the width in the middle (Q113c). */}
              <div className="flex min-h-0 w-full flex-1 items-center justify-center" data-testid="shorts-stage"
                data-orientation={card.videoVertical ? 'vertical' : 'landscape'}>
                <div className={card.videoVertical
                  ? 'relative aspect-[9/16] h-full max-w-full overflow-hidden rounded-md bg-neutral-900'
                  : 'relative aspect-video max-h-full w-full overflow-hidden bg-neutral-900'}>
                  {plays && trackable(card.video)
                    ? <VideoSlot id={`short:${card.publicationId}`} player={card.video!} mode="short"
                      publicationId={card.publicationId} className="absolute inset-0 size-full"
                      title={tView('feed.videoTitle', { name: card.name })} />
                    : plays
                      ? <VideoPlayer player={card.video!} autoplay className="absolute inset-0 size-full"
                        title={tView('feed.videoTitle', { name: card.name })} />
                      : (
                        <>
                          {card.cover
                            ? <LazyImage fileId={card.cover} variant="w640" alt="" className="absolute inset-0 size-full object-cover" />
                            : <CoverPlaceholder seed={card.publicationId} className="absolute inset-0 size-full" />}
                          {shown && card.video && failed && (
                            <span className="absolute inset-0 flex items-center justify-center">
                              <Button asChild size="lg" variant="secondary">
                                <a href={card.video} target="_blank" rel="noopener noreferrer"
                                  data-testid="shorts-open-video">{tView('feed.openVideo')}</a>
                              </Button>
                            </span>
                          )}
                          {shown && card.video && !failed && (
                            <span className="absolute inset-0 flex items-center justify-center">
                              <Button size="lg" className="rounded-full" data-testid="shorts-play"
                                onClick={() => setPlayed((done) => new Set(done).add(card.publicationId))}>
                                <Play aria-hidden />
                                {t('shorts.play')}
                              </Button>
                            </span>
                          )}
                        </>
                      )}
                </div>
              </div>
              {/* Over the foot of the screen (Q113c, §9.3): the Sponsor (to their page), the name, the reward in its colour,
                  and the two buttons; each may be absent (ads and entertainment videos later have "Details" only). */}
              <div className="flex w-full max-w-md shrink-0 flex-col gap-2 px-4">
                <div className="flex min-w-0 items-center gap-2 text-[15px] leading-5">
                  {sponsor && card.sponsorHandle
                    ? <Link to={sponsorPagePath(card.sponsorHandle)} className="flex min-w-0 items-center gap-2 font-bold hover:underline"
                      aria-label={tView('feed.sponsorPage', { name: sponsor })} data-testid="shorts-sponsor">
                      <SponsorAvatar card={card} /><span className="truncate">{sponsor}</span></Link>
                    : sponsor && <span className="flex min-w-0 items-center gap-2 font-bold" data-testid="shorts-sponsor">
                      <SponsorAvatar card={card} /><span className="truncate">{sponsor}</span></span>}
                  {menu && <span className="ml-auto shrink-0 [&_button]:text-white">{menu(card)}</span>}
                </div>
                <h3 className="line-clamp-2 text-[17px] leading-6 font-bold" data-testid="shorts-name">{card.name}</h3>
                <span className="flex flex-wrap items-center gap-x-1.5 text-[13px] text-white/80">
                  <span data-testid="shorts-reward"><RewardText card={card} className="text-qb-reward font-bold" /></span>
                  {card.watchRequired && (
                    <>
                      <span aria-hidden>·</span>
                      <span data-testid="shorts-watch">{tView('feed.watchFirst')}</span>
                    </>
                  )}
                </span>
                <div className="flex items-start justify-end gap-2">
                  <Button className={FLOW_OTHER} onClick={() => details(card)} data-testid="shorts-details">
                    {t('shorts.details')}
                  </Button>
                  {shown && <FlowStart card={card} exposure={exposure} />}
                </div>
                {/* The sound: YouTube's and Vimeo's with the app's button (it then stays on); the others' by a tap on
                    their own player (A3-18). */}
                {plays && trackable(card.video) && !stage.soundOn && (
                  <Button variant="outline" size="sm" onClick={() => soundOn()} data-testid="shorts-sound"
                    className="self-start rounded-full border-white/60 bg-transparent text-white hover:bg-white/10 hover:text-white">
                    <Volume2 aria-hidden />
                    {t('shorts.soundOn')}
                  </Button>
                )}
                {plays && !trackable(card.video) && (
                  <p className="text-xs text-white/80" data-testid="shorts-tap-for-sound">{t('shorts.tapForSound')}</p>
                )}
              </div>
            </section>
          )
        })}
      </div>
    </div>
  )
}
