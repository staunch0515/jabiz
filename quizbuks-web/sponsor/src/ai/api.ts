import { runProcess, runQuery } from '@jabiz/client'
import type { EditOutput, Material } from '../content/api'
import { minuteNow, serverNow } from '../qb/legal'
import { AI_KINDS, AI_LIMITS, AI_PROCESS, AI_TEMPLATE, type AI_LANGUAGES, type DIFFICULTIES } from './ids'

/*
 * The AI questions' calls (Q8a, M-26, M-10), typed by hand after `content.ai.AiProcesses` and the templates
 * `qb.sponsor.ai-jobs` / `qb.sponsor.ai-usage`. Candidates are never saved by the page: the sponsor reviews each one and
 * `QB_AI_ACCEPT` writes what they left, each marked `reviewed`. The page shows candidates as plain text only.
 */

export type AiJobStatus = 'QUEUED' | 'READY' | 'FAILED' | 'CANCELLED' | 'ACCEPTED' | 'DISCARDED' | 'EXPIRED'
export type AiJobKind = (typeof AI_KINDS)[keyof typeof AI_KINDS]
export type Difficulty = (typeof DIFFICULTIES)[number]
export type AiLanguage = (typeof AI_LANGUAGES)[number]

export interface AiOption {
  text: string
  correct: boolean
}

/** A candidate question as the server keeps it (numbered from 1). */
export interface AiCandidate {
  no: number
  stem: string
  points: number
  /** The numbers of the materials it rests on (the quiz's order, from 1). */
  materials: number[]
  options: AiOption[]
}

/**
 * A candidate cover (Q8b) as the server keeps it: the design Claude chose from the fixed catalogue and the picture the
 * server drew of it (a file of the sponsor's, `qb.content.image`).
 */
export interface AiCover {
  no: number
  palette: string
  layout: string
  motif: string
  text: string
  fileId: string
}

/**
 * A candidate translation (Q73d-1) as the server keeps it: the element it translates (`content/languages.ts`'s key)
 * and the text, numbered as the server accepts it.
 */
export interface AiTranslation {
  no: number
  key: string
  text: string
}

/** A row of `qb.sponsor.ai-jobs`. */
export interface AiJob {
  aiJobId: string
  externalJobId: string
  kind: AiJobKind
  status: AiJobStatus
  count: number | null
  candidates: AiCandidate[]
  /** A cover job's candidates (none for questions). */
  covers: AiCover[]
  /** Q73d-1: a translation job's language and candidates (none for the other kinds). */
  language: string | null
  /** Q73d-2: a publication texts job's publication. */
  publicationId: string | null
  translations: AiTranslation[]
  candidateCount: number
  invalidCount: number
  acceptedCount: number
  failureCode: string | null
  requestedAt: string
  finishedAt: string | null
  expiresAt: string | null
}

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))

/** The stored candidates (`{schema: 1, questions: [...]}`); none when there are none or they cannot be read. */
export function parseCandidates(text: unknown): AiCandidate[] {
  if (typeof text !== 'string' || !text) return []
  try {
    const document = JSON.parse(text) as { questions?: unknown }
    if (!Array.isArray(document.questions)) return []
    return document.questions.map((q: Row, index): AiCandidate => ({
      no: num(q.no) || index + 1,
      stem: String(q.stem ?? ''),
      points: num(q.points) || 10,
      materials: Array.isArray(q.materials) ? q.materials.map(num).filter((n) => n > 0) : [],
      options: Array.isArray(q.options)
        ? q.options.map((o: Row) => ({ text: String(o.text ?? ''), correct: o.correct === true }))
        : [],
    }))
  } catch {
    return []
  }
}

/** The stored candidate covers (`{schema: 1, covers: [...]}`); none when there are none or they cannot be read. */
export function parseCovers(text: unknown): AiCover[] {
  if (typeof text !== 'string' || !text) return []
  try {
    const document = JSON.parse(text) as { covers?: unknown }
    if (!Array.isArray(document.covers)) return []
    return document.covers
      .map((c: Row, index): AiCover => ({
        no: num(c.no) || index + 1,
        palette: String(c.palette ?? ''),
        layout: String(c.layout ?? ''),
        motif: String(c.motif ?? ''),
        text: String(c.text ?? ''),
        fileId: String(c.fileId ?? ''),
      }))
      .filter((c) => c.fileId !== '')
  } catch {
    return []
  }
}

