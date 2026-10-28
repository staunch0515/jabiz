import { runProcess } from '@jabiz/client'
import { sessionToken } from './appBridge'

/** The processes of QuizBuks for Shopify (backend/quizbuks-shopify; their records written by hand from the Java ones). */
export const JOIN = 'QBS_SHOP_JOIN'
export const ADMIN_TRANSFER = 'QBS_SHOP_ADMIN_TRANSFER'
export const CONNECT = 'QBS_SHOP_CONNECT'
export const CHECK = 'QBS_SHOP_CHECK'

export type Role = 'ADMIN' | 'MEMBER'
export type Connection = 'NONE' | 'CONNECTED' | 'RECONNECT'

export interface Member {
  userId: string
  staffId: string
  role: Role
  joinedAt: string
}

/** ShopProcesses.JoinOutput: the shop as its staff see it. */
export interface Shop {
  shopId: string
  shopDomain: string
  shopName: string | null
  connection: Connection
  role: Role
  joined: boolean
  reinstalled: boolean
  members: Member[]
}

/** ConnectionProcesses.ConnectionOutput. */
export interface ConnectionState {
  connection: Connection
  shopName: string | null
}

/** Joins (or rejoins) the shop with a fresh session token; the answer is the shop's overview. */
export async function join(): Promise<Shop> {
  return runProcess<Shop>(JOIN, { sessionToken: await sessionToken() })
}

export async function transferAdmin(toUserId: string): Promise<void> {
  await runProcess(ADMIN_TRANSFER, { toUserId })
}

/** Exchanges a fresh session token for the shop's access tokens. */
export async function connect(): Promise<ConnectionState> {
  return runProcess<ConnectionState>(CONNECT, { sessionToken: await sessionToken() })
}

export async function check(): Promise<ConnectionState> {
  return runProcess<ConnectionState>(CHECK, {})
}

// ---- quiz campaigns (S2b; backend CampaignProcesses, S2a) -------------------------------------------------------

export const CAMPAIGN_SAVE = 'QBS_CAMPAIGN_SAVE'
export const CAMPAIGN_PUBLISH = 'QBS_CAMPAIGN_PUBLISH'
export const CAMPAIGN_MOVE = 'QBS_CAMPAIGN_MOVE'
export const CAMPAIGN_LIST = 'QBS_CAMPAIGN_LIST'

export type CampaignStatus = 'DRAFT' | 'LIVE' | 'PAUSED' | 'ENDED'

/** Channel.OptionInput / QuestionInput: a question and its options, one of them correct. */
export interface CampaignOption {
  text: string
  correct: boolean
}

export interface CampaignQuestion {
  stem: string
  options: CampaignOption[]
}

/** CampaignProcesses.Campaign. */
export interface Campaign {
  campaignId: string
  status: CampaignStatus
  name: string
  video: string | null
  timeLimitSec: number
  passScore: number
  percent: number
  questions: CampaignQuestion[]
  publicationId: string | null
  revision: number
  createdAt: string
  editedAt: string
  publishedAt: string | null
  endedAt: string | null
}

/** CampaignProcesses.Pool: the running campaign's codes left to hand out, and those handed out. */
export interface Pool {
  available: number
  issuedToday: number
  issuedWeek: number
}

export interface CampaignList {
  campaigns: Campaign[]
  pool: Pool
}

/** CampaignProcesses.SaveInput: a new draft without campaignId, an edit with it and the revision read. */
export interface CampaignDraft {
  campaignId?: string
  baseRevision?: number
  name: string
  video: string | null
  timeLimitSec: number
  passScore: number
  percent: number
  questions: CampaignQuestion[]
}

/** The limits the server keeps (Channel and CampaignProcesses): the form says them before the server does. */
export const CAMPAIGN_LIMITS = {
  nameLength: 100,
  videoLength: 2000,
  minTimeLimitSec: 30,
  maxTimeLimitSec: 7200,
  maxQuestions: 20,
  minOptions: 2,
  maxOptions: 6,
  stemLength: 1000,
  optionLength: 300,
  minPercent: 1,
  maxPercent: 90,
} as const

export function listCampaigns(): Promise<CampaignList> {
  return runProcess<CampaignList>(CAMPAIGN_LIST, {})
}

export function saveCampaign(draft: CampaignDraft): Promise<Campaign> {
  return runProcess<Campaign>(CAMPAIGN_SAVE, { ...draft })
}

export function publishCampaign(campaignId: string): Promise<Campaign> {
  return runProcess<Campaign>(CAMPAIGN_PUBLISH, { campaignId })
}

/** Pauses (`PAUSED`), resumes (`LIVE`) or ends (`ENDED`) a campaign; ending a draft discards it. */
export function moveCampaign(campaignId: string, to: 'PAUSED' | 'LIVE' | 'ENDED'): Promise<Campaign> {
  return runProcess<Campaign>(CAMPAIGN_MOVE, { campaignId, to })
}

// ---- the plan (S3b, docs/quizbuks/shopify/S3b-ui.md) ---------------------------------------------------------------

export const PLAN_GET = 'QBS_PLAN_GET'

export type Plan = 'FREE' | 'STARTER' | 'GROWTH' | 'PRO'

/** PlanProcesses.PlanOutput: the store's plan and what its storefront used of it this period. */
export interface PlanState {
  plan: Plan
  /** The plan that follows at planUntil (after a move down); null when none is kept. */
  nextPlan: Plan | null
  planUntil: string | null
  shopifyPlan: string | null
  unknownPlan: boolean
  test: boolean
  trialEndsAt: string | null
  periodStart: string
  periodEnd: string
  used: number
  limit: number
  checkedAt: string | null
  connection: Connection
  /** Shopify's page of the plans, opened at the top of the admin. */
  pricingUrl: string
}

/** The store's plan; with refresh, read from Shopify now (else at most hourly). */
export function getPlan(refresh: boolean): Promise<PlanState> {
  return runProcess<PlanState>(PLAN_GET, { refresh })
}
