import { api, ApiError, runProcess, runQuery, toApiError, unwrap, type Filter, type Sort } from '@jabiz/client'
import { readDictionary, type DictionaryItem } from '../qb/api'
import { PUBLISHING_CODE, PUBLISHING_DATASET, PUBLISHING_DICTIONARY, PUBLISHING_PROCESS, PUBLISHING_TEMPLATE } from './ids'

/*
 * The publishing pages' calls, typed by hand after the Java records of backend/quizbuks `publishing` (processes have
 * no OpenAPI types of their own). The end-to-end test `sponsor.spec.ts` runs every one of them against the server.
 */

/** `publishing.RewardMethod` (Q22, dictionary `reward-method`): how the reward reaches the taker. */
export type RewardMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM'
export const REWARD_METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM']
export const REDEEM_DESCRIPTION_LENGTH = 500

/**
 * `publishing.Audience` (Q25, dictionary `publication-audience`): everybody in the regions, or the invitation list
 * only (then the regions are not asked and the publication is reached from its mail alone).
 */
export type Audience = 'PUBLIC' | 'INVITED'
export const AUDIENCES: readonly Audience[] = ['PUBLIC', 'INVITED']
export const audienceOf = (value: unknown): Audience => (value === 'INVITED' ? 'INVITED' : 'PUBLIC')

/** `publishing.PublicationStatus` (dictionary `publication-status`). */
export type PublicationStatus = 'DRAFT' | 'PENDING_REVIEW' | 'SCHEDULED' | 'LIVE' | 'PAUSED' | 'FUNDS_SHORT' | 'ENDED'
export const PUBLICATION_STATUSES: readonly PublicationStatus[] = ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'LIVE',
  'PAUSED', 'FUNDS_SHORT', 'ENDED']

/** `country.Regions.ALL`: the regions a publication is aimed at, in the order the server writes them. */
export const REGIONS = ['GLOBAL', 'JP', 'CN', 'US', 'EUROPE', 'ASIA', 'NORTH_AMERICA', 'SOUTH_AMERICA'] as const

/** The limits of `publishing.PublishingLimits` the pages use to stop typing early and to say them. */
export const PUBLISHING_LIMITS = {
  name: 100,
  tiers: 20,
  minTimeLimitSec: 30,
  maxTimeLimitSec: 7_200,
  maxScore: 999_999,
  maxWinners: 999_999_999,
} as const

/** A reward tier: the scores from and to (both included) and the reward, an amount of the ledger as decimal text. */
export interface Tier {
  tierId: string | null
  fromScore: number
  toScore: number
  reward: string
}

/** A publication as the sponsor's dataset has it (`QbPublication`). Amounts are decimal text. */
export interface Publication {
  publicationId: string
  name: string
  quizId: string | null
  versionId: string | null
  versionLabel: string | null
  regions: string[]
  startAt: string | null
  endAt: string | null
  timeLimitSec: number | null
  passScore: number | null
  budgetCap: string | null
  maxWinners: number | null
  /** How the reward reaches the taker (Q22); a prize at the sponsor's place is described. */
  rewardMethod: RewardMethod
  redeemDescription: string | null
  /** Who may answer (Q25). */
  audience: Audience
  fullScore: number | null
  maxReward: string | null
  maxSpend: string | null
  status: PublicationStatus
  rejectReason: string | null
  rejectCode: string | null
  submittedAt: string | null
  approvedAt: string | null
  pausedAt: string | null
  /** Who paused it: 'SPONSOR' or 'ADMIN' (Q10b: only an administrator resumes what an administrator paused). */
  pausedBy: string | null
  /** The reason an administrator gave for pausing, resuming or ending it. */
  adminReason: string | null
  endedAt: string | null
  endReason: string | null
  /** The number of changes; sent back as `baseRevision`. */
  revision: number
  editedAt: string
  copiedFrom: string | null
}

/** A publication with its tiers in their order. */
export interface PublicationDetail {
  publication: Publication
  tiers: Tier[]
}

