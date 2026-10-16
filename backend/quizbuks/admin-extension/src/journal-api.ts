import { ApiError, runProcess, runQuery, sessionFetch } from '@jabiz/admin'
import { fileNameOf, type Page } from './finance-api'
import { PROCESS, TEMPLATE } from './ids'

/*
 * What the journal pages read and run (docs/quizbuks/plans/Q9d-journal-receipts.md), typed by hand after
 * `journal.JournalProcesses` and the templates `qb.admin.journal-*`. The voucher is made by the server only: the page
 * shows the preview's lines and never sums or numbers anything itself. Amounts stay as the server sends them.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))
const list = (value: unknown): string[] => (Array.isArray(value) ? value.map(String) : [])

/** A line of a voucher (`JournalLines.Line`). */
export interface JournalLine {
  lineNo: number
  side: 'DEBIT' | 'CREDIT'
  accountCode: string
  /** The accounting system's code; null while the account is not mapped. */
  externalCode: string | null
  /** The sponsor of a 2110 line. */
  sponsorId: string | null
  amount: string
  transactions: number
}

/** The next export as it would be now (`QB_JOURNAL_PREVIEW`). */
export interface JournalPreview {
  month: string
  periodFrom: string
  periodTo: string
  periodEnded: boolean
  periodEnd: string | null
  previousVoucher: string | null
  knownAt: string | null
  reconStatus: string | null
  reconTime: string | null
  reconOk: boolean
  unmapped: string[]
  invalid: string[]
  lines: JournalLine[]
  transactionCount: number
  debitTotal: string
  creditTotal: string
  balanced: boolean
  /** Whether the export would run now: the page offers it only then (the server checks again). */
  ready: boolean
}

export async function previewJournal(): Promise<JournalPreview> {
  const output = await runProcess<Row>(PROCESS.journalPreview, {})
  return {
    month: String(output.month ?? ''),
    periodFrom: String(output.periodFrom ?? ''),
    periodTo: String(output.periodTo ?? ''),
    periodEnded: output.periodEnded === true,
    periodEnd: str(output.periodEnd),
    previousVoucher: str(output.previousVoucher),
    knownAt: str(output.knownAt),
    reconStatus: str(output.reconStatus),
    reconTime: str(output.reconTime),
    reconOk: output.reconOk === true,
    unmapped: list(output.unmapped),
    invalid: list(output.invalid),
    lines: (Array.isArray(output.lines) ? (output.lines as Row[]) : []).map((line) => ({
      lineNo: num(line.lineNo),
      side: line.side === 'CREDIT' ? 'CREDIT' : 'DEBIT',
      accountCode: String(line.accountCode ?? ''),
      externalCode: str(line.externalCode),
      sponsorId: str(line.sponsorId),
      amount: amount(line.amount),
      transactions: num(line.transactions),
    })),
    transactionCount: num(output.transactionCount),
    debitTotal: amount(output.debitTotal),
    creditTotal: amount(output.creditTotal),
    balanced: output.balanced === true,
    ready: output.ready === true,
  }
}

/** What `QB_JOURNAL_EXPORT` answers. */
export interface Exported {
  exportId: string
  voucherNo: string
  lineCount: number
}

/** Exports the month the preview showed: the server refuses any other (another export came first). */
export async function exportJournal(month: string): Promise<Exported> {
  const output = await runProcess<Row>(PROCESS.journalExport, { month })
  return { exportId: String(output.exportId), voucherNo: String(output.voucherNo ?? ''),
    lineCount: num(output.lineCount) }
}

/** Records the accounting system's voucher number of an export (once). */
export async function confirmJournal(exportId: string, externalVoucherNo: string) {
  return runProcess(PROCESS.journalConfirm, { exportId, externalVoucherNo })
}

/** A row of `qb.admin.journal-exports`. */
export interface JournalExport {
  exportId: string
  periodFrom: string
  periodTo: string
  voucherNo: string
  lineCount: number
  transactionCount: number
  debitTotal: string
  creditTotal: string
  knownAt: string | null
  exportedBy: string | null
  fileId: string
  fileSha256: string
  detailFileId: string
  detailSha256: string
  externalVoucherNo: string | null
  confirmedBy: string | null
}

/** A page of the exports, the newest period first, counted. */
export async function readJournalExports(offset: number, limit: number): Promise<Page<JournalExport>> {
  const page = await runQuery<Row>(TEMPLATE.journalExports, { offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({
      exportId: String(row.exportId),
      periodFrom: String(row.periodFrom ?? ''),
      periodTo: String(row.periodTo ?? ''),
      voucherNo: String(row.voucherNo ?? ''),
      lineCount: num(row.lineCount),
      transactionCount: num(row.transactionCount),
      debitTotal: amount(row.debitTotal),
      creditTotal: amount(row.creditTotal),
      knownAt: str(row.knownAt),
      exportedBy: str(row.exportedBy),
      fileId: String(row.fileId ?? ''),
      fileSha256: String(row.fileSha256 ?? ''),
      detailFileId: String(row.detailFileId ?? ''),
      detailSha256: String(row.detailSha256 ?? ''),
      externalVoucherNo: str(row.externalVoucherNo),
      confirmedBy: str(row.confirmedBy),
    })),
  }
}

/** A row of `qb.admin.journal-mapping`: an account and the accounting system's code now in effect. */
export interface MappingRow {
  accountCode: string
  accountName: string
  paramKey: string
  externalCode: string | null
  /** False until the setup's proposal of the parameter is published. */
  paramExists: boolean
}

export async function readJournalMapping(): Promise<MappingRow[]> {
  const page = await runQuery<Row>(TEMPLATE.journalMapping, { limit: 100 })
  return page.items.map((row) => ({
    accountCode: String(row.accountCode ?? ''),
    accountName: String(row.accountName ?? ''),
    paramKey: String(row.paramKey ?? ''),
    externalCode: str(row.externalCode),
    paramExists: row.paramExists === true || row.paramExists === 'true',
  }))
}

/**
 * Downloads a kept file exactly as it was made (`GET /api/generated-files/{id}`: the platform checks its hash and
 * records the download) and hands it to the browser under the server's name.
 */
export async function downloadGeneratedFile(fileId: string, fallbackName: string) {
  const response = await sessionFetch(`/api/generated-files/${encodeURIComponent(fileId)}`)
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
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? fallbackName
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}
