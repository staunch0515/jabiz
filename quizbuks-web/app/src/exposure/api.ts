import { runQuery } from '@jabiz/client'
import { PLAY } from '../qb/ids'
import { feedCard, type FeedCard, type FeedRequest } from '../qb/play'
import { maxPerCall, pushEvery, reportBatch, reportIntervalSec, seconds } from './rules'

/** The template of the parameters the App applies (F-S13). */
export const EXPOSURE_SETTINGS = 'qb.app.exposure-settings'

/** The parameters of the exposures and the pushes, bounded as the server bounds them. */
export interface ExposureSettings {
  seconds: number
  pushEvery: number
  reportBatch: number
  reportIntervalSec: number
  /** The most reports one call takes (`qb.exposure.report.max-per-call`). */
  maxPerCall: number
}

const numberOr = (value: unknown): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

export async function readExposureSettings(): Promise<ExposureSettings> {
  const page = await runQuery<Record<string, unknown>>(EXPOSURE_SETTINGS, { limit: 1 })
  const row = page.items[0] ?? {}
  return {
    seconds: seconds(numberOr(row.seconds)),
    pushEvery: pushEvery(numberOr(row.pushEvery)),
    reportBatch: reportBatch(numberOr(row.reportBatch)),
    reportIntervalSec: reportIntervalSec(numberOr(row.reportIntervalSec)),
    maxPerCall: maxPerCall(numberOr(row.maxPerCall)),
  }
}

/** The most pushes read at once (the vertical mode swipes through them as through its short videos). */
export const PUSHES_LIMIT = 50

/**
 * The vertical mode's pushes: the feed's template with `push` (another's vertical shared video, never opened by the
 * caller), under every condition the feed has (the blocks of F-S8, the strict mode of F-S9, the region of F-S11), in
 * turns the server gives them: every publisher's first push, then every publisher's second... (by the publisher's id,
 * which stays on the server: `pushTurn`, `pushRank`). Only cards that are what the server said are kept.
 */
export async function readPushes(request: Omit<FeedRequest, 'shorts' | 'offset' | 'limit'>): Promise<FeedCard[]> {
  const page = await runQuery<Record<string, unknown>>(PLAY.feed, {
    params: { at: request.at, country: request.country, q: request.q?.trim() || null,
      method: request.method ?? null, includeAnswered: request.includeAnswered ?? false,
      languages: request.languages && request.languages.length > 0 ? request.languages.join(',') : null,
      mainlandChina: request.mainlandChina ?? false, push: true },
    filters: request.filters,
    sorts: [{ field: 'pushTurn', asc: true }, { field: 'pushRank', asc: true }],
    offset: 0,
    limit: PUSHES_LIMIT,
  })
  return page.items.map(feedCard).filter((card) => card.exposed === true && card.videoVertical === true && !!card.video)
}
