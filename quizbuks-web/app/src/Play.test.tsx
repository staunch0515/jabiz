import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'
import { containsPattern, feedFilters, NO_FILTERS } from './pages/FeedPage'
import { forgetAttempts, storeStart } from './play/storage'
import type { StartOutput } from './qb/play'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const PUB = 'pub-1'
const ATTEMPT = 'att-1'

const CARD = { publicationId: PUB, name: 'Capitals week', quizTitle: 'World capitals', cover: null, maxReward: 100,
  completions: 2, sponsorName: 'Alice', sponsorCompany: 'Capitals Inc', sponsorAvatar: null, sponsorCountry: 'JP',
  startAt: '2026-10-01T01:00:00Z', endAt: '2026-10-30T00:00:00Z', timeLimitSec: 300, questionCount: 2,
  fullScore: 5 }

const MATERIALS = JSON.stringify([
  { no: 1, kind: 'ARTICLE', title: 'Read first', description: null, body: 'Paris is **the capital**.', url: null,
    pdf: null, audio: null, images: [] },
  { no: 2, kind: 'VIDEO_LINK', title: 'A tour', description: null, body: null, url: 'https://video.example.com/tour',
    pdf: null, audio: null, images: [] },
])

function publication(mine: Record<string, unknown> = {}) {
  return { ...CARD, intro: 'Two **easy** questions.', materials: MATERIALS, status: 'LIVE', passScore: 3,
    sponsorIntro: null, last1h: 0, last24h: 2, last7d: 2, last30d: 2, statsAt: '2026-10-14T00:00:00Z',
    canStart: true, myAttemptId: null, myStatus: null, myDeadline: null, ...mine }
}

/** The questions as QB_ATTEMPT_START gives them: options shuffled, never which are correct. */
function started(over: Partial<StartOutput> = {}): StartOutput {
  const now = new Date()
  return {
    attemptId: ATTEMPT, publicationId: PUB, status: 'IN_PROGRESS', startedAt: now.toISOString(),
    deadline: new Date(now.getTime() + 300_000).toISOString(), serverTime: now.toISOString(), timeLimitSec: 300,
    currentIndex: 0, resumed: false,
    questions: [
      { no: 1, stem: 'Capital of France?', image: null, points: 2, selection: null,
        options: [{ no: 2, text: 'Lyon', image: null }, { no: 1, text: 'Paris', image: null }] },
      { no: 2, stem: 'Capital of Japan?', image: null, points: 3, selection: null,
        options: [{ no: 1, text: 'Tokyo', image: null }, { no: 2, text: 'Osaka', image: null }] },
    ],
    answers: [],
    kind: 'QUIZ',
    ...over,
  }
}

const SUBMITTED = { attemptId: ATTEMPT, status: 'SUBMITTED', score: 2, fullScore: 5, passScore: 3, passed: false,
  reward: 0, rewardStatus: 'PENDING', message: 'The sponsor is topping up.', questions: [
    { no: 1, selected: [1], correct: [1], points: 2, earned: 2 },
    { no: 2, selected: [2], correct: [1], points: 3, earned: 0 },
  ] }

interface Options {
  start?: () => Response
  submit?: () => Response
  attempt?: Record<string, unknown>
  history?: Record<string, unknown>[]
  emailVerified?: boolean
}

