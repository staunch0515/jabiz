import { api, ApiError, runProcess, runQuery, toApiError, unwrap, type Filter, type Sort } from '@jabiz/client'
import { CONTENT_LANGUAGES, readDictionary, type ContentLanguage, type DictionaryItem } from '../qb/api'
import { minuteNow } from '../qb/legal'
import { CRITERIA, type Condition } from '../qb/audience'
import { AUDIENCE } from '../qb/ids'
import { referralPolicy, type ReferralPolicy } from '../qb/play'
import { PUBLISHING_CODE, PUBLISHING_DATASET, PUBLISHING_DICTIONARY, PUBLISHING_PROCESS, PUBLISHING_TEMPLATE } from './ids'

/*
 * The publishing pages' calls, typed by hand after the Java records of backend/quizbuks `publishing` (processes have
 * no OpenAPI types of their own). The end-to-end test `sponsor.spec.ts` runs every one of them against the server.
 */

/** `publishing.RewardMethod` (Q22, Q33, dictionary `reward-method`): how the reward reaches the taker. */
export type RewardMethod = 'PLATFORM' | 'SPONSOR_CASH' | 'OFFLINE_REDEEM' | 'DIGITAL_PRIZE'
export const REWARD_METHODS: readonly RewardMethod[] = ['PLATFORM', 'SPONSOR_CASH', 'OFFLINE_REDEEM', 'DIGITAL_PRIZE']
export const REDEEM_DESCRIPTION_LENGTH = 500

/**
 * `publishing.DigitalDelivery` (Q33, dictionary `digital-delivery`): how a digital prize reaches the winner. A pool
 * of one-time codes the sponsor loads (one each), one code or link every winner gets, or the sponsor sends it by
 * hand within a number of days.
 */
export type DigitalDelivery = 'POOL' | 'SHARED' | 'SPONSOR_SENT'
export const DIGITAL_DELIVERIES: readonly DigitalDelivery[] = ['POOL', 'SHARED', 'SPONSOR_SENT']
export const DIGITAL_DESCRIPTION_LENGTH = 500
export const DIGITAL_CONTENT_LENGTH = 500
export const deliveryOf = (value: unknown): DigitalDelivery | null =>
  (DIGITAL_DELIVERIES as readonly string[]).includes(String(value)) ? (value as DigitalDelivery) : null

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
  /** Q64: the publication's own cover; none shows the quiz version's. */
  cover: string | null
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
  /** A digital prize (Q33): how it reaches the winner, what it is, and the days a sponsor has to send one by hand. */
  digitalDelivery: DigitalDelivery | null
  digitalDescription: string | null
  digitalSendDays: number | null
  /** Who may answer (Q25). */
  audience: Audience
  /** Q70: "referrals welcome"; null when not. */
  referral: ReferralPolicy | null
  /** Q73b: the languages offered, the main one first (none: the version's base only), and the texts in them. */
  languages: ContentLanguage[]
  translations: Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>>
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
  /** A switch to another version of the quiz in review (Q51): the version, its label and since when; none without. */
  pendingVersionId: string | null
  pendingVersionLabel: string | null
  pendingSince: string | null
  /** Why the last switch did not happen: the reviewer's reason, or a code when it was dropped (ended, taken down). */
  switchRejectReason: string | null
  switchRejectCode: string | null
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
  /** The quiz's cover as its version keeps it (Q34b); none for a draft without a version. */
  cover: string | null
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
  /** Q64: the publication's own cover (a `qb.content.image` file); null shows the version's. Sent with each save. */
  cover: string | null
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
  /**
   * Q33, with a digital prize only. The shared content is a secret the server seals and never reads back: left out
   * (blank in the form), the one saved stays.
   */
  digitalDelivery?: DigitalDelivery | null
  digitalDescription?: string | null
  digitalSendDays?: number | null
  digitalContent?: string
  audience: Audience
  /** Q70: "referrals welcome", whole; null for not welcome. */
  referral?: ReferralInput | null
  /** Q73b: the languages offered, the main one first (none: the version's base only). */
  languages?: ContentLanguage[] | null
  /** Q73b: the publication's texts in each offered language other than the base, the whole set. */
  translations?: Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>> | null
}

