import { ApiError, configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import type { Draft } from './api'
import {
  base64Of,
  blobOf,
  exportDraft,
  exportFileName,
  importQuiz,
  MissingFileData,
  QUIZ_FILE_FORMAT,
  withoutData,
  type ImportCheck,
} from './transfer'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const QUIZ = {
  quizId: 'q1', ownerId: 'u2', title: 'Coffee basics', intro: 'About coffee.', cover: 'file-cover', timeLimitSec: 300,
  status: 'DRAFT', kind: 'QUIZ', removed: false, aiGenerated: false, latestVersionNo: 0, versionLabel: null,
  revision: 7, editedAt: '2026-10-14T01:00:00Z', changedSinceVersion: true, questionCount: 1, materialCount: 0,
  clonedFrom: null,
}

const DRAFT: Draft = {
  quiz: { quizId: 'q1', title: 'Coffee basics', kind: 'QUIZ', intro: 'About coffee.', cover: 'file-cover',
    timeLimitSec: 300, status: 'DRAFT', latestVersionNo: 0, versionLabel: null, revision: 7,
    editedAt: '2026-10-14T01:00:00Z', changedSinceVersion: true, questionCount: 2, materialCount: 1, clonedFrom: null },
  questions: [
    { questionId: 'k1', stem: 'First?', image: 'file-a', points: 2, selection: null, options: [
      { optionId: 'o1', text: 'Coffee', image: null, correct: true },
      { optionId: 'o2', text: 'Tea', image: 'file-cover', correct: false }] },
    { questionId: 'k2', stem: 'Second?', image: null, points: 1, selection: null, options: [] },
  ],
  materials: [{ materialId: 'm1', kind: 'PDF', title: 'Paper', description: null, body: null, url: null,
    pdf: 'file-pdf', audio: null, images: [] }],
}

const CHECK: ImportCheck = { formatVersion: 1, currentVersion: 1, readers: [1], kind: 'QUIZ', title: 'Coffee basics',
  questions: 2, materials: 1, files: [
    { key: 'f1', policy: 'qb.content.image', name: 'image.jpg', type: 'image/jpeg', size: 3 },
    { key: 'f2', policy: 'qb.content.pdf', name: 'pdf.pdf', type: 'application/pdf', size: 3 }] }

interface Server {
  /** Answers to processes by name, in turn (the last one repeats). */
  processes?: Record<string, { status: number; body: unknown }[]>
  /** Answers to uploads, in turn (the last one repeats). */
  uploads?: { status: number; body: unknown }[]
}

/** The sponsor console's server with one quiz and its cover; records posts and uploads. */
function server(options: Server = {}) {
  const posted: { path: string; body: unknown }[] = []
  const uploads: { policy: string | null; name: string; type: string }[] = []
  const turns = new Map<string, number>()
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : String(input), window.location.origin)
    const path = decodeURIComponent(url.pathname)
    // An upload's form, read before a Request is made of it (jsdom's cannot hold one).
    if (path === '/api/files' && init?.method === 'POST') {
      const file = (init.body as FormData).get('file') as File
      uploads.push({ policy: url.searchParams.get('policy'), name: file.name, type: file.type })
      const answers = options.uploads ?? [{ status: 201, body: {} }]
      const answer = answers[Math.min(uploads.length - 1, answers.length - 1)]
      return json(answer.status, answer.status === 201 ? { fileId: `up-${uploads.length}` } : answer.body)
    }
    const request = input instanceof Request ? input : new Request(url, init)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)
    if (process) {
      const answers = options.processes?.[process[1]] ?? [{ status: 404, body: {} }]
      const turn = turns.get(process[1]) ?? 0
      turns.set(process[1], turn + 1)
      const answer = answers[Math.min(turn, answers.length - 1)]
      return json(answer.status, answer.body)
    }
    const rows = (items: Record<string, unknown>[]) =>
      json(200, { items: items.map((attributes, i) => ({ id: String(i), version: 1, attributes })), offset: 0,
        limit: 100 })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.content.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return rows([{ contactName: 'Sara', company: 'Acme', reviewStatus: 'APPROVED', revision: 1 }])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbQuiz/entities/q1':
        return json(200, { id: 'q1', version: 1, attributes: QUIZ })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbQuestion/query':
        return rows([{ questionId: 'k1', quizId: 'q1', seq: 1, stem: 'First?', image: null, points: 2 }])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbOption/query':
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterial/query':
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterialImage/query':
        return rows([])
      case '/api/files/file-cover/content':
        return new Response(new Uint8Array([1, 2, 3]), { headers: { 'Content-Type': 'image/png' } })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:quiz-status':
        return json(200, [{ code: 'DRAFT', label: 'Draft' }, { code: 'VERSIONED', label: 'Versioned' }])
      case '/api/queries/qb.sponsor.quizzes':
        return json(200, { items: [{ ...QUIZ, publicationCount: 0, published: false }], offset: 0, limit: 20,
          total: 1 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { processCalls, uploads }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the quiz file helpers', () => {
  it('export the draft with each file once, keyed in the order of first use', async () => {
    const read = vi.fn(async (id: string) => new Blob([id], { type: id === 'file-pdf' ? 'application/pdf'
      : 'image/jpeg' }))
    const progress: number[][] = []
    const document = await exportDraft(DRAFT, (done, total) => progress.push([done, total]), read)
    expect(document.format).toBe(QUIZ_FILE_FORMAT)
    expect(document.formatVersion).toBe(1)
    expect(document.quiz).toEqual({ kind: 'QUIZ', title: 'Coffee basics', intro: 'About coffee.',
      timeLimitSec: 300, cover: 'f1' })
    expect(document.materials[0].pdf).toBe('f2')
    expect(document.questions[0].image).toBe('f3')
    // The cover again, the same key.
    expect(document.questions[0].options[1].image).toBe('f1')
    expect(Object.keys(document.files)).toEqual(['f1', 'f2', 'f3'])
    expect(document.files.f1).toEqual({ name: 'image.jpg', type: 'image/jpeg', size: 10,
      data: btoa('file-cover') })
    expect(document.files.f2.name).toBe('pdf.pdf')
    expect(read).toHaveBeenCalledTimes(3)
    expect(progress.at(-1)).toEqual([3, 3])
    // No ids, owners or revisions of this system.
    expect(JSON.stringify(document)).not.toMatch(/quizId|questionId|ownerId|revision/)
  })

  it('round-trip the bytes, leave the data out for the server and name the file after the title', async () => {
    // More than one piece, and not a multiple of 3.
    const bytes = new Uint8Array(70_001).map((_, i) => i % 256)
    const encoded = await base64Of(new Blob([bytes]))
    expect(new Uint8Array(await blobOf(encoded, 'image/png').arrayBuffer())).toEqual(bytes)
    expect(encoded).toBe(btoa(String.fromCharCode(...bytes)))
    expect(withoutData({ format: 'x', files: { f1: { name: 'a', type: 'b', size: 1, data: 'AAAA' } } }))
      .toEqual({ format: 'x', files: { f1: { name: 'a', type: 'b', size: 1 } } })
    expect(exportFileName('Coffee: basics / 101')).toBe('Coffee-basics-101.quizbuks.json')
    expect(exportFileName('珈琲の基本')).toBe('珈琲の基本.quizbuks.json')
    expect(exportFileName('!!!')).toBe('quiz.quizbuks.json')
  })

  it('upload each needed file under its policy, wait out the upload limit, then import without the data', async () => {
    const { uploads, processCalls } = server({
      uploads: [{ status: 201, body: {} }, { status: 429, body: { status: 429, violations: [
        { field: null, ruleCode: 'RATE_LIMITED', message: 'Too many' }] } }, { status: 201, body: {} }],
      processes: { QB_QUIZ_IMPORT: [{ status: 200, body: { output: { quizId: 'q9', formatVersion: 1,
        readers: [1] } } }] },
    })
    const wait = vi.fn(async () => undefined)
    const document = { format: QUIZ_FILE_FORMAT, formatVersion: 1, quiz: { title: 'Coffee basics' },
      files: { f1: { name: 'x.jpg', type: 'image/jpeg', size: 3, data: btoa('abc') },
        f2: { name: 'y.pdf', type: 'application/pdf', size: 3, data: btoa('pdf') } } }
    const imported = await importQuiz(document, CHECK, undefined, wait)
    expect(imported.quizId).toBe('q9')
    expect(uploads.map((u) => `${u.policy} ${u.name}`)).toEqual(['qb.content.image image.jpg',
      'qb.content.pdf pdf.pdf', 'qb.content.pdf pdf.pdf'])
    expect(wait).toHaveBeenCalledTimes(1)
    expect(processCalls('QB_QUIZ_IMPORT')).toEqual([{ document: { ...document, files: {
      f1: { name: 'x.jpg', type: 'image/jpeg', size: 3 }, f2: { name: 'y.pdf', type: 'application/pdf', size: 3 } } },
      files: { f1: 'up-1', f2: 'up-3' } }])
  })

  it('refuse a file without the data of a file it needs, and pass other refusals on', async () => {
    const { uploads } = server()
    // The second file has no data: nothing is uploaded, not even the first.
    await expect(importQuiz({ files: { f1: { data: btoa('x') } } }, CHECK, undefined, vi.fn()))
      .rejects.toBeInstanceOf(MissingFileData)
    expect(uploads).toEqual([])
    vi.restoreAllMocks()
    server({ uploads: [{ status: 422, body: { status: 422, violations: [
      { field: 'file', ruleCode: 'FILE_TYPE_NOT_ALLOWED', message: 'No' }] } }] })
    await expect(importQuiz({ files: { f1: { data: btoa('x') }, f2: { data: btoa('y') } } }, CHECK, undefined,
      vi.fn())).rejects.toBeInstanceOf(ApiError)
  })
})

