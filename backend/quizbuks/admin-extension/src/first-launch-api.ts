import { api, runQuery, unwrap } from '@jabiz/admin'
import { CONTROL, PAGE_LIMIT, TEMPLATE } from './ids'

/*
 * The first launch's money switches (F-F10b, docs/quizbuks/plans/impl/F-F10.md section 1.3): one row for each parameter
 * the backend registry `setup.FirstLaunchSwitches` lists. The rows that are active come from the template
 * `qb.admin.first-launch-switches` (the value the first launch proposes, the value in force, whether the parameter
 * exists); the rows that wait for a feature or are set up by hand are below, and `FirstLaunchSwitchesTemplateTest`
 * checks both lists against the registry, so a row added there fails until it is here and in the messages.
 * The card only reads: a value is proposed and published through the controlled change (four eyes).
 */

type Row = Record<string, unknown>

/** A row the registry lists as waiting for a feature: its key may not exist, or does not do what it means yet. */
export interface WaitingRow {
  key: string
  group: string
  waitingFor: string
}

/** The registry's `WAITING` rows, in its order (checked against `FirstLaunchSwitches.ALL`). */
export const WAITING_ROWS: readonly WaitingRow[] = [
  { key: 'qb.distribution.billing.enabled', group: 'distribution', waitingFor: 'F-M6' },
  { key: 'qb.audience.billing.enabled', group: 'audience', waitingFor: 'F-M7' },
  { key: 'qb.exposure.billing.enabled', group: 'exposure', waitingFor: 'F-M8' },
  { key: 'qb.ai.fee.enabled', group: 'aifee', waitingFor: 'F-M9' },
  { key: 'qb.ai.site-profile.enabled', group: 'aifee', waitingFor: 'F-M9' },
  { key: 'qb.fee.auto-pay.enabled', group: 'autopay', waitingFor: 'F-M13' },
]

/** A row the registry lists as set up by hand: not proposed by `QB_SETUP`; not set means off or unlimited. */
export interface ManualRow {
  key: string
  group: string
}

/** The registry's `MANUAL` rows, in its order (checked against `FirstLaunchSwitches.ALL`). */
export const MANUAL_ROWS: readonly ManualRow[] = [
  { key: 'qb.fee-credit.manual.max', group: 'grant' },
  { key: 'qb.tree.grant.max-per-node', group: 'grant' },
]

/** The message id of a parameter's name (`fundSwitch.names.<id>`): i18next reads a dot as a level. */
export const nameId = (key: string) => key.replace(/[.-]/g, '_')

/** An active row: the template's answer. `expected` is null where the declared value stands (an amount). */
export interface FundSwitch {
  key: string
  group: string
  expected: string | null
  value: string | null
  exists: boolean
}

const text = (value: unknown) => (value === null || value === undefined ? null : String(value))

export async function readFundSwitches(): Promise<FundSwitch[]> {
  const page = await runQuery<Row>(TEMPLATE.adminFirstLaunchSwitches, { limit: PAGE_LIMIT })
  return page.items.map((row) => ({
    key: String(row.paramKey),
    group: String(row.grp),
    expected: text(row.expected),
    value: text(row.value),
    exists: row.paramExists === true || row.paramExists === 'true',
  }))
}

/** An open proposal of one of the parameters (the setup's first values among them). */
export interface FundProposal {
  changeId: string
  key: string
  value: string | null
  proposedBy: string
  reason: string
}

/** The open proposals of the given parameters (read with `approval.read`), in the order of the keys. */
export async function readFundProposals(keys: readonly string[]): Promise<FundProposal[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: CONTROL.changes } },
    body: { offset: 0, limit: PAGE_LIMIT, filters: [{ field: 'targetEntity', op: 'eq', value: 'SysParam' },
      { field: 'status', op: 'eq', value: 'PROPOSED' }] },
  }))
  const open: FundProposal[] = []
  for (const item of answer.items ?? []) {
    const row = (item.attributes ?? {}) as Row
    let values: Row
    try {
      values = JSON.parse(String(row.changeValues ?? '{}')) as Row
    } catch {
      continue
    }
    const key = String(values.paramKey)
    if (!keys.includes(key)) continue
    open.push({ changeId: String(row.changeId ?? item.id), key,
      value: values.value == null ? null : String(values.value),
      proposedBy: String(row.proposedBy ?? ''), reason: String(row.reason ?? '') })
  }
  return open.sort((a, b) => keys.indexOf(a.key) - keys.indexOf(b.key))
}

/**
 * Whether the value in force is the one the first launch proposes. A parameter stores its value in its own form (a
 * rate of `0` is `0.0000`, a switch `true`), so two numbers are compared as numbers and two switches without case;
 * nothing stored and an empty text are the same (a list of methods that is empty).
 */
export function sameValue(expected: string, current: string | null): boolean {
  const a = expected.trim()
  const b = (current ?? '').trim()
  if (/^-?\d+(\.\d+)?$/.test(a) && /^-?\d+(\.\d+)?$/.test(b)) return Number(a) === Number(b)
  return a.toLowerCase() === b.toLowerCase()
}

/**
 * How an active row stands: `missing` (no value yet and none proposed), `proposed` (a proposal waits: the parameter is
 * not there, or its value differs and a proposal brings it to the first launch's), `differs`, `match`, or `amount`
 * (the declared value stands, so there is nothing to compare).
 */
export type FundState = 'match' | 'differs' | 'proposed' | 'missing' | 'amount'

export function stateOf(row: FundSwitch, proposals: readonly FundProposal[]): FundState {
  if (!row.exists) return proposals.length > 0 ? 'proposed' : 'missing'
  if (row.expected === null) return 'amount'
  if (sameValue(row.expected, row.value)) return 'match'
  return proposals.some((p) => sameValue(row.expected as string, p.value)) ? 'proposed' : 'differs'
}
