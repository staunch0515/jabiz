import { api, ApiError, runProcess, runQuery, sessionFetch, unwrap, type Filter } from '@jabiz/admin'
import { PROCESS, STALE_AFTER_MS, TEMPLATE, type ReportId } from './ids'

/*
 * What the finance pages read and run (docs/quizbuks/plans/Q9c-admin-finance.md), typed by hand after the templates of
 * backend/quizbuks (`queries/qb/admin`). Every call is the platform's: templates, their export
 * (`POST /api/queries/{id}/export`), the report archive (`REPORT_ISSUE`, `GET /api/reports/runs`) and the one QuizBuks
 * process finance runs here (`QB_TRANSFER_HOLD_RELEASE`). Amounts stay as the server sends them (exact decimals).
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** One page of a list; `total` when the server counted all rows. */
export interface Page<T> {
  items: T[]
  total?: number
}

/** The time before which a transfer still sending is in doubt. */
export const staleBefore = (now: number = Date.now()) => new Date(now - STALE_AFTER_MS).toISOString()

/** The row of `qb.admin.finance-summary`: the wallets' totals and the transfers that wait for finance. */
export interface FinanceSummary {
  sponsorBalance: string
  sponsorHeld: string
  sponsorPendingCount: number
  sponsorPendingAmount: string
  takerBalance: string
  takerPendingOut: string
  transfersInReview: number
  transfersInDoubt: number
  heldTakers: number
}

export async function readFinanceSummary(): Promise<FinanceSummary> {
  const row = (await runQuery<Row>(TEMPLATE.financeSummary, { params: { staleBefore: staleBefore() }, limit: 1 }))
    .items[0] ?? {}
  return {
    sponsorBalance: amount(row.sponsorBalance),
    sponsorHeld: amount(row.sponsorHeld),
    sponsorPendingCount: num(row.sponsorPendingCount),
    sponsorPendingAmount: amount(row.sponsorPendingAmount),
    takerBalance: amount(row.takerBalance),
    takerPendingOut: amount(row.takerPendingOut),
    transfersInReview: num(row.transfersInReview),
    transfersInDoubt: num(row.transfersInDoubt),
    heldTakers: num(row.heldTakers),
  }
}

/** The row of `qb.admin.recon-summary` (`qb.reconcile` and `task.read`): the latest run and the open money tasks. */
export interface ReconSummary {
  lastRunId: string | null
  lastRunAt: string | null
  lastRunStatus: string | null
  lastRunItems: number
  openTasks: number
}

export async function readReconSummary(): Promise<ReconSummary> {
  const row = (await runQuery<Row>(TEMPLATE.reconSummary, { limit: 1 })).items[0] ?? {}
  return {
    lastRunId: str(row.lastRunId),
    lastRunAt: str(row.lastRunAt),
    lastRunStatus: str(row.lastRunStatus),
    lastRunItems: num(row.lastRunItems),
    openTasks: num(row.openTasks),
  }
}

/** An account's balance now (the platform's `jabiz.ledger.account_balances`: debit less credit). */
export interface AccountBalance {
  code: string
  name: string
  type: string
  balance: string
}

export async function readAccountBalances(): Promise<AccountBalance[]> {
  const page = await runQuery<Row>(TEMPLATE.accountBalances, { params: { asOf: new Date().toISOString() },
    limit: 100 })
  return page.items.map((row) => ({ code: String(row.accountCode ?? ''), name: String(row.accountName ?? ''),
    type: String(row.accountType ?? ''), balance: amount(row.balance) }))
}

/** A row of `qb.admin.transfers` as finance sees it. */
export interface FinanceTransfer {
  transferId: string
  userId: string
  /** The Stripe account the transfer is bound to (only compared, never shown). */
  accountId: string | null
  /** The last four characters of the Stripe account: the page never shows more. */
  accountLast4: string | null
  amount: string
  fee: string
  net: string
  status: string
  approvalRequestId: string | null
  stripeTransferId: string | null
  reversedAmount: string
  failureCode: string | null
  createdAt: string | null
  sentAt: string | null
  stale: boolean
  held: boolean
  releasedAt: string | null
}

function toTransfer(row: Row): FinanceTransfer {
  const account = str(row.externalAccountId)
  return {
    transferId: String(row.transferId),
    userId: String(row.userId ?? ''),
    accountId: account,
    accountLast4: account ? account.slice(-4) : null,
    amount: amount(row.amount),
    fee: amount(row.fee),
    net: amount(row.net),
    status: String(row.status ?? ''),
    approvalRequestId: str(row.approvalRequestId),
    stripeTransferId: str(row.stripeTransferId),
    reversedAmount: amount(row.reversedAmount),
    failureCode: str(row.failureCode),
    createdAt: str(row.createdAt),
    sentAt: str(row.sentAt),
    stale: bool(row.stale),
    held: bool(row.held),
    releasedAt: str(row.releasedAt),
  }
}

