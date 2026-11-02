/**
 * The names the console's conversations with takers call on the server (Q82, docs/quizbuks/plans/Q82-conversations.md).
 * Only the console answers as a sponsor, so they live here rather than in the shared `src/qb/ids.ts`.
 */

export const CONVERSATION_PROCESS = {
  send: 'QB_CONVERSATION_SEND',
  close: 'QB_CONVERSATION_CLOSE',
  read: 'QB_CONVERSATION_READ',
} as const

export const CONVERSATION_TEMPLATE = {
  conversations: 'qb.sponsor.conversations',
  messages: 'qb.sponsor.conversation-messages',
  rewards: 'qb.sponsor.conversation-rewards',
  unread: 'qb.sponsor.conversations-unread',
} as const

/** The longest message the server takes (`ConversationRules.BODY_LENGTH`). */
export const MESSAGE_LENGTH = 1000
