import { ApiError, notify } from '@jabiz/admin'
import type { ReconItem, TopUpFilter, TransferFilter } from './finance-api'
import { isUserId } from './ids'

/*
 * The finance pages' helpers that are not components (docs/quizbuks/plans/Q9c-admin-finance.md): the filters their
 * addresses carry (so that the overview and the reconciliation link to a filtered list), the grouping of a run's
 * differences, and how a failed action is told.
 */

const DAY = /^\d{4}-\d{2}-\d{2}$/

/** The filter an address asks for; anything that is not a key, a state or a day is left out. */
export function transferFilterOf(search: URLSearchParams): TransferFilter {
  const text = (name: string) => search.get(name)?.trim() || null
  const day = (name: string) => (DAY.test(text(name) ?? '') ? text(name) : null)
  const user = text('user')
  const transfer = text('transfer')
  const status = text('status')
  return {
    status: status && /^[A-Z_]{1,20}$/.test(status) ? status : null,
    userId: user && isUserId(user) ? user : null,
    transferId: transfer && isUserId(transfer) ? transfer : null,
    from: day('from'),
    to: day('to'),
    stale: search.get('stale') === '1',
    held: search.get('held') === '1',
  }
}

export function transferAddressOf(filter: TransferFilter): Record<string, string> {
  const search: Record<string, string> = {}
  if (filter.status) search.status = filter.status
  if (filter.userId) search.user = filter.userId
  if (filter.transferId) search.transfer = filter.transferId
  if (filter.from) search.from = filter.from
  if (filter.to) search.to = filter.to
  if (filter.stale) search.stale = '1'
  if (filter.held) search.held = '1'
  return search
}

/** The filter an address asks for; anything that is not a key, a state or a day is left out. */
export function topUpFilterOf(search: URLSearchParams): TopUpFilter {
  const text = (name: string) => search.get(name)?.trim() || null
  const day = (name: string) => (DAY.test(text(name) ?? '') ? text(name) : null)
  const sponsor = text('sponsor')
  const topUp = text('topUp')
  const status = text('status')
  return {
    status: status && /^[A-Z_]{1,20}$/.test(status) ? status : null,
    sponsorId: sponsor && isUserId(sponsor) ? sponsor : null,
    topUpId: topUp && isUserId(topUp) ? topUp : null,
    from: day('from'),
    to: day('to'),
  }
}

export function topUpAddressOf(filter: TopUpFilter): Record<string, string> {
  const search: Record<string, string> = {}
  if (filter.status) search.status = filter.status
  if (filter.sponsorId) search.sponsor = filter.sponsorId
  if (filter.topUpId) search.topUp = filter.topUpId
  if (filter.from) search.from = filter.from
  if (filter.to) search.to = filter.to
  return search
}

/** The differences of a page, by check, in the order they came. */
export function byCheck(items: ReconItem[]): [string, ReconItem[]][] {
  const groups = new Map<string, ReconItem[]>()
  for (const item of items) groups.set(item.checkCode, [...(groups.get(item.checkCode) ?? []), item])
  return [...groups.entries()]
}

/** Tells why an action failed, in the server's words when it said why. */
export function tell(error: unknown, fallback: string) {
  notify.error(error instanceof ApiError && error.status !== 500 ? error.display : fallback)
}

/** As {@link tell}, and rethrows: a confirmation dialog stays open. */
export function failed(error: unknown, fallback: string): never {
  tell(error, fallback)
  throw error
}
