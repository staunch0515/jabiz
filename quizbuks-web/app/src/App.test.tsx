import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

interface Me {
  emailVerified?: boolean
  profile?: Record<string, unknown> | null
  /** The accounts at identity providers linked already. */
  identities?: Record<string, unknown>[]
  /** The answer to saving the profile when it is refused. */
  refusal?: Record<string, unknown>
}

/**
 * The server as the app sees it: its entry, no identity providers, and for a signed-in taker their identity, profile,
 * the countries and currencies. Records what was asked and what was posted.
 */
function server(me?: Me) {
  const asked: string[] = []
  const posted: { path: string; body: unknown }[] = []
  let profile = me?.profile ?? null
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    asked.push(path + url.search)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return me ? json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read', 'qb.country.read'], emailVerified: me.emailVerified ?? true,
          idleTimeoutSeconds: 0 }) : json(401, {})
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: profile ? [{ id: 'p1', version: 1, attributes: profile }] : [], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:default:QbCountry/query':
        return json(200, { items: [
          { id: 'c1', attributes: { code: 'JP', name: { en: 'Japan', zh: '日本', ja: '日本' } } },
          { id: 'c2', attributes: { code: 'FR', name: { en: 'France', zh: '法国', ja: 'フランス' } } },
        ], offset: 0, limit: 300 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:currency':
        return json(200, [{ code: 'USD', label: 'US dollar' }, { code: 'EUR', label: 'Euro' },
          { code: 'JPY', label: 'Japanese yen' }])
      case '/api/auth/account/identities':
        return json(200, me?.identities ?? [])
      case '/api/processes/QB_PROFILE_SAVE/latest':
        if (me?.refusal) return json(422, me.refusal)
        profile = { displayName: (body as { displayName: string }).displayName, countryCode: 'JP', city: null,
          preferredCurrency: 'JPY', revision: 1 }
        return json(200, { output: { userId: 'u1', profileId: 'p1', revision: 1, editedAt: '2026-10-14T00:00:00Z',
          created: true } })
      default:
        return json(404, {})
    }
  })
  return { asked, posted }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  window.history.replaceState(null, '', '/')
  // The browser's hints for the country: Japanese in Japan.
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ja-JP', 'en'])
})
afterEach(() => vi.restoreAllMocks())

describe('a visitor', () => {
  it('goes to the sign-in of the app\'s own entry, with sign-up and password reset', async () => {
    const { asked } = server()
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    await waitFor(() => expect(document.querySelector('a[href="/signup"]')).not.toBeNull())
    expect(document.querySelector('a[href="/password-reset"]')).not.toBeNull()
    await waitFor(() => expect(asked).toContain('/api/auth/oidc/providers?entry=app'))
  })

  it('opens the pages of mailed links without signing in, and drops the token from the address', async () => {
    server()
    window.history.replaceState(null, '', '/auth/verify-email?entry=app&token=abc')
    render(<App />)
    expect(await screen.findByTestId('verify-email')).toBeInTheDocument()
    expect(window.location.search).not.toContain('token')
  })

  it('cannot open the app\'s pages', async () => {
    server()
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
  })
})

