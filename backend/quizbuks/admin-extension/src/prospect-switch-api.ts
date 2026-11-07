import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { CONTROL, PAGE_LIMIT, TEMPLATE } from './ids'

/*
 * AI prospecting's platform switch (F-F8, troubleshooting T9): the three controlled parameters every prospecting job
 * is held by (backend `QbParams.PROSPECT_ENABLED` / `_MODEL` / `_EFFORT`), read from `qb.admin.prospect-switch`, their
 * open proposals (the platform's controlled changes), and proposing new values (four eyes, platform decision D40):
 * another administrator publishes them, never a direct write.
 */

type Row = Record<string, unknown>

/** The three parameters, in the order the page shows them. */
export const SWITCH_KEYS = ['qb.prospect.enabled', 'qb.prospect.model', 'qb.prospect.effort'] as const
export type SwitchKey = (typeof SWITCH_KEYS)[number]

/** The values the server accepts (backend `QbParams`: the dictionaries `ai-model` and `ai-effort`). */
export const MODELS = ['claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5'] as const
export const EFFORTS = ['low', 'medium', 'high'] as const

/**
 * When the daily search runs (backend `ProspectConfig.qbProspectFindJobDefinition`, `0 17 23 * * *` in UTC): a time
 * missed (the application down) is not run later. The page shows it in the reader's own zone, never a fixed one.
 */
export const FIND_RUN_UTC = { hour: 23, minute: 17 } as const

/** A parameter's value in force; `exists` is false until the setup's first value is published. */
export interface SwitchSetting {
  key: SwitchKey
  value: string | null
  exists: boolean
}

export async function readSwitch(): Promise<Record<SwitchKey, SwitchSetting>> {
  const page = await runQuery<Row>(TEMPLATE.adminProspectSwitch, { limit: SWITCH_KEYS.length })
  const settings = Object.fromEntries(SWITCH_KEYS.map((key) => [key, { key, value: null, exists: false }])) as
    Record<SwitchKey, SwitchSetting>
  for (const row of page.items) {
    const key = String(row.paramKey) as SwitchKey
    if (!(key in settings)) continue
    const exists = row.paramExists === true || row.paramExists === 'true'
    settings[key] = { key, exists, value: exists && row.value != null && row.value !== '' ? String(row.value) : null }
  }
  return settings
}

/** An open proposal of one of the three. */
export interface SwitchProposal {
  changeId: string
  key: SwitchKey
  value: string | null
  proposedBy: string
  reason: string
}

/** The open proposals of the three, the setup's first values among them (read with `approval.read`). */
export async function readSwitchProposals(): Promise<SwitchProposal[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: CONTROL.changes } },
    body: { offset: 0, limit: PAGE_LIMIT, filters: [{ field: 'targetEntity', op: 'eq', value: 'SysParam' },
      { field: 'status', op: 'eq', value: 'PROPOSED' }] },
  }))
  const open: SwitchProposal[] = []
  for (const item of answer.items ?? []) {
    const row = (item.attributes ?? {}) as Row
    let values: Row
    try {
      values = JSON.parse(String(row.changeValues ?? '{}')) as Row
    } catch {
      continue
    }
    const key = String(values.paramKey) as SwitchKey
    if (!(SWITCH_KEYS as readonly string[]).includes(key)) continue
    open.push({ changeId: String(row.changeId ?? item.id), key,
      value: values.value == null || values.value === '' ? null : String(values.value),
      proposedBy: String(row.proposedBy ?? ''), reason: String(row.reason ?? '') })
  }
  return open.sort((a, b) => SWITCH_KEYS.indexOf(a.key) - SWITCH_KEYS.indexOf(b.key))
}

/**
 * Proposes each of the three whose new value differs from the one in force, one controlled change each, with the
 * same reason; another administrator publishes them. Returns the keys proposed (none when nothing changed).
 */
export async function proposeSwitch(current: Record<SwitchKey, SwitchSetting>,
  wanted: Record<SwitchKey, string>, reason: string): Promise<SwitchKey[]> {
  const changed = SWITCH_KEYS.filter((key) => !current[key].exists || current[key].value !== wanted[key])
  for (const key of changed) {
    await runProcess<{ changeId: string; status: string }>(CONTROL.propose, {
      targetEntity: 'SysParam',
      values: { paramKey: key, value: wanted[key] },
      reason: reason.trim(),
    })
  }
  return changed
}

/** Today's run of the daily search as an instant, for showing in the reader's zone. */
export function findRunToday(now: Date = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), FIND_RUN_UTC.hour,
    FIND_RUN_UTC.minute))
}