/** Q73b: the publication's own texts that are translated (`PublicationLanguages.FIELDS`). */
export type PublicationTextField = 'name' | 'redeemDescription' | 'digitalDescription' | 'referralDescription'
export const PUBLICATION_TEXT_FIELDS: readonly PublicationTextField[] = ['name', 'redeemDescription',
  'digitalDescription', 'referralDescription']

/** The languages of a column (`zh,en`), the platform's only, in its order. */
export function languagesOf(value: unknown): ContentLanguage[] {
  return String(value ?? '').split(',').map((l) => l.trim())
    .filter((l): l is ContentLanguage => (CONTENT_LANGUAGES as readonly string[]).includes(l))
}

/** The stored translations' texts (`{lang: {field: {text, hash}}}`), by language and field. */
export function translationsOf(value: unknown):
  Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>> {
  if (value == null || value === '') return {}
  try {
    const parsed = JSON.parse(String(value)) as Record<string, Record<string, { text?: string }>>
    const out: Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>> = {}
    for (const [language, fields] of Object.entries(parsed)) {
      if (!(CONTENT_LANGUAGES as readonly string[]).includes(language)) continue
      const texts: Partial<Record<PublicationTextField, string>> = {}
      for (const [field, text] of Object.entries(fields)) {
        if ((PUBLICATION_TEXT_FIELDS as readonly string[]).includes(field) && text?.text) {
          texts[field as PublicationTextField] = text.text
        }
      }
      out[language as ContentLanguage] = texts
    }
    return out
  } catch {
    return {}
  }
}

/** Q70: `PublicationProcesses.ReferralInput`. */
export interface ReferralInput {
  quota: number | null
  method: RewardMethod
  amount: number | null
  description: string | null
  referrerRequires: ReferrerRequirement
  refereeRules: string[]
  days: number | null
}

export type ReferrerRequirement = 'NONE' | 'ANSWERED' | 'PASSED'
export const REFERRER_REQUIREMENTS: readonly ReferrerRequirement[] = ['NONE', 'ANSWERED', 'PASSED']
/** `publishing.RefereeRules.ALL`, in its order; a new referral ticks `NOT_REGULAR` (only a default). */
export const REFEREE_RULES = ['NOT_REGULAR', 'NEW_TO_PLATFORM', 'MATCHES_AUDIENCE', 'SAME_REGION_AS_REFERRER'] as const
export const DEFAULT_REFEREE_RULES = ['NOT_REGULAR']

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
    cover: str(row.cover),
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
    digitalDelivery: deliveryOf(row.digitalDelivery),
    digitalDescription: str(row.digitalDescription),
    digitalSendDays: numOrNull(row.digitalSendDays),
    audience: audienceOf(row.audience),
    referral: referralPolicy(row.referral),
    languages: languagesOf(row.languages),
    translations: translationsOf(row.translations),
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
    pendingVersionId: str(row.pendingVersionId),
    pendingVersionLabel: str(row.pendingVersionLabel),
    pendingSince: str(row.pendingSince),
    switchRejectReason: str(row.switchRejectReason),
    switchRejectCode: str(row.switchRejectCode),
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
      cover: str(row.cover),
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
export const readDigitalDeliveries = (): Promise<DictionaryItem[]> =>
  readDictionary(PUBLISHING_DICTIONARY.digitalDelivery)
export const readDigitalStatuses = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.digitalStatus)
export const readDigitalCodeStatuses = (): Promise<DictionaryItem[]> =>
  readDictionary(PUBLISHING_DICTIONARY.digitalCodeStatus)
export const readAudiences = (): Promise<DictionaryItem[]> => readDictionary(PUBLISHING_DICTIONARY.audience)
export const readInvitationStatuses = (): Promise<DictionaryItem[]> =>
  readDictionary(PUBLISHING_DICTIONARY.invitationStatus)

/** What the sponsor may publish with now (Q22, Q28), from `qb.sponsor.reward-methods`. */
export interface RewardSettings {
  /** The methods, in the fixed order; empty when nothing is allowed yet. */
  methods: RewardMethod[]
  /** Whether an administrator set them for this sponsor (otherwise the platform's default applies). */
  own: boolean
  /** The highest reward one attempt may win, in Kudos; null for no cap. */
  maxRewardPerAttempt: number | null
}

