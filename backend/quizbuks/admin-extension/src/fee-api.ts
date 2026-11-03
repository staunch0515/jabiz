import { runProcess, runQuery, type Filter } from '@jabiz/admin'
import type { Page } from './finance-api'
import { FEE_PROCESS, TEMPLATE } from './ids'
import type { SortSpec } from './api'

/*
 * The platform's fees (docs/quizbuks/plans/Q87-fee-account.md): what the server charged each sponsor, the invoices
 * (one a prepaid charge, one a postpaid month), and finance's requests — a fee by hand, an invoice voided — which
 * another administrator approves. Amounts stay as the server sends them (JPY); the page shows them through `money`.
 */

type Row = Record<string, unknown>
const str = (value: unknown) => (value === null || value === undefined || value === '' ? null : String(value))
const amount = (value: unknown) => (value === null || value === undefined ? '0' : String(value))
const num = (value: unknown) => (value === null || value === undefined ? 0 : Number(value))

/** How a sponsor is billed (backend `FeeRules`): the fee is taken from the cash account at once, or invoiced monthly. */
export const BILLING_MODES = ['PREPAID', 'POSTPAID'] as const
export type BillingMode = (typeof BILLING_MODES)[number]
export const billingModeOf = (value: unknown): BillingMode => (value === 'POSTPAID' ? 'POSTPAID' : 'PREPAID')

/** A fee request's kind (backend `QbFees`): a fee charged by hand, or an invoice voided. */
export const REQUEST_KINDS = ['MANUAL', 'VOID'] as const
export type RequestKind = (typeof REQUEST_KINDS)[number]

/** The invoice states (backend `QbFees`); the first two are unpaid. */
export const INVOICE_STATUSES = ['OPEN', 'OVERDUE', 'PAID', 'VOID'] as const
export const INVOICE_UNPAID: readonly string[] = ['OPEN', 'OVERDUE']
/** The request states: waiting for the approval, done, or rejected (by the approver, or refused by the server). */
export const REQUEST_STATUSES = ['REVIEW', 'DONE', 'REJECTED'] as const
export const REQUEST_REVIEW = 'REVIEW'

/** A row of `qb.admin.fee-charges`. */
export interface FeeCharge {
  chargeId: string
  sponsorId: string
  company: string | null
  operatorId: string | null
  kind: string
  period: string
  billingMode: BillingMode
  amount: string
  tax: string
  total: string
  reason: string | null
  chargedAt: string | null
  chargedBy: string | null
  invoiceNo: string | null
  invoiceStatus: string | null
}

/** A row of `qb.admin.fee-invoices`. */
export interface FeeInvoice {
  invoiceId: string
  invoiceNo: string | null
  sponsorId: string
  company: string | null
  operatorId: string | null
  period: string
  billingMode: BillingMode
  amount: string
  tax: string
  total: string
  status: string
  issuedAt: string | null
  dueOn: string | null
  paidAt: string | null
  remindersSent: number
  voidedAt: string | null
  voidReason: string | null
}

/** A row of `qb.admin.fee-requests`. */
export interface FeeRequest {
  requestId: string
  kind: RequestKind
  sponsorId: string
  company: string | null
  amount: string | null
  invoiceId: string | null
  reason: string | null
  status: string
  requestedBy: string | null
  requestedAt: string | null
  approvalRequestId: string | null
  decidedAt: string | null
  chargeId: string | null
}

function toCharge(row: Row): FeeCharge {
  return {
    chargeId: String(row.chargeId),
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    operatorId: str(row.operatorId),
    kind: String(row.kind ?? ''),
    period: String(row.period ?? ''),
    billingMode: billingModeOf(row.billingMode),
    amount: amount(row.amount),
    tax: amount(row.tax),
    total: amount(row.total),
    reason: str(row.reason),
    chargedAt: str(row.chargedAt),
    chargedBy: str(row.chargedBy),
    invoiceNo: str(row.invoiceNo),
    invoiceStatus: str(row.invoiceStatus),
  }
}