describe('a signed-in taker', () => {
  beforeEach(() => session.store('access', 'refresh'))

  it('completes the profile first, starting from the sign-up name and the browser\'s country', async () => {
    const { posted } = server({ profile: null })
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/welcome'))
    expect(await screen.findByTestId('profile-name')).toHaveValue('Mei')
    expect(screen.getByTestId('profile-country')).toHaveTextContent('Japan')
    expect(screen.getByTestId('profile-currency')).toHaveTextContent('Japanese yen')
    // No bottom navigation before the profile is complete.
    expect(screen.queryByRole('navigation', { name: 'App' })).toBeNull()

    await userEvent.clear(screen.getByTestId('profile-name'))
    await userEvent.type(screen.getByTestId('profile-name'), 'Mei Tanaka')
    await userEvent.type(screen.getByTestId('profile-city'), 'Osaka')
    await userEvent.click(screen.getByTestId('profile-save'))

    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(posted).toContainEqual({ path: '/api/processes/QB_PROFILE_SAVE/latest', body: {
      displayName: 'Mei Tanaka', countryCode: 'JP', city: 'Osaka', preferredCurrency: 'JPY', baseRevision: null } })
    expect(await screen.findByTestId('home')).toHaveTextContent('Mei Tanaka')
    expect(screen.getByRole('navigation', { name: 'App' })).toBeInTheDocument()
    expect(screen.getByTestId('nav-home')).toHaveAttribute('aria-current', 'page')
  })

  it('unverified, browses the app with the banner rather than being held at the welcome page', async () => {
    server({ profile: null, emailVerified: false })
    render(<App />)
    expect(await screen.findByTestId('home')).toHaveTextContent('Mei')
    expect(window.location.pathname).toBe('/')
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('mei@example.com')
    expect(screen.getByRole('navigation', { name: 'App' })).toBeInTheDocument()
  })

  it('unverified, can open the welcome page but not save it, and is told why', async () => {
    server({ profile: null, emailVerified: false })
    window.history.replaceState(null, '', '/welcome')
    render(<App />)
    expect(await screen.findByTestId('profile-verify-first')).toBeInTheDocument()
    expect(window.location.pathname).toBe('/welcome')
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('mei@example.com')
    expect(screen.getByTestId('profile-save')).toBeDisabled()
  })

  it('with a profile, starts at home and never sees the welcome page again', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 3 } })
    window.history.replaceState(null, '', '/welcome')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(await screen.findByTestId('home')).toHaveTextContent('Mei')
  })

  it('edits the profile on the account page with the revision it read', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: 'Kyoto',
      preferredCurrency: 'EUR', revision: 3 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    expect(await screen.findByTestId('profile-name')).toHaveValue('Mei')
    expect(screen.getByTestId('profile-city')).toHaveValue('Kyoto')
    expect(screen.getByTestId('profile-currency')).toHaveTextContent('Euro')
    expect(screen.getByTestId('account-email')).toHaveTextContent('mei@example.com')
    await userEvent.click(screen.getByTestId('profile-save'))
    expect(await screen.findByTestId('profile-saved')).toBeInTheDocument()
    expect(posted).toContainEqual({ path: '/api/processes/QB_PROFILE_SAVE/latest', body: {
      displayName: 'Mei', countryCode: 'JP', city: 'Kyoto', preferredCurrency: 'EUR', baseRevision: 3 } })
  })

  it('ties a refusal to the field it names', async () => {
    server({ profile: null, refusal: { detail: 'Refused', violations: [
      { field: 'countryCode', ruleCode: 'QB_COUNTRY_UNKNOWN', message: 'There is no such country.' },
      { field: 'city', ruleCode: 'X', message: 'The city is too long.' },
    ] } })
    render(<App />)
    await userEvent.click(await screen.findByTestId('profile-save'))
    const country = screen.getByTestId('profile-country')
    await waitFor(() => expect(country).toHaveAttribute('aria-invalid', 'true'))
    expect(country).toHaveAccessibleDescription('There is no such country.')
    expect(screen.getByTestId('profile-city')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByTestId('profile-city')).toHaveAccessibleDescription('The city is too long.')
  })

  it('sees accounts linked earlier even when the app offers no provider', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 },
      identities: [{ id: 'i1', provider: 'google', label: 'Google', kind: 'google', email: 'mei@gmail.com',
        linkedAt: '2026-10-13T09:00:00Z' }] })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    expect(await screen.findByTestId('linked-identity')).toHaveTextContent('mei@gmail.com')
  })

  it('is sent home from the sign-up and password reset pages', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/signup')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(await screen.findByTestId('home')).toBeInTheDocument()
  })
})
