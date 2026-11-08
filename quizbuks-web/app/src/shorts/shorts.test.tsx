import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import type { FeedCard } from '../qb/play'
import { forgetPlayback, playbackOf } from '../video/playback'
import { placeShelves, shelfPositions, shelfSize, SHORTS_DEFAULT_PER_SHELF, SHORTS_LIMIT } from './shelves'

/*
 * F-S10 (docs/quizbuks/plans/impl/F-S10.md): the home's shelves of vertical short videos and the full-screen vertical
 * mode, against a server as the app sees it.
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
    ...more }
}

const FEED = Array.from({ length: 5 }, (_, i) => card(`p${i + 1}`))
const SHORTS = [
  card('s1', { video: YOUTUBE, videoVertical: true, shortsPositions: '2,12', shortsPerShelf: '2' }),
  card('s2', { video: BILIBILI, videoVertical: true, shortsPositions: '2,12', shortsPerShelf: '2' }),
  card('s3', { video: YOUTUBE.replace('dQw4w9WgXcQ', 'aaaaaaaaaaa'), videoVertical: true, shortsPositions: '2,12',
    shortsPerShelf: '2' }),
]

interface Call { path: string; method: string; body: unknown }

function server(options: { feed?: Record<string, unknown>[]; shorts?: Record<string, unknown>[] } = {}) {
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
          permissions: ['qb.me', 'qb.play'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.feed': {
        const params = (body as { params: { shorts?: boolean } }).params
        return json(200, { items: params.shorts ? options.shorts ?? SHORTS : options.feed ?? FEED, offset: 0,
          limit: 10 })
      }
      case '/api/client/region':
        return json(404, {})
      default:
        if (path.startsWith('/api/public/counters/')) return new Response(null, { status: 204 })
        return json(200, { items: [], offset: 0, limit: 20 })
    }
  })
  return {
    calls,
    feedReads: () => calls.filter((c) => c.path === '/api/queries/qb.app.feed')
      .map((c) => (c.body as { params: Record<string, unknown>; limit: number })),
  }
}

/** The browser's IntersectionObserver, which jsdom lacks: none, so the buttons and keys move the screens. */
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
  document.body.style.overflow = ''
})

const feedCard = (id: string) => card(id) as unknown as FeedCard

describe('the shelves\' places (F-S10)', () => {
  it('reads the parameters as the setup gives them, and anything else safely', () => {
    expect(shelfPositions(null)).toEqual([3, 12])
    expect(shelfPositions('12, 3,3, x, 0, -1')).toEqual([3, 12])
    expect(shelfPositions('')).toEqual([])
    expect(shelfSize(null)).toBe(SHORTS_DEFAULT_PER_SHELF)
    expect(shelfSize('4')).toBe(4)
    expect(shelfSize('0')).toBe(1)
    expect(shelfSize('999')).toBe(SHORTS_LIMIT)
    expect(shelfSize('many')).toBe(SHORTS_DEFAULT_PER_SHELF)
  })

  it('puts each place\'s own group after its card, waits for more cards, and closes a complete list', () => {
    const shorts = ['a', 'b', 'c', 'd', 'e'].map(feedCard)
    const ids = (list: ReturnType<typeof placeShelves>) => list.map((s) => [s.after, s.items.map((c) => c.publicationId)])
    expect(ids(placeShelves(20, false, [3, 12], 2, shorts))).toEqual([[3, ['a', 'b']], [12, ['c', 'd']]])
    // Twelve cards not loaded yet: the second shelf waits; with no more cards it closes the list.
    expect(ids(placeShelves(10, false, [3, 12], 2, shorts))).toEqual([[3, ['a', 'b']]])
    expect(ids(placeShelves(10, true, [3, 12, 20], 2, shorts))).toEqual([[3, ['a', 'b']], [10, ['c', 'd']]])
    // A place without short videos left shows nothing; no short videos, no shelf.
    expect(ids(placeShelves(20, true, [3, 12], 10, shorts))).toEqual([[3, ['a', 'b', 'c', 'd', 'e']]])
    expect(placeShelves(20, true, [3, 12], 10, [])).toEqual([])
    expect(placeShelves(0, true, [3], 10, shorts)).toEqual([])
  })
})

describe('the home\'s shelf of short videos (F-S10)', () => {
  it('asks the feed\'s template for the vertical ones and shows them at the parameters\' places', async () => {
    const { feedReads } = server()
    render(<App />)
    const shelves = await screen.findAllByTestId('shorts-shelf')
    expect(shelves).toHaveLength(2)
    // The first after the second card, the second (the place 12 is beyond five cards) closing the list.
    const list = screen.getByTestId('feed-list')
    const order = Array.from(list.children).map((li) => (li as HTMLElement).dataset.publication ?? 'shelf')
    expect(order).toEqual(['p1', 'p2', 'shelf', 'p3', 'p4', 'p5', 'shelf'])
    expect(within(shelves[0]).getAllByTestId('shorts-card').map((c) => c.dataset.publication)).toEqual(['s1', 's2'])
    expect(within(shelves[1]).getAllByTestId('shorts-card').map((c) => c.dataset.publication)).toEqual(['s3'])
    expect(shelves[0]).toHaveTextContent('Shorts')
    // The same conditions as the feed, with only `shorts` added; never a player on a small card.
    const [main, shorts] = feedReads()
    expect(main.params.shorts).toBeUndefined()
    expect(shorts.params).toMatchObject({ ...main.params, shorts: true })
    expect(within(shelves[0]).queryByTitle(/Video/)).toBeNull()
    await expectAccessible()
  })

  it('shows no shelf when there is no vertical publication', async () => {
    server({ shorts: [card('h1', { video: YOUTUBE, videoVertical: false })] })
    render(<App />)
    await screen.findAllByTestId('feed-card')
    await waitFor(() => expect(screen.getAllByTestId('feed-card')).toHaveLength(5))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(screen.queryByTestId('shorts-shelf')).toBeNull()
  })
})