/** The server as the play pages see it; records every call (path and body). */
function server(options: Options = {}) {
  const calls: { path: string; body: unknown; key: string | null }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    calls.push({ path, body, key: request.headers.get('Idempotency-Key') })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.play'], emailVerified: options.emailVerified ?? true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.feed':
        return json(200, { items: [CARD], offset: 0, limit: 20 })
      case '/api/queries/qb.app.publication':
        return json(200, { items: [publication()], offset: 0, limit: 1 })
      case '/api/queries/qb.app.publication-tiers':
        return json(200, { items: [{ seq: 1, fromScore: 0, toScore: 2, reward: 0 },
          { seq: 2, fromScore: 3, toScore: 5, reward: 100 }], offset: 0, limit: 20 })
      case '/api/queries/qb.app.history':
        return json(200, { items: options.history ?? [], offset: 0, limit: 20 })
      case '/api/processes/QB_VIEW/latest':
        return json(200, { output: { publicationId: PUB, firstView: true } })
      case '/api/processes/QB_ATTEMPT_START/latest':
        return options.start ? options.start() : json(200, { output: started() })
      case '/api/processes/QB_ATTEMPT_SAVE/latest': {
        const input = body as { currentIndex: number | null; answers: unknown[] }
        return json(200, { output: { attemptId: ATTEMPT, currentIndex: input.currentIndex ?? 0, answers: input.answers,
          deadline: started().deadline, serverTime: new Date().toISOString() } })
      }
      case '/api/processes/QB_ATTEMPT_SUBMIT/latest':
        return options.submit ? options.submit() : json(200, { output: SUBMITTED })
      case `/api/datasets/urn:jabiz:dataset:taker:QbAttempt/entities/${ATTEMPT}`:
        return options.attempt ? json(200, { id: ATTEMPT, version: 1, attributes: options.attempt }) : json(404, {})
      default:
        return json(404, {})
    }
  })
  return {
    calls,
    posted: (name: string) => calls.filter((c) => c.path === `/api/processes/${name}/latest`).map((c) => c.body),
    keys: (name: string) => calls.filter((c) => c.path === `/api/processes/${name}/latest`).map((c) => c.key),
  }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  forgetAttempts()
  window.history.replaceState(null, '', '/')
})
afterEach(() => vi.restoreAllMocks())

describe('the feed', () => {
  it('lists the quizzes the taker can answer, for their country, in Kudos', async () => {
    const { calls } = server()
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    expect(card).toHaveTextContent('Capitals week')
    expect(card).toHaveTextContent('Capitals Inc')
    expect(card).toHaveTextContent('100 Kudos')
    expect(card).not.toHaveTextContent('JPY')
    const feed = calls.find((c) => c.path === '/api/queries/qb.app.feed')!.body as { params: Record<string, unknown> }
    expect(feed.params).toMatchObject({ country: 'JP', q: null })
    expect(screen.getByTestId('nav-history')).toBeInTheDocument()
  })

  it('searches by text and turns the filters into the template\'s conditions', async () => {
    const { calls } = server()
    render(<App />)
    await screen.findByTestId('feed-card')
    await userEvent.type(screen.getByTestId('feed-search'), 'capital')
    await userEvent.click(screen.getByTestId('feed-search-submit'))
    await waitFor(() => expect(calls.some((c) =>
      (c.body as { params?: { q?: string } } | null)?.params?.q === 'capital')).toBe(true))
    expect(feedFilters({ ...NO_FILTERS, sponsor: ' 50%_off ', rewardMin: '10', rewardMax: 'x', minCompletions: '3',
      sponsorCountry: 'FR' })).toEqual([
      { field: 'sponsorCompany', op: 'like', value: '%50\\%\\_off%' },
      { field: 'sponsorCountry', op: 'eq', value: 'FR' },
      { field: 'maxReward', op: 'gte', value: 10 },
      { field: 'completions', op: 'gte', value: 3 },
    ])
    expect(containsPattern('a\\b')).toBe('%a\\\\b%')
  })
})

describe('a publication\'s page', () => {
  it('shows the materials as Markdown and links, the tiers, and records the first view', async () => {
    const { posted } = server()
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-name')).toHaveTextContent('Capitals week')
    const materials = screen.getByTestId('materials')
    expect(within(materials).getByText('the capital').tagName).toBe('STRONG')
    const link = within(materials).getByTestId('material-link')
    expect(link).toHaveAttribute('href', 'https://video.example.com/tour')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(await screen.findByTestId('detail-tiers')).toHaveTextContent('100 Kudos')
    await waitFor(() => expect(posted('QB_VIEW')).toEqual([{ publicationId: PUB }]))
  })

  it('cannot start before the address is verified, and records no view', async () => {
    const { posted } = server({ emailVerified: false })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-verify-first')).toBeInTheDocument()
    expect(screen.queryByTestId('detail-start')).toBeNull()
    expect(posted('QB_VIEW')).toEqual([])
  })
})

