import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import { bundle, keyPaths } from '../qb/messageKeys'
import { SubmitAllDraftsButton } from '../publishing/SubmitAllDrafts'
import { SPONSOR } from '../messages'
import { packageMessages } from './packageMessages'
import { OpenImportNotice } from './SponsorPackage'
import { TransferMenu, TransferTools, useAbandonImport } from './TransferMenu'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const QUIZ_FILE = { format: 'quizbuks.quiz', formatVersion: 3, exportedAt: '2026-11-09T00:00:00Z',
  quiz: { kind: 'QUIZ', title: 'Coffee', intro: null, timeLimitSec: null, cover: null, language: 'en' },
  materials: [], questions: [{ stem: 'Beans?', image: null, points: 1, selection: null,
    options: [{ text: 'Yes', image: null, correct: true }, { text: 'No', image: null, correct: false }] }],
  files: {} }

const SETTINGS = { format: 'quizbuks.publication-settings', version: 1, name: 'Spring', fullScore: 1, durationDays: 7,
  passScore: 1, budgetCap: 1000, maxWinners: 10, tiers: [{ fromScore: 0, toScore: 1, reward: 10 }],
  rewardMethod: 'PLATFORM', audience: 'PUBLIC', regions: ['GLOBAL'], languages: [], translations: {}, conditions: [] }

const PACKAGE = { format: 'quizbuks.sponsor-package', version: 2, exportedAt: '2026-11-09T00:00:00Z', profile: null,
  trees: [{ key: 't-src', name: { en: 'Coffee map' }, description: null, nodes: [
    { key: 'n1', name: { en: 'Basics' }, description: null, condition: { op: 'ALL', items: [{ passed: true }] } }] }],
  quizzes: [{ key: 'q1', versions: [{ key: 'q1v1', label: 'v1', file: QUIZ_FILE }], current: null }],
  publications: [{ quiz: 'q1', version: 'q1v1', settings: SETTINGS, node: { tree: 't-src', node: 'n1',
    name: { en: 'Basics' } }, topics: null, sharedVideo: 'sv-gone' }] }

const packageFile = (document: unknown = PACKAGE) => new File([JSON.stringify(document)], 'acme.quizbuks-sponsor.json',
  { type: 'application/json' })

interface Server {
  submittedPublication?: boolean
  open?: Record<string, unknown> | null
  /** Steps refused once (by key) with this message. */
  failOnce?: Record<string, string>
  /** The question bank's limits the server gives (`qb.sponsor.quiz-limits`). */
  limits?: Record<string, unknown>
}

function server({ submittedPublication = false, open = null, failOnce = {}, limits = {} }: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const done: Record<string, Record<string, unknown>> = {}
  const failing = { ...failOnce }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
          sessionPersistence: 'tab' })
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:me:QbPackageImport/query':
        return json(200, { items: open ? [{ id: 'imp', version: 1, attributes: open }] : [], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.package-state':
        return json(200, { items: [{ submittedPublication, submittedTree: false, activeTrees: 0, maxTrees: 1,
          confirmBytes: 104857600 }] })
      case '/api/queries/qb.sponsor.quiz-limits':
        return json(200, { items: [{ maxQuestions: 1000, maxPerAttempt: 100, importBatch: 200, ...limits }] })
      case '/api/queries/qb.sponsor.quizzes':
        return json(200, { items: [{ quizId: 'old', title: 'Coffee' }], total: 1 })
      case '/api/queries/qb.sponsor.shared-videos':
        return json(200, { items: [] })
      case '/api/processes/QB_QUIZ_IMPORT_CHECK/latest':
        return json(200, { output: { formatVersion: 3, currentVersion: 3, readers: [3], kind: 'QUIZ', title: 'Coffee',
          questions: 1, materials: 0, files: [], language: 'en', languages: [] } })
      case '/api/processes/QB_PACKAGE_IMPORT_START/latest':
        return json(200, { output: { importId: 'imp', mode: body?.mode, status: 'RUNNING', batchSize: 200,
          batchBytes: body?.batchBytes ?? null, stepsTotal: body?.stepsTotal, stepsDone: Object.keys(done).length, done: { ...done },
          resumed: Object.keys(done).length > 0 } })
      case '/api/processes/QB_PACKAGE_IMPORT_STEP/latest': {
        const step = String(body?.step)
        if (failing[step]) {
          const message = failing[step]
          delete failing[step]
          return json(422, { title: 'Refused', violations: [{ field: null, ruleCode: 'QB_X', message }] })
        }
        const result = step === 'profile' ? {} : step.startsWith('tree') ? { treeId: 'T1', nodeIds: { n1: 'node-1' } }
          : step.endsWith(':version') ? { quizId: 'Q1', versionId: 'V1' }
            : step.startsWith('publication') ? { publicationId: 'P1' } : { quizId: 'Q1' }
        done[step] = result
        return json(200, { output: { importId: 'imp', step, status: 'RUNNING', stepsDone: Object.keys(done).length,
          stepsTotal: 4, result, repeated: false } })
      }
      case '/api/processes/QB_PROFILE_IMPORT_CHECK/latest':
        return json(200, { output: { plan: { basics: 'UPDATE', page: 'UPDATE', added: [], changed: [], deleted: [],
          mailServer: 'NONE', files: [], linked: [], links: [
            { path: 'page.cover', url: 'https://shop.example/cover.jpg', policy: 'qb.sponsor-page-image' },
            { path: 'page.gallery.0', url: 'https://shop.example/gone.jpg', policy: 'qb.sponsor-page-image' }] } } })
      case '/api/files/fetch':
        return String(body?.url).endsWith('gone.jpg')
          ? json(422, { title: 'Refused', detail: 'not an image' }) : json(201, { fileId: 'dl-cover' })
      case '/api/processes/QB_PACKAGE_IMPORT_ABANDON/latest':
        return json(200, { output: { importId: 'imp', status: 'ABANDONED', retiredTrees: ['T1'],
          deletedPublications: [], removedQuizzes: ['Q1'], keptQuizzes: [] } })
      default:
        return json(404, {})
    }
  })
  const steps = () => posted.filter((p) => p.path.endsWith('QB_PACKAGE_IMPORT_STEP/latest'))
    .map((p) => String(p.body.step))
  return { posted, steps }
}

