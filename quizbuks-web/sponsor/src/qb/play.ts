import { api, runProcess, runQuery, unwrap, type Filter, type QueryPage, type Sort } from '@jabiz/client'
import { PLAY } from './ids'

/*
 * Answering as the app calls it (docs/quizbuks/plans/QP-pages.md, QP2), typed by hand after the Java records and
 * templates of backend/quizbuks (`play.AttemptProcesses`, `play.AttemptFinishing`, `queries/qb/app/*.sql`), as
 * `api.ts` is for the accounts. The end-to-end test play.spec runs every call against the server.
 *
 * The templates answer numbers as JSON numbers or as decimal text (numeric columns); the readers below turn both
 * into numbers, and amounts stay as the server sent them for `formatKudos`.
 */

/** A number of a template's row (numeric columns may come as text); 0 when there is none. */
export function numberOf(value: unknown): number {
  const n = Number(value)
  return value === null || value === undefined || !Number.isFinite(n) ? 0 : n
}

function textOrNull(value: unknown): string | null {
  return value === null || value === undefined || value === '' ? null : String(value)
}

// ---- the feed (qb.app.feed) -------------------------------------------------------------------------------------

/** A card of the feed: a publication the caller can answer now. */
export interface FeedCard {
  publicationId: string
  name: string
  quizTitle: string
  /** A file of the policy `qb.content.image`. */
  cover: string | null
  /** The highest reward, in Kudos. */
  maxReward: unknown
  completions: number
  sponsorName: string | null
  sponsorCompany: string | null
  sponsorAvatar: string | null
  sponsorCountry: string | null
  startAt: string
  endAt: string
  timeLimitSec: number
  questionCount: number
  fullScore: number
}

/** The sorts the feed offers (U-14), each a column of the template and a direction. */
export const FEED_SORTS = {
  newest: { field: 'startAt', asc: false },
  oldest: { field: 'startAt', asc: true },
  rewardHigh: { field: 'maxReward', asc: false },
  rewardLow: { field: 'maxReward', asc: true },
  popular: { field: 'completions', asc: false },
  quiet: { field: 'completions', asc: true },
} as const satisfies Record<string, Sort>

export type FeedSort = keyof typeof FEED_SORTS

export interface FeedRequest {
  /** The client's now (the template's `at`). */
  at: string
  /** The taker's country; without one only publications aimed at GLOBAL. */
  country: string | null
  q?: string
  sort?: FeedSort
  filters?: Filter[]
  offset?: number
  limit?: number
}

function feedCard(row: Record<string, unknown>): FeedCard {
  return {
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    quizTitle: String(row.quizTitle ?? ''),
    cover: textOrNull(row.cover),
    maxReward: row.maxReward,
    completions: numberOf(row.completions),
    sponsorName: textOrNull(row.sponsorName),
    sponsorCompany: textOrNull(row.sponsorCompany),
    sponsorAvatar: textOrNull(row.sponsorAvatar),
    sponsorCountry: textOrNull(row.sponsorCountry),
    startAt: String(row.startAt ?? ''),
    endAt: String(row.endAt ?? ''),
    timeLimitSec: numberOf(row.timeLimitSec),
    questionCount: numberOf(row.questionCount),
    fullScore: numberOf(row.fullScore),
  }
}

/** One page of the feed (U-10 – U-14). */
export async function readFeed(request: FeedRequest): Promise<QueryPage<FeedCard>> {
  const page = await runQuery<Record<string, unknown>>(PLAY.feed, {
    params: { at: request.at, country: request.country, q: request.q?.trim() || null },
    filters: request.filters,
    sorts: request.sort ? [FEED_SORTS[request.sort]] : undefined,
    offset: request.offset,
    limit: request.limit,
  })
  return { ...page, items: page.items.map(feedCard) }
}

// ---- a publication's page (qb.app.publication, qb.app.publication-tiers) ---------------------------------------

export type MaterialKind = 'ARTICLE' | 'LINK' | 'VIDEO_LINK' | 'PDF' | 'IMAGES' | 'AUDIO'

