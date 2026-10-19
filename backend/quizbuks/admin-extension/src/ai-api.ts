import { runProcess, runQuery } from '@jabiz/admin'
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