describe('answering', () => {
  it('starts, saves answers and moves, submits after asking, and shows the result with the texts', async () => {
    const { posted } = server()
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await userEvent.click(await screen.findByTestId('detail-start'))
    await waitFor(() => expect(window.location.pathname).toBe(`/play/${ATTEMPT}`))
    expect(posted('QB_ATTEMPT_START')).toEqual([{ publicationId: PUB }])

    // One question per page, its options in the attempt's order, without any sign of the correct ones.
    expect(await screen.findByTestId('play-progress')).toHaveTextContent('Question 1 of 2')
    expect(screen.getByTestId('play-question')).toHaveTextContent('Capital of France?')
    const options = screen.getAllByTestId('play-option')
    expect(options.map((o) => o.textContent)).toEqual(['ALyon', 'BParis'])
    expect(screen.getByTestId('play-timer')).toHaveTextContent(/^[45]:\d\d$/)
    // Only the taker's bottom navigation is gone while answering.
    expect(screen.queryByRole('navigation', { name: 'App' })).toBeNull()

    await userEvent.click(screen.getByRole('checkbox', { name: /Paris/ }))
    await waitFor(() => expect(posted('QB_ATTEMPT_SAVE')).toEqual([
      { attemptId: ATTEMPT, currentIndex: null, answers: [{ questionNo: 1, selected: [1] }] }]), { timeout: 3000 })

    await userEvent.click(screen.getByTestId('play-next'))
    await waitFor(() => expect(posted('QB_ATTEMPT_SAVE')).toHaveLength(2))
    expect(posted('QB_ATTEMPT_SAVE')[1]).toEqual({ attemptId: ATTEMPT, currentIndex: 1, answers: [] })
    expect(screen.getByTestId('play-question')).toHaveTextContent('Capital of Japan?')
    // Focus moves to the new question for screen readers.
    expect(screen.getByTestId('play-question')).toHaveFocus()

    await userEvent.click(screen.getByRole('checkbox', { name: /Osaka/ }))
    await userEvent.click(screen.getByTestId('play-submit'))
    expect(await screen.findByRole('alertdialog')).toHaveTextContent('Submit your answers?')
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Submit answers' }))

    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`))
    expect(posted('QB_ATTEMPT_SUBMIT')).toEqual([{ attemptId: ATTEMPT, publicationId: PUB, currentIndex: 1,
      answers: [{ questionNo: 1, selected: [1] }, { questionNo: 2, selected: [2] }] }])
    expect(await screen.findByTestId('result-score')).toHaveTextContent('2 / 5')
    expect(screen.getByTestId('result-pass-score')).toHaveTextContent('3')
    expect(screen.getByTestId('result-passed')).toHaveTextContent('Not passed')
    const reward = screen.getByTestId('result-reward')
    expect(reward).toHaveAttribute('data-status', 'PENDING')
    expect(reward).toHaveTextContent('The sponsor is topping up.')
    const questions = screen.getAllByTestId('result-question')
    expect(questions[0]).toHaveAttribute('data-correct', 'true')
    expect(within(questions[0]).getByTestId('result-mine')).toHaveTextContent('B. Paris')
    expect(questions[1]).toHaveAttribute('data-correct', 'false')
    expect(within(questions[1]).getByTestId('result-mine')).toHaveTextContent('B. Osaka')
    expect(within(questions[1]).getByTestId('result-correct')).toHaveTextContent('A. Tokyo')
  })

  it('stays on the question when submitting fails, saves the answers again and can submit again', async () => {
    let failures = 1
    const { posted } = server({ submit: () => (failures-- > 0
      ? json(503, { title: 'Service unavailable', status: 503 })
      : json(200, { output: SUBMITTED })) })
    storeStart(started())
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    await screen.findByTestId('play-progress')
    await userEvent.click(screen.getByRole('checkbox', { name: /Paris/ }))
    await userEvent.click(screen.getByTestId('play-next'))
    const submitOnce = async () => {
      await userEvent.click(screen.getByTestId('play-submit'))
      await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Submit answers' }))
    }
    await submitOnce()
    expect(await screen.findByTestId('play-submit-error')).toBeInTheDocument()
    expect(window.location.pathname).toBe(`/play/${ATTEMPT}`)
    await waitFor(() => expect(posted('QB_ATTEMPT_SAVE').at(-1)).toEqual({ attemptId: ATTEMPT, currentIndex: null,
      answers: [{ questionNo: 1, selected: [1] }] }), { timeout: 3000 })
    await submitOnce()
    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`))
    expect(posted('QB_ATTEMPT_SUBMIT')).toHaveLength(2)
  })

  it('says a busy quiz is busy, starts it on the retry with the same key, and submits the same way', async () => {
    const busy = () => json(503, { status: 503, title: 'Busy', reason: 'QUEUE_FULL', retryAfter: 1,
      violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] })
    let startBusy = 1
    let submitBusy = 1
    const { posted, keys } = server({
      start: () => (startBusy-- > 0 ? busy() : json(200, { output: started() })),
      submit: () => (submitBusy-- > 0 ? busy() : json(200, { output: SUBMITTED })),
    })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await userEvent.click(await screen.findByTestId('detail-start'))
    expect(await screen.findByTestId('detail-start-busy')).toHaveTextContent('Many people are answering this quiz')
    await waitFor(() => expect(window.location.pathname).toBe(`/play/${ATTEMPT}`), { timeout: 4000 })
    expect(posted('QB_ATTEMPT_START')).toHaveLength(2)
    const [firstStart, secondStart] = keys('QB_ATTEMPT_START')
    expect(firstStart).toBeTruthy()
    expect(secondStart).toBe(firstStart)

    await screen.findByTestId('play-progress')
    await userEvent.click(screen.getByTestId('play-next'))
    await userEvent.click(screen.getByTestId('play-submit'))
    await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Submit answers' }))
    await waitFor(() => expect(screen.getByTestId('play-submitting')).toHaveAttribute('data-busy', 'true'))
    expect(screen.getByTestId('play-submitting')).toHaveTextContent('trying again')
    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`), { timeout: 4000 })
    // Exactly once: the retry is the same submission (same key, same input), and nothing else was sent.
    expect(posted('QB_ATTEMPT_SUBMIT')).toHaveLength(2)
    expect(posted('QB_ATTEMPT_SUBMIT')[1]).toEqual(posted('QB_ATTEMPT_SUBMIT')[0])
    expect(posted('QB_ATTEMPT_SUBMIT')[0]).toMatchObject({ attemptId: ATTEMPT, publicationId: PUB })
    const [firstSubmit, secondSubmit] = keys('QB_ATTEMPT_SUBMIT')
    expect(secondSubmit).toBe(firstSubmit)
    expect(firstSubmit).not.toBe(firstStart)
  })

  it('stops retrying a quiz that stays busy, says so, and submits again with the same key', async () => {
    let busyFor = 6
    const { keys } = server({ submit: () => (busyFor-- > 0
      ? json(503, { status: 503, title: 'Busy', reason: 'WAIT_TIMEOUT', retryAfter: 0,
        violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] })
      : json(200, { output: SUBMITTED })) })
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      storeStart(started())
      window.history.replaceState(null, '', `/play/${ATTEMPT}`)
      render(<App />)
      await screen.findByTestId('play-progress')
      await userEvent.click(screen.getByTestId('play-next'))
      await userEvent.click(screen.getByTestId('play-submit'))
      await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button',
        { name: 'Submit answers' }))
      for (let i = 0; i < 6 && !screen.queryByTestId('play-submit-error'); i++) {
        await act(async () => {
          await vi.advanceTimersByTimeAsync(10_000)
        })
      }
      expect(await screen.findByTestId('play-submit-error')).toHaveTextContent('Please try again in a moment')
      expect(keys('QB_ATTEMPT_SUBMIT')).toHaveLength(6)
      await userEvent.click(screen.getByTestId('play-submit'))
      await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button',
        { name: 'Submit answers' }))
      await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`))
      expect(new Set(keys('QB_ATTEMPT_SUBMIT')).size).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('saves the last answer before an automatic submission that queues past its time ends EXPIRED', async () => {
    let submits = 0
    const begun = Date.now()
    const late = started({ deadline: new Date(begun + 2_500).toISOString(), currentIndex: 1 })
    const { posted } = server({ start: () => json(200, { output: late }), submit: () => (submits++ === 0
      ? json(503, { status: 503, title: 'Busy', reason: 'WAIT_TIMEOUT', retryAfter: 1,
        violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] })
      : json(422, { status: 422, violations: [{ field: 'attemptId', ruleCode: 'QB_ATTEMPT_EXPIRED',
        message: 'Over' }] })) })
    storeStart(late)
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    await screen.findByTestId('play-progress')
    // An answer within the last moment: its save would come after the deadline, when the submission starts.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, Math.max(0, begun + 2_000 - Date.now())))
    })
    await userEvent.click(screen.getByRole('checkbox', { name: /Tokyo/ }))
    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`), { timeout: 6_000 })
    expect(posted('QB_ATTEMPT_SUBMIT')).toHaveLength(2)
    // Saved before submitting, not dropped: the server's expiry finishes the attempt with it.
    expect(posted('QB_ATTEMPT_SAVE').at(-1)).toMatchObject({ answers: [{ questionNo: 2, selected: [1] }] })
    expect(posted('QB_ATTEMPT_SAVE').length).toBeGreaterThan(0)
  }, 15_000)

  it('continues an attempt through a busy quiz on the play page, saying so', async () => {
    let busyFor = 1
    const { keys } = server({ start: () => (busyFor-- > 0
      ? json(503, { status: 503, title: 'Busy', reason: 'QUEUE_FULL', retryAfter: 1,
        violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] })
      : json(200, { output: started({ resumed: true }) })) })
    storeStart(started())
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('play-loading')).toHaveTextContent('trying again'))
    expect(await screen.findByTestId('play-progress', {}, { timeout: 4_000 })).toHaveTextContent('Question 1 of 2')
    expect(keys('QB_ATTEMPT_START')).toHaveLength(2)
    expect(new Set(keys('QB_ATTEMPT_START')).size).toBe(1)
  })

  it('shows a rate limit\'s own text and does not retry it', async () => {
    const { posted } = server({ start: () => json(429, { status: 429, title: 'Too many requests',
      violations: [{ field: null, ruleCode: 'RATE_LIMITED', message: 'Too many tries: wait a minute.' }] }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await userEvent.click(await screen.findByTestId('detail-start'))
    expect(await screen.findByTestId('detail-start-error')).toHaveTextContent('Too many tries: wait a minute.')
    expect(posted('QB_ATTEMPT_START')).toHaveLength(1)
    expect(screen.getByTestId('detail-start-busy')).toBeEmptyDOMElement()
  })

  it('continues an attempt after a reload at the question and with the answers saved', async () => {
    const { posted } = server({ start: () => json(200, { output: started({ resumed: true, currentIndex: 1,
      answers: [{ questionNo: 1, selected: [1] }, { questionNo: 2, selected: [2] }] }) }) })
    storeStart(started())
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('play-progress')).toHaveTextContent('Question 2 of 2')
    expect(posted('QB_ATTEMPT_START')).toEqual([{ publicationId: PUB }])
    expect(screen.getByRole('checkbox', { name: /Osaka/ })).toBeChecked()
    await userEvent.click(screen.getByTestId('play-previous'))
    expect(screen.getByRole('checkbox', { name: /Paris/ })).toBeChecked()
  })

  it('finds the publication of an attempt this tab never started, then continues it', async () => {
    const { posted } = server({ attempt: { attemptId: ATTEMPT, publicationId: PUB, status: 'IN_PROGRESS',
      deadline: started().deadline } })
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('play-progress')).toHaveTextContent('Question 1 of 2')
    expect(posted('QB_ATTEMPT_START')).toEqual([{ publicationId: PUB }])
  })

  it('submits by itself when the time runs out, and says so', async () => {
    const now = Date.now()
    const { posted } = server({
      start: () => json(200, { output: started({ deadline: new Date(now + 1200).toISOString(),
        serverTime: new Date(now).toISOString() }) }),
    })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await userEvent.click(await screen.findByTestId('detail-start'))
    await screen.findByTestId('play-progress')
    await userEvent.click(screen.getByRole('checkbox', { name: /Paris/ }))
    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`), { timeout: 4000 })
    expect(posted('QB_ATTEMPT_SUBMIT')[0]).toMatchObject({ answers: [{ questionNo: 1, selected: [1] }] })
    expect(await screen.findByTestId('result-automatic')).toBeInTheDocument()
  })

  it('leads to the result of an attempt answered already (the refusal names no attempt)', async () => {
    server({
      start: () => json(422, { title: 'Refused', status: 422, violations: [
        { field: 'publicationId', ruleCode: 'QB_ATTEMPT_ALREADY_TAKEN', message: 'Answered already' }] }),
      attempt: { attemptId: ATTEMPT, publicationId: PUB, status: 'SUBMITTED', score: 5, fullScore: 5, passed: true,
        reward: 100, rewardStatus: 'PAID', result: JSON.stringify([]) },
    })
    storeStart(started())
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`))
    expect(await screen.findByTestId('result-reward')).toHaveAttribute('data-status', 'PAID')
  })

  it('leads to the result when the server says the time is over', async () => {
    server({ start: () => json(422, { title: 'Refused', status: 422, violations: [
      { field: 'publicationId', ruleCode: 'QB_ATTEMPT_EXPIRED', message: 'The time of the attempt is over' }] }) })
    storeStart(started())
    window.history.replaceState(null, '', `/play/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('play-expired')).toBeInTheDocument()
  })
})

