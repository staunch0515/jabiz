import { ApiError, fetchFileContent, runProcess, runQuery, uploadFile } from '@jabiz/client'
import type { ContentLanguage } from '../qb/api'
import { readMyProducts, readMyQuizLinks, type MyProduct, type QuizLink } from '../products/api'
import { readDraft, type Draft, type Material, type VersionDetail } from './api'
import { COVER_KEY, optionImageKey, optionTextKey, questionImageKey, stemKey, translationsIn, variantOf } from './languages'
import { CONTENT_FILE_POLICY, CONTENT_PROCESS } from './ids'

/**
 * Quiz files (docs/quizbuks/plans/Q16-quiz-transfer.md): a quiz's draft and every file it uses in one JSON file, and
 * back. The export is the console's own (it reads the draft and the files as the editor does); the import asks the
 * server which version of the format reads the file and what to upload (`QB_QUIZ_IMPORT_CHECK`), uploads the files
 * through the content's policies, then makes the draft in one transaction (`QB_QUIZ_IMPORT`).
 */

export const QUIZ_FILE_FORMAT = 'quizbuks.quiz'
/**
 * The version this console writes (`QuizFileFormat.CURRENT`); the server says which versions it reads. Version 2
 * (Q73a) adds the base language and the other languages; version 3 (Q100e) the sponsor's products the quiz shows.
 */
export const QUIZ_FILE_VERSION = 3
/**
 * The declared value of `qb.package.confirm-bytes` (F-C4): above it an export asks before going on (base64 adds
 * about a third to the files). Only a fallback: the export reads the parameter ({@link readConfirmBytes}).
 */
export const LARGE_EXPORT_BYTES = 100 * 1024 * 1024

/**
 * Above how many bytes an export asks first: the parameter `qb.package.confirm-bytes` (every number a parameter,
 * F-C4), read through `qb.sponsor.package-state`; its declared value when it cannot be read.
 */
export async function readConfirmBytes(): Promise<number> {
  try {
    const row = (await runQuery<Record<string, unknown>>('qb.sponsor.package-state', { limit: 1 })).items[0]
    const n = Number(row?.confirmBytes)
    return row?.confirmBytes != null && Number.isFinite(n) && n >= 0 ? n : LARGE_EXPORT_BYTES
  } catch {
    return LARGE_EXPORT_BYTES
  }
}

export interface QuizFileEntry {
  name: string
  type: string
  size: number
  /** The file's bytes, base64. */
  data: string
}

type FileMaterial = {
  kind: string; title: string | null; description: string | null; body: string | null; url: string | null
  pdf: string | null; audio: string | null; images: { image: string; caption: string | null }[]
  /** Q19, written only when true (absent is false). */
  watchToAnswer?: true
  /** Q75, written with `watchToAnswer` when the sponsor gave the duration (seconds). */
  durationSec?: number
}

/**
 * One other language of the quiz (version 2, `QuizFile.Language`): texts and own images by their key in a version's
 * snapshot (`q3.stem`, `q3.o2.text`, `q3.image`), its own materials numbered as the base material each stands for,
 * and the base hashes its texts were made from (an out-of-date translation stays out of date).
 */
export interface QuizFileLanguage {
  texts: Record<string, string>
  images: Record<string, string>
  materials: (FileMaterial & { no: number })[]
  hashes: Record<string, string>
}

/** The file's JSON (version 2). */
export interface QuizFileDocument {
  format: typeof QUIZ_FILE_FORMAT
  formatVersion: number
  exportedAt: string
  quiz: { kind: string; title: string; intro: string | null; timeLimitSec: number | null; cover: string | null
    language: ContentLanguage
    /** F-S7: present (false) only when the questions keep their order; absent is shuffled. */
    shuffleQuestions?: boolean }
  materials: FileMaterial[]
  questions: {
    stem: string | null; image: string | null; points: number; selection: string | null
    options: { text: string | null; image: string | null; correct: boolean }[]
    /** F-S7: present only when set. */
    difficulty?: string
  }[]
  files: Record<string, QuizFileEntry>
  /** Present only when the quiz has other languages. */
  translations?: Partial<Record<ContentLanguage, QuizFileLanguage>>
  /**
   * Q100e (D-Q100-14): the products the quiz shows, each by the sponsor's own code (else its id); present only when
   * it shows any. Question numbers are the file's (from 1).
   */
  products?: { quiz: ProductRef[]; questions: { no: number; product: ProductRef }[] }
}

