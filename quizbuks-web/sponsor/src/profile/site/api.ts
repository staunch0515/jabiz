import { runProcess, runQuery } from '@jabiz/client'
import { AI_PROCESS } from '../../ai/ids'
import { serverNow } from '../../qb/legal'
import { CONTENT_LANGUAGES, type ContentLanguage } from '../../qb/api'
import { SITE_POLL, SITE_PROFILE } from './ids'

/*
 * The calls of "from the website" (F-C7b): the request, the sponsor's drafts and what the review shows of one. The
 * draft is kept by the server (`READY`) until it expires; nothing of it is written anywhere until the sponsor confirms
 * the usual profile import (`ProfileTransfer`), which is the only way it reaches the sponsor's data.
 */

export type SiteDraftStatus = 'QUEUED' | 'READY' | 'FAILED' | 'CANCELLED' | 'ACCEPTED' | 'DISCARDED' | 'EXPIRED'

type Row = Record<string, unknown>

/** A field of the draft that was left out, or whose text was changed, and why. */
export interface DroppedField { path: string; reason: string }

/** What the server says of the drafted link name. */
export interface HandleInfo { handle: string; taken: boolean; alternatives: string[] }

/** A job of the sponsor's, as the review needs it. */
export interface SiteDraft {
  aiJobId: string
  status: SiteDraftStatus
  failureCode: string | null
  /** The website as the server took it. */
  address: string
  language: ContentLanguage
  languages: ContentLanguage[]
  requestedAt: string
  finishedAt: string | null
  expiresAt: string | null
  /** The profile package without the contact name, or null when the draft has none (or is not `READY`). */
  profile: Record<string, unknown> | null
  /** Field path → the page of the website it was taken from. */
  sources: Record<string, string>
  notFound: string[]
  dropped: DroppedField[]
  /** The languages the AI translated (marked machine-translated on the review). */
  translated: ContentLanguage[]
  handle: HandleInfo | null
  materials: number
  /** The articles the quiz would have: the title and the page each was taken from. */
  articles: { title: string; url: string | null }[]
  questions: number
  /** The quiz made from this draft (one a draft), or null. */
  quizId: string | null
}

const isLanguage = (value: unknown): value is ContentLanguage =>
  (CONTENT_LANGUAGES as readonly unknown[]).includes(value)
const text = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
const strings = (value: unknown): string[] => (Array.isArray(value) ? value.map(String) : [])

function parse(textValue: unknown): Row {
  if (typeof textValue !== 'string' || !textValue) return {}
  try {
    return record(JSON.parse(textValue))
  } catch {
    return {}
  }
}

export function toSiteDraft(row: Row): SiteDraft {
  const params = parse(row.params)
  const draft = parse(row.candidates)
  const language = isLanguage(params.language) ? params.language : 'en'
  const languages = Array.isArray(params.languages) ? params.languages.filter(isLanguage) : [language]
  const handle = record(draft.handle)
  return {
    aiJobId: String(row.aiJobId),
    status: (row.status as SiteDraftStatus) ?? 'QUEUED',
    failureCode: text(row.failureCode),
    address: String(params.url ?? ''),
    language,
    languages: languages.length > 0 ? languages : [language],
    requestedAt: String(row.requestedAt ?? ''),
    finishedAt: text(row.finishedAt),
    expiresAt: text(row.expiresAt),
    profile: draft.profile && Object.keys(record(draft.profile)).length > 0 ? record(draft.profile) : null,
    sources: Object.fromEntries(Object.entries(record(draft.sources)).map(([k, v]) => [k, String(v)])),
    notFound: strings(draft.notFound),
    dropped: Array.isArray(draft.dropped)
      ? draft.dropped.map((d) => ({ path: String(record(d).path ?? ''), reason: String(record(d).reason ?? '') }))
      : [],
    translated: strings(draft.translated).filter(isLanguage),
    handle: typeof handle.handle === 'string'
      ? { handle: handle.handle, taken: handle.taken === true, alternatives: strings(handle.alternatives) } : null,
    materials: Array.isArray(draft.materials) ? draft.materials.length : 0,
    articles: Array.isArray(draft.materials)
      ? draft.materials.map((m) => ({ title: String(record(m).title ?? ''), url: text(record(m).url) })) : [],
    questions: Array.isArray(draft.questions) ? draft.questions.length : 0,
    quizId: text(row.siteQuizId),
  }
}

export const SITE_QUERY = ['qb', 'site-profile'] as const

/** The sponsor's drafts, newest first. */
export async function readSiteDrafts(): Promise<SiteDraft[]> {
  const page = await runQuery<Row>(SITE_PROFILE.template, { limit: 5 })
  return page.items.map(toSiteDraft)
}

/** Whether the draft is being made and still worth looking at (the reading takes minutes; the page gives up later). */
export function stillRunning(draft: SiteDraft | null | undefined, now: number = serverNow()): boolean {
  if (!draft || draft.status !== 'QUEUED') return false
  const asked = Date.parse(draft.requestedAt)
  return !Number.isFinite(asked) || now - asked < SITE_POLL.forMs
}

/** A draft that waits for the sponsor to review it. */
export function isReady(draft: SiteDraft | null | undefined, now: number = serverNow()): draft is SiteDraft {
  if (!draft || draft.status !== 'READY' || !draft.profile) return false
  const ends = draft.expiresAt ? Date.parse(draft.expiresAt) : NaN
  return !Number.isFinite(ends) || ends > now
}

/** `SiteProfileProcesses.RequestInput`. */
export interface SiteRequestInput {
  siteAddress: string
  language: ContentLanguage
  otherLanguages: ContentLanguage[]
  profile: boolean
  products: boolean
  quiz: boolean
  rightsDeclared: boolean
  aiNoticeVersionNo?: number | null
}

export const requestSiteProfile = (input: SiteRequestInput) =>
  runProcess<{ aiJobId: string; externalJobId: string; status: SiteDraftStatus }>(SITE_PROFILE.request, { ...input })

/** The quiz made from a draft: its id, and the job that keeps its drafted questions for review (null when none). */
export interface SiteQuizOutput { quizId: string; questionsJob: string | null; materials: number; questions: number }

export const makeSiteQuiz = (aiJobId: string) => runProcess<SiteQuizOutput>(SITE_PROFILE.quiz, { aiJobId })

export const cancelSiteDraft = (aiJobId: string) => runProcess(AI_PROCESS.cancel, { aiJobId })
export const discardSiteDraft = (aiJobId: string) => runProcess(AI_PROCESS.discard, { aiJobId })

/**
 * The draft as a profile package for the usual import check: the account's contact name goes in (the server never
 * takes one from the website), and the link name the sponsor chose replaces the drafted one.
 */
export function draftDocument(draft: SiteDraft, contactName: string, handle?: string,
  now: Date = new Date()): Record<string, unknown> {
  const profile = draft.profile ?? {}
  const document: Record<string, unknown> = {
    format: 'quizbuks-profile', schema: 1, exportedAt: now.toISOString(),
    languages: Array.isArray(profile.languages) ? profile.languages : draft.languages,
    products: Array.isArray(profile.products) ? profile.products : [],
    files: {},
  }
  if (profile.basics) document.basics = { ...record(profile.basics), contactName }
  if (profile.page) document.page = { ...record(profile.page), ...(handle ? { handle } : {}) }
  return document
}

/** The page a field was taken from, if the draft says. */
export const sourceOf = (draft: SiteDraft, path: string): string | null => draft.sources[path] ?? null
