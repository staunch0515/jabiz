import { importQuestions, type ImportQuestionsOutput } from './api'
import { checkImport, ImportStepError, uploadImportFiles, withoutData, type ImportStep } from './transfer'

/*
 * F-S6b (docs/quizbuks/plans/impl/F-S6.md §3.5): a quiz file's questions imported into an open quiz in batches of the
 * bank's `importBatch` (`QB_QUIZ_IMPORT_QUESTIONS`, each batch its own transaction). Each batch is a quiz file of its
 * own holding only its questions, numbered 1..k, with their translations renumbered to match and no materials; the
 * server checks it, its files are uploaded as for a new quiz, and the batch is imported. A refused batch stops the
 * import: the batches before it stay.
 */

type Doc = Record<string, unknown>

const isObject = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)

/** A translation key of a question (`q12.stem`, `q12.o3.image`): its number and the rest. */
const QUESTION_KEY = /^q(\d+)\.(.+)$/

/** The keys of a question's file fields (its image, its options' images). */
function fileKeysOf(question: unknown): string[] {
  if (!isObject(question)) return []
  const keys: string[] = []
  if (typeof question.image === 'string') keys.push(question.image)
  if (Array.isArray(question.options)) {
    for (const option of question.options) {
      if (isObject(option) && typeof option.image === 'string') keys.push(option.image)
    }
  }
  return keys
}

/**
 * The questions of a quiz's translation (texts, images, hashes) whose number is in `from + 1 .. from + count`,
 * renumbered from 1; everything else (the title, the introduction, the cover, materials) left out.
 */
function renumbered(entries: unknown, from: number, count: number): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  if (!isObject(entries)) return out
  for (const [key, value] of Object.entries(entries)) {
    const match = QUESTION_KEY.exec(key)
    if (!match) continue
    const n = Number(match[1])
    if (n > from && n <= from + count) out[`q${n - from}.${match[2]}`] = value
  }
  return out
}

/** How many questions a quiz file has (0 when it has none or is not one). */
export function questionCountOf(document: Doc): number {
  return Array.isArray(document.questions) ? document.questions.length : 0
}

/** What of a quiz file an import into an existing quiz leaves out: materials (also of other languages), products. */
export function leftOutOf(document: Doc): { materials: number; products: boolean } {
  const own = Array.isArray(document.materials) ? document.materials.length : 0
  const translated = isObject(document.translations)
    ? Object.values(document.translations).reduce<number>((sum, t) =>
      sum + (isObject(t) && Array.isArray(t.materials) ? t.materials.length : 0), 0)
    : 0
  return { materials: own + translated, products: isObject(document.products) }
}

/**
 * The quiz file's questions as batches of at most `size`, each a quiz file of its own: the same format and heading
 * (without its cover), only its questions numbered 1..k, each other language's texts, images and hashes of those
 * questions renumbered to match, no materials, no products, and only the files its questions use.
 */
export function splitQuestions(document: Doc, size: number): Doc[] {
  const questions = Array.isArray(document.questions) ? document.questions : []
  const per = Math.max(1, Math.floor(size))
  const files = isObject(document.files) ? document.files : {}
  const heading = isObject(document.quiz) ? { ...document.quiz, cover: null } : document.quiz
  const batches: Doc[] = []
  for (let from = 0; from < questions.length; from += per) {
    const own = questions.slice(from, from + per)
    const used = new Set(own.flatMap(fileKeysOf))
    const batch: Doc = {
      ...document,
      quiz: heading,
      materials: [],
      questions: own,
      files: {},
    }
    delete batch.products
    if (isObject(document.translations)) {
      const translations: Record<string, unknown> = {}
      for (const [language, t] of Object.entries(document.translations)) {
        if (!isObject(t)) continue
        const images = renumbered(t.images, from, own.length)
        for (const key of Object.values(images)) if (typeof key === 'string') used.add(key)
        translations[language] = { texts: renumbered(t.texts, from, own.length), images,
          materials: [], hashes: renumbered(t.hashes, from, own.length) }
      }
      batch.translations = translations
    }
    const batchFiles: Record<string, unknown> = {}
    for (const key of used) if (key in files) batchFiles[key] = files[key]
    batch.files = batchFiles
    batches.push(batch)
  }
  return batches
}

/** How far an import into a quiz got: the batch at work (from 1) of all, and its files uploaded. */
export interface BatchProgress {
  batch: number
  batches: number
  files: number
  filesTotal: number
}

/** The result of the batches imported. */
export interface BatchesImported {
  questions: number
  batches: number
  /** Every batch's languages not imported, once each. */
  skippedLanguages: string[]
  revision: number
}

/** A batch refused (or failing at one of its files): which, why, and what the batches before it imported. */
export class BatchImportError extends Error {
  readonly batch: number
  readonly batches: number
  readonly imported: number
  readonly skippedLanguages: string[]
  /** The step of the batch that failed: one of its files, or importing it. */
  readonly step: ImportStep

  constructor(batch: number, batches: number, imported: number, skippedLanguages: string[], step: ImportStep,
    cause: unknown) {
    super(`Batch ${batch} of ${batches} failed`, { cause })
    this.batch = batch
    this.batches = batches
    this.imported = imported
    this.skippedLanguages = skippedLanguages
    this.step = step
  }
}

/**
 * Imports the file's questions into the quiz, batch after batch: each checked by the server, its files uploaded,
 * then imported with the revision the last batch left (`revision` for the first). `onImported` hears every batch
 * imported. A failure throws {@link BatchImportError}; the batches before it stay.
 */
export async function importInBatches({ quizId, document, size, revision, onProgress, onImported, wait }: {
  quizId: string
  document: Doc
  size: number
  revision: number
  onProgress?: (progress: BatchProgress) => void
  onImported?: (output: ImportQuestionsOutput) => void
  wait?: (ms: number) => Promise<unknown>
}): Promise<BatchesImported> {
  const batches = splitQuestions(document, size)
  const skipped = new Set<string>()
  let imported = 0
  let current = revision
  for (const [i, batch] of batches.entries()) {
    const n = i + 1
    const fail = (step: ImportStep, cause: unknown) =>
      new BatchImportError(n, batches.length, imported, [...skipped], step, cause)
    onProgress?.({ batch: n, batches: batches.length, files: 0, filesTotal: 0 })
    let files: Record<string, string>
    try {
      const check = await checkImport(batch)
      files = await uploadImportFiles(batch, check, (done, total) =>
        onProgress?.({ batch: n, batches: batches.length, files: done, filesTotal: total }), wait)
    } catch (e) {
      throw e instanceof ImportStepError ? fail(e.step, e.cause) : fail({ kind: 'draft' }, e)
    }
    let output: ImportQuestionsOutput
    try {
      output = await importQuestions(quizId, withoutData(batch), files, current)
    } catch (e) {
      throw fail({ kind: 'draft' }, e)
    }
    imported += output.questions
    current = output.revision
    for (const language of output.skippedLanguages ?? []) skipped.add(language)
    onImported?.(output)
  }
  return { questions: imported, batches: batches.length, skippedLanguages: [...skipped], revision: current }
}
