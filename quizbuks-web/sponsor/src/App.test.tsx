import { changeLanguage, configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
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
}

/**
 * The server as the console sees it (built with the base / in tests): its entry, no identity providers, and for a
 * signed-in sponsor their identity and onboarding. Records what was asked and posted.
 */
function server(me?: Me) {
  const asked: string[] = []
  const posted: { path: string; body: unknown }[] = []
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
        return json(200, { items: me?.sponsor ? [{ id: 's1', version: 1, attributes: me.sponsor }] : [],
          offset: 0, limit: 1 })
      case '/api/processes/QB_SPONSOR_APPLY/latest':
        return me?.applied ? json(me.applied.status, me.applied.body) : json(404, {})
      case '/api/auth/mail/preferences':
        // The console's own notifications only (decision D49): the platform filters by the token's entry.
        return request.method === 'POST' ? new Response(null, { status: 204 }) : json(200, { templates: [
          { template: 'qb.publication.funds-short', subscribed: true, displayName: 'Publications short of funds',
            otherEntry: false },
          { template: 'qb.publication.ended', subscribed: false, displayName: 'Publication ended',
            otherEntry: false }] })
      case '/api/auth/account/locale':
        return request.method === 'POST' ? new Response(null, { status: 204 }) : json(200, { locale: null,
          effective: 'en', languages: ['en', 'zh', 'ja'], defaultLocale: 'en' })
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
      acceptTerms: true } })
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

  it('turns the console\'s notifications on and off on the account page', async () => {
    const { posted } = server({ sponsor: { contactName: 'Sara', company: 'Acme', intro: null, avatar: null,
      reviewStatus: 'APPROVED', revision: 3 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const section = await screen.findByTestId('account-mail')
    await waitFor(() => expect(within(section).getAllByTestId('mail-preference')).toHaveLength(2))
    expect(section).toHaveTextContent('Publications short of funds')
    expect(section).toHaveTextContent('Publication ended')
    expect(section).toHaveTextContent('Mail about your top-ups, the reviews of your publications')
    expect(within(section).getByTestId('mail-preference-qb.publication.ended')).not.toBeChecked()
    await userEvent.click(within(section).getByTestId('mail-preference-qb.publication.ended'))
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/auth/mail/preferences',
      body: { template: 'qb.publication.ended', subscribed: true } }))
  })

  it('offers the new interface language for mail, and changes nothing when declined', async () => {
    const { asked, posted } = server({ sponsor: { contactName: 'Sara', company: 'Acme', intro: null, avatar: null,
      reviewStatus: 'APPROVED', revision: 3 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await userEvent.click(await screen.findByTestId('language-switch'))
    await userEvent.click(await screen.findByRole('option', { name: '中文' }))
    await userEvent.click(await screen.findByRole('button', { name: '暂不' }))
    expect(asked).toContain('/api/auth/account/locale')
    expect(posted.filter((p) => p.path === '/api/auth/account/locale')).toEqual([])
    await changeLanguage('en')
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
