import { api, ApiError, runQuery, unwrap, type Filter } from '@jabiz/admin'
import { DATASET, PAGE_LIMIT, TEMPLATE } from './ids'
import { parseSnapshot, type Snapshot } from './snapshot'

/*
 * What the review and user pages read, typed by hand after the entities and templates of backend/quizbuks (QuizBuks
 * has no endpoints of its own, so there are no generated types; docs/quizbuks/plans/Q9a-admin-extension.md).
 * Everything is read only: the one decision the pages take is the platform's APPROVAL_DECIDE.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}
/** Amounts stay text: the server's decimals, formatted by `formatAmount`. */
const amount = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The most rows the pages ask for at once (the platform's page limit). */
export const PAGE = PAGE_LIMIT

/** One page of a list; `total` when the server counted all rows. */
export interface Page<Row> {
  items: Row[]
  total?: number
}

/** One page of a template's rows, mapped, counting all of them when `count`. */
async function page<T>(template: string, map: (row: Row) => T, options: {
  filters?: Filter[]
  sorts?: { field: string; asc: boolean }[]
  offset: number
  limit: number
  count?: boolean
}): Promise<Page<T>> {
  const answer = await runQuery<Row>(template, options)
  return { items: answer.items.map(map), total: answer.total }
}

/** A row of `qb.admin.publications`: the review queue, and a publication's counts and its sponsor's funds. */
export interface PublicationRow {
  publicationId: string
  sponsorId: string
  name: string
  quizTitle: string | null
  versionLabel: string | null
  status: string
  regions: string | null
  budgetCap: string | null
  maxSpend: string | null
  maxWinners: number | null
  startAt: string | null
  endAt: string | null
  approvalRequestId: string | null
  submittedAt: string | null
  rejectReason: string | null
}

export function toPublicationRow(row: Row): PublicationRow {
  return {
    publicationId: String(row.publicationId),
    sponsorId: String(row.sponsorId ?? ''),
    name: String(row.name ?? ''),
    quizTitle: str(row.quizTitle),
    versionLabel: str(row.versionLabel),
    status: String(row.status ?? ''),
    regions: str(row.regions),
    budgetCap: amount(row.budgetCap),
    maxSpend: amount(row.maxSpend),
    maxWinners: num(row.maxWinners),
    startAt: str(row.startAt),
    endAt: str(row.endAt),
    approvalRequestId: str(row.approvalRequestId),
    submittedAt: str(row.submittedAt),
    rejectReason: str(row.rejectReason),
  }
}

/** A page of the publications waiting for review, the longest waiting first, with their count. */
export const readPendingPublications = (offset: number, limit: number) =>
  page(TEMPLATE.publications, toPublicationRow, {
    filters: [{ field: 'status', op: 'eq', value: 'PENDING_REVIEW' }],
    sorts: [{ field: 'submittedAt', asc: true }],
    offset,
    limit,
    count: true,
  })

/** One publication's row of the queue template (its counts and its sponsor's funds); null when there is none. */
export async function readPublicationRow(publicationId: string): Promise<PublicationRow | null> {
  const answer = await runQuery<Row>(TEMPLATE.publications, {
    filters: [{ field: 'publicationId', op: 'eq', value: publicationId }],
    limit: 1,
  })
  return answer.items[0] ? toPublicationRow(answer.items[0]) : null
}

/** A publication's sponsor's funds (`qb.admin.publication-funds`, finance only). */
export interface PublicationFunds {
  fundsShort: boolean
  pendingCount: number
  pendingAmount: string | null
}

export async function readPublicationFunds(publicationId: string): Promise<PublicationFunds | null> {
  const answer = await runQuery<Row>(TEMPLATE.publicationFunds, {
    filters: [{ field: 'publicationId', op: 'eq', value: publicationId }], limit: 1 })
  const row = answer.items[0]
  if (!row) return null
  return { fundsShort: bool(row.fundsShort), pendingCount: num(row.pendingCount) ?? 0,
    pendingAmount: amount(row.pendingAmount) }
}

/** A row of `qb.admin.sponsors`. */
export interface SponsorRow {
  onboardingId: string
  userId: string
  company: string | null
  contactName: string | null
  email: string | null
  reviewStatus: string
  submittedAt: string | null
  approvalRequestId: string | null
}

/** A page of the sponsor applications waiting for review, the longest waiting first, with their count. */
export const readPendingSponsors = (offset: number, limit: number) =>
  page(TEMPLATE.sponsors, (row): SponsorRow => ({
    onboardingId: String(row.onboardingId),
    userId: String(row.userId ?? ''),
    company: str(row.company),
    contactName: str(row.contactName),
    email: str(row.email),
    reviewStatus: String(row.reviewStatus ?? ''),
    submittedAt: str(row.submittedAt),
    approvalRequestId: str(row.approvalRequestId),
  }), {
    filters: [{ field: 'reviewStatus', op: 'eq', value: 'PENDING' }],
    sorts: [{ field: 'submittedAt', asc: true }],
    offset,
    limit,
    count: true,
  })

/** One instance of an administrators' dataset; null when there is none (404). */
async function readEntity(dataset: string, id: string): Promise<Row | null> {
  try {
    const instance = await unwrap(api.GET('/api/datasets/{resourceId}/entities/{id}', {
      params: { path: { resourceId: dataset, id } },
    }))
    return (instance.attributes ?? null) as Row | null
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

/** The rows of an administrators' dataset that match, in the dataset's order (at most {@link PAGE}). */
async function readRows(dataset: string, filters: Filter[]): Promise<Row[]> {
  const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: dataset } },
    body: { offset: 0, limit: PAGE, filters },
  }))
  return (answer.items ?? []).map((item) => (item.attributes ?? {}) as Row)
}