/** A row of `qb.sponsor.publications` (M-34). */
export interface PublicationRow {
  publicationId: string
  name: string
  quizTitle: string | null
  versionLabel: string | null
  status: PublicationStatus
  rewardMethod: RewardMethod
  audience: Audience
  regions: string[]
  starts: number
  completions: number
  winners: number
  maxWinners: number | null
  spent: string
  budgetCap: string | null
  maxSpend: string | null
  /** Live but the sponsor's wallet cannot pay the highest reward (or rewards are pending), or underfunded. */
  fundsShort: boolean
  startAt: string | null
  endAt: string | null
  rejectReason: string | null
  rejectCode: string | null
  editedAt: string
}

/** `PublicationProcesses.TierInput`: an existing tier by `tierId`, a new one without. */
export interface TierInput {
  tierId: string | null
  fromScore: number
  toScore: number
  reward: number
}

/** `PublicationProcesses.SaveInput`: every field is written as given (empty clears it; the time limit is then the version's). */
export interface SaveInput {
  publicationId: string | null
  versionId: string | null
  name: string
  regions: string[]
  startAt: string | null
  endAt: string | null
  timeLimitSec: number | null
  passScore: number | null
  budgetCap: number | null
  maxWinners: number | null
  /** The whole list in its order. */
  tiers: TierInput[]
  baseRevision: number | null
  rewardMethod: RewardMethod
  redeemDescription: string | null
  audience: Audience
}

/** One problem submitting would be refused for, in the caller's language (`SaveOutput.Problem`). */
export interface Problem {
  field: string | null
  code: string
  message: string
  params: Record<string, unknown> | null
}

/** `SaveOutput`: the draft after a save or a copy, with what the wizard's last step shows. */
export interface SaveOutput {
  publicationId: string
  status: PublicationStatus
  revision: number
  editedAt: string
  fullScore: number | null
  maxReward: string | null
  maxSpend: string | null
  tierIds: string[]
  /** Empty when the publication can be submitted. */
  problems: Problem[]
}

/** `PublicationOutput`: a publication after a process moved it. */
export interface PublicationOutput {
  publicationId: string
  status: PublicationStatus
  revision: number
  /** `PENDING`, `APPROVED` or `NOT_REQUIRED` when submitting asked for a review. */
  approval: string | null
  approvalRequestId: string | null
}

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))
const str = (value: unknown): string | null => (value == null ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** A reward method as the server writes it; the platform's for a row from before Q22. */
export const methodOf = (value: unknown): RewardMethod =>
  (REWARD_METHODS as readonly string[]).includes(String(value)) ? (value as RewardMethod) : 'PLATFORM'

/** The regions of their written form (`GLOBAL,JP`). */
export const regionsOf = (value: unknown): string[] => (value == null || value === '' ? [] : String(value).split(','))

export function toPublication(row: Row): Publication {
  return {
    publicationId: String(row.publicationId),
    name: String(row.name ?? ''),
    quizId: str(row.quizId),
    versionId: str(row.versionId),
    versionLabel: str(row.versionLabel),
    regions: regionsOf(row.regions),
    startAt: str(row.startAt),
    endAt: str(row.endAt),
    timeLimitSec: numOrNull(row.timeLimitSec),
    passScore: numOrNull(row.passScore),
    budgetCap: str(row.budgetCap),
    maxWinners: numOrNull(row.maxWinners),
    rewardMethod: methodOf(row.rewardMethod),
    redeemDescription: str(row.redeemDescription),
    audience: audienceOf(row.audience),
    fullScore: numOrNull(row.fullScore),
    maxReward: str(row.maxReward),
    maxSpend: str(row.maxSpend),
    status: (row.status as PublicationStatus) ?? 'DRAFT',
    rejectReason: str(row.rejectReason),
    rejectCode: str(row.rejectCode),
    submittedAt: str(row.submittedAt),
    approvedAt: str(row.approvedAt),
    pausedAt: str(row.pausedAt),
    pausedBy: str(row.pausedBy),
    adminReason: str(row.adminReason),
    endedAt: str(row.endedAt),
    endReason: str(row.endReason),
    revision: num(row.revision),
    editedAt: String(row.editedAt ?? ''),
    copiedFrom: str(row.copiedFrom),
  }
}

export function toTiers(rows: Row[]): Tier[] {
  return [...rows].sort((a, b) => num(a.seq) - num(b.seq)).map((row) => ({
    tierId: String(row.tierId),
    fromScore: num(row.fromScore),
    toScore: num(row.toScore),
    reward: String(row.reward ?? '0'),
  }))
}

/** The most rows a dataset answers at once (the platform's page limit); a publication has at most 20 tiers. */
const PAGE = 100

/**
 * A publication with its tiers, from the sponsor's datasets; null when it is not theirs (or deleted): the datasets
 * show only the sponsor's own rows.
 */
export async function readPublication(publicationId: string): Promise<PublicationDetail | null> {
  const [publication, tiers] = await Promise.all([readPublicationRow(publicationId), readTierRows(publicationId)])
  return publication ? { publication: toPublication(publication), tiers: toTiers(tiers) } : null
}

/** The publication's own row by its key; null when the sponsor's dataset does not have it. */
async function readPublicationRow(publicationId: string): Promise<Row | null> {
  const { data, error, response } = await api.GET('/api/datasets/{resourceId}/entities/{id}', {
    params: { path: { resourceId: PUBLISHING_DATASET.publication, id: publicationId } },
  })
  if (response.status === 404) return null
  if (error !== undefined || !data) throw await toApiError(response, error)
  return (data.attributes ?? {}) as Row
}

async function readTierRows(publicationId: string): Promise<Row[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: PUBLISHING_DATASET.tier } },
    body: { limit: PAGE, filters: [{ field: 'publicationId', op: 'eq', value: publicationId }],
      sorts: [{ field: 'seq', asc: true }] },
  }))
  return (page.items ?? []).map((item) => (item.attributes ?? {}) as Row)
}

