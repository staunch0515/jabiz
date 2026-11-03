import { runProcess, runQuery } from '@jabiz/admin'
import { FEES } from './ids'

/*
 * The platform fees (Q87b, backend `fee.FeeProcesses`, docs/quizbuks/plans/Q87-fee-account.md §11), typed by hand
 * after the records and templates. Finance asks for a charge by hand or an invoice's voiding (another administrator
 * approves in the approvals inbox) and sets a sponsor's billing; an operator sets its own sponsors'. The server
 * decides every amount and refusal.
 */

type Row = Record<string, unknown>
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))

export type BillingMode = 'PREPAID' | 'POSTPAID'
export const BILLING_MODES: readonly BillingMode[] = ['PREPAID', 'POSTPAID']
export type InvoiceStatus = 'PAID' | 'ISSUED' | 'OVERDUE' | 'VOID'
export const INVOICE_STATUSES: readonly InvoiceStatus[] = ['ISSUED', 'OVERDUE', 'PAID', 'VOID']

export interface AdminInvoice {
  invoiceId: string
  invoiceNo: string
  sponsorId: string
  company: string | null
  period: string
  billingMode: BillingMode
  total: number
  due: number
  status: InvoiceStatus
  issuedAt: string
  dueOn: string | null
  reminders: number
}

export interface FeeRequest {
  requestId: string
  kind: 'CHARGE' | 'VOID'
  sponsorId: string
  company: string | null
  invoiceNo: string | null
  amount: number | null
  reason: string
  status: 'REVIEW' | 'APPROVED' | 'REJECTED'
  requestedBy: string
  requestedAt: string
}

export interface SponsorBilling {
  sponsorId: string
  company: string | null
  billingMode: BillingMode
  /** Whether the sponsor has a setting of its own (else the platform's default applies). */
  own: boolean
  creditLimit: number
  unpaid: number
  reason: string | null
  updatedAt: string | null
}

export const FEES_KEY = ['qb-ext', 'fees'] as const

const modeOf = (value: unknown): BillingMode => (value === 'POSTPAID' ? 'POSTPAID' : 'PREPAID')

export function invoiceOf(row: Row): AdminInvoice {
  return {
    invoiceId: String(row.invoiceId),
    invoiceNo: String(row.invoiceNo ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    period: String(row.period ?? ''),
    billingMode: modeOf(row.billingMode),
    total: num(row.total),
    due: num(row.due),
    status: (INVOICE_STATUSES as readonly string[]).includes(String(row.status))
      ? row.status as InvoiceStatus : 'PAID',
    issuedAt: String(row.issuedAt ?? ''),
    dueOn: str(row.dueOn),
    reminders: num(row.reminders),
  }
}

export async function readInvoices(status: InvoiceStatus | null): Promise<AdminInvoice[]> {
  const page = await runQuery<Row>(FEES.invoicesTemplate, { params: { status }, limit: 200 })
  return (page.items ?? []).map(invoiceOf)
}

export async function readRequests(): Promise<FeeRequest[]> {
  const page = await runQuery<Row>(FEES.requestsTemplate, { limit: 100 })
  return (page.items ?? []).map((row) => ({
    requestId: String(row.requestId),
    kind: row.kind === 'VOID' ? 'VOID' : 'CHARGE',
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    invoiceNo: str(row.invoiceNo),
    amount: row.amount == null ? null : num(row.amount),
    reason: String(row.reason ?? ''),
    status: row.status === 'APPROVED' || row.status === 'REJECTED' ? row.status : 'REVIEW',
    requestedBy: String(row.requestedBy ?? ''),
    requestedAt: String(row.requestedAt ?? ''),
  }))
}

export async function readOperatorBilling(): Promise<SponsorBilling[]> {
  const page = await runQuery<Row>(FEES.operatorBillingTemplate, { limit: 500 })
  return (page.items ?? []).map((row) => ({
    sponsorId: String(row.sponsorId),
    company: str(row.company),
    billingMode: modeOf(row.billingMode),
    own: row.own === true,
    creditLimit: num(row.creditLimit),
    unpaid: num(row.unpaid),
    reason: str(row.reason),
    updatedAt: str(row.updatedAt),
  }))
}

/** `FeeProcesses.RequestOutput`: the request, waiting for another administrator. */
export interface RequestOutput {
  requestId: string
  status: string
  approvalRequestId: string | null
}

export const askCharge = (sponsorId: string, amount: number, reason: string) =>
  runProcess<RequestOutput>(FEES.request, { kind: 'CHARGE', sponsorId, amount, reason })

export const askVoid = (invoice: Pick<AdminInvoice, 'sponsorId' | 'invoiceId'>, reason: string) =>
  runProcess<RequestOutput>(FEES.request, { kind: 'VOID', sponsorId: invoice.sponsorId,
    invoiceId: invoice.invoiceId, reason })

/** A prepaid sponsor has no credit: the page sends 0 for it. */
export const setBilling = (sponsorId: string, billingMode: BillingMode, creditLimit: number, reason: string) =>
  runProcess(FEES.billingSet, { sponsorId, billingMode, creditLimit: billingMode === 'POSTPAID' ? creditLimit : 0,
    reason })

/** A whole amount of yen as typed (0 allowed), or null. */
export function wholeOf(text: string, max = 10_000_000): number | null {
  const trimmed = text.trim().replace(/,/g, '')
  if (!/^\d{1,8}$/.test(trimmed)) return null
  const value = Number(trimmed)
  return value <= max ? value : null
}