/** A publication as it was submitted (frozen from then on), with the facts of its review. */
export interface Publication {
  publicationId: string
  sponsorId: string
  versionId: string | null
  versionLabel: string | null
  name: string
  regions: string[]
  startAt: string | null
  endAt: string | null
  timeLimitSec: number | null
  passScore: number | null
  fullScore: number | null
  budgetCap: string | null
  maxReward: string | null
  maxSpend: string | null
  maxWinners: number | null
  status: string
  approvalRequestId: string | null
  submittedAt: string | null
  rejectReason: string | null
}

export async function readPublication(publicationId: string): Promise<Publication | null> {
  const row = await readEntity(DATASET.publication, publicationId)
  if (!row) return null
  return {
    publicationId: String(row.publicationId ?? publicationId),
    sponsorId: String(row.sponsorId ?? ''),
    versionId: str(row.versionId),
    versionLabel: str(row.versionLabel),
    name: String(row.name ?? ''),
    regions: (str(row.regions) ?? '').split(',').map((r) => r.trim()).filter(Boolean),
    startAt: str(row.startAt),
    endAt: str(row.endAt),
    timeLimitSec: num(row.timeLimitSec),
    passScore: num(row.passScore),
    fullScore: num(row.fullScore),
    budgetCap: amount(row.budgetCap),
    maxReward: amount(row.maxReward),
    maxSpend: amount(row.maxSpend),
    maxWinners: num(row.maxWinners),
    status: String(row.status ?? ''),
    approvalRequestId: str(row.approvalRequestId),
    submittedAt: str(row.submittedAt),
    rejectReason: str(row.rejectReason),
  }
}

export interface Tier {
  seq: number
  fromScore: number
  toScore: number
  reward: string | null
}

/** A publication's reward tiers, in their order. */
export async function readTiers(publicationId: string): Promise<Tier[]> {
  const rows = await readRows(DATASET.tier, [{ field: 'publicationId', op: 'eq', value: publicationId }])
  return rows
    .map((row) => ({ seq: num(row.seq) ?? 0, fromScore: num(row.fromScore) ?? 0, toScore: num(row.toScore) ?? 0,
      reward: amount(row.reward) }))
    .sort((a, b) => a.fromScore - b.fromScore || a.seq - b.seq)
}

/** A version with its whole frozen content; `content` is null when it cannot be read as a snapshot. */
export interface Version {
  versionId: string
  label: string
  title: string
  contentHash: string
  versionedAt: string | null
  content: Snapshot | null
}

