import { countPublic } from '@jabiz/client'
import type { FeedCard } from '../qb/play'

/**
 * The public counter of the openings of the vertical mode (the backend's `ShortsStats.SHORTS_OPENS`): since the home's
 * shelf of short videos went (2026-11-28), a vertical video's post opening the video mode.
 */
export const SHORTS_OPENS = 'qb.shorts-open'

/**
 * Counts an opening of the vertical mode on a card, by its publication; nothing shown or awaited (once a visitor a
 * day).
 */
export function countShortsOpen(card: FeedCard) {
  countPublic(SHORTS_OPENS, card.publicationId)
}
