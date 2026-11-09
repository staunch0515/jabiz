import { ApiError, runProcess, runQuery } from '@jabiz/client'
import { contentLanguageOf } from './api'
import { HELP, HELP_CODE } from './ids'

/*
 * The AI help's calls and rules of display (F-C10c, docs/quizbuks/plans/impl/F-C10.md), typed by hand after
 * `help.HelpProcesses` and the templates `qb.me.help-*`. The server decides everything (the switch, the limits, whose
 * conversation it is, which links stay); the pages only show it and ask again while an answer is on its way.
 */

/** The front end the help is asked from: the Takers' app or the Sponsors' pages. */
export type HelpEntry = 'app' | 'sponsor'

/** A row of `qb.me.help-settings`. */
export interface HelpSettings {
  enabled: boolean
  /** How often the pages ask whether the answer came. */
  pollSeconds: number
  /** How long they ask before saying it takes longer than usual. */
  pollMaxMinutes: number
  userDaily: number
  /** The longest question (characters) the server takes. */
  questionMaxLength: number
}

export type ThreadStatus = 'OPEN' | 'ESCALATED' | 'CLOSED'

/** A row of `qb.me.help-threads`. */
export interface HelpThread {
  threadId: string
  status: ThreadStatus
  escalated: boolean
  /** `STAFF` (a person answers next) or `USER` (the person replied), while handed over. */
  waitingFor: string | null
  resolved: boolean | null
  closeReason: string | null
  createdAt: string
  lastActivityAt: string
  firstQuestion: string | null
}

export type MessageKind = 'QUESTION' | 'ANSWER' | 'USER' | 'STAFF'

/** A row of `qb.me.help-messages`. */
export interface HelpMessage {
  messageId: string
  kind: MessageKind
  body: string | null
  /** The site's paths, one a line (answers only). */
  links: string | null
  /** A question's job: `QUEUED`, `ANSWERED`, `FAILED` or `CANCELLED`. */
  jobStatus: string | null
  failureCode: string | null
  rating: 'SOLVED' | 'UNSOLVED' | null
  createdAt: string
}

/** `HelpProcesses.AskOutput`. */
export interface AskOutput {
  threadId: string
  messageId: string
  status: ThreadStatus
  /** `QUEUED` when the AI was asked; null when the words went to the person the conversation was handed to. */
  jobStatus: string | null
}

/** `HelpProcesses.ThreadOutput`. */
export interface ThreadOutput {
  threadId: string
  messageId: string | null
  status: ThreadStatus
}

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown, fallback: number): number => {
  const n = Number(value)
  return value != null && Number.isFinite(n) ? n : fallback
}

/** The defaults the server declares, until (or unless) it answers. */
export const DEFAULT_HELP_SETTINGS: HelpSettings = { enabled: false, pollSeconds: 3, pollMaxMinutes: 3,
  userDaily: 20, questionMaxLength: 2000 }

export const HELP_QUERY = ['qb', 'help'] as const

export async function readHelpSettings(): Promise<HelpSettings> {
  const row = (await runQuery<Row>(HELP.settings, { limit: 1 })).items[0] ?? {}
  const d = DEFAULT_HELP_SETTINGS
  return {
    enabled: row.enabled === true,
    pollSeconds: Math.max(1, num(row.pollSeconds, d.pollSeconds)),
    pollMaxMinutes: Math.max(1, num(row.pollMaxMinutes, d.pollMaxMinutes)),
    userDaily: num(row.userDaily, d.userDaily),
    questionMaxLength: Math.max(1, num(row.questionMaxLength, d.questionMaxLength)),
  }
}

function thread(row: Row): HelpThread {
  return {
    threadId: String(row.threadId),
    status: (str(row.status) ?? 'OPEN') as ThreadStatus,
    escalated: row.escalated === true,
    waitingFor: str(row.waitingFor),
    resolved: typeof row.resolved === 'boolean' ? row.resolved : null,
    closeReason: str(row.closeReason),
    createdAt: String(row.createdAt ?? ''),
    lastActivityAt: String(row.lastActivityAt ?? ''),
    firstQuestion: str(row.firstQuestion),
  }
}

/** The caller's conversations of the entry, newest first. */
export async function readHelpThreads(entry: HelpEntry, limit = 50): Promise<HelpThread[]> {
  return (await runQuery<Row>(HELP.threads, { params: { entry }, limit })).items.map(thread)
}

/** One of the caller's conversations of the entry; null when it is not (or no longer) theirs there. */
export async function readHelpThread(entry: HelpEntry, threadId: string): Promise<HelpThread | null> {
  const page = await runQuery<Row>(HELP.threads, { params: { entry },
    filters: [{ field: 'threadId', op: 'eq', value: threadId }], limit: 1 })
  return page.items[0] ? thread(page.items[0]) : null
}

