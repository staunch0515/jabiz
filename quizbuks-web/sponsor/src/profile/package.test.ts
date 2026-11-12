import { configureClient, session } from '@jabiz/client'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VersionDetail } from '../content/api'
import { exportVersion, type QuizFileDocument } from '../content/transfer'
import { parseSettings, type PublicationSettings } from '../publishing/settings'
import {
  buildPackage,
  CLEAR_ROWS,
  firstBatch,
  mergeChecks,
  PACKAGE_FORMAT,
  partsOf,
  planContent,
  planImport,
  readPackage,
  runImport,
  UNENDED,
  usedFiles,
  type AccountState,
  type PackageSources,
  type PlanContext,
  type SponsorPackageDocument,
} from './package'

function quizFile(title: string, count: number): QuizFileDocument {
  return { format: 'quizbuks.quiz', formatVersion: 3, exportedAt: '2026-11-09T00:00:00Z',
    quiz: { kind: 'QUIZ', title, intro: null, timeLimitSec: null, cover: null, language: 'en' }, materials: [],
    questions: Array.from({ length: count }, (_, i) => ({ stem: `Q${i + 1}?`, image: null, points: 1, selection: null,
      options: [{ text: 'Yes', image: null, correct: true }, { text: 'No', image: null, correct: false }] })),
    files: {} }
}

function settings(name: string): PublicationSettings {
  const read = parseSettings({ format: 'quizbuks.publication-settings', version: 1, name, fullScore: 3,
    durationDays: 7, passScore: 2, budgetCap: 1000, maxWinners: 10, tiers: [{ fromScore: 0, toScore: 3, reward: 10 }],
    rewardMethod: 'PLATFORM', audience: 'PUBLIC', regions: ['GLOBAL'], languages: [], translations: {},
    conditions: [] })
  if ('problem' in read) throw new Error(read.problem)
  return read.settings
}

const STATE: AccountState = { submittedPublication: false, submittedTree: false, activeTrees: 0, maxTrees: 1,
  confirmBytes: 100 }

function context(more: Partial<PlanContext> = {}): PlanContext {
  return { mode: 'FULL', batchSize: 2, maxQuestions: 1000, state: STATE, quizTitles: [], ownNodes: [],
    sharedVideos: new Set(), ...more }
}

/** A package of one map (two nodes), one quiz (versions v1 of 3 questions and v2 of 1, current of 2), one publication. */
function packageOf(): SponsorPackageDocument {
  return {
    format: PACKAGE_FORMAT, version: 2, exportedAt: '2026-11-09T00:00:00Z', profile: null,
    trees: [{ key: 't-src', name: { en: 'Coffee' }, description: null, nodes: [
      { key: 'n1', name: { en: 'Basics' }, description: null, condition: { op: 'ALL', items: [{ passed: true }] } },
      { key: 'n2', name: { en: 'Advanced' }, description: null,
        condition: { op: 'ALL', items: [{ node: 'n1', beforeAnswer: true }, { passed: true }] } },
    ] }],
    quizzes: [{ key: 'q1', versions: [
      { key: 'q1v1', label: 'v1', file: { ...quizFile('Coffee', 3) }, topics: ['coffee'], customTopics: [] },
      { key: 'q1v2', label: 'v2', file: { ...quizFile('Coffee', 1) } },
    ], current: { ...quizFile('Coffee', 2) }, topics: [], customTopics: ['beans'] }],
    publications: [{ quiz: 'q1', version: 'q1v1', settings: settings('Spring'),
      node: { tree: 't-src', node: 'n1', name: { en: 'Basics' } }, topics: { add: [], remove: [], customAdd: ['latte'],
        customRemove: [] }, sharedVideo: 'sv-1' }],
  }
}