/** What the transfer list is filtered by (from the address, so that the overview links to a filtered list). */
export interface TransferFilter {
  status: string | null
  userId: string | null
  transferId: string | null
  /** Created on or after this day (`YYYY-MM-DD`, UTC). */
  from: string | null
  /** Created on or before this day (`YYYY-MM-DD`, UTC). */
  to: string | null
  stale: boolean
  held: boolean
}

/** The first instant of a day (`YYYY-MM-DD`) in UTC, `days` later; null when it is not a day. */
export function dayStart(day: string | null, days = 0): string | null {
  const match = day ? /^(\d{4})-(\d{2})-(\d{2})$/.exec(day) : null
  if (!match) return null
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + days)).toISOString()
}

function periodFilters(field: string, from: string | null, to: string | null): Filter[] {
  const filters: Filter[] = []
  const start = dayStart(from)
  const end = dayStart(to, 1)
  if (start) filters.push({ field, op: 'gte', value: start })
  if (end) filters.push({ field, op: 'lt', value: end })
  return filters
}

export function transferFilters(filter: TransferFilter): Filter[] {
  const filters: Filter[] = []
  if (filter.transferId) filters.push({ field: 'transferId', op: 'eq', value: filter.transferId })
  if (filter.userId) filters.push({ field: 'userId', op: 'eq', value: filter.userId })
  if (filter.status) filters.push({ field: 'status', op: 'eq', value: filter.status })
  if (filter.stale) filters.push({ field: 'stale', op: 'eq', value: true })
  if (filter.held) filters.push({ field: 'held', op: 'eq', value: true })
  return [...filters, ...periodFilters('createdAt', filter.from, filter.to)]
}

/** A page of transfers, newest first, counted. */
export async function readFinanceTransfers(filter: TransferFilter, offset: number, limit: number):
  Promise<Page<FinanceTransfer>> {
  const page = await runQuery<Row>(TEMPLATE.transfers, { params: { staleBefore: staleBefore() },
    filters: transferFilters(filter), offset, limit, count: true })
  return { items: page.items.map(toTransfer), total: page.total }
}

/** One transfer by its key; null when there is none. */
export async function readTransfer(transferId: string): Promise<FinanceTransfer | null> {
  const page = await runQuery<Row>(TEMPLATE.transfers, { params: { staleBefore: staleBefore() },
    filters: [{ field: 'transferId', op: 'eq', value: transferId }], limit: 1 })
  return page.items[0] ? toTransfer(page.items[0]) : null
}

/** A taker's wallet (the cache of 2120 and 2130 for them); null without one. */
export interface TakerWallet {
  balance: string
  pendingOut: string
}

export async function readTakerWallet(userId: string): Promise<TakerWallet | null> {
  const page = await runQuery<Row>(TEMPLATE.wallets, { params: { kind: 'TAKER' },
    filters: [{ field: 'ownerId', op: 'eq', value: userId }], limit: 1 })
  const row = page.items[0]
  return row ? { balance: amount(row.balance), pendingOut: amount(row.pendingOut) } : null
}

/** Lets a held taker's transfers go on. */
export async function releaseHold(userId: string) {
  return runProcess(PROCESS.holdRelease, { userId })
}

/** A row of `qb.admin.topups`. */
export interface FinanceTopUp {
  topUpId: string
  sponsorId: string
  company: string | null
  amount: string
  status: string
  createdAt: string | null
  paidAt: string | null
  paymentIntentId: string | null
  stripeFee: string | null
  netAmount: string | null
  presentmentCurrency: string | null
  presentmentAmount: string | null
  refundedAmount: string
  refundShortfall: string
  disputeStatus: string | null
  disputeClawback: string
  disputeShortfall: string
  checkReason: string | null
}

export interface TopUpFilter {
  status: string | null
  sponsorId: string | null
  topUpId: string | null
  from: string | null
  to: string | null
}

export function topUpFilters(filter: TopUpFilter): Filter[] {
  const filters: Filter[] = []
  if (filter.topUpId) filters.push({ field: 'topUpId', op: 'eq', value: filter.topUpId })
  if (filter.sponsorId) filters.push({ field: 'sponsorId', op: 'eq', value: filter.sponsorId })
  if (filter.status) filters.push({ field: 'status', op: 'eq', value: filter.status })
  return [...filters, ...periodFilters('createdAt', filter.from, filter.to)]
}

