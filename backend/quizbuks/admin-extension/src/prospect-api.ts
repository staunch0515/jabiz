import { runProcess, runQuery } from '@jabiz/admin'
import type { Page } from './api'
import { PROSPECT_PROCESS, TEMPLATE } from './ids'

/*
 * AI prospecting (backend `prospect`, docs/quizbuks/plans/Q48-operators.md, Q48c): what its pages read and run, typed
 * by hand after the templates and processes. An operator's member reads the `qb.operator.prospect*` templates (its own
 * operator's); headquarters reads the `qb.admin.prospect*` ones for an owner (`direct` is its own). The server decides
 * whose prospecting a process acts on: the caller's operator, else headquarters'.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}
const bool = (value: unknown): boolean | null => (value === true || value === 'true' ? true
  : value === false || value === 'false' ? false : null)

/** Headquarters' own prospecting (backend `QbProspects.DIRECT`). */
export const DIRECT = 'direct'

/** The states of a business found (backend `QbProspects`). */
export const PROSPECT_STATUSES = ['DRAFTED', 'APPROVED', 'SENT', 'FOUND', 'RESEARCHING', 'LOW_SCORE', 'SKIPPED',
  'FAILED', 'DISCARDED'] as const

/** Which templates a page reads: the operator's own, or headquarters' for an owner. */
export interface Scope {
  admin: boolean
  owner: string
}

const params = (scope: Scope, extra: Row = {}): Row => (scope.admin ? { owner: scope.owner, ...extra } : extra)

export interface TrialOption { text: string; correct: boolean }
export interface TrialQuestion { stem: string; options: TrialOption[] }
/** The trial quiz made from the business's own site: five questions of four options, one right each. */
export interface Trial { title: string; intro: string | null; questions: TrialQuestion[] }

export interface Product { name: string; description: string | null }

/** A row of `qb.operator.prospects` / `qb.admin.prospects`. */
export interface Prospect {
  prospectId: string
  owner: string
  domain: string
  websiteUrl: string
  company: string | null
  status: string
  foundAt: string | null
  researchedAt: string | null
  language: string | null
  country: string | null
  sellsOnline: boolean | null
  shopify: boolean | null
  publicEmail: string | null
  refusesMarketing: boolean | null
  score: number | null
  scoreReasons: string[]
  products: Product[]
  trial: Trial | null
  mailSubject: string | null
  mailBody: string | null
  failure: string | null
  approvedAt: string | null
  approvedBy: string | null
  sentAt: string | null
  discardedAt: string | null
  revision: number
}

/** A JSON column, or the empty value when it is absent or not readable. */
function parsed<T>(value: unknown, empty: T): T {
  if (typeof value !== 'string' || !value) return empty
  try {
    return JSON.parse(value) as T
  } catch {
    return empty
  }
}

function toProspect(row: Row): Prospect {
  return {
    prospectId: String(row.prospectId),
    owner: String(row.owner ?? ''),
    domain: String(row.domain ?? ''),
    websiteUrl: String(row.websiteUrl ?? ''),
    company: str(row.company),
    status: String(row.status ?? ''),
    foundAt: str(row.foundAt),
    researchedAt: str(row.researchedAt),
    language: str(row.language),
    country: str(row.country),
    sellsOnline: bool(row.sellsOnline),
    shopify: bool(row.shopify),
    publicEmail: str(row.publicEmail),
    refusesMarketing: bool(row.refusesMarketing),
    score: row.score == null ? null : num(row.score),
    scoreReasons: parsed<string[]>(row.scoreReasons, []),
    products: parsed<Product[]>(row.products, []),
    trial: parsed<Trial | null>(row.trial, null),
    mailSubject: str(row.mailSubject),
    mailBody: str(row.mailBody),
    failure: str(row.failure),
    approvedAt: str(row.approvedAt),
    approvedBy: str(row.approvedBy),
    sentAt: str(row.sentAt),
    discardedAt: str(row.discardedAt),
    revision: num(row.revision),
  }
}

/** The businesses found, newest first, of a state when one is given. */
export async function readProspects(scope: Scope, status: string | null, q: string, offset: number,
  limit: number): Promise<Page<Prospect>> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspects : TEMPLATE.operatorProspects, {
    params: params(scope, { status: status || undefined, q: q || undefined }), offset, limit, count: true,
  })
  return { total: page.total, items: page.items.map(toProspect) }
}

/** One business; null when it is none of the owner's. */
export async function readProspect(scope: Scope, prospectId: string): Promise<Prospect | null> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspects : TEMPLATE.operatorProspects, {
    params: params(scope, { prospectId }), limit: 1,
  })
  return page.items[0] ? toProspect(page.items[0]) : null
}

/** The owner's settings and its mail server's state; the values are null before the first save. */
export interface ProspectSetting {
  owner: string
  saved: boolean
  enabled: boolean
  dailyCap: number
  minScore: number
  monthlyCostCap: string
  senderName: string
  senderAddress: string
  senderContact: string
  /** Q48d: drafts scoring at least this are approved without a person; null: never. */
  autoApproveScore: number | null
  revision: number | null
  serverHost: string | null
  serverPort: number | null
  serverTls: string | null
  serverUsername: string | null
  fromAddress: string | null
  fromName: string | null
  verifiedAt: string | null
  lastError: string | null
}

