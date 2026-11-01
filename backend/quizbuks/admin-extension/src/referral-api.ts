import { runQuery, type Filter } from '@jabiz/admin'
import type { SortSpec } from './api'
import { TEMPLATE } from './ids'

/*
 * Referrals (backend `referral`, docs/quizbuks/plans/Q70-referrals-and-rounds.md), typed by hand after the template
 * `qb.admin.referrals`. Read only: referrals are written by the processes alone.
 */

export type ReferralStatus = 'REFERRED' | 'REWARDED' | 'EXPIRED' | 'VOID'
export const REFERRAL_STATUSES: readonly ReferralStatus[] = ['REFERRED', 'REWARDED', 'EXPIRED', 'VOID']

export interface Referral {
  referralId: string
  publicationId: string
  publicationName: string | null
  publisherKind: string
  publisherId: string
  referrerId: string | null
  refereeId: string | null
  status: ReferralStatus
  referredAt: string
  expiresAt: string
  rewardMethod: string
  amount: string | null
  rewardedAt: string | null
  fundsShort: boolean
  voidReason: string | null
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value === null || value === undefined ? null : String(value))

function referral(row: Row): Referral {
  return {
    referralId: String(row.referralId),
    publicationId: String(row.publicationId),
    publicationName: text(row.publicationName),
    publisherKind: String(row.publisherKind ?? 'SPONSOR'),
    publisherId: String(row.publisherId),
    referrerId: text(row.referrerId),
    refereeId: text(row.refereeId),
    status: String(row.status) as ReferralStatus,
    referredAt: String(row.referredAt ?? ''),
    expiresAt: String(row.expiresAt ?? ''),
    rewardMethod: String(row.rewardMethod ?? ''),
    amount: text(row.amount),
    rewardedAt: text(row.rewardedAt),
    fundsShort: row.fundsShort === true || row.fundsShort === 'true',
    voidReason: text(row.voidReason),
  }
}

/** A page of referrals, newest first: by state and, to see one referrer's (the risk of spam), by referrer. */
export async function readReferrals(status: string | null, referrerId: string | null, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<{ items: Referral[]; total?: number }> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const page = await runQuery<Row>(TEMPLATE.referrals, {
    params: referrerId ? { referrerId } : {},
    filters,
    sorts,
    offset,
    limit,
    count: true,
  })
  return { items: page.items.map(referral), total: page.total }
}