export interface PublicationSearch {
  q?: string
  status?: PublicationStatus | null
  sorts?: Sort[]
  offset: number
  limit: number
}

/** The sponsor's publications, one page, counted. */
export async function searchPublications({ q, status, sorts, offset, limit }: PublicationSearch) {
  const text = q?.trim()
  const filters: Filter[] = status ? [{ field: 'status', op: 'eq', value: status }] : []
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.publications, {
    params: text ? { q: text } : {},
    filters,
    sorts,
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): PublicationRow => ({
      publicationId: String(row.publicationId),
      name: String(row.name ?? ''),
      quizTitle: str(row.quizTitle),
      versionLabel: str(row.versionLabel),
      status: (row.status as PublicationStatus) ?? 'DRAFT',
      rewardMethod: methodOf(row.rewardMethod),
      audience: audienceOf(row.audience),
      regions: regionsOf(row.regions),
      starts: num(row.starts),
      completions: num(row.completions),
      winners: num(row.winners),
      maxWinners: numOrNull(row.maxWinners),
      spent: String(row.spent ?? '0'),
      budgetCap: str(row.budgetCap),
      maxSpend: str(row.maxSpend),
      fundsShort: bool(row.fundsShort),
      startAt: str(row.startAt),
      endAt: str(row.endAt),
      rejectReason: str(row.rejectReason),
      rejectCode: str(row.rejectCode),
      editedAt: String(row.editedAt ?? ''),
    })),
  }
}

/** The labels of publication states, end reasons and regions, in the request's language. */
export const readPublicationStatuses = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.status)
export const readEndReasons = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.endReason)
export const readRegions = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.region)
export const readRewardMethods = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.rewardMethod)
export const readCashStatuses = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.cashStatus)
export const readAudiences = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.audience)
export const readInvitationStatuses = (): Promise<DictionaryItem[]> =>
  readDictionary(PUBLISHING_DICTIONARY.invitationStatus)

/**
 * The reward methods an administrator allowed the sponsor (Q22): their own row of the setting, or the platform's
 * Kudos alone without one. The server checks again when a draft is saved and submitted.
 */
export async function readAllowedMethods(): Promise<RewardMethod[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: PUBLISHING_DATASET.rewardMethods } },
    body: { limit: 1 },
  }))
  const row = (page.items ?? [])[0]?.attributes as Row | undefined
  const methods = row ? String(row.methods ?? '').split(',').map(methodOf) : []
  return methods.length > 0 ? REWARD_METHODS.filter((method) => methods.includes(method)) : ['PLATFORM']
}

