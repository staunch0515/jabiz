import { api, ApiError, runQuery, unwrap, type Filter } from '@jabiz/admin'
import { toPublicationRow, type Page, type PublicationRow } from './api'
import { DATASET, isUserId, PAGE_LIMIT, TEMPLATE } from './ids'
import type { MaterialKind, Snapshot } from './snapshot'
import type { SortSpec } from './api'

/*
 * What the overview pages read (Q17; docs/quizbuks/plans/Q17-admin-overview.md): every sponsor's quizzes
 * (`qb.admin.quizzes`), a quiz's versions (`qb.admin.quiz-versions`), its draft's parts from the content's default
 * datasets, and every publication in every state (`qb.admin.publications`). Read only, like the rest of the extension.
 */

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}
const bool = (value: unknown): boolean => value === true || value === 'true'
/** A key in the address, or null for anything that is none (the server would refuse it). */
const keyOf = (value: string | null): string | null => (value && isUserId(value) ? value : null)
const codeOf = (value: string | null): string | null => (value && /^[A-Z_]{1,20}$/.test(value) ? value : null)

/** A row of `qb.admin.quizzes`. */
export interface QuizRow {
  quizId: string
  ownerId: string
  title: string
  kind: string
  cover: string | null
  status: string
  removed: boolean
  aiGenerated: boolean
  versionLabel: string | null
  latestVersionNo: number | null
  changedSinceVersion: boolean
  questionCount: number
  materialCount: number
  timeLimitSec: number | null
  clonedFrom: string | null
  editedAt: string | null
  publicationCount: number
}

function toQuizRow(row: Row): QuizRow {
  return {
    quizId: String(row.quizId),
    ownerId: String(row.ownerId ?? ''),
    title: String(row.title ?? ''),
    kind: String(row.kind ?? ''),
    cover: str(row.cover),
    status: String(row.status ?? ''),
    removed: bool(row.removed),
    aiGenerated: bool(row.aiGenerated),
    versionLabel: str(row.versionLabel),
    latestVersionNo: num(row.latestVersionNo),
    changedSinceVersion: bool(row.changedSinceVersion),
    questionCount: num(row.questionCount) ?? 0,
    materialCount: num(row.materialCount) ?? 0,
    timeLimitSec: num(row.timeLimitSec),
    clonedFrom: str(row.clonedFrom),
    editedAt: str(row.editedAt),
    publicationCount: num(row.publicationCount) ?? 0,
  }
}

/** What the quiz list is narrowed to: a title's text, a sponsor, and whether removed quizzes are shown. */
export interface QuizFilter {
  q: string | null
  ownerId: string | null
  removed: boolean
}

export function quizFilterOf(search: URLSearchParams): QuizFilter {
  return { q: str(search.get('q')?.trim()), ownerId: keyOf(search.get('sponsor')), removed: search.get('removed') === '1' }
}

/** A page of quizzes, the last edited first, with their count. */
export async function readQuizzes(filter: QuizFilter, offset: number, limit: number, sorts?: SortSpec[], more?: Filter[]): Promise<Page<QuizRow>> {
  const filters: Filter[] = []
  if (filter.ownerId) filters.push({ field: 'ownerId', op: 'eq', value: filter.ownerId })
  if (!filter.removed) filters.push({ field: 'removed', op: 'eq', value: false })
  const answer = await runQuery<Row>(TEMPLATE.quizzes, { params: { q: filter.q }, filters: [...filters, ...(more ?? [])], sorts, offset, limit,
    count: true })
  return { items: answer.items.map(toQuizRow), total: answer.total }
}

/** One quiz's row; null when there is none. */
export async function readQuiz(quizId: string): Promise<QuizRow | null> {
  const answer = await runQuery<Row>(TEMPLATE.quizzes, {
    filters: [{ field: 'quizId', op: 'eq', value: quizId }], limit: 1 })
  return answer.items[0] ? toQuizRow(answer.items[0]) : null
}

/** A row of `qb.admin.quiz-versions`: a version without its content. */
export interface QuizVersionRow {
  versionId: string
  versionNo: number | null
  label: string
  title: string
  questionCount: number
  fullScore: number | null
  versionedAt: string | null
}

/** A page of a quiz's versions, the newest first. */
export async function readQuizVersions(quizId: string, offset: number, limit: number, sorts?: SortSpec[]): Promise<Page<QuizVersionRow>> {
  const answer = await runQuery<Row>(TEMPLATE.quizVersions, { params: { quizId }, sorts, offset, limit, count: true })
  return {
    total: answer.total,
    items: answer.items.map((row) => ({
      versionId: String(row.versionId),
      versionNo: num(row.versionNo),
      label: String(row.label ?? ''),
      title: String(row.title ?? ''),
      questionCount: num(row.questionCount) ?? 0,
      fullScore: num(row.fullScore),
      versionedAt: str(row.versionedAt),
    })),
  }
}

/** Every row of an administrators' dataset that matches, page by page (a quiz has up to 600 options). */
async function readAll(dataset: string, filters: Filter[]): Promise<Row[]> {
  const rows: Row[] = []
  for (let offset = 0; ; offset += PAGE_LIMIT) {
    const answer = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: dataset } },
      body: { offset, limit: PAGE_LIMIT, filters },
    }))
    const items = (answer.items ?? []).map((item) => (item.attributes ?? {}) as Row)
    rows.push(...items)
    if (items.length < PAGE_LIMIT) return rows
  }
}