/** A product named by its code or, without one, its id. */
export type ProductRef = { code: string } | { productId: string }

/** The quiz's product links (`qb.sponsor` links of `QbQuizProduct`, question 0 = the whole quiz) and the products. */
export interface QuizProducts { links: QuizLink[]; products: MyProduct[] }

/** Progress of an export or import: files done out of all. */
export type Progress = (done: number, total: number) => void

const EXTENSIONS: Record<string, string> = {
  'image/jpeg': 'jpg', 'image/png': 'png', 'application/pdf': 'pdf', 'audio/mpeg': 'mp3', 'audio/mp4': 'm4a',
  'audio/ogg': 'ogg',
}

/** The neutral name of a file of a field (`image.jpg`), never the sponsor's own. */
function nameOf(kind: 'image' | 'pdf' | 'audio', type: string): string {
  const extension = EXTENSIONS[type]
  return extension ? `${kind}.${extension}` : kind
}

/** Bytes per piece of {@link base64Of}: a multiple of 3, so the pieces' base64 joins without padding between. */
const BASE64_PIECE = 3 * 0x2000

/**
 * Base64 of a blob's bytes, piece by piece: never one binary string of the whole file (a 50 MB audio file), nor one
 * call with more arguments than an engine takes.
 */
export async function base64Of(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  const pieces: string[] = []
  for (let i = 0; i < bytes.length; i += BASE64_PIECE) {
    pieces.push(btoa(String.fromCharCode(...bytes.subarray(i, i + BASE64_PIECE))))
  }
  return pieces.join('')
}

/** The bytes of a base64 text as a blob of the type. */
export function blobOf(data: string, type: string): Blob {
  const binary = atob(data)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return new Blob([bytes], { type })
}

/** The draft as a quiz file, its files keyed `f1`, `f2` … in the order they are first used. */
export async function exportDraft(draft: Draft, onProgress?: Progress,
  read: (fileId: string) => Promise<Blob> = (id) => fetchFileContent(id),
  products?: QuizProducts): Promise<QuizFileDocument> {
  const keys = new Map<string, { key: string; kind: 'image' | 'pdf' | 'audio' }>()
  const key = (fileId: string | null, kind: 'image' | 'pdf' | 'audio'): string | null => {
    if (!fileId) return null
    if (!keys.has(fileId)) keys.set(fileId, { key: `f${keys.size + 1}`, kind })
    return keys.get(fileId)!.key
  }
  const { quiz } = draft
  const material = (m: Material): FileMaterial => ({
    kind: m.kind, title: m.title, description: m.description, body: m.body, url: m.url,
    pdf: key(m.pdf, 'pdf'), audio: key(m.audio, 'audio'),
    images: m.images.map((i) => ({ image: key(i.image, 'image')!, caption: i.caption })),
    ...(m.watchToAnswer ? { watchToAnswer: true as const } : {}),
    ...(m.watchToAnswer && m.durationSec != null ? { durationSec: m.durationSec } : {}),
  })
  const document: QuizFileDocument = {
    format: QUIZ_FILE_FORMAT,
    formatVersion: QUIZ_FILE_VERSION,
    exportedAt: new Date().toISOString(),
    quiz: { kind: quiz.kind, title: quiz.title, intro: quiz.intro, timeLimitSec: quiz.timeLimitSec,
      cover: key(quiz.cover, 'image'), language: quiz.language,
      // F-S7: only the fixed order is written (older readers skip it; absent is shuffled).
      ...(quiz.kind !== 'SURVEY' && quiz.shuffleQuestions === false ? { shuffleQuestions: false } : {}) },
    materials: draft.materials.map(material),
    questions: draft.questions.map((q) => ({
      stem: q.stem, image: key(q.image, 'image'), points: q.points, selection: q.selection,
      options: q.options.map((o) => ({ text: o.text, image: key(o.image, 'image'), correct: o.correct })),
      ...(q.difficulty ? { difficulty: q.difficulty } : {}),
    })),
    files: {},
  }
  // Q73a: each other language as the draft has it, complete or not.
  if (quiz.translationLanguages.length > 0) {
    document.translations = {}
    for (const language of quiz.translationLanguages) {
      const t = translationsIn(draft, language)
      const out: QuizFileLanguage = { texts: {}, images: {}, materials: [], hashes: {} }
      const text = (draftKey: string, fileKey: string) => {
        const row = t.get(draftKey)
        if (row?.text) {
          out.texts[fileKey] = row.text
          if (row.sourceHash) out.hashes[fileKey] = row.sourceHash
        }
      }
      const image = (draftKey: string, fileKey: string) => {
        const file = key(t.get(draftKey)?.image ?? null, 'image')
        if (file) out.images[fileKey] = file
      }
      text('quiz.title', 'title')
      text('quiz.intro', 'intro')
      // Q98b: the language's own cover.
      image(COVER_KEY, 'cover')
      draft.questions.forEach((q, i) => {
        text(stemKey(q.questionId), `q${i + 1}.stem`)
        image(questionImageKey(q.questionId), `q${i + 1}.image`)
        q.options.forEach((o, j) => {
          text(optionTextKey(o.optionId), `q${i + 1}.o${j + 1}.text`)
          image(optionImageKey(o.optionId), `q${i + 1}.o${j + 1}.image`)
        })
      })
      draft.materials.forEach((base, i) => {
        const own = variantOf(draft, base, language)
        if (own) out.materials.push({ no: i + 1, ...material({ ...own, watchToAnswer: false }) })
      })
      document.translations[language] = out
    }
  }
  // Q100e: the products the quiz shows, by code where the product has one.
  if (products && products.links.length > 0) {
    const byId = new Map(products.products.map((p) => [p.productId, p]))
    const ref = (productId: string): ProductRef => {
      const code = byId.get(productId)?.code
      return code ? { code } : { productId }
    }
    const ordered = [...products.links].sort((a, b) => a.seq - b.seq)
    document.products = {
      quiz: ordered.filter((l) => l.questionNo === 0).map((l) => ref(l.productId)),
      questions: ordered.filter((l) => l.questionNo > 0 && l.questionNo <= draft.questions.length)
        .map((l) => ({ no: l.questionNo, product: ref(l.productId) })),
    }
  }
  let done = 0
  onProgress?.(0, keys.size)
  for (const [fileId, { key: fileKey, kind }] of keys) {
    const blob = await read(fileId)
    document.files[fileKey] = { name: nameOf(kind, blob.type), type: blob.type, size: blob.size,
      data: await base64Of(blob) }
    onProgress?.(++done, keys.size)
  }
  return document
}