/** One reference material of the version's snapshot (`QuizSnapshots`): its kind uses one content field. */
export interface Material {
  no: number
  kind: MaterialKind
  title: string | null
  description: string | null
  /** ARTICLE: Markdown. */
  body: string | null
  /** LINK, VIDEO_LINK: opened in a new tab (Q-3). */
  url: string | null
  /** PDF: a file of the policy `qb.content.pdf`. */
  pdf: string | null
  /** AUDIO: a file of the policy `qb.content.audio`. */
  audio: string | null
  images: { no: number; image: string; caption: string | null }[]
}

/** The materials of the template's column (the snapshot's JSON); none when it cannot be read. */
export function parseMaterials(json: unknown): Material[] {
  if (typeof json !== 'string' || json.trim() === '') return []
  let list: unknown
  try {
    list = JSON.parse(json)
  } catch {
    return []
  }
  if (!Array.isArray(list)) return []
  return list.filter((m): m is Record<string, unknown> => typeof m === 'object' && m !== null).map((m) => ({
    no: numberOf(m.no),
    kind: String(m.kind) as MaterialKind,
    title: textOrNull(m.title),
    description: textOrNull(m.description),
    body: textOrNull(m.body),
    url: textOrNull(m.url),
    pdf: textOrNull(m.pdf),
    audio: textOrNull(m.audio),
    images: (Array.isArray(m.images) ? m.images : [])
      .filter((i): i is Record<string, unknown> => typeof i === 'object' && i !== null && !!i.image)
      .map((i) => ({ no: numberOf(i.no), image: String(i.image), caption: textOrNull(i.caption) })),
  }))
}

/** A publication as a taker sees it, with their own attempt if any. */
export interface PublicationView {
  publicationId: string
  name: string
  quizTitle: string
  cover: string | null
  /** Markdown. */
  intro: string | null
  materials: Material[]
  status: string
  startAt: string
  endAt: string
  timeLimitSec: number
  questionCount: number
  fullScore: number
  passScore: number
  maxReward: unknown
  sponsorName: string | null
  sponsorCompany: string | null
  sponsorIntro: string | null
  sponsorAvatar: string | null
  sponsorCountry: string | null
  completions: number
  last1h: number
  last24h: number
  last7d: number
  last30d: number
  /** When the windows' counts were taken (every ten minutes); null before the first run. */
  statsAt: string | null
  canStart: boolean
  myAttemptId: string | null
  myStatus: string | null
  myDeadline: string | null
}

export interface Tier {
  seq: number
  fromScore: number
  toScore: number
  reward: unknown
}

export interface PublicationRequest {
  publicationId: string
  at: string
  country: string | null
}

