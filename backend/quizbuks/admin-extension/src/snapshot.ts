/**
 * A quiz version's frozen content, as `content.QuizSnapshots` writes it (schema 1, canonical JSON): the same shape as
 * the sponsor console's `Snapshot` (quizbuks-web/sponsor/src/content/api.ts), copied here because the extension
 * reaches nothing outside `@jabiz/admin`. Parts are numbered from 1 in their order.
 */
export type MaterialKind = 'ARTICLE' | 'LINK' | 'VIDEO_LINK' | 'PDF' | 'IMAGES' | 'AUDIO'

export interface SnapshotOption {
  no: number
  text: string | null
  image: string | null
  correct: boolean
}

export interface SnapshotQuestion {
  no: number
  stem: string | null
  image: string | null
  points: number
  options: SnapshotOption[]
}

export interface SnapshotMaterial {
  no: number
  kind: MaterialKind
  title: string | null
  description: string | null
  body: string | null
  url: string | null
  pdf: string | null
  audio: string | null
  images: { no: number; image: string; caption: string | null }[]
}

export interface Snapshot {
  title: string
  intro: string | null
  cover: string | null
  timeLimitSec: number | null
  aiGenerated: boolean
  materials: SnapshotMaterial[]
  questions: SnapshotQuestion[]
}

/** The schema this page understands; another one is shown as unreadable rather than half. */
export const SNAPSHOT_SCHEMA = 1

type Json = Record<string, unknown>

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null)
const whole = (value: unknown): number => (typeof value === 'number' && Number.isInteger(value) ? value : 0)
const list = (value: unknown): Json[] =>
  Array.isArray(value) ? value.filter((v): v is Json => typeof v === 'object' && v !== null) : []

/** Reads a version's `content`; throws when it is not a snapshot of {@link SNAPSHOT_SCHEMA}. */
export function parseSnapshot(content: unknown): Snapshot {
  const raw: unknown = typeof content === 'string' ? JSON.parse(content) : content
  if (typeof raw !== 'object' || raw === null || (raw as Json).schema !== SNAPSHOT_SCHEMA) {
    throw new Error(`Not a quiz snapshot of schema ${SNAPSHOT_SCHEMA}`)
  }
  const json = raw as Json
  return {
    title: text(json.title) ?? '',
    intro: text(json.intro),
    cover: text(json.cover),
    timeLimitSec: typeof json.timeLimitSec === 'number' ? json.timeLimitSec : null,
    aiGenerated: json.aiGenerated === true,
    materials: list(json.materials).map((m) => ({
      no: whole(m.no),
      kind: String(m.kind) as MaterialKind,
      title: text(m.title),
      description: text(m.description),
      body: text(m.body),
      url: text(m.url),
      pdf: text(m.pdf),
      audio: text(m.audio),
      images: list(m.images)
        .filter((i) => typeof i.image === 'string')
        .map((i) => ({ no: whole(i.no), image: String(i.image), caption: text(i.caption) })),
    })),
    questions: list(json.questions).map((q) => ({
      no: whole(q.no),
      stem: text(q.stem),
      image: text(q.image),
      points: whole(q.points),
      options: list(q.options).map((o) => ({
        no: whole(o.no),
        text: text(o.text),
        image: text(o.image),
        correct: o.correct === true,
      })),
    })),
  }
}

/** A link a reviewer may open: only http(s), never `javascript:` or data links (the server allows no others). */
export function safeHttpUrl(url: string | null | undefined): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}
