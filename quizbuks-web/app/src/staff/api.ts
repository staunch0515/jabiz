import { ApiError, runProcess, runQuery } from '@jabiz/client'
import { hrefOf } from '../qb/router'
import { STAFF_CODE, STAFF_PROCESS, STAFF_TEMPLATE } from './ids'

/*
 * The staff pages' calls (Q39), typed by hand after the Java records of backend/quizbuks `reward.StaffProcesses`.
 */

/** The staff desk. */
export const STAFF_PATH = '/staff'
/** The caller's place on a staff; the counter's lists; the rotating code (renewed on its own clock, never with them). */
export const STAFF_QUERY = ['qb', 'staff-place'] as const
export const COUNTER_QUERY = ['qb', 'staff-counter'] as const
export const CODE_QUERY = ['qb', 'staff-code'] as const

/** The address of the App's page a redeem code leads to (`/redeem/<key>`, under the app's base). */
export function redeemLink(redeemKey: string, origin: string = window.location.origin): string {
  return origin + hrefOf(`/redeem/${encodeURIComponent(redeemKey)}`)
}

/** A row of `qb.app.staff-of`. */
export interface StaffOf {
  sponsorId: string
  sponsorCompany: string | null
  addedAt: string
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value == null ? null : String(value))

/** The sponsor whose staff the caller is on, or null. */
export async function readStaffOf(): Promise<StaffOf | null> {
  const row = (await runQuery<Row>(STAFF_TEMPLATE.staffOf, { limit: 1 })).items[0]
  return row ? { sponsorId: String(row.sponsorId), sponsorCompany: text(row.sponsorCompany),
    addedAt: String(row.addedAt) } : null
}

/** `RedemptionProcesses.IssueOutput`. */
export interface RedeemCode {
  redeemKey: string
  kind: 'ROTATING'
  issuedAt: string
  expiresAt: string | null
}

export const issueCode = () => runProcess<RedeemCode>(STAFF_PROCESS.issue, {})

export type CounterStatus = 'CLAIMED' | 'REDEEMED' | 'DUE' | 'EXPIRED' | 'VOID'

/** A row of `qb.app.staff-redemptions`: a claim to answer, or a prize the caller handed over. */
export interface CounterPrize {
  redemptionId: string
  code: string
  publicationName: string | null
  takerName: string | null
  description: string
  status: CounterStatus
  claimedAt: string | null
  confirmBy: string | null
  redeemedAt: string | null
}

export async function readCounter({ status, since }: { status: CounterStatus; since?: string }):
  Promise<CounterPrize[]> {
  const page = await runQuery<Row>(STAFF_TEMPLATE.redemptions, {
    params: since ? { since } : {},
    filters: [{ field: 'status', op: 'eq', value: status }],
    limit: 50,
  })
  return page.items.map((row) => ({
    redemptionId: String(row.redemptionId),
    code: String(row.code ?? ''),
    publicationName: text(row.publicationName),
    takerName: text(row.takerName),
    description: String(row.description ?? ''),
    status: String(row.status) as CounterStatus,
    claimedAt: text(row.claimedAt),
    confirmBy: text(row.confirmBy),
    redeemedAt: text(row.redeemedAt),
  }))
}

type RedemptionOutput = { redemptionId: string; status: CounterStatus; revision: number }

export const confirmClaim = (redemptionId: string) =>
  runProcess<RedemptionOutput>(STAFF_PROCESS.confirm, { redemptionId })

export const declineClaim = (redemptionId: string) =>
  runProcess<RedemptionOutput>(STAFF_PROCESS.decline, { redemptionId })

export const leaveStaff = () => runProcess<{ userId: string; changed: boolean }>(STAFF_PROCESS.leave, {})

/** Whether the server refused because the caller is on no sponsor's staff (any more). */
export function notStaff(error: unknown): boolean {
  return error instanceof ApiError && error.violations.some((v) => v.ruleCode === STAFF_CODE.notStaff)
}
