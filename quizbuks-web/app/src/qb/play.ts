import { runProcess, runQuery, type Filter, type QueryPage, type Sort } from '@jabiz/client'
import { trustedPlayer } from './video'
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

/**
 * A scored quiz or a survey (docs/quizbuks/plans/Q12-survey.md): a survey's questions have no correct options, every
 * one must be answered to submit, and a complete response earns the one reward.
 */
export type QuizKind = 'QUIZ' | 'SURVEY'

/** The kind a template or process gave; a scored quiz unless it says survey. */
export function kindOf(value: unknown): QuizKind {
  return value === 'SURVEY' ? 'SURVEY' : 'QUIZ'
}

/** How a survey's question is answered: exactly one option, or at least one. Null for a scored question. */
export type Selection = 'SINGLE' | 'MULTIPLE'

// ---- the feed (qb.app.feed) -------------------------------------------------------------------------------------

/** `publishing.RewardMethod` (Q22): Kudos paid by QuizBuks, cash from the sponsor, or a prize at the sponsor's place. */
export type RewardMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM'
export const REWARD_METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM']
export const methodOf = (value: unknown): RewardMethod =>
  (REWARD_METHODS as readonly string[]).includes(String(value)) ? (value as RewardMethod) : 'PLATFORM'

/** A card of the feed: a publication the caller can answer now. */
export interface FeedCard {
  publicationId: string
  name: string
  quizTitle: string
  /** A file of the policy `qb.content.image`. */
  cover: string | null
  /** Q19: the player of the video watching which is enough to answer (one of `qb/video.ts`), else null. */
  video: string | null
  kind: QuizKind
  /** The highest reward, in Kudos. */
  maxReward: unknown
  /** How the reward reaches the taker (Q22). */
  rewardMethod: RewardMethod
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
  /** Only publications whose reward comes this way (Q22); all when absent. */
  method?: RewardMethod | null
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
    video: trustedPlayer(textOrNull(row.video)),
    kind: kindOf(row.kind),
    maxReward: row.maxReward,
    rewardMethod: methodOf(row.rewardMethod),
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
    params: { at: request.at, country: request.country, q: request.q?.trim() || null,
      method: request.method ?? null },
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
  kind: QuizKind
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
  /** How the reward reaches the taker (Q22), and the prize when it is collected at the sponsor's place. */
  rewardMethod: RewardMethod
  redeemDescription: string | null
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
    kind: kindOf(row.kind),
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
    rewardMethod: methodOf(row.rewardMethod),
    redeemDescription: textOrNull(row.redeemDescription),
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
  /** A survey's question: one option or several; null for a scored question (any number may be chosen). */
  selection: Selection | null
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
  kind: QuizKind
}

/** Whether a choice answers a question: a survey's single choice takes exactly one option (Q12). */
export function answeredBy(question: Pick<QuestionView, 'selection'>, selected: readonly number[]): boolean {
  return question.selection === 'SINGLE' ? selected.length === 1 : selected.length > 0
}

/** The questions (their numbers) a choice does not answer yet, in order: a survey is submitted only without any. */
export function unanswered(questions: readonly QuestionView[], answers: ReadonlyMap<number, readonly number[]>): number[] {
  return questions.filter((q) => !answeredBy(q, answers.get(q.no) ?? [])).map((q) => q.no)
}

/**
 * Starts the caller's attempt of a publication, or continues the one in progress (U-23, U-41). Pass the same key when
 * retrying the same start (a busy answer, a lost one): the retry of one that went through returns its answer.
 */
export const startAttempt = (publicationId: string, idempotencyKey?: string) =>
  runProcess<StartOutput>(PLAY.start, { publicationId }, { idempotencyKey })

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

/** `play.RewardStatus`: since Q22 also owed in cash by the sponsor (CASH) or a prize to collect (REDEEM). */
export type RewardStatus = 'NONE' | 'PAID' | 'PENDING' | 'CASH' | 'REDEEM'

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
  kind: QuizKind
}

/**
 * `AttemptFinishing.SubmitInput`: the publication is the attempt's (the start's output gives it); the server queues the
 * submission by it before the publication's lock and refuses another one (`QB_ATTEMPT_PUBLICATION_MISMATCH`).
 */
export interface SubmitInput extends SaveInput {
  publicationId: string
}

/**
 * Submits the caller's attempt with its last answers (U-32). Pass the same key when retrying the same submission: the
 * server finishes an attempt once whatever the key, and the key makes a lost answer's retry return the first one.
 */
export const submitAttempt = (input: SubmitInput, idempotencyKey?: string) =>
  runProcess<SubmitOutput>(PLAY.submit, { ...input }, { idempotencyKey })

// ---- the caller's attempts ---------------------------------------------------------------------------------------

/**
 * `AttemptResults.ResultOutput` (Q20): one of the caller's attempts with, once finished, its questions as that attempt
 * showed them (options by position, as the start gives them) and its result by the same positions. In progress it
 * carries the state only; `passScore` is null for a survey.
 */
