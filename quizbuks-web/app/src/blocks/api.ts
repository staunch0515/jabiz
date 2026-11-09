import { runProcess, runQuery } from '@jabiz/client'
import type { QueryClient } from '@tanstack/react-query'
import type { BlockKind, FeedCard } from '../qb/play'

/**
 * Blocking (F-S8, `block.BlockProcesses`, template `qb.app.blocks`; docs/quizbuks/plans/impl/F-S8.md): the taker
 * blocks a publication ("not interested"), its quiz, its sponsor or its shared video's provider, always from the card;
 * the server takes the object from the card and hides it everywhere (the home, the shorts, the search, the page, the
 * inbox's notices) and refuses its start.
 */
export const BLOCK = {
  add: 'QB_BLOCK_ADD',
  remove: 'QB_BLOCK_REMOVE',
  list: 'qb.app.blocks',
} as const

/** The list's query key; the queries a block changes are listed in {@link refreshAfterBlocking}. */
export const BLOCKS_QUERY = ['qb', 'blocks'] as const

/** The most rows the list reads: the parameter `qb.block.max-per-user`'s ceiling is higher, its default this. */
export const BLOCKS_LIMIT = 1000

/** What `QB_BLOCK_ADD` / `QB_BLOCK_REMOVE` answer (typed by hand after `BlockProcesses.BlockOutput`). */
export interface BlockOutput {
  blockId: string
  kind: BlockKind
  objectId: string
  /** Whether this call made it (false: it was there already, or it was removed). */
  created: boolean
}

export const addBlock = (publicationId: string, kind: BlockKind) =>
  runProcess<BlockOutput>(BLOCK.add, { publicationId, kind })

export const removeBlock = (blockId: string) => runProcess<BlockOutput>(BLOCK.remove, { blockId })

/** One of the taker's blocks, as the list shows it. */
export interface BlockRow {
  blockId: string
  kind: BlockKind
  objectId: string
  /** The publication's name, the quiz's title, the sponsor's or provider's company; null when gone. */
  name: string | null
  /** A blocked publication's own page. */
  publicationId: string | null
  createdAt: string
}

const KINDS: readonly string[] = ['PUBLICATION', 'QUIZ', 'SPONSOR', 'PROVIDER']

/** The taker's blocks, newest first. */
export async function readBlocks(): Promise<BlockRow[]> {
  const page = await runQuery<Record<string, unknown>>(BLOCK.list, { limit: BLOCKS_LIMIT })
  return page.items.filter((row) => KINDS.includes(String(row.kind))).map((row) => ({
    blockId: String(row.blockId),
    kind: String(row.kind) as BlockKind,
    objectId: String(row.objectId),
    name: row.name == null || row.name === '' ? null : String(row.name),
    publicationId: row.publicationId == null ? null : String(row.publicationId),
    createdAt: String(row.createdAt ?? ''),
  }))
}

/**
 * Whether blocking `kind` of the card `from` takes `card` off the screen too: the client's view of
 * `block.BlockRules.blocks`, only to know at once where the vertical mode goes on; the server decides what is hidden
 * (the feed is read again). The sponsor is told by its name here (a card carries no sponsor's id).
 */
export function covers(kind: BlockKind, from: FeedCard, card: FeedCard): boolean {
  switch (kind) {
    case 'PUBLICATION': return card.publicationId === from.publicationId
    case 'QUIZ': return !!from.quizId && card.quizId === from.quizId
    case 'SPONSOR': {
      const name = (c: FeedCard) => c.sponsorCompany ?? c.sponsorName
      return card.publicationId === from.publicationId || (!!name(from) && name(card) === name(from))
    }
    case 'PROVIDER': return !!from.videoProviderId && card.videoProviderId === from.videoProviderId
  }
}

/** Everything a block or an unblock changes, read again: the home, its shorts, the pages, the inbox, the list. */
export function refreshAfterBlocking(queryClient: QueryClient) {
  return Promise.all([['qb', 'feed'], ['qb', 'feed-shorts'], ['qb', 'publication'], ['qb', 'inbox'], BLOCKS_QUERY]
    .map((queryKey) => queryClient.invalidateQueries({ queryKey })))
}
