import { runProcess, runQuery, type Filter } from '@jabiz/admin'
import type { Page, Sponsor, SponsorRow } from './api'
import { OPERATOR_PROCESS, TEMPLATE } from './ids'
import type { SortSpec } from './api'

/*
 * Operators (backend `operator`, docs/quizbuks/plans/Q48-operators.md, Q48a): what headquarters' pages and the
 * operators' own pages read and run, typed by hand after the templates and processes. The server checks every call;
 * an operator's page reads only the `qb.operator.*` templates and its own team.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}
const bool = (value: unknown): boolean => value === true || value === 'true'

/** An operator's state (backend `QbOperators`). */
export const OPERATOR_STATUSES = ['ACTIVE', 'SUSPENDED'] as const
export type OperatorStatus = (typeof OPERATOR_STATUSES)[number]

/** A spot check's result: the decision was right, or headquarters found a problem (which needs a note). */
export const CHECK_RESULTS = ['OK', 'PROBLEM'] as const
export type CheckResult = (typeof CHECK_RESULTS)[number]

/** A row of `qb.admin.operators`. */
export interface Operator {
  operatorId: string
  name: string
  contactName: string
  contactEmail: string
  collection: string
  status: OperatorStatus
  note: string | null
  createdAt: string | null
  revision: number
  members: number
  sponsors: number
}

function toOperator(row: Row): Operator {
  return {
    operatorId: String(row.operatorId),
    name: String(row.name ?? ''),
    contactName: String(row.contactName ?? ''),
    contactEmail: String(row.contactEmail ?? ''),
    collection: String(row.collection ?? ''),
    status: row.status === 'SUSPENDED' ? 'SUSPENDED' : 'ACTIVE',
    note: str(row.note),
    createdAt: str(row.createdAt),
    revision: num(row.revision),
    members: num(row.members),
    sponsors: num(row.sponsors),
  }
}

/** The operators, newest first. */
export async function readOperators(offset: number, limit: number, sorts?: SortSpec[]): Promise<Page<Operator>> {
  const page = await runQuery<Row>(TEMPLATE.operators, { sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toOperator) }
}

/** One operator; null when there is none. */
export async function readOperator(operatorId: string): Promise<Operator | null> {
  const page = await runQuery<Row>(TEMPLATE.operators, { params: { operatorId }, limit: 1 })
  return page.items[0] ? toOperator(page.items[0]) : null
}

/** What creating or changing an operator sends (`QB_OPERATOR_SAVE`); a change carries the revision read. */
export interface OperatorInput {
  operatorId?: string
  name: string
  contactName: string
  contactEmail: string
  status?: OperatorStatus
  note?: string | null
  baseRevision?: number
}

export const saveOperator = (input: OperatorInput) =>
  runProcess<{ operatorId: string; revision: number; status: string }>(OPERATOR_PROCESS.save, { ...input })

/** The query key of everything headquarters' operator pages read. */
export const OPERATORS_KEY = ['qb-ext', 'operators'] as const

/** A row of `qb.admin.operator-members`: current and removed members. */
export interface OperatorMember {
  memberId: string
  userId: string
  userName: string | null
  displayName: string | null
  email: string | null
  lead: boolean
  addedAt: string | null
  removedAt: string | null
  hasRole: boolean
}

function toMember(row: Row): OperatorMember {
  return {
    memberId: String(row.memberId),
    userId: String(row.userId),
    userName: str(row.userName),
    displayName: str(row.displayName),
    email: str(row.email),
    lead: bool(row.lead),
    addedAt: str(row.addedAt),
    removedAt: str(row.removedAt),
    hasRole: bool(row.hasRole),
  }
}

/** An operator's members, newest first, the removed ones too. */
export async function readMembers(operatorId: string): Promise<OperatorMember[]> {
  const page = await runQuery<Row>(TEMPLATE.operatorMembers, { params: { operatorId }, limit: 100 })
  return page.items.map(toMember)
}

export const addMember = (operatorId: string, userId: string, lead: boolean) =>
  runProcess<{ changed: boolean }>(OPERATOR_PROCESS.memberAdd, { operatorId, userId, lead })

export const removeMember = (userId: string) =>
  runProcess<{ changed: boolean }>(OPERATOR_PROCESS.memberRemove, { userId })