export interface AttemptResult {
  attemptId: string
  publicationId: string
  /** The publication's name, as its card has it. */
  name: string | null
  status: string
  kind: QuizKind
  deadline: string | null
  serverTime: string
  score: number | null
  fullScore: number | null
  passScore: number | null
  passed: boolean | null
  reward: unknown
  rewardStatus: RewardStatus | null
  questions: QuestionView[]
  result: QuestionResult[]
  /** Q22: how the reward reaches the taker, with the cash reward's or the prize's record when there is one. */
  rewardMethod: RewardMethod
  cash: CashView | null
  redeem: RedeemView | null
}

/** `reward.CashRewardStatus` (dictionary `cash-reward-status`). */
export type CashStatus = 'DUE' | 'MARKED_PAID' | 'CONFIRMED' | 'DISPUTED' | 'OVERDUE' | 'VOID'

/** `AttemptResults.CashView` (Q22): where the cash reward stands. */
export interface CashView {
  cashRewardId: string
  status: CashStatus
  code: string
  amount: unknown
  markedPaidAt: string | null
  confirmBy: string | null
  sponsorCompany: string | null
}

/** `AttemptResults.RedeemView` (Q22): the prize to collect and until when. */
export interface RedeemView {
  redemptionId: string
  status: 'DUE' | 'REDEEMED' | 'EXPIRED' | 'VOID'
  code: string
  amount: unknown
  description: string
  expiresAt: string | null
  sponsorCompany: string | null
}

/** The taker confirms they received the cash (`QB_CASH_REWARD_CONFIRM`). */
export const confirmCashReward = (cashRewardId: string) =>
  runProcess<{ cashRewardId: string; status: CashStatus; revision: number }>(PLAY.cashConfirm, { cashRewardId })

/** The taker says what is wrong with the cash reward (`QB_CASH_REWARD_DISPUTE`); an administrator decides. */
export const disputeCashReward = (cashRewardId: string, note: string) =>
  runProcess<{ cashRewardId: string; status: CashStatus; revision: number }>(PLAY.cashDispute,
    { cashRewardId, note: note.trim() })

/** A row of `qb.app.cash-rewards`: one of the caller's cash rewards. */
export interface CashRewardRow {
  cashRewardId: string
  attemptId: string
  code: string
  publicationId: string
  publicationName: string
  sponsorCompany: string | null
  amount: unknown
  status: CashStatus
  dueAt: string
  markedPaidAt: string | null
  confirmBy: string | null
}

/** The caller's cash rewards that still need something of them or the sponsor (Q22), newest first. */
export async function readOpenCashRewards(): Promise<CashRewardRow[]> {
  const page = await runQuery<Record<string, unknown>>(PLAY.cashRewards, { params: { open: true }, limit: 50 })
  return page.items.map((row) => ({
    cashRewardId: String(row.cashRewardId),
    attemptId: String(row.attemptId ?? ''),
    code: String(row.code ?? ''),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    sponsorCompany: textOrNull(row.sponsorCompany),
    amount: row.amount,
    status: String(row.status) as CashStatus,
    dueAt: String(row.dueAt ?? ''),
    markedPaidAt: textOrNull(row.markedPaidAt),
    confirmBy: textOrNull(row.confirmBy),
  }))
}

/** The result of one of the caller's own attempts; an `ApiError` 404 when it is not theirs or does not exist. */
export const readAttemptResult = (attemptId: string) => runProcess<AttemptResult>(PLAY.result, { attemptId })

/** The history's categories (D-Q5-11). */
export type HistoryCategory = 'PASSED' | 'FAILED' | 'IN_PROGRESS' | 'TIMED_OUT'

/** A row of `qb.app.history`. */
export interface HistoryRow {
  attemptId: string
  publicationId: string
  name: string
  quizTitle: string
  cover: string | null
  kind: QuizKind
  status: string
  category: HistoryCategory
  passed: boolean | null
  score: number | null
  fullScore: number
  reward: unknown
  rewardStatus: RewardStatus | null
  /** Q22: how the reward reaches the taker, and where the cash reward or the prize stands. */
  rewardMethod: RewardMethod
  cashRewardId: string | null
  cashStatus: CashStatus | null
  cashCode: string | null
  redeemStatus: string | null
  redeemExpiresAt: string | null
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
      kind: kindOf(row.kind),
      status: String(row.status ?? ''),
      category: String(row.category) as HistoryCategory,
      passed: row.passed === null || row.passed === undefined ? null : row.passed === true || row.passed === 'true',
      score: row.score === null || row.score === undefined ? null : numberOf(row.score),
      fullScore: numberOf(row.fullScore),
      reward: row.reward,
      rewardStatus: textOrNull(row.rewardStatus) as RewardStatus | null,
      rewardMethod: methodOf(row.rewardMethod),
      cashRewardId: textOrNull(row.cashRewardId),
      cashStatus: textOrNull(row.cashStatus) as CashStatus | null,
      cashCode: textOrNull(row.cashCode),
      redeemStatus: textOrNull(row.redeemStatus),
      redeemExpiresAt: textOrNull(row.redeemExpiresAt),
      startedAt: String(row.startedAt ?? ''),
      submittedAt: textOrNull(row.submittedAt),
      deadline: String(row.deadline ?? ''),
    })),
  }
}
