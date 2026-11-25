/**
 * How long ago a post went up, as x.com's timeline says it (Q113 §9.1 "条目": "名字 · 时间"; P8: no exact times for
 * takers): minutes, hours or days while under a week, then the date. Pure: the caller gives its now (the home's `at`).
 */
export type PostAge =
  | { unit: 'minutes' | 'hours' | 'days'; count: number }
  | { date: Date; withYear: boolean }

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/** The age of a post started at `startAt` seen at `now`; null when either is not a time. */
export function postAge(startAt: string, now: string | Date): PostAge | null {
  const start = new Date(startAt)
  const at = now instanceof Date ? now : new Date(now)
  if (Number.isNaN(start.getTime()) || Number.isNaN(at.getTime())) return null
  // A post from a moment ahead (a clock a little behind the server's) is a post of now.
  const elapsed = Math.max(0, at.getTime() - start.getTime())
  if (elapsed < HOUR) return { unit: 'minutes', count: Math.max(1, Math.floor(elapsed / MINUTE)) }
  if (elapsed < DAY) return { unit: 'hours', count: Math.floor(elapsed / HOUR) }
  if (elapsed < 7 * DAY) return { unit: 'days', count: Math.floor(elapsed / DAY) }
  return { date: start, withYear: start.getFullYear() !== at.getFullYear() }
}