// ---- cash rewards (Q22) -------------------------------------------------------------------------------------------

/** `reward.CashRewardStatus` (dictionary `cash-reward-status`). */
export type CashStatus = 'DUE' | 'MARKED_PAID' | 'CONFIRMED' | 'DISPUTED' | 'OVERDUE' | 'VOID'
export const CASH_STATUSES: readonly CashStatus[] = ['DUE', 'MARKED_PAID', 'CONFIRMED', 'DISPUTED', 'OVERDUE', 'VOID']

/** A row of `qb.sponsor.cash-rewards`: what the sponsor owes a taker for one attempt, and where it stands. */
export interface CashRewardRow {
  cashRewardId: string
  code: string
  publicationId: string
  publicationName: string
  takerName: string | null
  amount: string
  status: CashStatus
  dueAt: string
  payBy: string | null
  markedPaidAt: string | null
  markedPaidNote: string | null
  confirmBy: string | null
  confirmedAt: string | null
  disputedAt: string | null
  disputeNote: string | null
  resolution: string | null
  resolveNote: string | null
}

export interface CashRewardSearch {
  q?: string
  status?: CashStatus | null
  offset: number
  limit: number
}

export async function searchCashRewards({ q, status, offset, limit }: CashRewardSearch) {
  const text = q?.trim()
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.cashRewards, {
    params: text ? { q: text } : {},
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): CashRewardRow => ({
      cashRewardId: String(row.cashRewardId),
      code: String(row.code ?? ''),
      publicationId: String(row.publicationId),
      publicationName: String(row.publicationName ?? ''),
      takerName: str(row.takerName),
      amount: String(row.amount ?? '0'),
      status: (CASH_STATUSES as readonly string[]).includes(String(row.status)) ? (row.status as CashStatus) : 'DUE',
      dueAt: String(row.dueAt ?? ''),
      payBy: str(row.payBy),
      markedPaidAt: str(row.markedPaidAt),
      markedPaidNote: str(row.markedPaidNote),
      confirmBy: str(row.confirmBy),
      confirmedAt: str(row.confirmedAt),
      disputedAt: str(row.disputedAt),
      disputeNote: str(row.disputeNote),
      resolution: str(row.resolution),
      resolveNote: str(row.resolveNote),
    })),
  }
}

/** The sponsor handed the taker the cash: `QB_CASH_REWARD_MARK_PAID`, with a note for the record. */
export const markCashPaid = (cashRewardId: string, note: string) =>
  runProcess<{ cashRewardId: string; status: CashStatus; revision: number }>(PUBLISHING_PROCESS.cashMarkPaid,
    { cashRewardId, note: note.trim() || null })

function toSaveOutput(output: Row): SaveOutput {
  return {
    publicationId: String(output.publicationId),
    status: (output.status as PublicationStatus) ?? 'DRAFT',
    revision: num(output.revision),
    editedAt: String(output.editedAt ?? ''),
    fullScore: numOrNull(output.fullScore),
    maxReward: str(output.maxReward),
    maxSpend: str(output.maxSpend),
    tierIds: ((output.tierIds as unknown[] | undefined) ?? []).map(String),
    problems: ((output.problems as Row[] | undefined) ?? []).map((p) => ({
      field: str(p.field),
      code: String(p.code ?? ''),
      message: String(p.message ?? ''),
      params: (p.params as Record<string, unknown> | null) ?? null,
    })),
  }
}

/** Creates a draft (without `publicationId`) or changes one. */
export const savePublication = async (input: SaveInput): Promise<SaveOutput> =>
  toSaveOutput(await runProcess<Row>(PUBLISHING_PROCESS.save, { ...input }))

/** A new draft with a publication's policy and tiers; `name` is the server's "… (copy)" when not given. */
export const copyPublication = async (publicationId: string, name?: string | null): Promise<SaveOutput> =>
  toSaveOutput(await runProcess<Row>(PUBLISHING_PROCESS.copy, { publicationId, name: name?.trim() || null }))

export const deletePublication = (publicationId: string, baseRevision: number) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.delete, { publicationId, baseRevision })

