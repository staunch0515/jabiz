import { runProcess, runQuery } from '@jabiz/admin'
import { AUDIENCE_DIRECT, AUDIENCE_PRICING, AUDIENCE_RULE_KEYS } from './ids'

/*
 * The audience rules and their price lists (Q47c, backend `audience.AudiencePricingProcesses`), typed by hand after
 * the records. Headquarters open, close and charge for the kinds of rule; each owner (headquarters' `direct`, an
 * operator) prices them by the day with a least fee. What a publication pays is the server's alone (its quote).
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => (value == null || value === '' ? 0 : Number(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** `RuleState`: a kind of rule as managed; a kind without a record is open and charged for. */
export interface AudienceRule {
  ruleKey: string
  enabled: boolean
  billable: boolean
  sortOrder: number
  updatedAt: string | null
  updatedBy: string | null
}

export const AUDIENCE_PRICING_KEY = ['qb-ext', 'audience-pricing'] as const

/** Every kind the code knows, each as its record says or at its default, in the managed order. */
export async function readRules(): Promise<AudienceRule[]> {
  const answer = await runQuery<Row>(AUDIENCE_PRICING.rulesTemplate, { limit: 100 })
  const stored = new Map((answer.items ?? []).map((row) => [String(row.ruleKey), row]))
  return rulesOf(stored)
}

export function rulesOf(stored: ReadonlyMap<string, Row>): AudienceRule[] {
  return AUDIENCE_RULE_KEYS.map((ruleKey, i): AudienceRule => {
    const row = stored.get(ruleKey)
    return row
      ? { ruleKey, enabled: bool(row.enabled), billable: bool(row.billable), sortOrder: num(row.sortOrder),
        updatedAt: str(row.updatedAt), updatedBy: str(row.updatedBy) }
      : { ruleKey, enabled: true, billable: true, sortOrder: (i + 1) * 10, updatedAt: null, updatedBy: null }
  }).sort((a, b) => a.sortOrder - b.sortOrder || AUDIENCE_RULE_KEYS.indexOf(a.ruleKey as never)
    - AUDIENCE_RULE_KEYS.indexOf(b.ruleKey as never))
}

export const saveRules = (rules: readonly Pick<AudienceRule, 'ruleKey' | 'enabled' | 'billable' | 'sortOrder'>[]) =>
  runProcess<{ rules: AudienceRule[] }>(AUDIENCE_PRICING.rulesSet, { rules: rules.map((rule) => ({
    ruleKey: rule.ruleKey, enabled: rule.enabled, billable: rule.billable, sortOrder: rule.sortOrder })) })

/** An owner's price list: the least fee and the daily price of each rule (a rule without one costs 0). */
export interface PriceList {
  priceListId: string
  owner: string
  operatorName: string | null
  minimumFee: number
  dailyPrices: Record<string, number>
  reason: string | null
  updatedAt: string | null
  updatedBy: string | null
}

function priceListOf(row: Row, owner: string): PriceList {
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(String(row.prices ?? '{}')) as Record<string, unknown>
  } catch {
    parsed = {}
  }
  return {
    priceListId: String(row.priceListId),
    owner,
    operatorName: str(row.operatorName),
    minimumFee: num(row.minimumFee),
    dailyPrices: Object.fromEntries(Object.entries(parsed).map(([key, value]) => [key, num(value)])),
    reason: str(row.reason),
    updatedAt: str(row.updatedAt),
    updatedBy: str(row.updatedBy),
  }
}

/** Every owner's list (headquarters). */
export async function readPriceLists(): Promise<PriceList[]> {
  const answer = await runQuery<Row>(AUDIENCE_PRICING.pricesTemplate, { params: { owner: null }, limit: 500 })
  return (answer.items ?? []).map((row) => priceListOf(row, String(row.owner)))
}

/** The caller operator's own list; null before its lead saved one. */
export async function readOwnPriceList(): Promise<PriceList | null> {
  const answer = await runQuery<Row>(AUDIENCE_PRICING.operatorPricesTemplate, { limit: 1 })
  const row = (answer.items ?? [])[0]
  return row ? priceListOf(row, '') : null
}

export interface PricesInput {
  minimumFee: number
  /** Every rule priced; a rule left out costs 0. */
  prices: { ruleKey: string; dailyPrice: number }[]
  reason: string
}

/** Saves an owner's whole list (headquarters; `direct` for its own). */
export const savePriceList = (owner: string, input: PricesInput) =>
  runProcess(AUDIENCE_PRICING.pricesSet, { owner: owner === AUDIENCE_DIRECT ? null : owner, ...input })

/** Saves the caller operator's whole list (its lead only: the server refuses the other members). */
export const saveOwnPriceList = (input: PricesInput) =>
  runProcess(AUDIENCE_PRICING.operatorPricesSet, { owner: null, ...input })
