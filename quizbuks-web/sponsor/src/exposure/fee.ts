import { runProcess, runQuery } from '@jabiz/client'

/** F-M8: a publication's exposure fee and its settlements (`qb.sponsor.publication-exposure` and `-settlements`). */
export const FEE_TEMPLATE = {
  fee: 'qb.sponsor.publication-exposure',
  settlements: 'qb.sponsor.publication-exposure-settlements',
} as const

/** The process that buys more exposures for a running publication (`PublicationExposureTopUp`). */
export const TOP_UP_PROCESS = 'QB_EXPOSURE_TOP_UP'

/** The statuses in which a publication runs (what it can still buy exposures in). */
export const RUNNING_STATUSES: readonly string[] = ['SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT']

type Row = Record<string, unknown>
const num = (value: unknown) => (value == null || value === '' ? 0 : Number(value))
const numOrNull = (value: unknown) => (value == null || value === '' ? null : Number(value))

/** What a publication that bought exposures costs so far; `unit` is empty when it bought none. */
export interface ExposureFee {
  unit: number | null
  purchased: number | null
  /** The exposures counted so far (the settlement charges no more than were bought). */
  counted: number
  settledCount: number
  charged: number
  /** The exposures since the last settlement, before tax; 0 once the last settlement is written. */
  pendingEstimate: number
  settlements: number
  finalSettled: boolean
  /** The share left at which the Sponsor is told it is running low (0: never). */
  lowRatio: number
}

export const feeQuery = (publicationId: string) => ['qb', 'sponsor', 'exposure-fee', publicationId] as const

export async function readExposureFee(publicationId: string): Promise<ExposureFee | null> {
  const page = await runQuery<Row>(FEE_TEMPLATE.fee, { params: { publicationId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    unit: numOrNull(row.unit),
    purchased: numOrNull(row.purchased),
    counted: num(row.counted),
    settledCount: num(row.settledCount),
    charged: num(row.charged),
    pendingEstimate: num(row.pendingEstimate),
    settlements: num(row.settlements),
    finalSettled: row.finalSettled === true,
    lowRatio: num(row.lowRatio),
  }
}

/** A row of the settlements: one month's (or the last one's), with what it counted and charged. */
export interface ExposureSettlement {
  settlementId: string
  period: string
  counted: number
  toCount: number
  unit: number
  amount: number
  tax: number
  total: number
  final: boolean
}

export async function readExposureSettlements(publicationId: string): Promise<ExposureSettlement[]> {
  const page = await runQuery<Row>(FEE_TEMPLATE.settlements, { params: { publicationId }, limit: 120 })
  return page.items.map((row) => ({
    settlementId: String(row.settlementId),
    period: String(row.period),
    counted: num(row.counted),
    toCount: num(row.toCount),
    unit: num(row.unit),
    amount: num(row.amount),
    tax: num(row.tax),
    total: num(row.total),
    final: row.final === true,
  }))
}

/** Whether what is left of the purchase is at or below the share at which the Sponsor is told (never when it is 0). */
export function isRunningLow(fee: ExposureFee): boolean {
  if (fee.purchased == null || fee.purchased <= 0 || fee.lowRatio <= 0) return false
  const left = Math.max(0, fee.purchased - fee.counted)
  return left <= fee.purchased * Math.min(fee.lowRatio, 0.5)
}

export interface TopUpOutput {
  publicationId: string
  purchased: number
  frozen: number
  revision: number
}

/** Buys `additional` more; `expectedPurchased` is what the page saw (the server refuses when it has changed since). */
export const topUpExposure = (publicationId: string, expectedPurchased: number, additional: number) =>
  runProcess<TopUpOutput>(TOP_UP_PROCESS, { publicationId, expectedPurchased, additional })