/** The quiz's own row, by its key (the quiz dataset is not filtered by key); null when it has none left. */
async function readQuizEntity(quizId: string): Promise<Row | null> {
  try {
    const instance = await unwrap(api.GET('/api/datasets/{resourceId}/entities/{id}', {
      params: { path: { resourceId: DATASET.quiz, id: quizId } },
    }))
    return (instance.attributes ?? null) as Row | null
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null
    throw error
  }
}

const bySeq = (a: Row, b: Row) => (num(a.seq) ?? 0) - (num(b.seq) ?? 0)

/**
 * A quiz's draft as it is now, in the shape of a version's snapshot (so the version's preview shows it): read from
 * the content's default datasets, numbered from 1 in each part's order. A removed quiz has no parts left.
 */
export async function readDraft(quiz: QuizRow): Promise<Snapshot> {
  const ofQuiz: Filter[] = [{ field: 'quizId', op: 'eq', value: quiz.quizId }]
  const [own, materials, images, questions, options] = await Promise.all([
    readQuizEntity(quiz.quizId),
    readAll(DATASET.material, ofQuiz),
    readAll(DATASET.materialImage, ofQuiz),
    readAll(DATASET.question, ofQuiz),
    readAll(DATASET.option, ofQuiz),
  ])
  const draft = own ?? {}
  const group = (rows: Row[], key: string) => {
    const map = new Map<string, Row[]>()
    for (const row of [...rows].sort(bySeq)) {
      const of = String(row[key])
      map.set(of, [...(map.get(of) ?? []), row])
    }
    return map
  }
  const imagesOf = group(images, 'materialId')
  const optionsOf = group(options, 'questionId')
  return {
    // The draft's other languages (Q73a) are the sponsor's work in progress: the base is what the page shows.
    translations: {},
    kind: quiz.kind === 'SURVEY' ? 'SURVEY' : 'QUIZ',
    title: String(draft.title ?? quiz.title),
    intro: str(draft.intro),
    cover: str(draft.cover),
    timeLimitSec: num(draft.timeLimitSec),
    aiGenerated: quiz.aiGenerated,
    materials: [...materials].filter((m) => m.variantOf == null).sort(bySeq).map((m, i) => ({
      no: i + 1,
      kind: String(m.kind) as MaterialKind,
      title: str(m.title),
      description: str(m.description),
      body: str(m.body),
      url: str(m.url),
      pdf: str(m.pdf),
      audio: str(m.audio),
      images: (imagesOf.get(String(m.materialId)) ?? []).filter((image) => str(image.image))
        .map((image, n) => ({ no: n + 1, image: String(image.image), caption: str(image.caption) })),
    })),
    questions: [...questions].sort(bySeq).map((q, i) => ({
      no: i + 1,
      stem: str(q.stem),
      image: str(q.image),
      points: num(q.points) ?? 0,
      selection: q.selection === 'SINGLE' || q.selection === 'MULTIPLE' ? q.selection : null,
      options: (optionsOf.get(String(q.questionId)) ?? []).map((o, n) => ({
        no: n + 1,
        text: str(o.text),
        image: str(o.image),
        correct: bool(o.correct),
      })),
    })),
  }
}

/** A publications list row: the queue's row with its quiz and counts. */
export interface PublicationListRow extends PublicationRow {
  quizId: string | null
  starts: number
  completions: number
  winners: number
  editedAt: string | null
}

/** What the publication list is narrowed to (the address carries it: a quiz's and a user's pages link here). */
export interface PublicationFilter {
  q: string | null
  status: string | null
  /** A sponsor's user id (the address says `sponsor`): the publisher of the sponsors' console. */
  sponsorId: string | null
  quizId: string | null
}

export function publicationFilterOf(search: URLSearchParams): PublicationFilter {
  return { q: str(search.get('q')?.trim()), status: codeOf(search.get('status')),
    sponsorId: keyOf(search.get('sponsor')), quizId: keyOf(search.get('quiz')) }
}

/** A page of publications in every state, the last edited first, with their count. */
export async function readPublications(filter: PublicationFilter, offset: number,
  limit: number, sorts?: SortSpec[], more?: Filter[]): Promise<Page<PublicationListRow>> {
  const filters: Filter[] = []
  if (filter.status) filters.push({ field: 'status', op: 'eq', value: filter.status })
  if (filter.sponsorId) filters.push({ field: 'publisherId', op: 'eq', value: filter.sponsorId })
  if (filter.quizId) filters.push({ field: 'quizId', op: 'eq', value: filter.quizId })
  const answer = await runQuery<Row>(TEMPLATE.publications, { params: { q: filter.q }, filters: [...filters, ...(more ?? [])], sorts, offset, limit,
    count: true })
  return {
    total: answer.total,
    items: answer.items.map((row) => ({
      ...toPublicationRow(row),
      quizId: str(row.quizId),
      starts: num(row.starts) ?? 0,
      completions: num(row.completions) ?? 0,
      winners: num(row.winners) ?? 0,
      editedAt: str(row.editedAt),
    })),
  }
}