/**
 * The reward methods the sponsor may publish with (Q22): their own row of the setting, or without one the platform's
 * default parameter (Q28), and the highest reward per attempt. The server checks again when a draft is saved and
 * submitted.
 */
export async function readRewardSettings(): Promise<RewardSettings> {
  const row = (await runQuery<Row>(PUBLISHING_TEMPLATE.rewardMethods, { limit: 1 })).items[0] ?? {}
  // Names that are no method are dropped, as the server drops them (a typo allows less, never more).
  const named = String(row.methods ?? '').split(',').map((name) => name.trim())
  const max = row.maxRewardPerAttempt == null || row.maxRewardPerAttempt === '' ? null : Number(row.maxRewardPerAttempt)
  return {
    methods: REWARD_METHODS.filter((method) => named.includes(method)),
    own: row.own === true || row.own === 'true',
    maxRewardPerAttempt: max != null && Number.isFinite(max) && max > 0 ? max : null,
  }
}

/** The query key of {@link readRewardSettings}: the wizard and the console's frame share one read. */
export const REWARD_SETTINGS_QUERY = ['qb', 'reward-methods', 'allowed'] as const

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

// ---- digital prizes (Q33) -----------------------------------------------------------------------------------------

/** `reward.DigitalPrizeStatus` (dictionary `digital-prize-status`). */
export type DigitalStatus = 'DUE' | 'DELIVERED' | 'CONFIRMED' | 'DISPUTED' | 'OVERDUE' | 'VOID'
export const DIGITAL_STATUSES: readonly DigitalStatus[] = ['DUE', 'DELIVERED', 'CONFIRMED', 'DISPUTED', 'OVERDUE', 'VOID']

/** `reward.DigitalCodeStatus` (dictionary `digital-code-status`): a code of a pool. */
export type DigitalCodeStatus = 'AVAILABLE' | 'ISSUED' | 'REVOKED'
export const DIGITAL_CODE_STATUSES: readonly DigitalCodeStatus[] = ['AVAILABLE', 'ISSUED', 'REVOKED']

/** How many codes one call of `QB_DIGITAL_CODES_LOAD` takes, and how long each may be. */
export const DIGITAL_CODES_PER_LOAD = 1000
export const DIGITAL_CODE_LENGTH = 200

/** The counts of a publication's code pool (`qb.sponsor.digital-pool-summary`). */
export interface DigitalPoolSummary {
  available: number
  issued: number
  revoked: number
}

/**
 * A row of `qb.sponsor.digital-codes`: a code of the pool without the code itself, which is sealed on the server;
 * the hint is its last four characters (none for a short code).
 */
export interface DigitalCodeRow {
  codeId: string
  seq: number
  hint: string | null
  status: DigitalCodeStatus
  loadedAt: string
  issuedAt: string | null
  revokedAt: string | null
}

/** A row of `qb.sponsor.digital-prizes`: what a taker won from the sponsor, and where it stands. */
export interface DigitalPrizeRow {
  digitalPrizeId: string
  publicationId: string
  publicationName: string
  takerName: string | null
  amount: string
  description: string | null
  delivery: DigitalDelivery | null
  hint: string | null
  status: DigitalStatus
  dueAt: string
  sendBy: string | null
  deliveredAt: string | null
  /** 'SYSTEM' when the platform handed over a code, 'SPONSOR' when the sponsor sent it. */
  deliveredBy: string | null
  openedAt: string | null
  confirmBy: string | null
  confirmedAt: string | null
  disputedAt: string | null
  disputeNote: string | null
  resolution: string | null
  resolveNote: string | null
}

export async function readDigitalPoolSummary(publicationId: string): Promise<DigitalPoolSummary> {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.digitalPoolSummary, { params: { publicationId }, limit: 1 })
  const row = page.items[0] ?? {}
  return { available: num(row.available), issued: num(row.issued), revoked: num(row.revoked) }
}

