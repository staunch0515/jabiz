/**
 * The names the app's inbox calls on the server (backend/quizbuks, package `messaging`; docs/quizbuks/plans/
 * Q7-messages-legal.md, Q7c). Only the app shows a taker's inbox, so they live here rather than in the shared
 * `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`messaging.InboxProcesses`). */
export const INBOX_PROCESS = {
  mark: 'QB_MESSAGE_MARK',
  readAll: 'QB_INBOX_READ_ALL',
  settings: 'QB_INBOX_SETTINGS_SAVE',
} as const

/** SQL templates (`queries/qb/app`). */
export const INBOX_TEMPLATE = {
  inbox: 'qb.app.inbox',
  unread: 'qb.app.inbox-unread',
} as const

/** Datasets: the taker's own inbox settings, read only. */
export const INBOX_DATASET = {
  myState: 'urn:jabiz:dataset:me:QbInboxState',
} as const
