import { ApiError, runProcess, runQuery, sessionFetch } from '@jabiz/admin'
import type { Page } from './api'
import { fileNameOf } from './finance-api'
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
  revenue: string
  platformShare: string
  operatorShare: string
  prospectingCost: string
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
    revenue: amount(row.revenue),
    platformShare: amount(row.platformShare),
    operatorShare: amount(row.operatorShare),
    prospectingCost: amount(row.prospectingCost),
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
