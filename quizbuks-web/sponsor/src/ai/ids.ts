/**
 * The names the sponsor console's AI questions call on the server (backend/quizbuks, package `content.ai`;
 * docs/quizbuks/plans/Q8-ai.md, Q8a). Only the sponsor console asks the AI, so they live here rather than in the shared
 * `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`content.ai.AiProcesses`), permission `qb.ai.use`. */
export const AI_PROCESS = {
  request: 'QB_AI_QUESTIONS_REQUEST',
  accept: 'QB_AI_ACCEPT',
  /** Candidate covers (Q8b, M-25), and the one chosen. */
  coverRequest: 'QB_AI_COVER_REQUEST',
  coverAccept: 'QB_AI_COVER_ACCEPT',
  /** Candidate translations of the quiz's text (Q73d-1), and the reviewed ones written. */
  translateRequest: 'QB_AI_TRANSLATE_REQUEST',
  translationAccept: 'QB_AI_TRANSLATION_ACCEPT',
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
  /**
   * The request's largest size in bytes (`ClaudeApi.MAX_INPUT_BYTES`, 250 KiB since Q8b); larger is refused, never
   * cut. PDF materials are not part of it: they are sent as the files themselves.
   */
  maxInputBytes: 250 * 1024,
  /** A PDF material's largest size and all PDFs' pages as the server sends them by default (`quizbuks.ai.pdf.*`). */
  maxPdfMb: 10,
  maxPdfPages: 100,
  /** Candidate covers of one request (`CoverDesigns.COVERS`). */
  covers: 3,
  /** How often a running job is looked at, and for how long at most. */
  pollMs: 5_000,
  pollForMs: 5 * 60_000,
} as const

/** The kinds of AI jobs (`QbAi.KIND_*`): the template is read for one at a time. */
export const AI_KINDS = { questions: 'QUESTIONS', cover: 'COVER', translation: 'TRANSLATION' } as const

export const DIFFICULTIES = ['EASY', 'MEDIUM', 'HARD'] as const
export const AI_LANGUAGES = ['en', 'zh', 'ja'] as const
