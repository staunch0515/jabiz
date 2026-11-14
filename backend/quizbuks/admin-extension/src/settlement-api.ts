import { ApiError, runProcess, runQuery, sessionFetch } from '@jabiz/admin'
import type { Page } from './api'
import { exportTemplate, fileNameOf, type ExportFormat } from './finance-api'
import { SETTLEMENT_PROCESS, TEMPLATE } from './ids'
import type { SortSpec } from './api'

/*
 * Operators' monthly settlements (backend `operator.OperatorSettlements`, Q48b): finance reads every operator's
 * (`qb.admin.operator-settlements`) and records payments; an operator's member reads its own
 * (`qb.operator.settlements`), without who recorded the payment. The amounts are yen of the ledger. Since Q48e-2 a
 * settlement is credited to the operator's account (`CREDITED`), from which it withdraws (`account-api.ts`); only the
 * older ones (`ISSUED`) are still paid one by one.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const amount = (value: unknown): string => (value == null || value === '' ? '0' : String(value))

export interface Settlement {
  settlementId: string
  operatorId: string
  operatorName: string | null
  month: string
  /** The plan the month was settled on ('1'..'5'; Q48e-2), null before plans. */
  plan: string | null
  /** The plan's fixed fee taken from the share (Q48e-2). */
  fixedFee: string
  /** Q87: the platform's fees charged to the operator's sponsors in the month; the share is on both. */
  platformFees: string
  /** Of the platform fees, the cost and the kinds not shared (#701 review M4). */
  costExcluded: string
  /** F-M12: the part of the fees paid with granted credit (not shared), what was taken back of it, what is carried on. */
  giftPaid: string
  giftRecovered: string
  giftCarriedOut: string
  platformShare: string
  operatorShare: string
  prospectingCost: string
  /** F-M7: what credit notes on the operator's Sponsors' fees took off the month's share. */
  creditNoteDeduction: string
  carriedIn: string
  payable: string
  carriedOut: string
  status: string
  documentNo: string | null
  documentRunId: string | null
  paidOn: string | null
  bankReference: string | null
  approvalRequestId: string | null
  paidAt: string | null
}

function toSettlement(row: Row): Settlement {
  return {
    settlementId: String(row.settlementId),
    operatorId: String(row.operatorId ?? ''),
    operatorName: str(row.operatorName),
    month: String(row.month ?? ''),
    plan: str(row.plan),
    fixedFee: amount(row.fixedFee),
    platformFees: amount(row.platformFees),
    costExcluded: amount(row.costExcluded),
    giftPaid: amount(row.giftPaid),
    giftRecovered: amount(row.giftRecovered),
    giftCarriedOut: amount(row.giftCarriedOut),
    platformShare: amount(row.platformShare),
    operatorShare: amount(row.operatorShare),
    prospectingCost: amount(row.prospectingCost),
    creditNoteDeduction: amount(row.creditNoteDeduction),
    carriedIn: amount(row.carriedIn),
    payable: amount(row.payable),
    carriedOut: amount(row.carriedOut),
    status: String(row.status ?? ''),
    documentNo: str(row.documentNo),
    documentRunId: str(row.documentRunId),
    paidOn: str(row.paidOn),
    bankReference: str(row.bankReference),
    approvalRequestId: str(row.approvalRequestId),
    paidAt: str(row.paidAt),
  }
}

/** The settlements, the newest month first: every operator's for finance, the caller's own for an operator. */
export async function readSettlements(finance: boolean, status: string | null, offset: number,
  limit: number, sorts?: SortSpec[]): Promise<Page<Settlement>> {
  const page = await runQuery<Row>(finance ? TEMPLATE.operatorSettlements : TEMPLATE.ownSettlements, {
    params: finance && status ? { status } : {}, sorts, offset, limit, count: true,
  })
  return { total: page.total, items: page.items.map(toSettlement) }
}

