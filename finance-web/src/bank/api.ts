import { api, ApiError, runProcess, runQuery, sessionFetch, unwrap } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, bank/*Entities, *Processes and queries/finance/bank). */
export const DATASETS = {
  bank: 'urn:jabiz:dataset:default:FinBankAccount',
  settings: 'urn:jabiz:dataset:default:FinBankSettings',
  statement: 'urn:jabiz:dataset:default:FinBankStatement',
  line: 'urn:jabiz:dataset:default:FinStatementLine',
  transfer: 'urn:jabiz:dataset:default:FinBankTransfer',
  rule: 'urn:jabiz:dataset:default:FinBankEntryRule',
  entry: 'urn:jabiz:dataset:default:FinBankEntry',
  reconciliation: 'urn:jabiz:dataset:default:FinBankReconciliation',
} as const

export const PROCESSES = {
  propose: 'FIN_BANK_MATCH_PROPOSE',
  match: 'FIN_BANK_MATCH',
  accept: 'FIN_BANK_MATCH_ACCEPT',
  unmatch: 'FIN_BANK_UNMATCH',
  entry: 'FIN_BANK_ENTRY_FROM_LINE',
  prepare: 'FIN_BANK_REC_PREPARE',
  complete: 'FIN_BANK_REC_COMPLETE',
  withdraw: 'FIN_BANK_REC_WITHDRAW',
  issue: 'FIN_BANK_REC_ISSUE',
  transfer: 'FIN_BANK_TRANSFER_POST',
} as const

export const QUERIES = {
  statementItems: 'finance.bank.statement_items',
  bookItems: 'finance.bank.book_items',
  matchHistory: 'finance.bank.match_history',
  reconciliation: 'finance.bank.reconciliation',
  staleChecks: 'finance.bank.stale_checks',
  cashPosition: 'finance.bank.cash_position',
} as const

export const IMPORTS = {
  csv: 'finance.bank_statement',
  bai2: 'finance.bank_statement_bai2',
  camt053: 'finance.bank_statement_camt053',
  opening: 'finance.bank_opening_items',
} as const

export const PERMISSIONS = {
  read: 'fin.bank.activity.read',
  master: 'fin.master.read',
  reconcile: 'fin.bank.reconcile',
  review: 'fin.bank.rec.review',
  settings: 'fin.bank.settings',
  transfer: 'fin.bank.transfer',
  statementImport: 'fin.bank.statement.import',
  migration: 'fin.migration',
  decide: 'approval.decide',
  archive: 'report.archive.read',
} as const

type Amount = number | string

export type RecStatus = 'PREPARED' | 'SUBMITTED' | 'SIGNED_OFF'
export type BookKind = 'LEDGER' | 'OPENING'
/** The reconciliation template's sections, in the order it gives them. */
export type Section =
  | 'STATEMENT_BALANCE'
  | 'DEPOSIT_IN_TRANSIT'
  | 'OUTSTANDING_PAYMENT'
  | 'ADJUSTED_BANK_BALANCE'
  | 'BOOK_BALANCE'
  | 'NOT_IN_BOOKS'
  | 'DIFFERENCE'
  | 'PREPARED_BY'
  | 'REVIEWED_BY'

export interface BankAccount {
  bankCode: string
  bankName?: string | null
  glAccount: string
}

export interface Statement {
  statementId: string
  bankCode: string
  fromDate?: string | null
  toDate: string
  openingBalance: Amount
  closingBalance: Amount
  lineCount?: Amount | null
  format?: string | null
}

/** An open statement line (finance.bank.statement_items). */
export interface OpenLine {
  lineId: string
  valueDate: string
  bankReference?: string | null
  description?: string | null
  amount: Amount
  typeCode?: string | null
}

/** An open book item (finance.bank.book_items). */
export interface BookItem {
  refKind: BookKind
  refId: string
  itemDate: string
  documentNo?: string | null
  description?: string | null
  amount: Amount
  party?: string | null
  checkNo?: string | null
  runNo?: string | null
}

export interface ProposedItem {
  kind: BookKind
  id: string
  date: string
  amount: Amount
  documentNo?: string | null
  party?: string | null
}

export interface Proposal {
  lineId: string
  valueDate: string
  bankReference?: string | null
  description?: string | null
  amount: Amount
  items: ProposedItem[]
  confidence: number
  reasons: string[]
}

export interface ProposeOutput {
  proposals: Proposal[]
  openLines: number
  openItems: number
}

export interface HistoryRow {
  matchId: string
  action: 'MATCH' | 'UNMATCH'
  reversesMatchId?: string | null
  method?: 'AUTO' | 'MANUAL' | 'ENTRY' | null
  amount?: Amount | null
  confidence?: Amount | null
  reason?: string | null
  actor: string
  actionTime: string
  statementItems?: string | null
  bookItems?: string | null
}

export interface EntryOutput {
  entryId: string
  entryNo: string
  glNo?: string | null
  matchId: string
}

export interface Reconciliation {
  reconciliationId: string
  bankCode: string
  statementDate: string
  statementBalance: Amount
  depositsInTransit: Amount
  outstandingPayments: Amount
  adjustedBalance: Amount
  bookBalance: Amount
  notInBooks: Amount
  difference: Amount
  status: RecStatus
  preparedBy?: string | null
  approvalRequestId?: string | null
  reviewedBy?: string | null
  signedOffTime?: string | null
  reportRunId?: string | null
  reportHash?: string | null
}