describe('the full-screen vertical mode (F-S10)', () => {
  async function open(id = 's1') {
    const user = userEvent.setup()
    const shelf = (await screen.findAllByTestId('shorts-shelf'))[0]
    const target = within(shelf).getAllByTestId('shorts-card').find((c) => c.dataset.publication === id)!
    await user.click(within(target).getByTestId('shorts-card-open'))
    return { user, viewer: await screen.findByTestId('shorts-viewer') }
  }

  const current = () => screen.getAllByTestId('shorts-screen').find((s) => s.dataset.current)!

  it('opens over the home on the tapped one, with one player only, and counts the opening', async () => {
    const { calls } = server()
    render(<App />)
    const { viewer } = await open('s1')
    expect(window.location.search).toBe('?shorts=s1')
    expect(viewer).toHaveAttribute('aria-modal', 'true')
    expect(current().dataset.publication).toBe('s1')
    expect(within(viewer).getByTestId('shorts-position')).toHaveTextContent('1 of 3')
    // Q65: one player in the whole page, the app's own, over the shown screen.
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    expect(screen.getByTestId('video-stage')).toHaveAttribute('data-mode', 'short')
    expect(within(current()).getByTestId('video-slot')).toHaveAttribute('data-mode', 'short')
    await waitFor(() => expect(calls.some((c) => c.method === 'POST'
      && c.path === '/api/public/counters/qb.shorts-open/s1')).toBe(true))
    // The home does not scroll beneath it.
    expect(document.body.style.overflow).toBe('hidden')
    await expectAccessible(viewer)
  })

  it('moves one by one with the buttons and the arrow keys, and goes back with Escape', async () => {
    server()
    render(<App />)
    const { user, viewer } = await open('s1')
    expect(within(viewer).getByTestId('shorts-previous')).toBeDisabled()
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(current().dataset.publication).toBe('s2')
    await user.keyboard('{ArrowDown}')
    expect(current().dataset.publication).toBe('s3')
    expect(within(viewer).getByTestId('shorts-next')).toBeDisabled()
    await user.keyboard('{ArrowDown}')
    expect(current().dataset.publication).toBe('s3')
    await user.keyboard('{ArrowUp}')
    expect(current().dataset.publication).toBe('s2')
    await user.click(within(viewer).getByTestId('shorts-previous'))
    expect(current().dataset.publication).toBe('s1')
    // Still one player all along.
    expect(document.querySelectorAll('iframe').length).toBeLessThanOrEqual(1)
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('shorts-viewer')).toBeNull())
    expect(window.location.search).toBe('')
    expect(document.body.style.overflow).toBe('')
    expect(screen.getAllByTestId('shorts-shelf')).toHaveLength(2)
  })

  it('plays another site in its own player, whose sound is a tap on it (A3-18)', async () => {
    server()
    render(<App />)
    const { user, viewer } = await open('s2')
    const player = within(current()).getByTestId('video-player')
    expect(player.querySelector('iframe')).toHaveAttribute('src', expect.stringContaining('bilibili'))
    expect(within(viewer).getByTestId('shorts-tap-for-sound')).toHaveTextContent('Tap the player')
    // The app's player is not playing meanwhile: one player.
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    await user.click(within(viewer).getByTestId('shorts-next'))
    expect(within(current()).queryByTestId('video-player')).toBeNull()
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
  })

  it('plays nothing by itself when less motion is asked for, until the play button', async () => {
    stillMotion(true)
    server()
    render(<App />)
    const { user, viewer } = await open('s1')
    expect(document.querySelectorAll('iframe')).toHaveLength(0)
    await user.click(within(current()).getByTestId('shorts-play'))
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    await expectAccessible(viewer)
  })

  it('counts the watching towards "watch to answer" and goes to answer with the same player', async () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(5_000_000)
    server()
    render(<App />)
    const { user } = await open('s1')
    const frame = await screen.findByTitle('Video: Quiz s1') as HTMLIFrameElement
    const hear = (data: unknown) => act(() => {
      window.dispatchEvent(new MessageEvent('message', { source: frame.contentWindow, data: JSON.stringify(data) }))
    })
    hear({ event: 'infoDelivery', info: { playerState: 1, currentTime: 0, duration: 30 } })
    now.mockReturnValue(5_020_000)
    hear({ event: 'infoDelivery', info: { currentTime: 20 } })
    expect(playbackOf('s1').watchedSec).toBe(20)
    await user.click(within(current()).getByTestId('shorts-answer'))
    await waitFor(() => expect(window.location.pathname).toBe('/p/s1'))
    expect(screen.queryByTestId('shorts-viewer')).toBeNull()
  })
})
