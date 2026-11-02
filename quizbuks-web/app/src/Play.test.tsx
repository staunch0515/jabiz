import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { takeReferralFromAddress } from './referral/api'
import { forgetRedeem, pendingRedeem, rememberRedeem, RESUME_FOR_MS } from './redeemReturn'
import { placeholderIndex } from './qb/Illustrations'
import './messages'
import { stagedPlayer } from './video/stage'
import { activeFilterCount, containsPattern, feedFilters, NO_FILTERS } from './pages/FeedPage'
import { forgetAttempts, storeResult, storeStart } from './play/storage'
import { forgetPlayback } from './video/playback'
import type { StartOutput, SubmitOutput } from './qb/play'

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
  { no: 1, kind: 'ARTICLE', title: 'Read first', description: 'Two lines on France', body: 'Paris is **the capital**.', url: null,
    pdf: null, audio: null, images: [] },
  { no: 2, kind: 'VIDEO_LINK', title: 'A tour', description: null, body: null, url: 'https://video.example.com/tour',
    pdf: null, audio: null, images: [] },
  { no: 3, kind: 'VIDEO_LINK', title: 'On YouTube', description: null, body: null,
    url: 'https://youtu.be/dQw4w9WgXcQ', pdf: null, audio: null, images: [] },
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

/** The result as QB_ATTEMPT_RESULT gives it once finished (Q20): the start's questions and the submission's answers. */
function finished(over: Record<string, unknown> = {}) {
  return { attemptId: ATTEMPT, publicationId: PUB, name: 'Capitals week', status: 'SUBMITTED', kind: 'QUIZ',
    deadline: started().deadline,
    serverTime: new Date().toISOString(), score: SUBMITTED.score, fullScore: SUBMITTED.fullScore,
    passScore: SUBMITTED.passScore, passed: SUBMITTED.passed, reward: SUBMITTED.reward,
    rewardStatus: SUBMITTED.rewardStatus, questions: started().questions, result: SUBMITTED.questions, ...over }
}

/** The content of the digital prize `QB_DIGITAL_PRIZE_OPEN` answers (Q33): a link, so the page offers to open it. */
const PRIZE_LINK = 'https://shop.example.com/voucher/WXYZ'

interface Options {
  /** Answers each read of the publication's page (Q25: none until the view claimed the invitation). */
  publicationPage?: (body: unknown) => Response | Promise<Response>
  start?: () => Response
  submit?: () => Response
  /** The answer of QB_ATTEMPT_RESULT; the finished quiz by default. */
  result?: () => Response
  history?: Record<string, unknown>[]
  emailVerified?: boolean
  feed?: Record<string, unknown>[]
  /** Q63: the feed's page at an offset (10 a page), in place of `feed`. */
  feedPage?: (offset: number) => Record<string, unknown>[]
  /** The taker's open cash rewards (Q22). */
  cashRewards?: Record<string, unknown>[]
  cash?: () => Response
  /** Q22b: the taker's prizes, what a scanned code stands for, and the answer to collecting one. */
  redemptions?: Record<string, unknown>[]
  redeemCheck?: () => Response
  redeemClaim?: () => Response
  /** Q49a: the answer to reporting the quiz. */
  report?: () => Response
  /** Q33: the answer to opening a digital prize's content. */
  digitalOpen?: () => Response
  /** Q47: the taker's stored answers for matching (one row per option, or the birth year). */
  attributes?: Record<string, unknown>[]
  view?: () => Promise<Response> | Response
  /** Q60b: the products the publication shows (the quiz's, number 0, and the questions'). */
  publicationProducts?: Record<string, unknown>[]
  /** Q70: answers to anything else (the referrals' processes and templates); undefined falls back to 404. */
  extra?: (path: string, body: unknown) => Response | undefined
}

/** The server as the play pages see it; records every call (path and body). */
/**
 * The browser's IntersectionObserver, which jsdom lacks (Q63): the test says when a card enters the screen's middle
 * band or the end of the list comes near.
 */
