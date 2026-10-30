import { api, ApiError, runProcess, runQuery, toApiError, unwrap, type Sort } from '@jabiz/client'
import { contentLanguageOf, readDictionary, type ContentLanguage, type DictionaryItem } from '../qb/api'
import {
  CONTENT_CODE,
  CONTENT_DATASET,
  CONTENT_DICTIONARY,
  CONTENT_PROCESS,
  CONTENT_TEMPLATE,
} from './ids'

/*
 * The content workshop's calls, typed by hand after the Java records of backend/quizbuks `content` (processes have no
 * OpenAPI types of their own). The end-to-end test `content.spec.ts` runs every one of them against the server.
 */

export type QuizStatus = 'DRAFT' | 'VERSIONED'
export type MaterialKind = 'ARTICLE' | 'LINK' | 'VIDEO_LINK' | 'PDF' | 'IMAGES' | 'AUDIO'
export const MATERIAL_KINDS: readonly MaterialKind[] = ['ARTICLE', 'LINK', 'VIDEO_LINK', 'PDF', 'IMAGES', 'AUDIO']

/** A scored quiz or a survey (Q12, `plans/Q12-survey.md`): chosen when the quiz is made, never changed. */
export type QuizKind = 'QUIZ' | 'SURVEY'

/** How a survey's question is answered: exactly one option, or at least one. */
export type Selection = 'SINGLE' | 'MULTIPLE'

/**
 * How a scored question is answered (D-Q5-2), as the taker is shown it: one correct option makes a single choice (radio
 * buttons), several a multiple choice (check boxes). Null while none is marked.
 */
export function choiceOf(correct: number): Selection | null {
  return correct === 0 ? null : correct === 1 ? 'SINGLE' : 'MULTIPLE'
}

const kindOf = (value: unknown): QuizKind => (value === 'SURVEY' ? 'SURVEY' : 'QUIZ')
const selectionOf = (value: unknown): Selection | null =>
  value === 'SINGLE' || value === 'MULTIPLE' ? value : null

/** A row of `qb.sponsor.quizzes` (M-20). */
export interface QuizRow {
  quizId: string
  title: string
  kind: QuizKind
  cover: string | null
  versionLabel: string | null
  latestVersionNo: number
  questionCount: number
  materialCount: number
  status: QuizStatus
  changedSinceVersion: boolean
  aiGenerated: boolean
  timeLimitSec: number | null
  editedAt: string
  publicationCount: number
  published: boolean
}

/** The quiz's own row (`QbQuiz`, the sponsor's dataset). */
export interface Quiz {
  quizId: string
  title: string
  kind: QuizKind
  /** The language the quiz is written in (Q59a). */
  language: ContentLanguage
  intro: string | null
  cover: string | null
  timeLimitSec: number | null
  status: QuizStatus
  latestVersionNo: number
  versionLabel: string | null
  /** The number of changes of the content; sent back as `baseRevision`. */
  revision: number
  editedAt: string
  changedSinceVersion: boolean
  questionCount: number
  materialCount: number
  clonedFrom: string | null
}

export interface Option {
  optionId: string
  text: string | null
  image: string | null
  correct: boolean
}

export interface Question {
  questionId: string
  stem: string | null
  image: string | null
  points: number
  options: Option[]
  /** A survey's question; null for a scored question (and a survey's one not set yet). */
  selection: Selection | null
}

export interface MaterialImage {
  imageId: string
  image: string
  caption: string | null
}

export interface Material {
  materialId: string
  kind: MaterialKind
  title: string | null
  description: string | null
  body: string | null
  url: string | null
  pdf: string | null
  audio: string | null
  images: MaterialImage[]
  /** Q19: a YouTube or Vimeo video watching which is enough to answer; the App shows it on the card. */
  watchToAnswer: boolean
}

/** A quiz's draft as the editor shows it: questions and materials in their order, each with its parts. */
export interface Draft {
  quiz: Quiz
  questions: Question[]
  materials: Material[]
}

