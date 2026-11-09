import { api, ApiError, runProcess, runQuery, unwrap } from '@jabiz/admin'
import { HELP, PAGE_LIMIT } from './ids'

/*
 * What the AI help's pages read and run (F-C10c; docs/quizbuks/plans/impl/F-C10.md), typed by hand after
 * `help.HelpProcesses`, `help.HelpMaterialsProcesses` and the templates `qb.admin.help-*`. The server scopes
 * everything: an operator's session reads and answers its own Sponsors' conversations only; headquarters' every one.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** A row of `qb.admin.help-threads`: a conversation handed to a person. */
export interface StaffThread {
  threadId: string
  userId: string
  /** `app` (a Taker's) or `sponsor` (a Sponsor's). */
  entry: string
  /** A Taker's display name or a Sponsor's company. */
  name: string | null
  language: string | null
  status: string
  /** The operator it went to; none: headquarters. */
  operatorId: string | null
  operatorName: string | null
  waitingFor: string | null
  waitingSince: string | null
  escalatedAt: string | null
  closeReason: string | null
  lastActivityAt: string
}

function staffThread(row: Row): StaffThread {
  return {
    threadId: String(row.threadId),
    userId: String(row.userId ?? ''),
    entry: String(row.entry ?? ''),
    name: str(row.name),
    language: str(row.language),
    status: String(row.status ?? ''),
    operatorId: str(row.operatorId),
    operatorName: str(row.operatorName),
    waitingFor: str(row.waitingFor),
    waitingSince: str(row.waitingSince),
    escalatedAt: str(row.escalatedAt),
    closeReason: str(row.closeReason),
    lastActivityAt: String(row.lastActivityAt ?? ''),
  }
}

/** The filter of the list: those still open (the default), those waiting for a person, or every one. */
export type ThreadFilter = 'open' | 'waiting' | 'all'

/** The conversations handed to a person, latest activity first (the page shows those still waiting first). */
export async function readStaffThreads(filter: ThreadFilter): Promise<StaffThread[]> {
  const page = await runQuery<Row>(HELP.threads, {
    params: filter === 'all' ? {} : { open: true },
    filters: filter === 'waiting' ? [{ field: 'waitingFor', op: 'eq', value: 'STAFF' }] : undefined,
    limit: PAGE_LIMIT,
  })
  return page.items.map(staffThread)
}

/** One conversation handed to a person; null when it is not (or not in the caller's scope). */
export async function readStaffThread(threadId: string): Promise<StaffThread | null> {
  const page = await runQuery<Row>(HELP.threads, { filters: [{ field: 'threadId', op: 'eq', value: threadId }],
    limit: 1 })
  return page.items[0] ? staffThread(page.items[0]) : null
}

/** A row of `qb.admin.help-messages`. */
export interface StaffMessage {
  messageId: string
  /** `QUESTION`, `ANSWER` (the AI's), `USER` (to a person) or `STAFF` (a person's reply). */
  kind: string
  body: string | null
  links: string | null
  page: string | null
  jobStatus: string | null
  rating: string | null
  createdAt: string
}

export async function readStaffMessages(threadId: string): Promise<StaffMessage[]> {
  const page = await runQuery<Row>(HELP.messages, { params: { threadId }, limit: 500 })
  return page.items.map((row) => ({
    messageId: String(row.messageId),
    kind: String(row.kind ?? ''),
    body: str(row.body),
    links: str(row.links),
    page: str(row.page),
    jobStatus: str(row.jobStatus),
    rating: str(row.rating),
    createdAt: String(row.createdAt ?? ''),
  }))
}

/** `QB_HELP_REPLY`: a reply (none to close only), and whether the conversation closes after it. */
export const replyHelp = (threadId: string, text: string | null, close: boolean) =>
  runProcess<{ threadId: string; messageId: string | null; status: string }>(HELP.reply, { threadId, text, close })

/** A row of `qb.admin.help-gaps`: a question the AI did not solve, or a conversation handed over. */
export interface HelpGap {
  rowKey: string
  /** `UNSOLVED` (an answer rated "not solved") or `ESCALATED` (handed to a person). */
  reason: string
  threadId: string
  entry: string
  language: string | null
  page: string | null
  question: string | null
  at: string
}

/** The month (`YYYY-MM`) is the deployment's accounting zone's (`jabiz.fiscal-year-zone`); the server splits by it. */
export async function readHelpGaps(month: string): Promise<HelpGap[]> {
  const page = await runQuery<Row>(HELP.gaps, { params: { month }, limit: 500 })
  return page.items.map((row) => ({
    rowKey: String(row.rowKey),
    reason: String(row.reason ?? ''),
    threadId: String(row.threadId ?? ''),
    entry: String(row.entry ?? ''),
    language: str(row.language),
    page: str(row.page),
    question: str(row.question),
    at: String(row.at ?? ''),
  }))
}

