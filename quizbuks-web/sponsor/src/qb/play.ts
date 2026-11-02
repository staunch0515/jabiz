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

/**
 * `publishing.RewardMethod` (Q22): Kudos paid by QuizBuks, cash from the sponsor, a prize at the sponsor's place, or
 * (Q33) a digital prize: a code, a voucher number or a link the winner gets without going anywhere.
 */
export type RewardMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM' | 'DIGITAL_PRIZE'
export const REWARD_METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE']

/**
 * `publishing.DigitalDelivery` (Q33): how a digital prize reaches the winner: one code each from a pool the sponsor
 * loaded, one shared code or link for everyone, or sent by the sponsor by hand within some days.
 */
export type DigitalDelivery = 'POOL' | 'SHARED' | 'SPONSOR_SENT'
export const digitalDeliveryOf = (value: unknown): DigitalDelivery | null =>
  value === 'POOL' || value === 'SHARED' || value === 'SPONSOR_SENT' ? value : null
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
  /** The language the quiz is written in (Q59a); a preview's card has none. */
  language?: string | null
  /** The highest reward, in Kudos. */
  maxReward: unknown
  /** How the reward reaches the taker (Q22). */
  rewardMethod: RewardMethod
  /** Q33: how a digital prize is delivered and what it is; null for the other methods. */
  digitalDelivery: DigitalDelivery | null
  digitalDescription: string | null
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
  /** Q64: the sponsor's published page (its link name); none while it is not published, and on a preview's card. */
  sponsorHandle?: string | null
  /** Q64: the caller's own attempt at it, when they started one: its id, status and deadline. */
  myAttemptId?: string | null
  myStatus?: string | null
  myDeadline?: string | null
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
  /** Also the publications the taker answered (Q34); left out when absent. */
  includeAnswered?: boolean
  /** Q59a: only publications in these languages; all when absent. */
  languages?: readonly string[]
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
    language: textOrNull(row.language),
    maxReward: row.maxReward,
    rewardMethod: methodOf(row.rewardMethod),
    digitalDelivery: digitalDeliveryOf(row.digitalDelivery),
    digitalDescription: textOrNull(row.digitalDescription),
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
    sponsorHandle: textOrNull(row.sponsorHandle),
    myAttemptId: textOrNull(row.myAttemptId),
    myStatus: textOrNull(row.myStatus),
    myDeadline: textOrNull(row.myDeadline),
  }
}

/** Q64: where a card's start leads: the start page, back to answering, or the result. */
export type CardProgress = 'start' | 'resume' | 'done'

/** The caller's progress on a card at `now`: an attempt in progress and still in time resumes; any other is done. */
export function cardProgress(card: Pick<FeedCard, 'myAttemptId' | 'myStatus' | 'myDeadline'>, now: Date): CardProgress {
  if (!card.myAttemptId) return 'start'
  const inTime = card.myStatus === 'IN_PROGRESS' && !!card.myDeadline && Date.parse(card.myDeadline) > now.getTime()
  return inTime ? 'resume' : 'done'
}

