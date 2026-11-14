import { runProcess, runQuery } from '@jabiz/admin'
import { EXPOSURE_FEE } from './ids'

/*
 * The exposure fee's prices and settlements (F-M8, backend `fee.ExposurePrices`, `fee.ExposureSettlements`), typed by
 * hand after the records. An operator's price is the yen per exposure of another Sponsor's shared video; the server
 * checks it against the headquarters' range (the parameters qb.exposure.unit.min and .max). What a publication pays is
 * the server's alone.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

export const EXPOSURE_FEE_KEY = ['qb-ext', 'exposure-fee'] as const

/** An operator's price; the unit is null for an operator without one (its Sponsors pay no exposure fee). */
export interface ExposurePrice {
  operatorId: string
  operatorName: string | null
  unit: number | null
  reason: string | null
  updatedAt: string | null
  updatedBy: string | null
}

function priceOf(row: Row): ExposurePrice {
  return {
    operatorId: String(row.operatorId ?? ''),
    operatorName: str(row.operatorName),
    unit: numOrNull(row.unit),
    reason: str(row.reason),
    updatedAt: str(row.updatedAt),
    updatedBy: str(row.updatedBy),
  }
}

/** Every operator, with its price or none (headquarters). */
export async function readExposurePrices(): Promise<ExposurePrice[]> {
  const answer = await runQuery<Row>(EXPOSURE_FEE.pricesTemplate, { limit: 500 })
  return (answer.items ?? []).map(priceOf)
}

/** The headquarters' range as the operator's page shows it: bound by parameter key (a bound not set is missing). */
export type ExposureRange = Partial<Record<string, string>>

/** The caller operator's own price, whether the caller is its lead, and the headquarters' range. */
export async function readOwnExposurePrice(): Promise<{ price: ExposurePrice; lead: boolean;
  range: ExposureRange } | null> {
  const answer = await runQuery<Row>(EXPOSURE_FEE.operatorPriceTemplate, { limit: 1 })
  const row = (answer.items ?? [])[0]
  if (!row) return null
  let range: ExposureRange
  try {
    range = JSON.parse(String(row.range ?? '{}')) as ExposureRange
  } catch {
    range = {}
  }
  return { price: priceOf(row), lead: bool(row.lead), range }
}

/** What a price form sends: the price as typed (at most four decimals) and why. */
export interface ExposurePriceInput {
  unit: string
  reason: string
}

/** Saves an operator's price (headquarters). */
export const saveExposurePrice = (operatorId: string, input: ExposurePriceInput) =>
  runProcess(EXPOSURE_FEE.priceSet, { operatorId, ...input })

/** Saves the caller operator's own price (its lead only: the server refuses the other members). */
export const saveOwnExposurePrice = (input: ExposurePriceInput) =>
  runProcess(EXPOSURE_FEE.operatorPriceSet, { operatorId: null, ...input })

/** A row of `qb.admin.exposure-settlements`: one publication's settlement of one month (or its last). */
export interface ExposureSettlementRow {
  settlementId: string
  publicationId: string
  sponsorId: string
  operatorId: string | null
  period: string
  counted: number
  toCount: number
  unit: number
  amount: number
  tax: number
  total: number
  final: boolean
  settledAt: string | null
}

export interface ExposureSettlementFilter {
  period: string | null
}

/** The template's parameters: the operator's own list has no operator to filter by (its membership says which). */
export const exposureSettlementParams = (filter: ExposureSettlementFilter, own = false) => ({ period: filter.period,
  sponsorId: null, ...(own ? {} : { operatorId: null }), publicationId: null })

/** Every settlement (finance), or the caller operator's own (`own`). */
export async function readExposureSettlements(filter: ExposureSettlementFilter, offset: number, limit: number,
  own = false) {
  const answer = await runQuery<Row>(own ? EXPOSURE_FEE.operatorSettlementsTemplate : EXPOSURE_FEE.settlementsTemplate,
    { params: exposureSettlementParams(filter, own), offset, limit })
  return {
    total: answer.total ?? null,
    items: (answer.items ?? []).map((row): ExposureSettlementRow => ({
      settlementId: String(row.settlementId),
      publicationId: String(row.publicationId),
      sponsorId: String(row.sponsorId),
      operatorId: str(row.operatorId),
      period: String(row.period),
      counted: num(row.counted),
      toCount: num(row.toCount),
      unit: num(row.unit),
      amount: num(row.amount),
      tax: num(row.tax),
      total: num(row.total),
      final: bool(row.final),
      settledAt: str(row.settledAt),
    })),
  }
}

/** A price with at most four decimals as typed, more than 0, or null when the server would not take it. */
export function unitOf(text: string): string | null {
  const trimmed = text.trim().replace(/,/g, '')
  return /^\d{1,8}(\.\d{1,4})?$/.test(trimmed) && Number(trimmed) > 0 ? trimmed : null
}