export interface DigitalCodeSearch {
  publicationId: string
  status?: DigitalCodeStatus | null
  offset: number
  limit: number
}

export async function searchDigitalCodes({ publicationId, status, offset, limit }: DigitalCodeSearch) {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.digitalCodes, {
    params: { publicationId },
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): DigitalCodeRow => ({
      codeId: String(row.codeId),
      seq: num(row.seq),
      hint: str(row.hint),
      status: (DIGITAL_CODE_STATUSES as readonly string[]).includes(String(row.status))
        ? (row.status as DigitalCodeStatus) : 'AVAILABLE',
      loadedAt: String(row.loadedAt ?? ''),
      issuedAt: str(row.issuedAt),
      revokedAt: str(row.revokedAt),
    })),
  }
}

/** The codes typed or pasted, one a line; the server drops blank lines and repeats. */
export const countCodeLines = (lines: string): number => lines.split(/\r?\n/).filter((line) => line.trim()).length

export interface LoadDigitalCodesOutput { publicationId: string; loaded: number; available: number }
export interface RevokeDigitalCodesOutput { publicationId: string; revoked: number; available: number }

/** Adds codes to the pool (`QB_DIGITAL_CODES_LOAD`): the lines as one text, which the server seals one by one. */
export const loadDigitalCodes = (publicationId: string, lines: string) =>
  runProcess<LoadDigitalCodesOutput>(PUBLISHING_PROCESS.digitalCodesLoad, { publicationId, pastedCodes: lines })

/** Takes back every code not yet handed to a winner (`QB_DIGITAL_CODES_REVOKE`). */
export const revokeDigitalCodes = (publicationId: string) =>
  runProcess<RevokeDigitalCodesOutput>(PUBLISHING_PROCESS.digitalCodesRevoke, { publicationId })

export interface DigitalPrizeSearch {
  q?: string
  status?: DigitalStatus | null
  offset: number
  limit: number
}

export async function searchDigitalPrizes({ q, status, offset, limit }: DigitalPrizeSearch) {
  const text = q?.trim()
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.digitalPrizes, {
    params: text ? { q: text } : {},
    filters: status ? [{ field: 'status', op: 'eq', value: status }] : [],
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): DigitalPrizeRow => ({
      digitalPrizeId: String(row.digitalPrizeId),
      publicationId: String(row.publicationId),
      publicationName: String(row.publicationName ?? ''),
      takerName: str(row.takerName),
      amount: String(row.amount ?? '0'),
      description: str(row.description),
      delivery: deliveryOf(row.delivery),
      hint: str(row.hint),
      status: (DIGITAL_STATUSES as readonly string[]).includes(String(row.status)) ? (row.status as DigitalStatus) : 'DUE',
      dueAt: String(row.dueAt ?? ''),
      sendBy: str(row.sendBy),
      deliveredAt: str(row.deliveredAt),
      deliveredBy: str(row.deliveredBy),
      openedAt: str(row.openedAt),
      confirmBy: str(row.confirmBy),
      confirmedAt: str(row.confirmedAt),
      disputedAt: str(row.disputedAt),
      disputeNote: str(row.disputeNote),
      resolution: str(row.resolution),
      resolveNote: str(row.resolveNote),
    })),
  }
}

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

/**
 * Asks to switch a publication that went on to another version of its quiz (Q51): reviewed again while it runs on its
 * version; made at once when no review is needed.
 */
export const switchVersion = (publicationId: string, versionId: string, baseRevision: number) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.switchVersion, { publicationId, versionId, baseRevision })

