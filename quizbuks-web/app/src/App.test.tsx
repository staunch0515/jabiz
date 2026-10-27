import { changeLanguage, configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', environment: 'staging' }

interface Me {
  emailVerified?: boolean
  profile?: Record<string, unknown> | null
  /** The accounts at identity providers linked already. */
  identities?: Record<string, unknown>[]
  /** The answer to saving the profile when it is refused. */
  refusal?: Record<string, unknown>
}

/**
 * The server as the app sees it: its entry, the identity providers given (none by default), and for a signed-in taker their identity, profile,
 * the countries and currencies. Records what was asked and what was posted.
 */
function server(me?: Me, providers: unknown[] = []) {
  const asked: string[] = []
  const posted: { path: string; body: unknown }[] = []
  let profile = me?.profile ?? null
  let mailLocale: string | null = null
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
        return json(200, providers)
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
      case '/api/auth/mail/preferences':
        // The app's own notifications only (decision D49): the platform filters by the token's entry.
        return request.method === 'POST' ? new Response(null, { status: 204 }) : json(200, { templates: [
          { template: 'qb.welcome', subscribed: true, displayName: 'Welcome after registration', otherEntry: false }] })
      case '/api/auth/mail/unsubscribe':
        return new Response(null, { status: 204 })
      case '/api/auth/account/locale':
        if (request.method === 'POST') {
          mailLocale = (body as { locale: string }).locale || null
          return new Response(null, { status: 204 })
        }
        return json(200, { locale: mailLocale, effective: mailLocale ?? 'en', languages: ['en', 'zh', 'ja'],
          defaultLocale: 'en' })
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
  window.localStorage.clear()
  window.history.replaceState(null, '', '/')
  // The browser's hints for the country: Japanese in Japan.
  vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['ja-JP', 'en'])
})
afterEach(async () => {
  vi.restoreAllMocks()
  // A test that switches the interface language must not leave it to the next one.
  await changeLanguage('en')
})

describe('a visitor', () => {
  it('sees the introduction once, then the sign-in (Q32)', async () => {
    server()
    render(<App />)
    const intro = await screen.findByTestId('intro')
    expect(intro).toHaveAttribute('data-slide', 'answer')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('intro-next'))
    expect(screen.getByTestId('intro')).toHaveAttribute('data-slide', 'earn')
    await userEvent.click(screen.getByRole('button', { name: 'Slide 3 of 3' }))
    expect(screen.getByTestId('intro')).toHaveAttribute('data-slide', 'collect')
    expect(screen.getByTestId('intro-next')).toHaveTextContent('Get started')
    await userEvent.click(screen.getByTestId('intro-next'))
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    // Seen: the root leads straight to the sign-in from now on.
    expect(window.localStorage.getItem('qb.intro.seen')).toBe('1')
    cleanup()
    window.history.replaceState(null, '', '/')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
  })

  it('shows no introduction to a visitor who came from a sign-up or mailed-link page', async () => {
    server()
    window.history.replaceState(null, '', '/auth/verify-email?entry=app&token=abc')
    render(<App />)
    expect(await screen.findByTestId('verify-email')).toBeInTheDocument()
    expect(window.localStorage.getItem('qb.intro.seen')).toBe('1')
    window.history.replaceState(null, '', '/')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
  })

  it('skips the introduction to the sign-in', async () => {
    server()
    render(<App />)
    await userEvent.click(await screen.findByTestId('intro-skip'))
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    expect(window.localStorage.getItem('qb.intro.seen')).toBe('1')
  })

  it('goes to the sign-in of the app\'s own entry, with sign-up and password reset', async () => {
    window.localStorage.setItem('qb.intro.seen', '1')
    const { asked } = server()
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    await waitFor(() => expect(document.querySelector('a[href="/signup"]')).not.toBeNull())
    expect(document.querySelector('a[href="/password-reset"]')).not.toBeNull()
    await waitFor(() => expect(asked).toContain('/api/auth/oidc/providers?entry=app'))
    // A test system says so before signing in (platform 16zd): the entry names the environment.
    expect(await screen.findByTestId('environment-banner')).toHaveTextContent('staging')
  })

  it('signs in and signs up with Google, Facebook and LINE as a row of icons (Q40)', async () => {
    window.localStorage.setItem('qb.intro.seen', '1')
    server(undefined, [{ id: 'google', kind: 'google', label: 'Google' }, { id: 'facebook', kind: 'facebook',
      label: 'Facebook' }, { id: 'line', kind: 'line', label: 'LINE' }])
    render(<App />)
    const row = await screen.findByTestId('oidc-icons')
    expect(row).toHaveTextContent('Other ways to sign in')
    expect(within(row).getAllByRole('button').map((b) => b.getAttribute('aria-label')))
      .toEqual(['Google', 'Facebook', 'LINE'])
    cleanup()
    window.history.replaceState(null, '', '/signup')
    render(<App />)
    const signUp = await screen.findByTestId('signup-providers')
    expect(within(signUp).getByRole('button', { name: 'Facebook' })).toHaveAttribute('data-kind', 'facebook')
  })

  it('opens the pages of mailed links without signing in, and drops the token from the address', async () => {
    server()
    window.history.replaceState(null, '', '/auth/verify-email?entry=app&token=abc')
    render(<App />)
    expect(await screen.findByTestId('verify-email')).toBeInTheDocument()
    expect(window.location.search).not.toContain('token')
  })

  it('unsubscribes on its own page without signing in, posting the link\'s token once (D52)', async () => {
    const { posted } = server()
    window.history.replaceState(null, '', '/mail/unsubscribe?token=t1')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('done'))
    expect(posted.filter((p) => p.path === '/api/auth/mail/unsubscribe')).toEqual([
      { path: '/api/auth/mail/unsubscribe', body: { token: 't1' } }])
    expect(window.location.pathname).toBe('/mail/unsubscribe')
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
    // The app's own header carries the test system's banner too (platform 16zd).
    expect(await screen.findByTestId('environment-banner')).toHaveTextContent('staging')
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

  it('turns the app\'s notifications on and off on the account page, and says which mail always goes', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const section = await screen.findByTestId('account-mail')
    expect(await within(section).findByTestId('mail-preference')).toHaveTextContent('Welcome after registration')
    expect(section).toHaveTextContent('Mail about your transfers and your payout account is always sent.')
    await userEvent.click(within(section).getByTestId('mail-preference-qb.welcome'))
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/auth/mail/preferences',
      body: { template: 'qb.welcome', subscribed: false } }))
  })

  it('asks before sending mail in the language the interface was switched to', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await userEvent.click(await screen.findByTestId('language-switch'))
    await userEvent.click(await screen.findByRole('option', { name: '日本語' }))
    // Nothing changes until the taker accepts (decision D49 item 3).
    const accept = await screen.findByRole('button', { name: 'メールにも使う' })
    expect(posted.filter((p) => p.path === '/api/auth/account/locale')).toEqual([])
    await userEvent.click(accept)
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/auth/account/locale', body: { locale: 'ja' } }))
  })

  it('is sent home from the sign-up and password reset pages', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/signup')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/'))
    expect(await screen.findByTestId('home')).toBeInTheDocument()
  })
})