/** A row of `qb.admin.help-usage`: everybody's AI help usage of a month, apart from the Sponsors' AI usage. */
export interface HelpUsageRow {
  month: string
  questions: number
  failedJobs: number
  cancelledJobs: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimatedUsd: string | null
  people: number
  monthlyTokens: number
  /** The share of `qb.help.global-monthly-tokens` used (cache reads weighted), in percent; null without a cap. */
  percentUsed: number | null
}

export async function readHelpUsage(month: string | null): Promise<HelpUsageRow[]> {
  const page = await runQuery<Row>(HELP.usage, { params: month ? { month } : {}, limit: 24 })
  return page.items.map((row) => ({
    month: String(row.month),
    questions: num(row.questions),
    failedJobs: num(row.failedJobs),
    cancelledJobs: num(row.cancelledJobs),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    cacheReadTokens: num(row.cacheReadTokens),
    cacheWriteTokens: num(row.cacheWriteTokens),
    estimatedUsd: str(row.estimatedUsd),
    people: num(row.people),
    monthlyTokens: num(row.monthlyTokens),
    percentUsed: row.percentUsed == null ? null : num(row.percentUsed),
  }))
}

/** An entry's usage manual (`QbHelpManual`): the draft as read (its version is the next change's base). */
export interface HelpManual {
  manualId: string
  revision: number
  entry: string
  body: Record<string, string>
  publishedBody: Record<string, string>
  publishedAt: string | null
  sourceHash: string | null
}

const texts = (value: unknown): Record<string, string> =>
  value && typeof value === 'object' ? value as Record<string, string> : {}

export async function readHelpManuals(): Promise<HelpManual[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: HELP.manual } },
    body: { offset: 0, limit: PAGE_LIMIT },
  }))
  return (answer.items ?? []).map((item) => {
    const row = (item.attributes ?? {}) as Row
    return {
      manualId: String(item.id),
      revision: Number(item.version ?? 0),
      entry: String(row.entry ?? ''),
      body: texts(row.body),
      publishedBody: texts(row.publishedBody),
      publishedAt: str(row.publishedAt),
      sourceHash: str(row.sourceHash),
    }
  }).sort((a, b) => a.entry.localeCompare(b.entry))
}

/** A snapshot of an entry's and language's materials (`QbHelpMaterial`): in use, and the latest built. */
export interface HelpMaterial {
  entry: string
  language: string
  bytes: number | null
  builtAt: string | null
  latestBytes: number
  latestAt: string | null
  /** Being stored now (its job runs). */
  pending: boolean
}

export async function readHelpMaterials(): Promise<HelpMaterial[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: HELP.material } },
    body: { offset: 0, limit: PAGE_LIMIT },
  }))
  return (answer.items ?? []).map((item) => {
    const row = (item.attributes ?? {}) as Row
    return {
      entry: String(row.entry ?? ''),
      language: String(row.language ?? ''),
      bytes: row.bytes == null ? null : num(row.bytes),
      builtAt: str(row.builtAt),
      latestBytes: num(row.latestBytes),
      latestAt: str(row.latestAt),
      pending: row.pendingHash != null,
    }
  })
}

/** `QB_HELP_PUBLISH`: the manual as read (its draft must not have changed), or none to rebuild the snapshots only. */
export async function publishHelp(manual: Pick<HelpManual, 'manualId' | 'revision'> | null) {
  const output = await runProcess<Row>(HELP.publish, manual
    ? { manualId: manual.manualId, baseRevision: manual.revision } : {})
  return { limit: num(output.limit), sizes: Array.isArray(output.sizes)
    ? (output.sizes as Row[]).map((s) => ({ entry: String(s.entry), language: String(s.language), bytes: num(s.bytes) }))
    : [] }
}

/**
 * `QB_HELP_MANUAL_LOAD`: the repository's text into the draft. A draft edited here since it was last loaded is
 * refused (`QB_HELP_MANUAL_EDITED`) unless the administrator chose: keep the draft (the repository's change merged by
 * hand) or replace it.
 */
export function loadHelpManual(manual: Pick<HelpManual, 'manualId' | 'revision'>,
  choice: 'keepDraft' | 'replace' | null) {
  return runProcess<{ manualId: string; entry: string; replaced: boolean }>(HELP.load, {
    manualId: manual.manualId, baseRevision: manual.revision,
    ...(choice === 'keepDraft' ? { keepDraft: true } : choice === 'replace' ? { replace: true } : {}),
  })
}

/** Whether a refusal names a rule code. */
export function refusedWith(error: unknown, code: string): boolean {
  return error instanceof ApiError && error.violations.some((v) => v.ruleCode === code)
}

/** Whether the conversation (or the draft) changed meanwhile: a 409, or the manual's own "changed" refusal. */
export function changedMeanwhile(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 409 || refusedWith(error, 'QB_HELP_MANUAL_CHANGED'))
}

/** The languages the help answers in, in the order the pages show them. */
export const HELP_LANGUAGES = ['zh', 'ja', 'en'] as const
