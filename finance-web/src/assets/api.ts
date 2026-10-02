import { api, ApiError, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { queryDataset } from '../journal/api'

/** The finance backend's names (backend/finance, fa/*Entities, *Processes and queries/finance/fa). */
export const DATASETS = {
  asset: 'urn:jabiz:dataset:default:FinAsset',
  assetClass: 'urn:jabiz:dataset:default:FinAssetClass',
  settings: 'urn:jabiz:dataset:default:FinFaSettings',
  run: 'urn:jabiz:dataset:default:FinDepreciationRun',
  change: 'urn:jabiz:dataset:default:FinAssetChange',
  disposal: 'urn:jabiz:dataset:default:FinAssetDisposal',
  usage: 'urn:jabiz:dataset:default:FinAssetUsage',
} as const

export const PROCESSES = {
  run: 'FIN_FA_DEPRECIATION_RUN',
  reverse: 'FIN_FA_DEPRECIATION_REVERSE',
  project: 'FIN_FA_SCHEDULE_PROJECT',
  acquire: 'FIN_FA_ACQUIRE',
} as const

export const QUERIES = {
  register: 'finance.fa.register',
  rollForward: 'finance.fa.roll_forward',
  schedule: 'finance.fa.depreciation_schedule',
} as const

export const IMPORTS = { register: 'finance.fixed_assets' } as const

export const PERMISSIONS = {
  read: 'fin.fa.read',
  maintain: 'fin.fa.maintain',
  run: 'fin.fa.run',
  migration: 'fin.migration',
} as const

type Amount = number | string

export type AssetStatus = 'IN_SERVICE' | 'FULLY_DEPRECIATED' | 'DISPOSED'
export type RunStatus = 'POSTED' | 'REVERSED'

export interface Asset {
  assetId: string
  assetNo: string
  description: string
  classCode?: string | null
  costAccount: string
  cost: Amount
  inServiceDate: string
  method?: string | null
  lifeMonths?: Amount | null
  salvage?: Amount | null
  convention?: string | null
  department?: string | null
  location?: string | null
  custodian?: string | null
  status?: AssetStatus | null
  source?: string | null
  accumulated?: Amount | null
  depreciatedThrough?: string | null
  active: boolean
}

export interface Run {
  runId: string
  runNo: string
  periodKey: string
  round: Amount
  postingDate: string
  total: Amount
  assetCount: Amount
  status: RunStatus
  actor: string
  runTime: string
  reversedBy?: string | null
  reason?: string | null
}

export interface RunOutput {
  runId: string
  runNo: string
  periodKey: string
  posted: boolean
  total: Amount
  assetCount: number
  glNo?: string | null
}

export interface ScheduleRow {
  periodKey: string
  assetNo: string
  source: 'RUN' | 'DISPOSAL'
  documentNo?: string | null
  amount: Amount
  accumulated: Amount
  units?: Amount | null
}

export interface ProjectedMonth {
  periodKey: string
  amount: Amount
  accumulated: Amount
  netBookValue: Amount
}

export interface Projection {
  assetNo: string
  fromPeriod: string
  byUse: boolean
  accumulated: Amount
  months: ProjectedMonth[]
}

/** The runs, latest month first, a reversed round after the round that replaced it. */
export async function loadRuns(): Promise<Run[]> {
  const runs = await queryDataset<Run>(DATASETS.run, [])
  return runs.sort((a, b) => b.periodKey.localeCompare(a.periodKey) || Number(b.round) - Number(a.round))
}

/** The month a run takes next: the one after the latest posted run; unknown before the first run. */
export function nextMonth(runs: Run[]): string | null {
  const latest = runs.find((r) => r.status === 'POSTED')
  if (!latest) return null
  const [year, month] = latest.periodKey.split('-').map(Number)
  return month === 12 ? `${year + 1}-01` : `${year}-${String(month + 1).padStart(2, '0')}`
}

export function runDepreciation(periodKey: string, idempotencyKey?: string) {
  return runProcess<RunOutput>(PROCESSES.run, { periodKey }, { idempotencyKey })
}

export function reverseRun(periodKey: string, reason: string) {
  return runProcess<{ runId: string; runNo: string; glNo?: string | null }>(PROCESSES.reverse,
    { periodKey, reason: reason.trim() })
}

export async function loadAssets(): Promise<Asset[]> {
  const assets = await queryDataset<Asset>(DATASETS.asset, [])
  return assets.sort((a, b) => a.assetNo.localeCompare(b.assetNo))
}

/** The asset by its id; null when there is none. */
export async function loadAsset(assetId: string): Promise<Asset | null> {
  try {
    const instance = await unwrap(api.GET('/api/datasets/{resourceId}/entities/{id}', {
      params: { path: { resourceId: DATASETS.asset, id: assetId } },
    }))
    return instance.attributes as unknown as Asset
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return null
    throw e
  }
}

/** The months taken, oldest first. */
export async function loadSchedule(assetNo: string): Promise<ScheduleRow[]> {
  const answer = await runQuery<ScheduleRow>(QUERIES.schedule, {
    params: { assetNo, fromPeriod: null, toPeriod: null }, limit: 500,
    sorts: [{ field: 'periodKey', asc: true }],
  })
  return answer.items
}

export function project(assetId: string, months = 12) {
  return runProcess<Projection>(PROCESSES.project, { assetId, months })
}