/** Moves a sponsor to an operator, or back to headquarters (`operatorId` null), with a reason. */
export const assignSponsor = (userId: string, operatorId: string | null, reason: string) =>
  runProcess<{ changed: boolean }>(OPERATOR_PROCESS.assign, { userId, operatorId, reason })

/** A row of `qb.admin.operator-reviews`: a review an operator's member decided, with headquarters' check. */
export interface OperatorReview {
  decisionId: string
  decidedAt: string | null
  decision: string
  reason: string | null
  approverId: string
  subject: string
  entityId: string
  sponsorId: string | null
  title: string | null
  operatorId: string
  operatorName: string
  checkResult: CheckResult | null
  checkNote: string | null
  checkedAt: string | null
  checkedBy: string | null
}

function toReview(row: Row): OperatorReview {
  return {
    decisionId: String(row.decisionId),
    decidedAt: str(row.decidedAt),
    decision: String(row.decision ?? ''),
    reason: str(row.reason),
    approverId: String(row.approverId ?? ''),
    subject: String(row.subject ?? ''),
    entityId: String(row.entityId ?? ''),
    sponsorId: str(row.sponsorId),
    title: str(row.title),
    operatorId: String(row.operatorId ?? ''),
    operatorName: String(row.operatorName ?? ''),
    checkResult: row.checkResult === 'OK' || row.checkResult === 'PROBLEM' ? row.checkResult : null,
    checkNote: str(row.checkNote),
    checkedAt: str(row.checkedAt),
    checkedBy: str(row.checkedBy),
  }
}

