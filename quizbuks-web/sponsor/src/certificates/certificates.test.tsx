import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { certificateVerifyLink } from './paths'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const KEY = '0123456789abcdef0123456789abcdef'
const NODE = { source: 'NODE', certificateId: 'c1', verifyKey: KEY, names: { en: 'Fair dealing' },
  issuerName: 'Beans Platform', litAt: '2026-10-01T00:00:00Z', revokedAt: null, shared: true, runId: null,
  heldUntil: null, certification: null }

/** The server as the Sponsor app sees it: an approved Sponsor and their certificates. */
function server(initial: Record<string, unknown>[] = [NODE]) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let certificates = initial
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/processes/QB_SPONSOR_MY_CERTIFICATES/latest':
        return json(200, { output: { certificates } })
      case '/api/processes/QB_SPONSOR_CERTIFICATE_ISSUE/latest':
        certificates = certificates.map((c) => ({ ...c, runId: 'run1' }))
        return json(200, { output: { certificateId: 'c1', verifyKey: KEY, runId: 'run1', issued: true } })
      case '/api/processes/QB_SPONSOR_CERTIFICATE_SHARE_SET/latest':
        certificates = certificates.map((c) => ({ ...c, shared: body?.shared }))
        return json(200, { output: { certificateId: 'c1', shared: body?.shared } })
      case '/api/documents/runs/run1/pdf':
        return new Response('%PDF-1.4', { status: 200, headers: { 'Content-Type': 'application/pdf' } })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      default:
        return json(404, {})
    }
  })
  return { calls: (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body) }
}

const clicked: HTMLAnchorElement[] = []

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  clicked.length = 0
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

describe('the Sponsor\'s certificates (F-S5b)', () => {
  it('addresses the verify page of the app, below this site\'s /taker/', () => {
    expect(certificateVerifyLink(KEY, 'https://quiz.example')).toBe(`https://quiz.example/taker/verify/${KEY}`)
  })

  it('is a tab of the hub, lists the certificates and issues the PDF on the first download', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/profile/certificates')
    render(<App />)
    const page = await screen.findByTestId('sponsor-certificates')
    expect(screen.getByTestId('page-tab-certificates')).toHaveAttribute('href', '/profile/certificates')
    expect(screen.getByTestId('page-tab-certificates')).toHaveAttribute('aria-current', 'page')
    const item = await within(page).findByTestId('certificate-item')
    expect(within(item).getByTestId('certificate-name')).toHaveTextContent('Fair dealing')
    expect(within(item).getByTestId('certificate-issuer')).toHaveTextContent('Beans Platform')
    expect(within(item).getByTestId('certificate-link')).toHaveTextContent(`/taker/verify/${KEY}`)
    await expectAccessible()
    await userEvent.click(within(item).getByTestId('certificate-download'))
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(calls('QB_SPONSOR_CERTIFICATE_ISSUE')).toEqual([{ certificateId: 'c1', language: 'en' }])
  })

  it('turns the sharing of a certificate off and back on', async () => {
    const { calls } = server()
    window.history.replaceState(null, '', '/profile/certificates')
    render(<App />)
    await userEvent.click(await screen.findByTestId('certificate-share'))
    await waitFor(() => expect(calls('QB_SPONSOR_CERTIFICATE_SHARE_SET')).toEqual([{ certificateId: 'c1', shared: false }]))
    await waitFor(() => expect(screen.getByTestId('certificate-share')).not.toBeChecked())
    expect(screen.getByTestId('certificate-share-hint')).toHaveTextContent('turned off')
    await userEvent.click(screen.getByTestId('certificate-share'))
    await waitFor(() => expect(calls('QB_SPONSOR_CERTIFICATE_SHARE_SET')).toContainEqual({ certificateId: 'c1', shared: true }))
  })

  it('marks a revoked certificate and says when there is none', async () => {
    server([{ ...NODE, revokedAt: '2026-11-01T00:00:00Z' }])
    window.history.replaceState(null, '', '/profile/certificates')
    render(<App />)
    expect(await screen.findByTestId('certificate-revoked')).toHaveTextContent('Revoked')
    expect(screen.queryByTestId('certificate-share')).toBeNull()
    cleanup()
    vi.restoreAllMocks()
    server([])
    render(<App />)
    expect(await screen.findByTestId('certificates-none')).toBeInTheDocument()
  })
})
