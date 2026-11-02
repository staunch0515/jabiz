import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expectAccessible } from '@jabiz/ui/testing'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const QUIZ = {
  quizId: 'q1', ownerId: 'u2', title: 'Coffee basics', language: 'zh', intro: 'About **coffee**.', cover: null, timeLimitSec: 300,
  status: 'DRAFT', removed: false, aiGenerated: false, latestVersionNo: 0, versionLabel: null, revision: 7,
  editedAt: '2026-10-14T01:00:00Z', changedSinceVersion: true, questionCount: 2, materialCount: 1, clonedFrom: null, translationLanguages: [],
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
  /** Q73a: the quiz's translations and materials of other languages. */
  translations?: Record<string, unknown>[]
  variants?: Record<string, unknown>[]
  /** Q73d-1: the quiz's latest AI translation job, if any. */
  aiTranslation?: Record<string, unknown> | null
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
        return rows([...MATERIALS, ...(options.variants ?? [])])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbContentTranslation/query':
        return rows(options.translations ?? [])
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
      case '/api/queries/qb.sponsor.ai-usage':
        return json(200, { items: [{ month: '2026-10', enabled: true, jobs: 0, failedJobs: 0, inputTokens: 0,
          outputTokens: 0, monthlyTokens: 2000000, reservedTokens: 0, remainingTokens: 2000000, dailyJobs: 20,
          jobsToday: 0, monthEnds: '2026-10-31T15:00:00Z', dayEnds: '2026-10-18T15:00:00Z' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.ai-jobs': {
        const kind = (posted.at(-1)?.body.params as Record<string, unknown> | undefined)?.kind
        return json(200, { items: kind === 'TRANSLATION' && options.aiTranslation ? [options.aiTranslation] : [],
          offset: 0, limit: 1 })
      }
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
      timeLimitSec: null, kind: 'QUIZ', language: 'en' }])
  })

  it('shows an illustrated empty state when the sponsor has no quiz yet (Q32)', async () => {
    server({ noQuizzes: true })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const empty = await screen.findByTestId('quizzes-empty')
    expect(empty).toHaveTextContent('No quizzes yet')
    expect(empty.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('writes a new quiz in the language the sponsor picks (Q59a)', async () => {
    const { processCalls } = server({ processes: { QB_QUIZ_SAVE: [edit(1, { quizId: 'q9' })] } })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    await userEvent.click(await screen.findByTestId('new-quiz'))
    await userEvent.type(await screen.findByTestId('new-quiz-title'), 'お茶の時間')
    // The console's language first.
    expect(screen.getByTestId('new-quiz-language')).toHaveTextContent('English')
    await userEvent.click(screen.getByTestId('new-quiz-language'))
    await userEvent.click(await screen.findByRole('option', { name: '日本語' }))
    await userEvent.click(screen.getByTestId('new-quiz-create'))
    await waitFor(() => expect(window.location.pathname).toBe('/quizzes/q9'))
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([{ title: 'お茶の時間', intro: null, cover: null,
      timeLimitSec: null, kind: 'QUIZ', language: 'ja' }])
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
      timeLimitSec: null, kind: 'SURVEY', language: 'en' }])
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
      cover: null, timeLimitSec: 300, language: 'zh', baseRevision: 7 }])
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
      title: 'Watch', description: null, url: 'https://example.com/v', baseRevision: 7, watchToAnswer: false,
      durationSec: null })
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

  it('takes the duration of the video to watch, read from its player or typed (Q75)', async () => {
    const { processCalls } = server({ processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Watch')
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://youtu.be/dQw4w9WgXcQ')
    // The duration is a video's; the preview that reads it comes with the tick.
    expect(within(dialog).getByTestId('material-duration')).toBeInTheDocument()
    expect(within(dialog).queryByTestId('material-video-probe')).not.toBeInTheDocument()
    // Before the preview's frame: axe cannot look into a frame in jsdom.
    await expectAccessible()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Watching this video is enough to answer' }))
    const probe = within(dialog).getByTestId('material-video-probe') as HTMLIFrameElement
    expect(probe.getAttribute('src')).toMatch(/^https:\/\/www\.youtube-nocookie\.com\/embed\/dQw4w9WgXcQ\?enablejsapi=1&origin=/)
    // The player says how long the video is: filled in, until the sponsor types.
    window.dispatchEvent(new MessageEvent('message', { source: probe.contentWindow,
      data: JSON.stringify({ event: 'infoDelivery', info: { duration: 150.4 } }) }))
    await waitFor(() => expect(within(dialog).getByTestId('material-duration')).toHaveValue('2:30'))
    expect(within(dialog).getByText(/Read from the player/)).toBeInTheDocument()
    // A typed duration that cannot be read is pointed out and not sent as nothing.
    await userEvent.clear(within(dialog).getByTestId('material-duration'))
    await userEvent.type(within(dialog).getByTestId('material-duration'), '2:75')
    await userEvent.click(within(dialog).getByTestId('material-save'))
    expect(await within(dialog).findByTestId('material-duration-error')).toHaveTextContent('minutes:seconds')
    expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(0)
    await userEvent.clear(within(dialog).getByTestId('material-duration'))
    await userEvent.type(within(dialog).getByTestId('material-duration'), '3:05')
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toMatchObject({ kind: 'VIDEO_LINK', watchToAnswer: true, durationSec: 185 })
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

describe('the quiz\'s other languages (Q73a)', () => {
  beforeEach(() => window.history.replaceState(null, '', '/quizzes/q1'))

  // The hash of "First?" (NFC, SHA-256, 16 hexadecimal characters), as languages.test.ts checks.
  const FIRST_HASH = '4a3c3009935c1351'

  it('adds a language, shows the texts side by side and saves the changed ones', async () => {
    const { processCalls } = server({
      quiz: { ...QUIZ, translationLanguages: 'en' },
      translations: [
        { translationId: 't1', quizId: 'q1', language: 'en', elementKey: 'question:k1.stem', text: 'First?',
          image: null, sourceHash: FIRST_HASH },
        { translationId: 't2', quizId: 'q1', language: 'en', elementKey: 'question:k2.stem', text: 'Old',
          image: null, sourceHash: '0000000000000000' },
      ],
      processes: { QB_TRANSLATION_SAVE: [edit(8)], QB_QUIZ_LANGUAGES_SET: [edit(8)] },
    })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    const panel = await screen.findByTestId('translations')
    expect(screen.getByTestId('tab-languages')).toHaveTextContent('(2)')
    // Done, out of date and missing, by the base's hashes.
    await waitFor(() => expect(within(panel).getAllByTestId('translation-text')
      .find((row) => row.dataset.key === 'question:k1.stem')).toHaveAttribute('data-state', 'done'))
    const rows = within(panel).getAllByTestId('translation-text')
    expect(rows.find((row) => row.dataset.key === 'question:k2.stem')).toHaveAttribute('data-state', 'stale')
    expect(rows.find((row) => row.dataset.key === 'option:o1.text')).toHaveAttribute('data-state', 'missing')
    // The materials: the base's shown in its place.
    expect(within(panel).getByTestId('translation-material')).toHaveAttribute('data-own', 'no')
    await expectAccessible()

    const title = rows.find((row) => row.dataset.key === 'quiz.title')!
    await userEvent.type(within(title).getByTestId('translation-input'), 'Coffee basics, in English')
    await userEvent.click(within(panel).getByTestId('translation-save'))
    await waitFor(() => expect(processCalls('QB_TRANSLATION_SAVE')).toEqual([{ quizId: 'q1', language: 'en',
      entries: [{ key: 'quiz.title', text: 'Coffee basics, in English' }], baseRevision: 7 }]))

    // Another language.
    await userEvent.click(within(panel).getByTestId('translation-language-ja'))
    await waitFor(() => expect(processCalls('QB_QUIZ_LANGUAGES_SET')).toEqual([{ quizId: 'q1',
      languages: ['en', 'ja'], baseRevision: 8 }]))
  })

  it('shows the AI\'s suggestions under the texts and writes the checked ones as the AI\'s (Q73d-1)', async () => {
    const { processCalls } = server({
      quiz: { ...QUIZ, translationLanguages: 'en' },
      aiTranslation: { aiJobId: 'j1', externalJobId: 'x1', kind: 'TRANSLATION', status: 'READY',
        params: JSON.stringify({ language: 'en', all: false }), candidateCount: 2, invalidCount: 0, acceptedCount: 0,
        requestedAt: '2026-10-14T01:00:00Z', finishedAt: '2026-10-14T01:01:00Z', expiresAt: '2026-10-21T01:01:00Z',
        candidates: JSON.stringify({ schema: 1, language: 'en', translations: [
          { no: 1, key: 'quiz.title', sourceHash: 'h', text: 'Coffee basics (AI)' },
          { no: 2, key: 'option:o1.text', sourceHash: 'h', text: 'Coffee (AI)' }] }) },
      processes: { QB_AI_TRANSLATION_ACCEPT: [edit(8)], QB_TRANSLATION_SAVE: [edit(9)] },
    })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    const panel = await screen.findByTestId('translations')
    expect(await within(panel).findByTestId('ai-translate-ready')).toHaveTextContent('2 AI translations')
    const rows = within(panel).getAllByTestId('translation-text')
    const title = rows.find((row) => row.dataset.key === 'quiz.title')!
    expect(within(title).getByTestId('ai-suggestion')).toHaveTextContent('Coffee basics (AI)')
    await expectAccessible()

    // Every suggestion used at once; each must be checked before the save.
    await userEvent.click(within(panel).getByTestId('ai-translate-use-all'))
    expect(within(title).getByTestId('translation-input')).toHaveValue('Coffee basics (AI)')
    await userEvent.click(within(panel).getByTestId('translation-save'))
    expect(within(panel).getByTestId('ai-translate-review-first')).toBeInTheDocument()
    expect(processCalls('QB_AI_TRANSLATION_ACCEPT')).toEqual([])

    // The title checked as given; the option changed, then checked; the question translated by hand.
    await userEvent.click(within(title).getByTestId('ai-suggestion-reviewed'))
    const option = rows.find((row) => row.dataset.key === 'option:o1.text')!
    await userEvent.clear(within(option).getByTestId('translation-input'))
    await userEvent.type(within(option).getByTestId('translation-input'), 'Coffee')
    await userEvent.click(within(option).getByTestId('ai-suggestion-reviewed'))
    const question = rows.find((row) => row.dataset.key === 'question:k1.stem')!
    await userEvent.type(within(question).getByTestId('translation-input'), 'First?')
    await userEvent.click(within(panel).getByTestId('translation-save'))
    await waitFor(() => expect(processCalls('QB_TRANSLATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_AI_TRANSLATION_ACCEPT')).toEqual([{ aiJobId: 'j1', quizId: 'q1', baseRevision: 7,
      translations: [{ candidateNo: 1, text: 'Coffee basics (AI)', reviewed: true },
        { candidateNo: 2, text: 'Coffee', reviewed: true }] }])
    expect(processCalls('QB_TRANSLATION_SAVE')).toEqual([{ quizId: 'q1', language: 'en', baseRevision: 8,
      entries: [{ key: 'question:k1.stem', text: 'First?' }] }])
  })

  it('asks the AI to translate the missing texts, telling what is sent (Q73d-1)', async () => {
    const { processCalls } = server({ quiz: { ...QUIZ, translationLanguages: 'en' },
      processes: { QB_AI_TRANSLATE_REQUEST: [{ status: 200, body: { output: { aiJobId: 'j2', externalJobId: 'x2',
        status: 'QUEUED', estimatedTokens: 1000 } } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    await userEvent.click(await screen.findByTestId('ai-translate-generate'))
    const dialog = await screen.findByTestId('ai-translate-dialog')
    expect(within(dialog).getByTestId('ai-translate-sent')).toHaveTextContent('Never which answers are correct')
    await userEvent.click(within(dialog).getByTestId('ai-translate-submit'))
    await waitFor(() => expect(processCalls('QB_AI_TRANSLATE_REQUEST')).toEqual([{ quizId: 'q1', language: 'en',
      all: false, aiNoticeVersionNo: null }]))
  })

  it('makes a material of another language of the base\'s kind', async () => {
    const { processCalls } = server({ quiz: { ...QUIZ, translationLanguages: 'en' },
      processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm9' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    await userEvent.click(await screen.findByTestId('translation-material-edit'))
    const dialog = await screen.findByTestId('material-dialog')
    expect(within(dialog).queryByTestId('material-kind')).not.toBeInTheDocument()
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Read more (EN)')
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://example.com/en')
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toEqual([{ quizId: 'q1', materialId: null,
      kind: 'LINK', title: 'Read more (EN)', description: null, baseRevision: 7, variantOf: 'm1', language: 'en',
      url: 'https://example.com/en' }]))
  })

  it('keeps the base language while the quiz has others', async () => {
    server({ quiz: { ...QUIZ, translationLanguages: 'en' } })
    render(<App />)
    expect(await screen.findByTestId('heading-language')).toBeDisabled()
  })
})
