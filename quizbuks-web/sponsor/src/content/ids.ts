/**
 * The names the sponsor console's content workshop calls on the server (backend/quizbuks, package `content`;
 * docs/quizbuks/plans/Q3-content.md). Only the sponsor console edits quizzes, so they live here rather than in the
 * shared `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */

/** Processes (`content.QuizProcesses`, `QuestionProcesses`, `MaterialProcesses`). */
export const CONTENT_PROCESS = {
  quizSave: 'QB_QUIZ_SAVE',
  quizDelete: 'QB_QUIZ_DELETE',
  quizClone: 'QB_QUIZ_CLONE',
  quizPublishVersion: 'QB_QUIZ_PUBLISH_VERSION',
  quizImportCheck: 'QB_QUIZ_IMPORT_CHECK',
  /** Q99b: the full address a short video link (b23.tv) leads to. */
  videoLinkResolve: 'QB_VIDEO_LINK_RESOLVE',
  quizImport: 'QB_QUIZ_IMPORT',
  questionSave: 'QB_QUESTION_SAVE',
  questionDelete: 'QB_QUESTION_DELETE',
  questionReorder: 'QB_QUESTION_REORDER',
  /** F-S6: one question to another place, several deleted or given points at once, a batch imported and undone. */
  questionMove: 'QB_QUESTION_MOVE',
  questionDeleteMany: 'QB_QUESTION_DELETE_MANY',
  questionPointsSet: 'QB_QUESTION_POINTS_SET',
  quizImportQuestions: 'QB_QUIZ_IMPORT_QUESTIONS',
  questionImportUndo: 'QB_QUESTION_IMPORT_UNDO',
  materialSave: 'QB_MATERIAL_SAVE',
  materialDelete: 'QB_MATERIAL_DELETE',
  materialReorder: 'QB_MATERIAL_REORDER',
  /** Q73a: the quiz's other languages, and the translations of its text into them. */
  languagesSet: 'QB_QUIZ_LANGUAGES_SET',
  translationSave: 'QB_TRANSLATION_SAVE',
  /** F-S9: the quiz's topics (words of the word list and custom words). */
  topicsSet: 'QB_QUIZ_TOPICS_SET',
} as const


/** SQL templates (`queries/qb/sponsor`). */
export const CONTENT_TEMPLATE = {
  quizzes: 'qb.sponsor.quizzes',
  quizVersions: 'qb.sponsor.quiz-versions',
  quizVersion: 'qb.sponsor.quiz-version',
  /** F-S6: a page of a quiz's questions, searched by text or number; the bank's limits in effect. */
  quizQuestions: 'qb.sponsor.quiz-questions',
  quizLimits: 'qb.sponsor.quiz-limits',
  /** F-S6c: a page of a quiz's questions in one of its other languages, with their options and translations. */
  quizTranslations: 'qb.sponsor.quiz-translations',
  /** F-S6d: the summary line over a quiz's questions. */
  quizQuestionSummary: 'qb.sponsor.quiz-question-summary',
  /** F-S6c: per other language, the texts missing and out of date over the whole quiz. */
  quizTranslationProgress: 'qb.sponsor.quiz-translation-progress',
} as const

/** The sponsor's own rows of the content entities (read only; writing is the processes'). */
export const CONTENT_DATASET = {
  quiz: 'urn:jabiz:dataset:sponsor:QbQuiz',
  question: 'urn:jabiz:dataset:sponsor:QbQuestion',
  option: 'urn:jabiz:dataset:sponsor:QbOption',
  material: 'urn:jabiz:dataset:sponsor:QbMaterial',
  materialImage: 'urn:jabiz:dataset:sponsor:QbMaterialImage',
  translation: 'urn:jabiz:dataset:sponsor:QbContentTranslation',
  /** F-S6: the batches imported into a quiz (`QB_QUIZ_IMPORT_QUESTIONS`). */
  questionImport: 'urn:jabiz:dataset:sponsor:QbQuestionImport',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const CONTENT_DICTIONARY = {
  quizStatus: 'urn:jabiz:dict:quizbuks:quiz-status',
  materialKind: 'urn:jabiz:dict:quizbuks:material-kind',
} as const

/** File policies (`content.ContentConfig`): who may upload and read, the types and sizes are the server's. */
export const CONTENT_FILE_POLICY = {
  image: 'qb.content.image',
  pdf: 'qb.content.pdf',
  audio: 'qb.content.audio',
} as const

/** Error codes the pages react to (`content.ContentCodes`). */
export const CONTENT_CODE = {
  /** The quiz changed since the page read it (another window or person): reload before editing on. */
  quizChanged: 'QB_QUIZ_CHANGED',
  /** The order sent is not the quiz's current set of items: the page is out of date too. */
  orderMismatch: 'QB_ORDER_MISMATCH',
} as const
