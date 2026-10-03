import { ApiError, formatDecimal, runProcess, runQuery, sessionFetch, toDecimal, type Decimal } from '@jabiz/admin'
import { queryDataset } from '../journal/api'
import { add, ZERO } from '../receivables/money'

/** The finance backend's names (backend/finance, report/*Processes and queries/finance/report). */
export const QUERIES = {
  balanceSheet: 'finance.report.balance_sheet',
  incomeStatement: 'finance.report.income_statement',
  equity: 'finance.report.equity',
  cashFlow: 'finance.report.cash_flow',
  lineDetail: 'finance.report.line_detail',
  cashPosition: 'finance.bank.cash_position',
  arAging: 'finance.ar.aging',
  apAging: 'finance.ap.aging',
} as const

export const DATASETS = {
  layoutRow: 'urn:jabiz:dataset:default:FinStatementLayoutRow',
  period: 'urn:jabiz:dataset:default:FinPeriod',
} as const

export const PROCESSES = {
  issueStatement: 'FIN_STATEMENT_ISSUE',
  issueCashFlow: 'FIN_CASH_FLOW_ISSUE',
} as const

export const PERMISSIONS = {
  ledger: 'ledger.read',
  issue: 'report.issue',
} as const

type Amount = number | string

/** A column of a statement and, where it can be drilled into, the span its figure covers. */
export interface StatementColumn {
  key: string
  span?: 'MONTH' | 'QUARTER' | 'YEAR' | 'BALANCE' | 'YEAR_BALANCE'
}

/** One of the statements: its template, parameters, columns and how it is issued. */
export interface Statement {
  kind: StatementKind
  queryId: string
  /** The parameter holding the statement's day. */
  day: 'asOf' | 'through'
  /** The other parameters the page offers. */
  params: string[]
  columns: StatementColumn[]
  /** The layout code its template reads when none is given; none for the cash flow statement. */
  layout?: string
}

export type StatementKind = 'balance-sheet' | 'income-statement' | 'equity' | 'cash-flow'

export const STATEMENTS: Record<StatementKind, Statement> = {
  'balance-sheet': {
    kind: 'balance-sheet', queryId: QUERIES.balanceSheet, day: 'asOf', params: ['layout', 'layoutVersion', 'knownAt'],
    columns: [{ key: 'amount', span: 'BALANCE' }, { key: 'priorMonth' }, { key: 'priorYear' }], layout: 'BS',
  },
  'income-statement': {
    kind: 'income-statement', queryId: QUERIES.incomeStatement, day: 'through',
    params: ['adjustments', 'department', 'location', 'layout', 'layoutVersion', 'knownAt'],
    columns: [{ key: 'month', span: 'MONTH' }, { key: 'quarter', span: 'QUARTER' }, { key: 'yearToDate', span: 'YEAR' },
      { key: 'priorMonth' }, { key: 'priorYearToDate' }],
    layout: 'IS',
  },
  equity: {
    kind: 'equity', queryId: QUERIES.equity, day: 'through', params: ['from', 'layout', 'layoutVersion', 'knownAt'],
    columns: [{ key: 'opening' }, { key: 'netIncome' }, { key: 'otherChanges' }, { key: 'closing', span: 'YEAR_BALANCE' }],
    layout: 'EQ',
  },
  'cash-flow': {
    kind: 'cash-flow', queryId: QUERIES.cashFlow, day: 'through', params: ['from', 'adjustments', 'knownAt'],
    columns: [{ key: 'amount' }],
  },
}

export interface StatementRow {
  seq: Amount
  lineCode: string
  label: string
  kind: string
  [column: string]: unknown
}

export interface LayoutRow {
  layoutCode: string
  version: Amount
  lineCode: string
  accounts?: string | null
}

export interface DetailLine {
  lineKey: string
  kind: 'OPENING' | 'ENTRY'
  accountCode: string
  accountName: string
  postingDate?: string | null
  glNo?: string | null
  source?: string | null
  documentNo?: string | null
  description?: string | null
  memo?: string | null
  amount: Amount
  sourceEntity?: string | null
  sourceId?: string | null
}

/** The rows a page reads at most; a statement or a drill with more is cut, and the page says so. */
export const LIMIT = 500

/** Parameters with the empty ones left out: the server takes a missing one for its default. */
export function given(params: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''))
}

export async function runStatement(statement: Statement, params: Record<string, unknown>) {
  return runQuery<StatementRow>(statement.queryId, { params: given(params), limit: LIMIT })
}

/** The accounts of every row of a layout version (the latest when none is asked), by line code. */
export async function layoutAccounts(layoutCode: string, version?: string): Promise<Map<string, string>> {
  const rows = await queryDataset<LayoutRow>(DATASETS.layoutRow, [{ field: 'layoutCode', op: 'eq', value: layoutCode }])
  const wanted = version ? Number(version) : Math.max(0, ...rows.map((r) => Number(r.version)))
  return new Map(rows.filter((r) => Number(r.version) === wanted && r.accounts).map((r) => [r.lineCode, r.accounts!]))
}

