import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore, type CSSProperties,
  type ReactNode } from 'react'
import { cn } from '@jabiz/ui'
import { createPortal } from 'react-dom'
import { prefersStillVideo, VIMEO_PLAYER, videoKind } from '../qb/video'
import { markVideoFailed } from '../qb/videoFailures'
import { FullscreenButton, useFullscreen } from '../qb/view/FullscreenButton'
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

/**
 * Where the player shows: a home's card, the start page, or (F-S10) a screen of the full-screen vertical mode, which
 * lies over the home: the player is laid above it, and its watching counts as the start page's does (Q75, B of the
 * 2026-11-06 review).
 */
type Mode = 'card' | 'page' | 'short'

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
  /** The cover the player fades in over (`VideoStage`, `crossFade`). */
  cover?: ReactNode
  /** F-S10: a vertical video in the vertical mode fills its place (`coverBox`), cut at the edges, never letterboxed. */
  fit?: 'cover'
}

interface StageState {
  slot: Slot | null
  /** The player shown: kept a moment after its slot goes, for the next page's slot to take it over. */
  player: string | null
  /** Whether the viewer asked for the sound, and whether the player said it is on. */
  soundAsked: boolean
  soundOn: boolean
  /** Whether the player shown has its first frame: until it has, the stage is transparent (see `framedNow`). */
  framed: boolean
  /**
   * A card that stopped playing: its cover is kept on the stage while the player fades back to it, so the cover
   * is already painted when the card's own picture takes over (the player is released once the fade is done).
   */
  returning: { cover: ReactNode } | null
}

let state: StageState = { slot: null, player: null, soundAsked: false, soundOn: false, framed: false, returning: null }
const listeners = new Set<() => void>()
let frame: HTMLIFrameElement | null = null
/** F-S15: the stage's `<video>` while the player is a video file. */
let media: HTMLVideoElement | null = null
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
/** A player that says nothing of its first frame within this wait is shown anyway (its own error page, say). */
const FRAME_WAIT_MS = 8_000
let frameTimer: ReturnType<typeof setTimeout> | null = null
/**
 * F-S10: until when a short's player has had its sound asked for without the viewer's tap (a new player taking the
 * sound of the one before, after a swipe, or a tap the browser no longer counts): a browser that refuses the sound
 * then pauses the video (iOS's Safari, a file unmuted), leaving it on its paused controls. A short plays on instead,
 * muted (`playMuted`), and offers the sound button again.
 */
let unaskedSoundUntil = 0
let unaskedSoundTimer: ReturnType<typeof setTimeout> | null = null
/** How long a short's player has to show it took the sound asked for without a tap (it plays, or it is given up). */
export const UNASKED_SOUND_MS = 3_000
/** YouTube's last state of the player (`onStateChange`): 1 playing, 3 buffering. */
let youtubePlayerState: number | null = null

/**
 * The player's first frame is there: the stage shows it. Until then the stage is transparent, so what lies under its
 * place (a card's cover) stays in view, rather than the player's black before its first frame. A player's own sound
 * and counting do not wait for it.
 */
function framedNow() {
  if (frameTimer) {
    clearTimeout(frameTimer)
    frameTimer = null
  }
  if (state.player && !state.framed) set({ framed: true })
}

/** How long a switch between a cover and its player takes (one fades out while the other fades in). */
export const SWITCH_MS = 450

/**
 * A frame's player (YouTube, Vimeo) says it plays a moment before its first frame is painted, black until then; its
 * fade in starts after this beat, so the black is never what fades in. A file's `<video>` has its frame when it says so.
 */
export const FRAME_PAINT_MS = 250

/**
 * The cover and its player cross-fade: the cover fades out while the player fades in over it (and back again), so
 * neither ever shows an empty or black place between them. Faded by `opacity`, so nothing reloads; a viewer who asked
 * for less motion sees it at once. `settle` holds a fade in back by `FRAME_PAINT_MS` (a frame's player).
 */
export function crossFade(playerShown: boolean, settle: boolean): { player: CSSProperties; cover: CSSProperties } {
  const still = prefersStillVideo()
  const delay = playerShown && settle ? FRAME_PAINT_MS : 0
  const transition = still ? undefined : `opacity ${SWITCH_MS}ms ease-in-out ${delay}ms`
  return { player: { opacity: playerShown ? 1 : 0, transition }, cover: { opacity: playerShown ? 0 : 1, transition } }
}

