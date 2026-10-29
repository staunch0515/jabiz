import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const QUIZ = {
  quizId: 'q1', ownerId: 'u2', title: 'Coffee basics', intro: 'About **coffee**.', cover: null, timeLimitSec: 300,
  status: 'DRAFT', removed: false, aiGenerated: false, latestVersionNo: 0, versionLabel: null, revision: 7,
  editedAt: '2026-10-14T01:00:00Z', changedSinceVersion: true, questionCount: 2, materialCount: 1, clonedFrom: null,
}
const QUESTIONS = [
  { questionId: 'k2', quizId: 'q1', seq: 2, stem: 'Second?', image: null, points: 1 },
  { questionId: 'k1', quizId: 'q1', seq: 1, stem: 'First?', image: null, points: 2 },
]
const OPTIONS = [
  { optionId: 'o2', questionId: 'k1', quizId: 'q1', seq: 2, text: 'Tea', image: null, correct: false },
  { optionId: 'o1', questionId: 'k1', quizId: 'q1', seq: 1, text: 'Coffee', image: null, correct: true },
]
const MATERIALS = [
  { materialId: 'm1', quizId: 'q1', seq: 1, kind: 'LINK', title: 'Read more', description: null, body: null,
    url: 'https://example.com/coffee', pdf: null, audio: null },
]

type Answer = { status: number; body: unknown }

interface Server {
  /** The sponsor has no quiz yet. */
  noQuizzes?: boolean
  /** Answers to processes by name, in turn (the last one repeats). */
  processes?: Record<string, Answer[]>
  quiz?: Record<string, unknown> | null
}

