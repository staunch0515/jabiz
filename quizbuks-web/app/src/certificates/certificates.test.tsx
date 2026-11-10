import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { certificateLanguage } from '../qb/certificates'
import { stateOf, type VerifiedCertificate } from './api'
import { takerHandleOf, verifyKeyOf } from './PublicCertificates'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }
const USER = '01a0f4fe-4660-7000-8000-0000000000cc'
const KEY = '0123456789abcdef0123456789abcdef'

const NODE = { source: 'NODE', certificateId: 'c1', verifyKey: KEY, names: { en: 'Coffee basics', ja: 'コーヒー基礎' },
  issuerName: 'Beans Platform', litAt: '2026-10-01T00:00:00Z', revokedAt: null, shared: true, runId: null,
  heldUntil: null, certification: null }
const OLD = { source: 'CERTIFICATION', certificateId: 'c0', verifyKey: 'f'.repeat(32), names: null, issuerName: null,
  litAt: '2026-09-01T00:00:00Z', revokedAt: null, shared: true, runId: null, heldUntil: '2099-10-01T00:00:00Z',
  certification: 'SPONSOR_APPLY' }

const VERIFIED: VerifiedCertificate = { source: 'NODE', shared: true, certification: null, certificationVersion: null,
  certificationNames: null, certificateNames: { en: 'Coffee basics' }, issuerName: 'Beans Platform', holderName: 'Mei',
  heldFrom: '2026-10-01T00:00:00Z', validUntil: null, issuedAt: '2026-10-01T00:00:00Z', revokedAt: null }
const VERIFIED_OLD: VerifiedCertificate = { ...VERIFIED, source: 'CERTIFICATION', certification: 'SPONSOR_APPLY',
  certificationVersion: 1, certificateNames: null, issuerName: null, validUntil: '2099-10-01T00:00:00Z' }

interface Server {
  signedIn?: boolean
  certificates?: Record<string, unknown>[]
  settings?: Record<string, unknown> | null
  verified?: Record<string, unknown> | null
  page?: Record<string, unknown>[]
  settingsRefusal?: Record<string, unknown>
}