function toInvoice(row: Row): FeeInvoice {
  return {
    invoiceId: String(row.invoiceId),
    invoiceNo: str(row.invoiceNo),
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    operatorId: str(row.operatorId),
    period: String(row.period ?? ''),
    billingMode: billingModeOf(row.billingMode),
    amount: amount(row.amount),
    tax: amount(row.tax),
    total: amount(row.total),
    status: String(row.status ?? ''),
    issuedAt: str(row.issuedAt),
    dueOn: str(row.dueOn),
    paidAt: str(row.paidAt),
    remindersSent: num(row.remindersSent),
    voidedAt: str(row.voidedAt),
    voidReason: str(row.voidReason),
  }
}

function toRequest(row: Row): FeeRequest {
  return {
    requestId: String(row.requestId),
    kind: row.kind === 'VOID' ? 'VOID' : 'MANUAL',
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    amount: str(row.amount),
    invoiceId: str(row.invoiceId),
    reason: str(row.reason),
    status: String(row.status ?? ''),
    requestedBy: str(row.requestedBy),
    requestedAt: str(row.requestedAt),
    approvalRequestId: str(row.approvalRequestId),
    decidedAt: str(row.decidedAt),
    chargeId: str(row.chargeId),
  }
}

/** The charges, newest first, with the bar's filters. */
export async function readFeeCharges(offset: number, limit: number, sorts?: SortSpec[], filters?: Filter[]):
  Promise<Page<FeeCharge>> {
  const page = await runQuery<Row>(TEMPLATE.feeCharges, { filters, sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toCharge) }
}

/** The invoices, newest first, of a state (all when null), with the bar's filters. */
export async function readFeeInvoices(status: string | null, offset: number, limit: number, sorts?: SortSpec[],
  more?: Filter[]): Promise<Page<FeeInvoice>> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const page = await runQuery<Row>(TEMPLATE.feeInvoices, { filters: [...filters, ...(more ?? [])], sorts, offset,
    limit, count: true })
  return { total: page.total, items: page.items.map(toInvoice) }
}

/** One invoice; null when there is none. */
export async function readFeeInvoice(invoiceId: string): Promise<FeeInvoice | null> {
  const page = await runQuery<Row>(TEMPLATE.feeInvoices, { filters: [{ field: 'invoiceId', op: 'eq',
    value: invoiceId }], limit: 1 })
  return page.items[0] ? toInvoice(page.items[0]) : null
}

/** The requests, newest first, of a state (all when null), with the bar's filters. */
export async function readFeeRequests(status: string | null, offset: number, limit: number, sorts?: SortSpec[],
  more?: Filter[]): Promise<Page<FeeRequest>> {
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const page = await runQuery<Row>(TEMPLATE.feeRequests, { filters: [...filters, ...(more ?? [])], sorts, offset,
    limit, count: true })
  return { total: page.total, items: page.items.map(toRequest) }
}

/** One request; null when there is none. */
export async function readFeeRequest(requestId: string): Promise<FeeRequest | null> {
  const page = await runQuery<Row>(TEMPLATE.feeRequests, { filters: [{ field: 'requestId', op: 'eq',
    value: requestId }], limit: 1 })
  return page.items[0] ? toRequest(page.items[0]) : null
}

/**
 * `FeeRequests.RequestInput`: a fee by hand names the sponsor and the amount (the tax is the server's); an invoice
 * voided names the invoice. Both give the reason the approver reads.
 */
export type FeeRequestInput =
  | { kind: 'MANUAL'; sponsorId: string; amount: number; reason: string }
  | { kind: 'VOID'; invoiceId: string; reason: string }

/** Records the request for another administrator's approval; the output names the approval. */
export const recordFeeRequest = (input: FeeRequestInput) =>
  runProcess<{ requestId: string; kind: RequestKind; status: string; approvalRequestId: string | null }>(
    FEE_PROCESS.request, input)

/** `FeeProcesses.BillingInput`: how a sponsor is billed from now on, with the credit (postpaid) and the reason. */
export interface BillingInput {
  userId: string
  billingMode: BillingMode
  creditLimit: number
  reason: string
}

/** Sets a sponsor's billing (an operator's own sponsor, or any for finance); `changed` says whether anything moved. */
export const setBilling = (input: BillingInput) =>
  runProcess<{ userId: string; billingMode: BillingMode; creditLimit: string | number; changed: boolean }>(
    FEE_PROCESS.billingSet, input)