/** The stored candidate translations (`{schema: 1, translations: [...]}`); none when they cannot be read. */
export function parseTranslations(text: unknown): AiTranslation[] {
  if (typeof text !== 'string' || !text) return []
  try {
    const document = JSON.parse(text) as { translations?: unknown }
    if (!Array.isArray(document.translations)) return []
    return document.translations
      .map((c: Row): AiTranslation => ({ no: num(c.no), key: String(c.key ?? ''), text: String(c.text ?? '') }))
      .filter((c) => c.no > 0 && c.key !== '')
  } catch {
    return []
  }
}

const KINDS: readonly string[] = Object.values(AI_KINDS)

export function toAiJob(row: Row): AiJob {
  let params: Row
  try {
    params = JSON.parse(String(row.params ?? '{}')) as Row
  } catch {
    params = {}
  }
  const kind = (KINDS.includes(String(row.kind)) ? row.kind : AI_KINDS.questions) as AiJobKind
  return {
    aiJobId: String(row.aiJobId),
    externalJobId: String(row.externalJobId ?? ''),
    kind,
    status: (row.status as AiJobStatus) ?? 'QUEUED',
    count: num(params.count) || null,
    candidates: kind === AI_KINDS.questions ? parseCandidates(row.candidates) : [],
    covers: kind === AI_KINDS.cover ? parseCovers(row.candidates) : [],
    language: kind === AI_KINDS.translation || kind === AI_KINDS.publicationTexts ? str(params.language) : null,
    publicationId: str(params.publicationId),
    translations: kind === AI_KINDS.translation || kind === AI_KINDS.publicationTexts
      ? parseTranslations(row.candidates) : [],
    candidateCount: num(row.candidateCount),
    invalidCount: num(row.invalidCount),
    acceptedCount: num(row.acceptedCount),
    failureCode: str(row.failureCode),
    requestedAt: String(row.requestedAt ?? ''),
    finishedAt: str(row.finishedAt),
    expiresAt: str(row.expiresAt),
  }
}

/** The quiz's latest AI job of a kind, or null when it has none. */
export async function readLatestAiJob(quizId: string, kind: AiJobKind = AI_KINDS.questions,
  publicationId: string | null = null): Promise<AiJob | null> {
  const page = await runQuery<Row>(AI_TEMPLATE.jobs, { params: { quizId, kind,
    ...(publicationId ? { publicationId } : {}) }, limit: 1 })
  return page.items[0] ? toAiJob(page.items[0]) : null
}

/** A row of `qb.sponsor.ai-usage`: this month's (the deployment's month and day). */
export interface AiUsage {
  month: string
  enabled: boolean
  jobs: number
  failedJobs: number
  inputTokens: number
  outputTokens: number
  monthlyTokens: number | null
  reservedTokens: number
  remainingTokens: number | null
  dailyJobs: number | null
  jobsToday: number
  monthEnds: string | null
  dayEnds: string | null
  /** The most questions one request may ask for at the effort in force (fewer at effort high). */
  maxCount: number
}

export function toAiUsage(row: Row | undefined): AiUsage {
  const r = row ?? {}
  const orNull = (value: unknown) => (value == null || value === '' ? null : num(value))
  return {
    month: String(r.month ?? ''),
    enabled: r.enabled === true || r.enabled === 'true',
    jobs: num(r.jobs),
    failedJobs: num(r.failedJobs),
    inputTokens: num(r.inputTokens),
    outputTokens: num(r.outputTokens),
    monthlyTokens: orNull(r.monthlyTokens),
    reservedTokens: num(r.reservedTokens),
    remainingTokens: orNull(r.remainingTokens),
    dailyJobs: orNull(r.dailyJobs),
    jobsToday: num(r.jobsToday),
    monthEnds: str(r.monthEnds),
    dayEnds: str(r.dayEnds),
    maxCount: orNull(r.maxCount) ?? AI_LIMITS.maxCount,
  }
}

/** This month's usage; `at` is now by the server's clock. */
export async function readAiUsage(at: string = minuteNow()): Promise<AiUsage> {
  const page = await runQuery<Row>(AI_TEMPLATE.usage, { params: { at }, limit: 1 })
  return toAiUsage(page.items[0])
}

/** `AiProcesses.RequestInput`. */
export interface AiRequestInput {
  quizId: string
  materialIds: string[]
  count: number
  difficulty: Difficulty
  language: AiLanguage
  avoidExisting: boolean
  /** The AI notice's version the page showed, when the sponsor accepts it now. */
  aiNoticeVersionNo?: number | null
}

export interface AiRequestOutput {
  aiJobId: string
  externalJobId: string
  status: AiJobStatus
  estimatedTokens: number
}

export const requestAiQuestions = (input: AiRequestInput) =>
  runProcess<AiRequestOutput>(AI_PROCESS.request, { ...input })