/** Withdraws the switch in review; the publication keeps its version. */
export const withdrawSwitch = (publicationId: string, baseRevision: number) =>
  runProcess<PublicationOutput>(PUBLISHING_PROCESS.switchWithdraw, { publicationId, baseRevision })

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
    /** Q51: one that went on, has not ended and was not taken down switches its version. */
    switchVersion: !takenDown && (status === 'SCHEDULED' || status === 'LIVE' || status === 'FUNDS_SHORT'
      || status === 'PAUSED'),
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
  cover: string | null
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
  /** Q33: the digital prize's delivery, description and send days as typed; the shared content as typed now only. */
  digitalDelivery: DigitalDelivery | null
  digitalDescription: string
  digitalSendDays: string
  /** Never filled from the server (it is sealed there); blank keeps the one saved. */
  digitalContent: string
  audience: Audience
  /** Q70: "referrals welcome" as typed; its fields keep what was typed while switched off, but are not sent. */
  referralEnabled: boolean
  referralQuota: string
  referralMethod: RewardMethod
  referralAmount: string
  referralDescription: string
  referrerRequires: ReferrerRequirement
  refereeRules: string[]
  referralDays: string
  /** Q73b: the languages offered, the main one first (none: the version's base only), and the texts typed in them. */
  languages: ContentLanguage[]
  translations: Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>>
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
    cover: p?.cover ?? null,
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
    digitalDelivery: p?.digitalDelivery ?? null,
    digitalDescription: p?.digitalDescription ?? '',
    digitalSendDays: text(p?.digitalSendDays),
    digitalContent: '',
    audience: p?.audience ?? 'PUBLIC',
    referralEnabled: !!p?.referral,
    referralQuota: text(p?.referral?.quota),
    referralMethod: (p?.referral?.method as RewardMethod | undefined) ?? 'PLATFORM',
    referralAmount: text(p?.referral?.amount),
    referralDescription: p?.referral?.description ?? '',
    referrerRequires: p?.referral?.referrerRequires ?? 'NONE',
    refereeRules: p?.referral ? p.referral.refereeRules : DEFAULT_REFEREE_RULES,
    referralDays: p?.referral ? String(p.referral.days) : '7',
    languages: p?.languages ?? [],
    translations: p?.translations ?? {},
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
  const digital = form.rewardMethod === 'DIGITAL_PRIZE'
  const digitalSendDays = digital && form.digitalDelivery === 'SPONSOR_SENT'
    ? read('digitalSendDays', form.digitalSendDays) : null
  // Q70: only when welcome; an invited publication sends it too, and the server says why it cannot.
  const referral: ReferralInput | null = form.referralEnabled ? {
    quota: read('referral.quota', form.referralQuota),
    method: form.referralMethod,
    amount: read('referral.amount', form.referralAmount),
    description: form.referralDescription.trim() || null,
    referrerRequires: form.referrerRequires,
    refereeRules: REFEREE_RULES.filter((rule) => form.refereeRules.includes(rule)),
    days: read('referral.days', form.referralDays),
  } : null
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
      cover: form.cover,
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
      // The digital fields travel only with a digital prize; what another method left behind is not sent (Q33).
      ...(digital ? {
        digitalDelivery: form.digitalDelivery,
        digitalDescription: form.digitalDescription.trim() || null,
        digitalSendDays,
        ...(form.digitalDelivery === 'SHARED' && form.digitalContent.trim()
          ? { digitalContent: form.digitalContent.trim() } : {}),
      } : {}),
      audience: form.audience,
      referral,
      // Q73b: the texts of the languages offered only; neither for a publication of its version's base only.
      ...(form.languages.length > 0 ? {
        languages: form.languages,
        translations: Object.fromEntries(form.languages
          .filter((language) => form.translations[language])
          .map((language) => [language, Object.fromEntries(Object.entries(form.translations[language] ?? {})
            .filter(([, text]) => text && text.trim() !== '').map(([field, text]) => [field, text!.trim()]))])),
      } : {}),
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
  'budgetCap', 'maxWinners', 'rewardMethod', 'redeemDescription', 'digitalDelivery', 'digitalDescription',
  'digitalSendDays', 'digitalContent', 'audience', 'referral'] as const
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
  if (field.startsWith('referral.')) return 'referral'
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
  /** Who handed it over when one of the sponsor's staff did (Q39); null when the sponsor did. */
  redeemedByName: string | null
  /** Q74: the user who handed it over (the sponsor or one of their staff), and whether it was the sponsor. */
  redeemedBy: string | null
  handledBySelf: boolean
}

export interface RedemptionSearch {
  q?: string
  status?: RedemptionStatus | null
  /** Only prizes claimed or collected at or after this time (the redeem page's recent list). */
  since?: string | null
  /** Q74: only those handed over by this user (a handler's `handlerId`), or `SELF` for the sponsor's own. */
  handledBy?: string | null
  offset: number
  limit: number
}