/** Submits a draft: every problem at once (422), or in review, or on at once when no review is needed. */
export const submitPublication = (publicationId: string, baseRevision: number) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.submit, { publicationId, baseRevision })

export const withdrawPublication = (publicationId: string) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.withdraw, { publicationId })

export const pausePublication = (publicationId: string) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.pause, { publicationId })

export const resumePublication = (publicationId: string) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.resume, { publicationId })

/**
 * Whether a refusal means the page is out of date: the publication changed since it was read
 * (`QB_PUBLICATION_CHANGED`) or is no draft any more (`QB_PUBLICATION_NOT_DRAFT`). The page then offers to reload.
 */
export function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.violations.some((v) =>
    v.ruleCode === PUBLISHING_CODE.changed || v.ruleCode === PUBLISHING_CODE.notDraft)
}

/**
 * What can be done with a publication in a state (the processes' `actsOn`); the server decides all the same. A
 * publication an administrator paused is not the sponsor's to resume, and one an administrator paused or ended is not
 * copied (Q10b, review M-d).
 */
export function actionsOf(status: PublicationStatus, pausedBy: string | null = null,
  endReason: string | null = null) {
  // Paused by an administrator stays so when it ends afterwards (verification of review M-d).
  const takenDown = pausedBy === 'ADMIN' || endReason === 'ADMIN'
  return {
    edit: status === 'DRAFT',
    submit: status === 'DRAFT',
    delete: status === 'DRAFT',
    withdraw: status === 'PENDING_REVIEW',
    pause: status === 'SCHEDULED' || status === 'LIVE' || status === 'FUNDS_SHORT',
    resume: status === 'PAUSED' && pausedBy !== 'ADMIN',
    copy: !takenDown,
  }
}

/**
 * What the time says beyond the state (the state follows the times within a minute, docs/quizbuks/plans/
 * Q4-publishing.md): a scheduled publication whose start has come is about to go live, one that is on after its end is
 * about to end. Whether it takes attempts is always the times' answer.
 */
export function timeNote(status: PublicationStatus, startAt: string | null, endAt: string | null,
  now: number): 'starting' | 'ending' | null {
  const start = startAt ? Date.parse(startAt) : NaN
  const end = endAt ? Date.parse(endAt) : NaN
  if ((status === 'SCHEDULED' || status === 'LIVE' || status === 'PAUSED' || status === 'FUNDS_SHORT') && end <= now) {
    return 'ending'
  }
  if (status === 'SCHEDULED' && start <= now) return 'starting'
  return null
}

// ---- the wizard's form ---------------------------------------------------------------------------------------------

/** A tier as typed: numbers as text, a key of its own for React. */
export interface TierDraft {
  key: string
  tierId: string | null
  fromScore: string
  toScore: string
  reward: string
}

/** The wizard's form: the policy as typed. Blank numbers are none (the time limit: the version's). */
export interface PublicationForm {
  name: string
  quizId: string | null
  versionId: string | null
  regions: string[]
  startAt: string | null
  endAt: string | null
  timeLimitSec: string
  passScore: string
  budgetCap: string
  maxWinners: string
  tiers: TierDraft[]
  rewardMethod: RewardMethod
  redeemDescription: string
  audience: Audience
}

let keys = 0
/** A key of a tier being edited. */
export const tierKey = () => `tier-${++keys}`

const text = (value: number | string | null | undefined) => (value == null ? '' : String(value))

/** The form of a publication as read; an empty form for a new one. */
export function formOf(detail: PublicationDetail | null): PublicationForm {
  const p = detail?.publication
  return {
    name: p?.name ?? '',
    quizId: p?.quizId ?? null,
    versionId: p?.versionId ?? null,
    regions: p?.regions ?? [],
    startAt: p?.startAt ?? null,
    endAt: p?.endAt ?? null,
    timeLimitSec: text(p?.timeLimitSec),
    passScore: text(p?.passScore),
    budgetCap: text(p?.budgetCap),
    maxWinners: text(p?.maxWinners),
    rewardMethod: p?.rewardMethod ?? 'PLATFORM',
    redeemDescription: p?.redeemDescription ?? '',
    audience: p?.audience ?? 'PUBLIC',
    tiers: (detail?.tiers ?? []).map((tier) => ({ key: tierKey(), tierId: tier.tierId,
      fromScore: String(tier.fromScore), toScore: String(tier.toScore), reward: tier.reward })),
  }
}

