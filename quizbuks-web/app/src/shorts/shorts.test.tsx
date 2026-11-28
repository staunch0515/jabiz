import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { forgetPlayback, playbackOf } from '../video/playback'

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
  card('s1', { video: YOUTUBE, videoVertical: true }),
  card('s2', { video: BILIBILI, videoVertical: true }),
  card('s3', { video: YOUTUBE.replace('dQw4w9WgXcQ', 'aaaaaaaaaaa'), videoVertical: true }),
]

interface Call { path: string; method: string; body: unknown }

function server(options: { feed?: Record<string, unknown>[] } = {}) {
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
        return json(200, { items: options.feed ?? [...FEED, ...SHORTS], offset: 0, limit: 10 })
      }
      case '/api/client/region':
        return json(404, {})
      case '/api/processes/QB_ATTEMPT_START/latest': {
        const now = new Date()
        return json(200, { output: { attemptId: 'a1', publicationId: (body as { publicationId: string }).publicationId,
          status: 'IN_PROGRESS', startedAt: now.toISOString(), deadline: new Date(now.getTime() + 300_000).toISOString(),
          serverTime: now.toISOString(), timeLimitSec: 300, currentIndex: 0, resumed: false, questions: [], answers: [],
          kind: 'QUIZ' } })
      }
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


describe('the home without a shelf of short videos (2026-11-28, x.com\'s way)', () => {
  it('shows no shelf and asks for no short videos or pushes', async () => {
    const api = server({ feed: [...FEED, ...SHORTS] })
    render(<App />)
    expect(await screen.findAllByTestId('feed-card')).toHaveLength(FEED.length + SHORTS.length)
    expect(screen.queryByTestId('shorts-shelf')).toBeNull()
    expect(api.feedReads().every((read) => read.params.shorts !== true && read.params.push !== true)).toBe(true)
  })

  it('opens the video mode on the publication an old address of the vertical mode names', async () => {
    server({ feed: [...FEED, ...SHORTS] })
    window.history.replaceState(null, '', '/?shorts=s1')
    render(<App />)
    const viewer = await screen.findByTestId('shorts-viewer')
    await waitFor(() => expect(window.location.search).toBe('?video=s1'))
    expect(viewer.querySelector('[data-current]')).toHaveAttribute('data-publication', 's1')
  })
})

describe('the full-screen vertical mode (F-S10)', () => {
  /** Opens the mode on a post's video (the home's shelf of shorts that opened it before is gone, 2026-11-28). */
  async function open(id = 's1') {
    const user = userEvent.setup()
    window.history.pushState(null, '', `/?video=${id}`)
    window.dispatchEvent(new PopStateEvent('popstate'))
    return { user, viewer: await screen.findByTestId('shorts-viewer') }
  }

  const current = () => screen.getAllByTestId('shorts-screen').find((s) => s.dataset.current)!

  it('opens over the home on the tapped one, with one player only', async () => {
    server()
    render(<App />)
    const { viewer } = await open('s1')
    expect(window.location.search).toBe('?video=s1')
    expect(viewer).toHaveAttribute('aria-modal', 'true')
    expect(current().dataset.publication).toBe('s1')
    expect(within(viewer).getByTestId('shorts-position')).toHaveTextContent('1 of 3')
    // Q65: one player in the whole page, the app's own, over the shown screen.
    await waitFor(() => expect(document.querySelectorAll('iframe')).toHaveLength(1))
    expect(screen.getByTestId('video-stage')).toHaveAttribute('data-mode', 'short')
    expect(within(current()).getByTestId('video-slot')).toHaveAttribute('data-mode', 'short')
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
    await waitFor(() => expect(within(current()).queryByTestId('video-player')).toBeNull())
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
    await user.click(within(current()).getByTestId('shorts-details'))
    await waitFor(() => expect(window.location.pathname).toBe('/p/s1'))
    expect(screen.queryByTestId('shorts-viewer')).toBeNull()
  })

  it('keeps the app\'s player within the screens while a screen moves in, off the header\'s buttons', async () => {
    server()
    render(<App />)
    const { viewer } = await open('s1')
    const stage = await screen.findByTestId('video-stage')
    const box = (top: number, height: number) => () => ({ top, bottom: top + height, left: 0, right: 390, width: 390,
      height, x: 0, y: top, toJSON: () => ({}) }) as DOMRect
    const screens = within(viewer).getByTestId('shorts-screens')
    vi.spyOn(screens, 'getBoundingClientRect').mockImplementation(box(48, 796))
    const slot = within(current()).getByTestId('video-slot')
    // The screen coming down from above (a move up, travelling): its top part is still over the header.
    const moving = vi.spyOn(slot, 'getBoundingClientRect').mockImplementation(box(-300, 640))
    await waitFor(() => expect(stage.style.clipPath).toBe('inset(348px 0px 0px 0px)'))
    expect(stage.style.visibility).toBe('visible')
    // Settled within the screens: nothing cut.
    moving.mockImplementation(box(60, 640))
    await waitFor(() => expect(stage.style.clipPath).toBe('inset(0px 0px 0px 0px)'))
    // Wholly outside them: not shown at all (nor touched).
    moving.mockImplementation(box(-700, 640))
    await waitFor(() => expect(stage.style.visibility).toBe('hidden'))
  })
})

