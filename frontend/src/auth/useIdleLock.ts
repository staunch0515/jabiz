import { useEffect } from 'react'

const ACTIVITY = ['keydown', 'pointerdown', 'wheel', 'touchstart', 'scroll'] as const

/**
 * Calls {@code onIdle} once the user has done nothing for {@code seconds} (docs/design/10-security.md section 11).
 * The server refuses to refresh an idle session anyway; this locks the page itself, so that nothing stays on screen.
 */
export function useIdleLock(seconds: number | null, onIdle: () => void) {
  useEffect(() => {
    if (!seconds || seconds <= 0) return undefined
    let timer = window.setTimeout(onIdle, seconds * 1000)
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(onIdle, seconds * 1000)
    }
    ACTIVITY.forEach((event) => window.addEventListener(event, reset, { passive: true, capture: true }))
    return () => {
      window.clearTimeout(timer)
      ACTIVITY.forEach((event) => window.removeEventListener(event, reset, { capture: true }))
    }
  }, [seconds, onIdle])
}