/**
 * F-C4 (docs/quizbuks/plans/impl/F-C4.md): a saved version as a quiz file of the same format as {@link exportDraft}
 * writes, from its snapshot: its heading, materials, questions and other languages (a version holds only complete
 * languages, so no base hashes are written: imported, they are up to date), its files keyed `f1`, `f2` … A version
 * records no products (they are the quiz's, written with its current content).
 */
export async function exportVersion(version: VersionDetail, onProgress?: Progress,
  read: (fileId: string) => Promise<Blob> = (id) => fetchFileContent(id)): Promise<QuizFileDocument> {
  const keys = new Map<string, { key: string; kind: 'image' | 'pdf' | 'audio' }>()
  const key = (fileId: string | null | undefined, kind: 'image' | 'pdf' | 'audio'): string | null => {
    if (!fileId) return null
    if (!keys.has(fileId)) keys.set(fileId, { key: `f${keys.size + 1}`, kind })
    return keys.get(fileId)!.key
  }
  const snapshot = version.content
  const byNo = <T extends { no: number }>(items: readonly T[]) => [...items].sort((a, b) => a.no - b.no)
  type SnapshotMaterial = VersionDetail['content']['materials'][number]
  const material = (m: SnapshotMaterial, watch: boolean): FileMaterial => ({
    kind: m.kind, title: m.title, description: m.description, body: m.body, url: m.url,
    pdf: key(m.pdf, 'pdf'), audio: key(m.audio, 'audio'),
    images: byNo(m.images).map((i) => ({ image: key(i.image, 'image')!, caption: i.caption })),
    ...(watch && m.watchToAnswer ? { watchToAnswer: true as const } : {}),
    ...(watch && m.watchToAnswer && m.durationSec != null ? { durationSec: m.durationSec } : {}),
  })
  const survey = snapshot.kind === 'SURVEY' || version.kind === 'SURVEY'
  const document: QuizFileDocument = {
    format: QUIZ_FILE_FORMAT,
    formatVersion: QUIZ_FILE_VERSION,
    exportedAt: new Date().toISOString(),
    quiz: { kind: survey ? 'SURVEY' : 'QUIZ', title: snapshot.title, intro: snapshot.intro,
      timeLimitSec: snapshot.timeLimitSec, cover: key(snapshot.cover, 'image'), language: version.language },
    materials: byNo(snapshot.materials).map((m) => material(m, true)),
    questions: byNo(snapshot.questions).map((q) => ({
      stem: q.stem, image: key(q.image, 'image'), points: q.points, selection: q.selection ?? null,
      options: byNo(q.options).map((o) => ({ text: o.text, image: key(o.image, 'image'), correct: o.correct })),
    })),
    files: {},
  }
  const languages = Object.entries(snapshot.translations ?? {})
  if (languages.length > 0) {
    document.translations = {}
    for (const [language, t] of languages) {
      if (!t) continue
      const texts: Record<string, string> = {}
      for (const [k, text] of Object.entries(t.texts ?? {})) if (text) texts[k] = text
      const images: Record<string, string> = {}
      for (const [k, image] of Object.entries(t.images ?? {})) {
        const fileKey = key(image, 'image')
        if (fileKey) images[k] = fileKey
      }
      document.translations[language as ContentLanguage] = { texts, images, hashes: {},
        materials: byNo(t.materials ?? []).map((m) => ({ no: m.no, ...material(m, false) })) }
    }
  }
  let done = 0
  onProgress?.(0, keys.size)
  for (const [fileId, { key: fileKey, kind }] of keys) {
    const blob = await read(fileId)
    document.files[fileKey] = { name: nameOf(kind, blob.type), type: blob.type, size: blob.size,
      data: await base64Of(blob) }
    onProgress?.(++done, keys.size)
  }
  return document
}