/** `EditOutput`: the quiz after an edit. */
export interface EditOutput {
  quizId: string
  revision: number
  editedAt: string
  /** The question or material saved; null for the quiz itself. */
  itemId: string | null
  partIds: string[]
}

/** `QuizProcesses.QuizInput`: every field is written as given (empty clears it). */
export interface QuizInput {
  quizId?: string | null
  title: string
  intro: string | null
  cover: string | null
  timeLimitSec: number | null
  baseRevision?: number | null
  /** A new quiz's kind (a scored quiz when not given); a changed quiz's kind never changes. */
  kind?: QuizKind
  /** Q59a: the language; when not given a new quiz takes the console's language and a changed one keeps its own. */
  language?: ContentLanguage
}

/** `QuestionProcesses.QuestionInput`; `options` is the whole list in its order. */
export interface QuestionInput {
  quizId: string
  questionId?: string | null
  stem: string | null
  image: string | null
  points: number
  options: { optionId?: string | null; text: string | null; image: string | null; correct: boolean }[]
  baseRevision?: number | null
  /** A survey's question only: the server takes a point each and no correct option. */
  selection?: Selection | null
}

/** `MaterialProcesses.MaterialInput`: only the content field of the kind is given. */
export interface MaterialInput {
  quizId: string
  materialId?: string | null
  kind: MaterialKind
  title: string | null
  description: string | null
  body?: string | null
  url?: string | null
  pdf?: string | null
  audio?: string | null
  images?: { imageId?: string | null; image: string; caption: string | null }[] | null
  baseRevision?: number | null
  /** VIDEO_LINK only (Q19). */
  watchToAnswer?: boolean
}

/** `QuizProcesses.CloneOutput`. */
export interface CloneOutput {
  quizId: string
  clonedFrom: string
  versionNo: number | null
  revision: number
  editedAt: string
}

/** `QuizProcesses.VersionOutput`. */
export interface VersionOutput {
  quizId: string
  versionId: string
  versionNo: number
  label: string
  questionCount: number
  materialCount: number
  fullScore: number
  contentHash: string
  revision: number
}

/** A row of `qb.sponsor.quiz-versions`. */
export interface VersionRow {
  versionId: string
  versionNo: number
  label: string
  title: string
  kind: QuizKind
  questionCount: number
  fullScore: number
  versionedAt: string
  contentHash: string
}

/** A version's snapshot (`QuizSnapshots`, schema 1): parts by their number, from 1. */
export interface Snapshot {
  /** Only a survey's snapshot names its kind; a scored quiz's has none. */
  kind?: 'SURVEY'
  title: string
  intro: string | null
  cover: string | null
  timeLimitSec: number | null
  materials: {
    no: number
    kind: MaterialKind
    title: string | null
    description: string | null
    body: string | null
    url: string | null
    pdf: string | null
    audio: string | null
    images: { no: number; image: string; caption: string | null }[]
    /** Q19: present (true) only on the video watching which is enough to answer. */
    watchToAnswer?: boolean
  }[]
  questions: {
    no: number
    stem: string | null
    image: string | null
    points: number
    options: { no: number; text: string | null; image: string | null; correct: boolean }[]
    /** A survey's question only. */
    selection?: Selection
  }[]
}

/** One version with its whole content (`qb.sponsor.quiz-version`). */
export interface VersionDetail extends VersionRow {
  quizId: string
  timeLimitSec: number | null
  materialCount: number
  content: Snapshot
}

type Row = Record<string, unknown>

const num = (value: unknown): number => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const numOrNull = (value: unknown): number | null => (value == null || value === '' ? null : num(value))
const str = (value: unknown): string | null => (value == null ? null : String(value))
const bool = (value: unknown): boolean => value === true || value === 'true'

/** The most rows a dataset answers at once (the platform's page limit). */
const PAGE = 100

