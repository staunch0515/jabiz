import { runProcess, runQuery } from '@jabiz/admin'
import { exportTemplate, type ExportFormat } from './finance-api'
import { CONTROL, PAGE_LIMIT, TEMPLATE } from './ids'

/*
 * What the AI page reads and runs (Q8a; docs/quizbuks/plans/Q8-ai.md), typed by hand after the templates
 * `qb.admin.ai-*`. The page shows the figures as the server gives them; the switch is a controlled change (four eyes,
 * platform decision D40) that another administrator publishes, never a direct write.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** The messages' key of each AI parameter (`ai.params.<key>`). */
export const PARAM_LABELS: Record<string, string> = {
  'qb.ai.enabled': 'enabled',
  'qb.ai.model': 'model',
  'qb.ai.effort': 'effort',
  'qb.ai.sponsor.daily-jobs': 'dailyJobs',
  'qb.ai.sponsor.monthly-tokens': 'monthlyTokens',
  'qb.ai.global.monthly-tokens': 'globalTokens',
  'qb.ai.candidates.expire-days': 'expireDays',
  'qb.ai.price.input-per-mtok-usd': 'priceInput',
  'qb.ai.price.output-per-mtok-usd': 'priceOutput',
}

/** The row of everybody's usage. */
export const EVERYBODY = '*'

/** A row of `qb.admin.ai-settings`. */
export interface AiSetting {
  paramKey: string
  value: string | null
  paramExists: boolean
}

export async function readAiSettings(): Promise<AiSetting[]> {
  const page = await runQuery<Row>(TEMPLATE.aiSettings, { limit: 20 })
  return page.items.map((row) => ({
    paramKey: String(row.paramKey),
    value: str(row.value),
    paramExists: row.paramExists === true || row.paramExists === 'true',
  }))
}

/** A row of `qb.admin.ai-usage`. */
export interface AiUsageRow {
  usageId: string
  sponsorId: string
  company: string | null
  month: string
  jobs: number
  failedJobs: number
  inputTokens: number
  outputTokens: number
  /** Dollars at the price parameters, as text (exact); null while the prices are not set. */
  estimatedUsd: string | null
}

export async function readAiUsage(month: string | null): Promise<AiUsageRow[]> {
  const page = await runQuery<Row>(TEMPLATE.aiUsage, { params: month ? { month } : {}, limit: PAGE_LIMIT })
  return page.items.map((row) => ({
    usageId: String(row.usageId),
    sponsorId: String(row.sponsorId),
    company: str(row.company),
    month: String(row.month),
    jobs: num(row.jobs),
    failedJobs: num(row.failedJobs),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    estimatedUsd: str(row.estimatedUsd),
  }))
}

/** A row of `qb.admin.ai-jobs` (never the candidates). */
export interface AiJobRow {
  aiJobId: string
  sponsorId: string
  /** `QUESTIONS` or (Q8b) `COVER`. */
  kind: string
  status: string
  model: string
  servedModel: string | null
  attempts: number
  candidateCount: number | null
  failureCode: string | null
  failureMessage: string | null
  inputTokens: number
  outputTokens: number
  requestedAt: string
  /** F-M9: how the job is charged (`NONE` or `CREDIT`) and the operator the fee was frozen with; never an amount. */
  feeMode: string | null
  feeOperatorName: string | null
}

export async function readAiJobs(failedOnly: boolean): Promise<AiJobRow[]> {
  const page = await runQuery<Row>(TEMPLATE.aiJobs, { params: failedOnly ? { failedOnly: true } : {}, limit: 50 })
  return page.items.map((row) => ({
    aiJobId: String(row.aiJobId),
    sponsorId: String(row.sponsorId),
    kind: String(row.kind ?? 'QUESTIONS'),
    status: String(row.status),
    model: String(row.model ?? ''),
    servedModel: str(row.servedModel),
    attempts: num(row.attempts),
    candidateCount: row.candidateCount == null ? null : num(row.candidateCount),
    failureCode: str(row.failureCode),
    failureMessage: str(row.failureMessage),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    requestedAt: String(row.requestedAt ?? ''),
    feeMode: str(row.feeMode),
    feeOperatorName: str(row.feeOperatorName),
  }))
}

