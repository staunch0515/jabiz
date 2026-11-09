import type { FeedCard } from '../qb/play'
import { pushAt } from './rules'

/*
 * F-S13: where the vertical mode's pushes stand. Pure, so that the places are tested apart from the page; the page
 * (and later the new frame of Q108) only renders the list this makes.
 */

/** Whose a card is, for the turns: its Sponsor's name (a card carries no Sponsor's id), else the card itself. */
const sponsorOf = (card: FeedCard) => card.sponsorCompany ?? card.sponsorName ?? card.publicationId

/** The pushes taking turns among their Sponsors (each Sponsor's in the feed's order), the first Sponsor first. */
export function rotate(pushes: readonly FeedCard[]): FeedCard[] {
  const bySponsor = new Map<string, FeedCard[]>()
  for (const card of pushes) {
    const key = sponsorOf(card)
    const list = bySponsor.get(key)
    if (list) list.push(card)
    else bySponsor.set(key, [card])
  }
  const lists = [...bySponsor.values()]
  const turns: FeedCard[] = []
  for (let round = 0; turns.length < pushes.length; round++) {
    for (const list of lists) {
      if (round < list.length) turns.push(list[round])
    }
  }
  return turns
}

/**
 * The vertical mode's screens, opened on `startId`: the short videos in their order, the publications using another's
 * video (`exposed`) only as pushes, every `every`-th screen after the one opened on (the rules' `pushAt`), taking turns
 * among their Sponsors. Pushes left when the short videos run out follow one after another; short videos left when
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