export async function readSetting(scope: Scope): Promise<ProspectSetting | null> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspectSetting : TEMPLATE.operatorProspectSetting, {
    params: params(scope), limit: 1,
  })
  const row = page.items[0]
  if (!row) return null
  return {
    owner: String(row.owner ?? ''),
    saved: row.revision != null,
    enabled: row.enabled === true,
    dailyCap: row.dailyCap == null ? 10 : num(row.dailyCap),
    minScore: row.minScore == null ? 70 : num(row.minScore),
    monthlyCostCap: row.monthlyCostCap == null ? '50' : String(row.monthlyCostCap),
    senderName: String(row.senderName ?? ''),
    senderAddress: String(row.senderAddress ?? ''),
    senderContact: String(row.senderContact ?? ''),
    autoApproveScore: row.autoApproveScore == null ? null : num(row.autoApproveScore),
    revision: row.revision == null ? null : num(row.revision),
    serverHost: str(row.serverHost),
    serverPort: row.serverPort == null ? null : num(row.serverPort),
    serverTls: str(row.serverTls),
    serverUsername: str(row.serverUsername),
    fromAddress: str(row.fromAddress),
    fromName: str(row.fromName),
    verifiedAt: str(row.verifiedAt),
    lastError: str(row.lastError),
  }
}

/** A direction of search. */
export interface Direction {
  directionId: string
  name: string
  brief: string
  country: string | null
  language: string | null
  active: boolean
  perSearch: number
  lastSearchAt: string | null
  revision: number
  found: number
  sent: number
}

export async function readDirections(scope: Scope): Promise<Direction[]> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspectDirections
    : TEMPLATE.operatorProspectDirections, { params: params(scope), limit: 50 })
  return page.items.map((row) => ({
    directionId: String(row.directionId),
    name: String(row.name ?? ''),
    brief: String(row.brief ?? ''),
    country: str(row.country),
    language: str(row.language),
    active: row.active === true,
    perSearch: num(row.perSearch),
    lastSearchAt: str(row.lastSearchAt),
    revision: num(row.revision),
    found: num(row.found),
    sent: num(row.sent),
  }))
}

/** Q48d: a direction's funnel, from businesses found to the sponsors they brought. */
export interface FunnelRow {
  directionId: string
  name: string
  found: number
  researched: number
  drafted: number
  sent: number
  followedUp: number
  signedUp: number
  approved: number
}

export async function readFunnel(scope: Scope): Promise<FunnelRow[]> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspectFunnel : TEMPLATE.operatorProspectFunnel, {
    params: params(scope), limit: 50,
  })
  return page.items.map((row) => ({
    directionId: String(row.directionId), name: String(row.name ?? ''), found: num(row.found),
    researched: num(row.researched), drafted: num(row.drafted), sent: num(row.sent), followedUp: num(row.followedUp),
    signedUp: num(row.signedUp), approved: num(row.approved),
  }))
}

/** What prospecting cost by month, the newest first. */
export interface Usage {
  month: string
  inputTokens: number
  outputTokens: number
  searches: number
  jobs: number
  costUsd: string
}

export async function readUsage(scope: Scope): Promise<Usage[]> {
  const page = await runQuery<Row>(scope.admin ? TEMPLATE.adminProspectUsage : TEMPLATE.operatorProspectUsage, {
    params: params(scope), limit: 12,
  })
  return page.items.map((row) => ({
    month: String(row.month ?? ''),
    inputTokens: num(row.inputTokens),
    outputTokens: num(row.outputTokens),
    searches: num(row.searches),
    jobs: num(row.jobs),
    costUsd: String(row.costUsd ?? '0'),
  }))
}

// ---- processes -------------------------------------------------------------------------------------------------------

export interface SettingsInput {
  enabled: boolean
  dailyCap: number
  minScore: number
  monthlyCostCap: string
  senderName: string
  senderAddress: string
  senderContact: string
  autoApproveScore: number | null
  baseRevision: number | null
}

export const saveSettings = (input: SettingsInput) =>
  runProcess<{ owner: string; revision: number }>(PROSPECT_PROCESS.settingsSave, { ...input })

export interface DirectionInput {
  directionId?: string
  name: string
  brief: string
  country: string | null
  language: string | null
  active: boolean
  perSearch: number
  baseRevision?: number
}

export const saveDirection = (input: DirectionInput) =>
  runProcess<{ directionId: string; revision: number }>(PROSPECT_PROCESS.directionSave, { ...input })

/** The mail server the owner's mails leave through: tested by a mail to the caller before it is used. */
export interface MailServerInput {
  host: string
  port: number
  tls: string
  username: string
  password: string | null
  fromAddress: string
  fromName: string | null
}

export const saveMailServer = (input: MailServerInput) =>
  runProcess<{ verified: boolean; error: string | null; sentTo: string | null }>(PROSPECT_PROCESS.mailServerSet,
    { ...input })

export const removeMailServer = () => runProcess<{ sponsorId: string }>(PROSPECT_PROCESS.mailServerRemove, {})

/** Approves the draft as the person edited it; it leaves with the day's sends. */
export const approve = (prospectId: string, mailSubject: string, mailBody: string, trial: Trial | null,
  baseRevision: number) =>
  runProcess<{ prospectId: string; status: string }>(PROSPECT_PROCESS.approve, {
    prospectId, mailSubject, mailBody, trial, baseRevision,
  })

/** Discards the business; with `block`, its address and domain are never written to again, by anyone. */
export const discard = (prospectId: string, block: boolean, note: string | null) =>
  runProcess<{ prospectId: string; status: string }>(PROSPECT_PROCESS.discard, { prospectId, block, note })

/** The query key of everything the prospecting pages read. */
export const PROSPECTS_KEY = ['qb-ext', 'prospects'] as const