/** The server as the app sees it: a verified taker (or nobody), their certificates and the public templates. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let certificates = options.certificates ?? [NODE]
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
        return json(200, { output: { certificates } })
      case '/api/processes/QB_CERTIFICATE_ISSUE/latest':
        certificates = certificates.map((c) => c.certificateId === body?.certificateId ? { ...c, runId: 'run1' } : c)
        return json(200, { output: { certificateId: body?.certificateId, verifyKey: KEY, runId: 'run1', issued: true } })
      case '/api/processes/QB_CERTIFICATE_SHARE_SET/latest':
        certificates = certificates.map((c) => c.certificateId === body?.certificateId
          ? { ...c, shared: body?.shared } : c)
        return json(200, { output: { certificateId: body?.certificateId, shared: body?.shared } })
      case '/api/documents/runs/run1/pdf':
        return new Response('%PDF-1.4', { status: 200, headers: { 'Content-Type': 'application/pdf',
          'Content-Disposition': 'attachment; filename="certificate.pdf"' } })
      case '/api/documents/runs':
        return json(200, [{ runId: 'run0' }])
      case '/api/documents/runs/run0/pdf':
        return new Response('%PDF-1.4', { status: 200, headers: { 'Content-Type': 'application/pdf' } })
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

const clicked: HTMLAnchorElement[] = []

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  configureClient({ entry: 'app' })
  clicked.length = 0
  // jsdom has neither object URLs nor navigation: the download is the anchor that was clicked.
  URL.createObjectURL = vi.fn(() => 'blob:certificate')
  URL.revokeObjectURL = vi.fn()
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicked.push(this)
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('certificates (Q77, F-S5b)', () => {
  it('reads the verify and public page paths, the language and the state', () => {
    expect(verifyKeyOf(`/verify/${KEY.toUpperCase()}`)).toBe(KEY)
    expect(verifyKeyOf('/verify/')).toBeNull()
    expect(verifyKeyOf('/verify/%E0%A4%A')).toBeNull()
    expect(takerHandleOf('/u/Mei-Q77')).toBe('mei-q77')
    expect(takerHandleOf('/u/a/b')).toBeNull()
    expect(certificateLanguage('zh-CN')).toBe('zh')
    expect(certificateLanguage('fr')).toBe('en')
    const now = Date.parse('2027-01-01T00:00:00Z')
    // A node's certificate has no end: valid until revoked or switched off.
    expect(stateOf(VERIFIED, now)).toBe('VALID')
    expect(stateOf(VERIFIED, Date.parse('2999-01-01T00:00:00Z'))).toBe('VALID')
    expect(stateOf({ ...VERIFIED, revokedAt: '2026-11-01T00:00:00Z' }, now)).toBe('REVOKED')
    expect(stateOf({ ...VERIFIED, shared: false }, now)).toBe('DISABLED')
    expect(stateOf({ ...VERIFIED, shared: false, revokedAt: '2026-11-01T00:00:00Z' }, now)).toBe('DISABLED')
    // Q77's certificates keep their end.
    expect(stateOf(VERIFIED_OLD, now)).toBe('VALID')
    expect(stateOf({ ...VERIFIED_OLD, validUntil: '2026-12-31T00:00:00Z' }, now)).toBe('EXPIRED')
  })

  it('lists the certificates: a node\'s with its issuer and day, Q77\'s with its holding, nothing about paused issuing',
    async () => {
      session.store('access', 'refresh')
      server({ certificates: [NODE, OLD] })
      window.history.replaceState(null, '', '/account/certificates')
      render(<App />)
      const items = await screen.findAllByTestId('certificate-item')
      expect(items).toHaveLength(2)
      const [node, old] = items
      expect(within(node).getByTestId('certificate-name')).toHaveTextContent('Coffee basics')
      expect(within(node).getByTestId('certificate-issuer')).toHaveTextContent('Issued by Beans Platform')
      expect(within(node).getByTestId('certificate-dates')).toHaveTextContent('Lit on')
      expect(within(node).getByTestId('certificate-dates')).not.toHaveTextContent('–')
      expect(within(node).getByTestId('certificate-link')).toHaveTextContent(`/verify/${KEY}`)
      expect(within(node).getByTestId('certificate-share')).toBeChecked()
      expect(within(old).getByTestId('certificate-name')).toHaveTextContent('Sponsor Certification')
      expect(within(old).getByTestId('certificate-dates')).toHaveTextContent('–')
      // Q77's certificate has no sharing to set.
      expect(within(old).queryByTestId('certificate-share')).toBeNull()
      expect(screen.queryByTestId('certificates-paused')).toBeNull()
      await expectAccessible()
    })

  it('issues the PDF on the first download only, then downloads the stored one', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ certificates: [NODE] })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    await userEvent.click(await screen.findByTestId('certificate-download'))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(processCalls('QB_CERTIFICATE_ISSUE')).toEqual([{ certificateId: 'c1', language: 'en' }])
    expect(clicked[0].download).toBe('certificate.pdf')
    // The list read again has the run: the next click is the stored PDF, no issuing.
    await waitFor(() => expect(processCalls('QB_MY_CERTIFICATIONS').length).toBeGreaterThan(1))
    await userEvent.click(screen.getByTestId('certificate-download'))
    await waitFor(() => expect(clicked).toHaveLength(2))
    expect(processCalls('QB_CERTIFICATE_ISSUE')).toHaveLength(1)
  })

  it('downloads a Q77 certificate\'s PDF as issued back then, without issuing', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ certificates: [OLD] })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    await userEvent.click(await screen.findByTestId('certificate-download'))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(processCalls('QB_CERTIFICATE_ISSUE')).toEqual([])
  })

  it('copies the verify link and turns its sharing off and back on', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ certificates: [NODE] })
    const writeText = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    await userEvent.click(await screen.findByTestId('certificate-copy'))
    expect(writeText).toHaveBeenCalledWith(expect.stringContaining(`/verify/${KEY}`))

    await userEvent.click(screen.getByTestId('certificate-share'))
    await waitFor(() => expect(processCalls('QB_CERTIFICATE_SHARE_SET')).toEqual([{ certificateId: 'c1', shared: false }]))
    // Off: the hint says so, and there is no link to copy.
    await waitFor(() => expect(screen.getByTestId('certificate-share')).not.toBeChecked())
    expect(screen.getByTestId('certificate-share-hint')).toHaveTextContent('turned off')
    expect(screen.queryByTestId('certificate-copy')).toBeNull()
    expect(screen.queryByTestId('certificate-link')).toBeNull()
    await expectAccessible()

    await userEvent.click(screen.getByTestId('certificate-share'))
    await waitFor(() => expect(processCalls('QB_CERTIFICATE_SHARE_SET')).toContainEqual({ certificateId: 'c1', shared: true }))
    await waitFor(() => expect(screen.getByTestId('certificate-copy')).toBeInTheDocument())
  })

  it('marks a revoked certificate and shares nothing of it; one issued before stays downloadable', async () => {
    session.store('access', 'refresh')
    const revoked = { ...NODE, revokedAt: '2026-11-01T00:00:00Z' }
    server({ certificates: [revoked, { ...revoked, certificateId: 'c2', verifyKey: 'e'.repeat(32), runId: 'run1' }] })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    const [first, second] = await screen.findAllByTestId('certificate-item')
    for (const item of [first, second]) {
      expect(within(item).getByTestId('certificate-revoked')).toHaveTextContent('Revoked')
      expect(item).toHaveAttribute('data-revoked', 'true')
      expect(within(item).queryByTestId('certificate-share')).toBeNull()
      expect(within(item).queryByTestId('certificate-copy')).toBeNull()
    }
    // Never issued: the server would refuse the first PDF, so none is offered; an issued one is.
    expect(within(first).queryByTestId('certificate-download')).toBeNull()
    expect(within(second).getByTestId('certificate-download')).toBeInTheDocument()
    await expectAccessible()
  })

  it('says when there is none, and turns the public page on only with a name', async () => {
    session.store('access', 'refresh')
    const { processCalls } = server({ certificates: [] })
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
    server({ certificates: [], settingsRefusal: { status: 422, title: 'Unprocessable', detail: 'Rejected',
      violations: [{ field: 'publicHandle', code: 'QB_PUBLIC_HANDLE_TAKEN',
        message: 'This public name is taken. Please choose another.' }] } })
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    await userEvent.type(await screen.findByTestId('certificates-handle'), 'taken')
    await userEvent.click(screen.getByTestId('certificates-handle-save'))
    expect(await screen.findByText(/public name is taken/)).toBeInTheDocument()
  })

  it('verifies a certificate for anybody: valid with no validity row, revoked, switched off, or none', async () => {
    server({ signedIn: false })
    window.history.replaceState(null, '', `/verify/${KEY}`)
    render(<App />)
    const page = await screen.findByTestId('verify-page')
    expect(within(page).getByTestId('verify-state')).toHaveTextContent('Valid')
    expect(within(page).getByTestId('verify-holder')).toHaveTextContent('Mei')
    expect(within(page).getByTestId('verify-certification')).toHaveTextContent('Coffee basics')
    expect(within(page).getByTestId('verify-issuer')).toHaveTextContent('Beans Platform')
    // A node's certificate has a day it was lit and no validity (A2-12).
    expect(within(page).getByTestId('verify-lit')).toBeInTheDocument()
    expect(within(page).queryByTestId('verify-held')).toBeNull()
    expect(within(page).queryByText(/valid until|expires/i)).toBeNull()
    expect(document.head.querySelector('meta[name="robots"]')).toHaveAttribute('content', 'noindex')
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, verified: { ...VERIFIED, revokedAt: '2026-11-01T00:00:00Z' } })
    render(<App />)
    expect(await screen.findByTestId('verify-state')).toHaveTextContent('Revoked')
    cleanup()
    vi.restoreAllMocks()

    // Switched off by the holder: the state and nothing of the certificate.
    server({ signedIn: false, verified: { source: 'NODE', shared: false, certification: null, certificationVersion: null,
      certificationNames: null, certificateNames: null, issuerName: null, holderName: null, heldFrom: null,
      validUntil: null, issuedAt: null, revokedAt: null } })
    render(<App />)
    expect(await screen.findByTestId('verify-state')).toHaveTextContent('Link turned off')
    expect(screen.getByTestId('verify-disabled')).toBeInTheDocument()
    expect(screen.queryByTestId('verify-holder')).toBeNull()
    expect(screen.queryByTestId('verify-certification')).toBeNull()
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, verified: null })
    render(<App />)
    expect(await screen.findByText('No such certificate')).toBeInTheDocument()
  })

  it('verifies a Q77 certificate with its holding', async () => {
    server({ signedIn: false, verified: { ...VERIFIED_OLD } })
    window.history.replaceState(null, '', `/verify/${KEY}`)
    render(<App />)
    const page = await screen.findByTestId('verify-page')
    expect(within(page).getByTestId('verify-certification')).toHaveTextContent('Sponsor Certification')
    expect(within(page).getByTestId('verify-held')).toHaveTextContent('–')
    expect(within(page).queryByTestId('verify-lit')).toBeNull()
  })

  it('shows a public page\'s certificates, each leading to its verify page', async () => {
    server({ signedIn: false, page: [
      { handle: 'mei-q77', source: 'NODE', certification: null, certificationNames: null,
        certificateNames: '{"en":"Coffee basics"}', issuerName: 'Beans Platform', holderName: 'Mei',
        heldFrom: VERIFIED.heldFrom, validUntil: null, verifyKey: KEY },
      { handle: 'mei-q77', source: 'CERTIFICATION', certification: 'SPONSOR_APPLY', certificationNames: null,
        certificateNames: null, issuerName: null, holderName: 'Mei', heldFrom: '2026-10-01T00:00:00Z',
        validUntil: '2099-10-01T00:00:00Z', verifyKey: 'd'.repeat(32) },
      { handle: 'mei-q77', source: 'CERTIFICATION', certification: 'REFERRER', certificationNames: null,
        certificateNames: null, issuerName: null, holderName: 'Mei', heldFrom: '2020-01-01T00:00:00Z',
        validUntil: '2021-01-01T00:00:00Z', verifyKey: 'f'.repeat(32) }] })
    window.history.replaceState(null, '', '/u/mei-q77')
    render(<App />)
    const page = await screen.findByTestId('taker-page')
    expect(within(page).getByTestId('taker-page-name')).toHaveTextContent('Mei')
    const node = within(page).getByTestId(`taker-page-${KEY}`)
    expect(node).toHaveAttribute('href', `/verify/${KEY}`)
    expect(node).toHaveTextContent('Coffee basics')
    expect(node).toHaveTextContent('Lit on')
    expect(within(page).getByTestId('taker-page-SPONSOR_APPLY')).toHaveTextContent('–')
    // Q77's, expired: left out.
    expect(within(page).queryByTestId('taker-page-REFERRER')).toBeNull()
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()

    server({ signedIn: false, page: [] })
    render(<App />)
    expect(await screen.findByText('This page is not available')).toBeInTheDocument()
  })
})
