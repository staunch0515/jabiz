import { api, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { DATASET, PAGE_LIMIT, PROCESS, TEMPLATE } from './ids'

/*
 * What the legal texts' page reads and runs (docs/quizbuks/plans/Q7-messages-legal.md, Q7b; review M1 and L1), typed
 * by hand after `messaging.LegalProcesses` and the template `qb.admin.legal-versions`. The drafts are edited on the
 * platform's generic pages; this page publishes them and withdraws announced versions.
 */

type Row = Record<string, unknown>
type Text = Record<string, string>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const text = (value: unknown): Text => (value && typeof value === 'object' ? value as Text : {})

/** The kinds of legal text, in the order the page shows them (the dictionary `legal-kind`). */
export const LEGAL_KINDS = [
  'TERMS_APP', 'TERMS_SPONSOR', 'PRIVACY', 'COMMERCE_NOTICE', 'AI_NOTICE', 'CONTEST_RULES',
] as const

/** A kind's draft (`QbLegalDoc`): what the next version would be, and the draft's version the page read. */
export interface LegalDraft {
  docId: string
  /** The draft's version as read: the publication names it (`baseRevision`), so an edit meanwhile is not published. */
  revision: number
  kind: string
  title: Text
  body: Text
  changeSummary: Text
  latestVersionNo: number | null
}

/** Every kind's draft. */
export async function readLegalDrafts(): Promise<LegalDraft[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: DATASET.legalDoc } },
    body: { offset: 0, limit: PAGE_LIMIT },
  }))
  return (answer.items ?? []).map((item) => {
    const row = (item.attributes ?? {}) as Row
    return {
      docId: String(item.id),
      revision: Number(item.version ?? 0),
      kind: String(row.kind ?? ''),
      title: text(row.title),
      body: text(row.body),
      changeSummary: text(row.changeSummary),
      latestVersionNo: row.latestVersionNo == null ? null : Number(row.latestVersionNo),
    }
  })
}

/** A row of `qb.admin.legal-versions`. */
export interface LegalVersionRow {
  legalVersionId: string
  kind: string
  versionNo: number
  title: Text
  requiresConsent: boolean
  effectiveAt: string
  publishedAt: string | null
  publishedBy: string | null
  withdrawn: boolean
  withdrawnAt: string | null
  withdrawnBy: string | null
  reason: string | null
}

/** Every published version, newest first. */
export async function readLegalVersions(): Promise<LegalVersionRow[]> {
  const answer = await runQuery<Row>(TEMPLATE.legalVersions, { offset: 0, limit: PAGE_LIMIT })
  return answer.items.map((row) => ({
    legalVersionId: String(row.legalVersionId),
    kind: String(row.kind ?? ''),
    versionNo: Number(row.versionNo ?? 0),
    title: text(row.title),
    requiresConsent: row.requiresConsent === true,
    effectiveAt: String(row.effectiveAt ?? ''),
    publishedAt: str(row.publishedAt),
    publishedBy: str(row.publishedBy),
    withdrawn: row.withdrawn === true,
    withdrawnAt: str(row.withdrawnAt),
    withdrawnBy: str(row.withdrawnBy),
    reason: str(row.reason),
  }))
}

/** Publishes a draft as read (`QB_LEGAL_PUBLISH`, with a second factor); none for now, else when it takes effect. */
export async function publishLegal(draft: LegalDraft, effectiveAt: string | null, requiresConsent: boolean,
  announce = false) {
  const input: Row = { docId: draft.docId, requiresConsent, baseRevision: draft.revision }
  if (effectiveAt) input.effectiveAt = effectiveAt
  // Asked for only: an announcement in the inboxes of those the text concerns (Q7c).
  if (announce) input.announce = true
  const output = await runProcess<Row>(PROCESS.legalPublish, input)
  return { versionNo: Number(output.versionNo ?? 0), effectiveAt: String(output.effectiveAt ?? ''),
    announcements: Array.isArray(output.announcements) ? output.announcements.length : 0 }
}

/** Withdraws a version announced for later (`QB_LEGAL_WITHDRAW`, with a second factor and a reason). */
export async function withdrawLegal(legalVersionId: string, reason: string) {
  const output = await runProcess<Row>(PROCESS.legalWithdraw, { legalVersionId, reason })
  return { versionNo: Number(output.versionNo ?? 0) }
}
