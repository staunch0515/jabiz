import { api, runProcess, runQuery, unwrap } from '@jabiz/client'
import { readDraft, readVersion, readVersions, searchQuizzes, type VersionDetail } from '../content/api'
import { bytesOf, planBatches, splitQuestions } from '../content/bank'
import {
  exportQuiz,
  exportVersion,
  uploadImportFiles,
  withoutData,
  type ImportCheck,
  type QuizFileDocument,
} from '../content/transfer'
import { readMySponsorPage } from '../page/api'
import { readMyProducts } from '../products/api'
import {
  inputOf,
  readConditions,
  readMailServer,
  readPublication,
  searchPublications,
  type PublicationStatus,
  type TopicChanges,
} from '../publishing/api'
import { PUBLISHING_PROCESS } from '../publishing/ids'
import { endAfter, formOfSettings, nextHour, parseSettings, settingsOf, type PublicationSettings }
  from '../publishing/settings'
import { readMySponsor, type ContentLanguage } from '../qb/api'
import { downloadLink, exportProfile, uploadProfileImages, type ImportPlan, type ProfilePackageDocument } from './transfer'

/*
 * The sponsor package (F-C4, docs/quizbuks/plans/impl/F-C4.md; Q103): one Sponsor's profile, quizzes (with the
 * versions their publications use), publications not ended and knowledge maps in one JSON file, and back. Only content
 * and settings travel, never a state, a record, money or a secret. The export is the page's own (it reads what the
 * pages read); the import plans its steps here and runs each through the server's import record
 * (`QB_PACKAGE_IMPORT_START` / `_STEP`), which runs the pages' own processes in the same transaction as its record: a
 * step done is never done again, so an import stopped half-way goes on where it stopped.
 */

export const PACKAGE_FORMAT = 'quizbuks.sponsor-package'
/**
 * The version written. Version 2 adds to version 1 (Q103 §2) the knowledge maps, the publications' nodes, the topics
 * of quiz versions and publications, and the publications' shared videos. The quiz shuffle switch (F-S7) is not in the
 * platform yet: when it is, it travels in the quiz files (plan §8 item 1).
 */
export const PACKAGE_VERSION = 2
/** The versions read. */
export const PACKAGE_VERSIONS = [1, 2] as const

/** The publications a package carries: those not ended. */
export const UNENDED: readonly PublicationStatus[] = ['DRAFT', 'PENDING_REVIEW', 'SCHEDULED', 'LIVE', 'PAUSED',
  'FUNDS_SHORT']

export const PACKAGE_PROCESS = {
  start: 'QB_PACKAGE_IMPORT_START',
  step: 'QB_PACKAGE_IMPORT_STEP',
  abandon: 'QB_PACKAGE_IMPORT_ABANDON',
  treeSubmit: 'QB_TREE_SUBMIT',
} as const

export const PACKAGE_TEMPLATE = {
  state: 'qb.sponsor.package-state',
  trees: 'qb.sponsor.package-trees',
  treeNodes: 'qb.sponsor.package-tree-nodes',
} as const

/** The Sponsor's own import records (read only). */
export const PACKAGE_DATASET = 'urn:jabiz:dataset:me:QbPackageImport'

export type Texts = Partial<Record<ContentLanguage, string>>

export interface PackageNode {
  /** The node's id where it was exported: the key the package's conditions and publications name it by. */
  key: string
  name: Texts
  description: Texts | null
  /** The node's condition (`TreeCondition`), naming other nodes by their keys. */
  condition: Record<string, unknown>
}

export interface PackageTree {
  key: string
  name: Texts
  description: Texts | null
  nodes: PackageNode[]
}

export interface PackageQuizVersion {
  key: string
  label: string
  file: Record<string, unknown>
  topics?: string[]
  customTopics?: string[]
}

export interface PackageQuiz {
  key: string
  /** The versions the publications use, oldest first. */
  versions: PackageQuizVersion[]
  /** The current content; null when it is the last version's. */
  current: Record<string, unknown> | null
  topics?: string[]
  customTopics?: string[]
}

export interface PackagePublication {
  quiz: string | null
  version: string | null
  settings: PublicationSettings
  node?: { tree: string; node: string; name: Texts | null } | null
  topics?: TopicChanges | null
  sharedVideo?: string | null
}

export interface SponsorPackageDocument {
  format: typeof PACKAGE_FORMAT
  version: number
  exportedAt: string
  profile: ProfilePackageDocument | null
  trees: PackageTree[]
  quizzes: PackageQuiz[]
  publications: PackagePublication[]
}

// ---- writing ---------------------------------------------------------------------------------------------------

/** One quiz as read for the export: its current content and its versions with what they are. */
export interface QuizSource {
  quizId: string
  /** The draft has changes since its latest version. */
  changedSinceVersion: boolean
  latestVersionNo: number
  topics: string[]
  customTopics: string[]
  /** The draft as a quiz file; read only when needed. */
  current: () => Promise<QuizFileDocument>
  versions: { versionId: string; versionNo: number; label: string; topics: string[]; customTopics: string[];
    file: () => Promise<QuizFileDocument> }[]
}

export interface PublicationSource {
  publicationId: string
  quizId: string | null
  versionId: string | null
  settings: PublicationSettings
  treeId: string | null
  treeNodeId: string | null
  topics: TopicChanges | null
  sharedVideoId: string | null
}

export interface PackageSources {
  profile: ProfilePackageDocument | null
  trees: PackageTree[]
  quizzes: QuizSource[]
  /** The publications not ended (others are left out here too). */
  publications: PublicationSource[]
}

const isObject = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)

const hasTopics = (changes: TopicChanges | null | undefined): changes is TopicChanges =>
  !!changes && (changes.add.length + changes.remove.length + changes.customAdd.length + changes.customRemove.length) > 0

/**
 * The package of what was read: every quiz's current content and the versions the publications not ended use (oldest
 * first), the current content left out when it is the last version's; each publication naming its quiz's and
 * version's keys, its node by the map's and node's keys (with the node's name, to find it by in another account), its
 * topics and its shared video. Nothing of a state goes in.
 */