describe('writing a sponsor package', () => {
  it('carries the versions the publications not ended use, and the current content only when it differs', async () => {
    const sources: PackageSources = {
      profile: null,
      trees: [],
      quizzes: [
        { quizId: 'quiz-a', changedSinceVersion: false, latestVersionNo: 3, topics: [], customTopics: [],
          current: async () => quizFile('A now', 1),
          versions: [1, 2, 3].map((n) => ({ versionId: `a${n}`, versionNo: n, label: `v${n}`, topics: [],
            customTopics: [], file: async () => quizFile(`A v${n}`, n) })) },
        { quizId: 'quiz-b', changedSinceVersion: false, latestVersionNo: 1, topics: ['tea'], customTopics: [],
          current: async () => quizFile('B now', 1),
          versions: [{ versionId: 'b1', versionNo: 1, label: 'v1', topics: ['tea'], customTopics: [],
            file: async () => quizFile('B v1', 1) }] },
      ],
      publications: [
        { publicationId: 'p1', quizId: 'quiz-a', versionId: 'a2', settings: settings('On v2'), treeId: 't1',
          treeNodeId: 'n1', topics: { add: [], remove: [], customAdd: [], customRemove: [] }, sharedVideoId: null },
        { publicationId: 'p2', quizId: 'quiz-b', versionId: 'b1', settings: settings('On b1'), treeId: null,
          treeNodeId: null, topics: null, sharedVideoId: 'sv-9' },
      ],
    }
    const document = await buildPackage(sources, new Date('2026-11-09T01:00:00Z'))
    expect(document).toMatchObject({ format: 'quizbuks.sponsor-package', version: 2 })
    // Quiz A: only v2 (a publication uses it); its current content differs from v2 (the latest is v3).
    expect(document.quizzes[0].versions.map((v) => v.key)).toEqual(['q1v2'])
    expect(document.quizzes[0].current).toMatchObject({ quiz: { title: 'A now' } })
    // Quiz B: v1 is the latest and unchanged since: no current content to replace it with again.
    expect(document.quizzes[1].versions.map((v) => v.key)).toEqual(['q2v1'])
    expect(document.quizzes[1].current).toBeNull()
    expect(document.publications).toMatchObject([
      { quiz: 'q1', version: 'q1v2', node: { tree: 't1', node: 'n1' }, topics: null },
      { quiz: 'q2', version: 'q2v1', node: null, sharedVideo: 'sv-9' },
    ])
    // No state, no record, no secret.
    const text = JSON.stringify(document)
    for (const word of ['submittedAt', 'approvalRequestId', 'rejectReason', 'digitalContent', 'password', 'status"']) {
      expect(text).not.toContain(word)
    }
    expect(UNENDED).not.toContain('ENDED')
  })

  it('writes a saved version as a quiz file, with its other languages and files', async () => {
    const version = { versionId: 'v', quizId: 'q', versionNo: 2, label: 'v2', title: 'Tea', kind: 'QUIZ',
      language: 'en', languages: ['en', 'ja'], questionCount: 1, fullScore: 2, versionedAt: '', contentHash: '',
      foreignVideo: false, timeLimitSec: 60, materialCount: 1,
      content: { title: 'Tea', intro: 'About tea', cover: 'file-cover', timeLimitSec: 60,
        materials: [{ no: 1, kind: 'VIDEO_LINK', title: 'Film', description: null, body: null,
          url: 'https://youtu.be/x', pdf: null, audio: null, images: [], watchToAnswer: true, durationSec: 30 }],
        questions: [{ no: 1, stem: 'Green?', image: 'file-q', points: 2, options: [
          { no: 2, text: 'No', image: null, correct: false }, { no: 1, text: 'Yes', image: null, correct: true }] }],
        translations: { ja: { texts: { title: 'お茶', 'q1.stem': '緑？', intro: null }, images: { 'q1.image': 'file-q' },
          materials: [] } } },
    } as unknown as VersionDetail
    const read = vi.fn(async (id: string) => new Blob([id], { type: 'image/png' }))
    const file = await exportVersion(version, undefined, read)
    expect(file).toMatchObject({ format: 'quizbuks.quiz', formatVersion: 3,
      quiz: { kind: 'QUIZ', title: 'Tea', cover: 'f1', language: 'en', timeLimitSec: 60 } })
    expect(file.materials[0]).toMatchObject({ kind: 'VIDEO_LINK', watchToAnswer: true, durationSec: 30 })
    expect(file.questions[0].options.map((o) => o.text)).toEqual(['Yes', 'No'])
    expect(file.translations?.ja).toEqual({ texts: { title: 'お茶', 'q1.stem': '緑？' }, images: { 'q1.image': 'f2' },
      materials: [], hashes: {} })
    expect(read).toHaveBeenCalledTimes(2)
    expect(Object.keys(file.files)).toEqual(['f1', 'f2'])
    expect(file.products).toBeUndefined()
    // F-S7: shuffled (the default) is not written, nor a difficulty the question has not.
    expect(file.quiz.shuffleQuestions).toBeUndefined()
    expect(file.questions[0].difficulty).toBeUndefined()
  })

  it('writes a fixed question order and the questions\' difficulty (F-S7)', async () => {
    const version = { versionId: 'v', quizId: 'q', versionNo: 1, label: 'v1', title: 'Tea', kind: 'QUIZ',
      language: 'en', languages: ['en'], questionCount: 1, fullScore: 1, versionedAt: '', contentHash: '',
      foreignVideo: false, timeLimitSec: 60, materialCount: 0,
      content: { title: 'Tea', intro: null, cover: null, timeLimitSec: 60, materials: [], questionOrder: 'fixed',
        questions: [{ no: 1, stem: 'Green?', image: null, points: 1, difficulty: 'HARD', options: [
          { no: 1, text: 'Yes', image: null, correct: true }, { no: 2, text: 'No', image: null, correct: false }] }],
        translations: {} },
    } as unknown as VersionDetail
    const file = await exportVersion(version, undefined, vi.fn())
    expect(file.quiz.shuffleQuestions).toBe(false)
    expect(file.questions[0].difficulty).toBe('HARD')
  })
})