/**
 * Reads a quiz's draft with all its translations (read only now, when the export is asked for: F-S6c) and exports
 * it; null when the quiz is not the sponsor's.
 */
export async function exportQuiz(quizId: string, onProgress?: Progress): Promise<QuizFileDocument | null> {
  const draft = await readDraft(quizId, 'all')
  if (!draft) return null
  const [links, products] = await Promise.all([readMyQuizLinks(quizId), readMyProducts()])
  return exportDraft(draft, onProgress, undefined, { links, products })
}

/** A file name for the export: the title's letters and digits, then `.quizbuks.json`. */
export function exportFileName(title: string): string {
  const base = title.normalize('NFKC').replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `${base || 'quiz'}.quizbuks.json`
}

/** The export written once: the file to download, its name and size. */
export interface ExportFile {
  blob: Blob
  name: string
  size: number
}

export function exportFile(document: QuizFileDocument): ExportFile {
  const blob = new Blob([JSON.stringify(document)], { type: 'application/json' })
  return { blob, name: exportFileName(document.quiz.title), size: blob.size }
}

/** Saves the file through the browser's download. */
export function download(file: ExportFile) {
  const url = URL.createObjectURL(file.blob)
  const link = window.document.createElement('a')
  link.href = url
  link.download = file.name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

/** The document without its files' data: what the server checks and keeps (it never reads the data). */
export function withoutData(document: Record<string, unknown>): Record<string, unknown> {
  const files = document.files
  if (!files || typeof files !== 'object' || Array.isArray(files)) return document
  const stripped: Record<string, unknown> = {}
  for (const [key, entry] of Object.entries(files as Record<string, unknown>)) {
    if (entry && typeof entry === 'object' && !Array.isArray(entry)) {
      const rest = { ...(entry as Record<string, unknown>) }
      delete rest.data
      stripped[key] = rest
    } else {
      stripped[key] = entry
    }
  }
  return { ...document, files: stripped }
}

/** `QuizTransferProcesses.CheckOutput`. */
/** A file the import uploads, as the server's check names it. */
export interface ImportFile {
  key: string
  policy: string
  name: string
  type: string
  size: number
}

export interface ImportCheck {
  formatVersion: number
  currentVersion: number
  /** The versions the file is read through, its own first. */
  readers: number[]
  kind: string
  title: string
  questions: number
  materials: number
  files: ImportFile[]
  /** Q73a: the base language the file names (none in a version 1 file), and its other languages. */
  language: ContentLanguage | null
  languages: ContentLanguage[]
  /** Q100e: the links to the importer's own products the import makes, and the products the file names not found. */
  products?: number
  missingProducts?: string[]
}

/** A file read as JSON; throws `SyntaxError` for anything else. */
export async function readJsonFile(file: Blob): Promise<Record<string, unknown>> {
  const parsed: unknown = JSON.parse(await file.text())
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new SyntaxError('Not a JSON object')
  return parsed as Record<string, unknown>
}

/** What the server says of the file: its versions, what it makes and which files it needs; a refusal throws. */
export function checkImport(document: Record<string, unknown>): Promise<ImportCheck> {
  return runProcess<ImportCheck>(CONTENT_PROCESS.quizImportCheck, { document: withoutData(document) })
}

export interface Imported {
  quizId: string
  formatVersion: number
  readers: number[]
}

const KNOWN_POLICIES = new Set<string>(Object.values(CONTENT_FILE_POLICY))
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** How long an upload refused by the per-minute upload limit (429) waits before it is tried again. */
export const UPLOAD_RETRY_MS = 20_000

/** Uploads one file, waiting out the upload limit (`jabiz.files.upload-rate-per-minute`) a few times. */
async function uploadPaced(policy: string, blob: Blob, name: string, wait: (ms: number) => Promise<unknown>) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await uploadFile(policy, blob, name)
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 429 || attempt >= 5) throw e
      await wait(UPLOAD_RETRY_MS)
    }
  }
}

