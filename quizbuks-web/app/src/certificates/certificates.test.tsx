import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { certificateLanguage, stateOf, type VerifiedCertificate } from './api'
import { takerHandleOf, verifyKeyOf } from './PublicCertificates'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }
const USER = '01a0f4fe-4660-7000-8000-0000000000cc'
const KEY = '0123456789abcdef0123456789abcdef'

const HELD = { certification: 'SPONSOR_APPLY', source: 'PASSED', heldFrom: '2026-10-01T00:00:00Z',
  heldUntil: '2099-10-01T00:00:00Z', certificateId: null, verifyKey: null }

const VERIFIED: VerifiedCertificate = { certification: 'SPONSOR_APPLY', certificationVersion: 1, holderName: 'Mei',
  heldFrom: '2026-10-01T00:00:00Z', validUntil: '2099-10-01T00:00:00Z', issuedAt: '2026-10-02T00:00:00Z',
  revokedAt: null, certificationNames: null, issuerName: null }

interface Server {
  signedIn?: boolean
  held?: Record<string, unknown>[]
  settings?: Record<string, unknown> | null
  verified?: Record<string, unknown> | null
  page?: Record<string, unknown>[]
  settingsRefusal?: Record<string, unknown>
}

/** The server as the app sees it: a verified taker (or nobody), their certifications and the public templates. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let held = options.held ?? [HELD]
  let settings = options.settings ?? null
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        if (options.signedIn === false) return json(401, {})
        return json(200, { userId: USER, displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: settings ? [{ id: USER, version: 1, attributes: settings }] : [], offset: 0,
          limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/processes/QB_MY_CERTIFICATIONS/latest':
        return json(200, { output: { held } })
      case '/api/processes/QB_CERTIFICATE_ISSUE/latest':
        held = held.map((h) => ({ ...h, certificateId: 'c1', verifyKey: KEY }))
        return json(200, { output: { certificateId: 'c1', verifyKey: KEY, heldUntil: HELD.heldUntil, issued: true } })
      case '/api/processes/QB_SETTINGS_SAVE/latest': {
        if (options.settingsRefusal) return json(422, options.settingsRefusal)
        const given = body as { publicHandle: string | null; publicPage: boolean | null }
        const handle = given.publicHandle === null ? (settings?.publicHandle ?? null)
          : given.publicHandle.trim().toLowerCase() || null
        settings = { ...(settings ?? {}), publicHandle: handle, publicPage: given.publicPage ?? settings?.publicPage
          ?? false }
        return json(200, { output: { soundOn: true, contentLanguages: [], publicHandle: handle,
          publicPage: settings.publicPage } })
      }
      case '/api/public/queries/qb.public.certificate':
        return json(200, { items: options.verified === null ? [] : [options.verified ?? VERIFIED], offset: 0,
          limit: 1 })
      case '/api/public/queries/qb.public.taker-page':
        return json(200, { items: options.page ?? [], offset: 0, limit: 20 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { processCalls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  configureClient({ entry: 'app' })
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('certificates (Q77)', () => {
  it('reads the verify and public page paths, the language and the state', () => {
    expect(verifyKeyOf(`/verify/${KEY.toUpperCase()}`)).toBe(KEY)
    expect(verifyKeyOf('/verify/')).toBeNull()
    expect(verifyKeyOf('/verify/%E0%A4%A')).toBeNull()
    expect(takerHandleOf('/u/Mei-Q77')).toBe('mei-q77')
    expect(takerHandleOf('/u/a/b')).toBeNull()
    expect(certificateLanguage('zh-CN')).toBe('zh')
    expect(certificateLanguage('fr')).toBe('en')
    const now = Date.parse('2027-01-01T00:00:00Z')
    expect(stateOf(VERIFIED, now)).toBe('VALID')
    expect(stateOf({ ...VERIFIED, validUntil: '2026-12-31T00:00:00Z' }, now)).toBe('EXPIRED')
    expect(stateOf({ ...VERIFIED, revokedAt: '2026-11-01T00:00:00Z' }, now)).toBe('REVOKED')
  })

  it('lists the certifications held; issuing is paused, and an issued certificate keeps its link', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ held: [HELD, { ...HELD, certification: 'REFERRER', certificateId: 'c1',
      verifyKey: KEY }] })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    const item = await screen.findByTestId('certificate-SPONSOR_APPLY')
    expect(item).toHaveTextContent('Sponsor Certification')
    expect(item).toHaveTextContent('Passed the certification quiz')
    // F-S2c (D8): no certificate is issued any more; one issued before still verifies and downloads.
    expect(within(item).getByTestId('certificate-paused-SPONSOR_APPLY')).toHaveTextContent('not issued for now')
    expect(within(item).queryByRole('button')).toBeNull()
    const issued = await screen.findByTestId('certificate-REFERRER')
    expect(within(issued).getByTestId('certificate-link-REFERRER')).toHaveTextContent(`/verify/${KEY}`)
    expect(within(issued).getByTestId('certificate-download-REFERRER')).toBeEnabled()
    expect(processCalls('QB_CERTIFICATE_ISSUE')).toEqual([])
    await expectAccessible()
  })

  it('says when nothing is held, and turns the public page on only with a name', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ held: [] })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    expect(await screen.findByTestId('certificates-none')).toBeInTheDocument()
    const on = await screen.findByTestId('certificates-page-on')
    expect(on).toBeDisabled()
    await userEvent.type(screen.getByTestId('certificates-handle'), 'Mei-Q77')
    await userEvent.click(screen.getByTestId('certificates-handle-save'))
    await waitFor(() => expect(processCalls('QB_SETTINGS_SAVE')).toContainEqual({ soundOn: null,
      contentLanguages: null, publicHandle: 'Mei-Q77', publicPage: null }))
    await waitFor(() => expect(on).toBeEnabled())
    expect(screen.getByTestId('certificates-handle')).toHaveValue('mei-q77')
    await userEvent.click(on)
    await waitFor(() => expect(processCalls('QB_SETTINGS_SAVE')).toContainEqual({ soundOn: null,
      contentLanguages: null, publicHandle: null, publicPage: true }))
    expect(await screen.findByTestId('certificates-page-link')).toHaveAttribute('href', '/u/mei-q77')
    await expectAccessible()
  })

  it('shows the server\'s reason when a name is refused', async () => {
    session.store('access', 'refresh')
    server({ held: [], settingsRefusal: { status: 422, title: 'Unprocessable', detail: 'Rejected',
      violations: [{ field: 'publicHandle', code: 'QB_PUBLIC_HANDLE_TAKEN',
        message: 'This public name is taken. Please choose another.' }] } })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    await userEvent.type(await screen.findByTestId('certificates-handle'), 'taken')
    await userEvent.click(screen.getByTestId('certificates-handle-save'))
    expect(await screen.findByText(/public name is taken/)).toBeInTheDocument()
  })

  it('verifies a certificate for anybody: valid, revoked, or none', async () => {
    server({ signedIn: false })
    window.history.replaceState(null, '', `/verify/${KEY}`)
    render(<App />)
    const page = await screen.findByTestId('verify-page')
    expect(within(page).getByTestId('verify-state')).toHaveTextContent('Valid')
    expect(within(page).getByTestId('verify-holder')).toHaveTextContent('Mei')
    expect(within(page).getByTestId('verify-certification')).toHaveTextContent('Sponsor Certification')
    expect(document.head.querySelector('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, verified: { ...VERIFIED, revokedAt: '2026-11-01T00:00:00Z' } })
    render(<App />)
    expect(await screen.findByTestId('verify-state')).toHaveTextContent('Revoked')
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, verified: null })
    render(<App />)
    expect(await screen.findByText('No such certificate')).toBeInTheDocument()
  })

  it('shows a public page\'s valid certificates, each leading to its verify page', async () => {
    server({ signedIn: false, page: [
      { handle: 'mei-q77', certification: 'SPONSOR_APPLY', holderName: 'Mei', heldFrom: VERIFIED.heldFrom,
        validUntil: VERIFIED.validUntil, verifyKey: KEY },
      { handle: 'mei-q77', certification: 'REFERRER', holderName: 'Mei', heldFrom: '2020-01-01T00:00:00Z',
        validUntil: '2021-01-01T00:00:00Z', verifyKey: 'f'.repeat(32) }] })
    window.history.replaceState(null, '', '/u/mei-q77')
    render(<App />)
    const page = await screen.findByTestId('taker-page')
    expect(within(page).getByTestId('taker-page-name')).toHaveTextContent('Mei')
    expect(within(page).getByTestId('taker-page-SPONSOR_APPLY')).toHaveAttribute('href', `/verify/${KEY}`)
    // Expired: left out.
    expect(within(page).queryByTestId('taker-page-REFERRER')).toBeNull()
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, page: [] })
    render(<App />)
    expect(await screen.findByText('This page is not available')).toBeInTheDocument()
  })
})
