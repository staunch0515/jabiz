import { Maximize2, Minimize2 } from 'lucide-react'
import { useEffect, useState, type RefObject } from 'react'
import { useTranslation } from 'react-i18next'
import { QBVIEW } from './messages'

/** The Fullscreen API with the prefixes of Safari's older versions (and of iPhone's `<video>`). */
type FullscreenDocument = Document & {
  webkitFullscreenElement?: Element | null
  webkitFullscreenEnabled?: boolean
  webkitExitFullscreen?: () => Promise<void> | void
}
type FullscreenElement = HTMLElement & { webkitRequestFullscreen?: () => Promise<void> | void }
type FullscreenVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void }

const CHANGE_EVENTS = ['fullscreenchange', 'webkitfullscreenchange'] as const

function fullscreenElement(): Element | null {
  const doc = document as FullscreenDocument
  return document.fullscreenElement ?? doc.webkitFullscreenElement ?? null
}

/** Whether the browser makes an element full screen (an iPhone does not: only its `<video>` goes). */
function elementApi(): boolean {
  const doc = document as FullscreenDocument
  return !!(document.fullscreenEnabled || doc.webkitFullscreenEnabled)
}

/** Whether an iPhone's own `<video>` can go full screen (where no element can). */
function videoApi(): boolean {
  return typeof HTMLVideoElement !== 'undefined' && 'webkitEnterFullscreen' in HTMLVideoElement.prototype
}

/**
 * Full screen for a player's container (F-S16): `requestFullscreen` on it, the way back by the same key or Esc. On an
 * iPhone, where no element goes full screen, a video file's own `<video>` does (`webkitEnterFullscreen`, `video`
 * given); a framed player there has no full screen of ours, so `supported` is false and no key is shown.
 */
export function useFullscreen(target: RefObject<HTMLElement | null>, video?: RefObject<HTMLVideoElement | null>) {
  const [active, setActive] = useState(false)
  useEffect(() => {
    const sync = () => setActive(!!target.current && fullscreenElement() === target.current)
    CHANGE_EVENTS.forEach((name) => document.addEventListener(name, sync))
    return () => CHANGE_EVENTS.forEach((name) => document.removeEventListener(name, sync))
  }, [target])
  const supported = elementApi() || (!!video && videoApi())
  const toggle = () => {
    const element = target.current as FullscreenElement | null
    if (!element) return
    const doc = document as FullscreenDocument
    const run = (): Promise<void> | void => {
      if (fullscreenElement() === element) {
        return document.exitFullscreen ? document.exitFullscreen() : doc.webkitExitFullscreen?.()
      }
      if (elementApi()) {
        return element.requestFullscreen ? element.requestFullscreen() : element.webkitRequestFullscreen?.()
      }
      ;(video?.current as FullscreenVideo | null | undefined)?.webkitEnterFullscreen?.()
    }
    // A refusal (the page is not allowed to, the player went away) leaves the player as it was.
    void Promise.resolve(run()).catch(() => undefined)
  }
  return { active, supported, toggle }
}

/**
 * The full-screen key of a player (F-S16), at its bottom right like YouTube's: for the players with no key of their
 * own (a video file's page, another site's page, the sites other than YouTube and Vimeo). It is inside the element that
 * goes full screen, so it stays in full screen and then reads "Exit full screen". Nothing where the browser cannot.
 */
export function FullscreenButton({ target, video, className }: {
  target: RefObject<HTMLElement | null>
  video?: RefObject<HTMLVideoElement | null>
  className?: string
}) {
  const { t } = useTranslation(QBVIEW)
  const { active, supported, toggle } = useFullscreen(target, video)
  if (!supported) return null
  const label = active ? t('player.exitFullscreen') : t('player.fullscreen')
  const Icon = active ? Minimize2 : Maximize2
  return (
    <button
      type="button"
      data-testid="video-fullscreen"
      data-active={active || undefined}
      aria-label={label}
      title={label}
      onClick={(event) => {
        // On a card the whole of it may open something: the key only toggles the full screen.
        event.stopPropagation()
        toggle()
      }}
      className={[
        'absolute right-2 bottom-2 z-10 flex size-10 items-center justify-center rounded-full bg-black/55 text-white',
        'backdrop-blur-sm transition-colors hover:bg-black/75 focus-visible:outline-2 focus-visible:outline-offset-2',
        'focus-visible:outline-white',
        className,
      ].filter(Boolean).join(' ')}
    >
      <Icon aria-hidden className="size-5" />
    </button>
  )
}
