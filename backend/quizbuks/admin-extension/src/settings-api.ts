import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { CONTROL, PAGE_LIMIT, PROCESS, TEMPLATE } from './ids'

/*
 * The bank top-up's settings (Q18a; docs/quizbuks/plans/Q18-bank-settings-credit.md): the seven parameters in force
 * (`qb.admin.bank-settings`), the open proposals of them (the platform's controlled changes), proposing all of them in
 * one step (`QB_BANK_SETTINGS_PROPOSE`) and publishing each (`CONTROL_CHANGE_PUBLISH`, another administrator).
 */

type Row = Record<string, unknown>

/** The seven parameters, in the order the page shows them (backend `BankSettings.KEYS`). */
export const BANK_SETTING_KEYS = ['qb.bank.receiving.bank', 'qb.bank.receiving.branch',
  'qb.bank.receiving.account-type', 'qb.bank.receiving.account-number', 'qb.bank.receiving.account-holder',
  'qb.topup.bank.min', 'qb.topup.bank.max'] as const
export type BankSettingKey = (typeof BANK_SETTING_KEYS)[number]

/** The form's field of each parameter (`BankSettings.ProposeInput`). */
export const FIELD_OF: Record<BankSettingKey, keyof BankSettingsInput> = {
  'qb.bank.receiving.bank': 'bank',
  'qb.bank.receiving.branch': 'branch',
  'qb.bank.receiving.account-type': 'accountType',
  'qb.bank.receiving.account-number': 'accountNumber',
  'qb.bank.receiving.account-holder': 'accountHolder',
  'qb.topup.bank.min': 'min',
  'qb.topup.bank.max': 'max',
}

export interface BankSettingsInput {
  bank: string
  branch: string
  accountType: string
  accountNumber: string
  accountHolder: string
  min: string
  max: string
}

/** The value in force of each parameter; null when it has none (not declared yet, or empty). */
export type BankSettings = Record<BankSettingKey, string | null>

export async function readBankSettings(): Promise<BankSettings> {
  const page = await runQuery<Row>(TEMPLATE.bankSettings, { limit: BANK_SETTING_KEYS.length })
  const settings = Object.fromEntries(BANK_SETTING_KEYS.map((key) => [key, null])) as BankSettings
  for (const row of page.items) {
    const key = String(row.paramKey) as BankSettingKey
    if (key in settings && row.paramExists && row.value != null && String(row.value) !== '') {
      settings[key] = String(row.value)
    }
  }
  return settings
}

/** An open proposal of one of the seven parameters. */
export interface OpenProposal {
  changeId: string
  key: BankSettingKey
  value: string | null
  proposedBy: string
  reason: string
}

/** The open proposals of the seven parameters, the setup's first values among them. */
export async function readOpenProposals(): Promise<OpenProposal[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: CONTROL.changes } },
    body: { offset: 0, limit: PAGE_LIMIT, filters: [{ field: 'targetEntity', op: 'eq', value: 'SysParam' },
      { field: 'status', op: 'eq', value: 'PROPOSED' }] },
  }))
  const open: OpenProposal[] = []
  for (const item of answer.items ?? []) {
    const row = (item.attributes ?? {}) as Row
    let values: Row
    try {
      values = JSON.parse(String(row.changeValues ?? '{}')) as Row
    } catch {
      continue
    }
    const key = String(values.paramKey) as BankSettingKey
    if (!(BANK_SETTING_KEYS as readonly string[]).includes(key)) continue
    open.push({ changeId: String(row.changeId ?? item.id), key,
      value: values.value == null || values.value === '' ? null : String(values.value),
      proposedBy: String(row.proposedBy ?? ''), reason: String(row.reason ?? '') })
  }
  return open.sort((a, b) => BANK_SETTING_KEYS.indexOf(a.key) - BANK_SETTING_KEYS.indexOf(b.key))
}

export function proposeBankSettings(input: BankSettingsInput, reason: string) {
  return runProcess<{ changeIds: string[]; keys: string[] }>(PROCESS.bankSettingsPropose, {
    bank: input.bank.trim(), branch: input.branch.trim(), accountType: input.accountType.trim(),
    accountNumber: input.accountNumber.trim(), accountHolder: input.accountHolder.trim(),
    min: Number(input.min.replace(/[,\s]/g, '')), max: Number(input.max.replace(/[,\s]/g, '')), reason: reason.trim(),
  })
}

/** Publishes the proposals one after the other; stops at the first the server refuses. */
export async function publishProposals(changeIds: string[]) {
  for (const changeId of changeIds) {
    await runProcess(CONTROL.publish, { changeId })
  }
}
