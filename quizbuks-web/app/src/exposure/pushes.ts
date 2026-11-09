import type { FeedCard } from '../qb/play'
import { pushAt } from './rules'

/*
 * F-S13: where the vertical mode's pushes stand. Pure, so that the places are tested apart from the page; the page
 * (and later the new frame of Q108) only renders the list this makes.
 */

/**
 * The pushes in their turns: every publisher's first, then every publisher's second... as the server numbers them
 * (`pushTurn`, `pushRank`: by the publisher's id, which never reaches the App). A card without them keeps its place.
 */
export function rotate(pushes: readonly FeedCard[]): FeedCard[] {
  const key = (card: FeedCard) => [card.pushTurn ?? Number.MAX_SAFE_INTEGER, card.pushRank ?? Number.MAX_SAFE_INTEGER]
  return pushes.map((card, i) => ({ card, i })).sort((a, b) => {
    const [ta, ra] = key(a.card)
    const [tb, rb] = key(b.card)
    return ta - tb || ra - rb || a.i - b.i
  }).map(({ card }) => card)
}

/**
 * The vertical mode's screens, opened on `startId`: the short videos in their order, the publications using another's
 * video (`exposed`) only as pushes, every `every`-th screen after the one opened on (the rules' `pushAt`), taking turns
 * among their publishers (`rotate`). Pushes left when the short videos run out follow one after another; short videos left when
 * the pushes run out fill the places. The one opened on stays first wherever it came from (a push tapped on the home's
 * shelf is opened, and counted, as any). Opened earlier and no longer a push: not shown again.
 */
export function placePushes(shorts: readonly FeedCard[], pushes: readonly FeedCard[], startId: string,
  every: number): FeedCard[] {
  const start = shorts.find((card) => card.publicationId === startId)
    ?? pushes.find((card) => card.publicationId === startId)
  if (!start) return [...shorts]
  const regular = shorts.filter((card) => card.exposed !== true || card.publicationId === startId)
  const at = regular.findIndex((card) => card.publicationId === startId)
  const before = at >= 0 ? regular.slice(0, at) : []
  const after = at >= 0 ? regular.slice(at + 1) : regular
  const queue = rotate(pushes.filter((card) => card.publicationId !== startId))
  const screens = [...before, start]
  let offset = 1
  let a = 0
  let p = 0
  while (a < after.length || p < queue.length) {
    if (p < queue.length && (pushAt(offset, every) || a >= after.length)) screens.push(queue[p++])
    else screens.push(after[a++])
    offset++
  }
  return screens
}
