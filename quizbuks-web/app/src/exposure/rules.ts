/*
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): the rules of counting exposures and of the pushes, as the server's
 * `exposure.ExposureRules` has them; both pass the cases of `exposure-cases.json` (`exposure.test.ts`,
 * `ExposureRulesTest`). The server decides what counts; the App applies the same rules to know when to report (the
 * seconds, the surface) and where the pushes stand.
 */

export const KINDS = ['OPEN', 'EXPOSURE', 'GO'] as const
export type ReportKind = (typeof KINDS)[number]
/** The vertical mode (vertical videos count there) and the start page (horizontal ones). */
export type Surface = 'VERTICAL' | 'START'
/** The player's own clock (YouTube, Vimeo, video files) or the time on the screen with the page in front. */
export type Timer = 'PLAYER' | 'SCREEN'
export type Outcome = 'COUNTED' | 'RECORDED' | 'ALREADY' | 'OWN_VIDEO' | 'EXCLUDED' | 'NOT_LIVE' | 'WRONG_SURFACE'
  | 'TOO_SHORT' | 'DAILY_CAP'

/** The parameters' declared values (the backend's `QbParams`), used before the setup made them. */
export const DEFAULT_SECONDS = 10
export const DEFAULT_DAILY_CAP = 200
export const DEFAULT_PUSH_EVERY = 5
export const DEFAULT_REPORT_BATCH = 20
export const DEFAULT_REPORT_INTERVAL_SEC = 30
/** The most reports one call takes, whatever `qb.exposure.report.max-per-call` says. */
export const MAX_BATCH = 200
export const DEFAULT_MAX_PER_CALL = 50
/** The reconciliation's tolerance and the grace after an end, as the setup declares them. */
export const DEFAULT_MIN_TOLERANCE = 20
export const DEFAULT_TOLERANCE_PERCENT = 5
export const DEFAULT_END_GRACE_SEC = 60

/** What of a publication's card the rules read. */
export interface RulesCard {
  publisherId: string | null
  providerId: string | null
  sharedVideo: boolean
  vertical: boolean
  live: boolean
  durationSec: number | null
}

const clamp = (value: number, low: number, high: number) => Math.min(high, Math.max(low, value))
const read = (value: number | null | undefined, fallback: number) =>
  value == null || !Number.isFinite(value) ? fallback : Math.trunc(value)

/** Whether the card's video is another's: a shared video whose provider is not the publisher. */
export function othersVideo(card: RulesCard): boolean {
  return card.sharedVideo && card.providerId != null && card.providerId !== card.publisherId
}

/** Whether the viewer's watching never counts: the publisher, the provider, or the staff of either. */
export function excluded(viewerId: string | null, staffOf: readonly string[], card: RulesCard): boolean {
  if (viewerId == null) return true
  if (viewerId === card.publisherId || viewerId === card.providerId) return true
  return staffOf.some((sponsor) => sponsor === card.publisherId || sponsor === card.providerId)
}

/** Whether a surface counts the card's video: the vertical mode a vertical one, the start page the others. */
export function fits(surface: string, vertical: boolean): boolean {
  return surface === 'VERTICAL' ? vertical : surface === 'START' && !vertical
}

/** The seconds to watch: the parameter, or the whole video when it is shorter. */
export function neededSec(seconds: number, durationSec: number | null | undefined): number {
  return durationSec != null && durationSec > 0 ? Math.min(seconds, durationSec) : seconds
}

/** What becomes of a report of an exposure (the server's `decide`). */
export function decide(card: RulesCard, viewerId: string | null, staffOf: readonly string[], surface: string,
  reportedSec: number, seconds: number, alreadyCounted: boolean, countedToday: number, cap: number): Outcome {
  if (!othersVideo(card)) return 'OWN_VIDEO'
  if (excluded(viewerId, staffOf, card)) return 'EXCLUDED'
  if (alreadyCounted) return 'ALREADY'
  if (!card.live) return 'NOT_LIVE'
  if (!fits(surface, card.vertical)) return 'WRONG_SURFACE'
  if (reportedSec < neededSec(seconds, card.durationSec)) return 'TOO_SHORT'
  if (countedToday >= cap) return 'DAILY_CAP'
  return 'COUNTED'
}

/** Whether the card may be pushed to a taker: another's vertical video, taking attempts, not opened by them. */
export function pushable(card: RulesCard, opened: boolean): boolean {
  return othersVideo(card) && card.vertical && card.live && !opened
}

/** Whether the screen `offset` screens after the one the vertical mode opened on is a push's place. */
export function pushAt(offset: number, every: number): boolean {
  return offset > 0 && offset % every === 0
}

/** Whether the purchased exposures are used up; none purchased never is. */
export function usedUp(successes: number, purchased: number | null): boolean {
  return purchased != null && successes >= purchased
}

/** The successes charged: all of them without a purchase, else no more than purchased. */
export function billable(successes: number, purchased: number | null): number {
  return purchased == null ? successes : Math.min(successes, purchased)
}

/** The successes beyond the purchased: recorded, not charged. */
export function overshoot(successes: number, purchased: number | null): number {
  return successes - billable(successes, purchased)
}

/** How far beyond the purchased the daily reconciliation lets the successes go (its two parameters). */
export function tolerance(purchased: number | null, minTolerance: number, percent: number): number {
  return purchased == null ? 0 : Math.max(minTolerance, Math.ceil((purchased * percent) / 100))
}

export function reconciles(successes: number, purchased: number | null, minTolerance: number,
  percent: number): boolean {
  return purchased == null || successes <= purchased + tolerance(purchased, minTolerance, percent)
}

export const seconds = (value: number | null | undefined) => clamp(read(value, DEFAULT_SECONDS), 1, 600)
export const dailyCap = (value: number | null | undefined) => clamp(read(value, DEFAULT_DAILY_CAP), 1, 10_000)
export const pushEvery = (value: number | null | undefined) => clamp(read(value, DEFAULT_PUSH_EVERY), 1, 50)
export const reportBatch = (value: number | null | undefined) => clamp(read(value, DEFAULT_REPORT_BATCH), 1, MAX_BATCH)
export const reportIntervalSec = (value: number | null | undefined) =>
  clamp(read(value, DEFAULT_REPORT_INTERVAL_SEC), 5, 600)
export const maxPerCall = (value: number | null | undefined) => clamp(read(value, DEFAULT_MAX_PER_CALL), 1, MAX_BATCH)
export const minTolerance = (value: number | null | undefined) => Math.max(0, read(value, DEFAULT_MIN_TOLERANCE))
export const tolerancePercent = (value: number | null | undefined) =>
  clamp(read(value, DEFAULT_TOLERANCE_PERCENT), 0, 100)
export const endGraceSec = (value: number | null | undefined) => clamp(read(value, DEFAULT_END_GRACE_SEC), 0, 86_400)
