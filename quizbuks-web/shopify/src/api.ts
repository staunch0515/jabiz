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
