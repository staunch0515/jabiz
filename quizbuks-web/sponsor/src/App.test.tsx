import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

interface Me {
  sponsor?: Record<string, unknown> | null
  idleTimeoutSeconds?: number
  /** The answer to applying. */
  applied?: { status: number; body: unknown }
  /** The rows of `qb.me.consents` (the texts in effect and the caller's standing); none: nothing in effect. */
  consents?: Record<string, unknown>[]
}

const terms = (versionNo: number, mustAccept: boolean) => ({ kind: 'TERMS_SPONSOR', versionId: `v${versionNo}`,
  versionNo, title: { en: 'Sponsor terms', ja: 'スポンサー利用規約' }, changeSummary: { en: 'Fees are clearer.' },
  requiresConsent: true, effectiveAt: '2026-10-01T00:00:00Z', toAccept: versionNo, accepted: mustAccept ? 1 : versionNo,
  mustAccept })
const privacy = { kind: 'PRIVACY', versionId: 'p1', versionNo: 1, title: { en: 'Privacy policy' }, changeSummary: null,
  requiresConsent: true, effectiveAt: '2026-09-01T00:00:00Z', toAccept: 1, accepted: 1, mustAccept: false }

/**
 * The server as the console sees it (built with the base / in tests): its entry, no identity providers, and for a
 * signed-in sponsor their identity and onboarding. Records what was asked and posted.
 */