describe('a survey', () => {
  /** A survey's start (Q12): a single choice and a multiple choice, a point each, nothing correct. */
  const survey = (): StartOutput => started({ kind: 'SURVEY', questions: [
    { no: 1, stem: 'How often?', image: null, points: 1, selection: 'SINGLE',
      options: [{ no: 1, text: 'Daily', image: null }, { no: 2, text: 'Weekly', image: null }] },
    { no: 2, stem: 'Which ones?', image: null, points: 1, selection: 'MULTIPLE',
      options: [{ no: 1, text: 'Tea', image: null }, { no: 2, text: 'Coffee', image: null }] },
  ] })
  const done = { attemptId: ATTEMPT, status: 'SUBMITTED', score: 2, fullScore: 2, passScore: 2, passed: true,
    reward: 30, rewardStatus: 'PAID', message: null, kind: 'SURVEY', questions: [
      { no: 1, selected: [2], correct: [], points: 1, earned: 1 },
      { no: 2, selected: [1, 2], correct: [], points: 1, earned: 1 },
    ] }

  it('asks for one answer by radio buttons, submits only once every question is answered, and shows no score',
    async () => {
      const { posted } = server({ start: () => json(200, { output: survey() }),
        submit: () => json(200, { output: done }) })
      window.history.replaceState(null, '', `/p/${PUB}`)
      render(<App />)
      await userEvent.click(await screen.findByTestId('detail-start'))
      expect(await screen.findByTestId('play-question')).toHaveTextContent('How often?')
      expect(screen.getByText('Choose one answer.')).toBeInTheDocument()
      expect(screen.queryByText(/Points/)).toBeNull()
      await userEvent.click(screen.getByRole('radio', { name: /Daily/ }))
      await userEvent.click(screen.getByRole('radio', { name: /Weekly/ }))
      // One option holds: the second replaces the first.
      expect(screen.getByRole('radio', { name: /Weekly/ })).toBeChecked()
      expect(screen.getByRole('radio', { name: /Daily/ })).not.toBeChecked()

      await userEvent.click(screen.getByTestId('play-next'))
      expect(screen.getByText('Choose one or more answers.')).toBeInTheDocument()
      expect(screen.getByTestId('play-submit')).toHaveAttribute('aria-disabled', 'true')
      expect(screen.getByTestId('play-incomplete')).toHaveTextContent('1 still open')
      await userEvent.click(screen.getByRole('checkbox', { name: /Tea/ }))
      await userEvent.click(screen.getByRole('checkbox', { name: /Coffee/ }))
      expect(screen.getByTestId('play-submit')).not.toHaveAttribute('aria-disabled')
      expect(screen.queryByTestId('play-incomplete')).toBeNull()
      await userEvent.click(screen.getByTestId('play-submit'))
      await userEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button',
        { name: 'Submit answers' }))

      await waitFor(() => expect(window.location.pathname).toBe(`/result/${ATTEMPT}`))
      expect(posted('QB_ATTEMPT_SUBMIT')).toEqual([{ attemptId: ATTEMPT, publicationId: PUB, currentIndex: 1,
        answers: [{ questionNo: 1, selected: [2] }, { questionNo: 2, selected: [1, 2] }] }])
      expect(await screen.findByTestId('result-survey')).toHaveAttribute('data-complete', 'true')
      expect(screen.queryByTestId('result-score')).toBeNull()
      expect(screen.getByTestId('result-reward')).toHaveAttribute('data-status', 'PAID')
      const questions = screen.getAllByTestId('result-question')
      expect(questions.map((q) => q.getAttribute('data-correct'))).toEqual([null, null])
      expect(within(questions[0]).getByTestId('result-mine')).toHaveTextContent('B. Weekly')
      expect(within(questions[1]).getByTestId('result-mine')).toHaveTextContent('A. Tea, B. Coffee')
    })

  it('says a survey cut short by its time earns nothing, read later from the record alone', async () => {
    server({ attempt: { attemptId: ATTEMPT, publicationId: PUB, status: 'EXPIRED', score: 1, fullScore: 2,
      passed: false, reward: 0, rewardStatus: 'NONE', deadline: '2026-10-01T00:05:00Z',
      result: JSON.stringify([{ no: 1, selected: [1], correct: [], points: 1, earned: 1 },
        { no: 2, selected: [], correct: [], points: 1, earned: 0 }]) } })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-survey')).toHaveAttribute('data-complete', 'false')
    expect(screen.getByTestId('result-reward')).toHaveTextContent('Not every question was answered')
    expect(screen.queryByTestId('result-score')).toBeNull()
  })
})

