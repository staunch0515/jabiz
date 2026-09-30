import { useEffect } from 'react'

const ACTIVITY = ['keydown', 'pointerdown', 'wheel', 'touchstart', 'scroll'] as const

/**
 * Calls {@code onIdle} once the user has done nothing for {@code seconds} (docs/design/10-security.md section 11).
 * The server refuses to refresh an idle session anyway; this locks the page itself, so that nothing stays on screen.
 *
 * The server counts activity by refreshes, which the client makes only when a call finds its access token expired;
 * a user typing into a long form makes none. {@code onActivity} is therefore called on activity, at most once per
 * third of the idle time, to keep the session alive on the server too.
 */
export function useIdleLock(seconds: number | null, onIdle: () => void, onActivity?: () => void) {
  useEffect(() => {
    if (!seconds || seconds <= 0) return undefined
    let timer = window.setTimeout(onIdle, seconds * 1000)
    let lastKeptAlive = Date.now()
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(onIdle, seconds * 1000)
      if (onActivity && Date.now() - lastKeptAlive >= (seconds * 1000) / 3) {
        lastKeptAlive = Date.now()
        onActivity()
      }
    }
    ACTIVITY.forEach((event) => window.addEventListener(event, reset, { passive: true, capture: true }))
    return () => {
      window.clearTimeout(timer)
      ACTIVITY.forEach((event) => window.removeEventListener(event, reset, { capture: true }))
    }
  }, [seconds, onIdle, onActivity])
}
