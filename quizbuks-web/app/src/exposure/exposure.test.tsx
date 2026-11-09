import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { forgetPlayback } from '../video/playback'
import { forgetReports } from './reporter'

/*
 * F-S13 (docs/quizbuks/plans/impl/F-S13.md): the vertical mode's pushes, the timing of the exposures and their
 * reports, against a server as the app sees it.
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }
const YOUTUBE = 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ'
const BILIBILI = 'https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0'

function card(id: string, more: Record<string, unknown> = {}) {
  return { publicationId: id, name: `Quiz ${id}`, quizTitle: 'Capitals', cover: null, maxReward: 100, completions: 2,
    sponsorName: 'Alice', sponsorCompany: 'Capitals Inc', sponsorAvatar: null, sponsorCountry: 'JP',
    startAt: '2026-10-01T01:00:00Z', endAt: '2099-10-30T00:00:00Z', timeLimitSec: 300, questionCount: 2, fullScore: 5,
    shortsPositions: '2', shortsPerShelf: '10', ...more }
}

const short = (id: string, video = YOUTUBE.replace('dQw4w9WgXcQ', `${id}xxxxxxxxxx`.slice(0, 11))) =>
  card(id, { video, videoVertical: true })
const push = (id: string, company: string, video = YOUTUBE) =>
  card(id, { video, videoVertical: true, exposed: true, sponsorCompany: company, videoDurationSec: 95 })

const FEED = Array.from({ length: 3 }, (_, i) => card(`p${i + 1}`))
const PUSH_X = push('x', 'Xavier Co')
const SHORTS = [short('s1'), short('s2'), PUSH_X, short('s3')]

interface Call { path: string; method: string; body: unknown }

function server(options: { shorts?: Record<string, unknown>[]; pushes?: Record<string, unknown>[];
  feed?: Record<string, unknown>[]; publication?: Record<string, unknown>; seconds?: string;
  emailVerified?: boolean } = {}) {
  const calls: Call[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input
      : new Request(new URL(String(input), window.location.origin), { method: init?.method })
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    calls.push({ path, method: request.method, body })
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
      case '/api/queries/qb.app.feed': {
        const params = (body as { params: { shorts?: boolean; push?: boolean } }).params
        const items = params.push ? options.pushes ?? [PUSH_X] : params.shorts ? options.shorts ?? SHORTS
          : options.feed ?? FEED
        return json(200, { items, offset: 0, limit: 10 })
      }
      case '/api/queries/qb.app.exposure-settings':
        return json(200, { items: [{ seconds: options.seconds ?? '10', pushEvery: '2', reportBatch: '1',
          reportIntervalSec: '30' }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.publication':
        return json(200, { items: options.publication ? [options.publication] : [], offset: 0, limit: 1 })
      case '/api/processes/QB_EXPOSURE_REPORT/latest':
        return json(200, { output: { results: [], counted: 0 } })
      case '/api/client/region':
        return json(404, {})
      default:
        if (path.startsWith('/api/public/counters/')) return new Response(null, { status: 204 })
        if (path.startsWith('/api/processes/')) return json(200, { output: {} })
        return json(200, { items: [], offset: 0, limit: 20 })
    }
  })
  return {
    calls,
    reports: () => calls.filter((c) => c.path === '/api/processes/QB_EXPOSURE_REPORT/latest')
      .flatMap((c) => (c.body as { reports: Record<string, unknown>[] }).reports),
    pushReads: () => calls.filter((c) => c.path === '/api/queries/qb.app.feed'
      && (c.body as { params: { push?: boolean } }).params.push === true),
  }
}

function stillMotion(reduce: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: reduce && query.includes('reduce'), media: query,
    addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {}, onchange: null,
    dispatchEvent: () => false }))
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  window.history.replaceState(null, '', '/')
  stillMotion(false)
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  cleanup()
  forgetPlayback()
  forgetReports()
  document.body.style.overflow = ''
})

async function open(id = 's1') {
  const user = userEvent.setup()
  const shelf = (await screen.findAllByTestId('shorts-shelf'))[0]
  const target = within(shelf).getAllByTestId('shorts-card').find((c) => c.dataset.publication === id)!
  await user.click(within(target).getByTestId('shorts-card-open'))
  return { user, viewer: await screen.findByTestId('shorts-viewer') }
}

const current = () => screen.getAllByTestId('shorts-screen').find((s) => s.dataset.current)!
const order = () => screen.getAllByTestId('shorts-screen').map((s) => s.dataset.publication)

describe('the vertical mode\'s pushes (F-S13)', () => {
  it('reads them under the feed\'s conditions and places them every n-th screen, taking turns', async () => {
    const pushY = push('y', 'Yoshi Co', YOUTUBE.replace('dQw4w9WgXcQ', 'yyyyyyyyyyy'))
    const { calls, pushReads } = server({ shorts: [...SHORTS, short('s4')], pushes: [PUSH_X, push('x2', 'Xavier Co'),
      pushY] })
    render(<App />)
    // The home's shelf still shows every vertical publication, x among them (its preview never counts).
    const shelf = (await screen.findAllByTestId('shorts-shelf'))[0]
    expect(within(shelf).getAllByTestId('shorts-card').map((c) => c.dataset.publication)).toContain('x')
    const { viewer } = await open('s1')
    // Every second screen from the one opened on (the parameter), the Sponsors taking turns: x, y, then x2.
    expect(order()).toEqual(['s1', 's2', 'x', 's3', 'y', 's4', 'x2'])
    const reads = pushReads()
    expect(reads.length).toBeGreaterThan(0)
    const main = calls.find((c) => c.path === '/api/queries/qb.app.feed') as Call
    expect((reads[0].body as { params: object }).params).toMatchObject({
      ...(main.body as { params: object }).params, push: true })
    await expectAccessible(viewer)
  })

  it('reports a push opened, and reads them again once the mode closes', async () => {
    const { reports, pushReads } = server()
    render(<App />)
    const { user, viewer } = await open('s1')
    expect(order()).toEqual(['s1', 's2', 'x', 's3'])
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(reports()).toEqual([])
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(current().dataset.publication).toBe('x')
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'x', kind: 'OPEN', surface: 'VERTICAL' }))
    // Not twice in the same tab.
    await user.click(within(viewer).getByTestId('shorts-previous'))
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(reports().filter((r) => r.kind === 'OPEN')).toHaveLength(1)
    const before = pushReads().length
    await user.keyboard('{Escape}')
    await waitFor(() => expect(pushReads().length).toBeGreaterThan(before))
  })

  it('counts the exposure once a run of the player reaches the seconds, and going on to answer', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(5_000_000)
    const { reports } = server({ shorts: [PUSH_X, short('s1')] })
    render(<App />)
    const { user } = await open('x')
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'x', kind: 'OPEN', surface: 'VERTICAL' }))
    const frame = await screen.findByTitle('Video: Quiz x') as HTMLIFrameElement
    const hear = (data: unknown) => act(() => {
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow, data: JSON.stringify(data) }))
    })
    hear({ event: 'infoDelivery', info: { playerState: 1, currentTime: 0, duration: 95 } })
    now.mockReturnValue(5_006_000)
    hear({ event: 'infoDelivery', info: { currentTime: 6 } })
    // A pause breaks the run: six seconds are lost.
    hear({ event: 'infoDelivery', info: { playerState: 2 } })
    hear({ event: 'infoDelivery', info: { playerState: 1, currentTime: 6 } })
    now.mockReturnValue(5_015_000)
    hear({ event: 'infoDelivery', info: { currentTime: 15 } })
    expect(reports().filter((r) => r.kind === 'EXPOSURE')).toEqual([])
    now.mockReturnValue(5_016_000)
    hear({ event: 'infoDelivery', info: { currentTime: 16 } })
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'x', kind: 'EXPOSURE', surface: 'VERTICAL',
      seconds: 10, timer: 'PLAYER' }))
    now.mockReturnValue(5_030_000)
    hear({ event: 'infoDelivery', info: { currentTime: 30 } })
    expect(reports().filter((r) => r.kind === 'EXPOSURE')).toHaveLength(1)
    await user.click(within(current()).getByTestId('shorts-answer'))
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'x', kind: 'GO', surface: 'VERTICAL' }))
  })

  it('times another site\'s player on the screen, and nothing plays or counts with less motion', async () => {
    const other = push('o', 'Other Co', BILIBILI)
    const { reports } = server({ shorts: [other, short('s1')], pushes: [other], seconds: '1' })
    render(<App />)
    await open('o')
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'o', kind: 'EXPOSURE', surface: 'VERTICAL',
      seconds: expect.any(Number), timer: 'SCREEN' }), { timeout: 4000 })
    cleanup()
    forgetReports()
    window.history.replaceState(null, '', '/')
    stillMotion(true)
    const still = server({ shorts: [other, short('s1')], pushes: [other], seconds: '1' })
    render(<App />)
    await open('o')
    await waitFor(() => expect(still.reports()).toContainEqual({ publicationId: 'o', kind: 'OPEN',
      surface: 'VERTICAL' }))
    await new Promise((resolve) => setTimeout(resolve, 2500))
    expect(still.reports().filter((r) => r.kind === 'EXPOSURE')).toEqual([])
  })

  it('reports nothing for an unverified address, nor for the home\'s cards', async () => {
    const { reports } = server({ emailVerified: false, feed: [card('h', { video: BILIBILI, exposed: true })],
      seconds: '1' })
    render(<App />)
    const { user, viewer } = await open('s1')
    await user.click(within(viewer).getByTestId('shorts-next'))
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(current().dataset.publication).toBe('x')
    await new Promise((resolve) => setTimeout(resolve, 1500))
    expect(reports()).toEqual([])
  })
})

describe('the start page (F-S13)', () => {
  const page = (more: Record<string, unknown> = {}) => ({ ...card('h1', { video: BILIBILI, exposed: true,
    videoVertical: false }), intro: null, materials: '[]', status: 'LIVE', passScore: 3, sponsorIntro: null, last1h: 0,
  last24h: 0, last7d: 0, last30d: 0, statsAt: null, canStart: true, myAttemptId: null, myStatus: null,
  myDeadline: null, kind: 'QUIZ', rewardMethod: 'PLATFORM', languages: 'en', language: 'en', ...more })

  it('counts a horizontal video of another\'s on the screen, once, and going on to answer', async () => {
    window.history.replaceState(null, '', '/p/h1')
    const { reports } = server({ publication: page(), seconds: '1' })
    render(<App />)
    const wrapper = await screen.findByTestId('exposure-start')
    expect(within(wrapper).getByTestId('video-player')).toBeInTheDocument()
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'h1', kind: 'EXPOSURE', surface: 'START',
      seconds: expect.any(Number), timer: 'SCREEN' }), { timeout: 4000 })
    await expectAccessible(screen.getByTestId('detail'))
    await userEvent.setup().click(screen.getByTestId('detail-start'))
    await waitFor(() => expect(reports()).toContainEqual({ publicationId: 'h1', kind: 'GO', surface: 'START' }))
    expect(reports().filter((r) => r.kind === 'EXPOSURE')).toHaveLength(1)
  })

  it('leaves a vertical video, one\'s own and a still page as they were', async () => {
    for (const more of [{ videoVertical: true }, { exposed: false }]) {
      window.history.replaceState(null, '', '/p/h1')
      server({ publication: page(more), seconds: '1' })
      render(<App />)
      await screen.findByTestId('detail')
      expect(screen.queryByTestId('exposure-start')).toBeNull()
      cleanup()
      vi.restoreAllMocks()
    }
    stillMotion(true)
    window.history.replaceState(null, '', '/p/h1')
    server({ publication: page(), seconds: '1' })
    render(<App />)
    await screen.findByTestId('detail')
    expect(screen.queryByTestId('exposure-start')).toBeNull()
  })
})