/** A conversation's messages, oldest first. */
export async function readHelpMessages(threadId: string): Promise<HelpMessage[]> {
  const page = await runQuery<Row>(HELP.messages, { params: { threadId }, limit: 500 })
  return page.items.map((row) => ({
    messageId: String(row.messageId),
    kind: String(row.kind) as MessageKind,
    body: str(row.body),
    links: str(row.links),
    jobStatus: str(row.jobStatus),
    failureCode: str(row.failureCode),
    rating: row.rating === 'SOLVED' || row.rating === 'UNSOLVED' ? row.rating : null,
    createdAt: String(row.createdAt ?? ''),
  }))
}

/** The help's language: the interface's (Japanese for another). */
export const helpLanguage = (language: string | undefined) => contentLanguageOf(language)

export interface AskInput {
  threadId: string | null
  entry: HelpEntry
  language: string
  /** A name of the entry's list (`help.HelpPages`) or null: never a path. */
  page: string | null
  text: string
}

export const askHelp = (input: AskInput) => runProcess<AskOutput>(HELP.ask, { ...input })

export const rateHelp = (threadId: string, messageId: string, rating: 'SOLVED' | 'UNSOLVED') =>
  runProcess<ThreadOutput>(HELP.rate, { threadId, messageId, rating })

/** Hands the conversation (or, with none, the text) to a person. */
export const escalateHelp = (input: AskInput) => runProcess<ThreadOutput>(HELP.escalate, {
  ...input, text: input.text.trim() === '' ? null : input.text })

export const closeHelp = (threadId: string) => runProcess<ThreadOutput>(HELP.close, { threadId })

/** The last question of a conversation, if its answer is still on its way. */
export function pendingQuestion(messages: readonly HelpMessage[] | undefined): HelpMessage | null {
  const last = [...(messages ?? [])].reverse().find((m) => m.kind === 'QUESTION')
  return last && last.jobStatus === 'QUEUED' ? last : null
}

/**
 * What a refusal means to the pages: the help switched off, a limit reached (each with a person offered instead),
 * another change first (409: read again, keep what was typed), or anything else (the server's words).
 */
export type HelpProblem = 'off' | 'limit' | 'conflict' | 'waiting' | 'closed' | 'other'

export function helpProblem(error: unknown): HelpProblem {
  if (!(error instanceof ApiError)) return 'other'
  if (error.status === 409) return 'conflict'
  const codes = error.violations.map((v) => v.ruleCode)
  if (codes.includes(HELP_CODE.disabled)) return 'off'
  if (codes.includes(HELP_CODE.dailyLimit) || codes.includes(HELP_CODE.monthlyLimit)) return 'limit'
  if (codes.includes(HELP_CODE.waiting)) return 'waiting'
  if (codes.includes(HELP_CODE.closed) || codes.includes(HELP_CODE.alreadyEscalated)) return 'closed'
  return 'other'
}

/** A link of an answer: the site's path, and the path within this SPA when it is one of its pages. */
export interface HelpLink {
  href: string
  /** The page of this SPA (`/wallet`), followed without reloading; null for another part of the site. */
  inApp: string | null
}

/**
 * The links of an answer that are the site's own paths (the server keeps no other; this keeps the page safe even so):
 * a path from the root, never another host (`//`), a scheme or a backslash.
 */
export function siteLinks(links: string | null | undefined, base: string): HelpLink[] {
  const root = base.endsWith('/') ? base : `${base}/`
  const found: HelpLink[] = []
  for (const line of (links ?? '').split('\n')) {
    const path = line.trim()
    if (!/^\/(?![/\\])[^\s\\]*$/.test(path) || /[<>"']/.test(path)) continue
    if (found.some((link) => link.href === path)) continue
    const inApp = root === '/' ? path : path === root.slice(0, -1) ? '/'
      : path.startsWith(root) ? `/${path.slice(root.length)}` : null
    found.push({ href: path, inApp })
  }
  return found
}

/**
 * An answer as plain text: Markdown's link syntax becomes its words (the links are listed under it) and emphasis
 * marks go; nothing is ever rendered as markup.
 */
export function plainAnswer(body: string | null | undefined): string {
  return (body ?? '')
    .replace(/!?\[([^\]\n]*)\]\(([^)\s]*)\)/g, (_, label: string) => label)
    .replace(/(\*\*|__)(.+?)\1/g, '$2')
    .replace(/^#{1,6}\s+/gm, '')
}

/** Where a conversation's link (an inbox message's) points in an SPA: its help with the conversation open. */
export const HELP_THREAD_PARAM = 'thread'
