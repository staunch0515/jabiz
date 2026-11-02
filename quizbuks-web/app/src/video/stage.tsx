import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { VIMEO_PLAYER } from '../qb/video'
import { recordPlayback, UNKNOWN_AFTER_MS, type PlaybackEvent } from './playback'

/*
 * The one video player of the app (Q65, plan A): an iframe that never moves in the page, laid over the place
 * (`VideoSlot`) that shows it now: the home's card in the middle of the screen, then the start page it opens. A browser
 * plays sound only after a tap, and a new player on the next page would not have had one; moving the iframe in the
 * page would reload it. So the player stays, the slot changes, and the tap that opens the start page turns the sound on
 * through the player's own interface (YouTube's and Vimeo's postMessage commands). A tap on a card not playing (its
 * cover, its name) asks for the sound too: the start page's new player turns it on as soon as it is ready, where the
 * browser allows it after the tap (Android's Chrome); where it does not, the player waits on its own play button,
 * which plays with the sound (Q66).
 *
 * Q75: the players also say where they are (YouTube's `infoDelivery`, Vimeo's `timeupdate` and its play, pause and
 * ended events), which the stage hands to `playback.ts` for the publication shown — on the start page, or on a card
 * once its sound is on (D-Q75-3). A playing player not heard from within a wait is given up on (D-Q75-6).
 */

type Mode = 'card' | 'page'

interface Slot {
  key: string
  player: string
  title: string
  mode: Mode
  element: HTMLElement
  /** Q75: the publication whose video this is, for its playback record. */
  publicationId: string | null
  /** The card's tap: the start page, the sound on. */
  onOpen?: () => void
}

interface StageState {
  slot: Slot | null
  /** The player shown: kept a moment after its slot goes, for the next page's slot to take it over. */
  player: string | null
  /** Whether the viewer asked for the sound, and whether the player said it is on. */
  soundAsked: boolean
  soundOn: boolean
}

let state: StageState = { slot: null, player: null, soundAsked: false, soundOn: false }
const listeners = new Set<() => void>()
let frame: HTMLIFrameElement | null = null
let release: ReturnType<typeof setTimeout> | null = null
/** Until when a card's tap hands the player to the start page it opens (which may first load its data). */
let handoffUntil = 0
const HANDOFF_MS = 10_000
/** A player asked for with its sound by a tap on a card not playing, until when (Q66). */
let wanted: { player: string; until: number } | null = null
/** Q75: whether the loaded player reported anything of its playback yet, and whether it says it is playing. */
let heard = false
let playerPlaying = false
let unknownTimer: ReturnType<typeof setTimeout> | null = null

/** Q75 (D-Q75-3): the playback counts on the start page, or on a card once its sound is on. */
function counting(): boolean {
  return !!state.slot && (state.slot.mode === 'page' || state.soundOn)
}

/** Hands a playback event to the shown publication's record, while it counts. */
function report(event: PlaybackEvent) {
  const publicationId = state.slot?.publicationId
  if (publicationId && counting()) recordPlayback(publicationId, event)
}

/** A fact about the video rather than the watching (its duration): kept whether or not it counts now. */
function always(event: PlaybackEvent) {
  const publicationId = state.slot?.publicationId
  if (publicationId) recordPlayback(publicationId, event)
}

/**
 * Counting starts (the start page's slot, the sound turned on): the anchor is dropped, and a player not heard from
 * within the wait is given up on (D-Q75-6) — once per loaded player.
 */
function startCounting() {
  if (unknownTimer) clearTimeout(unknownTimer)
  unknownTimer = null
  const publicationId = state.slot?.publicationId
  if (!publicationId || !counting()) return
  recordPlayback(publicationId, { type: 'suspend' })
  if (heard) return
  unknownTimer = setTimeout(() => {
    unknownTimer = null
    if (!heard && state.slot?.publicationId === publicationId && counting()) {
      recordPlayback(publicationId, { type: 'unknown' })
    }
  }, UNKNOWN_AFTER_MS)
}

