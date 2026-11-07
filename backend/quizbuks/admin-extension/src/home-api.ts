import { runQuery, type Filter } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { TEMPLATE } from './ids'

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}

/** The problems of the daily operators' check (`qb.admin.operator-health`, F-S1). */
export type HealthCode = 'NO_DEFAULT_OPERATOR' | 'NO_OPERATOR' | 'DIRECT_SPONSOR' | 'FEW_REVIEWERS'

export interface HealthProblem {
  checkCode: HealthCode | string
  operatorId: string | null
  operatorName: string | null
  sponsorId: string | null
  found: number
}

export const HOME_KEY = ['qb-ext', 'home'] as const

/** Every problem there is now (the template returns few: one per operator or sponsor at fault). */
export async function readHealth(): Promise<HealthProblem[]> {
  const page = await runQuery<Row>(TEMPLATE.operatorHealth, { limit: 200 })
  return page.items.map((row) => ({ checkCode: String(row.checkCode ?? ''), operatorId: str(row.operatorId),
    operatorName: str(row.operatorName), sponsorId: str(row.sponsorId), found: num(row.found) }))
}

/** A row of `qb.admin.operator-workload`: an operator's reviews waiting now and decided since a time. */
export interface Workload {
  operatorId: string
  name: string
  status: string
  defaultForNew: boolean
  pending: number
  decided: number
}

/** The operators' workload, the most waiting first, a page at a time. */
export async function readWorkload(since: string, offset: number, limit: number, sorts?: SortSpec[],
  filters?: Filter[]): Promise<Page<Workload>> {
  const page = await runQuery<Row>(TEMPLATE.operatorWorkload, { params: { since }, filters, sorts, offset, limit,
    count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({ operatorId: String(row.operatorId), name: String(row.name ?? ''),
      status: String(row.status ?? ''), defaultForNew: row.defaultForNew === true || row.defaultForNew === 'true',
      pending: num(row.pending), decided: num(row.decided) })),
  }
}