export async function readFinanceTopUps(filter: TopUpFilter, offset: number, limit: number):
  Promise<Page<FinanceTopUp>> {
  const page = await runQuery<Row>(TEMPLATE.topUps, { filters: topUpFilters(filter), offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({
      topUpId: String(row.topUpId),
      sponsorId: String(row.sponsorId ?? ''),
      company: str(row.company),
      amount: amount(row.amount),
      status: String(row.status ?? ''),
      createdAt: str(row.createdAt),
      paidAt: str(row.paidAt),
      paymentIntentId: str(row.paymentIntentId),
      stripeFee: str(row.stripeFee),
      netAmount: str(row.netAmount),
      presentmentCurrency: str(row.presentmentCurrency),
      presentmentAmount: str(row.presentmentAmount),
      refundedAmount: amount(row.refundedAmount),
      refundShortfall: amount(row.refundShortfall),
      disputeStatus: str(row.disputeStatus),
      disputeClawback: amount(row.disputeClawback),
      disputeShortfall: amount(row.disputeShortfall),
      checkReason: str(row.checkReason),
    })),
  }
}

/** A run of the daily reconciliation (`qb.admin.recon-runs`). */
export interface ReconRun {
  runId: string
  scheduledTime: string | null
  status: string
  itemCount: number
  checkedAt: string | null
}

export async function readReconRuns(offset: number, limit: number): Promise<Page<ReconRun>> {
  const page = await runQuery<Row>(TEMPLATE.reconRuns, { offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({ runId: String(row.runId), scheduledTime: str(row.scheduledTime),
      status: String(row.status ?? ''), itemCount: num(row.itemCount), checkedAt: str(row.checkedAt) })),
  }
}

/** A difference a run found (`qb.admin.recon-items`). */
export interface ReconItem {
  itemId: string
  checkCode: string
  partyId: string | null
  reference: string | null
  expected: string
  actual: string
  difference: string
}

/** A page of a run's differences, by check (then by how much they differ), counted. */
export async function readReconItems(runId: string, offset: number, limit: number): Promise<Page<ReconItem>> {
  const page = await runQuery<Row>(TEMPLATE.reconItems, { params: { runId },
    sorts: [{ field: 'checkCode', asc: true }, { field: 'difference', asc: false }], offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({ itemId: String(row.itemId), checkCode: String(row.checkCode ?? ''),
      partyId: str(row.partyId), reference: str(row.reference), expected: amount(row.expected),
      actual: amount(row.actual), difference: amount(row.difference) })),
  }
}

/** A report's period: the month's first instant and the next month's, in UTC (the reports' zone). */
export interface Period {
  from: string
  to: string
}

/** The period of a month (`YYYY-MM`); null when it is not one. */
export function monthPeriod(month: string): Period | null {
  const match = /^(\d{4})-(\d{2})$/.exec(month)
  if (!match || Number(match[2]) < 1 || Number(match[2]) > 12) return null
  const year = Number(match[1])
  const index = Number(match[2]) - 1
  return { from: new Date(Date.UTC(year, index, 1)).toISOString(),
    to: new Date(Date.UTC(year, index + 1, 1)).toISOString() }
}

