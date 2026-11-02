/**
 * The names the app's conversations with sponsors call on the server (Q82, docs/quizbuks/plans/Q82-conversations.md).
 * Only the app opens conversations as a taker, so they live here rather than in the shared `src/qb/ids.ts`.
 */

export const CONVERSATION_PROCESS = {
  /** A taker writes to the sponsor of a quiz they completed: opens the conversation, or writes into it. */
  open: 'QB_CONVERSATION_OPEN',
  /** Writes into an open conversation. */
  send: 'QB_CONVERSATION_SEND',
  close: 'QB_CONVERSATION_CLOSE',
  /** Marks the conversation read up to now (on opening its page). */
  read: 'QB_CONVERSATION_READ',
  /** Blocks the conversation's sponsor (closing every conversation with them). */
  block: 'QB_CONVERSATION_BLOCK',
  unblock: 'QB_CONVERSATION_UNBLOCK',
} as const

export const CONVERSATION_TEMPLATE = {
  conversations: 'qb.app.conversations',
  messages: 'qb.app.conversation-messages',
  blocks: 'qb.app.conversation-blocks',
  unread: 'qb.app.conversations-unread',
} as const

/** The longest message the server takes (`ConversationRules.BODY_LENGTH`). */
export const MESSAGE_LENGTH = 1000
