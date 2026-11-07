import type { FeedCard } from '../qb/play'

/*
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): where the home puts its shelves of vertical short videos, like YouTube's
 * Shorts. Pure, so that the places are tested apart from the page; the page (and later the new frame of Q108) only
 * renders what this says.
 */

/** The setup's values of `qb.feed.shorts.positions` and `qb.feed.shorts.per-shelf` (the backend's `QbParams`). */
export const SHORTS_DEFAULT_POSITIONS: readonly number[] = [3, 12]
export const SHORTS_DEFAULT_PER_SHELF = 10
/** The most short videos read at once: the shelves' and the full-screen mode's (which swipes through all of them). */
export const SHORTS_LIMIT = 50
/** The most shelves one home shows, whatever the parameter lists. */
const MAX_SHELVES = 10

/**
 * The places of the parameter (after which card, the first card being 1), in order and once each; the setup's when
 * there is no value yet; none for an empty value. Anything that is not a whole number above 0 is left out.
 */
export function shelfPositions(value: string | null | undefined): number[] {
  if (value == null) return [...SHORTS_DEFAULT_POSITIONS]
  const places = value.split(',').map((part) => part.trim()).filter((part) => /^\d{1,4}$/.test(part)).map(Number)
    .filter((place) => place > 0)
  return [...new Set(places)].sort((a, b) => a - b).slice(0, MAX_SHELVES)
}

/** How many short videos one shelf shows (the parameter): the setup's when none or not a number, 1 to the limit. */
export function shelfSize(value: string | number | null | undefined): number {
  const n = Number(value)
  if (value == null || value === '' || !Number.isFinite(n)) return SHORTS_DEFAULT_PER_SHELF
  return Math.min(SHORTS_LIMIT, Math.max(1, Math.floor(n)))
}

/** One shelf: after which card of the feed it stands, and its short videos (`from`: the first one's place in all). */
export interface Shelf {
  after: number
  from: number
  items: FeedCard[]
}

/**
 * The shelves among `cardCount` cards of the feed: the n-th place shows the n-th group of `perShelf` short videos (as
 * YouTube's home shows other Shorts further down); a place without short videos left shows no shelf. A place beyond
 * the cards waits for more of them, unless the feed has no more (`complete`): then the first such shelf closes the
 * list, the rest are not shown. No shelf at all without short videos.
 */
export function placeShelves(cardCount: number, complete: boolean, positions: readonly number[], perShelf: number,
  shorts: readonly FeedCard[]): Shelf[] {
  const shelves: Shelf[] = []
  let atEnd = false
  positions.forEach((position, i) => {
    const from = i * perShelf
    const items = shorts.slice(from, from + perShelf)
    if (items.length === 0) return
    if (position <= cardCount) {
      shelves.push({ after: position, from, items })
    } else if (complete && !atEnd && cardCount > 0) {
      atEnd = true
      shelves.push({ after: cardCount, from, items })
    }
  })
  return shelves
}
