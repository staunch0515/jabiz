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
export const REQUEST_KINDS = ['MANUAL', 'VOID', 'CREDIT'] as const
export type RequestKind = (typeof REQUEST_KINDS)[number]

/** The invoice states (backend `QbFees`); the first three are unpaid (F-M1: an invoice may be paid in part). */
export const INVOICE_STATUSES = ['OPEN', 'PARTIAL', 'OVERDUE', 'PAID', 'VOID'] as const
export const INVOICE_UNPAID: readonly string[] = ['OPEN', 'PARTIAL', 'OVERDUE']
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
  /** F-M1: what has been paid of the total. */
  paidAmount: string
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
  /** F-M2: a grant by hand's days of validity (none: the parameter's) and the lot the approval made. */
  validDays: number | null
  grantId: string | null
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
    paidAmount: amount(row.paidAmount),
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
    kind: row.kind === 'VOID' ? 'VOID' : row.kind === 'CREDIT' ? 'CREDIT' : 'MANUAL',
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
    validDays: row.validDays == null ? null : num(row.validDays),
    grantId: str(row.grantId),
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
 * voided names the invoice; platform fee Kudos granted by hand (F-M2) name the sponsor, the amount (within the cap
 * `qb.fee-credit.manual.max`, the server's check) and, if not the parameter's, the days valid. All give the reason
 * the approver reads.
 */
export type FeeRequestInput =
  | { kind: 'MANUAL'; sponsorId: string; amount: number; reason: string }
  | { kind: 'VOID'; invoiceId: string; reason: string }
  | { kind: 'CREDIT'; sponsorId: string; amount: number; reason: string; validDays?: number }

/** Records the request for another administrator's approval; the output names the approval. */
export const recordFeeRequest = (input: FeeRequestInput) =>
  runProcess<{ requestId: string; kind: RequestKind; status: string; approvalRequestId: string | null }>(
    FEE_PROCESS.request, input)

/** `FeeProcesses.BillingInput`: how a sponsor is billed from now on, with the credit (postpaid) and the reason. */
export type BillingInput = {
  userId: string
  billingMode: BillingMode
  creditLimit: number
  reason: string
}

/** The fee pages' query keys: every list under one, so that a request recorded refreshes them all. */
export const FEES_KEY = ['qb-ext', 'finance', 'fees'] as const

/** Sets a sponsor's billing (an operator's own sponsor, or any for finance); `changed` says whether anything moved. */
export const setBilling = (input: BillingInput) =>
  runProcess<{ userId: string; billingMode: BillingMode; creditLimit: string | number; changed: boolean }>(
    FEE_PROCESS.billingSet, input)

// ---- F-M1: the account that pays the platform's fees ---------------------------------------------------------------

/** One Sponsor's account: the granted credit, the cash and what open disputes hold of it (`qb.*.fee-account`). */
export interface FeeAccount {
  feeCreditBalance: string
  cashBalance: string
  cashHeld: string
}

/** A lot with money left, in the order the account is spent (`qb.*.fee-account-lots`). */
export interface FeeAccountLot {
  seq: number
  subAccount: string
  lotId: string
  source: string
  amount: string
  remaining: string
  arrivedAt: string | null
  expiresAt: string | null
}

/** A part of a payment (`qb.*.fee-payments`); negative: given back when an invoice was voided. */
export interface FeePaymentPart {
  paymentId: string
  paidAt: string | null
  targetEntity: string
  invoiceNo: string | null
  subAccount: string
  source: string | null
  amount: string
}

/** The accounts in all, by sub-account (`qb.admin.fee-account-totals`). */
export interface FeeAccountTotal {
  subAccount: string
  balance: string
  lotsRemaining: string
  openLots: number
  sponsors: number
  paidFees: string
}

/** Finance reads any Sponsor's account; an operator only its own Sponsors' (another's: nothing). */
const accountTemplates = (operator: boolean) => (operator
  ? { account: TEMPLATE.operatorFeeAccount, lots: TEMPLATE.operatorFeeAccountLots, payments: TEMPLATE.operatorFeePayments }
  : { account: TEMPLATE.feeAccount, lots: TEMPLATE.feeAccountLots, payments: TEMPLATE.feePayments })

export async function readFeeAccount(sponsorId: string, operator: boolean): Promise<FeeAccount> {
  const page = await runQuery<Row>(accountTemplates(operator).account, { params: { sponsorId }, limit: 1 })
  const row = page.items[0] ?? {}
  return { feeCreditBalance: amount(row.feeCreditBalance), cashBalance: amount(row.cashBalance),
    cashHeld: amount(row.cashHeld) }
}

export async function readFeeAccountLots(sponsorId: string, operator: boolean, offset: number, limit: number):
  Promise<Page<FeeAccountLot>> {
  const page = await runQuery<Row>(accountTemplates(operator).lots, { params: { sponsorId }, offset, limit,
    count: true })
  return { total: page.total, items: page.items.map((row) => ({
    seq: num(row.seq), subAccount: String(row.subAccount ?? ''), lotId: String(row.lotId ?? ''),
    source: String(row.source ?? ''), amount: amount(row.amount), remaining: amount(row.remaining),
    arrivedAt: str(row.arrivedAt), expiresAt: str(row.expiresAt) })) }
}

export async function readFeePayments(sponsorId: string, operator: boolean, offset: number, limit: number):
  Promise<Page<FeePaymentPart>> {
  const page = await runQuery<Row>(accountTemplates(operator).payments, { params: { sponsorId }, offset, limit,
    count: true })
  return { total: page.total, items: page.items.map((row) => ({
    paymentId: String(row.paymentId), paidAt: str(row.paidAt), targetEntity: String(row.targetEntity ?? ''),
    invoiceNo: str(row.invoiceNo), subAccount: String(row.subAccount ?? ''), source: str(row.source),
    amount: amount(row.amount) })) }
}

/** The month (`from`, `to`: UTC instants) of the payments counted. */
export async function readFeeAccountTotals(from: string, to: string): Promise<FeeAccountTotal[]> {
  const page = await runQuery<Row>(TEMPLATE.feeAccountTotals, { params: { from, to }, limit: 10 })
  return page.items.map((row) => ({ subAccount: String(row.subAccount ?? ''), balance: amount(row.balance),
    lotsRemaining: amount(row.lotsRemaining), openLots: num(row.openLots), sponsors: num(row.sponsors),
    paidFees: amount(row.paidFees) }))
}

// ---- F-M2: platform fee Kudos granted ---------------------------------------------------------------------------

/** A row of `qb.admin.fee-credit-grants`: one lot, with its operator when it was granted. */
export interface FeeCreditGrant {
  grantId: string
  sponsorId: string
  company: string | null
  operatorId: string | null
  operatorName: string | null
  source: string
  amount: string
  spent: string
  remaining: string
  voidedAmount: string
  grantedAt: string | null
  expiresAt: string | null
  voidedAt: string | null
}

/** How the grants are summed (`qb.admin.fee-credit-summary`'s `groupBy`). */
export const GRANT_GROUPS = ['OPERATOR', 'SPONSOR', 'SOURCE'] as const
export type GrantGroup = (typeof GRANT_GROUPS)[number]

/** A row of `qb.admin.fee-credit-summary`: `groupKey` is the operator ('-': none), the Sponsor or the source. */
export interface FeeCreditSum {
  groupKey: string
  label: string | null
  lots: number
  granted: string
  spent: string
  remaining: string
  voided: string
}

/** The lots granted, newest first. */
export async function readFeeCreditGrants(offset: number, limit: number, sorts?: SortSpec[], filters?: Filter[]):
  Promise<Page<FeeCreditGrant>> {
  const page = await runQuery<Row>(TEMPLATE.feeCreditGrants, { filters, sorts, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({
      grantId: String(row.grantId),
      sponsorId: String(row.sponsorId ?? ''),
      company: str(row.company),
      operatorId: str(row.operatorId),
      operatorName: str(row.operatorName),
      source: String(row.source ?? ''),
      amount: amount(row.amount),
      spent: amount(row.spent),
      remaining: amount(row.remaining),
      voidedAmount: amount(row.voidedAmount),
      grantedAt: str(row.grantedAt),
      expiresAt: str(row.expiresAt),
      voidedAt: str(row.voidedAt),
    })),
  }
}

/** The grants summed one way, the most granted first. */
export async function readFeeCreditSummary(groupBy: GrantGroup, offset: number, limit: number):
  Promise<Page<FeeCreditSum>> {
  const page = await runQuery<Row>(TEMPLATE.feeCreditSummary, { params: { groupBy }, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({
      groupKey: String(row.groupKey ?? ''),
      label: str(row.label),
      lots: num(row.lots),
      granted: amount(row.granted),
      spent: amount(row.spent),
      remaining: amount(row.remaining),
      voided: amount(row.voided),
    })),
  }
}

/** An operator's Sponsor's onboarding grant: whether it is switched on, and the lot if given; null: not its Sponsor. */
export interface Onboarding {
  enabled: boolean
  grantedAt: string | null
  amount: string | null
  expiresAt: string | null
}

export async function readOnboarding(sponsorId: string): Promise<Onboarding | null> {
  const page = await runQuery<Row>(TEMPLATE.operatorFeeCreditOnboarding, { params: { sponsorId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return { enabled: row.enabled === true, grantedAt: str(row.grantedAt), amount: str(row.amount),
    expiresAt: str(row.expiresAt) }
}

/** Confirms the Sponsor (a lead of its operator) and grants the onboarding platform fee Kudos once. */
export const confirmOnboarding = (sponsorId: string) =>
  runProcess<{ grantId: string | null; granted: boolean; amount: number | null; expiresAt: string | null }>(
    FEE_PROCESS.onboarding, { sponsorId })