/** One page of the feed (U-10 – U-14). */
export async function readFeed(request: FeedRequest): Promise<QueryPage<FeedCard>> {
  const page = await runQuery<Record<string, unknown>>(PLAY.feed, {
    params: { at: request.at, country: request.country, q: request.q?.trim() || null,
      method: request.method ?? null, includeAnswered: request.includeAnswered ?? false,
      languages: request.languages && request.languages.length > 0 ? request.languages.join(',') : null },
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
  /** VIDEO_LINK (Q19): watching it is enough to answer. */
  watchToAnswer: boolean
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
    watchToAnswer: m.watchToAnswer === true,
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
  /** Q33: how a digital prize is delivered and what it is; null for the other methods. */
  digitalDelivery: DigitalDelivery | null
  digitalDescription: string | null
  sponsorName: string | null
  sponsorCompany: string | null
  sponsorIntro: string | null
  sponsorAvatar: string | null
  /** Q60a: the sponsor's published page (`/s/<handle>`), which the name links to; none while it is not published. */
  sponsorHandle?: string | null
  sponsorCountry: string | null
  completions: number
  last1h: number
  last24h: number
  last7d: number
  last30d: number
  /** When the windows' counts were taken (every ten minutes); null before the first run. */
  statsAt: string | null
  canStart: boolean
  /**
   * Q47: whether the caller's profile meets the publication's matching conditions (an invited one: always); when not,
   * the page asks for more of the profile instead of offering the start.
   */
  matched: boolean
  /**
   * Q76: whether the caller has finished (passed, where asked) every publication this one requires, and those
   * publications: in the page's language, whether the caller met each and whether it still accepts attempts.
   */
  prerequisitesMet: boolean
  prerequisites: PublicationPrerequisite[]
  /**
   * Q70a: until when the caller holds the certification this publication is a round of; meanwhile it cannot be
   * started (null for an ordinary publication, or one the caller does not hold).
   */
  heldUntil: string | null
  /** Q70: "referrals welcome" as frozen on the card (its JSON, `ReferralPolicy`); null when not welcome. */
  referral: ReferralPolicy | null
  /** Q70: the caller's own referral to this publication (a friend referred them), and until when it counts. */
  myReferralStatus: string | null
  myReferralExpiresAt: string | null
  myAttemptId: string | null
  myStatus: string | null
  myDeadline: string | null
  /** Q25: who may answer, and whether the caller's account holds an invitation (claimed by `QB_VIEW` or a start). */
  audience: 'PUBLIC' | 'INVITED'
  invited: boolean
  /**
   * Q73b: the languages the publication offers, its main one first, and the one the page is in (the attempt's, else
   * the first of the asked ones it offers, else its main one): the language a start answers in.
   */
  languages: string[]
  displayLanguage: string | null
}

/** Q76: one of a publication's prerequisites as `qb.app.publication` gives it (its `prerequisites` JSON). */
export interface PublicationPrerequisite {
  requiredPublicationId: string
  requirement: 'PASSED' | 'ANSWERED'
  name: string
  /** Whether the required publication accepts attempts now (ended or paused: the taker cannot meet it there). */
  live: boolean
  /** Whether the caller met it. */
  met: boolean
}

/** The `prerequisites` JSON of `qb.app.publication` (and `qb.public.sponsor-page-publications`); none on bad JSON. */
export function prerequisitesOf(value: unknown): PublicationPrerequisite[] {
  let raw: unknown = value
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw)
    } catch {
      return []
    }
  }
  if (!Array.isArray(raw)) return []
  return raw.flatMap((item): PublicationPrerequisite[] => {
    if (!item || typeof item !== 'object') return []
    const p = item as Record<string, unknown>
    if (typeof p.requiredPublicationId !== 'string') return []
    return [{
      requiredPublicationId: p.requiredPublicationId,
      requirement: p.requirement === 'ANSWERED' ? 'ANSWERED' : 'PASSED',
      name: typeof p.name === 'string' ? p.name : '',
      live: p.live === true || p.live === 'true',
      met: p.met === true || p.met === 'true',
    }]
  })
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
  /** Q73b: the languages to read the page in, the preferred first (the publication's tiers have none). */
  languages?: readonly string[]
}