describe('reading a sponsor package', () => {
  it('reads version 2 back as written', () => {
    const document = packageOf()
    const read = readPackage(JSON.parse(JSON.stringify(document)))
    expect('document' in read && read.document).toEqual(document)
  })

  it('reads version 1 (no maps, nodes, topics or shared videos)', () => {
    const v1 = { format: PACKAGE_FORMAT, version: 1, exportedAt: '2026-11-05T00:00:00Z', profile: null,
      quizzes: [{ key: 'q1', versions: [{ key: 'q1v2', label: 'v2', file: quizFile('Old', 1) }], current: null,
        currentIsLastVersion: true }],
      publications: [{ quiz: 'q1', version: 'q1v2', settings: settings('Old one'),
        node: { tree: 't', node: 'n' }, sharedVideo: 'ignored' }] }
    const read = readPackage(v1)
    if (!('document' in read)) throw new Error('refused')
    expect(read.document.version).toBe(1)
    expect(read.document.trees).toEqual([])
    expect(read.document.publications[0]).toMatchObject({ quiz: 'q1', version: 'q1v2', node: null, topics: null,
      sharedVideo: null })
    expect(read.document.quizzes[0].versions[0].topics).toBeUndefined()
  })

  it('refuses other files, newer versions and damaged packages', () => {
    expect(readPackage({ format: 'quizbuks.quiz', formatVersion: 3 })).toEqual({ problem: 'notPackage' })
    expect(readPackage([1, 2])).toEqual({ problem: 'notPackage' })
    expect(readPackage({ format: PACKAGE_FORMAT, version: 0 })).toEqual({ problem: 'notPackage' })
    expect(readPackage({ ...packageOf(), version: 3 })).toEqual({ problem: 'newerVersion' })
    expect(readPackage({ ...packageOf(), quizzes: 'x', publications: [{ settings: { format: 'other' } }] }))
      .toEqual({ problem: 'shape', places: ['quizzes', 'publications[0].settings'] })
  })
})