/** Every row of one of the sponsor's datasets that matches, page by page, in the dataset's order. */
async function readAll(dataset: string, quizId: string, sorts: Sort[]): Promise<Row[]> {
  const rows: Row[] = []
  for (let offset = 0; ; offset += PAGE) {
    const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: dataset } },
      body: { offset, limit: PAGE, filters: [{ field: 'quizId', op: 'eq', value: quizId }], sorts },
    }))
    const items = page.items ?? []
    for (const item of items) rows.push((item.attributes ?? {}) as Row)
    if (items.length < PAGE) return rows
  }
}

const BY_SEQ: Sort[] = [{ field: 'seq', asc: true }]

export function toQuiz(row: Row): Quiz {
  return {
    quizId: String(row.quizId),
    title: String(row.title ?? ''),
    kind: kindOf(row.kind),
    language: contentLanguageOf(str(row.language) ?? undefined),
    intro: str(row.intro),
    cover: str(row.cover),
    timeLimitSec: numOrNull(row.timeLimitSec),
    status: (row.status as QuizStatus) ?? 'DRAFT',
    latestVersionNo: num(row.latestVersionNo),
    versionLabel: str(row.versionLabel),
    revision: num(row.revision),
    editedAt: String(row.editedAt ?? ''),
    changedSinceVersion: bool(row.changedSinceVersion),
    questionCount: num(row.questionCount),
    materialCount: num(row.materialCount),
    clonedFrom: str(row.clonedFrom),
  }
}

/** Puts the rows of a quiz's parts together: each question with its options, each material with its images. */
export function assembleDraft(quiz: Row, questions: Row[], options: Row[], materials: Row[], images: Row[]): Draft {
  const bySeq = (a: Row, b: Row) => num(a.seq) - num(b.seq)
  const optionsOf = new Map<string, Option[]>()
  for (const o of [...options].sort(bySeq)) {
    const list = optionsOf.get(String(o.questionId)) ?? []
    list.push({ optionId: String(o.optionId), text: str(o.text), image: str(o.image), correct: bool(o.correct) })
    optionsOf.set(String(o.questionId), list)
  }
  const imagesOf = new Map<string, MaterialImage[]>()
  for (const i of [...images].sort(bySeq)) {
    const list = imagesOf.get(String(i.materialId)) ?? []
    list.push({ imageId: String(i.imageId), image: String(i.image), caption: str(i.caption) })
    imagesOf.set(String(i.materialId), list)
  }
  return {
    quiz: toQuiz(quiz),
    questions: [...questions].sort(bySeq).map((q) => ({
      questionId: String(q.questionId),
      stem: str(q.stem),
      image: str(q.image),
      points: num(q.points) || 1,
      options: optionsOf.get(String(q.questionId)) ?? [],
      selection: selectionOf(q.selection),
    })),
    materials: [...materials].sort(bySeq).map((m) => ({
      materialId: String(m.materialId),
      kind: m.kind as MaterialKind,
      title: str(m.title),
      description: str(m.description),
      body: str(m.body),
      url: str(m.url),
      pdf: str(m.pdf),
      audio: str(m.audio),
      images: imagesOf.get(String(m.materialId)) ?? [],
      watchToAnswer: m.watchToAnswer === true,
    })),
  }
}

/**
 * A quiz's draft, read from the sponsor's datasets; null when it is not theirs or removed (the datasets show only the
 * sponsor's own rows, and of quizzes only those not removed).
 */
export async function readDraft(quizId: string): Promise<Draft | null> {
  const [quiz, questions, options, materials, images] = await Promise.all([
    readQuizRow(quizId),
    readAll(CONTENT_DATASET.question, quizId, BY_SEQ),
    readAll(CONTENT_DATASET.option, quizId, BY_SEQ),
    readAll(CONTENT_DATASET.material, quizId, BY_SEQ),
    readAll(CONTENT_DATASET.materialImage, quizId, BY_SEQ),
  ])
  if (!quiz) return null
  return assembleDraft(quiz, questions, options, materials, images)
}

