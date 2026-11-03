import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { APP } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { scanTarget } from '../scan/target'
import { digitsOf, passLink } from '../staff/api'
import { passMessages } from './messages'
import { PassButton, spaced } from './PassDialog'

// jsdom has no canvas: the QR code is drawn as text.
vi.mock('qrcode', () => ({ default: { toString: vi.fn(async (text: string) => `<svg>${text}</svg>`) } }))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }
const PLACE = { sponsorId: 's1', sponsorCompany: 'Ramen B', addedAt: '2026-10-20T00:00:00Z' }
const PRIZE = { passId: 'pass-1', redemptionId: 'r1', publicationName: 'Fridge quiz', description: 'Beef noodles',
  amount: 30, takerName: 'Mei', status: 'DUE', passExpiresAt: '2099-01-01T00:01:00Z' }

/** The server for a verified taker in Japan, on Ramen B's staff unless told otherwise. Records what was posted. */
function server({ staff = true }: { staff?: boolean } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
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
        return json(200, { items: staff ? [PLACE] : [], offset: 0, limit: 1 })
      case '/api/queries/qb.app.staff-redemptions':
        return json(200, { items: [], offset: 0, limit: 50 })
      case '/api/processes/QB_STAFF_REDEEM_CODE_ISSUE/latest':
        return json(200, { output: { redeemKey: 'k1', kind: 'ROTATING', issuedAt: '2026-10-20T01:00:00Z',
          expiresAt: '2026-10-20T01:02:00Z' } })
      case '/api/processes/QB_STAFF_REDEMPTION_PASS_CHECK/latest':
        return json(200, { output: PRIZE })
      case '/api/processes/QB_STAFF_REDEMPTION_PASS_REDEEM/latest':
        return json(200, { output: { redemptionId: 'r1', status: 'REDEEMED', revision: 2 } })
      case '/api/processes/QB_REDEMPTION_PASS_ISSUE/latest':
        return json(200, { output: { redemptionId: 'r1', passKey: 'pass-key-1', passDigits: '123456',
          expiresAt: '2099-01-01T00:01:00Z' } })
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { calls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('the taker\'s pass (Q89c)', () => {
  it('has every text in every language, and its links and digits', () => {
    const english = keyPaths(passMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(passMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(APP, lng), lng).toEqual([])
    }
    const link = passLink('k 1', window.location.origin)
    expect(scanTarget(link)).toEqual({ page: 'pass', id: 'k 1' })
    expect(digitsOf('123 456')).toBe('123456')
    expect(digitsOf('12-34-56')).toBe('123456')
    expect(digitsOf('12345')).toBeNull()
    expect(spaced('123456')).toBe('123 456')
  })

  it('is shown to the counter with its digits and a minute to go', async () => {
    const { calls } = server()
    render(<QueryClientProvider client={new QueryClient()}><PassButton redemptionId="r1" collected={false} />
    </QueryClientProvider>)
    await userEvent.click(screen.getByTestId('pass-show'))
    const dialog = await screen.findByTestId('pass-dialog')
    expect(await within(dialog).findByTestId('pass-digits')).toHaveTextContent('123 456')
    expect(await within(dialog).findByTestId('pass-qr')).toBeInTheDocument()
    expect(within(dialog).getByTestId('pass-left')).toHaveTextContent(/6\d|5\d seconds/)
    expect(calls('QB_REDEMPTION_PASS_ISSUE')).toEqual([{ redemptionId: 'r1' }])
    await expectAccessible()
  })

  it('is read at the staff desk by its digits and the prize handed over', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    const section = await screen.findByTestId('staff-customer-pass')
    await userEvent.type(within(section).getByTestId('staff-pass-digits'), '123 456')
    await userEvent.click(within(section).getByTestId('staff-pass-read'))
    expect(await within(section).findByTestId('pass-prize-description')).toHaveTextContent('Beef noodles')
    expect(within(section).getByTestId('pass-prize-taker')).toHaveTextContent('Mei')
    expect(calls('QB_STAFF_REDEMPTION_PASS_CHECK')).toEqual([{ typedDigits: '123456' }])
    await expectAccessible()
    await userEvent.click(within(section).getByTestId('pass-hand-over'))
    await waitFor(() => expect(calls('QB_STAFF_REDEMPTION_PASS_REDEEM')).toEqual([{ passId: 'pass-1' }]))
    expect(await within(section).findByTestId('pass-next')).toBeInTheDocument()
  })

  it('opens from a scan at a staff member\'s phone, and is only a customer\'s pass to anybody else', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/pass/pass-key-1')
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('pass-prize-description')).toHaveTextContent('Beef noodles')
    expect(calls('QB_STAFF_REDEMPTION_PASS_CHECK')).toEqual([{ scannedPass: 'pass-key-1' }])
    unmount()
    vi.restoreAllMocks()

    const other = server({ staff: false })
    window.history.replaceState(null, '', '/pass/pass-key-1')
    render(<App />)
    expect(await screen.findByText('This is a customer\'s prize pass')).toBeInTheDocument()
    expect(other.calls('QB_STAFF_REDEMPTION_PASS_CHECK')).toEqual([])
  })
})
