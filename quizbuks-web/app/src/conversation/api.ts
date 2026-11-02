import { runProcess, runQuery } from '@jabiz/client'
import { CONVERSATION_PROCESS, CONVERSATION_TEMPLATE } from './ids'

/*
 * The app's conversations with sponsors (Q82): typed by hand after `ConversationProcesses` (OpenInput, SendInput,
 * MessageOutput, StatusOutput, BlockOutput) and the templates `qb.app.conversations` / `-messages` / `-blocks` /
 * `-unread`. The server decides who may write and what (plain text without addresses, so many a day); the pages show
 * its answers. The sponsor appears as their company, never as a person.
 */

export const CONVERSATIONS_PATH = '/conversations'
export const CONVERSATIONS_QUERY = ['qb', 'conversations'] as const

export type ConversationStatus = 'OPEN' | 'CLOSED'
export type Side = 'TAKER' | 'SPONSOR'

export const conversationPath = (conversationId: string) =>
  `${CONVERSATIONS_PATH}/${encodeURIComponent(conversationId)}`

/** The conversation of a path such as `/conversations/<id>`, or null for any other path. */
export function conversationOf(path: string): string | null {
  const match = /^\/conversations\/([^/]+)$/.exec(path)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return null
  }
}

export interface Conversation {
  conversationId: string
  publicationId: string
  publicationName: string
  sponsorCompany: string
  status: ConversationStatus
  closedBy: 'TAKER' | 'SPONSOR' | 'SYSTEM' | null
  lastMessageAt: string
  lastSender: Side
  lastBody: string | null
  unread: boolean
}

export interface Message {
  messageId: string
  sender: Side
  /** Null when its writer's account was erased. */
  body: string | null
  createdAt: string
}

export interface Block {
  blockId: string
  sponsorCompany: string
  createdAt: string
}

type Row = Record<string, unknown>
const text = (value: unknown) => (value === null || value === undefined ? null : String(value))

function conversationOfRow(row: Row): Conversation {
  return {
    conversationId: String(row.conversationId),
    publicationId: String(row.publicationId),
    publicationName: String(row.publicationName ?? ''),
    sponsorCompany: String(row.sponsorCompany ?? ''),
    status: String(row.status) as ConversationStatus,
    closedBy: text(row.closedBy) as Conversation['closedBy'],
    lastMessageAt: String(row.lastMessageAt ?? ''),
    lastSender: String(row.lastSender) as Side,
    lastBody: text(row.lastBody),
    unread: row.unread === true,
  }
}

/** The taker's conversations, newest first; only the one about a quiz, or only one, when given. */
export async function readConversations(only: { publicationId?: string; conversationId?: string } = {})
  : Promise<Conversation[]> {
  const params: Row = {}
  if (only.publicationId) params.publicationId = only.publicationId
  if (only.conversationId) params.conversationId = only.conversationId
  const page = await runQuery<Row>(CONVERSATION_TEMPLATE.conversations, { params, limit: 100 })
  return page.items.map(conversationOfRow)
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

export async function readBlocks(): Promise<Block[]> {
  const page = await runQuery<Row>(CONVERSATION_TEMPLATE.blocks, { params: {}, limit: 100 })
  return page.items.map((row) => ({
    blockId: String(row.blockId),
    sponsorCompany: String(row.sponsorCompany ?? ''),
    createdAt: String(row.createdAt ?? ''),
  }))
}

/** How many conversations have a sponsor's message the taker has not read. */
export async function readConversationsUnread(): Promise<number> {
  const row = (await runQuery<Row>(CONVERSATION_TEMPLATE.unread, { params: {}, limit: 1 })).items[0]
  return Number(row?.unread ?? 0)
}

export interface MessageResult {
  conversationId: string
  messageId: string | null
  created: boolean
}

export const openConversation = (publicationId: string, body: string) =>
  runProcess<MessageResult>(CONVERSATION_PROCESS.open, { publicationId, body })

export const sendMessage = (conversationId: string, body: string) =>
  runProcess<MessageResult>(CONVERSATION_PROCESS.send, { conversationId, body })

export const closeConversation = (conversationId: string) =>
  runProcess<{ conversationId: string; status: ConversationStatus }>(CONVERSATION_PROCESS.close, { conversationId })

export const markRead = (conversationId: string) =>
  runProcess<{ conversationId: string; status: ConversationStatus }>(CONVERSATION_PROCESS.read, { conversationId })

export const blockSponsor = (conversationId: string) =>
  runProcess<{ blockId: string; closed: number }>(CONVERSATION_PROCESS.block, { conversationId })

export const unblockSponsor = (blockId: string) =>
  runProcess<{ blockId: null; closed: number }>(CONVERSATION_PROCESS.unblock, { blockId })