export async function readVersion(versionId: string): Promise<Version | null> {
  const row = await readEntity(DATASET.version, versionId)
  if (!row) return null
  let content: Snapshot | null
  try {
    content = parseSnapshot(row.content)
  } catch {
    content = null
  }
  return {
    versionId: String(row.versionId ?? versionId),
    label: String(row.label ?? ''),
    title: String(row.title ?? ''),
    contentHash: String(row.contentHash ?? ''),
    versionedAt: str(row.versionedAt),
    content,
  }
}

/** A sponsor's onboarding: what the review of an application is bound to. */
export interface Sponsor {
  userId: string
  contactName: string | null
  company: string | null
  intro: string | null
  avatar: string | null
  reviewStatus: string
  termsAcceptedAt: string | null
  approvalRequestId: string | null
  submittedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
}

/** A user's onboarding; null when they never applied. */
export async function readSponsor(userId: string): Promise<Sponsor | null> {
  const row = (await readRows(DATASET.sponsor, [{ field: 'userId', op: 'eq', value: userId }]))[0]
  if (!row) return null
  return {
    userId: String(row.userId ?? userId),
    contactName: str(row.contactName),
    company: str(row.company),
    intro: str(row.intro),
    avatar: str(row.avatar),
    reviewStatus: String(row.reviewStatus ?? ''),
    termsAcceptedAt: str(row.termsAcceptedAt),
    approvalRequestId: str(row.approvalRequestId),
    submittedAt: str(row.submittedAt),
    approvedAt: str(row.approvedAt),
    rejectReason: str(row.rejectReason),
  }
}

/** A user's ban; null when they were never banned. */
export interface Ban {
  banned: boolean
  reason: string | null
  bannedAt: string | null
  bannedBy: string | null
  liftedAt: string | null
  liftedBy: string | null
}

export async function readBan(userId: string): Promise<Ban | null> {
  const row = (await readRows(DATASET.ban, [{ field: 'userId', op: 'eq', value: userId }]))[0]
  if (!row) return null
  return { banned: bool(row.banned), reason: str(row.reason), bannedAt: str(row.bannedAt),
    bannedBy: str(row.bannedBy), liftedAt: str(row.liftedAt), liftedBy: str(row.liftedBy) }
}

/** A row of `qb.admin.users`: the platform's user with their profile, onboarding and ban. */
export interface UserRow {
  userId: string
  userName: string
  email: string | null
  emailVerified: boolean
  enabled: boolean
  displayName: string | null
  countryCode: string | null
  countryName: string | null
  city: string | null
  registeredAt: string | null
  sponsorStatus: string | null
  company: string | null
  banned: boolean
}

function toUserRow(row: Row): UserRow {
  return {
    userId: String(row.userId),
    userName: String(row.userName ?? ''),
    email: str(row.email),
    emailVerified: bool(row.emailVerified),
    enabled: bool(row.enabled),
    displayName: str(row.displayName),
    countryCode: str(row.countryCode),
    countryName: str(row.countryName),
    city: str(row.city),
    registeredAt: str(row.registeredAt),
    sponsorStatus: str(row.sponsorStatus),
    company: str(row.company),
    banned: bool(row.banned),
  }
}

/** One user by key; null when there is none. */
export async function readUser(userId: string): Promise<UserRow | null> {
  const answer = await runQuery<Row>(TEMPLATE.users, { params: { userId }, limit: 1 })
  return answer.items[0] ? toUserRow(answer.items[0]) : null
}

/** One page of users whose user name, address, name or company contains `q` (all users without it). */
export async function searchUsers(q: string, offset: number, limit: number): Promise<UserRow[]> {
  const answer = await runQuery<Row>(TEMPLATE.users, { params: { q: q.trim() || null }, offset, limit })
  return answer.items.map(toUserRow)
}

/** A row of `qb.admin.attempts`. */
export interface AttemptRow {
  attemptId: string
  publicationId: string
  name: string | null
  status: string
  startedAt: string | null
  submittedAt: string | null
  score: number | null
  fullScore: number | null
  passed: boolean
  rewardStatus: string | null
}