describe('the quiz list', () => {
  it('exports a quiz as a downloaded file', async () => {
    server()
    const created: Blob[] = []
    vi.spyOn(URL, 'createObjectURL').mockImplementation((blob) => {
      created.push(blob as Blob)
      return 'blob:quiz'
    })
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const row = await screen.findByTestId('quiz-row')
    await userEvent.click(within(row).getByRole('button', { name: 'Export Coffee basics' }))
    await waitFor(() => expect(click).toHaveBeenCalled())
    // The list's cover thumbnail makes an object URL too: the download is the JSON one.
    const document = JSON.parse(await created.find((blob) => blob.type === 'application/json')!.text())
    expect(document).toMatchObject({ format: QUIZ_FILE_FORMAT, formatVersion: 1, quiz: { title: 'Coffee basics',
      cover: 'f1' }, questions: [{ stem: 'First?', points: 2 }] })
    expect(document.files.f1).toMatchObject({ type: 'image/png', size: 3, data: btoa('\x01\x02\x03') })
  })

  it('imports a quiz file after the server read it, and opens the new draft', async () => {
    const { processCalls, uploads } = server({ processes: {
      QB_QUIZ_IMPORT_CHECK: [{ status: 200, body: { output: { ...CHECK, readers: [1], files: [CHECK.files[0]] } } }],
      QB_QUIZ_IMPORT: [{ status: 200, body: { output: { quizId: 'q1', formatVersion: 1, readers: [1] } } }],
    } })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const file = new File([JSON.stringify({ format: QUIZ_FILE_FORMAT, formatVersion: 1, quiz: { title: 'Coffee' },
      files: { f1: { name: 'a.jpg', type: 'image/jpeg', size: 3, data: btoa('abc') } } })], 'quiz.json',
    { type: 'application/json' })
    await screen.findByTestId('quiz-row')
    await userEvent.upload(screen.getByTestId('quiz-import-file'), file)
    expect(await screen.findByTestId('quiz-import-summary')).toHaveTextContent(
      'Coffee basics — questions: 2, materials: 1, files: 1.')
    expect(screen.getByTestId('quiz-import-version')).toHaveTextContent('Format version 1')
    // The server checks the file without the data.
    expect(JSON.stringify(processCalls('QB_QUIZ_IMPORT_CHECK'))).not.toContain(btoa('abc'))
    await userEvent.click(screen.getByTestId('quiz-import-confirm'))
    await waitFor(() => expect(window.location.pathname).toBe('/quizzes/q1'))
    expect(uploads).toEqual([{ policy: 'qb.content.image', name: 'image.jpg', type: 'image/jpeg' }])
    expect(processCalls('QB_QUIZ_IMPORT')).toHaveLength(1)
  })

  it('lists why a file cannot be imported', async () => {
    server({ processes: { QB_QUIZ_IMPORT_CHECK: [{ status: 422, body: { status: 422, violations: [
      { field: null, ruleCode: 'QB_QUIZ_FILE_UNSUPPORTED', message: 'This file cannot be imported (version 3).' },
      { field: 'questions[0].stem', ruleCode: 'QB_QUIZ_FILE_VALUE', message: 'Too long.' }] } }] } })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    await screen.findByTestId('quiz-row')
    await userEvent.upload(screen.getByTestId('quiz-import-file'), new File(['{"format":"quizbuks.quiz"}'],
      'quiz.json', { type: 'application/json' }))
    const problems = await screen.findByTestId('quiz-import-problems')
    expect(problems).toHaveTextContent('This file cannot be imported (version 3).')
    expect(problems).toHaveTextContent('questions[0].stem: Too long.')
    expect(screen.queryByTestId('quiz-import-confirm')).toBeNull()

    // Behind the open dialog: the hidden input is still the one the dialog's "choose a file" uses.
    await userEvent.setup({ pointerEventsCheck: 0 }).upload(screen.getByTestId('quiz-import-file'),
      new File(['not json'], 'x.json'))
    expect(await screen.findByText('This file is not a quiz file (it is not JSON).')).toBeInTheDocument()
  })
})
