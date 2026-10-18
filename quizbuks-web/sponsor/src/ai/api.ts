import { runProcess, runQuery } from '@jabiz/client'
import type { Material } from '../content/api'
import { minuteNow, serverNow } from '../qb/legal'
import { AI_LIMITS, AI_PROCESS, AI_TEMPLATE, type AI_LANGUAGES, type DIFFICULTIES } from './ids'

/*
 * The AI questions' calls (Q8a, M-26, M-10), typed by hand after `content.ai.AiProcesses` and the templates
 * `qb.sponsor.ai-jobs` / `qb.sponsor.ai-usage`. Candidates are never saved by the page: the sponsor reviews each one and
 * `QB_AI_ACCEPT` writes what they left, each marked `reviewed`. The page shows candidates as plain text only.
 */

export type AiJobStatus = 'QUEUED' | 'READY' | 'FAILED' | 'CANCELLED' | 'ACCEPTED' | 'DISCARDED' | 'EXPIRED'
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

/** A row of `qb.sponsor.ai-jobs`. */
export interface AiJob {
  aiJobId: string
  externalJobId: string
  status: AiJobStatus
  count: number | null
  candidates: AiCandidate[]
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

export function toAiJob(row: Row): AiJob {
  let count: number | null
  try {
    count = num((JSON.parse(String(row.params ?? '{}')) as Row).count) || null
  } catch {
    count = null
  }
  return {
    aiJobId: String(row.aiJobId),
    externalJobId: String(row.externalJobId ?? ''),
    status: (row.status as AiJobStatus) ?? 'QUEUED',
    count,
    candidates: parseCandidates(row.candidates),
    candidateCount: num(row.candidateCount),
    invalidCount: num(row.invalidCount),
    acceptedCount: num(row.acceptedCount),
    failureCode: str(row.failureCode),
    requestedAt: String(row.requestedAt ?? ''),
    finishedAt: str(row.finishedAt),
    expiresAt: str(row.expiresAt),
  }
}

/** The quiz's latest AI job, or null when it has none. */
export async function readLatestAiJob(quizId: string): Promise<AiJob | null> {
  const page = await runQuery<Row>(AI_TEMPLATE.jobs, { params: { quizId }, limit: 1 })
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

export const discardAiJob = (aiJobId: string) => runProcess<{ status: AiJobStatus }>(AI_PROCESS.discard, { aiJobId })
export const cancelAiJob = (aiJobId: string) => runProcess<{ status: AiJobStatus }>(AI_PROCESS.cancel, { aiJobId })

const encoder = new TextEncoder()

/**
 * About how many bytes a material adds to the request, as the server sends it: an article with its text, any other
 * kind by its title and description only (A-5). For the page's guide; the server measures and decides.
 */
export function materialBytes(material: Pick<Material, 'kind' | 'title' | 'description' | 'body'>): number {
  const sent = {
    no: 1,
    kind: material.kind,
    title: material.title,
    description: material.description,
    ...(material.kind === 'ARTICLE' ? { text: material.body } : {}),
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