export async function buildPackage(sources: PackageSources, now: Date = new Date()): Promise<SponsorPackageDocument> {
  const publications = sources.publications.filter((p) => p.settings)
  const quizKeys = new Map<string, string>()
  const versionKeys = new Map<string, string>()
  const quizzes: PackageQuiz[] = []
  for (const [i, quiz] of sources.quizzes.entries()) {
    const key = `q${i + 1}`
    quizKeys.set(quiz.quizId, key)
    const used = new Set(publications.filter((p) => p.quizId === quiz.quizId && p.versionId).map((p) => p.versionId))
    const versions: PackageQuizVersion[] = []
    for (const version of [...quiz.versions].sort((a, b) => a.versionNo - b.versionNo)) {
      if (!used.has(version.versionId)) continue
      const versionKey = `${key}v${version.versionNo}`
      versionKeys.set(version.versionId, versionKey)
      versions.push({ key: versionKey, label: version.label, file: { ...(await version.file()) },
        topics: version.topics, customTopics: version.customTopics })
    }
    // The current content is the last version's when nothing changed since the latest version and that is the last
    // one carried: it is not replaced again.
    const last = [...quiz.versions].filter((v) => used.has(v.versionId)).sort((a, b) => a.versionNo - b.versionNo).at(-1)
    const same = !quiz.changedSinceVersion && last !== undefined && last.versionNo === quiz.latestVersionNo
    quizzes.push({ key, versions, current: same ? null : { ...(await quiz.current()) }, topics: quiz.topics,
      customTopics: quiz.customTopics })
  }
  const nodeNames = new Map<string, Texts>()
  for (const tree of sources.trees) for (const node of tree.nodes) nodeNames.set(`${tree.key} ${node.key}`, node.name)
  return {
    format: PACKAGE_FORMAT,
    version: PACKAGE_VERSION,
    exportedAt: now.toISOString(),
    profile: sources.profile,
    trees: sources.trees,
    quizzes,
    publications: publications.map((p): PackagePublication => ({
      quiz: p.quizId ? quizKeys.get(p.quizId) ?? null : null,
      version: p.versionId ? versionKeys.get(p.versionId) ?? null : null,
      settings: p.settings,
      node: p.treeId && p.treeNodeId ? { tree: p.treeId, node: p.treeNodeId,
        name: nodeNames.get(`${p.treeId} ${p.treeNodeId}`) ?? null } : null,
      topics: hasTopics(p.topics) ? p.topics : null,
      sharedVideo: p.sharedVideoId ?? null,
    })),
  }
}

type Row = Record<string, unknown>

/** A JSON object column (texts by language); null when absent or unreadable. */
function textsOf(value: unknown): Texts | null {
  if (isObject(value)) return value as Texts
  if (typeof value !== 'string' || value.trim() === '') return null
  try {
    const parsed: unknown = JSON.parse(value)
    return isObject(parsed) ? (parsed as Texts) : null
  } catch {
    return null
  }
}

function conditionOf(value: unknown): Record<string, unknown> {
  if (isObject(value)) return value
  try {
    const parsed: unknown = JSON.parse(String(value ?? ''))
    return isObject(parsed) ? parsed : { op: 'ALL', items: [] }
  } catch {
    return { op: 'ALL', items: [] }
  }
}

/** A row of `qb.sponsor.package-trees`. */
export interface OwnTree {
  treeId: string
  currentVersionId: string | null
  versionId: string | null
  status: string | null
  name: Texts | null
  description: Texts | null
  submitted: boolean
}

export async function readOwnTrees(): Promise<OwnTree[]> {
  const page = await runQuery<Row>(PACKAGE_TEMPLATE.trees, { limit: 100 })
  return page.items.map((row) => ({
    treeId: String(row.treeId),
    currentVersionId: row.currentVersionId == null ? null : String(row.currentVersionId),
    versionId: row.versionId == null ? null : String(row.versionId),
    status: row.status == null ? null : String(row.status),
    name: textsOf(row.name),
    description: textsOf(row.description),
    submitted: row.submitted === true || row.submitted === 'true',
  }))
}

/** The nodes of one version of the Sponsor's own map, in their order. */
export async function readOwnTreeNodes(versionId: string): Promise<PackageNode[]> {
  const page = await runQuery<Row>(PACKAGE_TEMPLATE.treeNodes, { params: { versionId }, limit: 1000 })
  return page.items.map((row) => ({ key: String(row.nodeId), name: textsOf(row.name) ?? {},
    description: textsOf(row.description), condition: conditionOf(row.condition) }))
}

/** What the import needs to know of the account (`qb.sponsor.package-state`). */
export interface AccountState {
  submittedPublication: boolean
  submittedTree: boolean
  activeTrees: number
  maxTrees: number
  confirmBytes: number
}

export async function readAccountState(): Promise<AccountState> {
  const row = (await runQuery<Row>(PACKAGE_TEMPLATE.state, { limit: 1 })).items[0] ?? {}
  const yes = (value: unknown) => value === true || value === 'true'
  const n = (value: unknown, fallback: number) => (value == null || value === '' || !Number.isFinite(Number(value))
    ? fallback : Number(value))
  return { submittedPublication: yes(row.submittedPublication), submittedTree: yes(row.submittedTree),
    activeTrees: n(row.activeTrees, 0), maxTrees: n(row.maxTrees, 1), confirmBytes: n(row.confirmBytes, 104857600) }
}

/** Every row of a paged reader. */
async function all<T>(read: (offset: number, limit: number) => Promise<{ items: T[] }>): Promise<T[]> {
  const rows: T[] = []
  for (let offset = 0; ; offset += 100) {
    const page = await read(offset, 100)
    rows.push(...page.items)
    if (page.items.length < 100) return rows
  }
}

/** How far the export got: a label of what it reads now. */
export type ExportProgress = (what: 'profile' | 'trees' | 'publications' | 'quizzes', done: number, total: number)
  => void

