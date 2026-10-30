import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { APP } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { createDecoder } from './decoder'
import { scanMessages } from './messages'
import { scanTarget, targetPath } from './target'

vi.mock('./decoder', () => ({ createDecoder: vi.fn() }))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }
const ORIGIN = window.location.origin

/** The server for a verified taker in Japan; the redeem code `k1` is the sponsor's screen with no prize to collect. */
function server() {
  const posted: { path: string; body: unknown }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    if (request.method === 'POST') posted.push({ path, body: await request.clone().json().catch(() => null) })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.app.staff-of':
        return json(200, { items: [], offset: 0, limit: 1 })
      case '/api/processes/QB_REDEEM_TOKEN_CHECK/latest':
        return json(200, { output: { sponsorCompany: 'Capitals Inc', kind: 'ROTATING', prizes: [] } })
      default:
        return json(404, {})
    }
  })
  return { posted }
}

/** A camera whose stream's tracks record being stopped, and a decoder reading `texts` one picture after another. */
function camera(texts: (string | null)[], getUserMedia?: () => Promise<MediaStream>) {
  const stopTrack = vi.fn()
  const stream = { getTracks: () => [{ stop: stopTrack }] } as unknown as MediaStream
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true,
    value: { getUserMedia: vi.fn(getUserMedia ?? (async () => stream)) } })
  const queue = [...texts]
  vi.mocked(createDecoder).mockResolvedValue({ decode: vi.fn(async () => (queue.length ? queue.shift()! : null)) })
  return { stopTrack, getUserMedia: navigator.mediaDevices.getUserMedia as ReturnType<typeof vi.fn> }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
})
afterEach(() => {
  vi.restoreAllMocks()
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
})

describe('the scanner texts', () => {
  it('are in every language', () => {
    const english = keyPaths(scanMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(scanMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(APP, lng), lng).toEqual([])
    }
  })
})

describe('what a scanned code opens (Q52)', () => {
  it('opens this site\'s publications and redeem codes, under the app\'s path or at the root of old codes', () => {
    expect(scanTarget('https://quizbuks.com/taker/redeem/k%201', 'https://quizbuks.com', '/taker/'))
      .toEqual({ page: 'redeem', id: 'k 1' })
    expect(scanTarget(' https://quizbuks.com/taker/p/pub-1?shell=android ', 'https://quizbuks.com', '/taker/'))
      .toEqual({ page: 'p', id: 'pub-1' })
    expect(scanTarget('https://quizbuks.com/redeem/k1', 'https://quizbuks.com', '/taker/'))
      .toEqual({ page: 'redeem', id: 'k1' })
    expect(scanTarget(`${ORIGIN}/redeem/k1`)).toEqual({ page: 'redeem', id: 'k1' })
    expect(targetPath({ page: 'redeem', id: 'k 1' })).toBe('/redeem/k%201')
  })

  it('opens nothing else: another site, another page of the app, plain text', () => {
    const site = 'https://quizbuks.com'
    for (const text of ['https://quizbuks.com.evil.example/taker/redeem/k1', 'http://quizbuks.com/taker/redeem/k1',
      'https://evil.example/taker/redeem/k1', 'https://quizbuks.com/taker/play/a1', 'https://quizbuks.com/taker/result/a1',
      'https://quizbuks.com/taker/', 'https://quizbuks.com/admin/redeem/k1', 'javascript:alert(1)', 'k1', '']) {
      expect(scanTarget(text, site, '/taker/'), text).toBeNull()
    }
  })
})

describe('the scanner (Q52)', () => {
  it('is offered in the header, starts the camera, ignores a foreign code and opens the scanned redeem code', async () => {
    const { posted } = server()
    const { stopTrack, getUserMedia } = camera([null, 'https://evil.example/redeem/x', `${ORIGIN}/redeem/k1`])
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const open = await screen.findByTestId('scan-open')
    expect(open).toHaveAttribute('href', '/scan')
    expect(open).toHaveTextContent('Scan')
    await userEvent.click(open)
    expect(await screen.findByTestId('scan-page')).toBeInTheDocument()
    // The back camera, no sound.
    expect(getUserMedia).toHaveBeenCalledWith({ video: { facingMode: { ideal: 'environment' } }, audio: false })
    // A code of another site is said to be none of QuizBuks's, and the camera keeps looking.
    expect(await screen.findByText(/This is not a QuizBuks code/)).toBeInTheDocument()
    await expectAccessible()
    // This site's redeem code opens its page in place of the scanner, and the camera stops.
    expect(await screen.findByTestId('redeem-page')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/redeem/k1')
    expect(stopTrack).toHaveBeenCalled()
    expect(posted.filter((p) => p.path === '/api/processes/QB_REDEEM_TOKEN_CHECK/latest').map((p) => p.body))
      .toEqual([{ scannedKey: 'k1' }])
    // The scanner is not in the history: back leaves the prize for where the person was.
    expect(screen.queryByTestId('scan-open')).toBeInTheDocument()
  })

  it('opens a publication\'s page from a shop\'s poster', async () => {
    server()
    camera([`${ORIGIN}/p/pub-1`])
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/p/pub-1'))
  })

  it('says how to scan otherwise when the camera is refused, and tries again on request', async () => {
    server()
    const { getUserMedia } = camera([], async () => {
      throw new DOMException('denied', 'NotAllowedError')
    })
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    expect(await screen.findByTestId('scan-denied')).toHaveTextContent('Allow it in your phone\'s settings')
    // The header does not offer the scanner on the scanner.
    expect(screen.queryByTestId('scan-open')).toBeNull()
    await expectAccessible()
    await userEvent.click(screen.getByTestId('scan-again'))
    await waitFor(() => expect(getUserMedia).toHaveBeenCalledTimes(2))
  })

  it('says so where the browser has no camera access, and closes back home', async () => {
    server()
    vi.mocked(createDecoder).mockReset()
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    expect(await screen.findByTestId('scan-unavailable')).toHaveTextContent('camera app')
    expect(screen.queryByTestId('scan-again')).toBeNull()
    await userEvent.click(screen.getByTestId('scan-close'))
    await waitFor(() => expect(window.location.pathname).toBe('/'))
  })

  it('stops the camera when the person leaves the scanner', async () => {
    server()
    const { stopTrack } = camera([])
    window.history.replaceState(null, '', '/scan')
    render(<App />)
    expect(await screen.findByText('Looking for a code…')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('scan-close'))
    await waitFor(() => expect(stopTrack).toHaveBeenCalled())
  })
})
