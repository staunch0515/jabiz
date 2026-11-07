import { countPublic, runQuery } from '@jabiz/client'
import { PLAY } from '../qb/ids'
import { FEED_SORTS, feedCard, type FeedCard, type FeedRequest } from '../qb/play'
import { shelfPositions, shelfSize, SHORTS_LIMIT } from './shelves'

/** The home's short videos (F-S10) and where its shelves stand. */
export interface Shorts {
  cards: FeedCard[]
  positions: number[]
  perShelf: number
}

/**
 * The home's vertical short videos: the feed's template with `shorts` (C of the 2026-11-06 review: one parameter, not
 * another template), under every condition the feed has (the region of F-S11, the audience, the languages, the search
 * and filters, and the blocking of F-S8 once it is there), in the feed's order. Its rows carry the parameters of the
 * shelves (the setup's values when none yet). Only vertical cards are kept, whatever the server sent.
 */
export async function readShorts(request: Omit<FeedRequest, 'shorts' | 'offset' | 'limit'>): Promise<Shorts> {
  const page = await runQuery<Record<string, unknown>>(PLAY.feed, {
    params: { at: request.at, country: request.country, q: request.q?.trim() || null,
      method: request.method ?? null, includeAnswered: request.includeAnswered ?? false,
      languages: request.languages && request.languages.length > 0 ? request.languages.join(',') : null,
      mainlandChina: request.mainlandChina ?? false, shorts: true },
    filters: request.filters,
    sorts: request.sort ? [FEED_SORTS[request.sort]] : undefined,
    offset: 0,
    limit: SHORTS_LIMIT,
  })
  const first = page.items[0]
  return {
    cards: page.items.map(feedCard).filter((card) => card.videoVertical === true && !!card.video),
    positions: shelfPositions(first ? (first.shortsPositions as string | null | undefined) : null),
    perShelf: shelfSize(first ? (first.shortsPerShelf as string | null | undefined) : null),
  }
}

/** The public counter of the openings of the vertical mode (the backend's `ShortsStats.SHORTS_OPENS`). */
export const SHORTS_OPENS = 'qb.shorts-open'

/**
 * Counts an opening of the vertical mode on a card, by its version (the backend's `ShortsStats` says why); nothing
 * shown or awaited (once a visitor a day).
 */
export function countShortsOpen(card: FeedCard) {
  if (card.versionId) countPublic(SHORTS_OPENS, card.versionId)
}
