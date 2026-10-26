// The pure parts of the content tool (docs/quizbuks/plans/Q36-content-pack.md): the content pack's format, a quiz
// file from a draft's rows (the sponsor console's export, content/transfer.ts), and what `apply` and `renew` would do.
// No network here, so that node:test covers it (lib.test.mjs).

export const PACK_FORMAT = 'quizbuks.content'
export const PACK_VERSION = 1
export const QUIZ_FILE_FORMAT = 'quizbuks.quiz'
export const QUIZ_FILE_VERSION = 1
/** The longest a publication may run (PublishingLimits.MAX_RUNNING). */
export const MAX_RUNNING_DAYS = 366
const DAY = 86_400_000

const EXTENSIONS = { 'image/jpeg': 'jpg', 'image/png': 'png', 'application/pdf': 'pdf', 'audio/mpeg': 'mp3',
  'audio/mp4': 'm4a', 'audio/ogg': 'ogg' }

/** The neutral name of a file of a field (`image.jpg`), never the sponsor's own (as the console's export). */
export function neutralName(kind, type) {
  const extension = EXTENSIONS[type]
  return extension ? `${kind}.${extension}` : kind
}

const num = (value) => {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}
const str = (value) => (value == null ? null : String(value))
const bool = (value) => value === true || value === 'true'
const bySeq = (a, b) => num(a.seq) - num(b.seq)

/**
 * The quiz file (format version 1) of a draft's rows, as the console's export writes it, with the files it uses
 * named `f1`, `f2` … in the order first used. Returns the document without the files' data, and the files to fetch.
 */
export function quizFileOf({ quiz, questions, options, materials, images }, exportedAt) {
  const keys = new Map()
  const key = (fileId, kind) => {
    if (!fileId) return null
    if (!keys.has(fileId)) keys.set(fileId, { key: `f${keys.size + 1}`, kind })
    return keys.get(fileId).key
  }
  const optionsOf = new Map()
  for (const o of [...options].sort(bySeq)) {
    const list = optionsOf.get(String(o.questionId)) ?? []
    list.push(o)
    optionsOf.set(String(o.questionId), list)
  }
  const imagesOf = new Map()
  for (const i of [...images].sort(bySeq)) {
    const list = imagesOf.get(String(i.materialId)) ?? []
    list.push(i)
    imagesOf.set(String(i.materialId), list)
  }
  const document = {
    format: QUIZ_FILE_FORMAT,
    formatVersion: QUIZ_FILE_VERSION,
    exportedAt,
    quiz: { kind: quiz.kind === 'SURVEY' ? 'SURVEY' : 'QUIZ', title: String(quiz.title ?? ''), intro: str(quiz.intro),
      timeLimitSec: quiz.timeLimitSec == null || quiz.timeLimitSec === '' ? null : num(quiz.timeLimitSec),
      cover: key(str(quiz.cover), 'image') },
    materials: [...materials].sort(bySeq).map((m) => ({
      kind: String(m.kind), title: str(m.title), description: str(m.description), body: str(m.body), url: str(m.url),
      pdf: key(str(m.pdf), 'pdf'), audio: key(str(m.audio), 'audio'),
      images: (imagesOf.get(String(m.materialId)) ?? []).map((i) => ({ image: key(String(i.image), 'image'),
        caption: str(i.caption) })),
      ...(bool(m.watchToAnswer) ? { watchToAnswer: true } : {}),
    })),
    questions: [...questions].sort(bySeq).map((q) => ({
      stem: str(q.stem), image: key(str(q.image), 'image'), points: num(q.points) || 1,
      selection: q.selection == null || q.selection === '' ? null : String(q.selection),
      options: (optionsOf.get(String(q.questionId)) ?? []).map((o) => ({ text: str(o.text), image: key(str(o.image), 'image'),
        correct: bool(o.correct) })),
    })),
    files: {},
  }
  const files = [...keys].map(([fileId, { key: fileKey, kind }]) => ({ fileId, key: fileKey, kind }))
  return { document, files }
}

/** A file name for a quiz file: the title's letters and digits, then `.quizbuks.json` (as the console's). */
export function quizFileName(title) {
  const base = String(title).normalize('NFKC').replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `${base || 'quiz'}.quizbuks.json`
}

/** The document without its files' data: what the server checks and keeps. */
export function withoutData(document) {
  const files = {}
  for (const [k, entry] of Object.entries(document.files ?? {})) {
    const { data: _data, ...rest } = entry ?? {}
    files[k] = rest
  }
  return { ...document, files }
}

/**
 * A publication as the pack keeps it: what the sponsor sets, never its state, counts, keys or secrets (a digital
 * prize's shared content is sealed and not readable). Its period is a length in days from the day it is applied,
 * unless the pack fixes the dates.
 */
