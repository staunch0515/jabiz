import { useEffect, useRef, useState } from 'react'

/*
 * The attempt's countdown (U-31; docs/quizbuks/plans/QP-pages.md section 10): counted by the server's clock only. The
 * device's clock may be wrong by minutes, so the time left is the deadline minus the server's now, which is the
 * device's now plus the offset measured when the server last answered (`serverTime`). The server keeps a few
 * seconds' grace and finishes attempts whose time ran out itself (qb.attempt-expire).
 */

/** How far the server's clock is ahead of the device's (milliseconds; negative when behind). */
export function clockOffset(serverTime: string, receivedAt: number = Date.now()): number {
  const server = Date.parse(serverTime)
  return Number.isFinite(server) ? server - receivedAt : 0
}

/** The time left until the deadline by the server's clock, never below 0. */
export function remainingMs(deadline: string, offset: number, now: number = Date.now()): number {
  const end = Date.parse(deadline)
  if (!Number.isFinite(end)) return 0
  return Math.max(0, end - (now + offset))
}

/** `m:ss` (or `h:mm:ss`), rounding up: "0:00" only once the time is over. */
export function formatRemaining(ms: number): string {
  const total = Math.ceil(Math.max(0, ms) / 1000)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = String(total % 60).padStart(2, '0')
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${seconds}` : `${minutes}:${seconds}`
}

/** The moments announced to screen readers (a polite live region), not every second. */
export const ANNOUNCE_AT_MS = [60_000, 10_000] as const

/** The last threshold the time left has reached (the one to announce), or null above them all. */
export function threshold(ms: number): number | null {
  let reached: number | null = null
  for (const at of ANNOUNCE_AT_MS) if (ms <= at) reached = at
  return reached
}

/**
 * The time left, updated a few times a second; `onExpired` runs once when it reaches 0 (also when it already has).
 */
export function useCountdown(deadline: string, offset: number, onExpired: () => void): number {
  const [left, setLeft] = useState(() => remainingMs(deadline, offset))
  const expired = useRef(onExpired)
  useEffect(() => {
    expired.current = onExpired
  })
  useEffect(() => {
    let done = false
    const tick = () => {
      const ms = remainingMs(deadline, offset)
      setLeft(ms)
      if (ms === 0 && !done) {
        done = true
        expired.current()
      }
    }
    tick()
    const timer = window.setInterval(tick, 250)
    return () => window.clearInterval(timer)
  }, [deadline, offset])
  return left
}