function set(next: Partial<StageState>) {
  state = { ...state, ...next }
  listeners.forEach((listener) => listener())
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The player's address with what the stage needs: started muted, inline, its interface on. */
export function stagedPlayer(player: string, origin: string = window.location.origin): string {
  return player.startsWith(VIMEO_PLAYER)
    ? `${player}?autoplay=1&muted=1&playsinline=1&api=1`
    : `${player}?autoplay=1&mute=1&playsinline=1&rel=0&enablejsapi=1&origin=${encodeURIComponent(origin)}`
}

function post(message: unknown) {
  const target = frame?.contentWindow
  if (!target || !state.player) return
  const vimeo = state.player.startsWith(VIMEO_PLAYER)
  target.postMessage(JSON.stringify(message), vimeo ? 'https://player.vimeo.com' : 'https://www.youtube-nocookie.com')
}

/** Turns the sound on, within the viewer's tap (the browser judges the tap). */
export function soundOn() {
  if (!state.player) return
  if (state.player.startsWith(VIMEO_PLAYER)) {
    post({ method: 'setMuted', value: false })
    post({ method: 'setVolume', value: 1 })
    post({ method: 'play' })
  } else {
    post({ event: 'command', func: 'unMute', args: [] })
    post({ event: 'command', func: 'setVolume', args: [100] })
    post({ event: 'command', func: 'playVideo', args: [] })
  }
  set({ soundAsked: true })
}

/** What the players say back: YouTube's `infoDelivery` with `muted`, Vimeo's `volumechange`. */
function listen(event: MessageEvent) {
  if (!frame || event.source !== frame.contentWindow) return
  let data: Record<string, unknown>
  try {
    data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data
  } catch {
    return
  }
  // A player now ready (YouTube's `onReady`, Vimeo's `ready`) takes the sound asked for before it could hear it.
  if ((data?.event === 'onReady' || data?.event === 'ready') && state.soundAsked && !state.soundOn) soundOn()
  const info = data?.info as Record<string, unknown> | undefined
  if (data?.event === 'infoDelivery' && info && typeof info.muted === 'boolean') {
    if (state.soundOn !== !info.muted) {
      set({ soundOn: !info.muted })
      if (!info.muted) startCounting()
    }
  }
  const vimeo = data?.data as Record<string, unknown> | undefined
  if (data?.event === 'volumechange' && vimeo && typeof vimeo.volume === 'number') {
    const on = vimeo.volume > 0
    if (state.soundOn !== on) {
      set({ soundOn: on })
      if (on) startCounting()
    }
  }
  // Q75: the playback, as each player tells it (D-Q75-2). YouTube: `infoDelivery` carries what changed (the time
  // every half second while playing, the state, the duration), `onStateChange` the state alone (0 ended, 1 playing,
  // 2 paused, 3 buffering). Vimeo: the events asked for in `greet`, and the answer to `getDuration`.
  const at = Date.now()
  if (data?.event === 'infoDelivery' && info) {
    if (typeof info.duration === 'number' && info.duration > 0) {
      heard = true
      always({ type: 'duration', durationSec: info.duration })
    }
    if (typeof info.playerState === 'number') youtubeState(info.playerState, at)
    if (typeof info.currentTime === 'number') {
      heard = true
      report({ type: 'time', currentTime: info.currentTime, playing: playerPlaying, at })
    }
  }
  if (data?.event === 'onStateChange' && typeof data.info === 'number') youtubeState(data.info, at)
  if (data?.event === 'timeupdate' && vimeo && typeof vimeo.seconds === 'number') {
    heard = true
    if (typeof vimeo.duration === 'number' && vimeo.duration > 0) always({ type: 'duration', durationSec: vimeo.duration })
    // Vimeo reports the time only while playing (a `play` before the stage listened would not have been heard).
    playerPlaying = true
    report({ type: 'time', currentTime: vimeo.seconds, playing: true, at })
  }
  if (data?.event === 'play' || data?.event === 'pause') {
    heard = true
    playerPlaying = data.event === 'play'
    report({ type: 'state', playing: playerPlaying, at })
  }
  if (data?.event === 'ended') {
    heard = true
    playerPlaying = false
    report({ type: 'ended' })
  }
  if (data?.method === 'getDuration' && typeof data.value === 'number' && data.value > 0) {
    heard = true
    always({ type: 'duration', durationSec: data.value })
  }
}

function youtubeState(playerState: number, at: number) {
  heard = true
  if (playerState === 0) {
    playerPlaying = false
    report({ type: 'ended' })
    return
  }
  const playing = playerState === 1
  if (playing !== playerPlaying) {
    playerPlaying = playing
    report({ type: 'state', playing, at })
  }
}

/**
 * A tap that opens a start page with the given video: the sound on, and the player kept for the page (Q65, Q66). The
 * playing card's player turns its sound on within the tap; a new one does as it comes, the browser permitting.
 */
export function openWithSound(player: string) {
  if (state.player === player && state.slot) {
    handoffUntil = Date.now() + HANDOFF_MS
    soundOn()
  } else {
    wanted = { player, until: Date.now() + HANDOFF_MS }
  }
}

/** YouTube reports only to a page that says it listens; Vimeo to one that adds the event. */
function greet() {
  if (!state.player) return
  if (state.player.startsWith(VIMEO_PLAYER)) {
    for (const value of ['volumechange', 'timeupdate', 'play', 'pause', 'ended']) post({ method: 'addEventListener', value })
    post({ method: 'getDuration' })
  } else {
    post({ event: 'listening', id: 'qb-stage', channel: 'widget' })
  }
}

function claim(slot: Slot) {
  if (release) {
    clearTimeout(release)
    release = null
  }
  handoffUntil = 0
  const same = state.player === slot.player
  const asked = !!wanted && wanted.player === slot.player && wanted.until >= Date.now()
  wanted = null
  set({ slot, player: slot.player, ...(same ? {} : { soundAsked: asked, soundOn: false }) })
  // Q75: a slot that counts (the start page's) starts the counting afresh.
  startCounting()
}

function leave(slot: Slot) {
  if (state.slot?.element !== slot.element) return
  set({ slot: null })
  // The next page's slot claims it within this tick when it shows the same video; else the player goes. A card's tap
  // waits longer: the start page it opens shows its slot only once its data has come.
  const wait = Math.max(0, handoffUntil - Date.now())
  handoffUntil = 0
  if (unknownTimer) {
    clearTimeout(unknownTimer)
    unknownTimer = null
  }
  release = setTimeout(() => {
    release = null
    if (!state.slot) {
      set({ player: null, soundAsked: false, soundOn: false })
      heard = false
      playerPlaying = false
    }
  }, wait)
}

export function useStage(): StageState {
  return useSyncExternalStore(subscribe, () => state, () => state)
}

/**
 * The place a video shows: an empty 16:9 box the stage lays the player over while `active`. On a card (`mode`
 * `card`) the player cannot be touched: a tap anywhere on it is `onOpen`. On the start page (`page`) the player's
 * controls work.
 */
export function VideoSlot({ id, player, title, mode, publicationId = null, active = true, onOpen, className }: {
  id: string
  player: string
  title: string
  mode: Mode
  /** Q75: the publication whose video this is, for its playback record. */
  publicationId?: string | null
  active?: boolean
  onOpen?: () => void
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element || !active) return
    const slot: Slot = { key: id, player, title, mode, element, publicationId, onOpen }
    claim(slot)
    return () => leave(slot)
  }, [id, player, title, mode, publicationId, active, onOpen])
  return (
    <div ref={ref} className={className ?? 'bg-muted relative aspect-video w-full'} data-testid="video-slot"
      data-mode={mode} data-active={active || undefined} />
  )
}

