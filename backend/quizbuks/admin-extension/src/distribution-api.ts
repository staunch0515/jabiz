import { runProcess, runQuery } from '@jabiz/admin'
import { DISTRIBUTION } from './ids'

/*
 * The distribution fee's prices and settlements (F-M6, backend `fee.DistributionPrices`, `fee.DistributionSettlements`),
 * typed by hand after the records. An operator's price is the rate (yen per start for each Kudos of a publication's
 * highest tier), the floor and ceiling of the price per start and the least deposit; the server checks it against the
 * headquarters' range (the parameters qb.distribution.*). What a publication pays is the server's alone.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : Number(value))
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

export const DISTRIBUTION_KEY = ['qb-ext', 'distribution'] as const

/** An operator's price; the numbers are null for an operator without one (its Sponsors pay no distribution fee). */
export interface DistributionPrice {
  operatorId: string
  operatorName: string | null
  rate: number | null
  unitFloor: number | null
  unitCeiling: number | null
  minimumDeposit: number | null
  reason: string | null
  updatedAt: string | null
  updatedBy: string | null
}

function priceOf(row: Row): DistributionPrice {
  return {
    operatorId: String(row.operatorId ?? ''),
    operatorName: str(row.operatorName),
    rate: numOrNull(row.rate),
    unitFloor: numOrNull(row.unitFloor),
    unitCeiling: numOrNull(row.unitCeiling),
    minimumDeposit: numOrNull(row.minimumDeposit),
    reason: str(row.reason),
    updatedAt: str(row.updatedAt),
    updatedBy: str(row.updatedBy),
  }
}

/** Every operator, with its price or none (headquarters). */
export async function readDistributionPrices(): Promise<DistributionPrice[]> {
  const answer = await runQuery<Row>(DISTRIBUTION.pricesTemplate, { limit: 500 })
  return (answer.items ?? []).map(priceOf)
}

/** The headquarters' range as the operator's page shows it: bound by parameter key (a bound not set is missing). */
export type DistributionRange = Partial<Record<string, string>>

/** The caller operator's own price, whether the caller is its lead, and the headquarters' range. */
export async function readOwnDistributionPrice(): Promise<{ price: DistributionPrice; lead: boolean;
  range: DistributionRange } | null> {
  const answer = await runQuery<Row>(DISTRIBUTION.operatorPriceTemplate, { limit: 1 })
  const row = (answer.items ?? [])[0]
  if (!row) return null
  let range: DistributionRange
  try {
    range = JSON.parse(String(row.range ?? '{}')) as DistributionRange
  } catch {
    range = {}
  }
  return { price: priceOf(row), lead: bool(row.lead), range }
}

/** What a price form sends; the numbers as typed (decimals for the rate and the price per start). */
export interface DistributionPriceInput {
  rate: string
  unitFloor: string
  unitCeiling: string
  minimumDeposit: number
  reason: string
}

/** Saves an operator's price (headquarters). */
export const saveDistributionPrice = (operatorId: string, input: DistributionPriceInput) =>
  runProcess(DISTRIBUTION.priceSet, { operatorId, ...input })

/** Saves the caller operator's own price (its lead only: the server refuses the other members). */
export const saveOwnDistributionPrice = (input: DistributionPriceInput) =>
  runProcess(DISTRIBUTION.operatorPriceSet, { operatorId: null, ...input })

/** A row of `qb.admin.distribution-settlements`: one publication's settlement of one month. */
export interface DistributionSettlementRow {
  settlementId: string
  publicationId: string
  sponsorId: string
  operatorId: string | null
  /** The operator's name (headquarters' list only; the operator's own list has none). */
  operatorName: string | null
  period: string
  starts: number
  unit: number
  atFloor: boolean
  amount: number
  tax: number
  total: number
  final: boolean
  settledAt: string | null
}

export interface SettlementFilter {
  period: string | null
  atFloor: boolean | null
}

/** The template's parameters: the operator's own list has no operator to filter by (its membership says which). */
export const settlementParams = (filter: SettlementFilter, own = false) => ({ period: filter.period, sponsorId: null,
  ...(own ? {} : { operatorId: null }), publicationId: null, atFloor: filter.atFloor })

/** Every settlement (finance), or the caller operator's own (`own`, review L5). */
export async function readDistributionSettlements(filter: SettlementFilter, offset: number, limit: number,
  own = false) {
  const answer = await runQuery<Row>(own ? DISTRIBUTION.operatorSettlementsTemplate : DISTRIBUTION.settlementsTemplate,
    { params: settlementParams(filter, own), offset, limit })
  return {
    total: answer.total ?? null,
    items: (answer.items ?? []).map((row): DistributionSettlementRow => ({
      settlementId: String(row.settlementId),
      publicationId: String(row.publicationId),
      sponsorId: String(row.sponsorId),
      operatorId: str(row.operatorId),
      operatorName: str(row.operatorName),
      period: String(row.period),
      starts: num(row.starts),
      unit: num(row.unit),
      atFloor: bool(row.atFloor),
      amount: num(row.amount),
      tax: num(row.tax),
      total: num(row.total),
      final: bool(row.final),
      settledAt: str(row.settledAt),
    })),
  }
}

/** A number with at most four decimals as typed, or null when the server would not take it. */
export function decimalOf(text: string): string | null {
  const trimmed = text.trim().replace(/,/g, '')
  return /^\d{1,8}(\.\d{1,4})?$/.test(trimmed) ? trimmed : null
}

/** A whole amount as typed, or null. */
export function wholeOf(text: string): number | null {
  const trimmed = text.trim().replace(/,/g, '')
  return /^\d{1,13}$/.test(trimmed) ? Number(trimmed) : null
}