export async function searchRedemptions({ q, status, since, handledBy, offset, limit }: RedemptionSearch) {
  const text = q?.trim()
  const params: Record<string, unknown> = {}
  if (text) params.q = text
  if (since) params.since = since
  if (handledBy) params.handledBy = handledBy
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
      redeemedByName: row.redeemedByName == null ? null : String(row.redeemedByName),
      redeemedBy: row.redeemedBy == null ? null : String(row.redeemedBy),
      handledBySelf: row.handledBySelf === true,
    })),
  }
}

/** A row of `qb.sponsor.redemption-handlers` (Q74): one who handed over the sponsor's prizes, and their figures. */
export interface RedemptionHandler {
  handlerId: string
  self: boolean
  name: string | null
  todayCount: number
  todayValue: number
  weekCount: number
  weekValue: number
  monthCount: number
  monthValue: number
  totalCount: number
  totalValue: number
  /** The deployment's zone the days are of. */
  zone: string
}

/** Everyone who ever handed over one of the sponsor's prizes, staff that left included, most first. */
export async function readRedemptionHandlers(): Promise<RedemptionHandler[]> {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.redemptionHandlers, { params: { at: minuteNow() },
    limit: 100 })
  return page.items.map((row) => ({
    handlerId: String(row.handlerId),
    self: row.self === true,
    name: row.name == null ? null : String(row.name),
    todayCount: Number(row.todayCount ?? 0), todayValue: Number(row.todayValue ?? 0),
    weekCount: Number(row.weekCount ?? 0), weekValue: Number(row.weekValue ?? 0),
    monthCount: Number(row.monthCount ?? 0), monthValue: Number(row.monthValue ?? 0),
    totalCount: Number(row.totalCount ?? 0), totalValue: Number(row.totalValue ?? 0),
    zone: String(row.zone ?? 'UTC'),
  }))
}

type RedemptionOutput = { redemptionId: string; status: RedemptionStatus; revision: number }

/** The claim at the counter is the person: collected. */
export const confirmRedemption = (redemptionId: string) =>
  runProcess<RedemptionOutput>(PUBLISHING_PROCESS.redemptionConfirm, { redemptionId })

/** The claim is not the person at the counter: the prize is due again. */
export const declineRedemption = (redemptionId: string) =>
  runProcess<RedemptionOutput>(PUBLISHING_PROCESS.redemptionDecline, { redemptionId })

// ---- the staff (Q39) ------------------------------------------------------------------------------------------------

/** `StaffProcesses.Candidate`: who an ID is before naming them. */
export interface StaffCandidate {
  userId: string
  displayName: string | null
  onStaff: boolean
}

/** A row of `qb.sponsor.staff`. */
export interface StaffMember {
  userId: string
  displayName: string | null
  addedAt: string
}

type StaffOutput = { userId: string; changed: boolean }

/** Who the ID a taker gave is (refused when it is not a taker's who may be named). */
export const findStaff = (userId: string) => runProcess<StaffCandidate>(PUBLISHING_PROCESS.staffFind, { userId })

export const addStaff = (userId: string) => runProcess<StaffOutput>(PUBLISHING_PROCESS.staffAdd, { userId })

export const removeStaff = (userId: string) => runProcess<StaffOutput>(PUBLISHING_PROCESS.staffRemove, { userId })

export async function readStaff(): Promise<StaffMember[]> {
  const page = await runQuery<Row>(PUBLISHING_TEMPLATE.staff, { limit: 100 })
  return page.items.map((row) => ({
    userId: String(row.userId),
    displayName: row.displayName == null ? null : String(row.displayName),
    addedAt: String(row.addedAt),
  }))
}

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

// ---- the sponsor's own mail server (Q29) -----------------------------------------------------------------------------

export type MailServerTls = 'SMTPS' | 'STARTTLS'
export const MAIL_SERVER_TLS: readonly MailServerTls[] = ['SMTPS', 'STARTTLS']