/** Records the bank payment for another administrator's approval (`QB_OPERATOR_SETTLEMENT_PAY`). */
export const paySettlement = (settlementId: string, paidOn: string, bankReference: string) =>
  runProcess<{ settlementId: string; status: string; approvalRequestId: string | null }>(SETTLEMENT_PROCESS.pay,
    { settlementId, paidOn, bankReference })

/** Issues the statement the first time (`QB_OPERATOR_SETTLEMENT_STATEMENT`), or gives the one issued. */
export const issueStatement = (settlementId: string) =>
  runProcess<{ settlementId: string; documentNo: string | null }>(SETTLEMENT_PROCESS.statement, { settlementId })

/** Downloads the statement as issued (the platform's archive, `GET /api/documents/runs/{id}/pdf`). */
export async function downloadStatement(runId: string, documentNo: string | null) {
  const response = await sessionFetch(`/api/documents/runs/${encodeURIComponent(runId)}/pdf`)
  if (!response.ok) throw new ApiError(response.status, undefined)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = fileNameOf(response.headers.get('Content-Disposition')) ?? `${documentNo ?? runId}.pdf`
    link.rel = 'noopener'
    link.click()
  } finally {
    URL.revokeObjectURL(url)
  }
}

export const SETTLEMENTS_KEY = ['qb-ext', 'settlements'] as const

/** The kinds of a settlement's lines: a fee counted, a fee voided, a part of a payment made with granted credit. */
export const LINE_KINDS = ['CHARGE', 'VOID', 'GIFT'] as const

export interface SettlementLine {
  lineId: string
  lineKind: string
  sponsorId: string
  sponsorName: string | null
  feeKind: string | null
  period: string | null
  /** Counted in a later settlement than the month it belongs to. */
  late: boolean
  amount: string
  /** The part of the fee that is not shared (a cost, a kind outside the list). */
  excluded: string
  occurredAt: string | null
}

function toLine(row: Row): SettlementLine {
  return {
    lineId: String(row.lineId),
    lineKind: String(row.lineKind ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    sponsorName: str(row.sponsorName),
    feeKind: str(row.feeKind),
    period: str(row.period),
    late: row.late === true || row.late === 'true',
    amount: amount(row.amount),
    excluded: amount(row.excluded),
    occurredAt: str(row.occurredAt),
  }
}

const linesTemplate = (finance: boolean) => (finance ? TEMPLATE.adminSettlementLines : TEMPLATE.ownSettlementLines)

/** One settlement's lines, the newest first (`qb.admin.settlement-lines`, or the operator's own limited to its scope). */
export async function readSettlementLines(finance: boolean, settlementId: string, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<SettlementLine>> {
  const page = await runQuery<Row>(linesTemplate(finance), { params: { settlementId }, sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toLine) }
}

/** Exports every line of the settlement through the platform's export. */
export const exportSettlementLines = (finance: boolean, settlementId: string, format: ExportFormat) =>
  exportTemplate(linesTemplate(finance), { settlementId }, format)

/** An operator whose earliest month without a settlement is waiting, and the steps it waits for (empty: none). */
export interface WaitingSettlement {
  operatorId: string
  operatorName: string
  month: string
  steps: string[]
}

export const WAITING_STEPS = ['DISTRIBUTION', 'INVOICE', 'EXPOSURE'] as const
export const WAITING_KEY = ['qb-ext', 'settlements-waiting'] as const

/**
 * The operators whose earliest month without a settlement is before the current one, every one (one row each at
 * most); the current month is the server's, in its fiscal year's zone (#701 review M2).
 */
export async function readWaitingSettlements(): Promise<{ current: string; operators: WaitingSettlement[] }> {
  const output = await runProcess<{ current: string; operators: { operatorId: string; operatorName: string | null;
    month: string; steps: string[] }[] }>(SETTLEMENT_PROCESS.waiting, {})
  return {
    current: output.current,
    operators: output.operators.map((row) => ({ operatorId: row.operatorId,
      operatorName: row.operatorName ?? row.operatorId, month: row.month, steps: row.steps })),
  }
}