/** A whole number typed into a field: null when blank, NaN when not a whole number of at least 0. */
function whole(value: string): number | null {
  if (value.trim() === '') return null
  const n = Number(value.trim())
  return Number.isInteger(n) && n >= 0 ? n : Number.NaN
}

/** Why a typed value cannot be sent, by field path (`passScore`, `tiers[1].reward`): message keys of the page. */
export type FormErrors = Record<string, 'wholeNumber' | 'required'>

/**
 * The input of `QB_PUBLICATION_SAVE` for the form, or what is typed wrong. Only the form's own shapes are checked
 * here (whole numbers, a tier's three values); the server checks everything else and lists it as problems.
 */
export function inputOf(form: PublicationForm, publicationId: string | null, baseRevision: number | null):
  { input: SaveInput; errors?: undefined } | { input?: undefined; errors: FormErrors } {
  const errors: FormErrors = {}
  const read = (field: string, value: string) => {
    const n = whole(value)
    if (Number.isNaN(n)) errors[field] = 'wholeNumber'
    return n
  }
  const timeLimitSec = read('timeLimitSec', form.timeLimitSec)
  const passScore = read('passScore', form.passScore)
  const budgetCap = read('budgetCap', form.budgetCap)
  const maxWinners = read('maxWinners', form.maxWinners)
  const tiers: TierInput[] = form.tiers.map((tier, i) => {
    const values = (['fromScore', 'toScore', 'reward'] as const).map((part) => {
      const field = `tiers[${i}].${part}`
      const n = read(field, tier[part])
      if (n === null) errors[field] = 'required'
      return n ?? 0
    })
    return { tierId: tier.tierId, fromScore: values[0], toScore: values[1], reward: values[2] }
  })
  if (Object.keys(errors).length > 0) return { errors }
  return {
    input: {
      publicationId,
      versionId: form.versionId,
      name: form.name.trim(),
      // An invited publication has no regions: its list stands in for them (Q25).
      regions: form.audience === 'INVITED' ? [] : REGIONS.filter((region) => form.regions.includes(region)),
      startAt: form.startAt,
      endAt: form.endAt,
      timeLimitSec,
      passScore,
      budgetCap,
      maxWinners,
      tiers,
      baseRevision,
      rewardMethod: form.rewardMethod,
      redeemDescription: form.rewardMethod === 'OFFLINE_REDEEM' && form.redeemDescription.trim()
        ? form.redeemDescription.trim() : null,
      audience: form.audience,
    },
  }
}

/** A survey's one reward as typed: its full score's tier's (Q12). */
export const surveyReward = (form: PublicationForm): string => form.tiers.at(-1)?.reward ?? ''

/**
 * The form of a survey's publication (Q12, D-Q12-4): its pass mark is the full score (every question answered, a
 * point each) and its tiers are nothing below it and the one reward at it. The tiers keep their keys, so the next save
 * sends them as the same tiers; the server checks the shape.
 */
export function asSurvey(form: PublicationForm, fullScore: number, reward: string): PublicationForm {
  const tier = (i: number, from: number, to: number, value: string): TierDraft => ({ key: form.tiers[i]?.key ?? tierKey(),
    tierId: form.tiers[i]?.tierId ?? null, fromScore: String(from), toScore: String(to), reward: value })
  // Before the reward is typed (the first step saves a draft) there are no tiers yet: a blank one would be refused as
  // typed wrong on a step that does not show it. The check before submitting asks for the tiers.
  if (reward.trim() === '') return { ...form, passScore: String(fullScore), tiers: [] }
  return { ...form, passScore: String(fullScore),
    tiers: [tier(0, 0, Math.max(fullScore - 1, 0), '0'), tier(1, fullScore, fullScore, reward)] }
}

/**
 * A new tier after the last: from the score after the last one's end (0 for the first) to the full score, without a
 * reward yet.
 */