/** A publication's page; null when the caller cannot see it (not live for them and no attempt of theirs). */
export async function readPublication(request: PublicationRequest): Promise<PublicationView | null> {
  const { languages, ...rest } = request
  const page = await runQuery<Record<string, unknown>>(PLAY.publication, { params: { ...rest,
    languages: languages && languages.length > 0 ? languages.join(',') : null }, limit: 1 })
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
    digitalDelivery: digitalDeliveryOf(row.digitalDelivery),
    digitalDescription: textOrNull(row.digitalDescription),
    sponsorName: textOrNull(row.sponsorName),
    sponsorCompany: textOrNull(row.sponsorCompany),
    sponsorIntro: textOrNull(row.sponsorIntro),
    sponsorAvatar: textOrNull(row.sponsorAvatar),
    sponsorHandle: textOrNull(row.sponsorHandle),
    sponsorCountry: textOrNull(row.sponsorCountry),
    completions: numberOf(row.completions),
    last1h: numberOf(row.last1h),
    last24h: numberOf(row.last24h),
    last7d: numberOf(row.last7d),
    last30d: numberOf(row.last30d),
    statsAt: textOrNull(row.statsAt),
    canStart: row.canStart === true || row.canStart === 'true',
    matched: row.matched !== false && row.matched !== 'false',
    prerequisitesMet: row.prerequisitesMet !== false && row.prerequisitesMet !== 'false',
    prerequisites: prerequisitesOf(row.prerequisites),
    heldUntil: textOrNull(row.heldUntil),
    referral: translatedReferral(referralPolicy(row.referral), row.referralDescription),
    myReferralStatus: textOrNull(row.myReferralStatus),
    myReferralExpiresAt: textOrNull(row.myReferralExpiresAt),
    myAttemptId: textOrNull(row.myAttemptId),
    myStatus: textOrNull(row.myStatus),
    myDeadline: textOrNull(row.myDeadline),
    audience: row.audience === 'INVITED' ? 'INVITED' : 'PUBLIC',
    invited: row.invited === true || row.invited === 'true',
    languages: String(row.languages ?? '').split(',').map((l) => l.trim()).filter((l) => l !== ''),
    displayLanguage: textOrNull(row.displayLanguage),
  }
}

/** Q73b: the referral offer with its description in the page's language, when the publication has it. */
function translatedReferral(policy: ReferralPolicy | null, description: unknown): ReferralPolicy | null {
  const text = textOrNull(description)
  return policy && text ? { ...policy, description: text } : policy
}