/** Reads everything the package carries and writes it. */
export async function exportPackage(fallbackLanguage: ContentLanguage, onProgress?: ExportProgress):
  Promise<SponsorPackageDocument> {
  onProgress?.('profile', 0, 1)
  const [sponsor, page, products, mailServer] = await Promise.all([readMySponsor(), readMySponsorPage(),
    readMyProducts(), readMailServer()])
  const profile = await exportProfile({ sponsor, page, products, mailServer, fallbackLanguage })
  onProgress?.('trees', 0, 1)
  const trees: PackageTree[] = []
  for (const tree of await readOwnTrees()) {
    if (!tree.versionId) continue
    trees.push({ key: tree.treeId, name: tree.name ?? {}, description: tree.description,
      nodes: await readOwnTreeNodes(tree.versionId) })
  }
  const rows = (await Promise.all(UNENDED.map((status) => all((offset, limit) =>
    searchPublications({ status, offset, limit }))))).flat()
  const publications: PublicationSource[] = []
  for (const [i, row] of rows.entries()) {
    onProgress?.('publications', i, rows.length)
    const detail = await readPublication(row.publicationId)
    if (!detail) continue
    const conditions = detail.publication.audience === 'INVITED' ? [] : await readConditions(row.publicationId)
    const p = detail.publication
    publications.push({ publicationId: p.publicationId, quizId: p.quizId, versionId: p.versionId,
      settings: settingsOf(detail, conditions), treeId: p.treeId ?? null, treeNodeId: p.treeNodeId ?? null,
      topics: p.topicChanges ?? null, sharedVideoId: p.sharedVideoId ?? null })
  }
  const quizRows = await all((offset, limit) => searchQuizzes({ offset, limit }))
  const quizzes: QuizSource[] = []
  for (const [i, row] of quizRows.entries()) {
    onProgress?.('quizzes', i, quizRows.length)
    const versions = await readVersions(row.quizId)
    const draft = await readDraft(row.quizId, 'editor')
    quizzes.push({
      quizId: row.quizId, changedSinceVersion: row.changedSinceVersion, latestVersionNo: row.latestVersionNo,
      topics: draft?.quiz.topics ?? [], customTopics: draft?.quiz.customTopics ?? [],
      current: async () => {
        const document = await exportQuiz(row.quizId)
        if (!document) throw new Error(`The quiz ${row.quizId} is gone`)
        return document
      },
      versions: versions.map((v) => ({ versionId: v.versionId, versionNo: v.versionNo, label: v.label,
        topics: v.topics ?? [], customTopics: v.customTopics ?? [],
        file: async () => {
          const detail: VersionDetail | null = await readVersion(v.versionId)
          if (!detail) throw new Error(`The version ${v.versionId} is gone`)
          return exportVersion(detail)
        } })),
    })
  }
  return buildPackage({ profile, trees, quizzes, publications })
}

/** The file name of an export: the page's handle (or the company) and the day. */
export function packageFileName(document: SponsorPackageDocument): string {
  const profile = document.profile
  const name = (profile?.page?.handle ?? profile?.basics?.company ?? 'sponsor').trim()
    .replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 60) || 'sponsor'
  return `${name}-${document.exportedAt.slice(0, 10)}.quizbuks-sponsor.json`
}

export function packageFile(document: SponsorPackageDocument): { blob: Blob; name: string; size: number } {
  const blob = new Blob([JSON.stringify(document)], { type: 'application/json' })
  return { blob, name: packageFileName(document), size: blob.size }
}

// ---- reading ---------------------------------------------------------------------------------------------------

/** Why a file is not a package this page reads; `shape` comes with the places found wrong. */
export type PackageProblem = 'notPackage' | 'newerVersion' | 'shape'

/**
 * Reads a package of version 1 or 2: only the shapes are checked here (the quiz files and the profile part are checked
 * by the server before anything is imported). A version 1 package reads as one without maps, nodes, topics or shared
 * videos.
 */
export function readPackage(value: unknown):
  { document: SponsorPackageDocument } | { problem: PackageProblem; places?: string[] } {
  if (!isObject(value) || value.format !== PACKAGE_FORMAT || typeof value.version !== 'number'
    || !Number.isInteger(value.version) || value.version < 1) {
    return { problem: 'notPackage' }
  }
  if (value.version > PACKAGE_VERSION) return { problem: 'newerVersion' }
  const places: string[] = []
  const v2 = value.version >= 2
  const profile = value.profile == null ? null : isObject(value.profile) ? value.profile : (places.push('profile'), null)
  const trees: PackageTree[] = []
  if (v2 && value.trees != null) {
    if (!Array.isArray(value.trees)) places.push('trees')
    else value.trees.forEach((tree, i) => {
      if (!isObject(tree) || typeof tree.key !== 'string' || !isObject(tree.name) || !Array.isArray(tree.nodes)) {
        places.push(`trees[${i}]`)
        return
      }
      const nodes: PackageNode[] = []
      tree.nodes.forEach((node, j) => {
        if (!isObject(node) || typeof node.key !== 'string' || !isObject(node.name) || !isObject(node.condition)) {
          places.push(`trees[${i}].nodes[${j}]`)
          return
        }
        nodes.push({ key: node.key, name: node.name as Texts,
          description: isObject(node.description) ? node.description as Texts : null, condition: node.condition })
      })
      trees.push({ key: tree.key, name: tree.name as Texts,
        description: isObject(tree.description) ? tree.description as Texts : null, nodes })
    })
  }
  const strings = (list: unknown): string[] | undefined =>
    Array.isArray(list) ? list.filter((item): item is string => typeof item === 'string') : undefined
  const quizzes: PackageQuiz[] = []
  if (!Array.isArray(value.quizzes)) places.push('quizzes')
  else value.quizzes.forEach((quiz, i) => {
    if (!isObject(quiz) || typeof quiz.key !== 'string' || !Array.isArray(quiz.versions)
      || !(quiz.current == null || isObject(quiz.current))) {
      places.push(`quizzes[${i}]`)
      return
    }
    const versions: PackageQuizVersion[] = []
    quiz.versions.forEach((version, j) => {
      if (!isObject(version) || typeof version.key !== 'string' || !isObject(version.file)) {
        places.push(`quizzes[${i}].versions[${j}]`)
        return
      }
      versions.push({ key: version.key, label: typeof version.label === 'string' ? version.label : version.key,
        file: version.file, ...(v2 ? { topics: strings(version.topics), customTopics: strings(version.customTopics) }
          : {}) })
    })
    if (versions.length === 0 && quiz.current == null) places.push(`quizzes[${i}].current`)
    quizzes.push({ key: quiz.key, versions, current: isObject(quiz.current) ? quiz.current : null,
      ...(v2 ? { topics: strings(quiz.topics), customTopics: strings(quiz.customTopics) } : {}) })
  })
  const publications: PackagePublication[] = []
  if (!Array.isArray(value.publications)) places.push('publications')
  else value.publications.forEach((publication, i) => {
    if (!isObject(publication)) {
      places.push(`publications[${i}]`)
      return
    }
    const read = parseSettings(publication.settings)
    if ('problem' in read) {
      places.push(`publications[${i}].settings`)
      return
    }
    const node = v2 && isObject(publication.node) && typeof publication.node.tree === 'string'
      && typeof publication.node.node === 'string'
      ? { tree: publication.node.tree, node: publication.node.node,
        name: isObject(publication.node.name) ? publication.node.name as Texts : null } : null
    const topics = v2 && isObject(publication.topics) ? {
      add: strings(publication.topics.add) ?? [], remove: strings(publication.topics.remove) ?? [],
      customAdd: strings(publication.topics.customAdd) ?? [],
      customRemove: strings(publication.topics.customRemove) ?? [] } : null
    publications.push({
      quiz: typeof publication.quiz === 'string' ? publication.quiz : null,
      version: typeof publication.version === 'string' ? publication.version : null,
      settings: read.settings, node, topics,
      sharedVideo: v2 && typeof publication.sharedVideo === 'string' ? publication.sharedVideo : null,
    })
  })
  if (places.length > 0) return { problem: 'shape', places }
  return { document: { format: PACKAGE_FORMAT, version: value.version,
    exportedAt: typeof value.exportedAt === 'string' ? value.exportedAt : '',
    profile: profile as ProfilePackageDocument | null, trees, quizzes, publications } }
}