export function nextTier(tiers: readonly TierDraft[], fullScore: number | null): TierDraft {
  const last = tiers.at(-1)
  const end = last ? whole(last.toScore) : null
  const from = last ? (end !== null && !Number.isNaN(end) ? end + 1 : '') : 0
  return { key: tierKey(), tierId: null, fromScore: String(from), toScore: fullScore === null ? '' : String(fullScore),
    reward: '' }
}

/** The fields of the publication the pages name (`entity.QbPublication.<field>` on the server). */
export const PUBLICATION_FIELDS = ['name', 'versionId', 'regions', 'startAt', 'endAt', 'timeLimitSec', 'passScore',
  'budgetCap', 'maxWinners', 'rewardMethod', 'redeemDescription', 'audience'] as const
export type PublicationField = (typeof PUBLICATION_FIELDS)[number]

/**
 * The part of the form a problem is about, for its label: a field of the publication, the reward tiers (the server's
 * message names the tier itself) or the content of the quiz version; null when it names none of them (the sponsor's
 * own approval, for instance).
 */
export function problemPlace(field: string | null): PublicationField | 'tiers' | 'version' | null {
  if (!field) return null
  if (field === 'tiers' || field.startsWith('tiers[')) return 'tiers'
  if (field === 'version' || field.startsWith('version.')) return 'version'
  return (PUBLICATION_FIELDS as readonly string[]).includes(field) ? (field as PublicationField) : null
}

// ---- prizes collected at the sponsor's place (Q22b) ------------------------------------------------------------------

export type RedemptionStatus = 'DUE' | 'CLAIMED' | 'REDEEMED' | 'EXPIRED' | 'VOID'
export const REDEMPTION_STATUSES: readonly RedemptionStatus[] = ['DUE', 'CLAIMED', 'REDEEMED', 'EXPIRED', 'VOID']
export type RedeemKind = 'ROTATING' | 'PRINTED'

/** A code issued by `QB_REDEEM_CODE_ISSUE`: the secret for the QR's link, and when a rotating one stops working. */
export interface RedeemCode {
  redeemKey: string
  kind: RedeemKind
  issuedAt: string
  expiresAt: string | null
}

/**
 * Issues a code. Without an idempotency key on purpose: the platform keeps the output of a keyed call for replays,
 * and the key in the QR is a secret that should live nowhere but in the console's memory (and the token table).
 */
export const issueRedeemCode = async (kind: RedeemKind, renew = false): Promise<RedeemCode> => {
  const answer = await unwrap(api.POST('/api/processes/{name}/{version}', {
    params: { path: { name: PUBLISHING_PROCESS.redeemIssue, version: 'latest' } },
    body: renew ? { kind, renew: true } : { kind },
  }))
  return (answer as { output?: RedeemCode }).output as RedeemCode
}

export const readRedemptionStatuses = (): Promise<DictionaryItem[]> =>
  readDictionary(PUBLISHING_DICTIONARY.redemptionStatus)

/** A row of `qb.sponsor.redemptions`. */
export interface RedemptionRow {
  redemptionId: string
  code: string
  publicationId: string
  publicationName: string
  takerName: string | null
  amount: number
  description: string
  status: RedemptionStatus
  dueAt: string
  expiresAt: string | null
  claimKind: RedeemKind | null
  claimedAt: string | null
  confirmBy: string | null
  redeemedAt: string | null
}

export interface RedemptionSearch {
  q?: string
  status?: RedemptionStatus | null
  /** Only prizes claimed or collected at or after this time (the redeem page's recent list). */
  since?: string | null
  offset: number
  limit: number
}

export async function searchRedemptions({ q, status, since, offset, limit }: RedemptionSearch) {
  const text = q?.trim()
  const params: Record<string, unknown> = {}
  if (text) params.q = text
  if (since) params.since = since
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.redemptions, {
    params,
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): RedemptionRow => ({
      redemptionId: String(row.redemptionId),
      code: String(row.code ?? ''),
      publicationId: String(row.publicationId),
      publicationName: String(row.publicationName ?? ''),
      takerName: row.takerName == null ? null : String(row.takerName),
      amount: Number(row.amount ?? 0),
      description: String(row.description ?? ''),
      status: String(row.status) as RedemptionStatus,
      dueAt: String(row.dueAt),
      expiresAt: row.expiresAt == null ? null : String(row.expiresAt),
      claimKind: row.claimKind == null ? null : (String(row.claimKind) as RedeemKind),
      claimedAt: row.claimedAt == null ? null : String(row.claimedAt),
      confirmBy: row.confirmBy == null ? null : String(row.confirmBy),
      redeemedAt: row.redeemedAt == null ? null : String(row.redeemedAt),
    })),
  }
}