function server(me?: Me) {
  const asked: string[] = []
  const posted: { path: string; body: unknown }[] = []
  let consents = me?.consents ?? []
  let sponsor = me?.sponsor ?? null
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    asked.push(path + url.search)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return me ? json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me', 'qb.account.read'], emailVerified: true,
          idleTimeoutSeconds: me.idleTimeoutSeconds ?? 1800 }) : json(401, {})
      case '/api/auth/logout':
        return json(204, {})
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: sponsor ? [{ id: 's1', version: 1, attributes: sponsor }] : [],
          offset: 0, limit: 1 })
      case '/api/processes/QB_SPONSOR_APPLY/latest':
        if (me?.applied?.status === 200) {
          // Applying records the application and the consents with it.
          sponsor = { reviewStatus: 'PENDING', contactName: 'Sara', company: 'Acme Quiz', revision: 1 }
          consents = consents.map((row) => ({ ...row, accepted: row.versionNo, mustAccept: false }))
        }
        return me?.applied ? json(me.applied.status, me.applied.body) : json(404, {})
      case '/api/queries/qb.me.consents':
        return json(200, { items: consents, offset: 0, limit: 20 })
      case '/api/processes/QB_CONSENT_ACCEPT/latest':
        consents = consents.map((row) => ({ ...row, accepted: row.versionNo, mustAccept: false }))
        return json(200, { output: { recorded: ['TERMS_SPONSOR'] } })
      case '/api/public/queries/qb.public.faq':
        return json(200, { items: [{ faqId: 'f1', category: 'BILLING', question: { en: 'How do I top up?' },
          answer: { en: 'From the **wallet**.' }, seq: 1 }], offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  return { asked, posted }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  window.history.replaceState(null, '', '/')
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('a visitor', () => {
  it('reads the help without signing in (Q7b)', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/help')
    render(<App />)
    expect(await screen.findByTestId('faq-entry')).toHaveTextContent('How do I top up?')
    expect(screen.getByTestId('help-contact')).toHaveTextContent('Sign in to contact us.')
    expect(asked.some((a) => a.startsWith('/api/public/queries/qb.public.faq?p.audience=SPONSOR'))).toBe(true)
    expect(window.location.pathname).toBe('/help')
  })

  it('goes to the console\'s own sign-in, with sign-up and password reset', async () => {
    const { asked } = server()
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    await waitFor(() => expect(document.querySelector('a[href="/signup"]')).not.toBeNull())
    expect(document.querySelector('a[href="/password-reset"]')).not.toBeNull()
    await waitFor(() => expect(asked).toContain('/api/auth/oidc/providers?entry=sponsor'))
  })

  it('opens the pages of mailed links without signing in', async () => {
    server()
    window.history.replaceState(null, '', '/auth/password-reset?entry=sponsor&token=abc')
    render(<App />)
    expect(await screen.findByTestId('password-reset')).toBeInTheDocument()
    expect(window.location.search).not.toContain('token')
  })
})

describe('a signed-in sponsor', () => {
  beforeEach(() => session.store('access', 'refresh'))

  it('who has not applied is led to the onboarding and applies with the terms accepted', async () => {
    const { posted } = server({ sponsor: null, applied: { status: 200, body: { output: { userId: 'u2',
      onboardingId: 'o1', reviewStatus: 'PENDING', revision: 1, approval: 'PENDING', approvalRequestId: 'r1' } } } })
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/onboarding'))
    expect(await screen.findByTestId('onboarding-status')).toHaveAttribute('data-status', 'NONE')
    expect(screen.getByTestId('onboarding-name')).toHaveValue('Sara')
    await userEvent.type(screen.getByTestId('onboarding-company'), 'Acme Quiz')
    await userEvent.type(screen.getByTestId('onboarding-intro'), 'We make **quizzes**.')
    await userEvent.click(screen.getByTestId('onboarding-terms'))
    await userEvent.click(screen.getByTestId('onboarding-submit'))
    expect(await screen.findByTestId('onboarding-done')).toBeInTheDocument()
    expect(posted).toContainEqual({ path: '/api/processes/QB_SPONSOR_APPLY/latest', body: {
      displayName: 'Sara', company: 'Acme Quiz', intro: 'We make **quizzes**.', avatar: null, baseRevision: null,
      acceptTerms: true, termsVersionNo: null, privacyVersionNo: null } })
  })

  it('applies accepting the versions in effect, each linked to its text (Q7b)', async () => {
    const { posted } = server({ sponsor: null, consents: [terms(2, true), privacy], applied: { status: 200,
      body: { output: { userId: 'u2', onboardingId: 'o1', reviewStatus: 'PENDING', revision: 1, approval: 'PENDING',
        approvalRequestId: 'r1' } } } })
    window.history.replaceState(null, '', '/onboarding')
    render(<App />)
    // Before applying there is nothing to accept again: no prompt, the form accepts with the application.
    expect(await screen.findByTestId('legal-link-TERMS_SPONSOR')).toHaveAttribute('href', '/legal/sponsor-terms')
    expect(screen.getByTestId('legal-link-PRIVACY')).toHaveAttribute('target', '_blank')
    expect(screen.queryByTestId('consent-gate')).toBeNull()
    await userEvent.type(screen.getByTestId('onboarding-company'), 'Acme Quiz')
    await userEvent.click(screen.getByTestId('onboarding-terms'))
    await userEvent.click(screen.getByTestId('onboarding-submit'))
    expect(await screen.findByTestId('onboarding-done')).toBeInTheDocument()
    // The application is there now: no prompt for what it accepted, and the form says it was sent.
    await waitFor(() => expect(screen.getByTestId('onboarding-status')).toHaveAttribute('data-status', 'PENDING'))
    expect(screen.queryByTestId('consent-gate')).toBeNull()
    expect(screen.getByTestId('onboarding-done')).toBeInTheDocument()
    expect(posted.find((p) => p.path === '/api/processes/QB_SPONSOR_APPLY/latest')?.body).toMatchObject({
      acceptTerms: true, termsVersionNo: 2, privacyVersionNo: 1 })
  })

  it('who applied before the new terms accepts them first, or signs out (Q7b)', async () => {
    const { posted } = server({ sponsor: { reviewStatus: 'APPROVED', contactName: 'Sara', company: 'Acme',
      revision: 1 }, consents: [terms(2, true), privacy] })
    window.history.replaceState(null, '', '/dashboard')
    render(<App />)
    const gate = await screen.findByTestId('consent-gate')
    expect(screen.queryByTestId('dashboard')).toBeNull()
    expect(screen.getByTestId('consent-item-TERMS_SPONSOR')).toHaveTextContent('Fees are clearer.')
    expect(screen.queryByTestId('consent-item-PRIVACY')).toBeNull()
    expect(gate).toHaveTextContent('Version 2')
    expect(screen.getByTestId('consent-accept')).toBeDisabled()
    await userEvent.click(screen.getByTestId('consent-agree'))
    await userEvent.click(screen.getByTestId('consent-accept'))
    await waitFor(() => expect(screen.queryByTestId('consent-gate')).toBeNull())
    expect(posted.find((p) => p.path === '/api/processes/QB_CONSENT_ACCEPT/latest')?.body).toEqual({
      items: [{ kind: 'TERMS_SPONSOR', versionNo: 2 }] })
    expect(await screen.findByTestId('legal-links')).toBeInTheDocument()
  })

  it('reads the help inside the console, with the legal links (Q7b)', async () => {
    server({ sponsor: { reviewStatus: 'APPROVED', contactName: 'Sara', company: 'Acme', revision: 1 } })
    window.history.replaceState(null, '', '/help')
    render(<App />)
    expect(await screen.findByTestId('help-category-BILLING')).toHaveTextContent('Top-ups and fees')
    expect(screen.getByTestId('faq-entry')).toHaveTextContent('How do I top up?')
    const links = screen.getByTestId('legal-links')
    expect(links.querySelector('a[href="/legal/sponsor-terms"]')).not.toBeNull()
    expect(links.querySelector('a[href="/legal/commerce"]')).not.toBeNull()
  })

  it('sees the server\'s refusal next to the field it names', async () => {
    server({ sponsor: null, applied: { status: 422, body: { detail: 'Refused', violations: [
      { field: 'acceptTerms', ruleCode: 'QB_SPONSOR_TERMS_REQUIRED', message: 'Accept the terms to apply.' },
      { field: null, ruleCode: 'QB_USER_BANNED', message: 'Your account is banned.' },
    ] } } })
    window.history.replaceState(null, '', '/onboarding')
    render(<App />)
    await userEvent.type(await screen.findByTestId('onboarding-company'), 'Acme')
    await userEvent.click(screen.getByTestId('onboarding-submit'))
    expect(await screen.findByText('Accept the terms to apply.')).toBeInTheDocument()
    expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Your account is banned.')
    // Tied to its field for assistive technology.
    expect(screen.getByTestId('onboarding-terms')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByTestId('onboarding-terms')).toHaveAccessibleDescription('Accept the terms to apply.')
    expect(screen.getByTestId('onboarding-company')).not.toHaveAttribute('aria-invalid')
  })

  it('sent back, sees why and applies again', async () => {
    server({ sponsor: { contactName: 'Sara', company: 'Acme', intro: null, avatar: null, reviewStatus: 'REJECTED',
      rejectReason: 'Add a company website.', revision: 2 } })
    window.history.replaceState(null, '', '/onboarding')
    render(<App />)
    expect(await screen.findByTestId('onboarding-reject-reason')).toHaveTextContent('Add a company website.')
    expect(screen.getByTestId('onboarding-submit')).toHaveTextContent('Apply again')
  })

  it('approved, starts at the dashboard and changes the profile without the terms', async () => {
    server({ sponsor: { contactName: 'Sara', company: 'Acme', intro: null, avatar: null, reviewStatus: 'APPROVED',
      approvedAt: '2026-10-14T00:00:00Z', revision: 3 } })
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/dashboard'))
    expect(await screen.findByTestId('dashboard-status')).toHaveAttribute('data-status', 'APPROVED')
    await userEvent.click(screen.getByRole('link', { name: 'Sponsor profile' }))
    expect(window.location.pathname).toBe('/onboarding')
    expect(await screen.findByTestId('onboarding-submit')).toHaveTextContent('Save')
    expect(screen.queryByTestId('onboarding-terms')).toBeNull()
  })

  it('is signed out after the entry\'s idle time and told why', async () => {
    // Fake timers from the start (the idle timer is set once the identity is known), running on by themselves.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    server({ sponsor: null, idleTimeoutSeconds: 60 })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    expect(await screen.findByTestId('account')).toBeInTheDocument()
    await act(async () => {
      vi.advanceTimersByTime(61_000)
    })
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    expect(await screen.findByTestId('idle-locked')).toBeInTheDocument()
  })

  it('is sent home from the sign-up and password reset pages', async () => {
    server({ sponsor: { contactName: 'Sara', company: 'Acme', intro: null, avatar: null, reviewStatus: 'APPROVED',
      revision: 3 } })
    window.history.replaceState(null, '', '/password-reset')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/dashboard'))
  })

  it('has every field\'s refusal described on the field', async () => {
    server({ sponsor: null, applied: { status: 400, body: { violations: [
      { field: 'displayName', ruleCode: 'QB_PROFILE_NAME_BLANK', message: 'Enter a name.' },
      { field: 'company', ruleCode: 'QB_SPONSOR_COMPANY_BLANK', message: 'Enter the company.' },
      { field: 'intro', ruleCode: 'X', message: 'Too long.' },
      { field: 'avatar', ruleCode: 'QB_FILE_NOT_OWN', message: 'Upload your own image.' },
    ] } } })
    window.history.replaceState(null, '', '/onboarding')
    render(<App />)
    await userEvent.click(await screen.findByTestId('onboarding-submit'))
    await screen.findByText('Enter a name.')
    expect(screen.getByTestId('onboarding-name')).toHaveAccessibleDescription('Enter a name.')
    expect(screen.getByTestId('onboarding-company')).toHaveAccessibleDescription('Enter the company.')
    expect(screen.getByTestId('onboarding-intro')).toHaveAccessibleDescription(/Markdown.*Too long\./)
    expect(screen.getByTestId('onboarding-avatar')).toHaveAccessibleDescription(/Upload your own image\./)
    for (const id of ['onboarding-name', 'onboarding-company', 'onboarding-intro', 'onboarding-avatar']) {
      expect(screen.getByTestId(id)).toHaveAttribute('aria-invalid', 'true')
    }
  })
})