/** A row of the reconciliation template. */
export interface RecRow {
  seq: Amount
  section: Section
  itemDate?: string | null
  reference?: string | null
  description?: string | null
  amount?: Amount | null
}

const PAGE = 500

export async function loadBanks(): Promise<BankAccount[]> {
  const banks = await queryDataset<BankAccount>(DATASETS.bank, [])
  return banks.sort((a, b) => a.bankCode.localeCompare(b.bankCode))
}

/** A bank account's statements, latest first. */
export async function loadStatements(bankCode: string): Promise<Statement[]> {
  const statements = await queryDataset<Statement>(DATASETS.statement, [{ field: 'bankCode', op: 'eq', value: bankCode }])
  return statements.sort((a, b) => b.toDate.localeCompare(a.toDate))
}

export function loadOpenLines(bankCode: string) {
  return runQuery<OpenLine>(QUERIES.statementItems, { params: { bankCode, to: null }, limit: PAGE, count: true })
}

export function loadBookItems(bankCode: string) {
  return runQuery<BookItem>(QUERIES.bookItems, { params: { bankCode, to: null }, limit: PAGE, count: true })
}

/** The latest matches and undos first: the ones still to be undone are the recent ones. */
export function loadHistory(bankCode: string) {
  return runQuery<HistoryRow>(QUERIES.matchHistory, { params: { bankCode }, sorts: [{ field: 'actionTime', asc: false }],
    limit: PAGE, count: true })
}

export function propose(bankCode: string): Promise<ProposeOutput> {
  return runProcess<ProposeOutput>(PROCESSES.propose, { bankCode })
}

export function acceptProposals(bankCode: string, proposals: Proposal[], idempotencyKey?: string) {
  return runProcess<{ matched: number }>(PROCESSES.accept, {
    bankCode,
    proposals: proposals.map((p) => ({ lineId: p.lineId, items: p.items.map((i) => ({ kind: i.kind, id: i.id })) })),
  }, { idempotencyKey })
}

export function matchByHand(bankCode: string, lineIds: string[], items: BookItem[], reason: string,
  idempotencyKey?: string) {
  return runProcess<{ matchId: string; amount: Amount }>(PROCESSES.match, {
    bankCode,
    lineIds,
    items: items.map((i) => ({ kind: i.refKind, id: i.refId })),
    reason: reason.trim() || null,
  }, { idempotencyKey })
}

export function unmatch(matchId: string, reason: string) {
  return runProcess<{ undoId: string; matchId: string }>(PROCESSES.unmatch, { matchId, reason: reason.trim() })
}

export function entryFromLine(lineId: string, account?: string | null, idempotencyKey?: string) {
  return runProcess<EntryOutput>(PROCESSES.entry, { lineId, account: account?.trim() || null }, { idempotencyKey })
}

export async function loadReconciliations(bankCode?: string | null): Promise<Reconciliation[]> {
  const recs = await queryDataset<Reconciliation>(DATASETS.reconciliation,
    bankCode ? [{ field: 'bankCode', op: 'eq', value: bankCode }] : [])
  return recs.sort((a, b) => b.statementDate.localeCompare(a.statementDate) || a.bankCode.localeCompare(b.bankCode))
}

export interface LoadedReconciliation {
  rec: Reconciliation
  rows: RecRow[]
}

/** A reconciliation and its rows worked out now (the issued report is the archive's). */
export async function loadReconciliation(reconciliationId: string): Promise<LoadedReconciliation> {
  const instance = await unwrap(api.GET('/api/datasets/{resourceId}/entities/{id}', {
    params: { path: { resourceId: DATASETS.reconciliation, id: reconciliationId } },
  }))
  const rec = instance.attributes as unknown as Reconciliation
  const rows = await runQuery<RecRow>(QUERIES.reconciliation, {
    params: { bankCode: rec.bankCode, statementDate: rec.statementDate, preparedBy: null, reviewedBy: null },
    limit: PAGE,
  })
  return { rec, rows: rows.items ?? [] }
}

export function prepare(bankCode: string, statementDate: string) {
  return runProcess<Reconciliation>(PROCESSES.prepare, { bankCode, statementDate })
}

/** The issued report exactly as archived, as a file. */
export async function downloadReport(runId: string, format: 'pdf' | 'csv'): Promise<{ blob: Blob; fileName?: string }> {
  const response = await sessionFetch(`/api/reports/runs/${encodeURIComponent(runId)}/export?format=${format}`)
  if (!response.ok) {
    const problem = (await response.json().catch(() => undefined)) as Record<string, unknown> | undefined
    throw new ApiError(response.status, problem ?? { title: response.statusText })
  }
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const name = /filename\*=UTF-8''([^;]+)/i.exec(disposition)?.[1] ?? /filename="?([^";]+)"?/i.exec(disposition)?.[1]
  return { blob: await response.blob(), fileName: name ? decodeURIComponent(name.trim()) : undefined }
}

/** Saves a blob as a file in the browser. */
export function save(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  // Some browsers start the download only after the click returns.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