export function publicationSpecOf(row, tiers, quizFile) {
  const start = row.startAt ? Date.parse(row.startAt) : NaN
  const end = row.endAt ? Date.parse(row.endAt) : NaN
  const days = Number.isFinite(start) && Number.isFinite(end) ? Math.max(1, Math.round((end - start) / DAY)) : 30
  const regions = Array.isArray(row.regions) ? row.regions : String(row.regions ?? '').split(',').filter(Boolean)
  const spec = {
    name: String(row.name),
    quiz: quizFile,
    durationDays: Math.min(days, MAX_RUNNING_DAYS),
    regions,
    audience: row.audience === 'INVITED' ? 'INVITED' : 'PUBLIC',
    timeLimitSec: row.timeLimitSec == null ? null : num(row.timeLimitSec),
    passScore: row.passScore == null ? null : num(row.passScore),
    budgetCap: row.budgetCap == null ? null : num(row.budgetCap),
    maxWinners: row.maxWinners == null ? null : num(row.maxWinners),
    rewardMethod: String(row.rewardMethod ?? 'PLATFORM'),
    redeemDescription: str(row.redeemDescription),
    tiers: [...tiers].sort(bySeq).map((t) => ({ fromScore: num(t.fromScore), toScore: num(t.toScore), reward: num(t.reward) })),
  }
  if (row.rewardMethod === 'DIGITAL_PRIZE') {
    spec.digitalDelivery = str(row.digitalDelivery)
    spec.digitalDescription = str(row.digitalDescription)
    spec.digitalSendDays = row.digitalSendDays == null ? null : num(row.digitalSendDays)
  }
  return spec
}

/** Checks a pack and gives its problems (none when it can be applied). */
export function packProblems(pack, quizFiles) {
  const problems = []
  if (pack?.format !== PACK_FORMAT) problems.push(`not a content pack (format ${JSON.stringify(pack?.format)})`)
  else if (pack.formatVersion !== PACK_VERSION) problems.push(`content pack version ${pack.formatVersion}, this tool reads ${PACK_VERSION}`)
  const names = new Set()
  for (const p of pack?.publications ?? []) {
    if (!p.name) problems.push('a publication without a name')
    if (names.has(p.name)) problems.push(`two publications named ${JSON.stringify(p.name)}`)
    names.add(p.name)
    if (!quizFiles.includes(p.quiz)) problems.push(`publication ${JSON.stringify(p.name)}: no quiz file ${JSON.stringify(p.quiz)}`)
    const days = p.durationDays ?? 0
    if (!p.endAt && !(days >= 1 && days <= MAX_RUNNING_DAYS)) {
      problems.push(`publication ${JSON.stringify(p.name)}: durationDays must be 1–${MAX_RUNNING_DAYS}`)
    }
    if (p.rewardMethod === 'DIGITAL_PRIZE' && p.digitalDelivery === 'SHARED' && !p.digitalContentEnv) {
      problems.push(`publication ${JSON.stringify(p.name)}: a shared digital prize names the environment variable of its content (digitalContentEnv)`)
    }
  }
  return problems
}

/** The publication save input of a pack's publication: the quiz's version, the period from now unless fixed. */
export function saveInputOf(spec, versionId, now, env = {}) {
  const startAt = spec.startAt ?? now.toISOString()
  const endAt = spec.endAt ?? new Date(Date.parse(startAt) + spec.durationDays * DAY).toISOString()
  const input = {
    publicationId: null, versionId, name: spec.name, regions: spec.regions ?? ['GLOBAL'], startAt, endAt,
    timeLimitSec: spec.timeLimitSec ?? null, passScore: spec.passScore ?? null, budgetCap: spec.budgetCap ?? null,
    maxWinners: spec.maxWinners ?? null, tiers: (spec.tiers ?? []).map((t) => ({ tierId: null, ...t })),
    baseRevision: null, rewardMethod: spec.rewardMethod ?? 'PLATFORM', redeemDescription: spec.redeemDescription ?? null,
    audience: spec.audience ?? 'PUBLIC',
  }
  if (spec.rewardMethod === 'DIGITAL_PRIZE') {
    input.digitalDelivery = spec.digitalDelivery ?? null
    input.digitalDescription = spec.digitalDescription ?? null
    input.digitalSendDays = spec.digitalSendDays ?? null
    if (spec.digitalContentEnv) input.digitalContent = env[spec.digitalContentEnv] ?? ''
  }
  return input
}

/** The statuses in which a publication still runs or will (a pack's publication of that name is not made again). */
export const RUNNING = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'SCHEDULED', 'LIVE', 'PAUSED', 'FUNDS_SHORT', 'REJECTED']

/**
 * What `apply` does with a pack's publication, given the sponsor's publications of that name: nothing when one
 * runs or will; a new one otherwise. `renew` also makes one when the running one ends within `renewDays`.
 */
export function publicationAction(existing, { now, renewDays = 0 } = {}) {
  const running = existing.filter((p) => RUNNING.includes(p.status))
  if (running.length === 0) return { action: 'create' }
  if (renewDays > 0) {
    const ends = Math.max(...running.map((p) => Date.parse(p.endAt ?? '') || 0))
    const later = running.some((p) => (p.status === 'SCHEDULED' || p.status === 'PENDING_REVIEW' || p.status === 'DRAFT')
      && Date.parse(p.startAt ?? '') > now.getTime())
    if (!later && ends - now.getTime() <= renewDays * DAY) return { action: 'renew', after: new Date(ends) }
  }
  return { action: 'skip', status: running[0].status }
}
