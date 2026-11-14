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

/**
 * The registry's `WAITING` rows whose key does not exist yet, in its order (checked against `FirstLaunchSwitches.ALL`).
 * The waiting rows whose key already exists (`KEY_BEFORE_MEANING`) are in the template instead, see
 * `KEY_BEFORE_MEANING_ROWS`.
 */
export const WAITING_ROWS: readonly WaitingRow[] = [
  { key: 'qb.ai.fee.enabled', group: 'aifee', waitingFor: 'F-M9' },
]

/**
 * The registry's `WAITING` rows whose key already exists (`FirstLaunchSwitches.KEY_BEFORE_MEANING`): the template reads
 * their value in force (the first launch has them off), and the card adds that they wait for their feature. Checked
 * against the registry by `FirstLaunchSwitchesTemplateTest`.
 */
export const KEY_BEFORE_MEANING_ROWS: readonly WaitingRow[] = [
  { key: 'qb.ai.site-profile.enabled', group: 'aifee', waitingFor: 'F-M9' },
]

/**
 * Switches that count as off when they are not set (the Kudos reward is refused without a value, and no reward method
 * is offered): the card says so beside them.
 */
export const NOT_SET_IS_OFF: ReadonlySet<string> = new Set(['qb.reward.kudos.enabled', 'qb.reward.default-methods'])

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

/** A safety stop for the paging below: far more open proposals than any system has. */
const MAX_PROPOSAL_PAGES = 50

/**
 * The open proposals of the given parameters (read with `approval.read`), in the order of the keys. The dataset cannot
 * filter on the parameter key (it is inside the proposal's values), so all open proposals of the platform parameters
 * are read, page after page: a long list of other proposals must not push the switches' own off the first page.
 */
export async function readFundProposals(keys: readonly string[]): Promise<FundProposal[]> {
  const open: FundProposal[] = []
  for (let page = 0; page < MAX_PROPOSAL_PAGES; page++) {
    const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: CONTROL.changes } },
      body: { offset: page * PAGE_LIMIT, limit: PAGE_LIMIT, filters: [{ field: 'targetEntity', op: 'eq', value: 'SysParam' },
        { field: 'status', op: 'eq', value: 'PROPOSED' }] },
    }))
    const items = answer.items ?? []
    for (const item of items) {
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
    if (items.length < PAGE_LIMIT) break
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
 * not there, or its value differs, and a proposal brings it to the first launch's), `proposedDiffers` (the parameter is
 * not there and what waits is not the first launch's value: a system that ran `QB_SETUP` without the production
 * environment proposes the declared values), `differs`, `match`, or `amount` (the declared value stands, so there is
 * nothing to compare; a waiting proposal of an amount is `proposed`).
 *
 * A parameter that is there and differs stays `differs` whatever waits: the value in force is the risk, and a proposal
 * that does not bring it to the first launch's value changes nothing about that.
 */
export type FundState = 'match' | 'differs' | 'proposed' | 'proposedDiffers' | 'missing' | 'amount'

export function stateOf(row: FundSwitch, proposals: readonly FundProposal[]): FundState {
  if (!row.exists) {
    if (proposals.length === 0) return 'missing'
    if (row.expected === null) return 'proposed'
    const expected = row.expected
    return proposals.some((p) => sameValue(expected, p.value)) ? 'proposed' : 'proposedDiffers'
  }
  if (row.expected === null) return 'amount'
  if (sameValue(row.expected, row.value)) return 'match'
  const expected = row.expected
  return proposals.some((p) => sameValue(expected, p.value)) ? 'proposed' : 'differs'
}
