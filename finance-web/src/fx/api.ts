import { runProcess } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, fx/FxEntities, Fx*Processes and queries/finance/fx). */
export const DATASETS = {
  settings: 'urn:jabiz:dataset:default:FinFxSettings',
  run: 'urn:jabiz:dataset:default:FinFxRevaluationRun',
  line: 'urn:jabiz:dataset:default:FinFxRevaluationLine',
  rate: 'urn:jabiz:dataset:default:FinExchangeRate',
} as const

export const PROCESSES = {
  revalue: 'FIN_FX_REVALUE',
  simulate: 'FIN_FX_REVALUE_SIMULATE',
} as const

export const QUERIES = {
  items: 'finance.fx.revaluation_items',
  gainsLosses: 'finance.fx.gains_losses',
} as const

export const PERMISSIONS = {
  read: 'fin.master.read',
  run: 'fin.fx.run',
  settings: 'fin.fx.settings',
  ledger: 'fin.journal.read',
} as const

type Amount = number | string

export interface Run {
  runId: string
  runNo: string
  periodKey: string
  revaluationDate: string
  reversalDate: string
  rateType: string
  total: Amount
  lineCount: Amount
  actor: string
  runTime: string
}

export interface RunOutput {
  runId: string
  runNo: string
  periodKey: string
  revaluationDate: string
  reversalDate: string
  total: Amount
  lineCount: number
  created: boolean
}

/** An item computed again beside the run's line of it; the run's side is empty when it has none. */
export interface SimulatedLine {
  kind: 'RECEIVABLE' | 'PAYABLE' | 'BANK'
  documentId: string
  documentNo: string
  currency: string
  openAmount?: Amount | null
  carryingUsd?: Amount | null
  rate?: Amount | null
  revaluedUsd?: Amount | null
  difference?: Amount | null
  originalRate?: Amount | null
  originalDifference?: Amount | null
  change?: Amount | null
}

export interface Simulation {
  periodKey: string
  revaluationDate: string
  runNo?: string | null
  total: Amount
  originalTotal?: Amount | null
  change?: Amount | null
  lines: SimulatedLine[]
}

/** The runs, latest period first. */
export async function loadRuns(): Promise<Run[]> {
  const runs = await queryDataset<Run>(DATASETS.run, [])
  return runs.sort((a, b) => b.periodKey.localeCompare(a.periodKey))
}

/** The month a run would take next: the one after the latest run; unknown before the first. */
export function nextMonth(runs: Run[]): string | null {
  const latest = runs[0]
  if (!latest) return null
  const [year, month] = latest.periodKey.split('-').map(Number)
  return month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, '0')}`
}

export function revalue(periodKey: string, idempotencyKey?: string) {
  return runProcess<RunOutput>(PROCESSES.revalue, { periodKey }, { idempotencyKey })
}

export function simulate(periodKey: string, asRecorded: boolean) {
  return runProcess<Simulation>(PROCESSES.simulate, { periodKey, asRecorded })
}