/**
 * Uploads the files the check named, each under its policy, from the file's data; their keys with the uploaded ids.
 * A file missing or spoiling one stops it before the first upload. Shared by the import of a new quiz and F-S6's
 * import of questions into one.
 */
export async function uploadImportFiles(document: Record<string, unknown>, check: Pick<ImportCheck, 'files'>,
  onProgress?: Progress, wait: (ms: number) => Promise<unknown> = sleep): Promise<Record<string, string>> {
  const files = (document.files ?? {}) as Record<string, Partial<QuizFileEntry>>
  const total = check.files.length
  const stepOf = (need: ImportFile, i: number): ImportStep =>
    ({ kind: 'file', index: i + 1, total, key: need.key, type: need.type, size: need.size })
  // Every file's data, decoded, before the first upload: a file missing or spoiling one would leave the others
  // uploaded for nothing.
  for (const need of check.files) {
    if (!KNOWN_POLICIES.has(need.policy) || typeof files[need.key]?.data !== 'string') {
      throw new MissingFileData(need.key)
    }
  }
  const blobs = check.files.map((need, i) => {
    try {
      return blobOf(files[need.key].data as string, need.type)
    } catch (e) {
      throw new ImportStepError(stepOf(need, i), new CorruptFileData(need.key, e))
    }
  })
  const uploaded: Record<string, string> = {}
  onProgress?.(0, total)
  for (const [i, need] of check.files.entries()) {
    const kind = need.policy === CONTENT_FILE_POLICY.pdf ? 'pdf' : need.policy === CONTENT_FILE_POLICY.audio
      ? 'audio' : 'image'
    try {
      const file = await uploadPaced(need.policy, blobs[i], nameOf(kind, need.type), wait)
      uploaded[need.key] = String(file.fileId)
    } catch (e) {
      throw new ImportStepError(stepOf(need, i), e)
    }
    onProgress?.(i + 1, total)
  }
  return uploaded
}

/**
 * Uploads the files the check named (each under its policy, from the file's data) and makes the draft. Files a
 * refused import leaves behind are swept by the platform as for any upload no quiz refers to.
 */
export async function importQuiz(document: Record<string, unknown>, check: ImportCheck, onProgress?: Progress,
  wait: (ms: number) => Promise<unknown> = sleep): Promise<Imported> {
  const uploaded = await uploadImportFiles(document, check, onProgress, wait)
  try {
    return await runProcess<Imported>(CONTENT_PROCESS.quizImport, { document: withoutData(document), files: uploaded })
  } catch (e) {
    throw new ImportStepError({ kind: 'draft' }, e)
  }
}

/** The step of an import that failed: uploading one of the file's files (counted from 1), or making the draft. */
export type ImportStep =
  | { kind: 'file'; index: number; total: number; key: string; type: string; size: number }
  | { kind: 'draft' }

/** An import that failed at one step; `cause` is why (an `ApiError`, a network error, {@link CorruptFileData}). */
export class ImportStepError extends Error {
  readonly step: ImportStep

  constructor(step: ImportStep, cause: unknown) {
    super(`The import failed at ${step.kind === 'file' ? `file ${step.key}` : 'the draft'}`, { cause })
    this.step = step
  }
}

/** A file whose data is not base64 (the quiz file was changed or cut short). */
export class CorruptFileData extends Error {
  readonly key: string

  constructor(key: string, cause: unknown) {
    super(`The quiz file's data of the file ${key} is not base64`, { cause })
    this.key = key
  }
}

/** The file names a file it does not carry the data of. */
export class MissingFileData extends Error {
  readonly key: string

  constructor(key: string) {
    super(`The quiz file has no data for the file ${key}`)
    this.key = key
  }
}
