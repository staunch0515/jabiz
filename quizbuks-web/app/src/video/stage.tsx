import { Button } from '@jabiz/ui'
import { Volume2 } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { VIMEO_PLAYER } from '../qb/video'

/*
 * The one video player of the app (Q65, plan A): an iframe that never moves in the page, laid over the place
 * (`VideoSlot`) that shows it now: the home's card in the middle of the screen, then the start page it opens. A browser
 * plays sound only after a tap, and a new player on the next page would not have had one; moving the iframe in the
 * page would reload it. So the player stays, the slot changes, and the tap that opens the start page turns the sound on
 * through the player's own interface (YouTube's and Vimeo's postMessage commands). Where the browser still refuses it
 * (an iPhone, a page opened first), the start page offers a button for the sound.
 */

type Mode = 'card' | 'page'

interface Slot {
  key: string
  player: string
  title: string
  mode: Mode
  element: HTMLElement
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
  const info = data?.info as Record<string, unknown> | undefined
  if (data?.event === 'infoDelivery' && info && typeof info.muted === 'boolean') {
    if (state.soundOn !== !info.muted) set({ soundOn: !info.muted })
  }
  const vimeo = data?.data as Record<string, unknown> | undefined
  if (data?.event === 'volumechange' && vimeo && typeof vimeo.volume === 'number') {
    set({ soundOn: vimeo.volume > 0 })
  }
}

/** YouTube reports only to a page that says it listens; Vimeo to one that adds the event. */
function greet() {
  if (!state.player) return
  if (state.player.startsWith(VIMEO_PLAYER)) post({ method: 'addEventListener', value: 'volumechange' })
  else post({ event: 'listening', id: 'qb-stage', channel: 'widget' })
}

function claim(slot: Slot) {
  if (release) {
    clearTimeout(release)
    release = null
  }
  handoffUntil = 0
  const same = state.player === slot.player
  set({ slot, player: slot.player, ...(same ? {} : { soundAsked: false, soundOn: false }) })
}

function leave(slot: Slot) {
  if (state.slot?.element !== slot.element) return
  set({ slot: null })
  // The next page's slot claims it within this tick when it shows the same video; else the player goes. A card's tap
  // waits longer: the start page it opens shows its slot only once its data has come.
  const wait = Math.max(0, handoffUntil - Date.now())
  handoffUntil = 0
  release = setTimeout(() => {
    release = null
    if (!state.slot) set({ player: null, soundAsked: false, soundOn: false })
  }, wait)
}

export function useStage(): StageState {
  return useSyncExternalStore(subscribe, () => state, () => state)
}

/**
 * The place a video shows: an empty 16:9 box the stage lays the player over while `active`. On a card (`mode`
 * `card`) the player cannot be touched: a tap anywhere on it is `onOpen`. On the start page (`page`) the player's
 * controls work, and while the sound is not on a button asks for it.
 */
export function VideoSlot({ id, player, title, mode, active = true, onOpen, className }: {
  id: string
  player: string
  title: string
  mode: Mode
  active?: boolean
  onOpen?: () => void
  className?: string
}) {
  const ref = useRef<HTMLDivElement>(null)
  const { t } = useTranslation(APP)
  const stage = useStage()
  useLayoutEffect(() => {
    const element = ref.current
    if (!element || !active) return
    const slot: Slot = { key: id, player, title, mode, element, onOpen }
    claim(slot)
    return () => leave(slot)
  }, [id, player, title, mode, active, onOpen])
  const shown = active && stage.slot?.key === id && stage.player === player
  return (
    <div ref={ref} className={className ?? 'bg-muted relative aspect-video w-full'} data-testid="video-slot"
      data-mode={mode} data-active={active || undefined}>
      {shown && mode === 'page' && !stage.soundOn && (
        <Button size="sm" className="absolute right-2 bottom-2 z-10 shadow-md" data-testid="video-sound"
          onClick={() => soundOn()}>
          <Volume2 aria-hidden />
          {t('video.sound')}
        </Button>
      )}
    </div>
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
          greet()
          if (state.soundAsked) soundOn()
        }}
      />
      {/* On a card nothing in the player is for touching: the whole of it opens the start page. */}
      {slot?.mode === 'card' && (
        <button type="button" className="absolute inset-0 size-full cursor-pointer" data-testid="video-open"
          aria-label={slot.title} onClick={() => {
            handoffUntil = Date.now() + HANDOFF_MS
            slot.onOpen?.()
          }} />
      )}
    </div>,
    document.body,
  )
}
