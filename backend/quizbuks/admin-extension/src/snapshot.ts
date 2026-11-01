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
  /** A survey's question (Q12): one option or several; null for a scored question. */
  selection: 'SINGLE' | 'MULTIPLE' | null
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

/**
 * Q73a: the quiz in one of its other languages: the text by its key in the snapshot (`title`, `intro`, `q3.stem`,
 * `q3.o2.text`), its own images (`q3.image`, `q3.o2.image`) and materials (numbered as the base material each stands
 * for). Whatever it lacks is the base's.
 */
export interface SnapshotLanguage {
  texts: Record<string, string>
  images: Record<string, string>
  materials: SnapshotMaterial[]
}

export interface Snapshot {
  /** Q73a: the version's other languages, by code (none for a version of its base language only). */
  translations: Record<string, SnapshotLanguage>
  /** A survey (Q12) has no correct options and one reward for a complete response; only its snapshot names its kind. */
  kind: 'QUIZ' | 'SURVEY'
  title: string
  intro: string | null
  cover: string | null
  timeLimitSec: number | null
  aiGenerated: boolean
  materials: SnapshotMaterial[]
  questions: SnapshotQuestion[]
}

/** The schemas this page understands (2 has translations, Q73a); another one is shown as unreadable rather than half. */
export const SNAPSHOT_SCHEMAS: readonly number[] = [1, 2]

type Json = Record<string, unknown>

const text = (value: unknown): string | null => (typeof value === 'string' ? value : null)
const whole = (value: unknown): number => (typeof value === 'number' && Number.isInteger(value) ? value : 0)
const list = (value: unknown): Json[] =>
  Array.isArray(value) ? value.filter((v): v is Json => typeof v === 'object' && v !== null) : []

const materialsOf = (value: unknown): SnapshotMaterial[] => list(value).map((m) => ({
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
}))

const strings = (value: unknown): Record<string, string> =>
  typeof value === 'object' && value !== null
    ? Object.fromEntries(Object.entries(value).filter((e): e is [string, string] => typeof e[1] === 'string'))
    : {}

/** Reads a version's `content`; throws when it is not a snapshot of one of {@link SNAPSHOT_SCHEMAS}. */
export function parseSnapshot(content: unknown): Snapshot {
  const raw: unknown = typeof content === 'string' ? JSON.parse(content) : content
  if (typeof raw !== 'object' || raw === null || !SNAPSHOT_SCHEMAS.includes((raw as Json).schema as number)) {
    throw new Error(`Not a quiz snapshot of schema ${SNAPSHOT_SCHEMAS.join(' or ')}`)
  }
  const json = raw as Json
  const translations: Record<string, SnapshotLanguage> = {}
  const given = json.translations
  if (typeof given === 'object' && given !== null) {
    for (const [language, value] of Object.entries(given as Json)) {
      const l = (typeof value === 'object' && value !== null ? value : {}) as Json
      translations[language] = { texts: strings(l.texts), images: strings(l.images), materials: materialsOf(l.materials) }
    }
  }
  return {
    translations,
    kind: json.kind === 'SURVEY' ? 'SURVEY' : 'QUIZ',
    title: text(json.title) ?? '',
    intro: text(json.intro),
    cover: text(json.cover),
    timeLimitSec: typeof json.timeLimitSec === 'number' ? json.timeLimitSec : null,
    aiGenerated: json.aiGenerated === true,
    materials: materialsOf(json.materials),
    questions: list(json.questions).map((q) => ({
      no: whole(q.no),
      stem: text(q.stem),
      image: text(q.image),
      points: whole(q.points),
      selection: q.selection === 'SINGLE' || q.selection === 'MULTIPLE' ? q.selection : null,
      options: list(q.options).map((o) => ({
        no: whole(o.no),
        text: text(o.text),
        image: text(o.image),
        correct: o.correct === true,
      })),
    })),
  }
}

/**
 * The version in one of its languages (Q73a, as `QuizSnapshots.in`): the same structure, correct options and points,
 * with that language's texts, its own images and materials where it has them; the base's elsewhere (and for a
 * language it does not have).
 */
export function snapshotIn(snapshot: Snapshot, language: string): Snapshot {
  const t = snapshot.translations[language]
  if (!t) return snapshot
  return {
    ...snapshot,
    translations: {},
    title: t.texts.title ?? snapshot.title,
    intro: t.texts.intro ?? snapshot.intro,
    materials: snapshot.materials.map((m) => t.materials.find((o) => o.no === m.no) ?? m),
    questions: snapshot.questions.map((q) => ({
      ...q,
      stem: t.texts[`q${q.no}.stem`] ?? q.stem,
      image: t.images[`q${q.no}.image`] ?? q.image,
      options: q.options.map((o) => ({ ...o, text: t.texts[`q${q.no}.o${o.no}.text`] ?? o.text,
        image: t.images[`q${q.no}.o${o.no}.image`] ?? o.image })),
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