/** A publication's page; null when the caller cannot see it (not live for them and no attempt of theirs). */
export async function readPublication(request: PublicationRequest): Promise<PublicationView | null> {
  const page = await runQuery<Record<string, unknown>>(PLAY.publication, { params: { ...request }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  return {
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    quizTitle: String(row.quizTitle ?? ''),
    cover: textOrNull(row.cover),
    intro: textOrNull(row.intro),
    materials: parseMaterials(row.materials),
    status: String(row.status ?? ''),
    startAt: String(row.startAt ?? ''),
    endAt: String(row.endAt ?? ''),
    timeLimitSec: numberOf(row.timeLimitSec),
    questionCount: numberOf(row.questionCount),
    fullScore: numberOf(row.fullScore),
    passScore: numberOf(row.passScore),
    maxReward: row.maxReward,
    sponsorName: textOrNull(row.sponsorName),
    sponsorCompany: textOrNull(row.sponsorCompany),
    sponsorIntro: textOrNull(row.sponsorIntro),
    sponsorAvatar: textOrNull(row.sponsorAvatar),
    sponsorCountry: textOrNull(row.sponsorCountry),
    completions: numberOf(row.completions),
    last1h: numberOf(row.last1h),
    last24h: numberOf(row.last24h),
    last7d: numberOf(row.last7d),
    last30d: numberOf(row.last30d),
    statsAt: textOrNull(row.statsAt),
    canStart: row.canStart === true || row.canStart === 'true',
    myAttemptId: textOrNull(row.myAttemptId),
    myStatus: textOrNull(row.myStatus),
    myDeadline: textOrNull(row.myDeadline),
  }
}

/** A publication's reward tiers, lowest first (U-20). */
export async function readTiers(request: PublicationRequest): Promise<Tier[]> {
  const page = await runQuery<Record<string, unknown>>(PLAY.publicationTiers, { params: { ...request }, limit: 20 })
  return page.items.map((row) => ({
    seq: numberOf(row.seq),
    fromScore: numberOf(row.fromScore),
    toScore: numberOf(row.toScore),
    reward: row.reward,
  }))
}

/** `AttemptProcesses.ViewOutput`. */
export interface ViewOutput {
  publicationId: string
  firstView: boolean
}

/** Records the caller's first view of a publication (Q9's funnel; needs a verified address). */
export const recordView = (publicationId: string) => runProcess<ViewOutput>(PLAY.view, { publicationId })

// ---- answering (QB_ATTEMPT_START / SAVE / SUBMIT) ----------------------------------------------------------------

/** `AttemptProcesses.OptionView`: an option without saying whether it is correct. */
export interface OptionView {
  /** The option's number in the version (answers name it); shown in the attempt's own order. */
  no: number
  text: string | null
  image: string | null
}

/** `AttemptProcesses.QuestionView`. */
export interface QuestionView {
  no: number
  stem: string | null
  image: string | null
  points: number
  options: OptionView[]
}

/** `Answers.Given`: the options chosen for one question. */
export interface Given {
  questionNo: number
  selected: number[]
}

/** `AttemptProcesses.StartOutput`: never the correct answers. */
export interface StartOutput {
  attemptId: string
  publicationId: string
  status: string
  startedAt: string
  /** When it must be submitted, by the server's clock. */
  deadline: string
  /** The server's clock when it answered: the countdown's offset from the device's. */
  serverTime: string
  timeLimitSec: number
  /** The question the taker is on, from 0. */
  currentIndex: number
  /** The attempt was already in progress (continued). */
  resumed: boolean
  questions: QuestionView[]
  answers: Given[]
}

/** Starts the caller's attempt of a publication, or continues the one in progress (U-23, U-41). */
export const startAttempt = (publicationId: string) => runProcess<StartOutput>(PLAY.start, { publicationId })

/** `AttemptProcesses.SaveInput`. */
export interface SaveInput {
  attemptId: string
  currentIndex?: number | null
  /** Each replaces that question's answer; an empty selection clears it. */
  answers?: Given[]
}

/** `AttemptProcesses.SaveOutput`. */
export interface SaveOutput {
  attemptId: string
  currentIndex: number
  answers: Given[]
  deadline: string
  serverTime: string
}

export const saveAttempt = (input: SaveInput) => runProcess<SaveOutput>(PLAY.save, { ...input })

/** `AttemptFinishing.QuestionOutput` (and a finished attempt's `result`): what was chosen and what is correct. */
export interface QuestionResult {
  no: number
  selected: number[]
  correct: number[]
  points: number
  earned: number
}

export type RewardStatus = 'NONE' | 'PAID' | 'PENDING'

/** `AttemptFinishing.SubmitOutput` (U-33, U-34). */
export interface SubmitOutput {
  attemptId: string
  status: string
  score: number
  fullScore: number
  passScore: number
  passed: boolean
  reward: unknown
  rewardStatus: RewardStatus
  /** The notice of a pending reward in the caller's language; null otherwise. */
  message: string | null
  questions: QuestionResult[]
}

/**
 * Submits the caller's attempt with its last answers (U-32). Pass the same key when retrying the same submission: the
 * server finishes an attempt once whatever the key, and the key makes a lost answer's retry return the first one.
 */
export const submitAttempt = (input: SaveInput, idempotencyKey?: string) =>
  runProcess<SubmitOutput>(PLAY.submit, { ...input }, { idempotencyKey })

// ---- the caller's attempts ---------------------------------------------------------------------------------------

/** One of the caller's attempts as the dataset keeps it; the result only once finished (D-Q5-2). */
export interface AttemptRecord {
  attemptId: string
  publicationId: string
  status: string
  deadline: string | null
  submittedAt: string | null
  score: number | null
  fullScore: number | null
  passed: boolean | null
  reward: unknown
  rewardStatus: RewardStatus | null
  result: QuestionResult[] | null
}

/** The `result` column: every question's choice and correct options, or null before the attempt finished. */
export function parseResult(json: unknown): QuestionResult[] | null {
  if (typeof json !== 'string' || json.trim() === '') return null
  try {
    const rows = JSON.parse(json) as unknown
    if (!Array.isArray(rows)) return null
    return rows.map((row: Record<string, unknown>) => ({
      no: numberOf(row.no),
      selected: Array.isArray(row.selected) ? row.selected.map(numberOf) : [],
      correct: Array.isArray(row.correct) ? row.correct.map(numberOf) : [],
      points: numberOf(row.points),
      earned: numberOf(row.earned),
    }))
  } catch {
    return null
  }
}

/** One of the caller's own attempts; an `ApiError` 404 when it is not theirs or does not exist. */
export async function readAttempt(attemptId: string): Promise<AttemptRecord> {
  const entity = await unwrap(api.GET('/api/datasets/{resourceId}/entities/{id}', {
    params: { path: { resourceId: PLAY.myAttempts, id: attemptId } },
  }))
  const row = (entity.attributes ?? {}) as Record<string, unknown>
  const optional = (value: unknown) => (value === null || value === undefined ? null : numberOf(value))
  return {
    attemptId: String(row.attemptId ?? attemptId),
    publicationId: String(row.publicationId ?? ''),
    status: String(row.status ?? ''),
    deadline: textOrNull(row.deadline),
    submittedAt: textOrNull(row.submittedAt),
    score: optional(row.score),
    fullScore: optional(row.fullScore),
    passed: row.passed === null || row.passed === undefined ? null : row.passed === true || row.passed === 'true',
    reward: row.reward,
    rewardStatus: (textOrNull(row.rewardStatus) as RewardStatus | null),
    result: parseResult(row.result),
  }
}

/** The history's categories (D-Q5-11). */
export type HistoryCategory = 'PASSED' | 'FAILED' | 'IN_PROGRESS' | 'TIMED_OUT'

/** A row of `qb.app.history`. */
export interface HistoryRow {
  attemptId: string
  publicationId: string
  name: string
  quizTitle: string
  cover: string | null
  status: string
  category: HistoryCategory
  passed: boolean | null
  score: number | null
  fullScore: number
  reward: unknown
  rewardStatus: RewardStatus | null
  startedAt: string
  submittedAt: string | null
  deadline: string
}

export interface HistoryRequest {
  at: string
  q?: string
  /** Only these categories; all when absent. */
  categories?: HistoryCategory[]
  offset?: number
  limit?: number
}

/** One page of the caller's attempts, newest first (U-40 – U-42). */
export async function readHistory(request: HistoryRequest): Promise<QueryPage<HistoryRow>> {
  const page = await runQuery<Record<string, unknown>>(PLAY.history, {
    params: { at: request.at, q: request.q?.trim() || null },
    filters: request.categories ? [{ field: 'category', op: 'in', values: request.categories }] : undefined,
    offset: request.offset,
    limit: request.limit,
  })
  return {
    ...page,
    items: page.items.map((row) => ({
      attemptId: String(row.attemptId),
      publicationId: String(row.publicationId),
      name: String(row.name ?? ''),
      quizTitle: String(row.quizTitle ?? ''),
      cover: textOrNull(row.cover),
      status: String(row.status ?? ''),
      category: String(row.category) as HistoryCategory,
      passed: row.passed === null || row.passed === undefined ? null : row.passed === true || row.passed === 'true',
      score: row.score === null || row.score === undefined ? null : numberOf(row.score),
      fullScore: numberOf(row.fullScore),
      reward: row.reward,
      rewardStatus: textOrNull(row.rewardStatus) as RewardStatus | null,
      startedAt: String(row.startedAt ?? ''),
      submittedAt: textOrNull(row.submittedAt),
      deadline: String(row.deadline ?? ''),
    })),
  }
}