/** A page of the user's attempts, newest first (no amounts: those are finance's, {@link readAttemptRewards}). */
export const readAttempts = (userId: string, offset: number, limit: number) =>
  page(TEMPLATE.attempts, (row): AttemptRow => ({
    attemptId: String(row.attemptId),
    publicationId: String(row.publicationId ?? ''),
    name: str(row.name),
    status: String(row.status ?? ''),
    startedAt: str(row.startedAt),
    submittedAt: str(row.submittedAt),
    score: num(row.score),
    fullScore: num(row.fullScore),
    passed: bool(row.passed),
    rewardStatus: str(row.rewardStatus),
  }), { filters: [{ field: 'userId', op: 'eq', value: userId }], offset, limit })

/** A row of `qb.admin.attempt-rewards` (finance only): the money of a submitted attempt. */
export interface AttemptRewardRow {
  attemptId: string
  name: string | null
  submittedAt: string | null
  reward: string | null
  fee: string | null
  rewardStatus: string | null
  rewardPaidAt: string | null
}

export const readAttemptRewards = (userId: string, offset: number, limit: number) =>
  page(TEMPLATE.attemptRewards, (row): AttemptRewardRow => ({
    attemptId: String(row.attemptId),
    name: str(row.name),
    submittedAt: str(row.submittedAt),
    reward: amount(row.reward),
    fee: amount(row.fee),
    rewardStatus: str(row.rewardStatus),
    rewardPaidAt: str(row.rewardPaidAt),
  }), { filters: [{ field: 'userId', op: 'eq', value: userId }], offset, limit })

/** A row of `qb.admin.transfers` (finance only). */
export interface TransferRow {
  transferId: string
  amount: string | null
  fee: string | null
  net: string | null
  status: string
  createdAt: string | null
  sentAt: string | null
  reversedAmount: string | null
}

export const readTransfers = (userId: string, offset: number, limit: number) =>
  page(TEMPLATE.transfers, (row): TransferRow => ({
    transferId: String(row.transferId),
    amount: amount(row.amount),
    fee: amount(row.fee),
    net: amount(row.net),
    status: String(row.status ?? ''),
    createdAt: str(row.createdAt),
    sentAt: str(row.sentAt),
    reversedAmount: amount(row.reversedAmount),
  }), { filters: [{ field: 'userId', op: 'eq', value: userId }], offset, limit })

/** A row of `qb.admin.topups` (finance only): a sponsor's top-ups. */
export interface TopUpRow {
  topUpId: string
  amount: string | null
  status: string
  createdAt: string | null
  paidAt: string | null
  refundedAmount: string | null
}

export const readTopUps = (sponsorId: string, offset: number, limit: number) =>
  page(TEMPLATE.topUps, (row): TopUpRow => ({
    topUpId: String(row.topUpId),
    amount: amount(row.amount),
    status: String(row.status ?? ''),
    createdAt: str(row.createdAt),
    paidAt: str(row.paidAt),
    refundedAmount: amount(row.refundedAmount),
  }), { filters: [{ field: 'sponsorId', op: 'eq', value: sponsorId }], offset, limit })

/** The user's payout account (finance only); only the last four characters of Stripe's account are shown. */
export interface PayoutAccount {
  status: string
  payoutsEnabled: boolean
  accountLast4: string | null
  country: string | null
  createdAt: string | null
}

export async function readPayoutAccount(userId: string): Promise<PayoutAccount | null> {
  const answer = await runQuery<Row>(TEMPLATE.payoutAccounts, {
    filters: [{ field: 'userId', op: 'eq', value: userId }], limit: 1 })
  const row = answer.items[0]
  if (!row) return null
  const account = str(row.externalAccountId)
  return {
    status: String(row.status ?? ''),
    payoutsEnabled: bool(row.payoutsEnabled),
    accountLast4: account ? account.slice(-4) : null,
    country: str(row.country),
    createdAt: str(row.createdAt),
  }
}

export interface DictionaryItem {
  code: string
  label: string
}

/** A dictionary's items in the request's language. */
export async function readDictionary(urn: string): Promise<DictionaryItem[]> {
  const items = await unwrap(api.GET('/api/dictionaries/{urn}', { params: { path: { urn } } }))
  return (items ?? []).map((item) => ({ code: String(item.code), label: String(item.label ?? item.code) }))
}