function Notice() {
  return <OpenImportNotice onAbandon={useAbandonImport()} />
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><TransferTools><TransferMenu /><Notice /></TransferTools></QueryClientProvider>)
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the sponsor package card', () => {
  it('has every text in every language', () => {
    const english = keyPaths(packageMessages.en)
    expect(keyPaths(packageMessages.zh)).toEqual(english)
    expect(keyPaths(packageMessages.ja)).toEqual(english)
    expect(keyPaths(bundle(SPONSOR, 'en'))).toEqual(expect.arrayContaining(['package.title', 'submitAll.button']))
  })

  it('checks the whole package, shows what it makes, then imports step by step', async () => {
    const { steps } = server()
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile())
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    const plan = await within(dialog).findByTestId('sponsor-package-plan')
    expect(plan).toHaveAttribute('data-mode', 'FULL')
    expect(within(plan).getByTestId('sponsor-package-makes-trees')).toHaveTextContent('Knowledge maps, as drafts: 1')
    expect(within(plan).getByTestId('sponsor-package-makes-quizzes')).toHaveTextContent('Quizzes: 1, with 1 saved')
    expect(within(plan).getByTestId('sponsor-package-makes-publications')).toHaveTextContent('Publication drafts: 1')
    expect(within(plan).getByTestId('sponsor-package-sameTitles')).toHaveTextContent('Coffee')
    expect(within(plan).getByTestId('sponsor-package-videosAgain')).toHaveTextContent('Spring')
    expect(plan).toHaveTextContent('reviewed one by one')
    await expectAccessible()

    await userEvent.click(within(dialog).getByTestId('sponsor-package-confirm'))
    expect(await within(dialog).findByTestId('sponsor-package-done')).toHaveTextContent('submit your drafts')
    expect(within(dialog).getByTestId('submit-all-drafts')).toBeInTheDocument()
    expect(steps()).toEqual(['tree:1', 'quiz:q1:v:q1v1:content', 'quiz:q1:v:q1v1:version', 'publication:1'])
    await expectAccessible()
  })

  it('says at which step an import stopped, and goes on from there without doing a step twice', async () => {
    const { steps } = server({ failOnce: { 'publication:1': 'Entry fees are not offered at the moment' } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile())
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    await userEvent.click(await within(dialog).findByTestId('sponsor-package-confirm'))
    const failed = await within(dialog).findByTestId('sponsor-package-failed')
    expect(failed).toHaveTextContent('The import stopped at publication “Spring”')
    expect(failed).toHaveTextContent('Entry fees are not offered at the moment')
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('sponsor-package-continue'))
    await within(dialog).findByTestId('sponsor-package-done')
    expect(steps()).toEqual(['tree:1', 'quiz:q1:v:q1v1:content', 'quiz:q1:v:q1v1:version', 'publication:1',
      'publication:1'])
  })

  it('offers only the profile part to an account that submitted a publication', async () => {
    server({ submittedPublication: true })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile({ ...PACKAGE, profile: null }))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    const plan = await within(dialog).findByTestId('sponsor-package-plan')
    expect(plan).toHaveAttribute('data-mode', 'PROFILE')
    expect(within(plan).getByTestId('sponsor-package-profile-only')).toBeInTheDocument()
    expect(within(dialog).getByTestId('sponsor-package-confirm')).toHaveTextContent('Import only the profile part')
  })

  it('lists the images given as links, has the server download them and imports without one it refuses', async () => {
    const { posted, steps } = server({ submittedPublication: true })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'),
      packageFile({ ...PACKAGE, profile: { format: 'quizbuks.profile', basics: {}, files: {} } }))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    const plan = await within(dialog).findByTestId('sponsor-package-plan')
    expect(within(plan).getByTestId('sponsor-package-links')).toHaveTextContent('page.cover、page.gallery.0')
    await userEvent.click(within(dialog).getByTestId('sponsor-package-confirm'))
    await within(dialog).findByTestId('sponsor-package-done')
    expect(steps()).toEqual(['profile'])
    const step = posted.find((p) => p.path.endsWith('QB_PACKAGE_IMPORT_STEP/latest'))!
    expect((step.body.profile as { files: Record<string, string> }).files).toEqual({ 'page.cover': 'dl-cover' })
  })

  it('refuses another file before asking the server', async () => {
    const { posted } = server()
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile({ format: 'quizbuks.quiz' }))
    expect(await screen.findByTestId('sponsor-package-refused')).toHaveTextContent('not a sponsor package')
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile({ ...PACKAGE, version: 9 }))
    expect(await screen.findByTestId('sponsor-package-refused')).toHaveTextContent('newer version')
    expect(posted.some((p) => p.path.includes('QB_PACKAGE'))).toBe(false)
  })

  it('shows an import not finished and gives it up', async () => {
    const { posted } = server({ open: { importId: 'imp', mode: 'FULL', status: 'RUNNING', stepsDone: 2,
      stepsTotal: 5, startedAt: '2026-11-09T00:00:00Z' } })
    renderCard()
    expect(await screen.findByTestId('sponsor-package-open')).toHaveTextContent('2 of 5 steps')
    await userEvent.click(screen.getByTestId('sponsor-package-abandon'))
    await userEvent.click(await screen.findByRole('button', { name: 'Give up' }))
    await waitFor(() => expect(posted.some((p) => p.path.endsWith('QB_PACKAGE_IMPORT_ABANDON/latest')
      && p.body.importId === 'imp')).toBe(true))
  })
})

