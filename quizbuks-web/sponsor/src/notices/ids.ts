/**
 * The names the sponsor console's message centre calls on the server (backend/quizbuks, package `messaging`;
 * docs/quizbuks/plans/Q7-messages-legal.md, Q7c: M-60, M-61). Only the console sends notices, so they live here rather
 * than in the shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`messaging.BroadcastProcesses`, `messaging.InboxProcesses`). */
export const NOTICE_PROCESS = {
  save: 'QB_BROADCAST_SAVE',
  send: 'QB_BROADCAST_SEND',
  cancel: 'QB_BROADCAST_CANCEL',
  withdraw: 'QB_BROADCAST_WITHDRAW',
  mark: 'QB_MESSAGE_MARK',
} as const

/** SQL templates (`queries/qb/sponsor`). */
export const NOTICE_TEMPLATE = {
  notices: 'qb.sponsor.broadcasts',
  publications: 'qb.sponsor.broadcast-publications',
  inbox: 'qb.sponsor.inbox',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const NOTICE_DICTIONARY = {
  status: 'urn:jabiz:dict:quizbuks:broadcast-status',
} as const