/** A new player is loaded: its first frame is awaited (see `framedNow`), for a while at most. */
function awaitFrame() {
  if (frameTimer) clearTimeout(frameTimer)
  frameTimer = setTimeout(() => {
    frameTimer = null
    framedNow()
  }, FRAME_WAIT_MS)
}

/**
 * Q75 (D-Q75-3): the playback counts on the start page and (F-S10) in the vertical mode, or on a card once its sound
 * is on.
 */
function counting(): boolean {
  return !!state.slot && (state.slot.mode !== 'card' || state.soundOn)
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

/**
 * Turns the sound on, within the viewer's tap (the browser judges the tap). `tap` false: asked for by the stage itself
 * (a new player taking the sound asked for before it was ready), which a browser may refuse (see `unaskedSoundUntil`).
 */
export function soundOn(tap = true) {
  if (!state.player) return
  const unasked = !tap && state.slot?.mode === 'short'
  if (unasked) awaitUnaskedSound()
  if (media && videoKind(state.player) === 'file') {
    media.muted = false
    media.volume = 1
    void media.play()?.catch(() => {
      if (soundRefused()) playMuted()
    })
  } else if (state.player.startsWith(VIMEO_PLAYER)) {
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

/** F-S10: the sound asked for without a tap is being awaited; once the wait is over, a player not playing is restarted muted. */
function awaitUnaskedSound() {
  unaskedSoundUntil = Date.now() + UNASKED_SOUND_MS
  if (unaskedSoundTimer) clearTimeout(unaskedSoundTimer)
  unaskedSoundTimer = setTimeout(() => {
    unaskedSoundTimer = null
    if (!soundRefused(true)) return
    const file = videoKind(state.player) === 'file'
    // Still loading (YouTube buffering, a file without its data) is no refusal; anything else not playing is.
    const stuck = file ? !!media && media.paused : !playerPlaying && youtubePlayerState !== 3
    if (stuck) playMuted()
    else unaskedSoundUntil = 0
  }, UNASKED_SOUND_MS)
}

/** Whether a short's player is within the wait of a sound asked for without a tap (`end`: the wait just ended). */
function soundRefused(end = false): boolean {
  return state.slot?.mode === 'short' && !!state.player && (end ? unaskedSoundUntil > 0
    : unaskedSoundUntil >= Date.now())
}

/** F-S10: the browser refused the sound and stopped the short: it plays on muted, and the sound button shows again. */
function playMuted() {
  unaskedSoundUntil = 0
  if (unaskedSoundTimer) {
    clearTimeout(unaskedSoundTimer)
    unaskedSoundTimer = null
  }
  if (!state.player) return
  if (media && videoKind(state.player) === 'file') {
    media.muted = true
    void media.play()?.catch(() => undefined)
  } else if (state.player.startsWith(VIMEO_PLAYER)) {
    post({ method: 'setMuted', value: true })
    post({ method: 'play' })
  } else {
    post({ event: 'command', func: 'mute', args: [] })
    post({ event: 'command', func: 'playVideo', args: [] })
  }
  set({ soundAsked: false, soundOn: false })
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
  if ((data?.event === 'onReady' || data?.event === 'ready') && state.soundAsked && !state.soundOn) soundOn(false)
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
    framedNow()
    if (typeof vimeo.duration === 'number' && vimeo.duration > 0) always({ type: 'duration', durationSec: vimeo.duration })
    // Vimeo reports the time only while playing (a `play` before the stage listened would not have been heard).
    playerPlaying = true
    report({ type: 'time', currentTime: vimeo.seconds, playing: true, at })
  }
  if (data?.event === 'play' || data?.event === 'pause') {
    heard = true
    if (data.event === 'play') framedNow()
    playerPlaying = data.event === 'play'
    report({ type: 'state', playing: playerPlaying, at })
    if (data.event === 'pause' && soundRefused()) playMuted()
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

/** F-S15: what the stage's `<video>` says, as the frames' messages say it. */
function mediaEvent(event: Event) {
  const element = event.currentTarget as HTMLVideoElement
  if (element !== media) return
  const at = Date.now()
  switch (event.type) {
    case 'loadeddata':
    case 'playing':
      framedNow()
      return
    case 'durationchange':
    case 'loadedmetadata':
      if (Number.isFinite(element.duration) && element.duration > 0) {
        heard = true
        always({ type: 'duration', durationSec: element.duration })
      }
      return
    case 'timeupdate':
      heard = true
      report({ type: 'time', currentTime: element.currentTime, playing: !element.paused, at })
      return
    case 'play':
    case 'pause':
      heard = true
      playerPlaying = event.type === 'play'
      report({ type: 'state', playing: playerPlaying, at })
      if (event.type === 'pause' && soundRefused()) playMuted()
      return
    case 'ended':
      heard = true
      playerPlaying = false
      report({ type: 'ended' })
      return
    case 'volumechange': {
      const on = !element.muted && element.volume > 0
      if (state.soundOn !== on) {
        set({ soundOn: on })
        if (on) startCounting()
      }
      return
    }
    default:
  }
}

/** The media element's `readyState` once the frame at the current position is there (HTML's HAVE_CURRENT_DATA). */
const HAVE_CURRENT_DATA = 2

const MEDIA_EVENTS = ['loadeddata', 'playing', 'durationchange', 'loadedmetadata', 'timeupdate', 'play', 'pause', 'ended',
  'volumechange']

/** F-S15: the stage's `<video>` comes or goes: a new player, nothing heard yet, its events listened to. */
function attachMedia(element: HTMLVideoElement | null) {
  if (element === media) return
  if (media) MEDIA_EVENTS.forEach((type) => media!.removeEventListener(type, mediaEvent))
  media = element
  if (!element) return
  MEDIA_EVENTS.forEach((type) => element.addEventListener(type, mediaEvent))
  heard = false
  playerPlaying = false
  // A file already holding its frame (loaded before the stage listened) says so now.
  if (element.readyState >= HAVE_CURRENT_DATA) framedNow()
  else awaitFrame()
  if (state.soundAsked) soundOn(false)
  startCounting()
}

function youtubeState(playerState: number, at: number) {
  heard = true
  youtubePlayerState = playerState
  // Paused (2) or back to its play button (5, cued) as the sound asked for without a tap came: the browser refused it.
  if ((playerState === 2 || playerState === 5) && soundRefused()) {
    playerPlaying = false
    playMuted()
    return
  }
  // Playing, or paused on a frame: the player has its first frame (buffering or unstarted show none yet).
  if (playerState === 1 || playerState === 2) framedNow()
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
  set({ slot, player: slot.player, returning: null,
    ...(same ? {} : { soundAsked: asked, soundOn: false, framed: false }) })
  // Q75: a slot that counts (the start page's) starts the counting afresh.
  startCounting()
}

function leave(slot: Slot) {
  if (state.slot?.element !== slot.element) return
  // The next page's slot claims it within this tick when it shows the same video; else the player goes. A card's tap
  // waits longer: the start page it opens shows its slot only once its data has come.
  const wait = Math.max(0, handoffUntil - Date.now())
  handoffUntil = 0
  // A framed card's player that is not handed on fades back to its cover, as it came: the cover is in place within
  // the switch, and the player is released when the fade is done. Not one that never showed its frame (a failed file),
  // nor a viewer who asked for less motion: those go at once.
  const back = wait === 0 && !!slot.cover && state.framed && !prefersStillVideo()
  set({ slot: null, returning: back ? { cover: slot.cover } : null })
  if (unknownTimer) {
    clearTimeout(unknownTimer)
    unknownTimer = null
  }
  release = setTimeout(() => {
    release = null
    if (!state.slot) {
      if (frameTimer) {
        clearTimeout(frameTimer)
        frameTimer = null
      }
      set({ player: null, soundAsked: false, soundOn: false, framed: false, returning: null })
      heard = false
      playerPlaying = false
      youtubePlayerState = null
      unaskedSoundUntil = 0
    }
  }, back ? SWITCH_MS : wait)
}

export function useStage(): StageState {
  return useSyncExternalStore(subscribe, () => state, () => state)
}

/** The stage's player now (Q65), and whether its sound is on: the vertical mode carries the sound to the next video. */
export function stagePlayer(): { player: string | null; soundOn: boolean } {
  return { player: state.player, soundOn: state.soundOn || state.soundAsked }
}

/**
 * The place a video shows: an empty 16:9 box the stage lays the player over while `active`. On a card (`mode`
 * `card`) the player cannot be touched: a tap anywhere on it is `onOpen`. On the start page (`page`) the player's
 * controls work.
 */
export function VideoSlot({ id, player, title, mode, publicationId = null, active = true, onOpen, cover, fit, className }: {
  id: string
  player: string
  title: string
  mode: Mode
  /** Q75: the publication whose video this is, for its playback record. */
  publicationId?: string | null
  active?: boolean
  onOpen?: () => void
  /** The cover the player comes in over (a card's): read as the slot is claimed, not a dependency. */
  cover?: ReactNode
  /** F-S10: the player fills the place, cut at its edges (a vertical video in the vertical mode). */
  fit?: 'cover'
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const coverRef = useRef(cover)
  useLayoutEffect(() => {
    coverRef.current = cover
  })
  useLayoutEffect(() => {
    const element = ref.current
    if (!element || !active) return
    const slot: Slot = { key: id, player, title, mode, element, publicationId, onOpen, cover: coverRef.current, fit }
    claim(slot)
    return () => leave(slot)
  }, [id, player, title, mode, publicationId, active, onOpen, fit])
  return (
    <div ref={ref} className={className ?? 'bg-muted relative aspect-video w-full'} data-testid="video-slot"
      data-mode={mode} data-active={active || undefined} data-fit={fit} />
  )
}

/**
 * The area a slot's player is kept within (F-S10: the vertical mode's screens): the element around the slot marked
 * `data-video-area`. Without it the player of a screen moving in or out would show beyond the screens while the scroll
 * travels (the stage lies on the page, not in the screens).
 */
const VIDEO_AREA = 'data-video-area'

/** F-S10: the stage's place in the vertical mode: over its backdrop (z-index 40), under its screens (50). */
export const SHORT_STAGE_Z = 45

interface Clip { top: number; right: number; bottom: number; left: number }

/** The shape of a vertical video (YouTube's Shorts, a phone's recording): 9 across, 16 high. */
export const VERTICAL_ASPECT = 9 / 16

/**
 * F-S10: where a player of the given shape (width over height) lies to fill a place of `width` by `height`: as wide or
 * as high as the place, whichever covers it, centred, the rest cut off by the place (as CSS's `object-fit: cover`). A
 * frame's player letterboxes its video within the frame, so the frame itself is given the video's shape.
 */
export function coverBox(width: number, height: number, aspect: number): { width: number; height: number;
  left: number; top: number } {
  if (!(width > 0) || !(height > 0) || !(aspect > 0)) return { width: Math.max(0, width), height: Math.max(0, height), left: 0, top: 0 }
  const coverWidth = Math.max(width, height * aspect)
  const coverHeight = Math.max(height, width / aspect)
  return { width: coverWidth, height: coverHeight, left: (width - coverWidth) / 2, top: (height - coverHeight) / 2 }
}

interface Placement { top: number; left: number; width: number; height: number; clip: Clip | null }

/** What of the slot's place lies outside its area, from each side; null when the slot has no area. */
function clipOf(element: HTMLElement, box: DOMRect): Clip | null {
  const area = element.closest(`[${VIDEO_AREA}]`)?.getBoundingClientRect()
  if (!area) return null
  return { top: Math.max(0, area.top - box.top), right: Math.max(0, box.right - area.right),
    bottom: Math.max(0, box.bottom - area.bottom), left: Math.max(0, area.left - box.left) }
}

function sameClip(a: Clip | null, b: Clip | null): boolean {
  return a === b || (!!a && !!b && a.top === b.top && a.right === b.right && a.bottom === b.bottom
    && a.left === b.left)
}

/**
 * The stage itself, once in the app's frame: the iframe over the slot's place, followed while the page moves. The
 * frame stays the same element as long as the video does.
 */
export function VideoStage() {
  const stage = useStage()
  const [rect, setRect] = useState<Placement | null>(null)
  const slot = stage.slot
  // F-S16: a video file has no full-screen key of YouTube's or Vimeo's kind: the stage gives it one (`FullscreenButton`).
  const box = useRef<HTMLDivElement>(null)
  const media = useRef<HTMLVideoElement>(null)
  const { active: fullscreen } = useFullscreen(box, media)
  // One function for the life of the stage: a new one at each render would be called again with the element, each time.
  const attach = useCallback((element: HTMLVideoElement | null) => {
    media.current = element
    attachMedia(element)
  }, [])
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
      const next: Placement = { top: box.top + window.scrollY, left: box.left + window.scrollX, width: box.width,
        height: box.height, clip: clipOf(slot.element, box) }
      setRect((current) => (current && current.top === next.top && current.left === next.left
        && current.width === next.width && current.height === next.height && sameClip(current.clip, next.clip)
        ? current : next))
      requestAnimationFrame(place)
    }
    place()
    return () => { running = false }
  }, [slot])
  if (!stage.player || typeof document === 'undefined') return null
  // Nothing of the slot's place within its area: the player is not shown (nor touched).
  const clip = rect?.clip
  const hidden = !!clip && !!rect && rect.height > 0 && (clip.top + clip.bottom >= rect.height
    || clip.left + clip.right >= rect.width)
  // A card that stopped playing keeps the stage over its place while the player fades back to the cover.
  const returning = stage.returning
  const visible = (!!slot || !!returning) && !!rect && !hidden
  // The cover on the stage: the slot's, or the one a returning player fades back to.
  const cover = slot?.cover ?? returning?.cover
  // The cover shows while there is none to show the player, and the player shows once framed (not while it returns).
  const coverShown = !!cover && !(stage.framed && !returning)
  const fade = cover ? crossFade(!coverShown, videoKind(stage.player) !== 'file') : null
  // F-S10: a vertical short fills the place: a file by `object-cover`, a frame given the video's shape over all of it.
  const fill = slot?.fit === 'cover'
  const framePlace = fill && rect ? coverBox(rect.width, rect.height, VERTICAL_ASPECT) : null
  return createPortal(
    <div ref={box} data-testid="video-stage" data-mode={slot?.mode}
      style={{ position: 'absolute', top: rect?.top ?? 0, left: rect?.left ?? 0, width: rect?.width ?? 0,
        height: rect?.height ?? 0, visibility: visible ? 'visible' : 'hidden',
        // Nothing of the stage is for touching once its card has let go of it.
        pointerEvents: slot ? undefined : 'none',
        // The player is kept to the slot's place.
        overflow: 'hidden',
        // In full screen the player fills the screen: nothing of the home's place is cut off it.
        clipPath: clip && !fullscreen ? `inset(${clip.top}px ${clip.right}px ${clip.bottom}px ${clip.left}px)` : undefined,
        // F-S10: in the vertical mode, between its black backdrop (z-index 40) and its screens (50), whose overlays (the
        // way back, the name, the buttons) lie over the video; the screens let a tap through to the player.
        zIndex: slot?.mode === 'short' ? SHORT_STAGE_Z : 5 }} data-fit={slot?.fit}>
      {/* A card's cover and its player cross-fade (`crossFade`): the player fades in over the cover as the cover fades
          out, once the player has its frame, and back when the card stops. Under the stage the card's own cover lies
          still, so what shows through mid-fade is the cover too, never black. Without a cover the player simply shows
          (the start page: its player is not held back until a frame). */}
      {cover && <div className="absolute inset-0" data-testid="video-stage-cover" style={fade?.cover}>{cover}</div>}
      <div className="absolute inset-0" data-testid="video-stage-content" style={fade?.player}>
      {videoKind(stage.player) === 'file' ? (
        // F-S15: a video file, started muted; its controls on the start page and in the vertical mode only.
        <video
          key={stage.player}
          ref={attach}
          src={stage.player}
          title={slot?.title ?? ''}
          aria-label={slot?.title ?? ''}
          // On a card nothing black lies behind the picture (it would darken the cover mid-fade).
          className={cn('size-full', fill ? 'object-cover' : 'object-contain',
            slot?.mode !== 'card' && !returning && 'bg-black')}
          data-testid="video-stage-file"
          autoPlay
          muted
          playsInline
          preload="auto"
          controls={!!slot && slot.mode !== 'card'}
          controlsList="nofullscreen"
          onError={() => markVideoFailed(stage.player!)}
        />
      ) : (
      <iframe
        key={stage.player}
        ref={(element) => {
          frame = element
        }}
        src={stagedPlayer(stage.player)}
        title={slot?.title ?? ''}
        className={framePlace ? 'absolute border-0' : 'size-full border-0'}
        style={framePlace ? { width: framePlace.width, height: framePlace.height, left: framePlace.left,
          top: framePlace.top } : undefined}
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
        allowFullScreen
        referrerPolicy="strict-origin-when-cross-origin"
        sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
        onLoad={() => {
          // Q75: a new player: nothing heard yet; the wait starts where the counting does.
          heard = false
          awaitFrame()
          playerPlaying = false
          greet()
          if (state.soundAsked) soundOn(false)
          startCounting()
        }}
      />
      )}
      {videoKind(stage.player) === 'file' && !!slot && slot.mode !== 'card' && (
        // Above the browser's own control bar, whose full-screen key `controlsList` takes away where it can.
        <FullscreenButton target={box} video={media} className="bottom-[4.25rem]" />
      )}
      </div>
      {/* On a card nothing in the player is for touching: the whole of it opens the start page (not moved, so a tap
          before the first frame opens it too). */}
      {slot?.mode === 'card' && (
        <button type="button" className="absolute inset-0 size-full cursor-pointer" data-testid="video-open"
          aria-label={slot.title} onClick={() => slot.onOpen?.()} />
      )}
    </div>,
    document.body,
  )
}