describe('planning an import', () => {
  it('runs the steps in order: profile, maps, versions oldest first in batches, current content, publications', () => {
    const plan = planImport({ ...packageOf(), profile: { format: 'quizbuks-profile' } as never }, context())
    expect(plan.steps.map((s) => s.key)).toEqual([
      'profile',
      'tree:1',
      'quiz:q1:v:q1v1:content', 'quiz:q1:v:q1v1:batch:2', 'quiz:q1:v:q1v1:version',
      // F-F11b: the content each next one replaces is cleared before it (here a small one: one step each).
      'quiz:q1:v:q1v2:clear:1', 'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version',
      'quiz:q1:c:clear:1', 'quiz:q1:c:content',
      'publication:1',
    ])
    const [, , first, batch, , , second, , , current, publication] = plan.steps
    expect(first).toMatchObject({ into: null, topics: { topics: ['coffee'], customTopics: [] } })
    expect(first.kind === 'content' && (first.document.questions as unknown[]).length).toBe(2)
    expect(batch.kind === 'batch' && (batch.document.questions as unknown[]).length).toBe(1)
    expect(second).toMatchObject({ into: 'quiz:q1:v:q1v1:content' })
    expect(current).toMatchObject({ into: 'quiz:q1:v:q1v1:content', topics: { customTopics: ['beans'] } })
    expect(publication).toMatchObject({ quizStep: 'quiz:q1:v:q1v1:content', versionStep: 'quiz:q1:v:q1v1:version',
      node: { treeStep: 'tree:1', nodeKey: 'n1' } })
    expect(plan.makes).toEqual({ profile: true, trees: 1, quizzes: 1, versions: 2, publications: 1 })
    expect(plan.videosAgain).toEqual(['Spring'])
  })

  it('leaves the current content out when it is the last version\'s, and points out titles the account has', () => {
    const document = packageOf()
    document.quizzes[0].current = null
    const plan = planImport(document, context({ quizTitles: [' Coffee'] }))
    expect(plan.steps.map((s) => s.key)).not.toContain('quiz:q1:c:content')
    expect(plan.sameTitles).toEqual(['Coffee'])
  })

  it('binds to the account\'s own map by node key, else by name, and lists what it cannot bind', () => {
    const own = { ...STATE, submittedTree: true, activeTrees: 1 }
    const byKey = planImport(packageOf(), context({ state: own, ownNodes: [{ treeId: 'T', nodeId: 'n1',
      name: { en: 'Other' } }] }))
    expect(byKey.steps.some((s) => s.kind === 'tree')).toBe(false)
    expect(byKey.treesKept).toBe(true)
    expect(byKey.steps.at(-1)).toMatchObject({ node: { treeId: 'T', nodeId: 'n1' } })
    const byName = planImport(packageOf(), context({ state: own, ownNodes: [{ treeId: 'T', nodeId: 'x',
      name: { en: 'Basics' } }] }))
    expect(byName.steps.at(-1)).toMatchObject({ node: { treeId: 'T', nodeId: 'x' } })
    const none = planImport(packageOf(), context({ state: own, ownNodes: [] }))
    expect(none.unbound).toEqual(['Spring'])
    expect(none.steps.at(-1)).toMatchObject({ node: null })
  })

  it('skips maps over the limit and quizzes over the bank\'s limit, and imports only the profile when asked', () => {
    const full = planImport(packageOf(), context({ state: { ...STATE, activeTrees: 1 } }))
    expect(full.treesSkipped).toEqual(['Coffee'])
    expect(full.unbound).toEqual(['Spring'])
    const large = planImport(packageOf(), context({ maxQuestions: 2 }))
    expect(large.tooLarge).toEqual(['Coffee'])
    expect(large.orphans).toEqual(['Spring'])
    const profile = planImport({ ...packageOf(), profile: { format: 'quizbuks-profile' } as never },
      context({ mode: 'PROFILE' }))
    expect(profile.steps.map((s) => s.key)).toEqual(['profile'])
  })

  it('keeps a first batch\'s materials and heading translations, and only its questions\' files', () => {
    const file = { ...quizFile('Big', 3), materials: [{ kind: 'IMAGES', images: [{ image: 'm' }] }],
      questions: [{ stem: 'a', image: 'f-a' }, { stem: 'b' }, { stem: 'c', image: 'f-c' }],
      translations: { ja: { texts: { title: '大', 'q1.stem': 'あ', 'q3.stem': 'う' }, images: {}, hashes: {} } },
      files: { m: {}, 'f-a': {}, 'f-c': {} } }
    const first = firstBatch(file, 2)
    expect((first.questions as unknown[]).length).toBe(2)
    expect((first.translations as Record<string, { texts: Record<string, string> }>).ja.texts)
      .toEqual({ title: '大', 'q1.stem': 'あ' })
    expect([...usedFiles(first)].sort()).toEqual(['f-a', 'm'])
  })
})

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('running an import', () => {
  it('goes on where it stopped: the steps done are skipped and their results name what later steps use', async () => {
    const steps: Record<string, unknown>[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin),
        init)
      const path = decodeURIComponent(new URL(request.url).pathname)
      const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
      if (path === '/api/processes/QB_PACKAGE_IMPORT_START/latest') {
        expect(body).toMatchObject({ packageHash: 'h', formatVersion: 2, mode: 'FULL', batchSize: 2, stepsTotal: 10 })
        return json(200, { output: { importId: 'imp', mode: 'FULL', status: 'RUNNING', batchSize: 2, batchBytes: null,
          stepsTotal: 10,
          stepsDone: 2, resumed: true, done: {
            'tree:1': { treeId: 'T1', nodeIds: { n1: 'node-1', n2: 'node-2' } },
            'quiz:q1:v:q1v1:content': { quizId: 'Q1' } } } })
      }
      if (path === '/api/processes/QB_PACKAGE_IMPORT_STEP/latest') {
        steps.push(body as Record<string, unknown>)
        const step = (body as { step: string }).step
        const result = step.endsWith(':version') ? { quizId: 'Q1', versionId: `V-${step}` }
          : step.startsWith('publication') ? { publicationId: 'P1' } : { quizId: 'Q1' }
        return json(200, { output: { importId: 'imp', step, status: 'RUNNING', stepsDone: 3, stepsTotal: 10, result,
          repeated: false } })
      }
      return json(404, {})
    })
    const document = packageOf()
    const plan = planImport(document, context({ sharedVideos: new Set(['sv-1']) }))
    const checks = { profile: null, mailPassword: '', sharedVideos: new Set(['sv-1']), quizzes: {
      'q1:q1v1': { files: [] }, 'q1:q1v2': { files: [] }, 'q1:current': { files: [] } } as never }
    const progress: string[] = []
    const run = await runImport({ document, hash: 'h', plan, checks, now: new Date('2026-11-09T10:20:00Z'),
      onProgress: (step, _i, _n, phase) => phase === 'done' && progress.push(step.key) })
    expect(steps.map((s) => s.step)).toEqual(['quiz:q1:v:q1v1:batch:2', 'quiz:q1:v:q1v1:version',
      'quiz:q1:v:q1v2:clear:1', 'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version', 'quiz:q1:c:clear:1',
      'quiz:q1:c:content', 'publication:1'])
    expect(progress).toHaveLength(10)
    expect(steps[0]).toMatchObject({ importId: 'imp', questions: { quizId: 'Q1' } })
    // The clearing steps name the quiz this import made and the most rows a step takes.
    expect(steps[2]).toMatchObject({ clear: { quizId: 'Q1', rows: CLEAR_ROWS } })
    expect(steps[3]).toMatchObject({ quiz: { intoQuizId: 'Q1' }, quizTopics: { topics: [], customTopics: [] } })
    expect(steps[7]).toMatchObject({ publication: {
      save: { publicationId: null, versionId: 'V-quiz:q1:v:q1v1:version', treeId: 'T1', treeNodeId: 'node-1',
        name: 'Spring', startAt: '2026-11-09T11:00:00.000Z', endAt: '2026-11-16T11:00:00.000Z' },
      topics: { customAdd: ['latte'] }, sharedVideoId: 'sv-1', criteria: null } })
    expect(run).toMatchObject({ importId: 'imp' })
    expect(Object.keys(run.results)).toHaveLength(10)
  })
})

