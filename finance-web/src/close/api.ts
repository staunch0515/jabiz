import { runProcess, runQuery, uploadFile } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, close/CloseEntities, close/*Processes, gl/YearCloseProcesses). */
export const DATASETS = {
  period: 'urn:jabiz:dataset:default:FinPeriod',
  fiscalYear: 'urn:jabiz:dataset:default:FinFiscalYear',
  template: 'urn:jabiz:dataset:default:FinCloseTemplate',
  task: 'urn:jabiz:dataset:default:FinCloseTask',
  artifact: 'urn:jabiz:dataset:default:FinCloseArtifact',
  reopen: 'urn:jabiz:dataset:default:FinPeriodReopen',
  settings: 'urn:jabiz:dataset:default:FinCloseSettings',
  yearClose: 'urn:jabiz:dataset:default:FinYearClose',
} as const

export const PROCESSES = {
  start: 'FIN_CLOSE_START',
  check: 'FIN_CLOSE_CHECK',
  complete: 'FIN_CLOSE_TASK_COMPLETE',
  close: 'FIN_PERIOD_CLOSE',
  setState: 'FIN_PERIOD_SET_STATE',
  reopen: 'FIN_PERIOD_REOPEN_REQUEST',
  withdraw: 'FIN_PERIOD_REOPEN_WITHDRAW',
  yearClose: 'FIN_YEAR_CLOSE',
  templateSave: 'FIN_CLOSE_TEMPLATE_SAVE',
  settings: 'FIN_CLOSE_SETTINGS_SET',
} as const

export const QUERIES = {
  overview: 'finance.close.overview',
  exceptions: 'finance.close.exceptions',
  artifacts: 'finance.close.artifacts',
  priorPeriod: 'finance.gl.prior_period_items',
  trialBalance: 'finance.gl.trial_balance',
} as const

export const PERMISSIONS = {
  read: 'fin.period.read',
  task: 'fin.close.task',
  close: 'fin.period.close',
  reopen: 'fin.period.reopen.request',
  /** The exceptions report needs all three. */
  exceptions: ['fin.journal.read', 'fin.ar.read', 'fin.ap.read'],
} as const

export const EVIDENCE_FILES = 'fin.close.evidence'

type Amount = number | string

export interface Period {
  periodId: string
  periodKey: string
  fiscalYear: Amount
  periodNo: Amount
  adjustment: boolean
  opening: boolean
  startDate: string
  endDate: string
  status: 'OPEN' | 'SOFT_CLOSED' | 'CLOSED'
}

export type Section = 'PROGRESS' | 'TASK' | 'SUBLEDGER' | 'RECONCILIATION'

/** A row of the close overview (FIN-PC-009), in its order: progress, tasks open first, subledgers, reconciliations. */
export interface OverviewRow {
  rank: Amount
  /** A task row's task. */
  taskId?: string | null
  section: Section
  code: string
  name?: string | null
  kind?: 'MANUAL' | 'AUTO' | null
  status: string
  required?: boolean | null
  owner?: string | null
  dueDate?: string | null
  doneBy?: string | null
  doneAt?: string | null
  done?: Amount | null
  total?: Amount | null
  detail?: string | null
}

export interface Exception {
  checkCode: string
  itemId: string
  reference: string
  description: string
  itemDate?: string | null
  amount?: Amount | null
}

export interface Artifact {
  artifactId: string
  periodKey: string
  seq: Amount
  closedBy: string
  closedAt: string
  totalDebit: Amount
  totalCredit: Amount
  trialBalanceHash: string
  contentHash: string
  reportRunId?: string | null
  supersedes?: string | null
  supersededBy?: string | null
}

export interface Reopen {
  reopenId: string
  periodKey: string
  reason: string
  requestedBy: string
  requestedAt: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN' | 'LAPSED'
  decidedBy?: string | null
  decidedAt?: string | null
}

export interface CloseOutput {
  periodKey: string
  status: string
  artifactId: string
  seq: number
  reportRunId?: string | null
}

export interface YearOutput {
  fiscalYear: number
  seq: number
  journalNo?: string | null
  reversalNo?: string | null
  netIncome: Amount
  artifactId: string
}

export interface ReopenOutput {
  reopenId: string
  periodKey: string
  status: string
  periodStatus: string
}

const LIMIT = 500

/** The periods a close is about: every one but the opening period, in order. */
export async function loadPeriods(): Promise<Period[]> {
  const periods = await queryDataset<Period>(DATASETS.period, [])
  return periods.filter((p) => !p.opening).sort((a, b) => a.periodKey.localeCompare(b.periodKey))
}

/** The period to show first: the earliest regular period not closed, else the latest. */
export function currentPeriod(periods: Period[]): string | null {
  return (periods.find((p) => !p.adjustment && p.status !== 'CLOSED') ?? periods[periods.length - 1])?.periodKey
    ?? null
}

export async function loadOverview(periodKey: string): Promise<OverviewRow[]> {
  return (await runQuery<OverviewRow>(QUERIES.overview, { params: { periodKey }, limit: LIMIT })).items
}

export async function loadExceptions(periodKey: string): Promise<Exception[]> {
  return (await runQuery<Exception>(QUERIES.exceptions, { params: { periodKey }, limit: LIMIT })).items
}

export async function loadArtifacts(periodKey: string): Promise<Artifact[]> {
  return (await runQuery<Artifact>(QUERIES.artifacts, { params: { periodKey }, limit: LIMIT })).items
}

export async function loadReopens(periodKey: string): Promise<Reopen[]> {
  const reopens = await queryDataset<Reopen>(DATASETS.reopen,
    [{ field: 'periodKey', op: 'eq', value: periodKey }])
  return reopens.sort((a, b) => b.requestedAt.localeCompare(a.requestedAt))
}

export function startClose(periodKey: string) {
  return runProcess(PROCESSES.start, { periodKey })
}

export function runChecks(periodKey: string) {
  return runProcess(PROCESSES.check, { periodKey })
}

export async function completeTask(taskId: string, note: string, evidence: File | null) {
  const evidenceFileId = evidence ? (await uploadFile(EVIDENCE_FILES, evidence, evidence.name)).fileId : undefined
  return runProcess(PROCESSES.complete, { taskId, note: note || undefined, evidenceFileId })
}

export function softClose(periodKey: string) {
  return runProcess(PROCESSES.setState, { periodKey, status: 'SOFT_CLOSED' })
}

export function closePeriod(periodKey: string, idempotencyKey?: string) {
  return runProcess<CloseOutput>(PROCESSES.close, { periodKey }, { idempotencyKey })
}

export function requestReopen(periodKey: string, reason: string) {
  return runProcess<ReopenOutput>(PROCESSES.reopen, { periodKey, reason })
}

export function withdrawReopen(reopenId: string) {
  return runProcess<ReopenOutput>(PROCESSES.withdraw, { reopenId })
}

export function closeYear(fiscalYear: number, idempotencyKey?: string) {
  return runProcess<YearOutput>(PROCESSES.yearClose, { fiscalYear }, { idempotencyKey })
}