describe('a vertical video fills the screen of the vertical mode (F-S10)', () => {
  const FILE = 'https://cdn.example.com/tall.mp4'
  const POSTS = [card('t1', { video: YOUTUBE, videoVertical: true }), card('t2', { video: FILE, videoVertical: true }),
    card('t3', { video: BILIBILI, videoVertical: true }), card('w1', { video: YOUTUBE.replace('dQw4w9WgXcQ', 'bbbbbbbbbbb') })]

  async function openOn(id: string) {
    server({ feed: POSTS })
    window.history.replaceState(null, '', `/?video=${id}`)
    render(<App />)
    const viewer = await screen.findByTestId('shorts-viewer')
    return viewer.querySelector('[data-current]') as HTMLElement
  }

  it('lays the app\'s player over all of the screen, the foot over the video, the screen taking the taps', async () => {
    const shown = await openOn('t1')
    const box = within(shown).getByTestId('shorts-video-box')
    // All of the screen's height, at most its width: on a phone all of it, wider a column of the video's shape.
    expect(box).toHaveClass('aspect-[9/16]', 'h-full', 'max-w-full')
    expect(box).not.toHaveClass('pointer-events-none')
    expect(box).not.toHaveClass('bg-neutral-900')
    expect(within(shown).getByTestId('video-slot')).toHaveAttribute('data-fit', 'cover')
    // The name and the buttons over the video, and still tappable.
    const foot = within(shown).getByTestId('shorts-foot')
    expect(box).toContainElement(foot)
    expect(foot).toHaveClass('pointer-events-auto')
    // The screens lie over the player, which lies over the black: out of the home's layers, in the page itself.
    expect(screen.getByTestId('shorts-viewer').parentElement).toBe(document.body)
    expect(screen.getByTestId('shorts-viewer')).toHaveClass('z-50')
    expect(screen.getByTestId('shorts-backdrop')).toHaveClass('z-40')
    await waitFor(() => expect(screen.getByTestId('video-stage')).toHaveStyle({ zIndex: '45' }))
    await expectAccessible(screen.getByTestId('shorts-viewer'))
  })

  it('fills the screen with a vertical video file, played muted by itself', async () => {
    const shown = await openOn('t2')
    expect(within(shown).getByTestId('video-slot')).toHaveAttribute('data-fit', 'cover')
    const video = await screen.findByTestId('video-stage-file') as HTMLVideoElement
    expect(video).toHaveClass('object-cover')
    expect(video.autoplay).toBe(true)
    expect(video.muted).toBe(true)
    // A short: never the browser's controls (paused on them, waiting for a tap); the screen's tap pauses and plays on.
    expect(video.controls).toBe(false)
    const pause = vi.spyOn(video, 'pause').mockImplementation(() => undefined)
    const play = vi.spyOn(video, 'play').mockResolvedValue(undefined)
    const toggle = within(shown).getByTestId('shorts-toggle')
    expect(toggle).toHaveAccessibleName('Pause')
    await userEvent.click(toggle)
    expect(pause).toHaveBeenCalledTimes(1)
    expect(toggle).toHaveAccessibleName('Play')
    await userEvent.click(toggle)
    expect(play).toHaveBeenCalled()
    expect(toggle).toHaveAccessibleName('Pause')
  })

  it('takes the taps on a YouTube short itself: they pause it and play it on, never reaching its controls', async () => {
    const shown = await openOn('t1')
    const frame = await screen.findByTitle('Video: Quiz t1') as HTMLIFrameElement
    const sent = vi.spyOn(frame.contentWindow!, 'postMessage').mockImplementation(() => undefined)
    const funcs = () => sent.mock.calls.map(([message]) => JSON.parse(String(message)).func)
    await userEvent.click(within(shown).getByTestId('shorts-toggle'))
    expect(funcs()).toContain('pauseVideo')
    await userEvent.click(within(shown).getByTestId('shorts-toggle'))
    expect(funcs().at(-1)).toBe('playVideo')
  })

  it('gives another site\'s player the vertical shape over all of the screen', async () => {
    const shown = await openOn('t3')
    const fit = within(shown).getByTestId('shorts-cover-fit')
    const player = within(fit).getByTestId('video-player')
    expect(player.className).toContain('h-[max(100%,calc(100cqw*16/9))]')
    expect(player.className).toContain('w-[max(100%,calc(100cqh*9/16))]')
    expect(player.querySelector('iframe')).toHaveAttribute('src', expect.stringContaining('autoplay=1'))
  })

  it('shows the video\'s own picture on every screen, not the sponsor\'s cover (it flashed as the video came)', async () => {
    server({ feed: POSTS.map((post) => ({ ...post, cover: 'cover-1' })) })
    window.history.replaceState(null, '', '/?video=t1')
    render(<App />)
    const viewer = await screen.findByTestId('shorts-viewer')
    const [t1, t2, t3, w1] = within(viewer).getAllByTestId('shorts-screen')
    // Not playing: YouTube's picture of the video; a file and a site without a picture show the sponsor's cover (no
    // `<video>` kept for a file's first frame).
    expect(within(w1).getByTestId('video-poster')).toHaveAttribute('src', 'https://i.ytimg.com/vi/bbbbbbbbbbb/hqdefault.jpg')
    expect(t2.querySelector('video')).toBeNull()
    expect(within(t2).queryByTestId('video-poster')).toBeNull()
    expect(within(t3).queryByTestId('video-poster')).toBeNull()
    // Playing: the same picture, the same element, stays over the app's player (beneath the screens) until the
    // player has its first frame; nothing of it is copied onto the stage (2026-11-28: painted anew, it flickered).
    const poster = within(t1).getByTestId('shorts-poster')
    expect(within(poster).getByTestId('video-poster')).toHaveAttribute('src', 'https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg')
    expect(poster).toHaveAttribute('data-shown')
    expect(poster.style.opacity).toBe('1')
    const stage = await screen.findByTestId('video-stage')
    expect(within(stage).queryByTestId('video-stage-cover')).toBeNull()
    // Its first frame (YouTube says it plays): the poster fades out, a beat later (the frame is painted after).
    const frame = stage.querySelector('iframe')!
    fireEvent(window, new MessageEvent('message', { source: frame.contentWindow, data: JSON.stringify({ event: 'onStateChange', info: 1 }) }))
    await waitFor(() => expect(poster).not.toHaveAttribute('data-shown'))
    expect(poster.style.opacity).toBe('0')
    expect(poster.style.transition).toContain('250ms')
  })

  it('keeps a landscape video across the middle, its foot under it', async () => {
    const shown = await openOn('w1')
    const box = within(shown).getByTestId('shorts-video-box')
    expect(box).toHaveClass('aspect-video')
    expect(box).not.toContainElement(within(shown).getByTestId('shorts-foot'))
    expect(within(shown).getByTestId('video-slot')).not.toHaveAttribute('data-fit')
  })
})

