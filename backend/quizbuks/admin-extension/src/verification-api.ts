import { runProcess, runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { SPONSOR_VERIFICATION_PROCESS, TEMPLATE } from './ids'

/*
 * F-M3: the Sponsors' company verifications (backend `sponsorverification`), typed by hand after
 * `qb.admin.sponsor-verifications`. Headquarters reads every Sponsor's, an operator its own Sponsors' (the server's
 * scope). The review is the platform's approval panel; the revoking a process with a reason and a second factor.
 */

export type VerificationStatus = 'LINK_SENT' | 'REVIEW' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'SUPERSEDED'
  | 'REVOKED'
export const VERIFICATION_STATUSES: readonly VerificationStatus[] = ['LINK_SENT', 'REVIEW', 'APPROVED', 'REJECTED',
  'WITHDRAWN', 'SUPERSEDED', 'REVOKED']
/** How the company was proven: the account's own address on the domain, or a link sent to the address shown. */
export type VerificationMethod = 'ACCOUNT' | 'LINK'

/** The longest reason the server takes (`QB_SPONSOR_VERIFICATION_REVOKE`). */
export const REVOKE_REASON_MAX = 500

/** A row of `qb.admin.sponsor-verifications`. */
export interface SponsorVerification {
  verificationId: string
  sponsorId: string
  companyName: string
  domain: string
  method: VerificationMethod
  confirmAddress: string | null
  status: VerificationStatus
  approvalRequestId: string | null
  operatorId: string | null
  submittedAt: string | null
  confirmedAt: string | null
  decidedAt: string | null
  verifiedAt: string | null
  rejectReason: string | null
  endedAt: string | null
  revokeReason: string | null
  /** The operator whose member revoked it; null: the platform (or not revoked). */
  revokedOperatorId: string | null
}

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

function rowOf(row: Row): SponsorVerification {
  return {
    verificationId: String(row.verificationId ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    companyName: String(row.companyName ?? ''),
    domain: String(row.domain ?? ''),
    method: (str(row.method) ?? 'ACCOUNT') as VerificationMethod,
    confirmAddress: str(row.confirmAddress),
    status: (str(row.status) ?? 'REVIEW') as VerificationStatus,
    approvalRequestId: str(row.approvalRequestId),
    operatorId: str(row.operatorId),
    submittedAt: str(row.submittedAt),
    confirmedAt: str(row.confirmedAt),
    decidedAt: str(row.decidedAt),
    verifiedAt: str(row.verifiedAt),
    rejectReason: str(row.rejectReason),
    endedAt: str(row.endedAt),
    revokeReason: str(row.revokeReason),
    revokedOperatorId: str(row.revokedOperatorId),
  }
}

/** One page of the verifications, newest first; of one status and one Sponsor when given. */
export async function readSponsorVerifications(offset: number, limit: number, sorts: SortSpec[] | undefined,
  status: VerificationStatus | null, sponsorId: string | null, filters: Filter[] = []): Promise<Page<SponsorVerification>> {
  const params: Record<string, string> = {}
  if (status) params.status = status
  if (sponsorId) params.sponsorId = sponsorId
  const answer = await runQuery<Row>(TEMPLATE.sponsorVerifications, { params, filters, sorts, offset, limit, count: true })
  return { items: answer.items.map(rowOf), total: answer.total }
}

/** One verification; null when the session does not read it (another operator's Sponsor's) or there is none. */
export async function readSponsorVerification(verificationId: string): Promise<SponsorVerification | null> {
  const answer = await runQuery<Row>(TEMPLATE.sponsorVerifications, {
    filters: [{ field: 'verificationId', op: 'eq', value: verificationId }], limit: 1 })
  return answer.items.length === 0 ? null : rowOf(answer.items[0])
}

/** The Sponsor's latest verification, if any (newest first); null when it has none or the session reads none. */
export async function readLatestVerification(sponsorId: string): Promise<SponsorVerification | null> {
  const answer = await runQuery<Row>(TEMPLATE.sponsorVerifications, { params: { sponsorId }, limit: 1 })
  return answer.items.length === 0 ? null : rowOf(answer.items[0])
}

/** Whether it may be revoked: only an approved verification. */
export const revocable = (status: VerificationStatus) => status === 'APPROVED'

/** Revokes the Sponsor's verification (the platform asks for a second factor, then repeats the call). */
export const revokeVerification = (sponsorId: string, reason: string) =>
  runProcess<unknown>(SPONSOR_VERIFICATION_PROCESS.revoke, { sponsorId, reason })