/** A publication's reward tiers, lowest first (U-20). */
export async function readTiers(request: PublicationRequest): Promise<Tier[]> {
  const { publicationId, at, country } = request
  const page = await runQuery<Record<string, unknown>>(PLAY.publicationTiers, { params: { publicationId, at, country },
    limit: 20 })
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
  /**
   * One option or several: a survey's question as set, a scored one by how many options are correct (one: a single
   * choice); null only from a server before this was given for scored questions (then any number may be chosen).
   */
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
 * retrying the same start (a busy answer, a lost one): the retry of one that went through returns its answer. Q73b: a
 * new attempt answers in `language` (one the publication offers; its main one when none); one in progress keeps its own.
 */
export const startAttempt = (publicationId: string, idempotencyKey?: string, language?: string | null) =>
  runProcess<StartOutput>(PLAY.start, { publicationId, ...(language ? { language } : {}) }, { idempotencyKey })

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

/**
 * `play.RewardStatus`: since Q22 also owed in cash by the sponsor (CASH) or a prize to collect (REDEEM); since Q33 a
 * digital prize (DIGITAL).
 */
export type RewardStatus = 'NONE' | 'PAID' | 'PENDING' | 'CASH' | 'REDEEM' | 'DIGITAL'

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
  /** Q33: the digital prize's record when there is one. */
  digital: DigitalView | null
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

export type RedemptionStatus = 'DUE' | 'CLAIMED' | 'REDEEMED' | 'EXPIRED' | 'VOID'

/**
 * `AttemptResults.RedeemView` (Q22): the prize to collect and until when; a claim from the sponsor's printed code
 * waiting for the sponsor until `confirmBy` (Q22b).
 */
export interface RedeemView {
  redemptionId: string
  status: RedemptionStatus
  code: string
  amount: unknown
  description: string
  expiresAt: string | null
  sponsorCompany: string | null
  confirmBy: string | null
  redeemedAt: string | null
}

/**
 * `reward.DigitalPrizeStatus` (Q33): the sponsor still has to send it (DUE, by `sendBy`), its content can be opened
 * (DELIVERED), the winner said it works (CONFIRMED) or reported a problem (DISPUTED), the sponsor is late (OVERDUE), or
 * QuizBuks voided it.
 */
export type DigitalStatus = 'DUE' | 'DELIVERED' | 'CONFIRMED' | 'DISPUTED' | 'OVERDUE' | 'VOID'

/**
 * `AttemptResults.DigitalView` (Q33): where the digital prize stands. Never its content: that comes only from
 * `openDigitalPrize`, once the winner asks for it (D-Q33-3). `hint` is the end of a pool's code when it is long enough.
 */
export interface DigitalView {
  digitalPrizeId: string
  status: DigitalStatus
  delivery: DigitalDelivery
  amount: unknown
  description: string
  hint: string | null
  deliveredAt: string | null
  sendBy: string | null
  confirmBy: string | null
  openedAt: string | null
  confirmedAt: string | null
  sponsorCompany: string | null
}

/** `DigitalPrizeProcesses.OpenOutput` (Q33): the prize's content, read only when the winner taps "Show prize". */
export interface DigitalPrizeOpened {
  digitalPrizeId: string
  status: DigitalStatus
  description: string
  prizeContent: string
  openedAt: string
}

/** Opens one of the caller's digital prizes (`QB_DIGITAL_PRIZE_OPEN`); the first call records when it was opened. */
export const openDigitalPrize = (digitalPrizeId: string) =>
  runProcess<DigitalPrizeOpened>(PLAY.digitalOpen, { digitalPrizeId })

/** The winner says the digital prize works (`QB_DIGITAL_PRIZE_CONFIRM`). */
export const confirmDigitalPrize = (digitalPrizeId: string) =>
  runProcess<{ digitalPrizeId: string; status: DigitalStatus; revision: number }>(PLAY.digitalConfirm,
    { digitalPrizeId })

/** The winner says what is wrong with the digital prize (`QB_DIGITAL_PRIZE_DISPUTE`); an administrator decides. */
export const disputeDigitalPrize = (digitalPrizeId: string, note: string) =>
  runProcess<{ digitalPrizeId: string; status: DigitalStatus; revision: number }>(PLAY.digitalDispute,
    { digitalPrizeId, note: note.trim() })

/** What a scanned redeem code stands for (`QB_REDEEM_TOKEN_CHECK`, Q22b): the sponsor and the caller's prizes there. */
export interface RedeemCheck {
  sponsorCompany: string | null
  /** ROTATING (collected at once) or PRINTED (a claim the sponsor confirms). */
  kind: 'ROTATING' | 'PRINTED'
  prizes: RedeemPrize[]
}

export interface RedeemPrize {
  redemptionId: string
  /** The attempt the prize was won by: its result page. */
  attemptId: string
  code: string
  amount: unknown
  description: string
  publicationName: string | null
  status: RedemptionStatus
  expiresAt: string | null
}

export const checkRedeemCode = (scannedKey: string) =>
  runProcess<RedeemCheck>(PLAY.redeemCheck, { scannedKey })

/** Collects one prize by the scanned code (`QB_REDEMPTION_CLAIM`): REDEEMED, or CLAIMED for the sponsor to confirm. */
export const claimRedemption = (scannedKey: string, redemptionId: string) =>
  runProcess<{ redemptionId: string; status: RedemptionStatus; revision: number }>(PLAY.redeemClaim,
    { scannedKey, redemptionId })

/** A row of `qb.app.redemptions`: one of the caller's prizes. */
export interface RedemptionRow {
  redemptionId: string
  attemptId: string
  /** Q70: what earned it; a referral's `attemptId` is the referral, which has no result. */
  sourceKind: 'ATTEMPT' | 'REFERRAL'
  code: string
  publicationId: string
  publicationName: string
  sponsorCompany: string | null
  amount: unknown
  description: string
  status: RedemptionStatus
  expiresAt: string | null
  confirmBy: string | null
}

/** The caller's prizes not collected yet (due, or claimed and waiting for the sponsor). */
export async function readOpenRedemptions(): Promise<RedemptionRow[]> {
  const page = await runQuery<Record<string, unknown>>(PLAY.redemptions, { params: { open: true }, limit: 50 })
  return page.items.map((row) => ({
    redemptionId: String(row.redemptionId),
    attemptId: String(row.attemptId ?? ''),
    sourceKind: row.sourceKind === 'REFERRAL' ? 'REFERRAL' : 'ATTEMPT',
    code: String(row.code ?? ''),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    sponsorCompany: textOrNull(row.sponsorCompany),
    amount: row.amount,
    description: String(row.description ?? ''),
    status: String(row.status) as RedemptionStatus,
    expiresAt: textOrNull(row.expiresAt),
    confirmBy: textOrNull(row.confirmBy),
  }))
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
  /** Q70: what earned it; a referral's `attemptId` is the referral, which has no result. */
  sourceKind: 'ATTEMPT' | 'REFERRAL'
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
    sourceKind: row.sourceKind === 'REFERRAL' ? 'REFERRAL' : 'ATTEMPT',
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

/**
 * Where a reward record leads (Q70, D-Q70-20): its attempt's result, or for a referral's reward the referral's page.
 */
export function rewardPath(row: { attemptId: string; sourceKind?: string }): string {
  return row.sourceKind === 'REFERRAL' ? `/referral/${encodeURIComponent(row.attemptId)}`
    : `/result/${encodeURIComponent(row.attemptId)}`
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
  /** Q33: the digital prize's record and where it stands; while DUE, when the sponsor must have sent it. */
  digitalPrizeId: string | null
  digitalStatus: DigitalStatus | null
  digitalSendBy: string | null
  startedAt: string
  submittedAt: string | null
  deadline: string
}

export interface HistoryRequest {
  at: string
  q?: string
  /** Only these categories; all when absent. */
  categories?: HistoryCategory[]
  /** Q58: only the rewards paid this way (a reward of nought is none). */
  method?: RewardMethod
  offset?: number
  limit?: number
}

/** How each method's rewards are recorded on the attempt (`rewardStatus`). */
const STATUSES_OF: Record<RewardMethod, RewardStatus[]> = {
  PLATFORM: ['PAID', 'PENDING'],
  SPONSOR_CASH: ['CASH'],
  OFFLINE_REDEEM: ['REDEEM'],
  DIGITAL_PRIZE: ['DIGITAL'],
}

function historyFilters(request: HistoryRequest) {
  const filters = []
  if (request.categories) filters.push({ field: 'category', op: 'in' as const, values: request.categories })
  if (request.method) filters.push({ field: 'rewardStatus', op: 'in' as const, values: STATUSES_OF[request.method] })
  return filters.length > 0 ? filters : undefined
}

/** One page of the caller's attempts, newest first (U-40 – U-42). */
export async function readHistory(request: HistoryRequest): Promise<QueryPage<HistoryRow>> {
  const page = await runQuery<Record<string, unknown>>(PLAY.history, {
    params: { at: request.at, q: request.q?.trim() || null },
    filters: historyFilters(request),
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
      digitalPrizeId: textOrNull(row.digitalPrizeId),
      digitalStatus: textOrNull(row.digitalStatus) as DigitalStatus | null,
      digitalSendBy: textOrNull(row.digitalSendBy),
      startedAt: String(row.startedAt ?? ''),
      submittedAt: textOrNull(row.submittedAt),
      deadline: String(row.deadline ?? ''),
    })),
  }
}

