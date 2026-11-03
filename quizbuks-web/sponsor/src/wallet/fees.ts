import { api, fileNameOf, runProcess, runQuery, sessionFetch, toApiError, unwrap } from '@jabiz/client'

/*
 * Q87b: the platform fees' calls, typed by hand after `fee.FeeProcesses` and the sponsor's templates and datasets of
 * backend/quizbuks (docs/quizbuks/plans/Q87-fee-account.md §11). The server decides every payment and refusal; the
 * page shows what it reads. Amounts are whole yen, shown as Kudos are (one to one, D-Q87-3).
 */

export const FEE_PROCESS = {
  /** Pays an invoice, or (without one) what is owed of the months not invoiced yet, from the cash account. */
  pay: 'QB_FEE_PAY',
  /** Issues an invoice's PDF the first time, or gives the one issued before. */
  document: 'QB_FEE_INVOICE_DOCUMENT',
} as const

export const FEE_TEMPLATE = {
  standing: 'qb.sponsor.fee-standing',
} as const

export const FEE_DATASET = {
  invoices: 'urn:jabiz:dataset:sponsor:QbFeeInvoice',
  charges: 'urn:jabiz:dataset:sponsor:QbFeeCharge',
} as const

export type BillingMode = 'PREPAID' | 'POSTPAID'
export type InvoiceStatus = 'PAID' | 'ISSUED' | 'OVERDUE' | 'VOID'
export const UNPAID: readonly InvoiceStatus[] = ['ISSUED', 'OVERDUE']

/** `qb.sponsor.fee-standing`: how the sponsor pays and what they owe now. */
export interface FeeStanding {
  billingMode: BillingMode
  creditLimit: number
  unpaid: number
  overdue: boolean
}

export interface InvoiceRow {
  invoiceId: string
  invoiceNo: string
  period: string
  total: number
  due: number
  status: InvoiceStatus
  issuedAt: string
  dueOn: string | null
}

type Row = Record<string, unknown>
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
export const modeOf = (value: unknown): BillingMode => (value === 'POSTPAID' ? 'POSTPAID' : 'PREPAID')
const statusOf = (value: unknown): InvoiceStatus =>
  value === 'ISSUED' || value === 'OVERDUE' || value === 'VOID' ? value : 'PAID'

export async function readFeeStanding(): Promise<FeeStanding> {
  const row = (await runQuery<Row>(FEE_TEMPLATE.standing, { limit: 1 })).items[0] ?? {}
  return {
    billingMode: modeOf(row.billingMode),
    creditLimit: num(row.creditLimit),
    unpaid: num(row.unpaid),
    overdue: row.overdue === true,
  }
}

export function toInvoice(row: Row): InvoiceRow {
  return {
    invoiceId: String(row.invoiceId),
    invoiceNo: String(row.invoiceNo ?? ''),
    period: String(row.period ?? ''),
    total: num(row.total),
    due: num(row.due),
    status: statusOf(row.status),
    issuedAt: String(row.issuedAt ?? ''),
    dueOn: str(row.dueOn),
  }
}

/** The sponsor's invoices, newest first (the dataset is the caller's own). */
export async function readInvoices(limit = 24): Promise<InvoiceRow[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: FEE_DATASET.invoices } },
    body: { limit, sorts: [{ field: 'issuedAt', asc: false }] },
  }))
  return (page.items ?? []).map((item) => toInvoice((item.attributes ?? {}) as Row))
}

/** What is owed now and not on an unpaid invoice: paid early with {@link payFees} without an invoice. */
export function owedOutsideInvoices(standing: FeeStanding, invoices: InvoiceRow[]): number {
  const invoiced = invoices.filter((invoice) => UNPAID.includes(invoice.status))
    .reduce((sum, invoice) => sum + invoice.due, 0)
  return Math.max(0, standing.unpaid - invoiced)
}

/** `FeeProcesses.PayOutput`. */
export interface PayOutput {
  amount: number
  cashBalance: number
}

export const payFees = (invoiceId: string | null): Promise<PayOutput> =>
  runProcess<PayOutput>(FEE_PROCESS.pay, invoiceId ? { invoiceId } : {})

/** The languages an invoice is issued in; another interface language falls back to English. */
export function invoiceLanguage(language: string | undefined): 'en' | 'zh' | 'ja' {
  const base = (language ?? '').toLowerCase().split('-')[0]
  return base === 'zh' || base === 'ja' ? base : 'en'
}

/** The invoice's PDF: issued the first time, then the same document as kept, handed to the browser. */
export async function downloadInvoice(invoice: { invoiceId: string; invoiceNo: string }, language?: string) {
  const issued = await runProcess<{ runId: string; invoiceNo: string; issued: boolean }>(FEE_PROCESS.document,
    { invoiceId: invoice.invoiceId, language: invoiceLanguage(language) })
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(issued.runId)}/pdf`)
  if (!response.ok) throw await toApiError(response)
  const url = URL.createObjectURL(await response.blob())
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${invoice.invoiceNo}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  return issued
}

export const FEE_QUERY = ['qb', 'sponsor-fees'] as const