/**
 * The accounts behind a statement row: a layout row's ranges, the account of a line of each account
 * (`OPERATING_EXPENSES.6400`) or of an unmapped one (`UNMAPPED.2150`); none for a heading or a computed line.
 */
export function accountsOf(row: StatementRow, layout: Map<string, string>): string | undefined {
  if (row.kind === 'HEADING') return undefined
  const ranges = layout.get(row.lineCode)
  if (ranges) return ranges
  const dot = row.lineCode.lastIndexOf('.')
  return dot > 0 && layout.has(row.lineCode.slice(0, dot)) || row.lineCode.startsWith('UNMAPPED.')
    ? row.lineCode.slice(dot + 1) : undefined
}

export async function runLineDetail(params: Record<string, unknown>) {
  return runQuery<DetailLine>(QUERIES.lineDetail, { params: given(params), limit: LIMIT })
}

export interface IssueOutput {
  runId: string
  contentHash: string
}

/** Issues a statement through the finance process that checks it first (unmapped or unexplained amounts refused). */
export async function issue(statement: Statement, params: Record<string, unknown>, key: string): Promise<IssueOutput> {
  return statement.kind === 'cash-flow'
    ? runProcess<IssueOutput>(PROCESSES.issueCashFlow, { params: given(params) }, { idempotencyKey: key })
    : runProcess<IssueOutput>(PROCESSES.issueStatement, { templateId: statement.queryId, params: given(params) },
      { idempotencyKey: key })
}

export type ExportFormat = 'pdf' | 'xlsx' | 'csv'

/** Exports a statement through the platform (POST /api/queries/{id}/export) and saves the file. */
export async function exportStatement(queryId: string, format: ExportFormat, params: Record<string, unknown>) {
  const response = await sessionFetch(`/api/queries/${encodeURIComponent(queryId)}/export?format=${format}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ params: given(params) }),
  })
  if (!response.ok) {
    const problem = (await response.json().catch(() => undefined)) as Record<string, unknown> | undefined
    throw new ApiError(response.status, problem ?? { title: response.statusText })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] ?? /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  save(await response.blob(), name ? decodeURIComponent(name.trim()) : `${queryId}.${format}`)
}

function save(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** The dashboard's figures on a day (FIN-RP-021); each from the report it links to. */
export interface Dashboard {
  cash: Amount | null
  receivables: Amount | null
  payables: Amount | null
  revenue: Amount | null
  netIncome: Amount | null
  periodKey: string | null
  periodStatus: string | null
}

/** The exact sum of decimal values (texts or numbers), as a text; the empty ones count as nothing. */
export function sum(values: unknown[]): string {
  return formatDecimal(values.reduce<Decimal>((total, value) => {
    const d = value === null || value === undefined || value === '' ? null : toDecimal(value)
    return d ? add(total, d) : total
  }, ZERO))
}

async function all<Row>(queryId: string, params: Record<string, unknown>): Promise<Row[]> {
  const rows: Row[] = []
  for (let page = 0; page < 20; page++) {
    const answer = await runQuery<Row>(queryId, { params, offset: page * LIMIT, limit: LIMIT })
    rows.push(...answer.items)
    if (answer.items.length < LIMIT) break
  }
  return rows
}

/**
 * Each figure from its own report, read with the user's permissions: one the user may not read (or that fails) is
 * empty, the others still shown.
 */
export async function loadDashboard(day: string): Promise<Dashboard> {
  const [position, receivables, payables, income, periods] = await Promise.allSettled([
    all<{ section: string; bookBalance?: Amount | null }>(QUERIES.cashPosition, { asOf: day }),
    all<{ openAmountUsd?: Amount | null }>(QUERIES.arAging, { agingDate: day }),
    all<{ openAmountUsd?: Amount | null }>(QUERIES.apAging, { agingDate: day }),
    runQuery<StatementRow>(QUERIES.incomeStatement, { params: { through: day }, limit: LIMIT }).then((p) => p.items),
    queryDataset<{ periodKey: string; startDate: string; endDate: string; status: string; adjustment?: boolean;
      opening?: boolean }>(DATASETS.period, []),
  ])
  const value = <T>(result: PromiseSettledResult<T>): T | null => (result.status === 'fulfilled' ? result.value : null)
  const period = value(periods)?.find((p) => !p.adjustment && !p.opening && p.startDate <= day && day <= p.endDate)
  const line = (code: string) => (value(income)?.find((r) => r.lineCode === code)?.month ?? null) as Amount | null
  const cash = value(position)
  const open = (rows: { openAmountUsd?: Amount | null }[] | null) => (rows ? sum(rows.map((r) => r.openAmountUsd)) : null)
  return {
    cash: cash ? sum(cash.filter((r) => r.section === 'ACCOUNT').map((r) => r.bookBalance)) : null,
    receivables: open(value(receivables)),
    payables: open(value(payables)),
    revenue: line('NET_REVENUE'),
    netIncome: line('NET_INCOME'),
    periodKey: period?.periodKey ?? null,
    periodStatus: period?.status ?? null,
  }
}
