import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { domainOfWebsite, topUpOf, VERIFICATION_CONFIRM_PATH, VERIFICATION_PATH, verificationOf } from './api'
import { verificationMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const NONE = { verificationId: null, status: null, verified: false, onboardingAmount: 1000, certifiedAmount: 10000,
  toppedUp: false }

/** The server as the verification sees it; `row` is what `qb.sponsor.verification` gives. */
function server(options: { row?: () => Record<string, unknown>; submit?: (input: Record<string, unknown>) => Response;
  confirm?: () => Response } = {}) {
  const sent: { name: string; input: Record<string, unknown> }[] = []
  const row = options.row ?? (() => NONE)
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const process = /^\/api\/processes\/([^/]+)\//.exec(path)
    if (process && request.method === 'POST') {
      const body = await request.clone().json() as Record<string, unknown>
      sent.push({ name: process[1], input: body })
      if (process[1] === 'QB_SPONSOR_VERIFICATION_SUBMIT') {
        return options.submit ? options.submit(body) : json(200, { output: { verificationId: 'v1', status: 'REVIEW',
          method: 'ACCOUNT', approvalRequestId: 'r1' } })
      }
      if (process[1] === 'QB_SPONSOR_VERIFICATION_CONFIRM') {
        return options.confirm ? options.confirm() : json(200, { output: { verificationId: 'v1', status: 'REVIEW',
          method: 'LINK', approvalRequestId: 'r1' } })
      }
      return json(404, {})
    }
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.verification'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.sponsor.verification':
        return json(200, { items: [row()], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      default:
        return json(404, {})
    }
  })
  return { sent }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('the company verification (F-M3)', () => {
  it('has every text in every language', () => {
    const english = keyPaths(verificationMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(verificationMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })

  it('reads the row, the top-up and a website\'s domain', () => {
    expect(verificationOf({ ...NONE, onboardingAmount: '1000', certifiedAmount: '10000' })).toMatchObject({
      verificationId: null, verified: false, onboardingAmount: 1000, certifiedAmount: 10000 })
    expect(topUpOf(verificationOf(NONE))).toBe(9000)
    expect(topUpOf(verificationOf({ ...NONE, verified: true }))).toBeNull()
    expect(topUpOf(verificationOf({ ...NONE, toppedUp: true }))).toBeNull()
    expect(topUpOf(verificationOf({ ...NONE, onboardingAmount: null }))).toBeNull()
    expect(domainOfWebsite('https://www.Acme.co.jp/about')).toBe('acme.co.jp')
    expect(domainOfWebsite('not a url')).toBe('')
    expect(domainOfWebsite(null)).toBe('')
  })

  it('submits the company with an address on the domain and says what a verification tops up', async () => {
    const { sent } = server()
    window.history.replaceState(null, '', VERIFICATION_PATH)
    render(<App />)
    expect(await screen.findByTestId('verification-top-up')).toHaveTextContent('9,000')
    await userEvent.type(screen.getByTestId('verification-company'), 'Acme K.K.')
    await userEvent.type(screen.getByTestId('verification-domain'), 'acme.co.jp')
    await userEvent.type(screen.getByTestId('verification-address'), 'info@acme.co.jp')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('verification-submit'))
    expect(await screen.findByTestId('verification-submitted')).toBeInTheDocument()
    expect(sent.find((s) => s.name === 'QB_SPONSOR_VERIFICATION_SUBMIT')?.input).toEqual({
      companyName: 'Acme K.K.', domain: 'acme.co.jp', confirmAddress: 'info@acme.co.jp' })
  })

  it('shows the server\'s refusal by its field', async () => {
    server({ submit: () => json(422, { violations: [{ field: 'domain', ruleCode: 'QB_VERIFICATION_PUBLIC_DOMAIN',
      message: 'gmail.com is a public mail service.' }] }) })
    window.history.replaceState(null, '', VERIFICATION_PATH)
    render(<App />)
    await userEvent.type(await screen.findByTestId('verification-company'), 'Acme')
    await userEvent.type(screen.getByTestId('verification-domain'), 'gmail.com')
    await userEvent.click(screen.getByTestId('verification-submit'))
    expect(await screen.findByText('gmail.com is a public mail service.')).toBeInTheDocument()
    expect(screen.getByTestId('verification-domain')).toHaveAttribute('aria-invalid', 'true')
  })

  it('shows a link out, offers it again, and the verified and withdrawn states with their reasons', async () => {
    let current: Record<string, unknown> = { ...NONE, verificationId: 'v1', status: 'LINK_SENT', companyName: 'Acme',
      domain: 'acme.co.jp', method: 'LINK', confirmAddress: 'info@acme.co.jp',
      linkExpiresAt: '2999-01-01T00:00:00Z' }
    server({ row: () => current })
    window.history.replaceState(null, '', VERIFICATION_PATH)
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('verification-link')).toHaveTextContent('info@acme.co.jp')
    expect(screen.getByTestId('verification-submit')).toHaveTextContent('Send the link again')
    unmount()
    current = { ...current, status: 'APPROVED', verified: true, verifiedAt: '2026-11-10T00:00:00Z', toppedUp: true }
    const second = render(<App />)
    expect(await screen.findByTestId('verification-status')).toHaveAttribute('data-status', 'APPROVED')
    expect(screen.getByTestId('verification-status')).toHaveTextContent('acme.co.jp')
    expect(screen.queryByTestId('verification-top-up')).toBeNull()
    await expectAccessible()
    second.unmount()
    current = { ...current, status: 'REVOKED', verified: false, revokeReason: 'The domain lapsed' }
    render(<App />)
    expect(await screen.findByTestId('verification-revoked')).toHaveTextContent('The domain lapsed')
  })

  it('confirms the mailed link once', async () => {
    const { sent } = server()
    window.history.replaceState(null, '', `${VERIFICATION_CONFIRM_PATH}?token=abc`)
    render(<App />)
    expect(await screen.findByTestId('verification-confirmed')).toBeInTheDocument()
    await waitFor(() => expect(sent.filter((s) => s.name === 'QB_SPONSOR_VERIFICATION_CONFIRM')).toEqual([
      { name: 'QB_SPONSOR_VERIFICATION_CONFIRM', input: { token: 'abc' } }]))
    await expectAccessible()
  })

  it('says when the link cannot be used', async () => {
    server({ confirm: () => json(422, { violations: [{ field: 'token', ruleCode: 'QB_VERIFICATION_LINK_INVALID',
      message: 'This link cannot be used.' }] }) })
    window.history.replaceState(null, '', `${VERIFICATION_CONFIRM_PATH}?token=old`)
    render(<App />)
    expect(await screen.findByTestId('verification-confirm-failed')).toBeInTheDocument()
  })
})