/** Q70: how a referral reward reaches the referrer (`RewardMethod`). */
export type ReferralMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM' | 'DIGITAL_PRIZE'

/** Q70 (D-Q70-10): a publication's "referrals welcome", as `ReferralPolicy` writes it. */
export interface ReferralPolicy {
  quota: number
  method: ReferralMethod
  amount: number
  description: string | null
  referrerRequires: 'NONE' | 'ANSWERED' | 'PASSED'
  refereeRules: string[]
  days: number
}

/** The policy of a template's `referral` column (its JSON), or null when there is none or it cannot be read. */
export function referralPolicy(value: unknown): ReferralPolicy | null {
  if (value === null || value === undefined || value === '') return null
  try {
    const raw = (typeof value === 'string' ? JSON.parse(value) : value) as Record<string, unknown>
    if (!raw || typeof raw !== 'object') return null
    return {
      quota: Number(raw.quota),
      method: String(raw.method) as ReferralMethod,
      amount: Number(raw.amount),
      description: typeof raw.description === 'string' ? raw.description : null,
      referrerRequires: (raw.referrerRequires ?? 'NONE') as ReferralPolicy['referrerRequires'],
      refereeRules: Array.isArray(raw.refereeRules) ? raw.refereeRules.map(String) : [],
      days: Number(raw.days),
    }
  } catch {
    return null
  }
}