/**
 * The stage itself, once in the app's frame: the iframe over the slot's place, followed while the page moves. The
 * frame stays the same element as long as the video does.
 */
export function VideoStage() {
  const stage = useStage()
  const [rect, setRect] = useState<{ top: number; left: number; width: number; height: number } | null>(null)
  const slot = stage.slot
  useEffect(() => {
    window.addEventListener('message', listen)
    return () => window.removeEventListener('message', listen)
  }, [])
  useEffect(() => {
    if (!slot) return
    let running = true
    const place = () => {
      if (!running) return
      const box = slot.element.getBoundingClientRect()
      const next = { top: box.top + window.scrollY, left: box.left + window.scrollX, width: box.width,
        height: box.height }
      setRect((current) => (current && current.top === next.top && current.left === next.left
        && current.width === next.width && current.height === next.height ? current : next))
      requestAnimationFrame(place)
    }
    place()
    return () => { running = false }
  }, [slot])
  if (!stage.player || typeof document === 'undefined') return null
  const visible = !!slot && !!rect
  return createPortal(
    <div data-testid="video-stage" data-mode={slot?.mode}
      style={{ position: 'absolute', top: rect?.top ?? 0, left: rect?.left ?? 0, width: rect?.width ?? 0,
        height: rect?.height ?? 0, visibility: visible ? 'visible' : 'hidden', zIndex: 5 }}>
      <iframe
        key={stage.player}
        ref={(element) => {
          frame = element
        }}
        src={stagedPlayer(stage.player)}
        title={slot?.title ?? ''}
        className="size-full border-0"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
        allowFullScreen
        referrerPolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
        onLoad={() => {
          // Q75: a new player: nothing heard yet; the wait starts where the counting does.
          heard = false
          playerPlaying = false
          greet()
          if (state.soundAsked) soundOn()
          startCounting()
        }}
      />
      {/* On a card nothing in the player is for touching: the whole of it opens the start page. */}
      {slot?.mode === 'card' && (
        <button type="button" className="absolute inset-0 size-full cursor-pointer" data-testid="video-open"
          aria-label={slot.title} onClick={() => slot.onOpen?.()} />
      )}
    </div>,
    document.body,
  )
}