describe('a result read later', () => {
  it('shows the score and each question right or wrong without the texts, which only the tab kept', async () => {
    server({ attempt: { attemptId: ATTEMPT, publicationId: PUB, status: 'EXPIRED', score: 2, fullScore: 5,
      passed: false, reward: 0, rewardStatus: 'NONE', deadline: '2026-10-01T00:05:00Z',
      result: JSON.stringify(SUBMITTED.questions) } })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-score')).toHaveTextContent('2 / 5')
    expect(screen.getByTestId('result-automatic')).toBeInTheDocument()
    expect(screen.getByTestId('result-reward')).toHaveAttribute('data-status', 'NONE')
    const questions = screen.getAllByTestId('result-question')
    expect(questions.map((q) => q.getAttribute('data-correct'))).toEqual(['true', 'false'])
    expect(screen.queryByTestId('result-mine')).toBeNull()
    expect(await screen.findByTestId('result-pass-score')).toHaveTextContent('3')
    expect(screen.getByTestId('result-name')).toHaveTextContent('Capitals week')
  })

  it('waits while the server finishes an attempt whose time ran out', async () => {
    server({ attempt: { attemptId: ATTEMPT, publicationId: PUB, status: 'IN_PROGRESS',
      deadline: '2026-10-01T00:05:00Z' } })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-finishing')).toBeInTheDocument()
  })
})

