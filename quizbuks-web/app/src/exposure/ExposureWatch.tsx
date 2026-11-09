import { useQuery } from '@tanstack/react-query'
import { useEffect, useState, type ReactNode } from 'react'
import { trackable } from '../qb/video'
import { onPlaybackEvent } from '../video/playback'
import { readExposureSettings, type ExposureSettings } from './api'
import { configureReporter, queueReport, reported } from './reporter'
import { DEFAULT_SECONDS, neededSec, type Surface, type Timer } from './rules'
import { NO_RUN, playerRun, screenRun, type Run } from './timer'

/** The query key of the parameters (read once a session, more or less). */
export const EXPOSURE_SETTINGS_QUERY = ['qb', 'exposure-settings'] as const

/** The parameters of the exposures (`qb.app.exposure-settings`), handed to the reporter as they come. */
export function useExposureSettings(enabled = true): ExposureSettings | undefined {
  const settings = useQuery({ queryKey: EXPOSURE_SETTINGS_QUERY, queryFn: readExposureSettings,
    staleTime: 10 * 60_000, enabled, retry: false })
  const data = settings.data
  useEffect(() => {
    if (data) configureReporter(data)
  }, [data])
  return data
}

/** How often the screen's clock ticks. */
const TICK_MS = 1000

/**
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): times the watching of one publication's video on a surface while
 * `active`, and reports one exposure once a run without a break reaches the seconds (the rules' `neededSec`): by the
 * player's clock where the player says where it is (YouTube, Vimeo, video files), else while on the screen (`target`
 * at least half in view; no target: the vertical mode's screen shown) with the page in front. Renders nothing.
 */
export function ExposureWatch({ publicationId, surface, video, durationSec, active, target = null }: {
  publicationId: string
  surface: Surface
  video: string | null | undefined
  durationSec: number | null | undefined
  active: boolean
  target?: Element | null
}) {
  const settings = useExposureSettings(active)
  const needed = neededSec(settings?.seconds ?? DEFAULT_SECONDS, durationSec)
  const timer: Timer = trackable(video ?? null) ? 'PLAYER' : 'SCREEN'
  useEffect(() => {
    if (!active || reported(publicationId, 'EXPOSURE')) return
    let run: Run = NO_RUN
    let done = false
    const front = () => typeof document === 'undefined' || document.visibilityState !== 'hidden'
    const check = () => {
      if (done || run.sec < needed) return
      done = true
      queueReport({ publicationId, kind: 'EXPOSURE', surface, seconds: Math.floor(run.sec), timer })
    }
    const background = () => {
      if (!front()) run = NO_RUN
    }
    document.addEventListener('visibilitychange', background)
    if (timer === 'PLAYER') {
      const stop = onPlaybackEvent((id, event) => {
        if (id !== publicationId) return
        run = front() ? playerRun(run, event) : NO_RUN
        check()
      })
      return () => {
        stop()
        document.removeEventListener('visibilitychange', background)
      }
    }
    let inView = target == null || typeof IntersectionObserver === 'undefined'
    const observer = target != null && typeof IntersectionObserver !== 'undefined'
      ? new IntersectionObserver((entries) => {
        inView = entries.some((entry) => entry.isIntersecting && entry.intersectionRatio >= 0.5)
      }, { threshold: [0, 0.5, 1] }) : null
    if (observer && target) observer.observe(target)
    const tick = setInterval(() => {
      run = screenRun(run, Date.now(), inView && front())
      check()
    }, TICK_MS)
    return () => {
      clearInterval(tick)
      observer?.disconnect()
      document.removeEventListener('visibilitychange', background)
    }
  }, [active, publicationId, surface, needed, timer, target])
  return null
}

/**
 * F-S13: the start page's video of a horizontal publication using another's video, timed on the screen (the wrapper is
 * what is seen). Anything else is the video as it was.
 */
export function StartPageExposure({ publicationId, video, durationSec, active, children }: {
  publicationId: string
  video: string | null | undefined
  durationSec: number | null | undefined
  active: boolean
  children: ReactNode
}) {
  const [element, setElement] = useState<HTMLDivElement | null>(null)
  if (!active) return <>{children}</>
  return (
    <div ref={setElement} data-testid="exposure-start">
      {children}
      <ExposureWatch publicationId={publicationId} surface="START" video={video} durationSec={durationSec} active
        target={element} />
    </div>
  )
}
