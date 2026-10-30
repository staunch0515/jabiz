import { runProcess, runQuery, type Filter } from '@jabiz/admin'
import type { Page } from './finance-api'
import { PROCESS, TEMPLATE } from './ids'
import type { SortSpec } from './api'

type Row = Record<string, unknown>
const str = (value: unknown) => (value === null || value === undefined ? null : String(value))
const amount = (value: unknown) => (value === null || value === undefined ? '0' : String(value))

/** A manual credit's kind (backend `QbTopUps`): money received outside the system, or Kudos granted. */
export const MANUAL_KINDS = ['PAID_OUTSIDE', 'GRANT'] as const
export type ManualKind = (typeof MANUAL_KINDS)[number]

/** A row of `qb.admin.manual-credits` (Q18b). */
export interface ManualCredit {
  topUpId: string
  sponsorId: string
  company: string | null
  transferCode: string | null
  manualKind: ManualKind
  manualReason: string | null
  amount: string
  status: string
  createdAt: string | null
  receivedOn: string | null
  bankReference: string | null
  recordedBy: string | null
  approvalRequestId: string | null
  paidAt: string | null
}

function toManualCredit(row: Row): ManualCredit {
  return {
    topUpId: String(row.topUpId),
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    transferCode: str(row.transferCode),
    manualKind: row.manualKind === 'GRANT' ? 'GRANT' : 'PAID_OUTSIDE',
    manualReason: str(row.manualReason),
    amount: amount(row.amount),
    status: String(row.status ?? ''),
    createdAt: str(row.createdAt),
    receivedOn: str(row.receivedOn),
    bankReference: str(row.bankReference),
    recordedBy: str(row.recordedBy),
    approvalRequestId: str(row.approvalRequestId),
    paidAt: str(row.paidAt),
  }
}

/** The manual credits, newest first, of a state (all when null). */
export async function readManualCredits(status: string | null, offset: number, limit: number, sorts?: SortSpec[], more?: Filter[]):
  Promise<Page<ManualCredit>> {
  const filters: Filter[] = []
  if (status) filters.push({ field: 'status', op: 'eq', value: status })
  const page = await runQuery<Row>(TEMPLATE.manualCredits, { filters: [...filters, ...(more ?? [])], sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toManualCredit) }
}

/** One manual credit; null when there is none. */
export async function readManualCredit(topUpId: string): Promise<ManualCredit | null> {
  const page = await runQuery<Row>(TEMPLATE.manualCredits, { filters: [{ field: 'topUpId', op: 'eq',
    value: topUpId }], limit: 1 })
  return page.items[0] ? toManualCredit(page.items[0]) : null
}

/** An approved sponsor, as the form offers them. */
export interface SponsorChoice {
  userId: string
  company: string | null
  contactName: string | null
}

/** How many approved sponsors the form offers at most, by company name. */
export const SPONSOR_CHOICES = 200

/** The approved sponsors, by company name (`qb.admin.sponsors`, which finance reads). */
export async function readApprovedSponsors(): Promise<SponsorChoice[]> {
  const page = await runQuery<Row>(TEMPLATE.sponsors, {
    filters: [{ field: 'reviewStatus', op: 'eq', value: 'APPROVED' }],
    sorts: [{ field: 'company', asc: true }],
    limit: SPONSOR_CHOICES,
  })
  return page.items.map((row) => ({ userId: String(row.userId ?? ''), company: str(row.company),
    contactName: str(row.contactName) }))
}

/**
 * `ManualCredits.RecordInput`: whose wallet, how much, which kind and why; money received outside also the day
 * (`YYYY-MM-DD`, in Japan) and the statement's reference, which a grant leaves out.
 */
export type ManualRecordInput = {
  sponsorId: string
  kind: ManualKind
  amount: number
  reason: string
  receivedOn?: string
  bankReference?: string
}

/** Records the credit for another administrator's approval; the output names the request. */
export const recordManualCredit = (input: ManualRecordInput) =>
  runProcess<{ topUpId: string; status: string; approvalRequestId: string | null }>(PROCESS.manualRecord, input)
