import { runProcess, runQuery } from '@jabiz/client'
import { MESSAGES_PATH } from '../notices/api'
import { CONVERSATION_PROCESS, CONVERSATION_TEMPLATE } from './ids'

/*
 * The console's conversations with takers (Q82): typed by hand after `ConversationProcesses` and the templates
 * `qb.sponsor.conversations` / `-messages` / `-rewards` / `-unread`. Only a taker opens one; the sponsor answers and
 * may close it. A taker shows by their profile's name, never their address or id.
 */

export const CONVERSATIONS_QUERY = ['qb', 'sponsor-conversations'] as const
export const CONVERSATIONS_TAB = 'conversations'
/** The message centre opened on its conversations. */
export const CONVERSATIONS_PATH = `${MESSAGES_PATH}?tab=${CONVERSATIONS_TAB}`

export type ConversationStatus = 'OPEN' | 'CLOSED'
export type Side = 'TAKER' | 'SPONSOR'

export const conversationPath = (conversationId: string) =>
  `${MESSAGES_PATH}/conversations/${encodeURIComponent(conversationId)}`

/** The conversation of a path such as `/messages/conversations/<id>`, or null for any other path. */
export function conversationOf(path: string): string | null {
  const match = /^\/messages\/conversations\/([^/]+)$/.exec(path)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export interface ConversationRow {
  conversationId: string
  publicationId: string
  publicationName: string
  takerName: string | null
  status: ConversationStatus
  closedBy: 'TAKER' | 'SPONSOR' | 'SYSTEM' | null
  lastMessageAt: string
  lastSender: Side
  lastBody: string | null
  unread: boolean
  waitingReply: boolean
}

export interface Message {
  messageId: string
  sender: Side
  body: string | null
  createdAt: string
}

/** What the conversation's taker won on its quiz that the sponsor settles. */
export interface RewardRow {
  kind: 'CASH' | 'REDEEM' | 'DIGITAL'
  recordId: string
  sourceKind: string | null
  code: string | null
  hint: string | null
  amount: unknown
  status: string
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value === null || value === undefined ? null : String(value))

export async function readConversations(only: { conversationId?: string; waiting?: boolean } = {})
  : Promise<ConversationRow[]> {
  const params: Row = {}
  if (only.conversationId) params.conversationId = only.conversationId
  if (only.waiting) params.waiting = true
  const page = await runQuery<Row>(CONVERSATION_TEMPLATE.conversations, { params, limit: 200 })
  return page.items.map((row) => ({
    conversationId: String(row.conversationId),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    takerName: text(row.takerName),
    status: String(row.status) as ConversationStatus,
    closedBy: text(row.closedBy) as ConversationRow['closedBy'],
    lastMessageAt: String(row.lastMessageAt ?? ''),
    lastSender: String(row.lastSender) as Side,
    lastBody: text(row.lastBody),
    unread: row.unread === true,
    waitingReply: row.waitingReply === true,
  }))
}

export async function readMessages(conversationId: string): Promise<Message[]> {
  const page = await runQuery<Row>(CONVERSATION_TEMPLATE.messages, { params: { conversationId }, limit: 500 })
  return page.items.map((row) => ({
    messageId: String(row.messageId),
    sender: String(row.sender) as Side,
    body: text(row.body),
    createdAt: String(row.createdAt ?? ''),
  }))
}

export async function readRewards(conversationId: string): Promise<RewardRow[]> {
  const page = await runQuery<Row>(CONVERSATION_TEMPLATE.rewards, { params: { conversationId }, limit: 50 })
  return page.items.map((row) => ({
    kind: String(row.kind) as RewardRow['kind'],
    recordId: String(row.recordId),
    sourceKind: text(row.sourceKind),
    code: text(row.code),
    hint: text(row.hint),
    amount: row.amount,
    status: String(row.status ?? ''),
  }))
}

export async function readUnreadConversations(): Promise<number> {
  const row = (await runQuery<Row>(CONVERSATION_TEMPLATE.unread, { params: {}, limit: 1 })).items[0]
  return Number(row?.unread ?? 0)
}

export const sendMessage = (conversationId: string, body: string) =>
  runProcess<{ conversationId: string; messageId: string }>(CONVERSATION_PROCESS.send, { conversationId, body })

export const closeConversation = (conversationId: string) =>
  runProcess<{ conversationId: string; status: ConversationStatus }>(CONVERSATION_PROCESS.close, { conversationId })

export const markRead = (conversationId: string) =>
  runProcess<{ conversationId: string; status: ConversationStatus }>(CONVERSATION_PROCESS.read, { conversationId })