/** `AiProcesses.CoverRequestInput` (Q8b): covers from the quiz's title and introduction. */
export interface AiCoverRequestInput {
  quizId: string
  language: AiLanguage
  aiNoticeVersionNo?: number | null
}

export const requestAiCovers = (input: AiCoverRequestInput) =>
  runProcess<AiRequestOutput>(AI_PROCESS.coverRequest, { ...input })

/** `QB_AI_COVER_ACCEPT`: the chosen cover becomes the quiz's, as a quiz edit (`EditOutput`). */
export const acceptAiCover = (aiJobId: string, quizId: string, candidateNo: number, baseRevision: number) =>
  runProcess<EditOutput>(AI_PROCESS.coverAccept, { aiJobId, quizId, candidateNo, baseRevision })

/** One reviewed question (`AiProcesses.AcceptedQuestion`): the text as the sponsor left it. */
export interface AcceptedQuestion {
  candidateNo: number
  stem: string
  points: number
  options: AiOption[]
  reviewed: true
}

export interface AcceptOutput {
  quizId: string
  revision: number
  aiJobId: string
  questionIds: string[]
}

export const acceptAiQuestions = (aiJobId: string, quizId: string, questions: AcceptedQuestion[],
  baseRevision: number) =>
  runProcess<AcceptOutput>(AI_PROCESS.accept, { aiJobId, quizId, questions, baseRevision })

/** `AiProcesses.TranslateRequestInput` (Q73d-1). */
export interface AiTranslateRequestInput {
  quizId: string
  language: string
  /** Every text, also the translated and up-to-date ones. */
  all: boolean
  aiNoticeVersionNo?: number | null
}

export const requestAiTranslation = (input: AiTranslateRequestInput) =>
  runProcess<AiRequestOutput>(AI_PROCESS.translateRequest, { ...input })

/** One reviewed candidate translation: its number and the text as the sponsor left it. */
export interface AcceptedTranslation {
  candidateNo: number
  text: string
  reviewed: true
}

export const acceptAiTranslations = (aiJobId: string, quizId: string, translations: AcceptedTranslation[],
  baseRevision: number) =>
  runProcess<EditOutput>(AI_PROCESS.translationAccept, { aiJobId, quizId, translations, baseRevision })

/** Q73d-2: candidate translations of a publication's own texts into one of its languages. */
export const requestAiPublicationTexts = (input: { quizId: string; publicationId: string; language: string;
  all: boolean; aiNoticeVersionNo?: number | null }) =>
  runProcess<AiRequestOutput>(AI_PROCESS.publicationTranslateRequest, { ...input })

/** Q73d-2: records that the sponsor used `used` of the job's texts (kept in the publication's form). */
export const acceptAiPublicationTexts = (aiJobId: string, publicationId: string, used: number) =>
  runProcess<{ status: AiJobStatus }>(AI_PROCESS.publicationTextsAccept, { aiJobId, publicationId, used })

export const discardAiJob = (aiJobId: string) => runProcess<{ status: AiJobStatus }>(AI_PROCESS.discard, { aiJobId })
export const cancelAiJob = (aiJobId: string) => runProcess<{ status: AiJobStatus }>(AI_PROCESS.cancel, { aiJobId })

const encoder = new TextEncoder()

/** Whether a material goes to the AI as its file (a PDF with its file, Q8b), not only as its title and description. */
export const sentAsFile = (material: Pick<Material, 'kind' | 'pdf'>): boolean =>
  material.kind === 'PDF' && material.pdf != null

/**
 * About how many bytes a material adds to the request's text, as the server sends it: an article with its text, any
 * other kind by its title and description only (A-5); a PDF's file goes beside the text and is not counted here.
 * For the page's guide; the server measures and decides.
 */
export function materialBytes(material: Pick<Material, 'kind' | 'title' | 'description' | 'body' | 'pdf'>): number {
  const sent = {
    no: 1,
    kind: material.kind,
    title: material.title,
    description: material.description,
    ...(material.kind === 'ARTICLE' ? { text: material.body } : {}),
    ...(sentAsFile(material) ? { document: 'Material 1' } : {}),
  }
  return encoder.encode(JSON.stringify(sent)).length
}

/** Whether the chosen materials and the quiz's own text fit the request's size (with room for its framing). */
export function fitsInput(bytes: number): boolean {
  return bytes <= AI_LIMITS.maxInputBytes
}

/** Whether a running job is still worth asking about (polling stops after five minutes). */
export function stillPolling(job: AiJob | null, now: number = serverNow()): boolean {
  if (!job || job.status !== 'QUEUED') return false
  const started = Date.parse(job.requestedAt)
  return Number.isNaN(started) || now - started < AI_LIMITS.pollForMs
}
