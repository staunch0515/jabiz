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
  questionSave: 'QB_QUESTION_SAVE',
  questionDelete: 'QB_QUESTION_DELETE',
  questionReorder: 'QB_QUESTION_REORDER',
  materialSave: 'QB_MATERIAL_SAVE',
  materialDelete: 'QB_MATERIAL_DELETE',
  materialReorder: 'QB_MATERIAL_REORDER',
} as const

/** SQL templates (`queries/qb/sponsor`). */
export const CONTENT_TEMPLATE = {
  quizzes: 'qb.sponsor.quizzes',
  quizVersions: 'qb.sponsor.quiz-versions',
  quizVersion: 'qb.sponsor.quiz-version',
} as const

/** The sponsor's own rows of the content entities (read only; writing is the processes'). */
export const CONTENT_DATASET = {
  quiz: 'urn:jabiz:dataset:sponsor:QbQuiz',
  question: 'urn:jabiz:dataset:sponsor:QbQuestion',
  option: 'urn:jabiz:dataset:sponsor:QbOption',
  material: 'urn:jabiz:dataset:sponsor:QbMaterial',
  materialImage: 'urn:jabiz:dataset:sponsor:QbMaterialImage',
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