/** A row of `qb.sponsor.mail-server`: the settings without the password, and whether the last test mail left. */
export interface MailServer {
  host: string
  port: number
  tls: MailServerTls
  username: string
  fromAddress: string
  fromName: string | null
  /** Set while the server is verified: the invitations leave through it. */
  verifiedAt: string | null
  testedAt: string | null
  /** The server's or the connection's answer when the last test mail did not leave. */
  lastError: string | null
}

/** `MailServerProcesses.SetInput`: the password is blank to keep the stored one. */
export interface MailServerInput {
  host: string
  port: number
  tls: MailServerTls
  username: string
  password: string | null
  fromAddress: string
  fromName: string | null
}

/** `MailServerProcesses.SetOutput`. */
export interface MailServerOutput {
  sponsorId: string
  verified: boolean
  error: string | null
  testedAt: string
  sentTo: string | null
}

/** The sponsor's server, or null before one is saved. */
export async function readMailServer(): Promise<MailServer | null> {
  const row = (await runQuery<Row>(PUBLISHING_TEMPLATE.mailServer, { limit: 1 })).items[0]
  if (!row) return null
  return {
    host: String(row.host ?? ''),
    port: num(row.port),
    tls: (String(row.tls ?? 'SMTPS') as MailServerTls),
    username: String(row.username ?? ''),
    fromAddress: String(row.fromAddress ?? ''),
    fromName: row.fromName == null ? null : String(row.fromName),
    verifiedAt: row.verifiedAt == null ? null : String(row.verifiedAt),
    testedAt: row.testedAt == null ? null : String(row.testedAt),
    lastError: row.lastError == null ? null : String(row.lastError),
  }
}

/** Saves the server after a test mail through it; the output says whether the mail left. */
export const saveMailServer = (input: MailServerInput) =>
  runProcess<MailServerOutput>(PUBLISHING_PROCESS.mailServerSet, { ...input })

export const removeMailServer = () =>
  runProcess<{ sponsorId: string }>(PUBLISHING_PROCESS.mailServerRemove, {})

/** `AudienceCount`: never a small number; under the threshold only that it is fewer (Q47, D-Q47-5). */
export interface AudienceCount {
  /** The count rounded down to tens; null when fewer than `fewerThan`. */
  count: number | null
  fewerThan: number | null
}

/** The query of a publication's conditions: apart from the publications' (a draft's save sets it from what it sent). */
export const conditionsQuery = (publicationId: string | null) => ['qb', 'publication-conditions', publicationId] as const

/** A draft's (or a frozen publication's) matching conditions, one per criterion, in the criteria's order. */
export async function readConditions(publicationId: string): Promise<Condition[]> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: AUDIENCE.sponsorConditions } },
    body: { limit: PAGE, filters: [{ field: 'publicationId', op: 'eq', value: publicationId }] },
  }))
  return conditionsOf((page.items ?? []).map((item) => (item.attributes ?? {}) as Row))
}

/** Stored rows (an option each, or a range) gathered into one condition per criterion. */
export function conditionsOf(rows: readonly Row[]): Condition[] {
  const byKey = new Map<string, Condition>()
  for (const row of rows) {
    const key = String(row.criterionKey)
    const condition = byKey.get(key) ?? { key }
    if (row.valueCode != null) condition.options = [...(condition.options ?? []), String(row.valueCode)]
    else {
      condition.min = numOrNull(row.minValue)
      condition.max = numOrNull(row.maxValue)
    }
    byKey.set(key, condition)
  }
  return CRITERIA.flatMap((criterion) => {
    const condition = byKey.get(criterion.key)
    if (!condition) return []
    return [condition.options
      ? { ...condition, options: criterion.options.filter((option) => condition.options!.includes(option)) }
      : condition]
  })
}

/** Replaces a draft's conditions with these (none: everybody in its regions). */
export const saveConditions = (publicationId: string, baseRevision: number | null, criteria: Condition[]) =>
  runProcess<PublicationOutput>(AUDIENCE.conditionsSave, { publicationId, baseRevision, criteria })

/** How many takers the saved regions and conditions reach now, rounded. */
export const countAudience = (publicationId: string) =>
  runProcess<AudienceCount>(AUDIENCE.count, { publicationId })
