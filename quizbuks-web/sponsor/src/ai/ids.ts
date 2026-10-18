/**
 * The names the sponsor console's AI questions call on the server (backend/quizbuks, package `content.ai`;
 * docs/quizbuks/plans/Q8-ai.md, Q8a). Only the sponsor console asks the AI, so they live here rather than in the shared
 * `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`content.ai.AiProcesses`), permission `qb.ai.use`. */
export const AI_PROCESS = {
  request: 'QB_AI_QUESTIONS_REQUEST',
  accept: 'QB_AI_ACCEPT',
  discard: 'QB_AI_DISCARD',
  cancel: 'QB_AI_CANCEL',
} as const

/** SQL templates (`queries/qb/sponsor`), the sponsor's own rows only. */
export const AI_TEMPLATE = {
  jobs: 'qb.sponsor.ai-jobs',
  usage: 'qb.sponsor.ai-usage',
} as const

/** Error codes the pages react to (the server words them itself). */
export const AI_CODE = {
  /** The AI notice must be accepted first: the dialog shows it. */
  noticeRequired: 'QB_AI_NOTICE_REQUIRED',
} as const

/** The limits the page checks before asking (the server checks again). */
export const AI_LIMITS = {
  maxCount: 20,
  /** The request's largest size in bytes (`ClaudeApi.MAX_INPUT_BYTES`); larger is refused, never cut. */
  maxInputBytes: 60 * 1024,
  /** How often a running job is looked at, and for how long at most. */
  pollMs: 5_000,
  pollForMs: 5 * 60_000,
} as const

export const DIFFICULTIES = ['EASY', 'MEDIUM', 'HARD'] as const
export const AI_LANGUAGES = ['en', 'zh', 'ja'] as const