/** The SHA-256 of the file's bytes, in hex: the import record knows the package by it. */
export async function packageHash(file: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

// ---- planning --------------------------------------------------------------------------------------------------

/** The import's way: the whole package, or only the profile part (an account that submitted a publication). */
export type ImportMode = 'FULL' | 'PROFILE'

/** One step of the import, in the order run; `key` is the record's. */
export type PlannedStep =
  | { kind: 'profile'; key: string }
  | { kind: 'tree'; key: string; tree: PackageTree }
  | { kind: 'content'; key: string; quiz: string; label: string; title: string; check: string;
    document: Record<string, unknown>; into: string | null; topics: { topics: string[]; customTopics: string[] };
    /** The steps that clear the content this one replaces, before it (0 for the first content). */
    clears: number }
  | { kind: 'clear'; key: string; quiz: string; label: string; title: string; clear: number; clears: number;
    quizStep: string; /** The step that brings the content these clear the way for. */ content: string }
  | { kind: 'batch'; key: string; quiz: string; label: string; title: string; check: string; batch: number;
    batches: number; document: Record<string, unknown>; quizStep: string;
    /** The steps that cleared the content this one replaces: a batch refused after them leaves the draft part empty. */
    clears: number }
  | { kind: 'version'; key: string; quiz: string; label: string; title: string; versionKey: string;
    quizStep: string }
  | { kind: 'publication'; key: string; index: number; name: string; publication: PackagePublication;
    quizStep: string | null; versionStep: string | null; node: Binding | null }

/** Where a publication is bound: a node of a map this import makes, or a node in effect of the account's own. */
export type Binding = { treeStep: string; nodeKey: string } | { treeId: string; nodeId: string }

/** A node in effect of one of the account's own maps (a package's publications are bound to it by node key). */
export interface OwnNode {
  treeId: string
  nodeId: string
  name: Texts
}

export interface PlanContext {
  mode: ImportMode
  batchSize: number
  /** The bytes one request takes at most (`importBatchBytes`); 0 or none: only the questions are counted. */
  batchBytes?: number
  maxQuestions: number
  state: AccountState
  /** The titles of the account's quizzes (same titles are only pointed out). */
  quizTitles: string[]
  /** The nodes in effect of the account's own maps, when it has a map it submitted. */
  ownNodes: OwnNode[]
  /** The shared videos of this environment's catalog the package names that are shared now. */
  sharedVideos: ReadonlySet<string>
}

/** What the check page shows besides the server's problems. */
export interface ImportPlanned {
  mode: ImportMode
  batchSize: number
  /** The bytes one request takes at most, as planned (0: not limited). */
  batchBytes: number
  steps: PlannedStep[]
  /** What the import makes. */
  makes: { profile: boolean; trees: number; quizzes: number; versions: number; publications: number }
  /** Quizzes of the package whose title the account has already. */
  sameTitles: string[]
  /** Maps not imported (over the account's limit), and maps not imported because the account has one submitted. */
  treesSkipped: string[]
  treesKept: boolean
  /** Publications bound in the package that the import leaves unbound, by name. */
  unbound: string[]
  /** Publications whose shared video this environment does not share: chosen again after the import. */
  videosAgain: string[]
  /** Quizzes over the question bank's limit (not importable). */
  tooLarge: string[]
  /**
   * F-F11b: what no request can take within the byte limit, by the quiz's title: a question that alone is over it
   * (its number in the file), or the heading and materials (null). The import does not start until the file is mended.
   */
  oversized: { title: string; question: number | null }[]
  /** Publications naming a quiz or version the package lacks (not imported). */
  orphans: string[]
}

const QUESTION_KEY = /^q(\d+)\.(.+)$/

function titleOf(file: Record<string, unknown>): string {
  return isObject(file.quiz) && typeof file.quiz.title === 'string' ? file.quiz.title : ''
}

function questionsOf(file: Record<string, unknown>): unknown[] {
  return Array.isArray(file.questions) ? file.questions : []
}

/**
 * The first batch of a content: everything of the file but the questions after the first `size` (and their
 * translations and products), so that the materials, the heading and their translations come with it.
 */
export function firstBatch(file: Record<string, unknown>, size: number): Record<string, unknown> {
  const questions = questionsOf(file)
  if (questions.length <= size) return file
  const keep = (entries: unknown) => isObject(entries) ? Object.fromEntries(Object.entries(entries).filter(([key]) => {
    const match = QUESTION_KEY.exec(key)
    return !match || Number(match[1]) <= size
  })) : entries
  const translations = isObject(file.translations) ? Object.fromEntries(Object.entries(file.translations)
    .map(([language, t]) => [language, isObject(t) ? { ...t, texts: keep(t.texts), images: keep(t.images),
      hashes: keep(t.hashes) } : t])) : file.translations
  const products = isObject(file.products) ? { ...file.products,
    questions: Array.isArray(file.products.questions) ? file.products.questions.filter((link) =>
      isObject(link) && typeof link.no === 'number' && link.no <= size) : [] } : file.products
  return { ...file, questions: questions.slice(0, size), translations, products }
}

/** The rows one step that clears a content takes at most (the server's `ContentLimits.CLEAR_ROWS_TECH`). */
export const CLEAR_ROWS = 2000

/**
 * The parts (rows) a quiz file makes: its materials (and their images), questions, options and every text, image and
 * material it translates. The server deletes a content part by part, so this says how many steps clear it (F-F11b;
 * the count of the server's own tests, `PackageFixtures.partsOf`, with the materials' images counted too: a count that
 * is short only makes the step that follows slower).
 */
export function partsOf(file: Record<string, unknown>): number {
  const length = (value: unknown) => (Array.isArray(value) ? value.length : 0)
  const size = (value: unknown) => (isObject(value) ? Object.keys(value).length : 0)
  let parts = length(file.materials)
  if (Array.isArray(file.materials)) {
    for (const material of file.materials) parts += isObject(material) ? length(material.images) : 0
  }
  for (const question of questionsOf(file)) {
    parts += 1 + (isObject(question) ? length(question.options) : 0)
  }
  if (isObject(file.translations)) {
    for (const t of Object.values(file.translations)) {
      if (isObject(t)) parts += size(t.texts) + size(t.images) + length(t.materials)
    }
  }
  return parts
}

/** A content cut into the requests of its steps. */
export interface ContentCut {
  /** The first step: the heading, the materials and as many questions as both limits allow. */
  first: Record<string, unknown>
  /** The questions after it, as batches. */
  batches: Record<string, unknown>[]
  /** What no request can take within the byte limit: question numbers (from 1), and null for the heading and materials. */
  oversized: (number | null)[]
}

/**
 * The content of a file cut by the questions (`size`) and, when `maxBytes` is above 0, by the bytes a request takes:
 * the first step (heading, materials and their translations, the first questions) and the batches of the rest each
 * within both (F-F11b, the same cut as the server's tests make with `PackageFixtures.splitContentByBytes`). Without
 * the byte limit it is the cut there always was.
 */
export function planContent(file: Record<string, unknown>, size: number, maxBytes: number): ContentCut {
  const questions = questionsOf(file)
  if (maxBytes <= 0) {
    return { first: firstBatch(file, size), batches: splitQuestions(file, size).slice(1), oversized: [] }
  }
  const room = Math.min(size, questions.length)
  // The most questions the first step takes: the first step grows with them, so halve.
  let low = 0
  let high = room
  while (low < high) {
    const middle = Math.ceil((low + high) / 2)
    if (bytesOf(firstBatch(file, middle)) <= maxBytes) low = middle
    else high = middle - 1
  }
  const first = firstBatch(file, low)
  const oversized: (number | null)[] = []
  if (bytesOf(first) > maxBytes) oversized.push(null)
  const rest = planBatches(file, size, maxBytes, low)
  rest.tooLarge.forEach((n) => oversized.push(n))
  return { first, batches: rest.batches, oversized }
}

/**
 * The checks of the pieces a content is cut into, as one check of the content (F-F11b): a large bank is checked
 * piece by piece (a request holds only so many bytes), and what the steps need of the check, the files to upload, is
 * the pieces' together, each once.
 */
export function mergeChecks(checks: ImportCheck[]): ImportCheck {
  const [head, ...rest] = checks
  if (rest.length === 0) return head
  const files = new Map(head.files.map((file) => [file.key, file]))
  for (const check of rest) for (const file of check.files) if (!files.has(file.key)) files.set(file.key, file)
  return { ...head, questions: checks.reduce((sum, check) => sum + check.questions, 0), files: [...files.values()],
    ...(head.products === undefined ? {} : { products: checks.reduce((sum, check) => sum + (check.products ?? 0), 0) }),
    ...(head.missingProducts === undefined ? {} : { missingProducts: [...new Set(checks.flatMap(
      (check) => check.missingProducts ?? []))] }) }
}

/** The keys of the files a quiz file's content uses (every string naming one of its files). */
export function usedFiles(document: Record<string, unknown>): Set<string> {
  const files = isObject(document.files) ? document.files : {}
  const used = new Set<string>()
  const walk = (value: unknown) => {
    if (typeof value === 'string') {
      if (Object.hasOwn(files, value)) used.add(value)
    } else if (Array.isArray(value)) value.forEach(walk)
    else if (isObject(value)) Object.values(value).forEach(walk)
  }
  for (const [key, value] of Object.entries(document)) if (key !== 'files') walk(value)
  return used
}

/** Whether two names say the same in a language both have. */
function sameName(a: Texts | null | undefined, b: Texts | null | undefined): boolean {
  if (!a || !b) return false
  return (Object.keys(a) as ContentLanguage[]).some((language) => !!a[language] && a[language]?.trim()
    === b[language]?.trim())
}

/**
 * The steps of the import, in order, and what the check page says of them: the profile; the maps (only when the
 * account has none it submitted, within its limit); each quiz's versions oldest first (content, further batches of a
 * large bank, the version), then its current content; the publications last, each on the version made of its own,
 * bound to the node of the map made or, when the account has a map it submitted, to its node of the same key (else of
 * the same name).
 */
export function planImport(document: SponsorPackageDocument, context: PlanContext): ImportPlanned {
  const steps: PlannedStep[] = []
  const size = Math.max(1, Math.floor(context.batchSize))
  const bytes = context.batchBytes && context.batchBytes > 0 ? Math.floor(context.batchBytes) : 0
  const planned: ImportPlanned = { mode: context.mode, batchSize: size, batchBytes: bytes, steps,
    makes: { profile: !!document.profile, trees: 0, quizzes: 0, versions: 0, publications: 0 },
    sameTitles: [], treesSkipped: [], treesKept: false, unbound: [], videosAgain: [], tooLarge: [], oversized: [],
    orphans: [] }
  if (document.profile) steps.push({ kind: 'profile', key: 'profile' })
  if (context.mode === 'PROFILE') return planned

  const treeSteps = new Map<string, string>()
  if (context.state.submittedTree) {
    planned.treesKept = document.trees.length > 0
  } else {
    let room = Math.max(0, context.state.maxTrees - context.state.activeTrees)
    for (const [i, tree] of document.trees.entries()) {
      if (room <= 0) {
        planned.treesSkipped.push(Object.values(tree.name).find(Boolean) ?? tree.key)
        continue
      }
      room--
      const key = `tree:${i + 1}`
      treeSteps.set(tree.key, key)
      steps.push({ kind: 'tree', key, tree })
      planned.makes.trees++
    }
  }

  const titles = new Set(context.quizTitles.map((title) => title.trim()))
  const firstSteps = new Map<string, string>()
  const versionSteps = new Map<string, string>()
  for (const [i, quiz] of document.quizzes.entries()) {
    const contents: { label: string; file: Record<string, unknown>; versionKey: string | null;
      topics: { topics: string[]; customTopics: string[] } }[] = quiz.versions.map((v) => ({ label: v.label,
      file: v.file, versionKey: v.key, topics: { topics: v.topics ?? [], customTopics: v.customTopics ?? [] } }))
    if (quiz.current) {
      contents.push({ label: '', file: quiz.current, versionKey: null,
        topics: { topics: quiz.topics ?? [], customTopics: quiz.customTopics ?? [] } })
    }
    const title = titleOf(contents.at(-1)?.file ?? {})
    if (contents.some((c) => questionsOf(c.file).length > context.maxQuestions)) {
      planned.tooLarge.push(title)
      continue
    }
    if (titles.has(title.trim())) planned.sameTitles.push(title)
    const quizKey = `q${i + 1}`
    planned.makes.quizzes++
    contents.forEach((content, c) => {
      const part = content.versionKey ? `v:${content.versionKey}` : 'c'
      const base = `quiz:${quizKey}:${part}`
      const check = `${quiz.key}:${content.versionKey ?? 'current'}`
      const contentKey = `${base}:content`
      if (c === 0) firstSteps.set(quiz.key, contentKey)
      // F-F11b: the content replaced is cleared in steps of CLEAR_ROWS parts before the step that brings the next
      // (a count that is short only leaves the rest to that step, which is slower, never wrong).
      const clears = c === 0 ? 0 : Math.ceil(partsOf(contents[c - 1].file) / CLEAR_ROWS)
      for (let k = 1; k <= clears; k++) {
        steps.push({ kind: 'clear', key: `${base}:clear:${k}`, quiz: quiz.key, label: content.label, title, clear: k,
          clears, quizStep: firstSteps.get(quiz.key)!, content: contentKey })
      }
      const cut = planContent(content.file, size, bytes)
      cut.oversized.forEach((question) => planned.oversized.push({ title, question }))
      steps.push({ kind: 'content', key: contentKey, quiz: quiz.key, label: content.label, title, check,
        document: cut.first, into: c === 0 ? null : firstSteps.get(quiz.key)!, topics: content.topics, clears })
      cut.batches.forEach((batch, b) => steps.push({ kind: 'batch', key: `${base}:batch:${b + 2}`, quiz: quiz.key,
        label: content.label, title, check, batch: b + 2, batches: cut.batches.length + 1, document: batch,
        quizStep: firstSteps.get(quiz.key)!, clears }))
      if (content.versionKey) {
        const versionKey = `${base}:version`
        versionSteps.set(content.versionKey, versionKey)
        steps.push({ kind: 'version', key: versionKey, quiz: quiz.key, label: content.label, title,
          versionKey: content.versionKey, quizStep: firstSteps.get(quiz.key)! })
        planned.makes.versions++
      }
    })
  }

  for (const [i, publication] of document.publications.entries()) {
    const name = publication.settings.name
    const quizStep = publication.quiz ? firstSteps.get(publication.quiz) ?? null : null
    const versionStep = publication.version ? versionSteps.get(publication.version) ?? null : null
    if ((publication.quiz && !quizStep) || (publication.version && !versionStep)) {
      planned.orphans.push(name)
      continue
    }
    let node: Binding | null = null
    if (publication.node) {
      const treeStep = treeSteps.get(publication.node.tree)
      const tree = document.trees.find((t) => t.key === publication.node!.tree)
      if (treeStep && tree?.nodes.some((n) => n.key === publication.node!.node)) {
        node = { treeStep, nodeKey: publication.node.node }
      } else if (context.state.submittedTree) {
        const byKey = context.ownNodes.find((n) => n.nodeId === publication.node!.node)
        const byName = context.ownNodes.filter((n) => sameName(n.name, publication.node!.name))
        const found = byKey ?? (byName.length === 1 ? byName[0] : undefined)
        if (found) node = { treeId: found.treeId, nodeId: found.nodeId }
      }
      if (!node) planned.unbound.push(name)
    }
    if (publication.sharedVideo && !context.sharedVideos.has(publication.sharedVideo)) planned.videosAgain.push(name)
    steps.push({ kind: 'publication', key: `publication:${i + 1}`, index: i, name, publication, quizStep,
      versionStep, node })
    planned.makes.publications++
  }
  return planned
}

// ---- running ---------------------------------------------------------------------------------------------------

/** `PackageImportProcesses.ImportView`. */
export interface ImportView {
  importId: string
  mode: ImportMode
  status: 'RUNNING' | 'DONE' | 'ABANDONED'
  batchSize: number
  /** The bytes the import was started with (F-F11a); null for one started before. */
  batchBytes: number | null
  stepsTotal: number
  stepsDone: number
  done: Record<string, Record<string, unknown>>
  resumed: boolean
}

/** `PackageImportProcesses.StepOutput`. */
export interface StepOutput {
  importId: string
  step: string
  status: string
  stepsDone: number
  stepsTotal: number
  result: Record<string, unknown>
  repeated: boolean
}

/** `PackageImportProcesses.AbandonOutput`. */
export interface AbandonOutput {
  importId: string
  status: string
  retiredTrees: string[]
  deletedPublications: string[]
  removedQuizzes: string[]
  keptQuizzes: string[]
}

/** A step that failed: which, and why (`cause`). */
export class PackageStepError extends Error {
  readonly step: PlannedStep

  constructor(step: PlannedStep, cause: unknown) {
    super(`The import failed at ${step.key}`, { cause })
    this.step = step
  }
}

/** What the server's checks gave at the check page: the profile's plan and each quiz file's check. */
export interface ImportChecks {
  profile: ImportPlan | null
  /** By the content's check id (`<quiz key>:<version key|current>`). */
  quizzes: Record<string, ImportCheck>
  /** The mail server's password, when the profile's plan asks for it. */
  mailPassword: string
  /** The package's shared videos this environment shares now (the others are chosen again). */
  sharedVideos: ReadonlySet<string>
}

export interface ImportRun {
  importId: string
  /** What the steps made, by step key (done before and now). */
  results: Record<string, Record<string, unknown>>
  status: string
}

/** The progress of a run: the step at work (from 1) of all, and whether it was done before. */
export type RunProgress = (step: PlannedStep, index: number, total: number, phase: 'files' | 'step' | 'done')
  => void

const startAt = (now: Date) => nextHour(now)

/** The input of a step, its files uploaded; the earlier steps' results give the ids it names. */
async function stepInput(step: PlannedStep, document: SponsorPackageDocument, checks: ImportChecks,
  results: Record<string, Record<string, unknown>>, now: Date): Promise<Record<string, unknown>> {
  const id = (key: string | null, field: string): string | null => {
    const value = key ? results[key]?.[field] : null
    return value == null ? null : String(value)
  }
  switch (step.kind) {
    case 'profile': {
      const plan = checks.profile
      if (!plan || !document.profile) throw new Error('No profile part was checked')
      const raw = document.profile as unknown as Record<string, unknown>
      // F-C6: images given as links are downloaded by the server first; one it refuses keeps the image there is (the
      // check page said which links there are, so the sponsor sets that image on the page afterwards).
      const linked: Record<string, string> = {}
      for (const link of plan.links ?? []) {
        try {
          linked[link.path] = await downloadLink(link)
        } catch {
          // left as it is: listed before the import
        }
      }
      const files = await uploadProfileImages(raw, plan, undefined, linked)
      return { profile: { document: withoutData(raw), files, mailPassword: checks.mailPassword || null } }
    }
    case 'tree': {
      const { tree } = step
      return { tree: {
        create: { ownerKind: 'SPONSOR', name: tree.name, description: tree.description },
        nodes: tree.nodes.map((node) => ({ key: node.key, name: node.name, description: node.description,
          condition: node.condition })),
      } }
    }
    case 'content': {
      const check = checks.quizzes[step.check]
      if (!check) throw new Error(`The quiz file ${step.check} was not checked`)
      const used = usedFiles(step.document)
      const files = await uploadImportFiles(step.document, { files: check.files.filter((f) => used.has(f.key)) })
      const into = step.into ? id(step.into, 'quizId') : null
      return { quiz: { document: withoutData(step.document), files, ...(into ? { intoQuizId: into } : {}) },
        quizTopics: step.topics }
    }
    case 'clear':
      return { clear: { quizId: id(step.quizStep, 'quizId'), rows: CLEAR_ROWS } }
    case 'batch': {
      const check = checks.quizzes[step.check]
      if (!check) throw new Error(`The quiz file ${step.check} was not checked`)
      const used = usedFiles(step.document)
      const files = await uploadImportFiles(step.document, { files: check.files.filter((f) => used.has(f.key)) })
      return { questions: { quizId: id(step.quizStep, 'quizId'), document: withoutData(step.document), files } }
    }
    case 'version':
      return { version: { quizId: id(step.quizStep, 'quizId') } }
    case 'publication': {
      const { publication } = step
      const start = startAt(now)
      const settings = publication.settings
      const form = formOfSettings(settings, { quizId: id(step.quizStep, 'quizId') ?? '',
        versionId: id(step.versionStep, 'versionId') ?? '', fullScore: null, startAt: start,
        endAt: settings.durationDays ? endAfter(start, settings.durationDays) : null, name: settings.name })
      const built = inputOf({ ...form, quizId: form.quizId || null, versionId: form.versionId || null }, null, null)
      if (!built.input) throw new Error(`The settings of ${settings.name} do not make a draft`)
      let tree: { treeId: string; treeNodeId: string } | null = null
      if (step.node && 'treeStep' in step.node) {
        const treeId = id(step.node.treeStep, 'treeId')
        const nodeIds = results[step.node.treeStep]?.nodeIds as Record<string, string> | undefined
        if (treeId && nodeIds?.[step.node.nodeKey]) tree = { treeId, treeNodeId: nodeIds[step.node.nodeKey] }
      } else if (step.node) {
        tree = { treeId: step.node.treeId, treeNodeId: step.node.nodeId }
      }
      return { publication: {
        save: { ...built.input, ...(tree ?? {}) },
        criteria: settings.audience === 'INVITED' || settings.conditions.length === 0 ? null : settings.conditions,
        topics: hasTopics(publication.topics) ? publication.topics : null,
        sharedVideoId: publication.sharedVideo && checks.sharedVideos.has(publication.sharedVideo)
          ? publication.sharedVideo : null,
      } }
    }
  }
}

/**
 * Runs the import: starts it (or goes on with the open import of the same package, whose steps done are skipped with
 * what they made), then each step in order. A failure throws {@link PackageStepError}: the steps before it stay done,
 * and running again goes on from it.
 */
export async function runImport({ document, hash, plan, checks, onProgress, now = new Date(), replan }: {
  document: SponsorPackageDocument
  hash: string
  plan: ImportPlanned
  checks: ImportChecks
  onProgress?: RunProgress
  now?: Date
  /**
   * The plan again with the batch size and the bytes an import gone on with was started with: it keeps the batches it
   * began with, whatever the limits are now (the steps' keys count the batches).
   */
  replan?: (batchSize: number, batchBytes: number) => ImportPlanned
}): Promise<ImportRun> {
  const start = (steps: number, batchSize: number, batchBytes: number) => runProcess<ImportView>(
    PACKAGE_PROCESS.start, { packageHash: hash, formatVersion: document.version, mode: plan.mode, batchSize,
      ...(batchBytes > 0 ? { batchBytes } : {}), stepsTotal: steps })
  let current = plan
  let view = await start(current.steps.length, current.batchSize, current.batchBytes)
  // An import gone on with returns what it was started with (none for the bytes: one started before F-F11a).
  const recorded = view.batchBytes ?? 0
  if ((view.batchSize !== current.batchSize || recorded !== current.batchBytes) && replan) {
    current = replan(view.batchSize, recorded)
    view = await start(current.steps.length, view.batchSize, recorded)
  }
  const results: Record<string, Record<string, unknown>> = { ...view.done }
  // Clearing steps of contents already done are skipped below and never reach the server, which counts the steps it is
  // sent: the total it is given leaves them out, or the import would stay open for ever.
  const skipped = (step: PlannedStep) => step.kind === 'clear' && !!results[step.content]
  const sent = current.steps.filter((step) => !skipped(step)).length
  if (sent !== current.steps.length) view = await start(sent, view.batchSize, recorded)
  let status: string = view.status
  for (const [i, step] of current.steps.entries()) {
    if (results[step.key]) {
      onProgress?.(step, i + 1, current.steps.length, 'done')
      continue
    }
    // An import begun before the clearing steps existed has its contents made without them: where the content a step
    // clears the way for is done, the clearing must not run now, it would delete what that content imported (and the
    // content, being done, would not bring it back).
    if (skipped(step)) {
      onProgress?.(step, i + 1, current.steps.length, 'done')
      continue
    }
    try {
      onProgress?.(step, i + 1, current.steps.length, 'files')
      const input = await stepInput(step, document, checks, results, now)
      onProgress?.(step, i + 1, current.steps.length, 'step')
      const out = await runProcess<StepOutput>(PACKAGE_PROCESS.step, { importId: view.importId, step: step.key,
        ...input })
      results[step.key] = out.result
      status = out.status
    } catch (e) {
      throw new PackageStepError(step, e)
    }
    onProgress?.(step, i + 1, current.steps.length, 'done')
  }
  return { importId: view.importId, results, status }
}

/** Gives the open import up: the drafts it made are removed by the existing processes. */
export const abandonImport = (importId: string) =>
  runProcess<AbandonOutput>(PACKAGE_PROCESS.abandon, { importId })

/** The Sponsor's open import, if any (to go on with or give up). */
export interface OpenImport {
  importId: string
  mode: ImportMode
  stepsDone: number
  stepsTotal: number
  startedAt: string
  lastStep: string | null
}

export async function readOpenImport(): Promise<OpenImport | null> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: PACKAGE_DATASET } },
    body: { offset: 0, limit: 1, filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }] },
  }))
  const row = (page.items?.[0]?.attributes ?? null) as Row | null
  if (!row) return null
  return { importId: String(row.importId), mode: row.mode === 'PROFILE' ? 'PROFILE' : 'FULL',
    stepsDone: Number(row.stepsDone ?? 0), stepsTotal: Number(row.stepsTotal ?? 0),
    startedAt: String(row.startedAt ?? ''), lastStep: row.lastStep == null ? null : String(row.lastStep) }
}