/** The quiz's own row by its key; null when the sponsor's dataset does not have it (not theirs, or removed). */
async function readQuizRow(quizId: string): Promise<Row | null> {
  const { data, error, response } = await api.GET('/api/datasets/{resourceId}/entities/{id}', {
    params: { path: { resourceId: CONTENT_DATASET.quiz, id: quizId } },
  })
  if (response.status === 404) return null
  if (error !== undefined || !data) throw await toApiError(response, error)
  return (data.attributes ?? {}) as Row
}

export interface QuizSearch {
  q?: string
  sorts?: Sort[]
  offset: number
  limit: number
}

/** The sponsor's quizzes (not removed), one page, counted. */
export async function searchQuizzes({ q, sorts, offset, limit }: QuizSearch) {
  const text = q?.trim()
  const page = await runQuery<Row>(CONTENT_TEMPLATE.quizzes, {
    params: text ? { q: text } : {},
    sorts,
    offset,
    limit,
    count: true,
  })
  return {
    total: page.total,
    items: page.items.map((row): QuizRow => ({
      quizId: String(row.quizId),
      title: String(row.title ?? ''),
      kind: kindOf(row.kind),
      cover: str(row.cover),
      versionLabel: str(row.versionLabel),
      latestVersionNo: num(row.latestVersionNo),
      questionCount: num(row.questionCount),
      materialCount: num(row.materialCount),
      status: (row.status as QuizStatus) ?? 'DRAFT',
      changedSinceVersion: bool(row.changedSinceVersion),
      aiGenerated: bool(row.aiGenerated),
      timeLimitSec: numOrNull(row.timeLimitSec),
      editedAt: String(row.editedAt ?? ''),
      publicationCount: num(row.publicationCount),
      published: bool(row.published),
    })),
  }
}

function toVersionRow(row: Row): VersionRow {
  return {
    versionId: String(row.versionId),
    versionNo: num(row.versionNo),
    label: String(row.label ?? ''),
    title: String(row.title ?? ''),
    kind: kindOf(row.kind),
    questionCount: num(row.questionCount),
    fullScore: num(row.fullScore),
    versionedAt: String(row.versionedAt ?? ''),
    contentHash: String(row.contentHash ?? ''),
  }
}

/** The versions of one of the sponsor's quizzes, newest first. */
export async function readVersions(quizId: string): Promise<VersionRow[]> {
  const versions: VersionRow[] = []
  for (let offset = 0; ; offset += PAGE) {
    const page = await runQuery<Row>(CONTENT_TEMPLATE.quizVersions, { params: { quizId }, offset, limit: PAGE })
    versions.push(...page.items.map(toVersionRow))
    if (page.items.length < PAGE) return versions
  }
}

/** One version with its content; null when it is not of one of the sponsor's quizzes. */
export async function readVersion(versionId: string): Promise<VersionDetail | null> {
  const page = await runQuery<Row>(CONTENT_TEMPLATE.quizVersion, { params: { versionId }, limit: 1 })
  const row = page.items[0]
  if (!row) return null
  const content = JSON.parse(String(row.content)) as Snapshot
  return {
    ...toVersionRow(row),
    // The version's own content says whether it is a survey.
    kind: kindOf(content.kind),
    quizId: String(row.quizId),
    timeLimitSec: numOrNull(row.timeLimitSec),
    materialCount: num(row.materialCount),
    content,
  }
}

/** The labels of quiz states and material kinds, in the request's language. */
export const readQuizStatuses = (): Promise<DictionaryItem[]> => readDictionary(CONTENT_DICTIONARY.quizStatus)
export const readMaterialKinds = (): Promise<DictionaryItem[]> => readDictionary(CONTENT_DICTIONARY.materialKind)