describe('large banks: the clearing steps and the byte limit (F-F11b)', () => {
  const TWO_VERSIONS = { ...PACKAGE, quizzes: [{ key: 'q1', versions: [
    { key: 'q1v1', label: 'v1', file: QUIZ_FILE }, { key: 'q1v2', label: 'v2', file: QUIZ_FILE }], current: null }] }

  it('says what a step takes by questions and bytes, from the numbers the server gives', async () => {
    server({ limits: { importBatchBytes: 3_145_728 } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile())
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    expect(await within(dialog).findByTestId('sponsor-bank-batch-info'))
      .toHaveTextContent('A step takes at most 200 questions or 3 MB of the file')
    expect(within(dialog).queryByTestId('sponsor-package-oversized')).toBeNull()
  })

  it('says nothing of bytes when the server gives none (an older server)', async () => {
    server()
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile())
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    await within(dialog).findByTestId('sponsor-package-plan')
    expect(within(dialog).queryByTestId('sponsor-bank-batch-info')).toBeNull()
  })

  it('clears the content a version replaces before the step that brings the next, and sends the bytes it was cut by', async () => {
    const { steps, posted } = server({ limits: { importBatchBytes: 3_145_728 } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile(TWO_VERSIONS))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    await userEvent.click(await within(dialog).findByTestId('sponsor-package-confirm'))
    await within(dialog).findByTestId('sponsor-package-done')
    expect(steps()).toEqual(['tree:1', 'quiz:q1:v:q1v1:content', 'quiz:q1:v:q1v1:version', 'quiz:q1:v:q1v2:clear:1',
      'quiz:q1:v:q1v2:content', 'quiz:q1:v:q1v2:version', 'publication:1'])
    const clear = posted.find((p) => p.body.step === 'quiz:q1:v:q1v2:clear:1')!
    expect(clear.body.clear).toEqual({ quizId: 'Q1', rows: 2000 })
    const start = posted.find((p) => p.path.endsWith('QB_PACKAGE_IMPORT_START/latest'))!
    expect(start.body).toMatchObject({ batchSize: 200, batchBytes: 3_145_728, stepsTotal: 7 })
  })

  it('tells that the draft is empty when the step that brings the new content is refused after the clearing', async () => {
    const { steps } = server({ failOnce: { 'quiz:q1:v:q1v2:content': 'Not now' } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile(TWO_VERSIONS))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    await userEvent.click(await within(dialog).findByTestId('sponsor-package-confirm'))
    const failed = await within(dialog).findByTestId('sponsor-package-failed')
    expect(failed).toHaveTextContent('Not now')
    expect(within(failed).getByTestId('sponsor-package-emptied')).toHaveTextContent('the draft is empty')
    await expectAccessible()
    // Continuing goes on from the content step: the clearing is not done again.
    await userEvent.click(within(dialog).getByTestId('sponsor-package-continue'))
    await within(dialog).findByTestId('sponsor-package-done')
    expect(steps().filter((step) => step.includes(':clear:'))).toEqual(['quiz:q1:v:q1v2:clear:1'])
  })

  it('has no such note when another step is refused', async () => {
    server({ failOnce: { 'publication:1': 'Not now' } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile(TWO_VERSIONS))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    await userEvent.click(await within(dialog).findByTestId('sponsor-package-confirm'))
    const failed = await within(dialog).findByTestId('sponsor-package-failed')
    expect(within(failed).queryByTestId('sponsor-package-emptied')).toBeNull()
  })

  it('does not start while a question alone is more than a request takes, and names it', async () => {
    const big = { ...QUIZ_FILE, questions: [QUIZ_FILE.questions[0], { ...QUIZ_FILE.questions[0],
      stem: 'x'.repeat(100_000) }] }
    const { posted } = server({ limits: { importBatchBytes: 65_536 } })
    renderCard()
    await userEvent.upload(screen.getByTestId('sponsor-package-file'), packageFile({ ...PACKAGE,
      quizzes: [{ key: 'q1', versions: [{ key: 'q1v1', label: 'v1', file: big }], current: null }] }))
    const dialog = await screen.findByTestId('sponsor-package-dialog')
    const oversized = await within(dialog).findByTestId('sponsor-package-oversized')
    expect(oversized).toHaveTextContent('Coffee')
    expect(oversized).toHaveTextContent('question 2')
    expect(within(dialog).getByTestId('sponsor-package-confirm')).toBeDisabled()
    expect(posted.some((p) => p.path.endsWith('QB_PACKAGE_IMPORT_START/latest'))).toBe(false)
    await expectAccessible()
  })
})

describe('submitting all drafts', () => {
  it('submits the maps never in effect and the draft publications, listing those skipped with why', async () => {
    const posted: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin),
        init)
      const path = decodeURIComponent(new URL(request.url).pathname)
      const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
      if (path.startsWith('/api/processes/')) posted.push(`${path.split('/')[3]} ${body?.publicationId ?? body?.treeId}`)
      switch (path) {
        case '/api/queries/qb.sponsor.package-trees':
          return json(200, { items: [{ treeId: 'T1', currentVersionId: null, versionId: 'TV1', status: 'DRAFT',
            name: '{"en":"Coffee map"}', submitted: false }, { treeId: 'T2', currentVersionId: 'TV0',
            versionId: 'TV0', status: 'ACTIVE', name: '{"en":"In effect"}', submitted: true }] })
        case '/api/queries/qb.sponsor.publications':
          return json(200, { items: [{ publicationId: 'P1', name: 'Spring', status: 'DRAFT' },
            { publicationId: 'P2', name: 'Summer', status: 'DRAFT' }], total: 2 })
        case '/api/processes/QB_TREE_SUBMIT/latest':
          return json(200, { output: { treeId: 'T1', status: 'REVIEW' } })
        case '/api/processes/QB_PUBLICATION_SUBMIT/latest':
          return body?.publicationId === 'P1'
            ? json(200, { output: { publicationId: 'P1', status: 'PENDING_REVIEW', revision: 2 } })
            : json(422, { title: 'Refused', violations: [{ field: null, ruleCode: 'QB_FUNDS_SHORT',
              message: 'Not enough Kudos in your wallet' }] })
        default:
          return json(404, {})
      }
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><SubmitAllDraftsButton /></QueryClientProvider>)
    await userEvent.click(screen.getByTestId('submit-all-drafts'))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit all' }))
    const results = await screen.findByTestId('submit-all-results')
    expect(posted).toEqual(['QB_TREE_SUBMIT T1', 'QB_PUBLICATION_SUBMIT P1', 'QB_PUBLICATION_SUBMIT P2'])
    expect(results).toHaveTextContent('2 submitted, 1 skipped')
    const rows = within(results).getAllByTestId('submit-all-result')
    expect(rows.map((row) => row.getAttribute('data-ok'))).toEqual(['true', 'true', 'false'])
    expect(rows[2]).toHaveTextContent('Skipped: Not enough Kudos in your wallet')
    await expectAccessible()
  })
})