// ---- submitting the drafts -------------------------------------------------------------------------------------

/** One draft submitted, or skipped with why. */
export interface Submitted {
  kind: 'tree' | 'publication'
  id: string
  name: string
  ok: boolean
  error?: unknown
}

/**
 * "Submit all drafts" (D-Q103-5): first the drafts of the Sponsor's maps never in effect (`QB_TREE_SUBMIT`), then
 * every draft publication (`QB_PUBLICATION_SUBMIT`), one after the other; one refused is skipped with the server's
 * reasons (a publication bound to a map not in effect yet, funds short …), the review goes on as for any.
 */
export async function submitAllDrafts(onProgress?: (done: number, total: number) => void,
  treeName: (tree: OwnTree) => string = (tree) => Object.values(tree.name ?? {}).find(Boolean) ?? tree.treeId):
  Promise<Submitted[]> {
  const trees = (await readOwnTrees()).filter((tree) => !tree.currentVersionId && tree.status === 'DRAFT')
  const drafts = await all((offset, limit) => searchPublications({ status: 'DRAFT', offset, limit }))
  const total = trees.length + drafts.length
  const out: Submitted[] = []
  let done = 0
  onProgress?.(0, total)
  for (const tree of trees) {
    try {
      await runProcess(PACKAGE_PROCESS.treeSubmit, { treeId: tree.treeId })
      out.push({ kind: 'tree', id: tree.treeId, name: treeName(tree), ok: true })
    } catch (error) {
      out.push({ kind: 'tree', id: tree.treeId, name: treeName(tree), ok: false, error })
    }
    onProgress?.(++done, total)
  }
  for (const draft of drafts) {
    try {
      await runProcess(PUBLISHING_PROCESS.submit, { publicationId: draft.publicationId })
      out.push({ kind: 'publication', id: draft.publicationId, name: draft.name, ok: true })
    } catch (error) {
      out.push({ kind: 'publication', id: draft.publicationId, name: draft.name, ok: false, error })
    }
    onProgress?.(++done, total)
  }
  return out
}