/** The server as the console sees it, for a signed-in, approved sponsor with one quiz. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const quiz = { current: options.quiz === undefined ? { ...QUIZ } : options.quiz }
  const turns = new Map<string, number>()
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const rows = (items: Record<string, unknown>[]) =>
      json(200, { items: items.map((attributes, i) => ({ id: String(i), version: 1, attributes })), offset: 0,
        limit: 100 })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)
    if (process) {
      const answers = options.processes?.[process[1]] ?? [{ status: 404, body: {} }]
      const turn = turns.get(process[1]) ?? 0
      turns.set(process[1], turn + 1)
      const answer = answers[Math.min(turn, answers.length - 1)]
      return json(answer.status, answer.body)
    }
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
        return quiz.current ? json(200, { id: 'q1', version: 1, attributes: quiz.current })
          : json(404, { detail: 'Not found' })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbQuestion/query':
        return rows(QUESTIONS)
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbOption/query':
        return rows(OPTIONS)
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterial/query':
        return rows(MATERIALS)
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterialImage/query':
        return rows([])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:quiz-status':
        return json(200, [{ code: 'DRAFT', label: 'Draft' }, { code: 'VERSIONED', label: 'Versioned' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:material-kind':
        return json(200, ['ARTICLE', 'LINK', 'VIDEO_LINK', 'PDF', 'IMAGES', 'AUDIO']
          .map((code) => ({ code, label: code === 'LINK' ? 'Web link' : code })))
      case '/api/queries/qb.sponsor.quizzes':
        return options.noQuizzes ? json(200, { items: [], offset: 0, limit: 20, total: 0 })
          : json(200, { items: [{ ...QUIZ, publicationCount: 0, published: false }], offset: 0, limit: 20, total: 1 })
      case '/api/queries/qb.sponsor.quiz-versions':
        return json(200, { items: [], offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { posted, processCalls, quiz }
}

const edit = (revision: number, extra: Record<string, unknown> = {}) =>
  ({ status: 200, body: { output: { quizId: 'q1', revision, editedAt: '2026-10-14T02:00:00Z', itemId: null,
    partIds: [], ...extra } } })

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the quiz list', () => {
  it('lists the sponsor\'s quizzes, searches by title and starts a new draft', async () => {
    const { posted, processCalls } = server({ processes: { QB_QUIZ_SAVE: [edit(1, { quizId: 'q9' })] } })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const row = await screen.findByTestId('quiz-row')
    expect(within(row).getByRole('link', { name: 'Coffee basics' })).toHaveAttribute('href', '/quizzes/q1')
    expect(row).toHaveTextContent('Draft')
    await userEvent.type(screen.getByTestId('quiz-search'), 'cof')
    await waitFor(() => expect(posted.some((p) => p.path === '/api/queries/qb.sponsor.quizzes'
      && (p.body.params as Record<string, unknown>)?.q === 'cof')).toBe(true))

    await userEvent.click(screen.getByTestId('new-quiz'))
    await userEvent.type(await screen.findByTestId('new-quiz-title'), 'Tea time')
    await userEvent.click(screen.getByTestId('new-quiz-create'))
    await waitFor(() => expect(window.location.pathname).toBe('/quizzes/q9'))
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([{ title: 'Tea time', intro: null, cover: null,
      timeLimitSec: null, kind: 'QUIZ' }])
  })

  it('shows an illustrated empty state when the sponsor has no quiz yet (Q32)', async () => {
    server({ noQuizzes: true })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const empty = await screen.findByTestId('quizzes-empty')
    expect(empty).toHaveTextContent('No quizzes yet')
    expect(empty.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('makes a survey when the sponsor chooses one (Q12)', async () => {
    const { processCalls } = server({ processes: { QB_QUIZ_SAVE: [edit(1, { quizId: 'q9' })] } })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    await userEvent.click(await screen.findByTestId('new-quiz'))
    await userEvent.type(await screen.findByTestId('new-quiz-title'), 'Tea habits')
    await userEvent.click(screen.getByTestId('new-quiz-kind-SURVEY'))
    await userEvent.click(screen.getByTestId('new-quiz-create'))
    await waitFor(() => expect(window.location.pathname).toBe('/quizzes/q9'))
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([{ title: 'Tea habits', intro: null, cover: null,
      timeLimitSec: null, kind: 'SURVEY' }])
  })
})

describe('the quiz editor', () => {
  beforeEach(() => window.history.replaceState(null, '', '/quizzes/q1'))

  it('saves the heading with the revision it read', async () => {
    const { processCalls } = server({ processes: { QB_QUIZ_SAVE: [edit(8)] } })
    render(<App />)
    const title = await screen.findByTestId('heading-title')
    expect(title).toHaveValue('Coffee basics')
    await userEvent.clear(title)
    await userEvent.type(title, 'Coffee 101')
    await userEvent.click(screen.getByTestId('heading-save'))
    expect(await screen.findByTestId('heading-saved')).toBeInTheDocument()
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([{ quizId: 'q1', title: 'Coffee 101', intro: 'About **coffee**.',
      cover: null, timeLimitSec: 300, baseRevision: 7 }])
  })

  it('previews the introduction as Markdown', async () => {
    server()
    render(<App />)
    await userEvent.click(await screen.findByTestId('heading-intro-preview-toggle'))
    const preview = screen.getByTestId('heading-intro-preview')
    expect(within(preview).getByText('coffee').tagName).toBe('STRONG')
  })

  it('refuses a time limit that is not a whole number before asking the server', async () => {
    const { processCalls } = server()
    render(<App />)
    const time = await screen.findByTestId('heading-time-limit')
    await userEvent.clear(time)
    await userEvent.type(time, '1.5')
    await userEvent.click(screen.getByTestId('heading-save'))
    expect(time).toHaveAccessibleDescription(/whole number/)
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([])
  })

  it('asks to reload when the quiz changed elsewhere, and edits on from the new revision', async () => {
    const changed = { status: 422, body: { detail: 'Refused', violations: [{ field: 'baseRevision',
      ruleCode: 'QB_QUIZ_CHANGED', message: 'The quiz was changed meanwhile.' }] } }
    const { processCalls, quiz } = server({ processes: { QB_QUIZ_SAVE: [changed, edit(10)] } })
    // A frame may come before React shows the notice (a busy machine): the focus must not depend on it.
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 0
    })
    render(<App />)
    await userEvent.click(await screen.findByTestId('heading-save'))
    const stale = await screen.findByTestId('stale')
    expect(stale).toHaveTextContent('changed elsewhere')
    // The notice takes the focus, once shown, so that a screen reader reads it.
    await waitFor(() => expect(stale).toHaveFocus())
    // Held until reloaded; the form does not repeat the refusal.
    expect(screen.getByTestId('heading-save')).toBeDisabled()
    expect(screen.getByTestId('save-version')).toBeDisabled()
    expect(screen.queryByTestId('heading-error')).toBeNull()

    quiz.current = { ...QUIZ, title: 'Renamed elsewhere', revision: 9 }
    await userEvent.click(within(stale).getByTestId('reload'))
    await waitFor(() => expect(screen.queryByTestId('stale')).toBeNull())
    await waitFor(() => expect(screen.getByTestId('heading-title')).toHaveValue('Renamed elsewhere'))
    await userEvent.click(screen.getByTestId('heading-save'))
    await screen.findByTestId('heading-saved')
    expect(processCalls('QB_QUIZ_SAVE').map((b) => b.baseRevision)).toEqual([7, 9])
  })

  it('shows the questions in their order and moves one with the whole order', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_REORDER: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const questions = await screen.findAllByTestId('question')
    expect(questions.map((q) => q.getAttribute('data-question'))).toEqual(['k1', 'k2'])
    const options = within(questions[0]).getAllByTestId('option-row')
    expect(options.map((o) => o.textContent)).toEqual(['1.Coffee(correct)', '2.Tea'])
    await userEvent.click(within(questions[0]).getByRole('button', { name: 'Move question 1 down' }))
    await waitFor(() => expect(processCalls('QB_QUESTION_REORDER')).toEqual([{ quizId: 'q1',
      questionIds: ['k2', 'k1'], baseRevision: 7 }]))
  })

  it('saves a new question with its options in their order', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_SAVE: [edit(8, { itemId: 'k3' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await userEvent.click(await screen.findByTestId('question-add'))
    const dialog = await screen.findByTestId('question-dialog')
    await userEvent.type(within(dialog).getByTestId('question-stem'), 'Which is a bean?')
    const texts = within(dialog).getAllByTestId('option-text')
    await userEvent.type(texts[0], 'Rock')
    await userEvent.type(texts[1], 'Coffee')
    // One correct option makes a single choice, two a multiple choice (D-Q5-2), said as the author marks them.
    expect(within(dialog).getByTestId('question-choice')).toHaveAttribute('data-choice', 'none')
    await userEvent.click(within(dialog).getAllByTestId('option-correct')[0])
    await userEvent.click(within(dialog).getAllByTestId('option-correct')[1])
    expect(within(dialog).getByTestId('question-choice')).toHaveAttribute('data-choice', 'MULTIPLE')
    expect(within(dialog).getByTestId('question-choice')).toHaveTextContent('2 correct options')
    await userEvent.click(within(dialog).getAllByTestId('option-correct')[0])
    expect(within(dialog).getByTestId('question-choice')).toHaveAttribute('data-choice', 'SINGLE')
    expect(within(dialog).getByTestId('question-choice')).toHaveTextContent('Single choice')
    await userEvent.click(within(dialog).getByTestId('option-add'))
    await userEvent.type(within(dialog).getAllByTestId('option-text')[2], 'Cocoa')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move option 3 up' }))
    await userEvent.click(within(dialog).getByTestId('question-save'))
    await waitFor(() => expect(screen.queryByTestId('question-dialog')).toBeNull())
    expect(processCalls('QB_QUESTION_SAVE')).toEqual([{ quizId: 'q1', questionId: null, stem: 'Which is a bean?',
      image: null, points: 1, baseRevision: 7, options: [
        { optionId: null, text: 'Rock', image: null, correct: false },
        { optionId: null, text: 'Cocoa', image: null, correct: false },
        { optionId: null, text: 'Coffee', image: null, correct: true },
      ] }])
  })

  it('asks a survey\'s question for single or multiple choice, without points or correct options (Q12)', async () => {
    const { processCalls } = server({ quiz: { ...QUIZ, kind: 'SURVEY' },
      processes: { QB_QUESTION_SAVE: [edit(8, { itemId: 'k3' })] } })
    render(<App />)
    expect(await screen.findByTestId('editor-survey')).toHaveTextContent('Survey')
    await userEvent.click(await screen.findByTestId('tab-questions'))
    expect(screen.queryByTestId('ai-panel')).toBeNull()
    await userEvent.click(await screen.findByTestId('question-add'))
    const dialog = await screen.findByTestId('question-dialog')
    expect(within(dialog).queryByTestId('question-points')).toBeNull()
    expect(within(dialog).queryByTestId('option-correct')).toBeNull()
    await userEvent.type(within(dialog).getByTestId('question-stem'), 'Which do you drink?')
    const texts = within(dialog).getAllByTestId('option-text')
    await userEvent.type(texts[0], 'Tea')
    await userEvent.type(texts[1], 'Coffee')
    await userEvent.click(within(dialog).getByTestId('question-selection-MULTIPLE'))
    await userEvent.click(within(dialog).getByTestId('question-save'))
    await waitFor(() => expect(screen.queryByTestId('question-dialog')).toBeNull())
    expect(processCalls('QB_QUESTION_SAVE')).toEqual([{ quizId: 'q1', questionId: null, stem: 'Which do you drink?',
      image: null, points: 1, baseRevision: 7, selection: 'MULTIPLE', options: [
        { optionId: null, text: 'Tea', image: null, correct: false },
        { optionId: null, text: 'Coffee', image: null, correct: false },
      ] }])
  })

  it('sends only the content field of a material\'s kind, and links only to http(s)', async () => {
    const { processCalls } = server({ processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    const link = await screen.findByRole('link', { name: /example\.com\/coffee/ })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    await userEvent.click(screen.getByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Watch')
    await userEvent.type(within(dialog).getByTestId('material-url'), ' https://example.com/v ')
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toEqual({ quizId: 'q1', materialId: null, kind: 'VIDEO_LINK',
      title: 'Watch', description: null, url: 'https://example.com/v', baseRevision: 7, watchToAnswer: false })
  })

  it('marks a video as the one watching which is enough to answer (Q19)', async () => {
    const { processCalls } = server({ processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    // Only a video offers it.
    expect(within(dialog).queryByTestId('material-watch')).not.toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Watch')
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://youtu.be/dQw4w9WgXcQ')
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Watching this video is enough to answer' }))
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toMatchObject({ kind: 'VIDEO_LINK',
      url: 'https://youtu.be/dQw4w9WgXcQ', watchToAnswer: true })
  })

  it('lists every reason a version was refused', async () => {
    server({ processes: { QB_QUIZ_PUBLISH_VERSION: [{ status: 422, body: { detail: 'Refused', violations: [
      { field: 'questions[0].options', ruleCode: 'QB_QUESTION_OPTION_COUNT', message: 'Question 1 needs 2 to 6 options.' },
      { field: 'questions[1].stem', ruleCode: 'QB_QUESTION_STEM_REQUIRED', message: 'Question 2 has no text.' },
    ] } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('save-version'))
    const problems = await screen.findByTestId('version-problems')
    expect(within(problems).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Question 1 needs 2 to 6 options.', 'Question 2 has no text.'])
  })

  it('says so when the quiz is not the sponsor\'s', async () => {
    server({ quiz: null })
    render(<App />)
    expect(await screen.findByText('This quiz does not exist or is no longer yours.')).toBeInTheDocument()
  })
})