export const saveQuiz = (input: QuizInput) => runProcess<EditOutput>(CONTENT_PROCESS.quizSave, { ...input })

export const deleteQuiz = (quizId: string, baseRevision: number) =>
  runProcess<EditOutput>(CONTENT_PROCESS.quizDelete, { quizId, baseRevision })

/** A new draft with the content of the quiz's draft, or of one of its versions (`versionNo`). */
export const cloneQuiz = (quizId: string, versionNo?: number | null, title?: string | null) =>
  runProcess<CloneOutput>(CONTENT_PROCESS.quizClone, { quizId, versionNo: versionNo ?? null, title: title || null })

/** "Save version": checks that the draft is complete and writes it once as the next version. */
export const publishVersion = (quizId: string, baseRevision: number) =>
  runProcess<VersionOutput>(CONTENT_PROCESS.quizPublishVersion, { quizId, baseRevision })

export const saveQuestion = (input: QuestionInput) =>
  runProcess<EditOutput>(CONTENT_PROCESS.questionSave, { ...input })

export const deleteQuestion = (quizId: string, questionId: string, baseRevision: number) =>
  runProcess<EditOutput>(CONTENT_PROCESS.questionDelete, { quizId, questionId, baseRevision })

export const reorderQuestions = (quizId: string, questionIds: string[], baseRevision: number) =>
  runProcess<EditOutput>(CONTENT_PROCESS.questionReorder, { quizId, questionIds, baseRevision })

export const saveMaterial = (input: MaterialInput) =>
  runProcess<EditOutput>(CONTENT_PROCESS.materialSave, { ...input })

export const deleteMaterial = (quizId: string, materialId: string, baseRevision: number) =>
  runProcess<EditOutput>(CONTENT_PROCESS.materialDelete, { quizId, materialId, baseRevision })

export const reorderMaterials = (quizId: string, materialIds: string[], baseRevision: number) =>
  runProcess<EditOutput>(CONTENT_PROCESS.materialReorder, { quizId, materialIds, baseRevision })

/**
 * Whether a refusal means the page is out of date: the quiz changed since it was read (`QB_QUIZ_CHANGED`), or an
 * order no longer matches its items (`QB_ORDER_MISMATCH`). The editor then offers to reload.
 */
export function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.violations.some((v) =>
    v.ruleCode === CONTENT_CODE.quizChanged || v.ruleCode === CONTENT_CODE.orderMismatch)
}

/** Moves the item at `from` one place up (-1) or down (+1); the list unchanged when it cannot move. */
export function moved<T>(items: readonly T[], from: number, by: -1 | 1): T[] {
  const to = from + by
  if (from < 0 || from >= items.length || to < 0 || to >= items.length) return [...items]
  const next = [...items]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

/** The content field a material of this kind fills (`MaterialKind.contentField`). */
export function contentFieldOf(kind: MaterialKind): 'body' | 'url' | 'pdf' | 'audio' | 'images' {
  switch (kind) {
    case 'ARTICLE':
      return 'body'
    case 'LINK':
    case 'VIDEO_LINK':
      return 'url'
    case 'PDF':
      return 'pdf'
    case 'AUDIO':
      return 'audio'
    case 'IMAGES':
      return 'images'
  }
}

/**
 * The name a content file is uploaded under: its kind and the extension of the sponsor's file (`image.png`), never
 * the sponsor's own name. Takers can read a content file's details, so a name like "answers-final.pdf" or a person's
 * name would show (Q10b, a stop-gap until the platform hides such details from all but the uploader).
 */
export function neutralName(kind: 'image' | 'pdf' | 'audio' | 'avatar', fileName: string): string {
  const extension = /\.([A-Za-z0-9]{1,8})$/.exec(fileName)?.[1]
  return extension ? `${kind}.${extension.toLowerCase()}` : kind
}

/** Whether a link may be opened: the server accepts http(s) only; anything else is never made a link. */
export function safeHttpUrl(url: string | null | undefined): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}