/** A quiz file of `count` questions of `options` options each, each text `size` characters long. */
function bigFile(count: number, options: number, size: number, extra: Record<string, unknown> = {}) {
  const text = (n: number, what: string) => `${what}${n} ${'x'.repeat(size)}`
  return { ...quizFile('Big', 0), quiz: { kind: 'QUIZ', title: 'Big', language: 'en' },
    questions: Array.from({ length: count }, (_, i) => ({ stem: text(i + 1, 'Q'), image: null, points: 1,
      selection: null, options: Array.from({ length: options }, (_, o) => ({ text: text(o + 1, 'O'), image: null,
        correct: o === 0 })) })), ...extra } as Record<string, unknown>
}

describe('the steps that clear a content, and the cuts by bytes (F-F11b)', () => {
  it('counts the parts a file makes: materials and their images, questions and options, every translation', () => {
    const file = { ...quizFile('T', 2), materials: [{ kind: 'IMAGES', images: [{ image: 'a' }, { image: 'b' }] },
      { kind: 'ARTICLE' }],
    translations: { ja: { texts: { title: 'a', 'q1.stem': 'b' }, images: { 'q1.image': 'f' }, materials: [{ no: 1 }],
      hashes: { title: 'h' } } } }
    // 2 materials + 2 images, 2 questions + 4 options, 2 texts + 1 image + 1 material translated.
    expect(partsOf(file)).toBe(2 + 2 + 2 + 4 + 2 + 1 + 1)
    expect(partsOf({})).toBe(0)
  })

  it('plans ceil(parts / 2000) clearing steps before each content that replaces one, naming the new content', () => {
    // 1000 questions of 3 options: 4000 parts, so two steps; the next content has two questions.
    const document = packageOf()
    document.quizzes[0].versions = [
      { key: 'q1v1', label: 'v1', file: bigFile(1000, 3, 1) as never },
      { key: 'q1v2', label: 'v2', file: { ...quizFile('Big', 2) } as never }]
    document.quizzes[0].current = null
    const plan = planImport(document, context({ batchSize: 1000, maxQuestions: 1000 }))
    const keys = plan.steps.map((s) => s.key).filter((key) => key.startsWith('quiz:'))
    expect(keys).toEqual(['quiz:q1:v:q1v1:content', 'quiz:q1:v:q1v1:version', 'quiz:q1:v:q1v2:clear:1',
      'quiz:q1:v:q1v2:clear:2', 'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version'])
    const clear = plan.steps.find((s) => s.key === 'quiz:q1:v:q1v2:clear:2')
    expect(clear).toMatchObject({ kind: 'clear', clear: 2, clears: 2, quizStep: 'quiz:q1:v:q1v1:content' })
    // The step that follows says how many cleared before it (a refusal there leaves the draft empty).
    expect(plan.steps.find((s) => s.key === 'quiz:q1:v:q1v2:content')).toMatchObject({ clears: 2 })
    // The first content replaces nothing.
    expect(plan.steps.find((s) => s.key === 'quiz:q1:v:q1v1:content')).toMatchObject({ clears: 0 })
  })

  it('cuts the first step too: heading and materials with as many questions as both limits allow', () => {
    const file = bigFile(10, 2, 200, { materials: [{ kind: 'ARTICLE', title: 'M', body: 'b'.repeat(3000) }] })
    const bytes = JSON.stringify(firstBatch(file, 4)).length + 50
    const cut = planContent(file, 10, bytes)
    const count = (document: Record<string, unknown>) => (document.questions as unknown[]).length
    expect(count(cut.first)).toBeGreaterThan(0)
    expect(count(cut.first)).toBeLessThan(10)
    expect(count(cut.first) + cut.batches.reduce((sum, batch) => sum + count(batch), 0)).toBe(10)
    // The first step keeps the materials; the batches have none.
    expect((cut.first.materials as unknown[]).length).toBe(1)
    expect(cut.batches.every((batch) => (batch.materials as unknown[]).length === 0)).toBe(true)
    // Every piece is within the limit; nothing is reported.
    for (const piece of [cut.first, ...cut.batches]) expect(new TextEncoder().encode(JSON.stringify(piece)).length).toBeLessThanOrEqual(bytes)
    expect(cut.oversized).toEqual([])
    // Without a byte limit it is the cut by questions it always was.
    const plain = planContent(file, 4, 0)
    expect(count(plain.first)).toBe(4)
    expect(plain.batches.map(count)).toEqual([4, 2])
  })

  it('reports what no request can take: a question alone over the limit, or the heading and materials', () => {
    const file = bigFile(3, 2, 20)
    ;(file.questions as { stem: string }[])[1].stem = 'y'.repeat(20_000)
    const limit = 5000
    const cut = planContent(file, 10, limit)
    expect(cut.oversized).toEqual([2])
    const heavy = bigFile(2, 2, 20, { materials: [{ kind: 'ARTICLE', title: 'M', body: 'b'.repeat(20_000) }] })
    expect(planContent(heavy, 10, limit).oversized).toContain(null)
    const plan = planImport({ ...packageOf(), quizzes: [{ ...packageOf().quizzes[0], versions: [],
      current: file as never }] }, context({ batchSize: 10, batchBytes: limit }))
    expect(plan.oversized).toEqual([{ title: 'Big', question: 2 }])
    expect(plan.batchBytes).toBe(limit)
  })

  it('puts the cuts into the steps: the batches are numbered from 2 as the server keys them', () => {
    const document = { ...packageOf(), quizzes: [{ ...packageOf().quizzes[0], versions: [],
      current: bigFile(6, 2, 300) as never }] }
    const limit = JSON.stringify(firstBatch(bigFile(6, 2, 300), 2)).length + 60
    const plan = planImport(document, context({ batchSize: 6, batchBytes: limit }))
    const keys = plan.steps.map((s) => s.key).filter((key) => key.startsWith('quiz:'))
    expect(keys[0]).toBe('quiz:q1:c:content')
    expect(keys.length).toBeGreaterThan(2)
    keys.slice(1).forEach((key, i) => expect(key).toBe(`quiz:q1:c:batch:${i + 2}`))
    const last = plan.steps.filter((s) => s.kind === 'batch').at(-1)
    expect(last).toMatchObject({ batches: keys.length })
  })

  it('merges the checks of the pieces: the files once each, the questions summed', () => {
    const a = { formatVersion: 3, currentVersion: 3, readers: [3], kind: 'QUIZ', title: 'T', questions: 2, materials: 1,
      files: [{ key: 'f1' }, { key: 'f2' }], language: 'en', languages: [] }
    const b = { ...a, questions: 3, materials: 0, files: [{ key: 'f2' }, { key: 'f3' }] }
    const merged = mergeChecks([a, b] as never)
    expect(merged.questions).toBe(5)
    expect(merged.files.map((f) => f.key)).toEqual(['f1', 'f2', 'f3'])
    expect(mergeChecks([a] as never)).toBe(a)
  })
})