type RedemptionOutput = { redemptionId: string; status: RedemptionStatus; revision: number }

/** The claim at the counter is the person: collected. */
export const confirmRedemption = (redemptionId: string) =>
  runProcess<RedemptionOutput>(PUBLISHING_PROCESS.redemptionConfirm, { redemptionId })

/** The claim is not the person at the counter: the prize is due again. */
export const declineRedemption = (redemptionId: string) =>
  runProcess<RedemptionOutput>(PUBLISHING_PROCESS.redemptionDecline, { redemptionId })

// ---- the invitation list (Q25) -------------------------------------------------------------------------------------

/** `invitation.InvitationStatus`. */
export type InvitationStatus = 'ADDED' | 'QUEUED' | 'SENT' | 'STARTED' | 'ANSWERED'
export const INVITATION_STATUSES: readonly InvitationStatus[] = ['ADDED', 'QUEUED', 'SENT', 'STARTED', 'ANSWERED']
/** How many addresses one call of `QB_INVITATION_ADD` takes (`QbInvitations.MAX_PER_CALL`). */
export const INVITATIONS_PER_CALL = 500

/** A row of `qb.sponsor.invitations`. */
export interface InvitationRow {
  invitationId: string
  email: string
  status: InvitationStatus
  addedAt: string
  sentCount: number
  lastSentAt: string | null
  startedAt: string | null
  answeredAt: string | null
  score: number | null
  reward: string | null
}

export interface InvitationSummary {
  total: number
  added: number
  queued: number
  sent: number
  started: number
  answered: number
}

export interface InvitationSearch {
  publicationId: string
  q?: string
  status?: InvitationStatus | null
  offset: number
  limit: number
}

export async function searchInvitations({ publicationId, q, status, offset, limit }: InvitationSearch) {
  const params: Record<string, unknown> = { publicationId }
  const text = q?.trim()
  if (text) params.q = text
  if (status) params.status = status
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.invitations, { params, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row): InvitationRow => ({
      invitationId: String(row.invitationId),
      email: String(row.email ?? ''),
      status: String(row.status) as InvitationStatus,
      addedAt: String(row.addedAt),
      sentCount: num(row.sentCount),
      lastSentAt: str(row.lastSentAt),
      startedAt: str(row.startedAt),
      answeredAt: str(row.answeredAt),
      score: numOrNull(row.score),
      reward: str(row.reward),
    })),
  }
}

export async function readInvitationSummary(publicationId: string): Promise<InvitationSummary> {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.invitationSummary, { params: { publicationId }, limit: 1 })
  const row = page.items[0] ?? {}
  return { total: num(row.total), added: num(row.added), queued: num(row.queued), sent: num(row.sent),
    started: num(row.started), answered: num(row.answered) }
}

/** The addresses typed or pasted, one per line or separated by commas, semicolons or spaces; duplicates kept once. */
export function splitAddresses(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map((part) => part.trim()).filter(Boolean))]
}

export interface AddInvitationsOutput { publicationId: string; added: number; skipped: number; total: number }
export interface SendInvitationsOutput { publicationId: string; queued: number; remainingToday: number }

export const addInvitations = (publicationId: string, emails: string[]) =>
  runProcess<AddInvitationsOutput>(PUBLISHING_PROCESS.invitationAdd, { publicationId, emails })
export const removeInvitation = (invitationId: string) =>
  runProcess<{ invitationId: string }>(PUBLISHING_PROCESS.invitationRemove, { invitationId })
export const sendInvitations = (publicationId: string) =>
  runProcess<SendInvitationsOutput>(PUBLISHING_PROCESS.invitationSend, { publicationId })
