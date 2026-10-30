import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { APP } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { redeemLink } from './api'
import { staffMessages } from './messages'

// jsdom has no canvas: the QR code is drawn as text.
vi.mock('qrcode', () => ({ default: { toString: vi.fn(async (text: string) => `<svg>${text}</svg>`) } }))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }
const USER = '01a0f4fe-4660-7000-8000-0000000000aa'

const prize = (over: Record<string, unknown>) => ({ redemptionId: 'r1', code: 'C-ABCD1234',
  publicationName: 'Coffee week', takerName: 'Mei', description: 'A coffee at our counter', status: 'CLAIMED',
  claimedAt: '2026-10-20T01:00:00Z', confirmBy: '2099-01-01T00:10:00Z', redeemedAt: null, ...over })

interface Server {
  /** The sponsor whose staff the taker is on; none by default. */
  staffOf?: Record<string, unknown> | null
  /** The counter's prizes, answered by their status filter. */
  counter?: Record<string, unknown>[]
  processes?: Record<string, { status: number; body: unknown }>
}

/** The server as the app sees it, for a verified taker in Japan. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let staffOf = options.staffOf ?? null
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)
    if (process) {
      if (process[1] === 'QB_STAFF_LEAVE') staffOf = null
      const answer = options.processes?.[process[1]] ?? { status: 404, body: {} }
      return json(answer.status, answer.body)
    }
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: USER, displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.app.staff-of':
        return json(200, { items: staffOf ? [staffOf] : [], offset: 0, limit: 1 })
      case '/api/queries/qb.app.staff-redemptions': {
        const filter = (body as { filters?: { field: string; value: string }[] } | null)?.filters
          ?.find((f) => f.field === 'status')
        const items = (options.counter ?? []).filter((row) => !filter || row.status === filter.value)
        return json(200, { items, offset: 0, limit: 50 })
      }
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { processCalls, setStaffOf: (place: Record<string, unknown> | null) => { staffOf = place } }
}

const PLACE = { sponsorId: 's1', sponsorCompany: 'Capitals Inc', addedAt: '2026-10-20T00:00:00Z' }
const ISSUED = { status: 200, body: { output: { redeemKey: 'staff-key-1', kind: 'ROTATING',
  issuedAt: '2026-10-20T01:00:00Z', expiresAt: '2026-10-20T01:02:00Z' } } }

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the staff texts', () => {
  it('are in every language', () => {
    const english = keyPaths(staffMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(staffMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(APP, lng), lng).toEqual([])
    }
    expect(redeemLink('a b', 'https://quizbuks.com')).toBe('https://quizbuks.com/redeem/a%20b')
  })
})

describe('the account page (Q39)', () => {
  it('shows the taker\'s ID to copy, and no staff section for a taker on no staff', async () => {
    const writeText = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    server()
    window.history.replaceState(null, '', '/account/id')
    render(<App />)
    expect(await screen.findByTestId('my-id')).toHaveTextContent(USER)
    await userEvent.click(screen.getByTestId('my-id-copy'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(USER))
    expect(screen.queryByTestId('account-staff')).toBeNull()
  })

  it('shows whose staff the taker is on, leads to the counter and lets them leave', async () => {
    const { processCalls } = server({ staffOf: PLACE,
      processes: { QB_STAFF_LEAVE: { status: 200, body: { output: { userId: USER, changed: true } } } } })
    window.history.replaceState(null, '', '/account/id')
    render(<App />)
    const place = await screen.findByTestId('account-staff')
    expect(place).toHaveTextContent('You are on the staff of Capitals Inc')
    expect(within(place).getByTestId('staff-desk-link')).toHaveAttribute('href', '/staff')
    await userEvent.click(within(place).getByTestId('staff-leave'))
    await userEvent.click(await screen.findByRole('button', { name: 'Leave' }))
    await waitFor(() => expect(processCalls('QB_STAFF_LEAVE')).toEqual([{}]))
    await waitFor(() => expect(screen.queryByTestId('account-staff')).toBeNull())
  })
})

describe('the counter (Q39)', () => {
  it('shows the sponsor\'s code in the staff member\'s name, the claims to answer and the day\'s prizes', async () => {
    const { processCalls } = server({ staffOf: PLACE, counter: [prize({}),
      prize({ redemptionId: 'r2', code: 'C-00000002', takerName: 'Aki', status: 'REDEEMED',
        redeemedAt: new Date().toISOString() })],
    processes: { QB_STAFF_REDEEM_CODE_ISSUE: ISSUED,
      QB_STAFF_REDEMPTION_CONFIRM: { status: 200, body: { output: { redemptionId: 'r1', status: 'REDEEMED',
        revision: 2 } } } } })
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('Counter of Capitals Inc')
    expect(await screen.findByTestId('staff-code-link')).toHaveTextContent(`${window.location.origin}/redeem/staff-key-1`)
    await screen.findByTestId('staff-code-image')
    expect(processCalls('QB_STAFF_REDEEM_CODE_ISSUE')).toEqual([{}])
    const claim = await screen.findByTestId('staff-claim')
    expect(claim).toHaveTextContent('C-ABCD1234')
    expect(claim).toHaveTextContent('Mei')
    expect(await screen.findByTestId('staff-today-item')).toHaveTextContent('C-00000002')
    // The counter belongs to the account in the navigation.
    expect(screen.getByTestId('nav-account')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
    await userEvent.click(within(claim).getByTestId('staff-claim-confirm'))
    await waitFor(() => expect(processCalls('QB_STAFF_REDEMPTION_CONFIRM')).toEqual([{ redemptionId: 'r1' }]))
    // Answering a claim reads the lists again, never a new code.
    expect(processCalls('QB_STAFF_REDEEM_CODE_ISSUE')).toEqual([{}])
  })

  it('says so when the staff member is removed while the counter is open', async () => {
    const removed = { status: 422, body: { title: 'Rejected', status: 422, detail: 'You are not on a sponsor\'s staff.',
      violations: [{ field: null, ruleCode: 'QB_NOT_STAFF', message: 'You are not on a sponsor\'s staff.' }] } }
    const { setStaffOf } = server({ staffOf: PLACE, counter: [prize({})],
      processes: { QB_STAFF_REDEEM_CODE_ISSUE: ISSUED, QB_STAFF_REDEMPTION_CONFIRM: removed } })
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    const claim = await screen.findByTestId('staff-claim')
    setStaffOf(null)
    await userEvent.click(within(claim).getByTestId('staff-claim-confirm'))
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }))
      .toHaveTextContent('You are not on a sponsor\'s staff'))
  })

  it('tells a taker on no staff so, and asks for no code', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent('You are not on a sponsor\'s staff')
    expect(processCalls('QB_STAFF_REDEEM_CODE_ISSUE')).toEqual([])
  })
})
