import { runQuery, type QueryPage } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'

/*
 * The taker's prizes of all three kinds in one list (F-F4 section 4.1; template `qb.app.prizes`): the tab "To
 * collect", its badge, the bottom menu's badge, the number in "Me" and the pass shown at the counter all read it, so
 * that they say the same number. The server says what is open; the pages only show.
 */

export const PRIZES_TEMPLATE = 'qb.app.prizes'

/** Everything about prizes in the query cache: invalidated together when one is collected, confirmed or settled. */
export const PRIZES_QUERY = ['qb', 'prizes'] as const

/** To collect at a sponsor's place, owed in cash, digital. */
export type PrizeKind = 'REDEEM' | 'CASH' | 'DIGITAL'

export interface Prize {
  kind: PrizeKind
  prizeId: string
  attemptId: string
  /** `REFERRAL` for a prize a friend's pass earned. */
  sourceKind: string | null
  /** The cash reward's or the prize's code. */
  code: string | null
  publicationId: string
  publicationName: string
  sponsorCompany: string | null
  amount: unknown
  description: string | null
  status: string
  dueAt: string | null
  expiresAt: string | null
  confirmBy: string | null
}

type Row = Record<string, unknown>

const text = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

function prize(row: Row): Prize {
  return {
    kind: String(row.kind) as PrizeKind,
    prizeId: String(row.prizeId),
    attemptId: String(row.attemptId ?? ''),
    sourceKind: text(row.sourceKind),
    code: text(row.code),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    sponsorCompany: text(row.sponsorCompany),
    amount: row.amount,
    description: text(row.description),
    status: String(row.status),
    dueAt: text(row.dueAt),
    expiresAt: text(row.expiresAt),
    confirmBy: text(row.confirmBy),
  }
}

/** A page of the caller's prizes, newest first; `open` only those that still need something. */
export async function readPrizes(open: boolean, offset: number, limit: number): Promise<QueryPage<Prize>> {
  const page = await runQuery<Row>(PRIZES_TEMPLATE, { params: { open }, offset, limit })
  return { ...page, items: page.items.map(prize) }
}

/** How many prizes are open: one row asked for, and the count. */
export async function readOpenCount(): Promise<number> {
  const page = await runQuery<Row>(PRIZES_TEMPLATE, { params: { open: true }, limit: 1, count: true })
  return page.total ?? page.items.length
}

/** The prizes to collect at a sponsor's place that are due now: what the counter can be shown for. */
export async function readDuePasses(): Promise<Prize[]> {
  const page = await runQuery<Row>(PRIZES_TEMPLATE, {
    params: { open: true },
    filters: [
      { field: 'kind', op: 'eq', value: 'REDEEM' },
      { field: 'status', op: 'eq', value: 'DUE' },
    ],
    limit: 50,
  })
  return page.items.map(prize)
}

export const OPEN_COUNT_QUERY = [...PRIZES_QUERY, 'count'] as const
export const DUE_PASSES_QUERY = [...PRIZES_QUERY, 'due-passes'] as const

/**
 * The number of open prizes, undefined until known or when it cannot be read (the number is then left out, never
 * guessed). One query for the tab, both badges and "Me": asked when the app is entered and whenever the frame
 * invalidates {@link PRIZES_QUERY}, never polled.
 */
export function useOpenPrizes(enabled = true): number | undefined {
  const count = useQuery({ queryKey: OPEN_COUNT_QUERY, queryFn: readOpenCount, enabled, staleTime: 60_000 })
  return count.data
}