/** The operators' reviews, newest first; one operator's, and only the unchecked, when given. */
export async function readOperatorReviews(operatorId: string | null, unchecked: boolean, offset: number,
  limit: number, sorts?: SortSpec[]): Promise<Page<OperatorReview>> {
  const params: Record<string, unknown> = {}
  if (operatorId) params.operatorId = operatorId
  if (unchecked) params.unchecked = true
  const page = await runQuery<Row>(TEMPLATE.operatorReviews, { params, sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(toReview) }
}

export const spotCheck = (decisionId: string, result: CheckResult, note: string | null) =>
  runProcess<{ result: string }>(OPERATOR_PROCESS.spotCheck, { decisionId, result, note })

// ---- the operator's own pages ----------------------------------------------------------------------------------------

/** The caller's operator and its current members (`QB_OPERATOR_TEAM`). */
export interface Team {
  operatorId: string
  name: string
  status: string
  collection: string
  /** Whether the caller leads it: they remove members then. */
  lead: boolean
  members: { userId: string; displayName: string | null; email: string | null; lead: boolean; addedAt: string | null;
    self: boolean }[]
}

export async function readTeam(): Promise<Team> {
  const output = await runProcess<Row>(OPERATOR_PROCESS.team, {})
  const members = Array.isArray(output.members) ? (output.members as Row[]) : []
  return {
    operatorId: String(output.operatorId),
    name: String(output.name ?? ''),
    status: String(output.status ?? ''),
    collection: String(output.collection ?? ''),
    lead: bool(output.lead),
    members: members.map((member) => ({
      userId: String(member.userId),
      displayName: str(member.displayName),
      email: str(member.email),
      lead: bool(member.lead),
      addedAt: str(member.addedAt),
      self: bool(member.self),
    })),
  }
}

/** The lead removes a member of their operator. */
export const dropMember = (userId: string) =>
  runProcess<{ changed: boolean }>(OPERATOR_PROCESS.memberDrop, { userId })

/** The operator's home page's totals (`qb.operator.overview`). */
export interface Overview {
  mySponsors: number
  sponsors: number
  sponsorsInReview: number
  publicationsInReview: number
  livePublications: number
  myLivePublications: number
  myCompletions: number
}

export async function readOverview(): Promise<Overview> {
  const page = await runQuery<Row>(TEMPLATE.operatorOverview, { limit: 1 })
  const row = page.items[0] ?? {}
  return {
    mySponsors: num(row.mySponsors),
    sponsors: num(row.sponsors),
    sponsorsInReview: num(row.sponsorsInReview),
    publicationsInReview: num(row.publicationsInReview),
    livePublications: num(row.livePublications),
    myLivePublications: num(row.myLivePublications),
    myCompletions: num(row.myCompletions),
  }
}

/** A row of `qb.operator.sponsors`. */
export interface OperatorSponsor {
  onboardingId: string
  userId: string
  avatar: string | null
  termsAcceptedAt: string | null
  emailVerified: boolean
  company: string
  contactName: string
  intro: string | null
  email: string | null
  reviewStatus: string
  submittedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
  approvalRequestId: string | null
  operatorId: string | null
  operatorName: string | null
  mine: boolean
  banned: boolean
  bannedByOperator: boolean
  bannedAt: string | null
  runningPublications: number
}

export function toOperatorSponsor(row: Row): OperatorSponsor {
  return {
    onboardingId: String(row.onboardingId ?? row.userId),
    userId: String(row.userId),
    avatar: str(row.avatar),
    termsAcceptedAt: str(row.termsAcceptedAt),
    emailVerified: bool(row.emailVerified),
    company: String(row.company ?? ''),
    contactName: String(row.contactName ?? ''),
    intro: str(row.intro),
    email: str(row.email),
    reviewStatus: String(row.reviewStatus ?? ''),
    submittedAt: str(row.submittedAt),
    approvedAt: str(row.approvedAt),
    rejectReason: str(row.rejectReason),
    approvalRequestId: str(row.approvalRequestId),
    operatorId: str(row.operatorId),
    operatorName: str(row.operatorName),
    mine: bool(row.mine),
    banned: bool(row.banned),
    bannedByOperator: bool(row.bannedByOperator),
    bannedAt: str(row.bannedAt),
    runningPublications: num(row.runningPublications),
  }
}

/** The sponsors, newest application first: only the operator's own when `mine`, searched by `q`. */
export async function readOperatorSponsors(q: string, mine: boolean, offset: number, limit: number, sorts?: SortSpec[]):
  Promise<Page<OperatorSponsor>> {
  const filters: Filter[] = mine ? [{ field: 'mine', op: 'eq', value: true }] : []
  const page = await runQuery<Row>(TEMPLATE.operatorSponsors, { params: { q: q.trim() || null }, filters, sorts,
    offset,
    limit, count: true })
  return { total: page.total, items: page.items.map(toOperatorSponsor) }
}

/** One sponsor as the operator sees them; null when there is none. */
export async function readOperatorSponsor(userId: string): Promise<OperatorSponsor | null> {
  const page = await runQuery<Row>(TEMPLATE.operatorSponsors, { params: { userId }, limit: 1 })
  return page.items[0] ? toOperatorSponsor(page.items[0]) : null
}

export const banSponsor = (userId: string, reason: string) =>
  runProcess<{ banned: boolean }>(OPERATOR_PROCESS.ban, { userId, reason })

export const unbanSponsor = (userId: string, note: string | null) =>
  runProcess<{ banned: boolean }>(OPERATOR_PROCESS.unban, { userId, note })

/**
 * The applications waiting for review as an operator reads them (it has no `qb.admin.sponsors`): the same rows, the
 * longest waiting first, with their count.
 */
export async function readPendingSponsorsAsOperator(offset: number, limit: number, sorts?: SortSpec[]): Promise<Page<SponsorRow>> {
  const page = await runQuery<Row>(TEMPLATE.operatorSponsors, {
    filters: [{ field: 'reviewStatus', op: 'eq', value: 'PENDING' }],
    sorts: sorts ?? [{ field: 'submittedAt', asc: true }],
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map(toOperatorSponsor).map((s) => ({ onboardingId: s.onboardingId, userId: s.userId,
      company: s.company || null, contactName: s.contactName || null, email: s.email, reviewStatus: s.reviewStatus,
      submittedAt: s.submittedAt, approvalRequestId: s.approvalRequestId })),
  }
}

/** A sponsor's onboarding as an operator reads it, with the account's address; null when there is none. */
export async function readSponsorAsOperator(userId: string):
  Promise<{ sponsor: Sponsor; email: string | null; emailVerified: boolean } | null> {
  const s = await readOperatorSponsor(userId)
  if (!s) return null
  return {
    sponsor: { userId: s.userId, contactName: s.contactName || null, company: s.company || null, intro: s.intro,
      avatar: s.avatar, reviewStatus: s.reviewStatus, termsAcceptedAt: s.termsAcceptedAt,
      approvalRequestId: s.approvalRequestId, submittedAt: s.submittedAt, approvedAt: s.approvedAt,
      rejectReason: s.rejectReason },
    email: s.email,
    emailVerified: s.emailVerified,
  }
}