describe('the video mode of the posts (Q113c, §9.3)', () => {
  const LANDSCAPE = 'https://www.youtube-nocookie.com/embed/bbbbbbbbbbb'
  const POSTS = [card('v1', { video: LANDSCAPE, displayLanguage: 'ja' }), card('n1'),
    card('v2', { video: BILIBILI }), card('v3', { video: YOUTUBE, videoVertical: true })]
  const starts = (calls: { path: string; body: unknown }[]) =>
    calls.filter((c) => c.path.startsWith('/api/processes/QB_ATTEMPT_START')).map((c) => c.body)

  async function openVideos(id: string, feed: Record<string, unknown>[] = POSTS) {
    const api = server({ feed })
    window.history.replaceState(null, '', `/?video=${id}`)
    render(<App />)
    const viewer = await screen.findByTestId('shorts-viewer')
    return { api, viewer, user: userEvent.setup() }
  }
  const shown = () => screen.getByTestId('shorts-viewer').querySelector('[data-current]') as HTMLElement

  it('is black, over the posts with a video in the home\'s order (those without skipped), a landscape one across', async () => {
    const { viewer } = await openVideos('v1')
    // Black behind it all (the app's player lies between the black and the screens, its overlays over the video).
    expect(viewer).toHaveClass('text-white')
    expect(screen.getByTestId('shorts-backdrop')).toHaveClass('bg-black')
    expect(viewer).toHaveAccessibleName('Videos')
    expect(within(viewer).getAllByTestId('shorts-screen').map((s) => s.getAttribute('data-publication')))
      .toEqual(['v1', 'v2', 'v3'])
    expect(within(shown()).getByTestId('shorts-stage')).toHaveAttribute('data-orientation', 'landscape')
    expect(within(viewer).getAllByTestId('shorts-stage')[2]).toHaveAttribute('data-orientation', 'vertical')
    expect(within(viewer).getByTestId('shorts-position')).toHaveTextContent('1 of 3')
    // Over the foot: the Sponsor, the name, the reward, and the two buttons.
    expect(within(shown()).getByTestId('shorts-sponsor')).toHaveTextContent('Capitals Inc')
    expect(within(shown()).getByTestId('shorts-name')).toHaveTextContent('Quiz v1')
    expect(within(shown()).getByTestId('shorts-reward')).toHaveTextContent('Up to 100 Kudos')
    expect(within(shown()).getByTestId('shorts-details')).toHaveTextContent('Details')
    expect(within(shown()).getByTestId('shorts-start')).toHaveTextContent('Start the quiz')
    // Only the way back at the top left; it goes back to the home.
    await userEvent.click(within(viewer).getByTestId('shorts-close'))
    await waitFor(() => expect(screen.queryByTestId('shorts-viewer')).toBeNull())
  })

  it('starts the quiz shown at once, in the language it is shown in, and "Details" opens its start page', async () => {
    const { api, user } = await openVideos('v1')
    await user.click(within(shown()).getByTestId('shorts-start'))
    await waitFor(() => expect(window.location.pathname).toBe('/play/a1'))
    expect(starts(api.calls)).toEqual([expect.objectContaining({ publicationId: 'v1', language: 'ja' })])
    cleanup()
    vi.restoreAllMocks()
    const again = await openVideos('v1')
    await again.user.click(within(shown()).getByTestId('shorts-details'))
    await waitFor(() => expect(window.location.pathname).toBe('/p/v1'))
    expect(starts(again.api.calls)).toEqual([])
  })

  it('waits for the video to be watched first, saying how long (Q75)', async () => {
    await openVideos('v1', [card('v1', { video: LANDSCAPE, watchRequired: true, videoDurationSec: 30 })])
    const start = within(shown()).getByTestId('shorts-start')
    expect(start).toBeDisabled()
    // How long is the watch rule's (`video/playback.ts`, its tolerance under the length).
    expect(start).toHaveTextContent(/^\d+ s to watch$/)
    await expectAccessible()
  })

  it('continues an attempt in progress, and offers only the start page for one answered', async () => {
    const deadline = new Date(Date.now() + 120_000).toISOString()
    await openVideos('v1', [card('v1', { video: LANDSCAPE, myAttemptId: 'a9', myStatus: 'IN_PROGRESS', myDeadline: deadline }),
      card('v2', { video: BILIBILI, myAttemptId: 'a8', myStatus: 'SUBMITTED' })])
    expect(within(shown()).getByTestId('shorts-start')).toHaveTextContent('Continue the quiz')
    const answered = screen.getAllByTestId('shorts-screen')[1]
    expect(within(answered).getByTestId('shorts-details')).toBeInTheDocument()
    expect(within(answered).queryByTestId('shorts-start')).toBeNull()
  })

  it('reads the home\'s next page when the screen shown is near the end of the list', async () => {
    const page = Array.from({ length: 10 }, (_, i) => card(`w${i}`, { video: LANDSCAPE.replace('bbbbbbbbbbb', `w${i}`.padEnd(11, 'x')) }))
    const { api } = await openVideos('w9', page)
    await waitFor(() => expect(api.feedReads().some((read) => (read as unknown as { offset: number }).offset === 10)).toBe(true))
  })
})
