import { api, unwrap, type components } from '@jabiz/client'
import type { HistoryVersion } from '../meta/types'

/**
 * The mail texts and the mail layout administrators change (docs/design/18-numbering-approvals-tasks.md section 5.6;
 * decision D72): what the pages read and the processes they run. The server checks every rule again.
 */
export type MailTexts = components['schemas']['TextsResponse']
export type MailTemplateTexts = components['schemas']['TemplateTexts']
export type MailLanguageTexts = components['schemas']['LanguageTexts']
export type MailLayouts = components['schemas']['LayoutsResponse']
export type MailLanguageLayout = components['schemas']['LanguageLayout']
export type MailLayoutValues = Pick<components['schemas']['MailLayout'], 'displayName' | 'logoUrl' | 'logoAlt' | 'footer'>
export type MailPreview = components['schemas']['MailTextPreviewResponse']
export type MailTextState = NonNullable<MailLanguageTexts['state']>

export const MAIL_TEXT_READ = 'mail.text.read'
export const MAIL_TEXT_WRITE = 'mail.text.write'
export const CONTROL_PROPOSE = 'control.propose'
export const APPROVAL_READ = 'approval.read'

export const MAIL_TEXT_DATASET = 'urn:jabiz:dataset:platform:MailText'
export const MAIL_LAYOUT_DATASET = 'urn:jabiz:dataset:platform:MailLayout'
/** The controlled changes: where another administrator publishes a proposed change (its row action). */
export const CONTROL_CHANGE_DATASET = 'urn:jabiz:dataset:platform:SysControlChange'

export const MAIL_TEXT_SAVE = 'MAIL_TEXT_SAVE'
export const MAIL_TEXT_RESET = 'MAIL_TEXT_RESET'
export const MAIL_TEXT_TEST_SEND = 'MAIL_TEXT_TEST_SEND'
export const MAIL_LAYOUT_RESET = 'MAIL_LAYOUT_RESET'
export const MAIL_LAYOUT_TEST_SEND = 'MAIL_LAYOUT_TEST_SEND'
export const CONTROL_CHANGE_PROPOSE = 'CONTROL_CHANGE_PROPOSE'
export const CONTROL_CHANGE_WITHDRAW = 'CONTROL_CHANGE_WITHDRAW'

/** The targets of controlled changes. */
export const MAIL_TEXT_TARGET = 'MailText'
export const MAIL_LAYOUT_TARGET = 'MailLayout'

/** How a controlled change of a mail text names it in the list of changes (`MailTextControlTarget`). */
export function textTargetKey(template: string, locale: string): string {
  return `${template} [${locale}]`
}

/** How long typing must pause before a preview is asked for. */
export const PREVIEW_DEBOUNCE_MS = 400

export function fetchMailTexts(): Promise<MailTexts> {
  return unwrap(api.GET('/api/mail/texts'))
}

export function fetchMailLayouts(): Promise<MailLayouts> {
  return unwrap(api.GET('/api/mail/layouts'))
}

export function previewMailText(template: string, locale: string, subject: string, body: string): Promise<MailPreview> {
  return unwrap(api.POST('/api/mail/texts/preview', { body: { template, locale, subject, body } }))
}

export function previewMailLayout(locale: string, layout: MailLayoutValues): Promise<MailPreview> {
  return unwrap(api.POST('/api/mail/layouts/preview', { body: { locale, layout } }))
}

/** A proposed controlled change, as the list of changes keeps it. */
export interface PendingChange {
  changeId: string
  targetKey: string
  proposedBy: string
  reason: string
  /** The values proposed (the recorded JSON, read). */
  values: Record<string, unknown>
}

async function query(dataset: string, filters: { field: string; value: string }[], limit: number) {
  const result = await unwrap(
    api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: dataset } },
      body: { filters: filters.map((f) => ({ field: f.field, op: 'eq', value: f.value })), limit },
    }),
  )
  return result.items ?? []
}

function parse(json: unknown): Record<string, unknown> {
  if (typeof json !== 'string') return {}
  try {
    const value: unknown = JSON.parse(json)
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** The proposed (not yet published or withdrawn) changes of a target, optionally of one of its keys. */
export async function fetchPendingChanges(targetEntity: string, targetKey?: string): Promise<PendingChange[]> {
  const items = await query(
    CONTROL_CHANGE_DATASET,
    [
      { field: 'targetEntity', value: targetEntity },
      { field: 'status', value: 'PROPOSED' },
      ...(targetKey ? [{ field: 'targetKey', value: targetKey }] : []),
    ],
    200,
  )
  return items.map((item) => {
    const a = (item.attributes ?? {}) as Record<string, unknown>
    return {
      changeId: String(item.id),
      targetKey: String(a.targetKey ?? ''),
      proposedBy: String(a.proposedBy ?? ''),
      reason: String(a.reason ?? ''),
      values: parse(a.changeValues),
    }
  })
}

/**
 * The versions of a mail text or layout, newest first: the instance is found by its key fields, then its history
 * read. None when nothing was ever stored.
 */
export async function fetchVersions(dataset: string, key: Record<string, string>): Promise<HistoryVersion[]> {
  const items = await query(dataset, Object.entries(key).map(([field, value]) => ({ field, value })), 1)
  const id = items[0]?.id
  if (id == null) return []
  const versions = (await unwrap(
    api.GET('/api/datasets/{resourceId}/entities/{id}/history', {
      params: { path: { resourceId: dataset, id: String(id) } },
    }),
  )) as unknown as HistoryVersion[]
  return [...versions].sort((a, b) => b.versionNo - a.versionNo)
}