describe('the history', () => {
  const row = (over: Record<string, unknown>) => ({ attemptId: ATTEMPT, publicationId: PUB, name: 'Capitals week',
    quizTitle: 'World capitals', cover: null, status: 'SUBMITTED', category: 'PASSED', passed: true, score: 5,
    fullScore: 5, reward: 100, rewardStatus: 'PAID', startedAt: '2026-10-01T01:05:00Z',
    submittedAt: '2026-10-01T01:07:00Z', deadline: '2026-10-01T01:10:00Z', ...over })

  it('lists finished attempts with score and reward, and filters the unfinished ones', async () => {
    const { calls } = server({ history: [row({})] })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const item = await screen.findByTestId('history-row')
    expect(item).toHaveTextContent('Passed')
    expect(item).toHaveTextContent('Score 5 / 5')
    expect(item).toHaveTextContent('Earned 100 Kudos')
    expect(screen.getByTestId('nav-history')).toHaveAttribute('aria-current', 'page')
    await userEvent.click(screen.getByRole('radio', { name: 'Unfinished' }))
    await waitFor(() => expect(calls.some((c) => JSON.stringify(c.body).includes('"values":["IN_PROGRESS","TIMED_OUT"]')))
      .toBe(true))
  })

  it('continues an attempt in progress where it was left', async () => {
    const deadline = new Date(Date.now() + 120_000).toISOString()
    const { posted } = server({ history: [row({ status: 'IN_PROGRESS', category: 'IN_PROGRESS', passed: null,
      score: null, reward: null, rewardStatus: null, submittedAt: null, deadline })],
    start: () => json(200, { output: started({ resumed: true, currentIndex: 1 }) }) })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const button = await screen.findByTestId('history-continue')
    expect(button).toHaveTextContent('minutes left: 2')
    await act(async () => userEvent.click(button))
    await waitFor(() => expect(window.location.pathname).toBe(`/play/${ATTEMPT}`))
    expect(await screen.findByTestId('play-progress')).toHaveTextContent('Question 2 of 2')
    // The play page took the started attempt as handed over: one start only.
    expect(posted('QB_ATTEMPT_START')).toHaveLength(1)
  })

  it('says a busy quiz is busy when continuing, and stops trying once the user leaves', async () => {
    const deadline = new Date(Date.now() + 120_000).toISOString()
    const { posted } = server({ history: [row({ status: 'IN_PROGRESS', category: 'IN_PROGRESS', passed: null,
      score: null, reward: null, rewardStatus: null, submittedAt: null, deadline })],
    start: () => json(503, { status: 503, title: 'Busy', reason: 'QUEUE_FULL', retryAfter: 1,
      violations: [{ field: null, ruleCode: 'PROCESS_BUSY', message: 'Busy' }] }) })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await act(async () => userEvent.click(await screen.findByTestId('history-continue')))
    expect(await screen.findByTestId('history-continue-busy')).toHaveTextContent('Many people are answering')
    // Away to the home page: no more tries, and no late start takes the user back to the quiz.
    await userEvent.click(screen.getByTestId('nav-home'))
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    const tries = posted('QB_ATTEMPT_START').length
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 2_600))
    })
    expect(posted('QB_ATTEMPT_START')).toHaveLength(tries)
    expect(window.location.pathname).toBe('/')
  })
})
