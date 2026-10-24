import { ApiError, fetchFileContent, runProcess, uploadFile } from '@jabiz/client'
import { readDraft, type Draft } from './api'
import { CONTENT_FILE_POLICY, CONTENT_PROCESS } from './ids'

/**
 * Quiz files (docs/quizbuks/plans/Q16-quiz-transfer.md): a quiz's draft and every file it uses in one JSON file, and
 * back. The export is the console's own (it reads the draft and the files as the editor does); the import asks the
 * server which version of the format reads the file and what to upload (`QB_QUIZ_IMPORT_CHECK`), uploads the files
 * through the content's policies, then makes the draft in one transaction (`QB_QUIZ_IMPORT`).
 */

export const QUIZ_FILE_FORMAT = 'quizbuks.quiz'
/** The version this console writes (`QuizFileFormat.CURRENT`); the server says which versions it reads. */
export const QUIZ_FILE_VERSION = 1
/** Above this size the export asks before going on (base64 adds about a third to the files). */
export const LARGE_EXPORT_BYTES = 100 * 1024 * 1024

export interface QuizFileEntry {
  name: string
  type: string
  size: number
  /** The file's bytes, base64. */
  data: string
}

/** The file's JSON (version 1). */
export interface QuizFileDocument {
  format: typeof QUIZ_FILE_FORMAT
  formatVersion: number
  exportedAt: string
  quiz: { kind: string; title: string; intro: string | null; timeLimitSec: number | null; cover: string | null }
  materials: {
    kind: string; title: string | null; description: string | null; body: string | null; url: string | null
    pdf: string | null; audio: string | null; images: { image: string; caption: string | null }[]
    /** Q19, written only when true (absent is false). */
    watchToAnswer?: true
  }[]
  questions: {
    stem: string | null; image: string | null; points: number; selection: string | null
    options: { text: string | null; image: string | null; correct: boolean }[]
  }[]
  files: Record<string, QuizFileEntry>
}

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
  read: (fileId: string) => Promise<Blob> = (id) => fetchFileContent(id)): Promise<QuizFileDocument> {
  const keys = new Map<string, { key: string; kind: 'image' | 'pdf' | 'audio' }>()
  const key = (fileId: string | null, kind: 'image' | 'pdf' | 'audio'): string | null => {
    if (!fileId) return null
    if (!keys.has(fileId)) keys.set(fileId, { key: `f${keys.size + 1}`, kind })
    return keys.get(fileId)!.key
  }
  const { quiz } = draft
  const document: QuizFileDocument = {
    format: QUIZ_FILE_FORMAT,
    formatVersion: QUIZ_FILE_VERSION,
    exportedAt: new Date().toISOString(),
    quiz: { kind: quiz.kind, title: quiz.title, intro: quiz.intro, timeLimitSec: quiz.timeLimitSec,
      cover: key(quiz.cover, 'image') },
    materials: draft.materials.map((m) => ({
      kind: m.kind, title: m.title, description: m.description, body: m.body, url: m.url,
      pdf: key(m.pdf, 'pdf'), audio: key(m.audio, 'audio'),
      images: m.images.map((i) => ({ image: key(i.image, 'image')!, caption: i.caption })),
      ...(m.watchToAnswer ? { watchToAnswer: true as const } : {}),
    })),
    questions: draft.questions.map((q) => ({
      stem: q.stem, image: key(q.image, 'image'), points: q.points, selection: q.selection,
      options: q.options.map((o) => ({ text: o.text, image: key(o.image, 'image'), correct: o.correct })),
    })),
    files: {},
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

/** Reads a quiz's draft and exports it; null when the quiz is not the sponsor's. */
export async function exportQuiz(quizId: string, onProgress?: Progress): Promise<QuizFileDocument | null> {
  const draft = await readDraft(quizId)
  return draft ? exportDraft(draft, onProgress) : null
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
export interface ImportCheck {
  formatVersion: number
  currentVersion: number
  /** The versions the file is read through, its own first. */
  readers: number[]
  kind: string
  title: string
  questions: number
  materials: number
  files: { key: string; policy: string; name: string; type: string; size: number }[]
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
 * Uploads the files the check named (each under its policy, from the file's data) and makes the draft. Files a
 * refused import leaves behind are swept by the platform as for any upload no quiz refers to.
 */
export async function importQuiz(document: Record<string, unknown>, check: ImportCheck, onProgress?: Progress,
  wait: (ms: number) => Promise<unknown> = sleep): Promise<Imported> {
  const files = (document.files ?? {}) as Record<string, Partial<QuizFileEntry>>
  // Every file's data before the first upload: a file missing one would leave the others uploaded for nothing.
  for (const need of check.files) {
    if (!KNOWN_POLICIES.has(need.policy) || typeof files[need.key]?.data !== 'string') {
      throw new MissingFileData(need.key)
    }
  }
  const uploaded: Record<string, string> = {}
  let done = 0
  onProgress?.(0, check.files.length)
  for (const need of check.files) {
    const entry = files[need.key]
    const kind = need.policy === CONTENT_FILE_POLICY.pdf ? 'pdf' : need.policy === CONTENT_FILE_POLICY.audio
      ? 'audio' : 'image'
    const blob = blobOf(entry.data as string, need.type)
    const file = await uploadPaced(need.policy, blob, nameOf(kind, need.type), wait)
    uploaded[need.key] = String(file.fileId)
    onProgress?.(++done, check.files.length)
  }
  return runProcess<Imported>(CONTENT_PROCESS.quizImport, { document: withoutData(document), files: uploaded })
}

/** The file names a file it does not carry the data of. */
export class MissingFileData extends Error {
  readonly key: string

  constructor(key: string) {
    super(`The quiz file has no data for the file ${key}`)
    this.key = key
  }
}
