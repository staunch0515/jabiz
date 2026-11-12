import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
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
  /** F-C1: the sponsor's asset library. */
  assets?: Record<string, unknown>[]
  /** F-S6: the quiz's questions as `qb.sponsor.quiz-questions` numbers them (else QUESTIONS in their order). */
  bank?: Record<string, unknown>[]
  /** F-S6: the bank's limits. */
  limits?: Record<string, unknown>
  /** F-S6: the quiz's import batches. */
  imports?: Record<string, unknown>[]
  /** F-S6c: the rows of `qb.sponsor.quiz-translations` (else QUESTIONS and OPTIONS with `translations`). */
  translationBank?: Record<string, unknown>[]
}

/** F-S6: the rows of `qb.sponsor.quiz-questions` of the default quiz: QUESTIONS in their order, numbered. */
function bankOf(questions: Record<string, unknown>[], options: Record<string, unknown>[]) {
  return [...questions].sort((a, b) => Number(a.seq) - Number(b.seq)).map((q, i) => ({
    questionId: q.questionId, questionNo: i + 1, stem: q.stem, image: q.image, points: q.points,
    selection: q.selection ?? null, difficulty: q.difficulty ?? null,
    optionCount: options.filter((o) => o.questionId === q.questionId).length,
    translated: 0, correctCount: options.filter((o) => o.questionId === q.questionId && o.correct).length,
    optionImages: options.some((o) => o.questionId === q.questionId && o.image) }))
}

/**
 * F-S6c: the rows of `qb.sponsor.quiz-translations` of the default quiz in a language: QUESTIONS in their order with
 * their options and translations. The server judges a translation's state by the hash of the base text; here the
 * hash 0000000000000000 stands for one made from another text.
 */