/** Proposes the switch's new value as a controlled change; another administrator publishes it. */
export function proposeSwitch(on: boolean, reason: string) {
  return runProcess<{ changeId: string; status: string }>(CONTROL.propose, {
    targetEntity: 'SysParam',
    values: { paramKey: 'qb.ai.enabled', value: String(on) },
    reason,
  })
}

/** The share of failed requests, from 0 to 1; null without requests. */
export function failureRate(row: Pick<AiUsageRow, 'jobs' | 'failedJobs'>): number | null {
  return row.jobs > 0 ? row.failedJobs / row.jobs : null
}

/** A row of `qb.admin.ai-fees` (F-M9 b2; amounts are whole yen before tax, as text; the dollars exact). */
export interface AiFeeRow {
  month: string
  sponsorId: string
  company: string | null
  operatorId: string | null
  operatorName: string | null
  jobs: number
  costUsd: string
  feeYen: string
  absorbedYen: string
  unrecordedUsd: string
  absorbedTotalYen: string
  unbilledYen: string
  /** Only when one sponsor was asked for. */
  aiCredit: string | null
}

export interface AiFeeFilter {
  month: string
  /** The last month of the span; empty for the one month. */
  toMonth: string
  sponsorId: string | null
  operatorId: string | null
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const index = (month: string) => Number(month.slice(0, 4)) * 12 + Number(month.slice(5, 7))

/** The longest span the template answers, in months (the server returns no rows beyond it). */
export const AI_FEE_MAX_MONTHS = 12

/** Whether the filter is one the server answers: a month, and a span of at most 12 months that is not reversed. */
export function validAiFeeSpan(filter: Pick<AiFeeFilter, 'month' | 'toMonth'>): boolean {
  if (!MONTH.test(filter.month)) return false
  if (!filter.toMonth) return true
  if (!MONTH.test(filter.toMonth)) return false
  const span = index(filter.toMonth) - index(filter.month)
  return span >= 0 && span < AI_FEE_MAX_MONTHS
}

export function aiFeeParams(filter: AiFeeFilter): Record<string, unknown> {
  return {
    month: filter.month,
    ...(filter.toMonth ? { toMonth: filter.toMonth } : {}),
    ...(filter.sponsorId ? { sponsorId: filter.sponsorId } : {}),
    ...(filter.operatorId ? { operatorId: filter.operatorId } : {}),
  }
}

const text = (value: unknown, fallback = '0'): string => (value == null || value === '' ? fallback : String(value))

export async function readAiFees(filter: AiFeeFilter): Promise<AiFeeRow[]> {
  const page = await runQuery<Row>(TEMPLATE.aiFees, { params: aiFeeParams(filter), limit: PAGE_LIMIT })
  return page.items.map((row) => ({
    month: String(row.month ?? ''),
    sponsorId: String(row.sponsorId ?? ''),
    company: str(row.company),
    operatorId: str(row.operatorId),
    operatorName: str(row.operatorName),
    jobs: num(row.jobs),
    costUsd: text(row.costUsd),
    feeYen: text(row.feeYen),
    absorbedYen: text(row.absorbedYen),
    unrecordedUsd: text(row.unrecordedUsd),
    absorbedTotalYen: text(row.absorbedTotalYen),
    unbilledYen: text(row.unbilledYen),
    aiCredit: row.aiCredit == null || row.aiCredit === '' ? null : String(row.aiCredit),
  }))
}

/** Exports every row of the filter through the platform (`POST /api/queries/qb.admin.ai-fees/export`). */
export const exportAiFees = (filter: AiFeeFilter, format: ExportFormat) =>
  exportTemplate(TEMPLATE.aiFees, aiFeeParams(filter), format)