describe('starting an import with the bytes (F-F11b)', () => {
  function server(view: Record<string, unknown>) {
    const started: Record<string, unknown>[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
      const path = decodeURIComponent(new URL(request.url).pathname)
      const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
      if (path === '/api/processes/QB_PACKAGE_IMPORT_START/latest') {
        started.push(body as Record<string, unknown>)
        return json(200, { output: { importId: 'imp', mode: 'PROFILE', status: 'RUNNING', stepsTotal: 1, stepsDone: 0,
          resumed: false, done: {}, ...view } })
      }
      return json(404, {})
    })
    return started
  }
  const profileOnly = (more: Partial<PlanContext>) => ({ document: { ...packageOf(), profile: null }, hash: 'h',
    checks: { profile: null, mailPassword: '', sharedVideos: new Set<string>(), quizzes: {} },
    plan: planImport({ ...packageOf(), profile: null }, context({ mode: 'PROFILE', ...more })) })

  it('sends the bytes the plan was cut by, and none when it was cut by the questions alone', async () => {
    const started = server({ batchSize: 2, batchBytes: 65_536 })
    await runImport(profileOnly({ batchBytes: 65_536 }))
    expect(started[0]).toMatchObject({ batchSize: 2, batchBytes: 65_536 })
    started.length = 0
    const none = server({ batchSize: 2, batchBytes: null })
    await runImport(profileOnly({ batchBytes: 0 }))
    expect(none[0]).not.toHaveProperty('batchBytes')
  })

  it('goes on with the batches and the bytes the import began with, whatever the limits are now', async () => {
    const started = server({ batchSize: 5, batchBytes: 100_000, resumed: true })
    const replan = vi.fn((batchSize: number, batchBytes: number) => planImport({ ...packageOf(), profile: null },
      context({ mode: 'PROFILE', batchSize, batchBytes })))
    await runImport({ ...profileOnly({ batchBytes: 65_536 }), replan })
    expect(replan).toHaveBeenCalledWith(5, 100_000)
    // Started again with the recorded numbers, so that the record is the one it began with.
    expect(started.at(-1)).toMatchObject({ batchSize: 5, batchBytes: 100_000 })
  })

  it('goes on with an import begun before the bytes were kept as one cut by the questions alone', async () => {
    server({ batchSize: 2, batchBytes: null, resumed: true })
    const replan = vi.fn((batchSize: number, batchBytes: number) => planImport({ ...packageOf(), profile: null },
      context({ mode: 'PROFILE', batchSize, batchBytes })))
    await runImport({ ...profileOnly({ batchBytes: 65_536 }), replan })
    expect(replan).toHaveBeenCalledWith(2, 0)
  })
})