function translationBankOf(language: string, translations: Record<string, unknown>[]) {
  const of = (key: string) => translations.find((t) => t.language === language && t.elementKey === key)
  const state = (text: unknown, key: string) => !text ? 'none' : !of(key)?.text ? 'missing'
    : of(key)!.sourceHash === '0000000000000000' ? 'stale' : 'done'
  return [...QUESTIONS].sort((a, b) => a.seq - b.seq).map((q, i) => {
    const options = OPTIONS.filter((o) => o.questionId === q.questionId).sort((a, b) => a.seq - b.seq)
      .map((o, j) => ({ optionId: o.optionId, no: j + 1, text: o.text, image: o.image,
        translation: of(`option:${o.optionId}.text`)?.text ?? null, state: state(o.text, `option:${o.optionId}.text`),
        by: of(`option:${o.optionId}.text`)?.translatedBy ?? null, ownImage: of(`option:${o.optionId}.image`)?.image ?? null }))
    const stemState = state(q.stem, `question:${q.questionId}.stem`)
    return { questionId: q.questionId, questionNo: i + 1, stem: q.stem, image: q.image,
      stemText: of(`question:${q.questionId}.stem`)?.text ?? null, stemState,
      stemBy: of(`question:${q.questionId}.stem`)?.translatedBy ?? null,
      ownImage: of(`question:${q.questionId}.image`)?.image ?? null, options: JSON.stringify(options),
      pending: [stemState, ...options.map((o) => o.state)].filter((x) => x === 'missing' || x === 'stale').length }
  })
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
    const question = /^\/api\/datasets\/urn:jabiz:dataset:sponsor:QbQuestion\/entities\/(.+)$/.exec(path)
    if (question) {
      // F-S6d: an opened question is read alone (the editor's draft has none).
      const row = (options.bank ?? QUESTIONS).find((r) => r.questionId === question[1])
      return row ? json(200, { id: question[1], version: 1, attributes: { ...row, quizId: 'q1' } })
        : json(404, { detail: 'Not found' })
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
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbOption/query': {
        // F-S6b: one question's options when opened on its own.
        const byQuestion = (body?.filters as { field: string; value: string }[] | undefined)
          ?.find((f) => f.field === 'questionId')?.value
        return rows(byQuestion ? OPTIONS.filter((o) => o.questionId === byQuestion).sort((a, b) => a.seq - b.seq)
          : OPTIONS)
      }
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterial/query':
        return rows([...MATERIALS, ...(options.variants ?? [])])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbContentTranslation/query': {
        // F-S6c: by key when the filter names keys (the editor reads only the quiz's own).
        const keys = (body?.filters as { field: string; values?: string[] }[] | undefined)
          ?.find((f) => f.field === 'elementKey')?.values
        return rows((options.translations ?? []).filter((t) => !keys || keys.includes(String(t.elementKey))))
      }
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbMaterialImage/query':
        return rows([])
      case '/api/datasets/urn:jabiz:dataset:me:QbAsset/query':
        return rows(options.assets ?? [])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:quiz-status':
        return json(200, [{ code: 'DRAFT', label: 'Draft' }, { code: 'VERSIONED', label: 'Versioned' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:material-kind':
        return json(200, ['ARTICLE', 'LINK', 'VIDEO_LINK', 'PDF', 'IMAGES', 'AUDIO']
          .map((code) => ({ code, label: code === 'LINK' ? 'Web link' : code })))
      case '/api/queries/qb.sponsor.quizzes':
        return options.noQuizzes ? json(200, { items: [], offset: 0, limit: 20, total: 0 })
          : json(200, { items: [{ ...QUIZ, publicationCount: 0, published: false }], offset: 0, limit: 20, total: 1 })
      case '/api/queries/qb.sponsor.quiz-questions': {
        // As the template answers: searched by text or number, then the page asked for, counted.
        const params = (body?.params ?? {}) as { q?: string; no?: number; difficulty?: string }
        const all = (options.bank ?? bankOf(QUESTIONS, OPTIONS)).filter((row) =>
          (params.q === undefined || String(row.stem ?? '').toLowerCase().includes(params.q.toLowerCase()))
          && (params.no === undefined || row.questionNo === params.no)
          && (params.difficulty === undefined || (row.difficulty ?? 'NONE') === params.difficulty))
        const offset = Number(body?.offset ?? 0)
        const limit = Number(body?.limit ?? 50)
        return json(200, { items: all.slice(offset, offset + limit), offset, limit, total: all.length })
      }
      case '/api/queries/qb.sponsor.quiz-translations': {
        // As the template answers: searched by a question's or an option's text, only what needs doing, then the
        // page asked for, counted.
        const params = (body?.params ?? {}) as { language: string; q?: string; untranslated?: boolean }
        const has = (text: unknown) => params.q === undefined
          || String(text ?? '').toLowerCase().includes(params.q.toLowerCase())
        const all = (options.translationBank ?? translationBankOf(params.language, options.translations ?? []))
          .filter((row) => (has(row.stem) || (JSON.parse(String(row.options)) as { text: string }[])
            .some((o) => has(o.text))) && (!params.untranslated || Number(row.pending) > 0))
        const offset = Number(body?.offset ?? 0)
        const limit = Number(body?.limit ?? 50)
        return json(200, { items: all.slice(offset, offset + limit), offset, limit, total: all.length })
      }
      case '/api/queries/qb.sponsor.quiz-translation-progress': {
        // As the template answers: per other language, the texts missing and out of date (by the stand-in hash).
        const languages = String(quiz.current?.translationLanguages ?? '').split(',').filter(Boolean)
        return json(200, { items: languages.map((language) => {
          const of = (key: string) => (options.translations ?? [])
            .find((t) => t.language === language && t.elementKey === key)
          const texts: [string, unknown][] = [['quiz.title', quiz.current?.title], ['quiz.intro', quiz.current?.intro],
            ...QUESTIONS.map((q): [string, unknown] => [`question:${q.questionId}.stem`, q.stem]),
            ...OPTIONS.map((o): [string, unknown] => [`option:${o.optionId}.text`, o.text])]
          const states = texts.filter(([, text]) => text).map(([key]) => !of(key)?.text ? 'missing'
            : of(key)!.sourceHash === '0000000000000000' ? 'stale' : 'done')
          return { language, missing: states.filter((x) => x === 'missing').length,
            stale: states.filter((x) => x === 'stale').length }
        }), offset: 0, limit: 10 })
      }
      case '/api/queries/qb.sponsor.quiz-question-summary': {
        // As the template answers, from the bank's rows (their correct options) in their order.
        const bank = options.bank ?? bankOf(QUESTIONS, OPTIONS)
        const correct = (row: Record<string, unknown>) => Number(row.correctCount ?? 0)
        const unmarked = bank.filter((row) => correct(row) === 0)
        return json(200, { items: [{ quizId: 'q1', questionCount: bank.length,
          fullScore: bank.reduce((sum, row) => sum + Number(row.points ?? 1), 0),
          singleCount: bank.filter((row) => correct(row) === 1).length,
          multipleCount: bank.filter((row) => correct(row) > 1).length, unmarkedCount: unmarked.length,
          firstUnmarkedId: unmarked[0]?.questionId ?? null, firstUnmarkedNo: unmarked[0]?.questionNo ?? null }],
          offset: 0, limit: 1 })
      }
      case '/api/queries/qb.sponsor.quiz-limits':
        return json(200, { items: [options.limits ?? { maxQuestions: 1000, maxPerAttempt: 100, importBatch: 200 }],
          offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbQuestionImport/query':
        return rows(options.imports ?? [])
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
  it('lists the Sponsor\'s quizzes, searches by title and starts a new draft', async () => {
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

  it('shows an illustrated empty state when the Sponsor has no quiz yet (Q32)', async () => {
    server({ noQuizzes: true })
    window.history.replaceState(null, '', '/quizzes')
    render(<App />)
    const empty = await screen.findByTestId('quizzes-empty')
    expect(empty).toHaveTextContent('No quizzes yet')
    expect(empty.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
  })

  it('writes a new quiz in the language the Sponsor picks (Q59a)', async () => {
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

  it('makes a survey when the Sponsor chooses one (Q12)', async () => {
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
      cover: null, timeLimitSec: 300, language: 'zh', shuffleQuestions: true, baseRevision: 7 }])
  })

  it('turns the shuffling of the questions off with the heading, a survey not at all (F-S7)', async () => {
    const { processCalls } = server({ processes: { QB_QUIZ_SAVE: [edit(8)] } })
    render(<App />)
    const shuffle = await screen.findByRole('switch', { name: 'Shuffle the questions' })
    expect(shuffle).toBeChecked()
    expect(shuffle).toHaveAccessibleDescription(/an order of their own/)
    await userEvent.click(shuffle)
    await userEvent.click(screen.getByTestId('heading-save'))
    expect(await screen.findByTestId('heading-saved')).toBeInTheDocument()
    expect(processCalls('QB_QUIZ_SAVE')).toEqual([expect.objectContaining({ quizId: 'q1', shuffleQuestions: false })])
  })

  it('shows no shuffling for a survey', async () => {
    server({ quiz: { ...QUIZ, kind: 'SURVEY' } })
    render(<App />)
    await screen.findByTestId('heading-title')
    expect(screen.queryByTestId('heading-shuffle')).toBeNull()
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

  it('shows the questions folded in their order, opens one at a time, and moves one to another place', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_MOVE: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const questions = await screen.findAllByTestId('question')
    expect(questions.map((q) => q.getAttribute('data-question'))).toEqual(['k1', 'k2'])
    // Q78: folded rows: the first line, the badge and the option count; the options only once opened.
    expect(within(questions[0]).getByTestId('question-first-line')).toHaveTextContent('First?')
    expect(within(questions[0]).getByTestId('question-badge')).toHaveTextContent('Single choice · Points: 2')
    expect(within(questions[0]).getByTestId('question-option-count')).toHaveTextContent('2 options')
    expect(within(questions[1]).getByTestId('question-option-count')).toHaveTextContent('0 options')
    expect(screen.queryByTestId('option-row')).toBeNull()
    // Q79, F-F3: the staff are a tab of the Sponsor hub, not an entry of the menu.
    expect(screen.queryByTestId('menu-staff')).toBeNull()
    const summary = screen.getByTestId('questions-summary')
    expect(summary).toHaveTextContent('2 / 1,000 questions')
    expect(summary).toHaveTextContent('Full score 3')
    expect(summary).toHaveTextContent('Single choice 1 · multiple choice 0')
    expect(within(summary).getByTestId('questions-unmarked')).toHaveTextContent('1 question has no correct option')
    const toggle = within(questions[0]).getByTestId('question-row-toggle')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const options = await within(questions[0]).findAllByTestId('option-row')
    expect(options.map((o) => o.textContent)).toEqual(['1.Coffee(correct)', '2.Tea'])
    await expectAccessible()
    // Opening another closes it; the summary's link opens the unmarked one.
    await userEvent.click(within(questions[1]).getByTestId('question-row-toggle'))
    expect(within(questions[0]).queryByTestId('option-row')).toBeNull()
    expect(within(questions[1]).getByTestId('question-row-toggle')).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(within(questions[1]).getByTestId('question-row-toggle'))
    expect(screen.queryByTestId('question-row-detail')).toBeNull()
    await userEvent.click(within(summary).getByTestId('questions-unmarked'))
    expect(within(questions[1]).getByTestId('question-row-toggle')).toHaveAttribute('aria-expanded', 'true')
    // F-S6b: "Move to" moves the one question (`QB_QUESTION_MOVE`); past the last place it goes to the end.
    await userEvent.click(screen.getByRole('combobox', { name: 'Move question 1 to' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Place 2' }))
    await waitFor(() => expect(processCalls('QB_QUESTION_MOVE')).toEqual([{ quizId: 'q1', questionId: 'k1',
      beforeQuestionId: null, baseRevision: 7 }]))
    expect(processCalls('QB_QUESTION_REORDER')).toEqual([])
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
      image: null, points: 1, baseRevision: 7, difficulty: '', options: [
        { optionId: null, text: 'Rock', image: null, correct: false },
        { optionId: null, text: 'Cocoa', image: null, correct: false },
        { optionId: null, text: 'Coffee', image: null, correct: true },
      ] }])
  })

  it('saves a question\'s difficulty, chosen in its dialog (F-S7)', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_SAVE: [edit(8, { itemId: 'k3' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await userEvent.click(await screen.findByTestId('question-add'))
    const dialog = await screen.findByTestId('question-dialog')
    await userEvent.type(within(dialog).getByTestId('question-stem'), 'Which is a bean?')
    await userEvent.type(within(dialog).getAllByTestId('option-text')[0], 'Coffee')
    await userEvent.type(within(dialog).getAllByTestId('option-text')[1], 'Rock')
    await userEvent.click(within(dialog).getAllByTestId('option-correct')[0])
    expect(within(dialog).getByRole('combobox', { name: 'Difficulty' })).toHaveTextContent('Not set')
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Difficulty' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Medium' }))
    await userEvent.click(within(dialog).getByTestId('question-save'))
    await waitFor(() => expect(screen.queryByTestId('question-dialog')).toBeNull())
    expect(processCalls('QB_QUESTION_SAVE')).toEqual([expect.objectContaining({ difficulty: 'MEDIUM' })])
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

  it('offers watching first only for a video whose player says how much was watched (Q99)', async () => {
    server({})
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    expect(within(dialog).getByTestId('material-url')).toHaveAccessibleDescription(/Bilibili \(b23\.tv short links too\), Niconico, Dailymotion/)
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://www.bilibili.com/video/BV1GJ411x7h7')
    const watch = within(dialog).getByRole('checkbox', { name: 'Watching this video is enough to answer' })
    expect(watch).toBeDisabled()
    expect(within(dialog).getByTestId('material-watch-unavailable')).toHaveTextContent('does not tell how much was watched')
    expect(watch).toHaveAccessibleDescription(/does not tell how much was watched/)
    await userEvent.clear(within(dialog).getByTestId('material-url'))
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://vimeo.com/76979871')
    expect(watch).toBeEnabled()
    expect(within(dialog).queryByTestId('material-watch-unavailable')).toBeNull()
    await expectAccessible()
  })

  it('turns a b23.tv short link into the video\'s address, and says when an address does not play (Q99b)', async () => {
    const { processCalls } = server({ processes: { QB_VIDEO_LINK_RESOLVE: [
      { status: 200, body: { output: { url: 'https://www.bilibili.com/video/BV1GJ411x7h7' } } },
      { status: 422, body: { detail: 'Refused', violations: [{ field: 'url', ruleCode: 'QB_VIDEO_LINK_NOT_RESOLVED',
        message: 'The short link could not be turned into the address of one Bilibili video.' }] } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    const url = within(dialog).getByTestId('material-url')
    // An address the App does not play (F-S15: not https) is said to be only a link.
    await userEvent.type(url, 'http://video.example/1')
    expect(within(dialog).getByTestId('material-url-status')).toHaveTextContent('cannot play this address')
    // A short link is asked for once pasted, and replaced by the video's address.
    await userEvent.clear(url)
    await userEvent.click(url)
    await userEvent.paste('https://b23.tv/bMyOKXv')
    await waitFor(() => expect(url).toHaveValue('https://www.bilibili.com/video/BV1GJ411x7h7'))
    expect(processCalls('QB_VIDEO_LINK_RESOLVE')).toEqual([{ url: 'https://b23.tv/bMyOKXv' }])
    expect(within(dialog).getByTestId('material-url-status')).toHaveTextContent('turned into the video\'s full address')
    // One that leads nowhere says why.
    await userEvent.clear(url)
    await userEvent.click(url)
    await userEvent.paste('https://b23.tv/gone123')
    await waitFor(() => expect(within(dialog).getByTestId('material-url-status'))
      .toHaveTextContent('could not be turned into the address of one Bilibili video'))
    expect(url).toHaveValue('https://b23.tv/gone123')
    await expectAccessible()
  })

  it('previews a video file, reads its length, and requires the length for watching it (F-S15)', async () => {
    const { processCalls } = server({ processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Demo')
    const url = within(dialog).getByTestId('material-url')
    await userEvent.type(url, 'https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4')
    expect(within(dialog).getByTestId('material-url-status')).toHaveTextContent('Plays in the App')
    // Previewed for real at once, and its watching can be required.
    const probe = within(dialog).getByTestId('material-video-probe') as HTMLVideoElement
    expect(probe.tagName).toBe('VIDEO')
    expect(probe).toHaveAttribute('src', 'https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4')
    const watch = within(dialog).getByRole('checkbox', { name: 'Watching this video is enough to answer' })
    expect(watch).toBeEnabled()
    await userEvent.click(watch)
    // Not saved before its length was read.
    await userEvent.click(within(dialog).getByTestId('material-save'))
    expect(await within(dialog).findByTestId('material-file-duration-needed'))
      .toHaveTextContent('wait until the preview has read the video\'s length')
    expect(processCalls('QB_MATERIAL_SAVE')).toEqual([])
    // The file's metadata says how long it is: filled in, and the save goes ahead.
    Object.defineProperty(probe, 'duration', { value: 30.2, configurable: true })
    act(() => {
      probe.dispatchEvent(new Event('loadedmetadata'))
    })
    await waitFor(() => expect(within(dialog).getByTestId('material-duration')).toHaveValue('0:30'))
    Object.defineProperty(probe, 'readyState', { value: 4, configurable: true })
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toMatchObject({ kind: 'VIDEO_LINK', watchToAnswer: true,
      durationSec: 30, url: 'https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4' })
  })

  it('says on the spot when a video file cannot be opened (F-S15)', async () => {
    server()
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://cdn.example.com/missing.mp4')
    const probe = within(dialog).getByTestId('material-video-probe') as HTMLVideoElement
    act(() => {
      probe.dispatchEvent(new Event('error'))
    })
    expect(await within(dialog).findByTestId('material-file-unplayable')).toHaveTextContent('cannot be opened')
  })

  it('previews a page of another site, says what a blank preview means, and does not take it as the video to watch (F-S15)',
    async () => {
    server()
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-url'), 'https://player.example.com/embed/1')
    const preview = within(dialog).getByTestId('material-page-preview')
    const frame = preview.querySelector('iframe')!
    expect(frame).toHaveAttribute('src', 'https://player.example.com/embed/1')
    expect(frame).toHaveAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation')
    expect(frame).toHaveAttribute('referrerpolicy', 'no-referrer')
    expect(within(dialog).getByTestId('material-page-hint')).toHaveTextContent('If this preview stays blank')
    expect(within(dialog).getByRole('checkbox', { name: 'Watching this video is enough to answer' })).toBeDisabled()
    await expectAccessible()
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

  it('marks a vertical video, and says a Shorts link is one anyway (F-S10)', async () => {
    const { processCalls } = server({ processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByRole('radio', { name: 'VIDEO_LINK' }))
    await userEvent.type(within(dialog).getByTestId('material-title'), 'Upright')
    const url = within(dialog).getByTestId('material-url')
    await userEvent.type(url, 'https://www.youtube.com/shorts/dQw4w9WgXcQ')
    const box = within(dialog).getByRole('checkbox', { name: 'Vertical video' })
    expect(box).toBeChecked()
    expect(box).toBeDisabled()
    expect(within(dialog).getByTestId('material-vertical-hint')).toHaveTextContent('YouTube Shorts link')
    await userEvent.clear(url)
    await userEvent.type(url, 'https://www.bilibili.com/video/BV1GJ411x7h7')
    expect(box).not.toBeChecked()
    await userEvent.click(box)
    await expectAccessible()
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toMatchObject({ kind: 'VIDEO_LINK', vertical: true })
  })

  it('lists every reason a version was refused', async () => {
    const changed = { status: 422, body: { detail: 'Refused', violations: [{ field: 'baseRevision',
      ruleCode: 'QB_QUIZ_CHANGED', message: 'The quiz was changed meanwhile.' }] } }
    server({ processes: { QB_QUIZ_SAVE: [changed], QB_QUIZ_PUBLISH_VERSION: [{ status: 422, body: { detail: 'Refused',
      violations: [
      { field: 'questions[0].options', ruleCode: 'QB_QUESTION_OPTION_COUNT', message: 'Question 1 needs 2 to 6 options.' },
      { field: 'questions[1].stem', ruleCode: 'QB_QUESTION_STEM_REQUIRED', message: 'Question 2 has no text.' },
    ] } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('save-version'))
    const problems = await screen.findByTestId('version-problems')
    expect(within(problems).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'Question 1 needs 2 to 6 options.', 'Question 2 has no text.'])
    // Q78: one notice at a time: a page out of date is the more pressing one.
    await userEvent.click(screen.getByTestId('heading-save'))
    await screen.findByTestId('stale')
    expect(screen.queryByTestId('version-problems')).toBeNull()
  })

  it('says so when the quiz is not the Sponsor\'s', async () => {
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
    const { processCalls, posted } = server({
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
    // F-S6c: the draft reads only the quiz's own translations, by key; the counts are the server's.
    const translationQueries = posted.filter((p) => p.path.endsWith(':QbContentTranslation/query'))
    expect(translationQueries.length).toBeGreaterThan(0)
    expect(translationQueries.every((p) => JSON.stringify(p.body.filters).includes(
      '{"field":"elementKey","op":"in","values":["quiz.title","quiz.intro","quiz.cover"]}'))).toBe(true)
    // English lacks: the title and introduction, question 2's text (out of date), and both options.
    await waitFor(() => expect(within(panel).getByTestId('translation-language')).toHaveTextContent('English (5)'))
    expect(posted.some((p) => p.path === '/api/queries/qb.sponsor.quiz-translation-progress'
      && JSON.stringify(p.body.params) === '{"quizId":"q1"}')).toBe(true)
    // Done, out of date and missing, by the base's hashes.
    await waitFor(() => expect(within(panel).getAllByTestId('translation-text')
      .find((row) => row.dataset.key === 'question:k1.stem')).toHaveAttribute('data-state', 'done'))
    const rows = within(panel).getAllByTestId('translation-text')
    expect(rows.find((row) => row.dataset.key === 'question:k2.stem')).toHaveAttribute('data-state', 'stale')
    expect(rows.find((row) => row.dataset.key === 'option:o1.text')).toHaveAttribute('data-state', 'missing')
    // The materials: the base's shown in its place.
    expect(within(panel).getByTestId('translation-material')).toHaveAttribute('data-own', 'no')
    // Q98b: the cover can be the language's own, first among the images.
    expect(within(panel).getAllByTestId('translation-image')[0]).toHaveAttribute('data-key', 'quiz.cover')
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
      processes: { QB_AI_TRANSLATION_ACCEPT: [edit(8), edit(9)], QB_TRANSLATION_SAVE: [edit(10)] },
    })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    const panel = await screen.findByTestId('translations')
    expect(await within(panel).findByTestId('ai-translate-ready')).toHaveTextContent('2 AI translations')
    const texts = within(panel).getByTestId('translation-texts')
    const title = within(texts).getAllByTestId('translation-text').find((row) => row.dataset.key === 'quiz.title')!
    expect(within(title).getByTestId('ai-suggestion')).toHaveTextContent('Coffee basics (AI)')
    // F-S6c: the questions' suggestions are shown on their page.
    const questions = within(panel).getByTestId('translation-questions')
    await waitFor(() => expect(within(questions).getAllByTestId('translation-text').length).toBeGreaterThan(0))
    const option = within(questions).getAllByTestId('translation-text')
      .find((row) => row.dataset.key === 'option:o1.text')!
    expect(within(option).getByTestId('ai-suggestion')).toHaveTextContent('Coffee (AI)')
    await expectAccessible()

    // Every suggestion of the quiz's texts used at once; each must be checked before the save.
    await userEvent.click(within(texts).getByTestId('ai-translate-use-all'))
    expect(within(title).getByTestId('translation-input')).toHaveValue('Coffee basics (AI)')
    await userEvent.click(within(texts).getByTestId('translation-save'))
    expect(within(texts).getByTestId('ai-translate-review-first')).toBeInTheDocument()
    expect(processCalls('QB_AI_TRANSLATION_ACCEPT')).toEqual([])
    // The title checked as given.
    await userEvent.click(within(title).getByTestId('ai-suggestion-reviewed'))
    await userEvent.click(within(texts).getByTestId('translation-save'))
    await waitFor(() => expect(processCalls('QB_AI_TRANSLATION_ACCEPT')).toHaveLength(1))

    // On the page: every suggestion of the page used, the option changed, then checked; the question translated by
    // hand; saved together.
    await userEvent.click(within(questions).getByTestId('ai-translate-use-all'))
    expect(within(questions).getByTestId('ai-translate-use-all')).toHaveTextContent('on this page')
    await userEvent.clear(within(option).getByTestId('translation-input'))
    await userEvent.type(within(option).getByTestId('translation-input'), 'Coffee')
    await userEvent.click(within(option).getByTestId('ai-suggestion-reviewed'))
    const question = within(questions).getAllByTestId('translation-text')
      .find((row) => row.dataset.key === 'question:k1.stem')!
    await userEvent.type(within(question).getByTestId('translation-input'), 'First?')
    await userEvent.click(within(questions).getByTestId('translation-page-save'))
    await waitFor(() => expect(processCalls('QB_TRANSLATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_AI_TRANSLATION_ACCEPT')).toEqual([
      { aiJobId: 'j1', quizId: 'q1', baseRevision: 7,
        translations: [{ candidateNo: 1, text: 'Coffee basics (AI)', reviewed: true }] },
      { aiJobId: 'j1', quizId: 'q1', baseRevision: 8,
        translations: [{ candidateNo: 2, text: 'Coffee', reviewed: true }] }])
    expect(processCalls('QB_TRANSLATION_SAVE')).toEqual([{ quizId: 'q1', language: 'en', baseRevision: 9,
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

  /** F-S6c: 120 questions in English, as the template answers; question 2 already translated. */
  const TRANSLATION_BANK = Array.from({ length: 120 }, (_, i) => ({ questionId: `b${i + 1}`, questionNo: i + 1,
    stem: i === 60 ? 'Roast coffee?' : `Question ${i + 1}?`, image: i === 50 ? 'img51' : null,
    stemText: i === 1 ? 'Frage 2?' : null, stemState: i === 1 ? 'done' : 'missing', stemBy: null,
    ownImage: null, options: JSON.stringify([
      { optionId: `b${i + 1}o1`, no: 1, text: 'Yes', image: null, translation: i === 1 ? 'Ja' : null,
        state: i === 1 ? 'done' : 'missing', by: null, ownImage: null },
      { optionId: `b${i + 1}o2`, no: 2, text: 'No', image: null, translation: i === 1 ? 'Nein' : null,
        state: i === 1 ? 'done' : 'missing', by: null, ownImage: null }]),
    pending: i === 1 ? 0 : 3 }))
  const translationCalls = (posted: { path: string; body: Record<string, unknown> }[]) =>
    posted.filter((p) => p.path === '/api/queries/qb.sponsor.quiz-translations').map((p) => p.body)

  it('reads the questions a page at a time and saves a translation from the second page (F-S6c)', async () => {
    const { posted, processCalls } = server({ quiz: { ...QUIZ, translationLanguages: 'en', questionCount: 120 },
      translationBank: TRANSLATION_BANK, processes: { QB_TRANSLATION_SAVE: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    const questions = await screen.findByTestId('translation-questions')
    await waitFor(() => expect(within(questions).getAllByTestId('translation-text')).toHaveLength(150))
    expect(translationCalls(posted)[0]).toEqual({ params: { quizId: 'q1', language: 'en' }, offset: 0, limit: 50,
      count: true })
    expect(within(questions).getByTestId('translations-page')).toHaveTextContent('Page 1 of 3')
    const done = within(questions).getAllByTestId('translation-text').find((row) => row.dataset.key === 'question:b2.stem')!
    expect(done).toHaveAttribute('data-state', 'done')
    expect(within(done).getByTestId('translation-input')).toHaveValue('Frage 2?')

    await userEvent.click(within(questions).getByTestId('translations-next'))
    await waitFor(() => expect(within(questions).getAllByTestId('translation-text')[0])
      .toHaveAttribute('data-key', 'question:b51.stem'))
    expect(translationCalls(posted).at(-1)).toEqual({ params: { quizId: 'q1', language: 'en' }, offset: 50,
      limit: 50, count: true })
    // The page's own images: question 51's.
    expect(within(within(questions).getByTestId('translation-page-images')).getByTestId('translation-image'))
      .toHaveAttribute('data-key', 'question:b51.image')
    await expectAccessible()

    const row = within(questions).getAllByTestId('translation-text')[0]
    expect(row).toHaveTextContent('Question 51')
    await userEvent.type(within(row).getByTestId('translation-input'), 'Frage 51?')
    // A change not saved keeps the page.
    expect(within(questions).getByTestId('translations-next')).toBeDisabled()
    expect(within(questions).getByTestId('translations-save-first')).toBeInTheDocument()
    expect(screen.getByTestId('translation-only-pending')).toBeDisabled()
    await userEvent.click(within(questions).getByTestId('translation-page-save'))
    await waitFor(() => expect(processCalls('QB_TRANSLATION_SAVE')).toEqual([{ quizId: 'q1', language: 'en',
      entries: [{ key: 'question:b51.stem', text: 'Frage 51?' }], baseRevision: 7 }]))
  })

  it('searches the questions and shows only what needs doing (F-S6c)', async () => {
    const { posted } = server({ quiz: { ...QUIZ, translationLanguages: 'en', questionCount: 120 },
      translationBank: TRANSLATION_BANK })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-languages'))
    const questions = await screen.findByTestId('translation-questions')
    await waitFor(() => expect(within(questions).getAllByTestId('translation-text')).toHaveLength(150))
    await userEvent.click(within(questions).getByTestId('translations-next'))
    await waitFor(() => expect(translationCalls(posted).at(-1)).toMatchObject({ offset: 50 }))

    // The filter starts again at the first page.
    await userEvent.click(screen.getByTestId('translation-only-pending'))
    await waitFor(() => expect(translationCalls(posted).at(-1)).toEqual({ params: { quizId: 'q1', language: 'en',
      untranslated: true }, offset: 0, limit: 50, count: true }))
    await waitFor(() => expect(within(questions).queryAllByTestId('translation-text')
      .find((row) => row.dataset.key === 'question:b2.stem')).toBeUndefined())
    expect(within(questions).getByTestId('translations-found')).toHaveTextContent('119 questions found')

    await userEvent.type(within(questions).getByTestId('translations-search'), 'roast')
    await userEvent.click(within(questions).getByTestId('translations-search-submit'))
    await waitFor(() => expect(translationCalls(posted).at(-1)).toEqual({ params: { quizId: 'q1', language: 'en',
      q: 'roast', untranslated: true }, offset: 0, limit: 50, count: true }))
    await waitFor(() => expect(within(questions).getAllByTestId('translation-text')[0])
      .toHaveAttribute('data-key', 'question:b61.stem'))
    expect(within(questions).getByTestId('translations-found')).toHaveTextContent('1 question found')
    await expectAccessible()

    await userEvent.clear(within(questions).getByTestId('translations-search'))
    await userEvent.type(within(questions).getByTestId('translations-search'), 'nothing like it')
    await userEvent.click(within(questions).getByTestId('translations-search-submit'))
    expect(await within(questions).findByTestId('translations-no-questions')).toHaveTextContent('No question matches')
  })

  it('reads every translation only for the preview, when it opens (F-S6c)', async () => {
    const { posted } = server({ quiz: { ...QUIZ, translationLanguages: 'en' }, translations: [
      { translationId: 't1', quizId: 'q1', language: 'en', elementKey: 'question:k1.stem', text: 'First, in English?',
        image: null, sourceHash: 'h' }] })
    window.history.replaceState(null, '', '/quizzes/q1/preview')
    render(<App />)
    expect(await screen.findByTestId('preview-language')).toBeInTheDocument()
    const filters = posted.filter((p) => p.path.endsWith(':QbContentTranslation/query')).map((p) => p.body.filters)
    expect(filters).toEqual([[{ field: 'quizId', op: 'eq', value: 'q1' }]])
  })

  it('keeps the base language while the quiz has others', async () => {
    server({ quiz: { ...QUIZ, translationLanguages: 'en' } })
    render(<App />)
    expect(await screen.findByTestId('heading-language')).toBeDisabled()
  })
})

describe('the asset library in the quiz editor (F-C1)', () => {
  beforeEach(() => window.history.replaceState(null, '', '/quizzes/q1'))
  const asset = (more: Record<string, unknown>) => ({ sponsorId: 'u2', description: null, language: 'zh', tags: null,
    translations: null, image: null, pdf: null, audio: null, url: null, durationSec: null, body: null, usage: null,
    prizeMethod: null, prizeDescription: null, digitalDelivery: null, digitalSendDays: null, revision: 2,
    editedAt: '2026-11-01T00:00:00Z', ...more })
  const ASSETS = [
    asset({ assetId: 'a-cover', kind: 'IMAGE', name: 'Autumn cover', image: 'f-cover' }),
    asset({ assetId: 'a-intro', kind: 'TEXT', name: 'Welcome text', body: 'Welcome!', usage: 'QUIZ_INTRO' }),
    asset({ assetId: 'a-redeem', kind: 'TEXT', name: 'At the counter', body: 'Show it', usage: 'REDEEM_DESCRIPTION' }),
    asset({ assetId: 'a-link', kind: 'LINK', name: 'Our blog', description: 'Beans explained',
      url: 'https://example.com/blog' }),
  ]

  it('copies a cover and an introduction from the library and names them with the next save only', async () => {
    const { processCalls } = server({ assets: ASSETS, processes: { QB_QUIZ_SAVE: [edit(8), edit(9), edit(10)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('heading-cover-library-pick'))
    const picker = await screen.findByTestId('asset-picker')
    // Only images suit a cover.
    expect((await within(picker).findAllByTestId('asset-picker-item')).map((item) => item.dataset.id))
      .toEqual(['a-cover'])
    await expectAccessible()
    await userEvent.click(within(picker).getByTestId('asset-picker-item'))
    expect(await screen.findByTestId('heading-cover-current')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('heading-intro-library-pick'))
    const intros = await screen.findAllByTestId('asset-picker-item')
    expect(intros.map((item) => item.dataset.id)).toEqual(['a-intro'])
    await userEvent.click(intros[0])
    await waitFor(() => expect(screen.getByTestId('heading-intro')).toHaveValue('Welcome!'))
    // The introduction changed after copying: it is no longer the asset's copy.
    await userEvent.type(screen.getByTestId('heading-intro'), ' Enjoy.')
    await userEvent.click(screen.getByTestId('heading-save'))
    await waitFor(() => expect(processCalls('QB_QUIZ_SAVE')).toHaveLength(1))
    expect(processCalls('QB_QUIZ_SAVE')[0]).toMatchObject({ cover: 'f-cover', coverAssetId: 'a-cover',
      intro: 'Welcome! Enjoy.' })
    expect(processCalls('QB_QUIZ_SAVE')[0]).not.toHaveProperty('introAssetId')
    // Saved again: the copy was recorded once; the record is not renewed (it would hide "older content").
    await userEvent.click(screen.getByTestId('heading-save'))
    await waitFor(() => expect(processCalls('QB_QUIZ_SAVE')).toHaveLength(2))
    expect(processCalls('QB_QUIZ_SAVE')[1]).not.toHaveProperty('coverAssetId')
  })

  it('saves the introduction to the library, which then counts as its copy', async () => {
    const { processCalls } = server({ assets: ASSETS, processes: {
      QB_ASSET_SAVE: [{ status: 200, body: { output: { assetId: 'a-new', revision: 1 } } }],
      QB_QUIZ_SAVE: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('heading-intro-library-store'))
    const dialog = await screen.findByTestId('asset-store-dialog')
    expect(within(dialog).getByTestId('asset-store-name')).toHaveValue('Coffee basics')
    await userEvent.click(within(dialog).getByTestId('asset-store-save'))
    await waitFor(() => expect(processCalls('QB_ASSET_SAVE')).toEqual([{ assetId: null, kind: 'TEXT',
      name: 'Coffee basics', description: null, language: 'zh', tags: [], confirmed: [], baseRevision: null,
      body: 'About **coffee**.', usage: 'QUIZ_INTRO' }]))
    await waitFor(() => expect(screen.queryByTestId('asset-store-dialog')).not.toBeInTheDocument())
    await userEvent.click(screen.getByTestId('heading-save'))
    await waitFor(() => expect(processCalls('QB_QUIZ_SAVE')).toHaveLength(1))
    expect(processCalls('QB_QUIZ_SAVE')[0]).toMatchObject({ introAssetId: 'a-new' })
  })

  it('copies option images by keyboard, each named with its own option, and drops a question image changed after',
    async () => {
      const photo = asset({ assetId: 'a-photo', kind: 'IMAGE', name: 'Bean photo', image: 'f-photo' })
      const { processCalls } = server({ assets: [...ASSETS, photo],
        processes: { QB_QUESTION_SAVE: [edit(8, { itemId: 'k3' })] } })
      render(<App />)
      await userEvent.click(await screen.findByTestId('tab-questions'))
      await userEvent.click(await screen.findByTestId('question-add'))
      const dialog = await screen.findByTestId('question-dialog')
      await userEvent.type(within(dialog).getByTestId('question-stem'), 'Which is a bean?')
      // The question's image from the library, then removed: no longer the asset's copy.
      await userEvent.click(within(dialog).getByTestId('question-image-library-pick'))
      const images = await screen.findAllByTestId('asset-picker-item')
      expect(images.map((item) => item.dataset.id)).toEqual(['a-cover', 'a-photo'])
      await userEvent.click(images[0])
      await userEvent.click(await within(dialog).findByTestId('question-image-remove'))
      // Option 2's image with the keyboard: its own button (named by the option), the arrows, Enter.
      const pick = within(dialog).getByRole('button', { name: 'Choose from the library: Image of option 2' })
      pick.focus()
      await userEvent.keyboard('{Enter}')
      const picker = await screen.findByTestId('asset-picker')
      await expectAccessible()
      await userEvent.keyboard('{ArrowDown}{Enter}')
      await waitFor(() => expect(screen.queryByTestId('asset-picker')).toBeNull())
      expect(within(within(dialog).getAllByTestId('option')[1]).getByTestId('option-image-current'))
        .toBeInTheDocument()
      expect(picker).not.toBeInTheDocument()
      // A new option first: option 2 keeps its copy by its key, now in third place.
      await userEvent.click(within(dialog).getByTestId('option-add'))
      await userEvent.click(within(dialog).getByRole('button', { name: 'Move option 3 up' }))
      await userEvent.click(within(dialog).getByTestId('question-save'))
      await waitFor(() => expect(processCalls('QB_QUESTION_SAVE')).toHaveLength(1))
      const sent = processCalls('QB_QUESTION_SAVE')[0]
      expect(sent).not.toHaveProperty('imageAssetId')
      expect(sent.image).toBeNull()
      expect(sent.options).toEqual([
        { optionId: null, text: null, image: null, correct: false },
        { optionId: null, text: null, image: null, correct: false },
        { optionId: null, text: null, image: 'f-photo', correct: false, imageAssetId: 'a-photo' },
      ])
    })

  it('saves an option’s image to the library and names it with the save', async () => {
    const { processCalls } = server({ assets: ASSETS, processes: {
      QB_ASSET_SAVE: [{ status: 200, body: { output: { assetId: 'a-new', revision: 1 } } }],
      QB_QUESTION_SAVE: [edit(8, { itemId: 'k3' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await userEvent.click(await screen.findByTestId('question-add'))
    const dialog = await screen.findByTestId('question-dialog')
    await userEvent.click(within(dialog).getAllByTestId('option-image-library-pick')[0])
    await userEvent.click((await screen.findAllByTestId('asset-picker-item'))[0])
    await waitFor(() => expect(screen.queryByTestId('asset-picker')).toBeNull())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save to the library: Image of option 1' }))
    const store = await screen.findByTestId('asset-store-dialog')
    await expectAccessible()
    await userEvent.click(within(store).getByTestId('asset-store-save'))
    await waitFor(() => expect(processCalls('QB_ASSET_SAVE')).toMatchObject([{ kind: 'IMAGE', image: 'f-cover',
      name: 'Image of option 1', language: 'zh' }]))
    await waitFor(() => expect(screen.queryByTestId('asset-store-dialog')).toBeNull())
    await userEvent.click(within(dialog).getByTestId('question-save'))
    await waitFor(() => expect(processCalls('QB_QUESTION_SAVE')).toHaveLength(1))
    expect((processCalls('QB_QUESTION_SAVE')[0].options as Record<string, unknown>[])[0])
      .toMatchObject({ image: 'f-cover', imageAssetId: 'a-new' })
  })

  it('makes a new material from an asset, of the asset’s kind, naming it', async () => {
    const { processCalls } = server({ assets: ASSETS, processes: { QB_MATERIAL_SAVE: [edit(8, { itemId: 'm2' })] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-materials'))
    await userEvent.click(await screen.findByTestId('material-add'))
    const dialog = await screen.findByTestId('material-dialog')
    await userEvent.click(within(dialog).getByTestId('material-library-pick'))
    const items = await screen.findAllByTestId('asset-picker-item')
    // Media of every material kind; no texts for an introduction or a description.
    expect(items.map((item) => item.dataset.id)).toEqual(['a-cover', 'a-link'])
    await userEvent.click(items[1])
    await waitFor(() => expect(within(dialog).getByTestId('material-url')).toHaveValue('https://example.com/blog'))
    expect(within(dialog).getByTestId('material-title')).toHaveValue('Our blog')
    await userEvent.click(within(dialog).getByTestId('material-save'))
    await waitFor(() => expect(processCalls('QB_MATERIAL_SAVE')).toHaveLength(1))
    expect(processCalls('QB_MATERIAL_SAVE')[0]).toMatchObject({ kind: 'LINK', title: 'Our blog',
      description: 'Beans explained', url: 'https://example.com/blog', assetId: 'a-link', baseRevision: 7 })
  })
})

describe('the question bank (F-S6b)', () => {
  beforeEach(() => window.history.replaceState(null, '', '/quizzes/q1'))

  /** 120 questions as the template numbers them; the draft holds only QUESTIONS. */
  const BANK = Array.from({ length: 120 }, (_, i) => ({ questionId: `b${i + 1}`, questionNo: i + 1,
    stem: i === 6 ? 'Roast coffee?' : `Question ${i + 1}?`, image: null, points: 1, selection: null,
    optionCount: 2, translated: 0 }))
  const BIG = { ...QUIZ, questionCount: 120 }
  const queryCalls = (posted: { path: string; body: Record<string, unknown> }[]) =>
    posted.filter((p) => p.path === '/api/queries/qb.sponsor.quiz-questions').map((p) => p.body)

  it('reads the list a page at a time, searches by text or number, and reads an opened question alone', async () => {
    const { posted } = server({ quiz: BIG, bank: BANK })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await waitFor(() => expect(screen.getAllByTestId('question')).toHaveLength(50))
    expect(queryCalls(posted)[0]).toEqual({ params: { quizId: 'q1' }, offset: 0, limit: 50, count: true })
    expect(screen.getByTestId('questions-count')).toHaveTextContent('120 / 1,000 questions')
    // The limits are the template's; one attempt's limit explains drawing.
    expect(screen.getByTestId('questions-draw-hint')).toHaveTextContent('One attempt asks at most 100 questions')
    expect(screen.getByTestId('questions-page')).toHaveTextContent('Page 1 of 3')
    await userEvent.click(screen.getByTestId('questions-next'))
    await waitFor(() => expect(screen.getAllByTestId('question')[0]).toHaveAttribute('data-no', '51'))
    expect(queryCalls(posted).at(-1)).toMatchObject({ offset: 50, limit: 50 })
    await expectAccessible()

    await userEvent.type(screen.getByTestId('questions-search'), '7')
    await userEvent.click(screen.getByTestId('questions-search-submit'))
    await waitFor(() => expect(screen.getAllByTestId('question')).toHaveLength(1))
    expect(queryCalls(posted).at(-1)).toEqual({ params: { quizId: 'q1', no: 7 }, offset: 0, limit: 50, count: true })
    await userEvent.clear(screen.getByTestId('questions-search'))
    await userEvent.type(screen.getByTestId('questions-search'), 'roast')
    await userEvent.click(screen.getByTestId('questions-search-submit'))
    await waitFor(() => expect(queryCalls(posted).at(-1)?.params).toEqual({ quizId: 'q1', q: 'roast' }))
    expect(await screen.findByTestId('questions-found')).toHaveTextContent('1 question found')
    const found = screen.getByTestId('question')
    expect(found).toHaveAttribute('data-no', '7')

    // Not in the draft: its options are read when it opens.
    await userEvent.click(within(found).getByTestId('question-row-toggle'))
    expect(await within(found).findByTestId('question-stem-text')).toHaveTextContent('Roast coffee?')
    expect(posted.some((p) => p.path.endsWith(':QbOption/query')
      && JSON.stringify(p.body.filters).includes('"b7"'))).toBe(true)
    await userEvent.click(screen.getByTestId('questions-search-clear'))
    await waitFor(() => expect(screen.getAllByTestId('question')).toHaveLength(50))
  })

  it('opens with the same requests whatever the size of the bank, the summary counted by the server (F-S6d)',
    async () => {
      const opened = async (options: Server) => {
        const { posted } = server(options)
        const view = render(<App />)
        await userEvent.click(await screen.findByTestId('tab-questions'))
        await waitFor(() => expect(screen.getByTestId('questions-score')).toBeInTheDocument())
        await waitFor(() => expect(screen.getAllByTestId('question').length).toBeGreaterThan(0))
        const paths = (vi.mocked(globalThis.fetch).mock.calls).map(([input]) => decodeURIComponent(new URL(
          input instanceof Request ? input.url : String(input), window.location.origin).pathname))
          // The session's own calls are read once per page load, not per quiz.
          .filter((path) => !path.startsWith('/api/auth/'))
        const summary = screen.getByTestId('questions-summary').textContent
        view.unmount()
        vi.restoreAllMocks()
        return { paths, posted, summary }
      }
      // 120 questions, the 7th with no correct option.
      const bank = BANK.map((row, i) => ({ ...row, points: 2, correctCount: i === 6 ? 0 : i % 2 === 0 ? 1 : 2 }))
      const big = await opened({ quiz: BIG, bank })
      const small = await opened({})
      // No question or option of the draft is read; the same requests either way.
      expect(big.paths.filter((p) => p.includes(':QbQuestion/') || p.includes(':QbOption/'))).toEqual([])
      expect([...new Set(big.paths)].sort()).toEqual([...new Set(small.paths)].sort())
      expect(big.paths.length).toBe(small.paths.length)
      expect(big.posted.find((p) => p.path === '/api/queries/qb.sponsor.quiz-question-summary')?.body)
        .toMatchObject({ params: { quizId: 'q1' } })
      expect(big.summary).toContain('120 / 1,000 questions')
      expect(big.summary).toContain('Full score 240')
      expect(big.summary).toContain('Single choice 59 · multiple choice 60')
      expect(big.summary).toContain('1 question has no correct option')
    })

  it('goes to the first question without a correct option, on its page (F-S6d)', async () => {
    const bank = BANK.map((row, i) => ({ ...row, correctCount: i === 76 ? 0 : 1 }))
    server({ quiz: BIG, bank })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await userEvent.click(await screen.findByTestId('questions-unmarked'))
    await waitFor(() => expect(screen.getByTestId('questions-page')).toHaveTextContent('Page 2 of 3'))
    const row = screen.getAllByTestId('question').find((q) => q.dataset.no === '77')!
    expect(within(row).getByTestId('question-row-toggle')).toHaveAttribute('aria-expanded', 'true')
    expect(within(row).getByTestId('question-unmarked')).toBeInTheDocument()
    await expectAccessible()
  })

  it('moves a question up or down with QB_QUESTION_MOVE and keeps the keyboard on it', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_MOVE: [edit(8), edit(9)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const questions = await screen.findAllByTestId('question')
    expect(within(questions[0]).getByRole('button', { name: 'Move question 1 up' })).toBeDisabled()
    expect(within(questions[1]).getByRole('button', { name: 'Move question 2 down' })).toBeDisabled()
    const up = within(questions[1]).getByRole('button', { name: 'Move question 2 up' })
    await userEvent.click(up)
    await waitFor(() => expect(processCalls('QB_QUESTION_MOVE')).toEqual([{ quizId: 'q1', questionId: 'k2',
      beforeQuestionId: 'k1', baseRevision: 7 }]))
    await waitFor(() => expect(document.activeElement?.getAttribute('data-move')).toMatch(/^k2:/))
    await userEvent.click(within(screen.getAllByTestId('question')[0]).getByRole('button',
      { name: 'Move question 1 down' }))
    // Down from the place before last: to the end.
    await waitFor(() => expect(processCalls('QB_QUESTION_MOVE')[1]).toEqual({ quizId: 'q1', questionId: 'k1',
      beforeQuestionId: null, baseRevision: 8 }))
    // The whole-order reorder stays for others (the import tools, tests); the page no longer sends it.
    expect(processCalls('QB_QUESTION_REORDER')).toEqual([])
  })

  it('moves a question of a large quiz to a typed place, finding the question there', async () => {
    const { processCalls, posted } = server({ quiz: BIG, bank: BANK, processes: { QB_QUESTION_MOVE: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const first = (await screen.findAllByTestId('question'))[0]
    await userEvent.click(within(first).getByRole('button', { name: 'Move question 1 to' }))
    const dialog = await screen.findByTestId('question-move-dialog')
    await userEvent.clear(within(dialog).getByTestId('question-move-place-input'))
    await userEvent.type(within(dialog).getByTestId('question-move-place-input'), '500')
    await userEvent.click(within(dialog).getByTestId('question-move-confirm'))
    expect(within(dialog).getByText('Choose a whole number from 1 to 120.')).toBeInTheDocument()
    await userEvent.clear(within(dialog).getByTestId('question-move-place-input'))
    await userEvent.type(within(dialog).getByTestId('question-move-place-input'), '60')
    await userEvent.click(within(dialog).getByTestId('question-move-confirm'))
    // Going down to 60: before the question now at 61, which is on another page.
    await waitFor(() => expect(processCalls('QB_QUESTION_MOVE')).toEqual([{ quizId: 'q1', questionId: 'b1',
      beforeQuestionId: 'b61', baseRevision: 7 }]))
    expect(queryCalls(posted).some((b) => (b.params as Record<string, unknown>).no === 61)).toBe(true)
    // The list follows it to its page.
    await waitFor(() => expect(screen.getByTestId('questions-page')).toHaveTextContent('Page 2 of 3'))
  })

  it('deletes the selected questions after confirming, and sets their points', async () => {
    const { processCalls } = server({ processes: { QB_QUESTION_DELETE_MANY: [edit(8)],
      QB_QUESTION_POINTS_SET: [edit(9)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await screen.findAllByTestId('question')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select the questions on this page' }))
    expect(screen.getByTestId('bank-selected')).toHaveTextContent('2 selected')
    await userEvent.click(screen.getByTestId('bank-points'))
    const points = await screen.findByTestId('bank-points-dialog')
    await userEvent.clear(within(points).getByTestId('bank-points-input'))
    await userEvent.type(within(points).getByTestId('bank-points-input'), '0')
    await userEvent.click(within(points).getByTestId('bank-points-save'))
    expect(within(points).getByText(/Points are a whole number from 1/)).toBeInTheDocument()
    await userEvent.clear(within(points).getByTestId('bank-points-input'))
    await userEvent.type(within(points).getByTestId('bank-points-input'), '5')
    await userEvent.click(within(points).getByTestId('bank-points-save'))
    await waitFor(() => expect(processCalls('QB_QUESTION_POINTS_SET')).toEqual([{ quizId: 'q1',
      questionIds: ['k1', 'k2'], points: 5, baseRevision: 7 }]))
    await waitFor(() => expect(screen.queryByTestId('bank-selection')).toBeNull())

    await userEvent.click(screen.getByRole('checkbox', { name: 'Select question 2' }))
    await userEvent.click(screen.getByTestId('bank-delete'))
    const confirm = await screen.findByRole('alertdialog')
    expect(confirm).toHaveTextContent('Delete 1 question?')
    await expectAccessible()
    expect(processCalls('QB_QUESTION_DELETE_MANY')).toEqual([])
    await userEvent.click(within(confirm).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(processCalls('QB_QUESTION_DELETE_MANY')).toEqual([{ quizId: 'q1',
      questionIds: ['k2'], baseRevision: 9 }]))
  })

  it('offers no batch points for a survey', async () => {
    server({ quiz: { ...QUIZ, kind: 'SURVEY' } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await screen.findAllByTestId('question')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select question 1' }))
    expect(screen.getByTestId('bank-delete')).toBeInTheDocument()
    expect(screen.queryByTestId('bank-points')).toBeNull()
    // F-S7: nor difficulty: a survey is never drawn by it.
    expect(screen.queryByTestId('bank-difficulty')).toBeNull()
    expect(screen.queryByTestId('questions-difficulty-filter')).toBeNull()
  })

  it('sets the selected questions\' difficulty, shows it on the rows and filters the list by it (F-S7)', async () => {
    const bank = [
      { questionId: 'd1', questionNo: 1, stem: 'Easy one?', image: null, points: 1, selection: null, difficulty: 'EASY',
        optionCount: 2, translated: 0, correctCount: 1, optionImages: false },
      { questionId: 'd2', questionNo: 2, stem: 'No level?', image: null, points: 1, selection: null, difficulty: null,
        optionCount: 2, translated: 0, correctCount: 1, optionImages: false },
    ]
    const { processCalls, posted } = server({ bank, processes: { QB_QUESTION_DIFFICULTY_SET: [edit(8)] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const rows = await screen.findAllByTestId('question')
    expect(within(rows[0]).getByTestId('question-difficulty-badge')).toHaveTextContent('Easy')
    expect(within(rows[1]).queryByTestId('question-difficulty-badge')).toBeNull()

    await userEvent.click(screen.getByRole('combobox', { name: 'Difficulty' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Not set' }))
    await waitFor(() => expect(screen.getAllByTestId('question')).toHaveLength(1))
    expect(queryCalls(posted).at(-1)).toMatchObject({ params: { quizId: 'q1', difficulty: 'NONE' } })
    expect(screen.getByTestId('questions-found')).toHaveTextContent('1')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Select question 2' }))
    await userEvent.click(screen.getByTestId('bank-difficulty'))
    const dialog = await screen.findByTestId('bank-difficulty-dialog')
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Difficulty' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Hard' }))
    await userEvent.click(within(dialog).getByTestId('bank-difficulty-save'))
    await waitFor(() => expect(processCalls('QB_QUESTION_DIFFICULTY_SET')).toEqual([{ quizId: 'q1',
      questionIds: ['d2'], difficulty: 'HARD', baseRevision: 7 }]))
    await waitFor(() => expect(screen.queryByTestId('bank-selection')).toBeNull())
  })

  const FILE = {
    format: 'quizbuks.quiz', formatVersion: 3, exportedAt: '2026-11-08T00:00:00Z',
    quiz: { kind: 'QUIZ', title: 'Beans', intro: null, timeLimitSec: null, cover: null, language: 'zh' },
    materials: [],
    questions: [1, 2, 3].map((n) => ({ stem: `Q${n}`, image: null, points: 1, selection: null,
      options: [{ text: 'A', image: null, correct: true }, { text: 'B', image: null, correct: false }] })),
    files: {},
    translations: { en: { texts: { 'q1.stem': 'E1', 'q3.stem': 'E3', title: 'Beans' }, images: {}, materials: [],
      hashes: { 'q3.stem': 'h3' } } },
  }
  const CHECKED = { status: 200, body: { output: { formatVersion: 3, currentVersion: 3, readers: [3], kind: 'QUIZ',
    title: 'Beans', questions: 3, materials: 0, files: [], language: 'zh', languages: ['en'] } } }
  const imported = (revision: number, questions: number) => ({ status: 200, body: { output: { quizId: 'q1',
    importId: `i${revision}`, questions, revision, skippedLanguages: ['en'] } } })

  it('imports a file\'s questions in batches of the limit, renumbered, with progress and the languages skipped',
    async () => {
      const { processCalls } = server({ limits: { maxQuestions: 1000, maxPerAttempt: 100, importBatch: 2 },
        processes: { QB_QUIZ_IMPORT_CHECK: [CHECKED], QB_QUIZ_IMPORT_QUESTIONS: [imported(8, 2), imported(9, 1)] } })
      render(<App />)
      await userEvent.click(await screen.findByTestId('tab-questions'))
      await waitFor(() => expect(screen.getByTestId('bank-import')).toBeEnabled())
      await userEvent.upload(screen.getByTestId('bank-import-file'), new File([JSON.stringify(FILE)], 'beans.json',
        { type: 'application/json' }))
      expect(await screen.findByTestId('bank-import-summary')).toHaveTextContent('3 questions in 2 batches, 0 files.')
      await userEvent.click(screen.getByTestId('bank-import-confirm'))
      expect(await screen.findByTestId('bank-import-done')).toHaveTextContent('3 questions imported in 2 batches.')
      expect(screen.getByTestId('bank-import-skipped')).toHaveTextContent('English')
      const calls = processCalls('QB_QUIZ_IMPORT_QUESTIONS')
      expect(calls.map((c) => c.baseRevision)).toEqual([7, 8])
      const second = calls[1].document as typeof FILE
      expect(second.questions.map((q) => q.stem)).toEqual(['Q3'])
      expect(second.translations.en).toEqual({ texts: { 'q1.stem': 'E3' }, images: {}, materials: [],
        hashes: { 'q1.stem': 'h3' } })
      // The whole file, then each batch, checked by the server.
      expect(processCalls('QB_QUIZ_IMPORT_CHECK')).toHaveLength(3)
      await expectAccessible()
    })

  it('says what a batch takes by questions and bytes, from the numbers the server gives (F-F11b)', async () => {
    server({ limits: { maxQuestions: 1000, maxPerAttempt: 100, importBatch: 2, importBatchBytes: 3_145_728 },
      processes: { QB_QUIZ_IMPORT_CHECK: [CHECKED] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await waitFor(() => expect(screen.getByTestId('bank-import')).toBeEnabled())
    await userEvent.upload(screen.getByTestId('bank-import-file'), new File([JSON.stringify(FILE)], 'beans.json',
      { type: 'application/json' }))
    expect(await screen.findByTestId('sponsor-bank-batch-info'))
      .toHaveTextContent('A batch takes at most 2 questions or 3 MB, whichever comes first.')
    expect(screen.getByTestId('bank-import-summary')).toHaveTextContent('3 questions in 2 batches')
    expect(screen.queryByTestId('bank-import-too-large')).toBeNull()
    expect(screen.getByTestId('bank-import-confirm')).toBeEnabled()
  })

  it('names the questions too large for one request and does not start (F-F11b)', async () => {
    const { processCalls } = server({ limits: { maxQuestions: 1000, maxPerAttempt: 100, importBatch: 2,
      importBatchBytes: 100 }, processes: { QB_QUIZ_IMPORT_CHECK: [CHECKED] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await waitFor(() => expect(screen.getByTestId('bank-import')).toBeEnabled())
    await userEvent.upload(screen.getByTestId('bank-import-file'), new File([JSON.stringify(FILE)], 'beans.json',
      { type: 'application/json' }))
    expect(await screen.findByTestId('bank-import-too-large')).toHaveTextContent('Question 1, 2, 3 alone takes more than')
    expect(screen.getByTestId('bank-import-confirm')).toBeDisabled()
    expect(processCalls('QB_QUIZ_IMPORT_QUESTIONS')).toHaveLength(0)
    await expectAccessible()
  })

  it('stops at a refused batch, saying which and why; the batches before it stay', async () => {
    server({ limits: { maxQuestions: 1000, maxPerAttempt: 100, importBatch: 2 },
      processes: { QB_QUIZ_IMPORT_CHECK: [CHECKED], QB_QUIZ_IMPORT_QUESTIONS: [imported(8, 2),
        { status: 422, body: { detail: 'Refused', violations: [{ field: null, ruleCode: 'QB_QUIZ_TOO_MANY_QUESTIONS',
          message: 'A quiz has at most 3 questions.' }] } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    await waitFor(() => expect(screen.getByTestId('bank-import')).toBeEnabled())
    await userEvent.upload(screen.getByTestId('bank-import-file'), new File([JSON.stringify(FILE)], 'beans.json',
      { type: 'application/json' }))
    await userEvent.click(await screen.findByTestId('bank-import-confirm'))
    const problems = await screen.findByTestId('bank-import-problems')
    expect(problems).toHaveTextContent('Batch 2 of 2 was not imported. The 2 questions of the batches before it stay')
    expect(problems).toHaveTextContent('Importing the batch: A quiz has at most 3 questions.')
  })

  it('lists the import batches and undoes one, saying what was removed and what stayed', async () => {
    const { processCalls } = server({ imports: [
      { importId: 'i2', quizId: 'q1', questionCount: 2, importedAt: '2026-11-08T03:00:00Z', undoneAt: null,
        removedCount: null },
      { importId: 'i1', quizId: 'q1', questionCount: 5, importedAt: '2026-11-08T02:00:00Z',
        undoneAt: '2026-11-08T02:30:00Z', removedCount: 5 }],
    processes: { QB_QUESTION_IMPORT_UNDO: [{ status: 200, body: { output: { quizId: 'q1', revision: 8, removed: 1,
      kept: ['k9'] } } }] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('tab-questions'))
    const batches = await screen.findAllByTestId('bank-batch')
    expect(batches).toHaveLength(2)
    expect(batches[0]).toHaveTextContent('2 questions')
    expect(within(batches[1]).getByTestId('bank-batch-undone')).toHaveTextContent('5 removed')
    expect(within(batches[1]).queryByTestId('bank-batch-undo')).toBeNull()
    await userEvent.click(within(batches[0]).getByTestId('bank-batch-undo'))
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(processCalls('QB_QUESTION_IMPORT_UNDO')).toEqual([{ quizId: 'q1', importId: 'i2',
      baseRevision: 7 }]))
    expect(await screen.findByTestId('bank-undo-result')).toHaveTextContent(
      '1 questions removed; 1 kept because they were changed.')
  })
})