/** The month before the one of `now` (`YYYY-MM`, UTC): the one a monthly report is usually made for. */
export function previousMonth(now: Date = new Date()): string {
  const date = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`
}

/** A row of `qb.admin.monthly`: one kind of transaction in the month. */
export interface MonthlyRow {
  category: string
  transactionCount: number
  amount: string
  /** What the kind moved on each account (debit less credit), by account code. */
  moves: Record<string, string>
}

/** The monthly report's account columns, by account code. */
export const MONTHLY_COLUMNS: Record<string, string> = {
  '1110': 'stripeBalance',
  '1120': 'bank',
  '2110': 'sponsorWallets',
  '2120': 'takerWallets',
  '2130': 'inTransit',
  '4110': 'revenue',
  '5110': 'paymentFees',
}

export async function readMonthly(period: Period): Promise<MonthlyRow[]> {
  const page = await runQuery<Row>(TEMPLATE.monthly, { params: { ...period }, limit: 100 })
  return page.items.map((row) => ({
    category: String(row.category ?? ''),
    transactionCount: num(row.transactionCount),
    amount: amount(row.amount),
    moves: Object.fromEntries(Object.entries(MONTHLY_COLUMNS).map(([code, column]) => [code, amount(row[column])])),
  }))
}

/** A row of `qb.admin.balances`: an account, or a check. */
export interface BalanceRow {
  rowKind: 'ACCOUNT' | 'CHECK'
  code: string
  name: string
  opening: string
  debit: string
  credit: string
  closing: string
  partyTotal: string | null
  matches: boolean | null
}

export async function readBalances(period: Period): Promise<BalanceRow[]> {
  const page = await runQuery<Row>(TEMPLATE.balances, { params: { ...period }, limit: 100 })
  return page.items.map((row) => ({
    rowKind: row.rowKind === 'CHECK' ? 'CHECK' : 'ACCOUNT',
    code: String(row.code ?? ''),
    name: String(row.name ?? ''),
    opening: amount(row.opening),
    debit: amount(row.debit),
    credit: amount(row.credit),
    closing: amount(row.closing),
    partyTotal: str(row.partyTotal),
    matches: row.matches == null ? null : bool(row.matches),
  }))
}

/** The formats the platform exports a report in. */
export const EXPORT_FORMATS = ['csv', 'xlsx', 'pdf'] as const
export type ExportFormat = (typeof EXPORT_FORMATS)[number]

/** The file name of a `Content-Disposition` header (`filename="…"`); null without one. */
export function fileNameOf(disposition: string | null): string | null {
  const match = disposition ? /filename="?([^";]+)"?/i.exec(disposition) : null
  return match ? match[1] : null
}

/**
 * Exports every row of a report for a period through the platform's endpoint
 * (`POST /api/queries/{id}/export?format=`) and hands the file to the browser under the server's name. Throws an
 * `ApiError` when the server refuses (`REPORT_TOO_LARGE` beyond its row limit: never cut short).
 */
export async function exportReport(id: ReportId, period: Period, format: ExportFormat) {
  const response = await sessionFetch(`/api/queries/${encodeURIComponent(id)}/export?format=${format}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ params: period }),
  })
  if (!response.ok) {
    let problem: Record<string, unknown> | undefined
    try {
      problem = (await response.json()) as Record<string, unknown>
    } catch {
      problem = undefined
    }
    throw new ApiError(response.status, problem)
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${id}.${format}`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

/** What `REPORT_ISSUE` answers. */
export interface Issued {
  runId: string
  contentHash: string
  rowCount: number
}

/** Issues a report for a period: the platform archives every row with the content hash (only appended to). */
export async function issueReport(id: ReportId, period: Period, supersedes?: string | null): Promise<Issued> {
  const output = await runProcess<Row>(PROCESS.reportIssue, { templateId: id, params: { ...period },
    ...(supersedes ? { supersedes } : {}) })
  return { runId: String(output.runId), contentHash: String(output.contentHash ?? ''),
    rowCount: num(output.rowCount) }
}

/** An issued run of a report, as the archive lists it. */
export interface IssuedRun {
  runId: string
  period: string | null
  /** The period it was issued for (its parameters). */
  from: string | null
  to: string | null
  rowCount: number
  contentHash: string
  issuedBy: string | null
  issuedTime: string | null
  supersededBy: string | null
}

/** Whether two instants are the same (the archive may write them in another form). */
const sameInstant = (a: string | null, b: string) => a != null && Date.parse(a) === Date.parse(b)

/**
 * The latest issued runs of a report the caller may read (`report.archive.read`) that were issued for the period,
 * newest first: the archive lists the latest runs of the template, each run's own parameters say its period.
 */
export async function readIssuedRuns(id: ReportId, period: Period): Promise<IssuedRun[]> {
  const runs = await unwrap(api.GET('/api/reports/runs', { params: { query: { template: id, limit: 50 } } }))
  const all = await Promise.all((runs ?? []).map(async (run) => {
    const detail = await unwrap(api.GET('/api/reports/runs/{runId}', { params: { path: { runId: String(run.runId) } } }))
    const params = (detail?.params ?? {}) as Row
    return { run, from: str(params.from), to: str(params.to) }
  }))
  return all.filter(({ from, to }) => sameInstant(from, period.from) && sameInstant(to, period.to)).map(({ run, from, to }) => ({
    runId: String(run.runId),
    period: str(run.period),
    from,
    to,
    rowCount: num(run.rowCount),
    contentHash: String(run.contentHash ?? ''),
    issuedBy: str(run.issuedBy),
    issuedTime: str(run.issuedTime),
    supersededBy: str(run.supersededBy),
  }))
}