function intersections() {
  const observers: { callback: IntersectionObserverCallback; targets: Element[]; margin: string }[] = []
  class Observer {
    entry: (typeof observers)[number]
    constructor(callback: IntersectionObserverCallback, init?: IntersectionObserverInit) {
      this.entry = { callback, targets: [], margin: init?.rootMargin ?? '' }
      observers.push(this.entry)
    }
    observe(target: Element) { this.entry.targets.push(target) }
    unobserve() {}
    disconnect() { this.entry.targets = [] }
    takeRecords() { return [] }
  }
  vi.stubGlobal('IntersectionObserver', Observer)
  return {
    /** The target crosses (or leaves) the area the observers with this margin watch. */
    fire(target: Element, isIntersecting: boolean, margin: string) {
      act(() => {
        for (const o of observers.filter((o) => o.margin === margin && o.targets.includes(target))) {
          o.callback([{ target, isIntersecting } as unknown as IntersectionObserverEntry], o as never)
        }
      })
    },
  }
}
const MIDDLE = '-40% 0px -40% 0px'
const NEAR_END = '0px 0px 600px 0px'

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
      case '/api/datasets/urn:jabiz:dataset:me:QbProfileAttribute/query':
        return json(200, { items: (options.attributes ?? []).map((attributes, i) => ({ id: String(i), version: 1,
          attributes })), offset: 0, limit: 100 })
      case '/api/processes/QB_PROFILE_ATTRIBUTES_SAVE/latest':
        return json(200, { output: { answered: (body as { profileAnswers: unknown[] }).profileAnswers.length } })
      case '/api/queries/qb.app.feed': {
        const offset = (body as { offset?: number } | null)?.offset ?? 0
        return json(200, { items: options.feedPage ? options.feedPage(offset) : options.feed ?? [CARD], offset, limit: 10 })
      }
      case '/api/queries/qb.app.publication':
        return options.publicationPage ? options.publicationPage(body) : json(200, { items: [publication()], offset: 0, limit: 1 })
      case '/api/queries/qb.app.publication-tiers':
        return json(200, { items: [{ seq: 1, fromScore: 0, toScore: 2, reward: 0 },
          { seq: 2, fromScore: 3, toScore: 5, reward: 100 }], offset: 0, limit: 20 })
      case '/api/queries/qb.app.history':
        return json(200, { items: options.history ?? [], offset: 0, limit: 20 })
      case '/api/queries/qb.app.cash-rewards':
        return json(200, { items: options.cashRewards ?? [], offset: 0, limit: 50 })
      case '/api/queries/qb.app.redemptions':
        return json(200, { items: options.redemptions ?? [], offset: 0, limit: 50 })
      case '/api/processes/QB_REDEEM_TOKEN_CHECK/latest':
        return options.redeemCheck ? options.redeemCheck() : json(200, { output: { sponsorCompany: 'Capitals Inc',
          kind: 'ROTATING', prizes: [{ redemptionId: 'r1', attemptId: ATTEMPT, code: 'C-ABCD1234', amount: 100,
            description: 'A coffee at our counter', publicationName: 'Capitals week', status: 'DUE',
            expiresAt: '2026-11-20T00:00:00Z' }] } })
      case '/api/processes/QB_REDEMPTION_CLAIM/latest':
        return options.redeemClaim ? options.redeemClaim() : json(200, { output: { redemptionId: 'r1', status: 'REDEEMED',
          revision: 2 } })
      case '/api/processes/QB_CASH_REWARD_CONFIRM/latest':
      case '/api/processes/QB_CASH_REWARD_DISPUTE/latest':
        return options.cash ? options.cash() : json(200, { output: { cashRewardId: 'c1', status: 'CONFIRMED', revision: 3 } })
      case '/api/processes/QB_DIGITAL_PRIZE_OPEN/latest':
        return options.digitalOpen ? options.digitalOpen() : json(200, { output: { digitalPrizeId: 'd1',
          status: 'DELIVERED', description: 'A voucher for our online shop', prizeContent: PRIZE_LINK,
          openedAt: '2026-10-21T01:00:00Z' } })
      case '/api/processes/QB_DIGITAL_PRIZE_CONFIRM/latest':
      case '/api/processes/QB_DIGITAL_PRIZE_DISPUTE/latest':
        return json(200, { output: { digitalPrizeId: 'd1', status: 'CONFIRMED', revision: 3 } })
      case '/api/processes/QB_REPORT_SUBMIT/latest':
        return options.report ? options.report() : json(200, { output: { reportId: 'r1' } })
      case '/api/processes/QB_VIEW/latest':
        return options.view ? options.view() : json(200, { output: { publicationId: PUB, firstView: true } })
      case '/api/processes/QB_ATTEMPT_START/latest':
        return options.start ? options.start() : json(200, { output: started() })
      case '/api/processes/QB_ATTEMPT_SAVE/latest': {
        const input = body as { currentIndex: number | null; answers: unknown[] }
        return json(200, { output: { attemptId: ATTEMPT, currentIndex: input.currentIndex ?? 0, answers: input.answers,
          deadline: started().deadline, serverTime: new Date().toISOString() } })
      }
      case '/api/processes/QB_ATTEMPT_SUBMIT/latest':
        return options.submit ? options.submit() : json(200, { output: SUBMITTED })
      case '/api/public/queries/qb.public.publication-products':
        return json(200, { items: options.publicationProducts ?? [], offset: 0, limit: 200 })
      case '/api/processes/QB_ATTEMPT_RESULT/latest':
        return options.result ? options.result() : json(200, { output: finished() })
      default:
        return options.extra?.(path, body) ?? json(404, {})
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
  forgetRedeem()
  window.history.replaceState(null, '', '/')
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

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

  it('shows a cover grid with a placeholder pattern, the reward badge, the sponsor and the completions (Q32)', async () => {
    window.sessionStorage.clear()
    server({ feed: [CARD, { ...CARD, publicationId: 'pub-2', name: 'Rivers', cover: 'file-1', sponsorAvatar: null }] })
    render(<App />)
    const cards = await screen.findAllByTestId('feed-card')
    // Q63: one card a row, the picture across the screen, the name and the sponsor under it.
    expect(screen.getByTestId('feed-list')).toHaveClass('flex-col')
    // No cover: a pattern chosen by the publication's id, decorative.
    expect(within(cards[0]).getByTestId('feed-card-placeholder')).toHaveAttribute('aria-hidden', 'true')
    expect(cards[0]).toHaveTextContent('100 Kudos')
    expect(within(cards[0]).getByTestId('feed-card-sponsor')).toHaveTextContent('Capitals Inc')
    expect(within(cards[0]).getByTestId('feed-card-completions')).toHaveTextContent('Completed: 2')
    expect(within(cards[0]).getByTestId('feed-card-link')).toHaveAccessibleName('Capitals week')
    expect(within(cards[0]).getByTestId('feed-card-link')).toHaveAttribute('href', `/p/${PUB}`)
    expect(within(cards[0]).getByTestId('feed-card-name')).toHaveTextContent('Capitals week')
    // A cover: the image (loaded lazily), no placeholder.
    expect(within(cards[1]).queryByTestId('feed-card-placeholder')).toBeNull()
    expect(placeholderIndex('pub-1')).toBe(placeholderIndex('pub-1'))
    expect(new Set(['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'].map(placeholderIndex)).size).toBeGreaterThan(1)
  })

  it("greets nobody: the cards come first, the page's heading only for assistive technology (Q66)", async () => {
    server()
    render(<App />)
    await screen.findByTestId('feed-card')
    expect(screen.queryByTestId('home-hero')).toBeNull()
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('sr-only')
  })

  it('keeps the filters in the header: the ways a reward is paid and the others in one sheet (Q64)', async () => {
    const { calls } = server()
    render(<App />)
    await screen.findByTestId('feed-card')
    // The search and the filters are the header's; the home has no row of its own.
    expect(screen.queryByRole('search')).toBeNull()
    expect(within(screen.getByRole('banner')).getByTestId('feed-filters-open')).toHaveAccessibleName('Filters')
    expect(screen.queryByTestId('feed-chips')).toBeNull()
    expect(screen.queryByTestId('feed-sort')).toBeNull()
    // By default the answered ones are left out.
    expect(calls.some((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { includeAnswered?: boolean } }).params.includeAnswered === false)).toBe(true)
    await userEvent.click(screen.getByTestId('feed-filters-open'))
    expect([...(await screen.findByTestId('feed-chips')).querySelectorAll('[data-testid]')]
      .map((e) => e.getAttribute('data-testid'))).toEqual(['feed-method-ALL', 'feed-method-PLATFORM',
      'feed-method-SPONSOR_CASH', 'feed-method-OFFLINE_REDEEM', 'feed-method-DIGITAL_PRIZE'])
    expect(screen.getByTestId('feed-method-ALL')).toHaveAttribute('aria-pressed', 'true')
    await expectAccessible()
    await userEvent.click(await screen.findByTestId('feed-filter-answered'))
    await userEvent.click(screen.getByTestId('feed-filters-apply'))
    await waitFor(() => expect(calls.some((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { includeAnswered?: boolean } }).params.includeAnswered === true)).toBe(true))
    expect(screen.getByTestId('feed-filters-dot')).toBeInTheDocument()
    expect(screen.getByTestId('feed-filters-open')).toHaveAccessibleName('Filters, Filters on: 1')
    expect(activeFilterCount({ ...NO_FILTERS, sort: 'popular', includeAnswered: true, minCompletions: '3' })).toBe(3)
  })

  it('shows the empty state with its illustration when nothing matches', async () => {
    const { calls } = server({ feed: [] })
    render(<App />)
    const empty = await screen.findByTestId('home-empty')
    expect(empty).toBeInTheDocument()
    expect(screen.queryByTestId('feed-list')).not.toBeInTheDocument()
    // Q34: the answered ones are hidden; the empty home says so and offers them.
    expect(screen.getByTestId('home-answered-hidden')).toHaveTextContent('Quizzes you have answered are not shown here.')
    await userEvent.click(screen.getByTestId('home-show-answered'))
    await waitFor(() => expect(calls.some((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { includeAnswered?: boolean } }).params.includeAnswered === true)).toBe(true))
    expect(screen.queryByTestId('home-answered-hidden')).toBeNull()
  })

  it('searches by text and turns the filters into the template\'s conditions', async () => {
    const { calls } = server()
    render(<App />)
    await screen.findByTestId('feed-card')
    // Q63: the magnifier beside the header's menu opens the box; a search shows the home's results.
    await userEvent.click(within(screen.getByRole('banner')).getByTestId('search-open'))
    await userEvent.type(screen.getByTestId('feed-search'), 'capital')
    await userEvent.click(screen.getByTestId('feed-search-submit'))
    await waitFor(() => expect(calls.some((c) =>
      (c.body as { params?: { q?: string } } | null)?.params?.q === 'capital')).toBe(true))
    expect(window.location.search).toBe('?q=capital')
    expect(screen.getByTestId('feed-searched')).toHaveTextContent('Results for “capital”')
    expect(screen.queryByTestId('header-search')).toBeNull()
    await expectAccessible()
    // Opened again, it starts from the search; cleared, the home is the whole feed again.
    await userEvent.click(screen.getByTestId('search-open'))
    expect(screen.getByTestId('feed-search')).toHaveValue('capital')
    await userEvent.click(screen.getByTestId('search-close'))
    await userEvent.click(screen.getByTestId('feed-search-clear'))
    expect(window.location.search).toBe('')
    await waitFor(() => expect(screen.queryByTestId('feed-searched')).toBeNull())
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

describe('the ways a reward is paid (Q22)', () => {
  const cash = { cashRewardId: 'c1', status: 'MARKED_PAID', code: 'C-ABCD1234', amount: 100,
    markedPaidAt: '2026-10-20T01:00:00Z', confirmBy: '2026-10-27T01:00:00Z', sponsorCompany: 'Capitals Inc' }

  it('narrows the home to one way, remembers it on the device, and badges cash and prizes', async () => {
    const { calls } = server({ feed: [{ ...CARD, rewardMethod: 'SPONSOR_CASH' }] })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    expect(within(card).getByTestId('feed-card-method')).toHaveAttribute('data-method', 'SPONSOR_CASH')
    // Q64: chips in the header's filters, the one chosen pressed; it counts as a filter on.
    await userEvent.click(screen.getByTestId('feed-filters-open'))
    await userEvent.click(await screen.findByTestId('feed-method-OFFLINE_REDEEM'))
    await waitFor(() => expect(screen.getByTestId('feed-method-OFFLINE_REDEEM')).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.getByTestId('feed-method-ALL')).toHaveAttribute('aria-pressed', 'false')
    await waitFor(() => expect(calls.some((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { method?: string } }).params.method === 'OFFLINE_REDEEM')).toBe(true))
    expect(window.localStorage.getItem('qb.feed.method')).toBe('OFFLINE_REDEEM')
    expect(screen.getByTestId('feed-filters-open')).toHaveAccessibleName('Filters, Filters on: 1')
    window.localStorage.removeItem('qb.feed.method')
  })

  it('lists the cash rewards waiting for the taker on the home, leading to each result', async () => {
    server({ cashRewards: [{ cashRewardId: 'c1', attemptId: ATTEMPT, code: 'C-ABCD1234', publicationId: PUB,
      publicationName: 'Capitals week', sponsorCompany: 'Capitals Inc', amount: 100, status: 'MARKED_PAID',
      dueAt: '2026-10-20T00:00:00Z', markedPaidAt: '2026-10-20T01:00:00Z', confirmBy: '2026-10-27T01:00:00Z' }] })
    render(<App />)
    const notice = await screen.findByTestId('home-cash')
    expect(notice).toHaveTextContent('A cash reward needs your attention')
    expect(within(notice).getByRole('link')).toHaveAttribute('href', `/result/${ATTEMPT}`)
    expect(within(notice).getByTestId('home-cash-item')).toHaveTextContent('C-ABCD1234')
  })

  it('says how a quiz pays on its page, with the prize to collect', async () => {
    vi.spyOn(globalThis, 'fetch').mockRestore?.()
    const { calls } = server()
    calls.length = 0
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await screen.findByTestId('detail')
    // The server's answer is the platform's: no note.
    expect(screen.getByTestId('detail-method')).toHaveAttribute('data-method', 'PLATFORM')
    expect(screen.queryByTestId('detail-method-note')).toBeNull()
  })

  it('shows a cash reward with its code, confirms it, and reports a problem with a note', async () => {
    const { posted } = server({ result: () => json(200, { output: finished({ score: 5, passed: true, reward: 100,
      rewardStatus: 'CASH', rewardMethod: 'SPONSOR_CASH', cash, redeem: null }) }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(reward).toHaveAttribute('data-status', 'CASH')
    expect(reward).toHaveAttribute('data-cash-status', 'MARKED_PAID')
    expect(within(reward).getByTestId('cash-code')).toHaveTextContent('C-ABCD1234')
    expect(reward).toHaveTextContent('Capitals Inc says they paid you')
    await userEvent.click(within(reward).getByTestId('cash-dispute'))
    await userEvent.type(await screen.findByTestId('cash-dispute-note'), 'I got 50, not 100')
    await userEvent.click(screen.getByTestId('cash-dispute-send'))
    await waitFor(() => expect(posted('QB_CASH_REWARD_DISPUTE')).toEqual([{ cashRewardId: 'c1', note: 'I got 50, not 100' }]))
    await waitFor(() => expect(screen.queryByTestId('cash-dispute-dialog')).toBeNull())
    await userEvent.click(within(screen.getByTestId('result-reward')).getByTestId('cash-confirm'))
    await waitFor(() => expect(posted('QB_CASH_REWARD_CONFIRM')).toEqual([{ cashRewardId: 'c1' }]))
  })

  it('offers the sponsor\'s page on the result, whether passed or not (Q64)', async () => {
    server({ result: () => json(200, { output: finished({ score: 0, passed: false, reward: 0, rewardStatus: 'NONE' }) }),
      publicationPage: () => json(200, { items: [publication({ sponsorHandle: 'capitals' })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-passed')).toHaveAttribute('data-passed', 'false')
    const visit = await screen.findByTestId('sponsor-visit')
    expect(visit).toHaveAttribute('href', '/s/capitals')
    expect(visit).toHaveTextContent('See their products and story')
    // Opened first (from the history's link or a mail), the header's arrow goes home.
    expect(screen.getByTestId('header-back')).toBeInTheDocument()
  })

  it('shows a prize with what it is and until when', async () => {
    server({ result: () => json(200, { output: finished({ score: 5, passed: true, reward: 100,
      rewardStatus: 'REDEEM', rewardMethod: 'OFFLINE_REDEEM', cash: null, redeem: { redemptionId: 'r1', status: 'DUE',
      code: 'C-ABCD1234', amount: 100, description: 'A coffee at our counter, Shibuya', expiresAt: '2026-11-20T00:00:00Z',
      sponsorCompany: 'Capitals Inc' } }) }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(reward).toHaveAttribute('data-status', 'REDEEM')
    expect(within(reward).getByTestId('redeem-prize')).toHaveTextContent('A coffee at our counter, Shibuya')
    expect(reward).toHaveTextContent("Collect it at Capitals Inc's place by")
  })
})

describe('collecting a prize by the sponsor\'s code (Q22b)', () => {
  const prizeRow = { redemptionId: 'r1', attemptId: ATTEMPT, code: 'C-ABCD1234', publicationId: PUB,
    publicationName: 'Capitals week', sponsorCompany: 'Capitals Inc', amount: 100, description: 'A coffee at our counter',
    status: 'DUE', dueAt: '2026-10-20T00:00:00Z', expiresAt: '2026-11-20T00:00:00Z', claimedAt: null, confirmBy: null,
    redeemedAt: null }

  it('shows the prizes to collect at the sponsor whose code was scanned, and collects one at once', async () => {
    const { posted } = server({ redemptions: [prizeRow] })
    window.history.replaceState(null, '', '/redeem/rot-key-1')
    render(<App />)
    const page = await screen.findByTestId('redeem-page')
    expect(page).toHaveAttribute('data-kind', 'ROTATING')
    expect(page).toHaveTextContent('Collect your prize at Capitals Inc')
    const prize = screen.getByTestId('redeem-prize')
    expect(prize).toHaveTextContent('A coffee at our counter')
    expect(prize).toHaveTextContent('Code C-ABCD1234')
    await userEvent.click(within(prize).getByTestId('redeem-collect'))
    await waitFor(() => expect(posted('QB_REDEMPTION_CLAIM')).toEqual([{ scannedKey: 'rot-key-1', redemptionId: 'r1' }]))
    const done = await screen.findByTestId('redeem-done')
    expect(done).toHaveAttribute('data-status', 'REDEEMED')
    expect(done).toHaveTextContent('Collected!')
    expect(within(done).getByTestId('redeem-code')).toHaveTextContent('C-ABCD1234')
    expect(within(done).getByTestId('redeem-result-link')).toHaveAttribute('href', `/result/${ATTEMPT}`)
    expect(posted('QB_REDEEM_TOKEN_CHECK')).toEqual([{ scannedKey: 'rot-key-1' }])
  })

  it('waits for the sponsor after a printed code\'s claim, and says when a code no longer works', async () => {
    server({ redemptions: [prizeRow],
      redeemCheck: () => json(200, { output: { sponsorCompany: 'Capitals Inc', kind: 'PRINTED', prizes: [{
        redemptionId: 'r1', attemptId: ATTEMPT, code: 'C-ABCD1234', amount: 100, description: 'A coffee at our counter',
        publicationName: 'Capitals week', status: 'DUE', expiresAt: '2026-11-20T00:00:00Z' }] } }),
      redeemClaim: () => json(200, { output: { redemptionId: 'r1', status: 'CLAIMED', revision: 2 } }) })
    window.history.replaceState(null, '', '/redeem/print-key-1')
    render(<App />)
    expect(await screen.findByTestId('redeem-page')).toHaveTextContent('The sponsor confirms it on their screen')
    await userEvent.click(screen.getByTestId('redeem-collect'))
    const done = await screen.findByTestId('redeem-done')
    expect(done).toHaveAttribute('data-status', 'CLAIMED')
    expect(done).toHaveTextContent('Waiting for the sponsor')
    cleanup()
    server({ redeemCheck: () => json(422, { title: 'Unprocessable', status: 422, violations: [{ ruleCode: 'QB_REDEEM_TOKEN_INVALID' }] }) })
    window.history.replaceState(null, '', '/redeem/old-key')
    render(<App />)
    expect(await screen.findByText('This code does not work any more')).toBeInTheDocument()
    // Q52: the screen shows a new code every minute; scanning it again is one tap away.
    expect(screen.getByTestId('redeem-scan-again')).toHaveAttribute('href', '/scan')
  })

  it('offers to scan again when the code expired while the prize was chosen (Q52)', async () => {
    server({ redemptions: [prizeRow],
      redeemCheck: () => json(200, { output: { sponsorCompany: 'Capitals Inc', kind: 'ROTATING', prizes: [{
        redemptionId: 'r1', attemptId: ATTEMPT, code: 'C-ABCD1234', amount: 100, description: 'A coffee at our counter',
        publicationName: 'Capitals week', status: 'DUE', expiresAt: '2026-11-20T00:00:00Z' }] } }),
      redeemClaim: () => json(422, { title: 'Unprocessable', status: 422, detail: 'This code is no longer valid.',
        violations: [{ ruleCode: 'QB_REDEEM_TOKEN_INVALID', message: 'This code is no longer valid.' }] }) })
    window.history.replaceState(null, '', '/redeem/screen-key')
    render(<App />)
    await userEvent.click(await screen.findByTestId('redeem-collect'))
    expect(await screen.findByTestId('redeem-failed')).toBeInTheDocument()
    expect(screen.getByTestId('redeem-failed-scan')).toHaveAttribute('href', '/scan')
  })

  it('collects at once by the rotating code a prize whose printed code\'s claim still waits', async () => {
    const { posted } = server({ redemptions: [prizeRow],
      redeemCheck: () => json(200, { output: { sponsorCompany: 'Capitals Inc', kind: 'ROTATING', prizes: [{
        redemptionId: 'r1', attemptId: ATTEMPT, code: 'C-ABCD1234', amount: 100, description: 'A coffee at our counter',
        publicationName: 'Capitals week', status: 'CLAIMED', expiresAt: '2026-11-20T00:00:00Z' }] } }) })
    window.history.replaceState(null, '', '/redeem/rot-key-2')
    render(<App />)
    const prize = await screen.findByTestId('redeem-prize')
    expect(prize).toHaveAttribute('data-status', 'CLAIMED')
    expect(within(prize).queryByTestId('redeem-claimed')).toBeNull()
    await userEvent.click(within(prize).getByTestId('redeem-collect'))
    await waitFor(() => expect(posted('QB_REDEMPTION_CLAIM')).toEqual([{ scannedKey: 'rot-key-2', redemptionId: 'r1' }]))
    expect(await screen.findByTestId('redeem-done')).toHaveAttribute('data-status', 'REDEEMED')
  })

  it('goes back to a code scanned before signing in when signing in ended elsewhere, for a few minutes', async () => {
    server({ redemptions: [prizeRow] })
    rememberRedeem('rot-key-3')
    window.history.replaceState(null, '', '/')
    render(<App />)
    expect(await screen.findByTestId('redeem-page')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/redeem/rot-key-3')
    // Reached: not again.
    expect(pendingRedeem()).toBeNull()
    cleanup()
    server({ redemptions: [prizeRow] })
    rememberRedeem('rot-key-4', Date.now() - RESUME_FOR_MS - 1)
    window.history.replaceState(null, '', '/')
    render(<App />)
    expect(await screen.findByTestId('home-redeem')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/')
  })

  it('lists the prizes waiting on the home, and shows a claim waiting for the sponsor on the result', async () => {
    server({ redemptions: [{ ...prizeRow, status: 'CLAIMED', confirmBy: '2026-10-20T01:10:00Z' }],
      result: () => json(200, { output: finished({ score: 5, passed: true, reward: 100, rewardStatus: 'REDEEM',
        rewardMethod: 'OFFLINE_REDEEM', cash: null, redeem: { redemptionId: 'r1', status: 'CLAIMED', code: 'C-ABCD1234',
          amount: 100, description: 'A coffee at our counter', expiresAt: '2026-11-20T00:00:00Z',
          sponsorCompany: 'Capitals Inc', confirmBy: '2026-10-20T01:10:00Z', redeemedAt: null } }) }) })
    render(<App />)
    const notice = await screen.findByTestId('home-redeem')
    expect(notice).toHaveTextContent('A prize is waiting for you')
    expect(within(notice).getByTestId('home-redeem-item')).toHaveAttribute('data-status', 'CLAIMED')
    expect(within(notice).getByRole('link')).toHaveAttribute('href', `/result/${ATTEMPT}`)
    cleanup()
    server({ result: () => json(200, { output: finished({ score: 5, passed: true, reward: 100, rewardStatus: 'REDEEM',
      rewardMethod: 'OFFLINE_REDEEM', cash: null, redeem: { redemptionId: 'r1', status: 'CLAIMED', code: 'C-ABCD1234',
        amount: 100, description: 'A coffee at our counter', expiresAt: '2026-11-20T00:00:00Z',
        sponsorCompany: 'Capitals Inc', confirmBy: '2026-10-20T01:10:00Z', redeemedAt: null } }) }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(reward).toHaveAttribute('data-redeem-status', 'CLAIMED')
    expect(reward).toHaveTextContent("You scanned Capitals Inc's printed code")
    expect(within(reward).getByTestId('redeem-code')).toHaveTextContent('C-ABCD1234')
  })
})

describe('a digital prize (Q33)', () => {
  const digital = { digitalPrizeId: 'd1', status: 'DELIVERED', delivery: 'POOL', amount: 100,
    description: 'A voucher for our online shop', hint: '…WXYZ', deliveredAt: '2026-10-20T01:00:00Z', sendBy: null,
    confirmBy: '2026-10-27T01:00:00Z', openedAt: null, confirmedAt: null, sponsorCompany: 'Capitals Inc' }
  const result = (over: Record<string, unknown>) => () => json(200, { output: finished({ score: 5, passed: true,
    reward: 100, rewardStatus: 'DIGITAL', rewardMethod: 'DIGITAL_PRIZE', cash: null, redeem: null,
    digital: { ...digital, ...over } }) })

  it('shows a delivered prize, reads its content only when asked, copies and links it, and confirms it', async () => {
    const { posted } = server({ result: result({}) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(reward).toHaveAttribute('data-status', 'DIGITAL')
    expect(reward).toHaveAttribute('data-digital-status', 'DELIVERED')
    expect(within(reward).getByTestId('digital-prize')).toHaveTextContent('A voucher for our online shop')
    expect(within(reward).getByTestId('digital-hint')).toHaveTextContent('Code ending …WXYZ')
    // The content is never read with the result (D-Q33-3): only the tap asks for it.
    expect(within(reward).queryByTestId('digital-content')).toBeNull()
    expect(posted('QB_DIGITAL_PRIZE_OPEN')).toEqual([])
    await expectAccessible()
    await userEvent.click(within(reward).getByTestId('digital-show'))
    expect(await within(reward).findByTestId('digital-content')).toHaveTextContent(PRIZE_LINK)
    expect(posted('QB_DIGITAL_PRIZE_OPEN')).toEqual([{ digitalPrizeId: 'd1' }])
    expect(within(reward).queryByTestId('digital-show')).toBeNull()
    const link = within(reward).getByTestId('digital-link')
    expect(link).toHaveAttribute('href', PRIZE_LINK)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link.getAttribute('rel')).toContain('noopener')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await userEvent.click(within(reward).getByTestId('digital-copy'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(PRIZE_LINK))
    await expectAccessible()
    await userEvent.click(within(reward).getByTestId('digital-confirm'))
    await waitFor(() => expect(posted('QB_DIGITAL_PRIZE_CONFIRM')).toEqual([{ digitalPrizeId: 'd1' }]))
  })

  it('shows a code that is no link without one, and keeps showing it when the clipboard is refused', async () => {
    server({ result: result({ delivery: 'SHARED', hint: null }),
      digitalOpen: () => json(200, { output: { digitalPrizeId: 'd1', status: 'DELIVERED',
        description: 'A voucher for our online shop', prizeContent: 'SPRING-2026', openedAt: '2026-10-21T01:00:00Z' } }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(within(reward).queryByTestId('digital-hint')).toBeNull()
    await userEvent.click(within(reward).getByTestId('digital-show'))
    expect(await within(reward).findByTestId('digital-content')).toHaveTextContent('SPRING-2026')
    expect(within(reward).queryByTestId('digital-link')).toBeNull()
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await userEvent.click(within(reward).getByTestId('digital-copy'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('SPRING-2026'))
    expect(within(reward).getByTestId('digital-content')).toHaveTextContent('SPRING-2026')
  })

  it('says when the sponsor sends a due prize, offers only a report, and sends its note', async () => {
    const { posted } = server({ result: result({ status: 'DUE', delivery: 'SPONSOR_SENT', hint: null, deliveredAt: null,
      sendBy: '2026-10-23T01:00:00Z', confirmBy: null }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    const reward = await screen.findByTestId('result-reward')
    expect(reward).toHaveAttribute('data-digital-status', 'DUE')
    expect(reward).toHaveTextContent('Capitals Inc sends it to you by')
    expect(within(reward).queryByTestId('digital-show')).toBeNull()
    expect(within(reward).queryByTestId('digital-confirm')).toBeNull()
    expect(posted('QB_DIGITAL_PRIZE_OPEN')).toEqual([])
    await userEvent.click(within(reward).getByTestId('digital-dispute'))
    await userEvent.type(await screen.findByTestId('digital-dispute-note'), 'Nothing came')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('digital-dispute-send'))
    await waitFor(() => expect(posted('QB_DIGITAL_PRIZE_DISPUTE')).toEqual([{ digitalPrizeId: 'd1', note: 'Nothing came' }]))
    await waitFor(() => expect(screen.queryByTestId('digital-dispute-dialog')).toBeNull())
  })

  it('badges a digital prize on the home and offers it in the filter', async () => {
    const { calls } = server({ feed: [{ ...CARD, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL',
      digitalDescription: 'A voucher for our online shop' }] })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    expect(within(card).getByTestId('feed-card-method')).toHaveAttribute('data-method', 'DIGITAL_PRIZE')
    expect(within(card).getByTestId('feed-card-method')).toHaveTextContent('Digital')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('feed-filters-open'))
    await userEvent.click(await screen.findByTestId('feed-method-DIGITAL_PRIZE'))
    await waitFor(() => expect(calls.some((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { method?: string } }).params.method === 'DIGITAL_PRIZE')).toBe(true))
    window.localStorage.removeItem('qb.feed.method')
  })

  it('says how a quiz with a digital prize pays on its page, with what the prize is', async () => {
    server({ publicationPage: () => json(200, { items: [publication({ rewardMethod: 'DIGITAL_PRIZE',
      digitalDelivery: 'SHARED', digitalDescription: '10% off at our online shop' })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await screen.findByTestId('detail')
    expect(screen.getByTestId('detail-method')).toHaveAttribute('data-method', 'DIGITAL_PRIZE')
    const note = screen.getByTestId('detail-method-note')
    expect(note).toHaveAttribute('data-method', 'DIGITAL_PRIZE')
    expect(within(note).getByTestId('detail-prize')).toHaveTextContent('10% off at our online shop')
    expect(screen.getByTestId('detail-facts')).toHaveTextContent('Value of the prize')
  })

  it('says where a digital prize stands in the history', async () => {
    server({ history: [{ attemptId: ATTEMPT, publicationId: PUB, name: 'Capitals week', quizTitle: 'World capitals',
      cover: null, status: 'SUBMITTED', category: 'PASSED', passed: true, score: 5, fullScore: 5, reward: 100,
      rewardStatus: 'DIGITAL', rewardMethod: 'DIGITAL_PRIZE', digitalPrizeId: 'd1', digitalStatus: 'DELIVERED',
      digitalSendBy: null, startedAt: '2026-10-01T01:05:00Z', submittedAt: '2026-10-01T01:07:00Z',
      deadline: '2026-10-01T01:10:00Z' }] })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const item = await screen.findByTestId('history-digital')
    expect(item).toHaveAttribute('data-status', 'DELIVERED')
    expect(item).toHaveTextContent('Digital prize worth 100 Kudos is ready: show it')
    await expectAccessible()
  })
})

describe('the video to watch (Q19, Q63)', () => {
  const PLAYER = 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'

  it('plays a card\'s video muted while the card is in the middle of the screen, and its start opens the start page',
    async () => {
    const io = intersections()
    const api = server({ feed: [{ ...CARD, video: PLAYER }] })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    expect(card).toHaveAttribute('data-video', 'true')
    // Out of the middle: the picture with a play mark, no player.
    expect(within(card).getByTestId('feed-card-video-mark')).toHaveTextContent('With a video')
    expect(document.querySelector('iframe')).toBeNull()
    io.fire(card, true, MIDDLE)
    // Q65: the app's one player, laid over the card's place; nothing in it is for touching.
    const frame = await screen.findByTitle('Video: Capitals week')
    expect(frame).toHaveAttribute('src', stagedPlayer(PLAYER))
    expect(frame.getAttribute('src')).toContain('mute=1')
    expect(frame.getAttribute('allow')).toContain('autoplay')
    expect(screen.getByTestId('video-open')).toHaveAccessibleName('Video: Capitals week')
    expect(within(card).getByTestId('video-slot')).toHaveAttribute('data-mode', 'card')
    expect(card).toHaveAttribute('data-playing', 'true')
    // Q66: the reward is in the line of facts, the same whether the video plays or not.
    expect(within(card).getByTestId('feed-card-reward')).toHaveTextContent('Up to 100 Kudos')
    io.fire(card, false, MIDDLE)
    await waitFor(() => expect(document.querySelector('iframe')).toBeNull())
    expect(within(card).getByTestId('feed-card-reward')).toHaveTextContent('Up to 100 Kudos')
    // The picture opens the start page first (its time limit and pass score), and starts nothing.
    expect(within(card).getByTestId('feed-card-picture')).toHaveAttribute('href', `/p/${PUB}`)
    await userEvent.click(within(card).getByTestId('feed-card-picture'))
    await waitFor(() => expect(window.location.pathname).toBe(`/p/${PUB}`))
    expect(api.posted('QB_ATTEMPT_START')).toEqual([])
  })

  it('opens the start page from a tap on the playing video, the same player playing on with its sound (Q65)',
    async () => {
    const io = intersections()
    // The start page's data comes a while after the tap: the player waits for its slot.
    server({ feed: [{ ...CARD, video: PLAYER }], publicationPage: () => new Promise<Response>((resolve) => setTimeout(
      () => resolve(json(200, { items: [publication({
        materials: JSON.stringify([{ no: 1, kind: 'VIDEO_LINK', title: 'Watch first', description: null, body: null,
          url: 'https://youtu.be/dQw4w9WgXcQ', pdf: null, audio: null, images: [], watchToAnswer: true }]) })],
        offset: 0, limit: 1 })), 300)) })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    io.fire(card, true, MIDDLE)
    const frame = await screen.findByTitle('Video: Capitals week') as HTMLIFrameElement
    const sent = vi.spyOn(frame.contentWindow!, 'postMessage')
    await userEvent.click(screen.getByTestId('video-open'))
    await waitFor(() => expect(window.location.pathname).toBe(`/p/${PUB}`))
    // The sound is asked for within the tap, through the player's own commands.
    expect(sent.mock.calls.map(([message]) => JSON.parse(String(message)).func)).toEqual(
      expect.arrayContaining(['unMute', 'playVideo']))
    // The start page takes the same player over: not a new one from the start.
    const hero = await screen.findByTestId('detail-hero')
    await waitFor(() => expect(within(hero).getByTestId('video-slot')).toHaveAttribute('data-mode', 'page'))
    expect(document.querySelectorAll('iframe')).toHaveLength(1)
    expect(document.querySelector('iframe')).toBe(frame)
    expect(screen.queryByTestId('video-open')).toBeNull()
    // Q66: no button of the app's own for the sound; the player's are there.
    expect(screen.queryByTestId('video-sound')).toBeNull()
  })

  it("asks for the sound from a tap on a card not playing: the start page's player turns it on once ready (Q66)",
    async () => {
    intersections()
    server({ feed: [{ ...CARD, video: PLAYER }], publicationPage: () => json(200, { items: [publication({
      materials: JSON.stringify([{ no: 1, kind: 'VIDEO_LINK', title: 'Watch first', description: null, body: null,
        url: 'https://youtu.be/dQw4w9WgXcQ', pdf: null, audio: null, images: [], watchToAnswer: true }]) })],
      offset: 0, limit: 1 }) })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    // Out of the middle of the screen: the cover, no player yet.
    expect(document.querySelector('iframe')).toBeNull()
    await userEvent.click(within(card).getByTestId('feed-card-link'))
    await waitFor(() => expect(window.location.pathname).toBe(`/p/${PUB}`))
    const frame = await screen.findByTitle('Watch first') as HTMLIFrameElement
    const sent = vi.spyOn(frame.contentWindow!, 'postMessage')
    act(() => {
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow,
        data: JSON.stringify({ event: 'onReady' }) }))
    })
    expect(sent.mock.calls.map(([message]) => JSON.parse(String(message)).func)).toEqual(
      expect.arrayContaining(['unMute', 'playVideo']))
  })

  it('leaves a start page opened first muted: no tap asked for the sound (Q66)', async () => {
    server({ publicationPage: () => json(200, { items: [publication({
      materials: JSON.stringify([{ no: 1, kind: 'VIDEO_LINK', title: 'Watch first', description: null, body: null,
        url: 'https://youtu.be/dQw4w9WgXcQ', pdf: null, audio: null, images: [], watchToAnswer: true }]) })],
      offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const frame = await screen.findByTitle('Watch first') as HTMLIFrameElement
    const sent = vi.spyOn(frame.contentWindow!, 'postMessage')
    act(() => {
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow,
        data: JSON.stringify({ event: 'onReady' }) }))
    })
    expect(sent.mock.calls.map(([message]) => JSON.parse(String(message)).func)).not.toContain('unMute')
  })

  it('plays one card at a time, the first of those in the middle', async () => {
    const io = intersections()
    server({ feed: [{ ...CARD, video: PLAYER }, { ...CARD, publicationId: 'pub-2', name: 'Rivers', video: PLAYER },
      { ...CARD, publicationId: 'pub-3', name: 'Plain' }] })
    render(<App />)
    const cards = await screen.findAllByTestId('feed-card')
    expect(cards[2]).not.toHaveAttribute('data-video')
    expect(within(cards[2]).queryByTestId('feed-card-progress')).toBeNull()
    io.fire(cards[1], true, MIDDLE)
    io.fire(cards[0], true, MIDDLE)
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    expect(cards[0]).toHaveAttribute('data-playing', 'true')
    io.fire(cards[0], false, MIDDLE)
    expect(cards[1]).toHaveAttribute('data-playing', 'true')
  })

  it('starts nothing by itself for a viewer who asked for less motion', async () => {
    const io = intersections()
    const media = vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({ matches: query.includes('reduce'),
      media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(),
      removeEventListener: vi.fn(), dispatchEvent: vi.fn() }) as MediaQueryList)
    try {
      server({ feed: [{ ...CARD, video: PLAYER }] })
      render(<App />)
      const card = await screen.findByTestId('feed-card')
      io.fire(card, true, MIDDLE)
      expect(document.querySelector('iframe')).toBeNull()
    } finally {
      media.mockRestore()
    }
  })

  it('frames no other address, whatever the server says', async () => {
    const io = intersections()
    server({ feed: [{ ...CARD, video: 'https://evil.example/embed/dQw4w9WgXcQ' }] })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    expect(card).not.toHaveAttribute('data-video')
    io.fire(card, true, MIDDLE)
    expect(document.querySelector('iframe')).toBeNull()
  })
})

describe('the pages of the bottom bar (Q67)', () => {
  it('puts a round badge before the title of each page of the bottom bar, as the account has (Q67)', async () => {
    server()
    render(<App />)
    await screen.findByTestId('home')
    for (const [nav, title] of [['nav-history', 'history-title'], ['nav-wallet', 'wallet-title'],
      ['nav-inbox', 'inbox-title'], ['nav-account', 'account-title']]) {
      await userEvent.click(screen.getByTestId(nav))
      const heading = await screen.findByRole('heading', { level: 1 })
      expect(heading).toHaveAttribute('id', title)
      const badge = heading.closest('section')!.querySelector('[data-testid="title-badge"]')!
      expect(badge).toHaveAttribute('aria-hidden', 'true')
      // The bar's icon for the three pages; the account's initial.
      if (nav === 'nav-account') expect(badge).toHaveTextContent('M')
      else expect(badge.querySelector('svg')).not.toBeNull()
    }
  })
})

describe('a card\'s progress and its sponsor (Q64, Q66)', () => {
  it("says the taker's progress in the line of facts, and links the sponsor's published page from the name and picture",
    async () => {
    const future = new Date(Date.now() + 600_000).toISOString()
    const past = new Date(Date.now() - 600_000).toISOString()
    server({ feed: [{ ...CARD, sponsorHandle: 'capitals' },
      { ...CARD, publicationId: 'pub-2', name: 'Going', myAttemptId: 'att-2', myStatus: 'IN_PROGRESS', myDeadline: future },
      { ...CARD, publicationId: 'pub-3', name: 'Ran out', myAttemptId: 'att-3', myStatus: 'IN_PROGRESS', myDeadline: past },
      { ...CARD, publicationId: 'pub-4', name: 'Done', myAttemptId: 'att-4', myStatus: 'SUBMITTED', myDeadline: past }] })
    render(<App />)
    const cards = await screen.findAllByTestId('feed-card')
    // No start icon (Q66): the whole card opens the start page, which leads on.
    expect(screen.queryByTestId('feed-card-start')).toBeNull()
    expect(within(cards[0]).queryByTestId('feed-card-progress')).toBeNull()
    expect(within(cards[1]).getByTestId('feed-card-progress')).toHaveTextContent('In progress')
    expect(within(cards[2]).getByTestId('feed-card-progress')).toHaveAttribute('data-progress', 'done')
    expect(within(cards[3]).getByTestId('feed-card-progress')).toHaveTextContent('Answered')
    expect(within(cards[3]).getByTestId('feed-card-link')).toHaveAttribute('href', '/p/pub-4')
    // The sponsor of a published page links to it, by name and by picture; another's are not links.
    expect(within(cards[0]).getByTestId('feed-card-sponsor')).toHaveAttribute('href', '/s/capitals')
    expect(within(cards[0]).getByTestId('feed-card-avatar')).toHaveAttribute('href', '/s/capitals')
    expect(within(cards[0]).getByTestId('feed-card-avatar')).toHaveAccessibleName("Capitals Inc's page")
    expect(within(cards[1]).getByTestId('feed-card-sponsor').tagName).toBe('SPAN')
    expect(within(cards[1]).getByTestId('feed-card-avatar').tagName).toBe('SPAN')
    await expectAccessible()
  })

  it('goes back from the start page with the header\'s arrow, and offers the sponsor\'s page there', async () => {
    server({ publicationPage: () => json(200, { items: [publication({ sponsorHandle: 'capitals' })], offset: 0,
      limit: 1 }) })
    render(<App />)
    await userEvent.click(within((await screen.findAllByTestId('feed-card'))[0]).getByTestId('feed-card-link'))
    expect(await screen.findByTestId('detail-name')).toBeInTheDocument()
    expect(screen.queryByText('All quizzes')).toBeNull()
    const visit = await screen.findByTestId('sponsor-visit')
    expect(visit).toHaveAttribute('href', '/s/capitals')
    expect(visit).toHaveTextContent('Visit Capitals Inc')
    await userEvent.click(screen.getByTestId('header-back'))
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(screen.queryByTestId('header-back')).toBeNull()
  })

  it('goes home from a start page opened first, and shows no sponsor page while there is none', async () => {
    server()
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-name')).toBeInTheDocument()
    expect(screen.queryByTestId('sponsor-visit')).toBeNull()
    await userEvent.click(screen.getByTestId('header-back'))
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })
})

describe('the feed as the screen scrolls (Q63)', () => {
  it('reads ten at first and the next ten as the end of the list comes near, until there are no more', async () => {
    const io = intersections()
    const page = (offset: number) => (offset >= 20 ? [] : Array.from({ length: 10 }, (_, i) => ({ ...CARD,
      publicationId: `pub-${offset + i}`, name: `Quiz ${offset + i}` })))
    const { calls } = server({ feedPage: page })
    render(<App />)
    await waitFor(() => expect(screen.getAllByTestId('feed-card')).toHaveLength(10))
    const reads = () => calls.filter((c) => c.path === '/api/queries/qb.app.feed').map((c) =>
      (c.body as { offset: number; limit: number }))
    expect(reads()).toEqual([expect.objectContaining({ offset: 0, limit: 10 })])
    io.fire(screen.getByTestId('feed-end'), true, NEAR_END)
    await waitFor(() => expect(screen.getAllByTestId('feed-card')).toHaveLength(20))
    expect(reads().at(-1)).toMatchObject({ offset: 10, limit: 10 })
    io.fire(screen.getByTestId('feed-end'), true, NEAR_END)
    await waitFor(() => expect(reads().at(-1)).toMatchObject({ offset: 20 }))
    await waitFor(() => expect(screen.queryByTestId('feed-more')).toBeNull())
    io.fire(screen.getByTestId('feed-end'), true, NEAR_END)
    expect(reads()).toHaveLength(3)
  })
})

describe('a publication\'s page', () => {
  it('shows the materials as Markdown and links, the tiers, and records the first view', async () => {
    const { posted } = server()
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-name')).toHaveTextContent('Capitals week')
    const materials = screen.getByTestId('materials')
    // Q34: a row per material with its title and its description on one line; the article opens on a sheet.
    // Q63: the video the app plays leads the page, playing muted as it opens; it is not among the materials again.
    const hero = screen.getByTestId('detail-hero')
    // Q65: the app's one player over the hero's place, started muted.
    expect(await screen.findByTitle('On YouTube'))
      .toHaveAttribute('src', stagedPlayer('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'))
    expect(within(hero).getByTestId('video-slot')).toHaveAttribute('data-mode', 'page')
    expect(within(hero).getByRole('heading', { level: 1 })).toHaveTextContent('Capitals week')
    const rows = within(materials).getAllByTestId('material')
    expect(rows.map((row) => row.getAttribute('data-kind'))).toEqual(['ARTICLE', 'VIDEO_LINK'])
    expect(within(rows[0]).getByTestId('material-title')).toHaveTextContent('Read first')
    expect(within(rows[0]).getByTestId('material-description')).toHaveTextContent('Two lines on France')
    expect(within(materials).queryByText('the capital')).toBeNull()
    await userEvent.click(within(rows[0]).getByTestId('material-open'))
    const sheet = await screen.findByTestId('material-sheet')
    expect(within(sheet).getByRole('heading', { name: 'Read first' })).toBeInTheDocument()
    expect(within(sheet).getByText('the capital').tagName).toBe('STRONG')
    await expectAccessible()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('material-sheet')).toBeNull())
    // A video the app cannot play is a row that goes to its site in a new tab.
    const link = within(materials).getByTestId('material-link')
    expect(link).toHaveAttribute('href', 'https://video.example.com/tour')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(within(materials).queryByTitle('On YouTube')).toBeNull()
    expect(await screen.findByTestId('detail-tiers')).toHaveTextContent('100 Kudos')
    await waitFor(() => expect(posted('QB_VIEW')).toEqual([{ publicationId: PUB }]))
  })

  it('links the official rules of a quiz with a reward, and reports the quiz with a reason and a note (Q49a)',
    async () => {
      let refuse = true
      const { posted } = server({ report: () => refuse
        ? json(422, { status: 422, code: 'QB_REPORT_ALREADY_REPORTED',
          detail: 'You reported this quiz already; we are looking into it.' })
        : json(200, { output: { reportId: 'r1' } }) })
      window.history.replaceState(null, '', `/p/${PUB}`)
      render(<App />)
      const footer = await screen.findByTestId('detail-footer')
      expect(await within(footer).findByTestId('detail-contest-rules')).toHaveAttribute('href', '/legal/contest-rules')
      expect(within(footer).getByTestId('detail-contest-rules')).toHaveTextContent('Official rules of reward campaigns')

      await userEvent.click(within(footer).getByTestId('report-open'))
      const dialog = await screen.findByTestId('report-dialog')
      expect(within(dialog).getByTestId('report-send')).toBeDisabled()
      await userEvent.click(within(dialog).getByTestId('report-reason-INAPPROPRIATE'))
      await userEvent.type(within(dialog).getByTestId('report-note'), '  Rude words  ')
      await expectAccessible()
      await userEvent.click(within(dialog).getByTestId('report-send'))
      expect(await within(dialog).findByTestId('report-error')).toHaveTextContent('reported this quiz already')
      refuse = false
      await userEvent.click(within(dialog).getByTestId('report-send'))
      await waitFor(() => expect(screen.queryByTestId('report-dialog')).toBeNull())
      expect(posted('QB_REPORT_SUBMIT')).toEqual([
        { publicationId: PUB, reason: 'INAPPROPRIATE', note: 'Rude words' },
        { publicationId: PUB, reason: 'INAPPROPRIATE', note: 'Rude words' }])
    })

  it('opens a long introduction in place, and a link with a description on the sheet (Q34)', async () => {
    // jsdom lays nothing out: the introduction's content says it is taller than three lines.
    const height = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(400)
    try {
      server({ publicationPage: () => json(200, { items: [publication({ materials: JSON.stringify([
        { no: 1, kind: 'LINK', title: 'Origins', description: 'Where the beans grow.\nAnd who grows them.', body: null,
          url: 'https://example.com/origins', pdf: null, audio: null, images: [] }]) })], offset: 0, limit: 1 }) })
      window.history.replaceState(null, '', `/p/${PUB}`)
      render(<App />)
      const toggle = await screen.findByTestId('detail-intro-text-toggle')
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(toggle).toHaveAttribute('aria-controls')
      await userEvent.click(toggle)
      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      expect(toggle).toHaveTextContent('Show less')
      await userEvent.click(toggle)
      // Still more to show after closing it again.
      expect(toggle).toHaveTextContent('Show more')
      // The link has a description: its row opens the sheet with the description in full and the link.
      await userEvent.click(screen.getByTestId('material-open'))
      const sheet = await screen.findByTestId('material-sheet')
      expect(within(sheet).getByText(/And who grows them/)).toBeInTheDocument()
      expect(within(sheet).getByTestId('material-link')).toHaveAttribute('href', 'https://example.com/origins')
    } finally {
      height.mockRestore()
    }
  })

  it('lays the page out as a picture, one row of facts and titled cards (Q34)', async () => {
    // Without the embedded video: axe cannot look into a frame in jsdom.
    server({ publicationPage: () => json(200, { items: [publication({ quizTitle: 'World capitals',
      sponsorIntro: 'A shop by the river.', materials: JSON.stringify(JSON.parse(MATERIALS).slice(0, 2)) })],
      offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const hero = await screen.findByTestId('detail-hero')
    expect(within(hero).getByRole('heading', { level: 1 })).toHaveTextContent('Capitals week')
    expect(within(hero).getByTestId('detail-quiz-title')).toHaveTextContent('World capitals')
    expect(within(hero).getByTestId('detail-sponsor')).toHaveTextContent('Capitals Inc')
    // Q65: the six facts in a line or two of small type, each saying what it is (the term for assistive technology).
    const facts = screen.getByTestId('detail-facts')
    expect(facts.querySelectorAll('dt')).toHaveLength(6)
    expect(facts).toHaveTextContent('2 questions')
    expect(facts).toHaveTextContent('Pass 3/5')
    expect(facts).toHaveTextContent('Until')
    expect(facts).toHaveClass('flex-wrap')
    // The introduction without a card of its own; the reward folded to one line, its details inside.
    expect(screen.getByTestId('detail-intro')).toHaveAccessibleName('About this quiz')
    expect(screen.getByTestId('detail-reward-line')).toHaveTextContent('Your reward: Kudos')
    expect(screen.getByTestId('detail-reward')).not.toHaveAttribute('open')
    expect(screen.getByTestId('detail-reward')).toHaveTextContent('Kudos paid by QuizBuks')
    // The counts in one line, the details and the sponsor folded.
    expect(screen.getByTestId('detail-stats-line')).toHaveTextContent('2 in the last 24 hours · 2 in total')
    expect(screen.getByTestId('detail-stats')).not.toHaveAttribute('open')
    expect(screen.getByTestId('detail-sponsor-intro')).not.toHaveAttribute('open')
    await expectAccessible()
  })

  it('shows an invited publication once the view claimed the invitation (Q25)', async () => {
    let reads = 0
    let claim: (response: Response) => void = () => undefined
    const { posted } = server({ publicationPage: () => json(200, { items: ++reads === 1 ? []
      : [publication({ audience: 'INVITED', invited: true })], offset: 0, limit: 1 }),
    view: () => new Promise<Response>((resolve) => { claim = resolve }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    // The first read finds nothing while the view is claiming the invitation: still loading, never "not available".
    await waitFor(() => expect(reads).toBe(1))
    await waitFor(() => expect(posted('QB_VIEW')).toHaveLength(1))
    expect(screen.queryByTestId('detail-not-found')).toBeNull()
    claim(json(200, { output: { publicationId: PUB, firstView: true } }))
    expect(await screen.findByTestId('detail-invited')).toHaveTextContent('By invitation')
    expect(screen.getByTestId('detail-start')).toBeInTheDocument()
    expect(posted('QB_VIEW')).toEqual([{ publicationId: PUB }])
    expect(reads).toBe(2)
  })

  it('reads once more a first read still under way when the view ends, and then says the quiz is not available',
    async () => {
    let reads = 0
    // The first read ends only after the view: it may have missed what the view claimed, so it is read again.
    let firstRead: (response: Response) => void = () => undefined
    const { posted } = server({ publicationPage: () => ++reads === 1
      ? new Promise<Response>((resolve) => { firstRead = resolve })
      : json(200, { items: [], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await waitFor(() => expect(posted('QB_VIEW')).toHaveLength(1))
    expect(screen.queryByTestId('detail-not-found')).toBeNull()
    firstRead(json(200, { items: [], offset: 0, limit: 1 }))
    expect(await screen.findByTestId('detail-not-found')).toBeInTheDocument()
    expect(reads).toBe(2)
  })

  it('says the quiz is not available when the view fails, without reading again', async () => {
    let reads = 0
    server({ publicationPage: () => json(200, { items: [], offset: 0, limit: (++reads, 1) }),
      view: () => json(422, { title: 'Unprocessable', status: 422 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-not-found')).toBeInTheDocument()
    expect(reads).toBe(1)
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

describe('certification rounds (Q70a)', () => {
  it('says until when the taker holds the certification instead of offering its round', async () => {
    const { posted } = server({ publicationPage: () => json(200, { items: [publication({ canStart: false,
      heldUntil: '2027-10-31T00:00:00Z' })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const notice = await screen.findByTestId('detail-certification-held')
    expect(notice).toHaveTextContent('You hold this certification')
    expect(notice).toHaveTextContent('2027')
    expect(screen.queryByTestId('detail-start')).toBeNull()
    expect(screen.queryByTestId('detail-unavailable')).toBeNull()
    expect(posted('QB_ATTEMPT_START')).toEqual([])
  })
})

describe('prerequisites (Q76)', () => {
  it('says what to complete first, linking each one not done, instead of offering the start', async () => {
    const { posted } = server({ publicationPage: () => json(200, { items: [publication({ canStart: false,
      prerequisitesMet: false, prerequisites: JSON.stringify([
        { publicationId: 'pub-0', requirement: 'PASSED', name: 'Basics', met: true },
        { publicationId: 'pub-9', requirement: 'ANSWERED', name: 'Practice', met: false },
        { publicationId: 'pub-8', requirement: 'PASSED', name: null, met: false }]) })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    const { container } = render(<App />)
    const notice = await screen.findByTestId('detail-prerequisites')
    expect(notice).toHaveTextContent('Complete these first to answer this quiz:')
    const items = within(notice).getAllByTestId('detail-prerequisite')
    expect(items[0]).toHaveAttribute('data-met', 'true')
    expect(items[0]).toHaveTextContent('Basicsdone')
    expect(within(items[0]).queryByRole('link')).toBeNull()
    expect(within(items[1]).getByRole('link', { name: 'Practice' })).toHaveAttribute('href', '/p/pub-9')
    expect(items[1]).toHaveTextContent('answer it')
    expect(within(items[2]).getByRole('link', { name: 'A quiz not open now' })).toHaveAttribute('href', '/p/pub-8')
    expect(screen.queryByTestId('detail-start')).toBeNull()
    expect(screen.queryByTestId('detail-unavailable')).toBeNull()
    expect(posted('QB_ATTEMPT_START')).toEqual([])
    await expectAccessible(container)
  })

  it('offers the start once they are met', async () => {
    server({ publicationPage: () => json(200, { items: [publication({ prerequisitesMet: true,
      prerequisites: JSON.stringify([{ publicationId: 'pub-0', requirement: 'PASSED', name: 'Basics', met: true }]) })],
    offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('detail-start')).toBeEnabled()
    expect(screen.queryByTestId('detail-prerequisites')).toBeNull()
  })
})

describe('referrals (Q70)', () => {
  const POLICY = { quota: 20, method: 'PLATFORM', amount: '300', description: null, referrerRequires: 'NONE',
    refereeRules: ['NOT_REGULAR'], days: 7 }
  const KEY = '0123456789abcdef0123456789abcdef'

  it('offers a referral link, made on request, with its places left and no sending for the referrer', async () => {
    const { posted } = server({ publicationPage: () => json(200, { items: [publication({
      referral: JSON.stringify(POLICY) })], offset: 0, limit: 1 }),
    extra: (path) => path === '/api/processes/QB_REFERRAL_LINK/latest'
      ? json(200, { output: { publicationId: PUB, linkKey: KEY, quotaLeft: 19 } }) : undefined })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    expect(await screen.findByTestId('referral-offer')).toHaveTextContent('you get 300 Kudos')
    await userEvent.click(screen.getByTestId('referral-make'))
    expect(await screen.findByTestId('referral-url')).toHaveValue(`http://localhost:3000/p/${PUB}?ref=${KEY}`)
    expect(screen.getByTestId('referral-link')).toHaveTextContent('Referral places left: 19')
    expect(posted('QB_REFERRAL_LINK')).toEqual([{ publicationId: PUB }])
  })

  it('says why no link can be made, as the server says', async () => {
    server({ publicationPage: () => json(200, { items: [publication({ referral: JSON.stringify(POLICY) })],
      offset: 0, limit: 1 }),
    extra: (path) => path === '/api/processes/QB_REFERRAL_LINK/latest'
      ? json(422, { title: 'Unprocessable', status: 422, violations: [{ field: 'publicationId',
        ruleCode: 'QB_REFERRAL_NOT_REFERRER', message: 'Pass the referrer certification quiz first.' }] })
      : undefined })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    await userEvent.click(await screen.findByTestId('referral-make'))
    expect(await screen.findByTestId('referral-error')).toHaveTextContent('Pass the referrer certification quiz first.')
  })

  it('claims a friend\'s link once the friend is signed in, and tells them until when it counts', async () => {
    const claims: unknown[] = []
    let claimed = false
    server({ publicationPage: () => json(200, { items: [publication(claimed ? { myReferralStatus: 'REFERRED',
      myReferralExpiresAt: '2026-11-08T00:00:00Z' } : {})], offset: 0, limit: 1 }),
    extra: (path, body) => {
      if (path !== '/api/processes/QB_REFERRAL_CLAIM/latest') return undefined
      claims.push(body)
      claimed = true
      return json(200, { output: { publicationId: PUB, counted: true, expiresAt: '2026-11-08T00:00:00Z' } })
    } })
    window.history.replaceState(null, '', `/p/${PUB}?ref=${KEY}`)
    takeReferralFromAddress()
    expect(window.location.search).toBe('')
    render(<App />)
    await waitFor(() => expect(claims).toEqual([{ linkKey: KEY }]))
    expect(await screen.findByTestId('referral-referred')).toHaveTextContent('A friend referred you')
  })

  it('lists the referrer\'s links and friends without names, and a rewarded one\'s cash reward', async () => {
    server({ extra: (path) => {
      if (path === '/api/queries/qb.app.referral-links') {
        return json(200, { items: [{ linkId: 'l1', linkKey: KEY, publicationId: PUB, publicationName: 'Capitals week',
          referral: JSON.stringify(POLICY), status: 'LIVE', endAt: '2026-11-20T00:00:00Z', createdAt:
          '2026-10-20T00:00:00Z', placesLeft: 18, broughtByMe: 2 }], offset: 0, limit: 100 })
      }
      if (path === '/api/queries/qb.app.referrals') {
        return json(200, { items: [
          { referralId: 'r1', publicationId: PUB, publicationName: 'Capitals week', status: 'REWARDED',
            referredAt: '2026-10-21T00:00:00Z', expiresAt: '2026-10-28T00:00:00Z', rewardMethod: 'SPONSOR_CASH',
            amount: 300, rewardedAt: '2026-10-22T00:00:00Z', endedAt: '2026-10-22T00:00:00Z' },
          { referralId: 'r2', publicationId: PUB, publicationName: 'Capitals week', status: 'REFERRED',
            referredAt: '2026-10-25T00:00:00Z', expiresAt: '2026-11-01T00:00:00Z', rewardMethod: 'SPONSOR_CASH',
            amount: 300, rewardedAt: null, endedAt: null }], offset: 0, limit: 100 })
      }
      if (path === '/api/processes/QB_REFERRAL_VIEW/latest') {
        return json(200, { output: { referralId: 'r1', publicationId: PUB, publicationName: 'Capitals week',
          status: 'REWARDED', referredAt: '2026-10-21T00:00:00Z', expiresAt: '2026-10-28T00:00:00Z',
          rewardMethod: 'SPONSOR_CASH', amount: 300, rewardedAt: '2026-10-22T00:00:00Z', endedAt: null,
          cash: { cashRewardId: 'c1', status: 'MARKED_PAID', code: 'C-ABCD1234', amount: 300,
            markedPaidAt: '2026-10-23T00:00:00Z', confirmBy: '2026-10-30T00:00:00Z', sponsorCompany: 'Capitals Inc' },
          redeem: null, digital: null } })
      }
      return undefined
    } })
    window.history.replaceState(null, '', '/referrals')
    render(<App />)
    expect(await screen.findByTestId('referral-link-row')).toHaveTextContent('Friends who came: 2')
    const rows = await screen.findAllByTestId('referral-row')
    expect(rows.map((r) => r.dataset.status)).toEqual(['REWARDED', 'REFERRED'])
    await expectAccessible()
    await userEvent.click(within(rows[0]).getByRole('link'))
    expect(await screen.findByTestId('cash-code')).toHaveTextContent('C-ABCD1234')
    await userEvent.click(screen.getByTestId('cash-confirm'))
    await waitFor(() => expect(screen.getByTestId('referral-page')).toBeInTheDocument())
  })
})

describe('customer matching (Q47)', () => {
  it('asks for more of the profile instead of the start when the quiz aims at other people', async () => {
    const { posted } = server({ publicationPage: () => json(200, { items: [publication({ canStart: false,
      matched: false })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const notice = await screen.findByTestId('detail-not-matched')
    expect(notice).toHaveTextContent('Telling more about yourself may let you answer it.')
    expect(screen.queryByTestId('detail-start')).toBeNull()
    expect(screen.queryByTestId('detail-unavailable')).toBeNull()
    await userEvent.click(screen.getByTestId('detail-complete-profile'))
    await waitFor(() => expect(window.location.pathname).toMatch(/\/account\/details$/))
    expect(posted('QB_ATTEMPT_START')).toEqual([])
  })

  it('shows what the taker told and saves the whole of it, a criterion left empty cleared', async () => {
    const { posted } = server({ attributes: [
      { criterionKey: 'interest', valueCode: 'PETS' }, { criterionKey: 'gender', valueCode: 'FEMALE' },
      { criterionKey: 'occupation', valueCode: 'STUDENT' }] })
    window.history.replaceState(null, '', '/account/details')
    render(<App />)
    const details = await screen.findByTestId('profile-details')
    expect(within(details).getByTestId('details-interest-PETS')).toBeChecked()
    expect(within(details).getByTestId('details-gender')).toHaveTextContent('Female')
    await userEvent.type(within(details).getByTestId('details-age'), '19x95')
    expect(within(details).getByTestId('details-age')).toHaveValue('1995')
    await userEvent.click(within(details).getByTestId('details-interest-FOOD'))
    await userEvent.click(within(details).getByTestId('details-interest-PETS'))
    await expectAccessible()
    await userEvent.click(within(details).getByTestId('details-save'))
    expect(await within(details).findByTestId('details-saved')).toBeInTheDocument()
    expect(posted('QB_PROFILE_ATTRIBUTES_SAVE')).toEqual([{ profileAnswers: [
      { key: 'gender', options: ['FEMALE'] }, { key: 'age', number: 1995 }, { key: 'occupation', options: ['STUDENT'] },
      { key: 'interest', options: ['FOOD'] }] }])
  })
})

describe('answering', () => {
  it('reads a publication in several languages in the one chosen and starts it in that one (Q73b)', async () => {
    const { posted } = server({ publicationPage: (body) => {
      const languages = String((body as { params: { languages?: string } }).params.languages ?? '')
      const zh = languages.startsWith('zh')
      return json(200, { items: [publication({ name: zh ? '首都' : 'Capitals', languages: 'en,zh',
        displayLanguage: zh ? 'zh' : 'en' })], offset: 0, limit: 1 })
    } })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const choice = await screen.findByTestId('detail-languages')
    expect(within(choice).getByTestId('detail-language-en')).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(within(choice).getByTestId('detail-language-zh'))
    await waitFor(() => expect(screen.getByTestId('detail-language-zh')).toHaveAttribute('aria-pressed', 'true'))
    expect(screen.getByTestId('detail')).toHaveTextContent('首都')
    await userEvent.click(screen.getByTestId('detail-start'))
    await waitFor(() => expect(posted('QB_ATTEMPT_START')).toEqual([{ publicationId: PUB, language: 'zh' }]))
  })

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
    // Q65: a right answer once; the right one besides only where the taker's differs.
    expect(within(questions[0]).queryByTestId('result-correct')).toBeNull()
    expect(questions[1]).toHaveAttribute('data-correct', 'false')
    expect(within(questions[1]).getByTestId('result-mine')).toHaveTextContent('B. Osaka')
    expect(within(questions[1]).getByTestId('result-correct')).toHaveTextContent('A. Tokyo')
    // Back to the quiz's own page.
    expect(screen.getByTestId('result-publication')).toHaveAttribute('href', `/p/${PUB}`)
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
    const { posted } = server({ result: () => json(200, { output: finished({ status: 'IN_PROGRESS',
      deadline: started().deadline, score: null, questions: [], result: [] }) }) })
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
      result: () => json(200, { output: finished({ score: 5, passed: true, reward: 100, rewardStatus: 'PAID',
        result: [] }) }),
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
        submit: () => json(200, { output: done }),
        result: () => json(200, { output: finished({ kind: 'SURVEY', score: 2, fullScore: 2, passScore: null,
          passed: true, reward: 30, rewardStatus: 'PAID', questions: survey().questions, result: done.questions }) }) })
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
    server({ result: () => json(200, { output: finished({ kind: 'SURVEY', status: 'EXPIRED', score: 1, fullScore: 2,
      passScore: null, passed: false, reward: 0, rewardStatus: 'NONE', deadline: '2026-10-01T00:05:00Z',
      questions: survey().questions, result: [{ no: 1, selected: [1], correct: [], points: 1, earned: 1 },
        { no: 2, selected: [], correct: [], points: 1, earned: 0 }] }) }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-survey')).toHaveAttribute('data-complete', 'false')
    expect(screen.getByTestId('result-reward')).toHaveTextContent('Not every question was answered')
    expect(screen.queryByTestId('result-score')).toBeNull()
  })
})

describe('a result read later', () => {
  it('shows the questions, the answers chosen and the correct ones from the server, nothing kept in the tab',
    async () => {
      const { posted } = server({ result: () => json(200, { output: finished({ status: 'EXPIRED', reward: 0,
        rewardStatus: 'NONE', deadline: '2026-10-01T00:05:00Z' }) }) })
      window.history.replaceState(null, '', `/result/${ATTEMPT}`)
      render(<App />)
      expect(await screen.findByTestId('result-score')).toHaveTextContent('2 / 5')
      expect(screen.getByTestId('result-automatic')).toBeInTheDocument()
      expect(screen.getByTestId('result-reward')).toHaveAttribute('data-status', 'NONE')
      const questions = screen.getAllByTestId('result-question')
      expect(questions.map((q) => q.getAttribute('data-correct'))).toEqual(['true', 'false'])
      // The texts, in the order that attempt showed the options (Q20): from the server, not from this tab.
      expect(questions[0]).toHaveTextContent('Capital of France?')
      expect(within(questions[0]).getByTestId('result-mine')).toHaveTextContent('B. Paris')
      expect(within(questions[1]).getByTestId('result-mine')).toHaveTextContent('B. Osaka')
      expect(within(questions[1]).getByTestId('result-correct')).toHaveTextContent('A. Tokyo')
      expect(screen.queryByText(/shown only right after/)).toBeNull()
      expect(await screen.findByTestId('result-pass-score')).toHaveTextContent('3')
      expect(screen.getByTestId('result-name')).toHaveTextContent('Capitals week')
      expect(posted('QB_ATTEMPT_RESULT')).toEqual([{ attemptId: ATTEMPT }])
    })

  it('waits while the server finishes an attempt whose time ran out', async () => {
    server({ result: () => json(200, { output: finished({ status: 'IN_PROGRESS', deadline: '2026-10-01T00:05:00Z',
      score: null, fullScore: null, passScore: null, passed: null, reward: null, rewardStatus: null, questions: [],
      result: [] }) }) })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-finishing')).toBeInTheDocument()
  })

  it('sends an attempt still in time back to answering', async () => {
    const deadline = new Date(Date.now() + 120_000).toISOString()
    server({ result: () => json(200, { output: finished({ status: 'IN_PROGRESS', deadline, score: null, questions: [],
      result: [] }) }) })
    storeStart(started({ deadline }))
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe(`/play/${ATTEMPT}`))
  })

  it('falls back to the submission\'s own answer when the server cannot be read right after finishing', async () => {
    server({ result: () => json(503, { title: 'Service unavailable', status: 503 }) })
    storeStart(started())
    storeResult({ ...SUBMITTED, kind: 'QUIZ' } as SubmitOutput)
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByTestId('result-score')).toHaveTextContent('2 / 5')
    expect(screen.getByTestId('result-reward')).toHaveTextContent('The sponsor is topping up.')
    expect(within(screen.getAllByTestId('result-question')[0]).getByTestId('result-mine')).toHaveTextContent('B. Paris')
  })

  it('says when there is no such attempt, whatever this tab kept', async () => {
    server({ result: () => json(404, { title: 'Not found', status: 404 }) })
    storeStart(started())
    storeResult({ ...SUBMITTED, kind: 'QUIZ' } as SubmitOutput)
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    expect(await screen.findByText('This page does not exist.')).toBeInTheDocument()
    expect(screen.queryByTestId('result-score')).toBeNull()
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
    // Q65: the publication's cover beside the name (a drawn one without a cover).
    expect(within(item).getByTestId('history-cover')).toBeInTheDocument()
    expect(within(item).getByTestId('history-cover').querySelector('svg')).not.toBeNull()
    expect(item).toHaveTextContent('Passed')
    expect(item).toHaveTextContent('Score 5 / 5')
    expect(item).toHaveTextContent('Earned 100 Kudos')
    // Q66: the cover and the name lead back to the start page; the round arrow at the end, to the result.
    expect(within(item).getByTestId('history-cover')).toHaveAttribute('href', `/p/${PUB}`)
    expect(within(item).getByTestId('history-name')).toHaveAttribute('href', `/p/${PUB}`)
    expect(within(item).getByTestId('history-open')).toHaveAttribute('href', `/result/${ATTEMPT}`)
    expect(within(item).getByTestId('history-open')).toHaveAccessibleName('See the result: Capitals week')
    expect(within(item).getByTestId('history-open')).toHaveTextContent('')
    await expectAccessible()
    expect(screen.getByTestId('nav-history')).toHaveAttribute('aria-current', 'page')
    await userEvent.click(screen.getByRole('radio', { name: 'Unfinished' }))
    await waitFor(() => expect(calls.some((c) => JSON.stringify(c.body).includes('"values":["IN_PROGRESS","TIMED_OUT"]')))
      .toBe(true))
  })

  it('says where a cash reward stands (Q22)', async () => {
    server({ history: [row({ rewardStatus: 'CASH', rewardMethod: 'SPONSOR_CASH', cashRewardId: 'c1',
      cashStatus: 'MARKED_PAID', cashCode: 'C-ABCD1234' })] })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    expect(await screen.findByTestId('history-cash')).toHaveTextContent('The sponsor paid 100 Kudos: confirm it')
  })

  it('continues an attempt in progress where it was left', async () => {
    const deadline = new Date(Date.now() + 120_000).toISOString()
    const { posted } = server({ history: [row({ status: 'IN_PROGRESS', category: 'IN_PROGRESS', passed: null,
      score: null, reward: null, rewardStatus: null, submittedAt: null, deadline })],
    start: () => json(200, { output: started({ resumed: true, currentIndex: 1 }) }) })
    window.history.replaceState(null, '', '/history')
    render(<App />)
    const button = await screen.findByTestId('history-continue')
    // Q66: a round button at the row's end, named for assistive technology.
    expect(button).toHaveAccessibleName(expect.stringContaining('minutes left: 2'))
    expect(screen.queryByTestId('history-open')).toBeNull()
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

describe('single and multiple choice in a scored quiz', () => {
  it('shows a question with one correct option as a single choice with radio buttons, one with several as check boxes',
    async () => {
      const questions = started().questions
      const { posted } = server({ start: () => json(200, { output: started({ questions: [
        { ...questions[0], selection: 'SINGLE' }, { ...questions[1], selection: 'MULTIPLE' }] }) }) })
      window.history.replaceState(null, '', `/p/${PUB}`)
      render(<App />)
      await userEvent.click(await screen.findByTestId('detail-start'))

      // The server says which (D-Q5-2): one correct option is a single choice, and only one option holds.
      expect(await screen.findByTestId('play-choice')).toHaveAttribute('data-choice', 'single')
      expect(screen.getByTestId('play-choice')).toHaveTextContent('Single choice')
      expect(screen.getByText(/Choose the correct option\./)).toBeInTheDocument()
      expect(screen.queryByRole('checkbox')).toBeNull()
      await userEvent.click(screen.getByRole('radio', { name: /Paris/ }))
      await userEvent.click(screen.getByRole('radio', { name: /Lyon/ }))
      expect(screen.getByRole('radio', { name: /Lyon/ })).toBeChecked()
      expect(screen.getByRole('radio', { name: /Paris/ })).not.toBeChecked()
      await waitFor(() => expect(posted('QB_ATTEMPT_SAVE').at(-1)).toEqual(
        { attemptId: ATTEMPT, currentIndex: null, answers: [{ questionNo: 1, selected: [2] }] }), { timeout: 3000 })

      await userEvent.click(screen.getByTestId('play-next'))
      expect(screen.getByTestId('play-choice')).toHaveAttribute('data-choice', 'multiple')
      expect(screen.getByTestId('play-choice')).toHaveTextContent('Multiple choice')
      expect(screen.getByText(/Choose every correct option\./)).toBeInTheDocument()
      expect(screen.queryByRole('radio')).toBeNull()
      expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    })
})

describe("the sponsor's products (Q60b)", () => {
  const products = [
    { questionNo: 0, seq: 0, productId: 'p1', name: 'Rye loaf', description: null, image: null, price: '1200',
      currency: 'JPY', shopUrl: 'https://shop.example/rye' },
    { questionNo: 2, seq: 0, productId: 'p2', name: 'Butter', description: null, image: null, price: '300',
      currency: 'JPY', shopUrl: 'https://shop.example/butter' },
  ]

  it("shows a question's product under it in the result", async () => {
    server({ publicationProducts: products })
    window.history.replaceState(null, '', `/result/${ATTEMPT}`)
    render(<App />)
    await waitFor(() => expect(screen.getAllByTestId('result-question').length).toBeGreaterThan(1))
    const questions = screen.getAllByTestId('result-question')
    expect(await within(questions[1]).findByTestId('result-product-item')).toHaveTextContent('Butter')
    expect(within(questions[0]).queryByTestId('result-product-item')).toBeNull()
  })

  it("shows the quiz's products on its page", async () => {
    server({ publicationProducts: products })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const items = await screen.findAllByTestId('publication-products-item')
    expect(items).toHaveLength(1)
    expect(items[0]).toHaveTextContent('Rye loaf')
    expect(within(items[0]).getByTestId('publication-products-shop')).toHaveAttribute('href', 'https://shop.example/rye')
  })
})

describe('the video to watch first (Q75)', () => {
  const PLAYER = 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
  const WATCH = JSON.stringify([{ no: 1, kind: 'VIDEO_LINK', title: 'Watch first', description: null, body: null,
    url: 'https://youtu.be/dQw4w9WgXcQ', pdf: null, audio: null, images: [], watchToAnswer: true, durationSec: 100 }])
  const required = () => json(200, { items: [publication({ watchRequired: true, videoDurationSec: 100, materials: WATCH })],
    offset: 0, limit: 1 })

  /** The player says something: a message from the stage's frame, as YouTube's player posts it. */
  function hear(frame: HTMLIFrameElement, data: unknown) {
    act(() => {
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow, data: JSON.stringify(data) }))
    })
  }

  afterEach(() => forgetPlayback())

  it('waits for the video to be watched before the start and reports the watching (D-Q75-2, D-Q75-4)', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000_000)
    const { posted } = server({ publicationPage: required })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const frame = await screen.findByTitle('Watch first') as HTMLIFrameElement
    const start = await screen.findByTestId('detail-start')
    expect(start).toBeDisabled()
    expect(screen.getByTestId('detail-watch')).toHaveTextContent('Watch the video first: 0:00 of 1:40 watched, 90 s to go.')
    // Sixty seconds of the video over sixty seconds: counted, not yet enough.
    hear(frame, { event: 'infoDelivery', info: { playerState: 1, currentTime: 0, duration: 100 } })
    now.mockReturnValue(1_060_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 60 } })
    expect(screen.getByTestId('detail-watch')).toHaveTextContent('1:00 of 1:40 watched, 30 s to go')
    expect(start).toBeDisabled()
    // A jump back is not watching: counting goes on from there.
    now.mockReturnValue(1_061_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 20 } })
    expect(screen.getByTestId('detail-watch')).toHaveTextContent('1:00 of 1:40')
    // Thirty more seconds at pace: nine tenths, enough.
    now.mockReturnValue(1_091_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 50 } })
    expect(screen.getByTestId('detail-watch')).toHaveTextContent('Video watched: you can start.')
    expect(start).toBeEnabled()
    // (No axe here: it cannot look into the player's frame in jsdom; the start page is checked without one above.)
    await userEvent.click(start)
    await waitFor(() => expect(posted('QB_ATTEMPT_START')).toHaveLength(1))
    expect(posted('QB_ATTEMPT_START')[0]).toMatchObject({ publicationId: PUB,
      playback: { status: 'REPORTED', watchedSec: 90, ended: false, reportedDurationSec: 100 } })
  })

  it('takes the end of the video, whatever was watched', async () => {
    const { posted } = server({ publicationPage: required })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const frame = await screen.findByTitle('Watch first') as HTMLIFrameElement
    expect(await screen.findByTestId('detail-start')).toBeDisabled()
    hear(frame, { event: 'onStateChange', info: 0 })
    expect(screen.getByTestId('detail-start')).toBeEnabled()
    await userEvent.click(screen.getByTestId('detail-start'))
    await waitFor(() => expect(posted('QB_ATTEMPT_START')).toHaveLength(1))
    expect(posted('QB_ATTEMPT_START')[0]).toMatchObject({ playback: { status: 'REPORTED', watchedSec: 0, ended: true } })
  })

  it("does not count the home card's muted playback; the start page's counts (D-Q75-3)", async () => {
    const io = intersections()
    const now = vi.spyOn(Date, 'now').mockReturnValue(2_000_000)
    server({ feed: [{ ...CARD, video: PLAYER, watchRequired: true, videoDurationSec: 100 }], publicationPage: required })
    render(<App />)
    const card = await screen.findByTestId('feed-card')
    // Q75: the card says the video must be watched.
    expect(within(card).getByTestId('feed-card-watch')).toHaveTextContent('Watch to answer')
    io.fire(card, true, MIDDLE)
    const frame = await screen.findByTitle('Video: Capitals week') as HTMLIFrameElement
    // And still says so while it plays (the player takes the picture's place).
    expect(card).toHaveAttribute('data-playing', 'true')
    expect(within(card).getByTestId('feed-card-watch')).toHaveTextContent('Watch to answer')
    hear(frame, { event: 'infoDelivery', info: { playerState: 1, currentTime: 0, muted: true } })
    now.mockReturnValue(2_050_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 50 } })
    // The tap opens the start page with the same player; what played muted on the card did not count.
    await userEvent.click(screen.getByTestId('video-open'))
    await waitFor(() => expect(window.location.pathname).toBe(`/p/${PUB}`))
    expect(await screen.findByTestId('detail-watch')).toHaveTextContent('0:00 of 1:40 watched')
    // From here it counts: fifty more seconds, then forty, make ninety.
    now.mockReturnValue(2_051_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 51 } })
    now.mockReturnValue(2_141_000)
    hear(frame, { event: 'infoDelivery', info: { currentTime: 141 } })
    expect(screen.getByTestId('detail-watch')).toHaveTextContent('Video watched')
    expect(screen.getByTestId('detail-start')).toBeEnabled()
  })

  it('lets the start through, marked, when the player is never heard from (D-Q75-6)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { posted } = server({ publicationPage: required })
      window.history.replaceState(null, '', `/p/${PUB}`)
      render(<App />)
      await screen.findByTitle('Watch first')
      expect(await screen.findByTestId('detail-start')).toBeDisabled()
      await act(async () => {
        vi.advanceTimersByTime(8_500)
      })
      expect(screen.getByTestId('detail-watch')).toHaveTextContent("Your browser does not report the video's progress")
      expect(screen.getByTestId('detail-start')).toBeEnabled()
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      await user.click(screen.getByTestId('detail-start'))
      await waitFor(() => expect(posted('QB_ATTEMPT_START')).toHaveLength(1))
      expect(posted('QB_ATTEMPT_START')[0]).toMatchObject({ playback: { status: 'UNKNOWN' } })
    } finally {
      vi.useRealTimers()
    }
  })

  it('asks nothing of a publication whose video is not the one to watch, nor of an attempt in progress', async () => {
    const { posted } = server({ publicationPage: () => json(200, { items: [publication({ watchRequired: false,
      videoDurationSec: null })], offset: 0, limit: 1 }) })
    window.history.replaceState(null, '', `/p/${PUB}`)
    render(<App />)
    const start = await screen.findByTestId('detail-start')
    expect(start).toBeEnabled()
    expect(screen.queryByTestId('detail-watch')).toBeNull()
    await userEvent.click(start)
    await waitFor(() => expect(posted('QB_ATTEMPT_START')).toHaveLength(1))
    expect(posted('QB_ATTEMPT_START')[0]).not.toHaveProperty('playback')
  })
})
