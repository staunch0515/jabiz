/**
 * The limits of quiz content, copied from the server's `content.ContentLimits` and the file policies of
 * `content.ContentConfig`. The server checks them all; the page uses them to stop typing early and to say them.
 * The question bank's own limits are parameters, read from `qb.sponsor.quiz-limits` (`readBankLimits`, F-S6).
 */
export const LIMITS = {
  materials: 10,
  options: 6,
  minOptions: 2,
  images: 20,
  title: 100,
  intro: 5_000,
  stem: 1_000,
  option: 300,
  materialTitle: 100,
  materialDescription: 500,
  materialBody: 20_000,
  url: 2_000,
  caption: 200,
  minTimeLimitSec: 30,
  maxTimeLimitSec: 7_200,
  minPoints: 1,
  maxPoints: 1_000,
} as const

/** What each file policy takes: offered types (checked again by the server, by content) and the largest size. */
export const UPLOADS = {
  image: { accept: 'image/jpeg,image/png', maxSize: 10 * 1024 * 1024, maxSizeText: '10 MB' },
  pdf: { accept: 'application/pdf,.pdf', maxSize: 20 * 1024 * 1024, maxSizeText: '20 MB' },
  audio: {
    accept: 'audio/mpeg,audio/mp4,audio/x-m4a,audio/ogg,.mp3,.m4a,.ogg',
    maxSize: 50 * 1024 * 1024,
    maxSizeText: '50 MB',
  },
} as const

/** A PDF or an audio file: shown as a download. */
export const DOCUMENT_FIELD = { image: false }

/** F-S6: the questions one page of the editor's list shows. */
export const QUESTION_PAGE = 50

/** F-S6c: the questions one page of the translation page shows. */
export const TRANSLATION_PAGE = 50

/** F-S6: up to this many questions the "move to" control lists every place; above it the place is typed. */
export const MOVE_LIST_MAX = 100
