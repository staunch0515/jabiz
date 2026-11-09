import { configureClient, session } from '@jabiz/client'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VersionDetail } from '../content/api'
import { exportVersion, type QuizFileDocument } from '../content/transfer'
import { parseSettings, type PublicationSettings } from '../publishing/settings'
import {
  buildPackage,
  firstBatch,
  PACKAGE_FORMAT,
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
      'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version',
      'quiz:q1:c:content',
      'publication:1',
    ])
    const [, , first, batch, , second, , current, publication] = plan.steps
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
        expect(body).toMatchObject({ packageHash: 'h', formatVersion: 2, mode: 'FULL', batchSize: 2, stepsTotal: 8 })
        return json(200, { output: { importId: 'imp', mode: 'FULL', status: 'RUNNING', batchSize: 2, stepsTotal: 8,
          stepsDone: 2, resumed: true, done: {
            'tree:1': { treeId: 'T1', nodeIds: { n1: 'node-1', n2: 'node-2' } },
            'quiz:q1:v:q1v1:content': { quizId: 'Q1' } } } })
      }
      if (path === '/api/processes/QB_PACKAGE_IMPORT_STEP/latest') {
        steps.push(body as Record<string, unknown>)
        const step = (body as { step: string }).step
        const result = step.endsWith(':version') ? { quizId: 'Q1', versionId: `V-${step}` }
          : step.startsWith('publication') ? { publicationId: 'P1' } : { quizId: 'Q1' }
        return json(200, { output: { importId: 'imp', step, status: 'RUNNING', stepsDone: 3, stepsTotal: 8, result,
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
      'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version', 'quiz:q1:c:content', 'publication:1'])
    expect(progress).toHaveLength(8)
    expect(steps[0]).toMatchObject({ importId: 'imp', questions: { quizId: 'Q1' } })
    expect(steps[2]).toMatchObject({ quiz: { intoQuizId: 'Q1' }, quizTopics: { topics: [], customTopics: [] } })
    expect(steps[5]).toMatchObject({ publication: {
      save: { publicationId: null, versionId: 'V-quiz:q1:v:q1v1:version', treeId: 'T1', treeNodeId: 'node-1',
        name: 'Spring', startAt: '2026-11-09T11:00:00.000Z', endAt: '2026-11-16T11:00:00.000Z' },
      topics: { customAdd: ['latte'] }, sharedVideoId: 'sv-1', criteria: null } })
    expect(run).toMatchObject({ importId: 'imp' })
    expect(Object.keys(run.results)).toHaveLength(8)
  })
})
