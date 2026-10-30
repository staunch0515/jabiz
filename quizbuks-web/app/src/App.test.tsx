import { changeLanguage, configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { soundEnabled } from './qb/sound'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', environment: 'staging', emailLink: true }

interface Me {
  emailVerified?: boolean
  profile?: Record<string, unknown> | null
  /** The accounts at identity providers linked already. */
  identities?: Record<string, unknown>[]
  /** Q57: the account has the sound effects off. */
  soundOff?: boolean
  /** Q59a: the languages of the quizzes switched on (comma-separated), none when absent. */
  contentLanguages?: string
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
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: me?.soundOff || me?.contentLanguages ? [{ id: 'u1', version: 0, attributes: {
          userId: 'u1', soundOff: me?.soundOff === true, contentLanguages: me?.contentLanguages ?? null } }]
          : [], offset: 0, limit: 1 })
      case '/api/processes/QB_SETTINGS_SAVE/latest':
        return json(200, { output: { soundOn: (body as { soundOn: boolean | null }).soundOn ?? true,
          contentLanguages: (body as { contentLanguages: string[] | null }).contentLanguages ?? [] } })
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
    // In the sign-up card, as on the sign-in page, under the words of signing up; with the terms' note (17u).
    const signUp = await screen.findByTestId('oidc-icons')
    expect(screen.getByTestId('sign-up')).toContainElement(signUp)
    expect(signUp).toHaveTextContent('Other ways to sign up')
    expect(screen.getByTestId('sign-up')).toContainElement(screen.getByTestId('signup-terms'))
    expect(await within(signUp).findByRole('button', { name: 'Facebook' })).toHaveAttribute('data-kind', 'facebook')
  })

  it('asks for the address, and in Chinese offers the mailed link first, before Google, Facebook and LINE (Q50)',
    async () => {
      window.localStorage.setItem('qb.intro.seen', '1')
      server(undefined, [{ id: 'google', kind: 'google', label: 'Google' }, { id: 'line', kind: 'line', label: 'LINE' }])
      const { unmount } = render(<App />)
      await screen.findByTestId('oidc-icons')
      expect(screen.getByTestId('login-user-name')).toHaveAttribute('placeholder', 'E-mail address')
      // Not in English: the password first, as before.
      expect(screen.queryByTestId('email-link-form')).toBeNull()
      unmount()
      await changeLanguage('zh')
      render(<App />)
      const mailed = await screen.findByTestId('email-link-form')
      const name = screen.getByTestId('login-user-name')
      const icons = await screen.findByTestId('oidc-icons')
      expect(mailed.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(name.compareDocumentPosition(icons) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(name).toHaveAttribute('placeholder', '电子邮箱')
      expect(screen.getByTestId('login-or-password')).toHaveTextContent('或用密码登录')
      // The buttons stay.
      expect(within(icons).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['Google', 'LINE'])
      await expectAccessible()
    })

  it('says how to leave WeChat for the phone\'s browser from the first visit, and nothing in a browser (Q50)', async () => {
    window.localStorage.setItem('qb.intro.seen', '1')
    const agent = vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Linux; Android 14; 23116PN5BC '
      + 'Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile '
      + 'Safari/537.36 XWEB/1160117 MicroMessenger/8.0.49.2600(0x28003133) WeChat/arm64')
    server()
    const { unmount } = render(<App />)
    const notice = await screen.findByTestId('pwa-install')
    expect(notice).toHaveAttribute('data-situation', 'in-app')
    expect(notice).toHaveTextContent('tap "···" at the top right')
    await expectAccessible()
    unmount()
    agent.mockReturnValue('Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like '
      + 'Gecko) Version/18.1 Mobile/15E148 Safari/604.1')
    render(<App />)
    await screen.findByTestId('login-user-name')
    // In Safari the offer to install waits for a finished quiz (D-Q38-1).
    expect(screen.queryByTestId('pwa-install')).toBeNull()
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

  it('opens the account as a menu of its parts, each on a page of its own (Q56)', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account')
    render(<App />)
    const menu = await screen.findByTestId('account')
    expect(await within(menu).findByTestId('account-who')).toHaveTextContent('Mei · mei@example.com')
    const settings = within(menu).getByRole('navigation', { name: 'Settings' })
    expect(within(settings).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/account/profile', '/account/details', '/account/security', '/account/mail', '/account/preferences',
      '/account/id'])
    expect(within(menu).getByTestId('account-menu-security')).toHaveTextContent('Sign-in and security')
    // Only the menu: no form of a part on it.
    expect(screen.queryByTestId('profile-name')).toBeNull()
    await expectAccessible()

    await userEvent.click(within(menu).getByTestId('account-menu-security'))
    expect(window.location.pathname).toBe('/account/security')
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign-in and security' })).toBeInTheDocument()
    expect(screen.getByTestId('account-email')).toHaveTextContent('mei@example.com')
    // The bottom navigation still marks the account.
    expect(screen.getByTestId('nav-account')).toHaveAttribute('aria-current', 'page')
    await userEvent.click(screen.getByTestId('account-back'))
    expect(window.location.pathname).toBe('/account')
    expect(await screen.findByTestId('account-menu-profile')).toBeInTheDocument()
  })

  it('keeps the old link to the details, and sends a part there is not to the menu', async () => {
    server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account?section=details')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/account/details'))
    expect(await screen.findByTestId('account-details-page')).toBeInTheDocument()
    cleanup()
    window.history.replaceState(null, '', '/account/nothing')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/account'))
    expect(await screen.findByTestId('account-menu-profile')).toBeInTheDocument()
  })

  it('edits the profile on the account page with the revision it read', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: 'Kyoto',
      preferredCurrency: 'EUR', revision: 3 } })
    window.history.replaceState(null, '', '/account/profile')
    render(<App />)
    expect(await screen.findByTestId('profile-name')).toHaveValue('Mei')
    expect(screen.getByTestId('profile-city')).toHaveValue('Kyoto')
    expect(screen.getByTestId('profile-currency')).toHaveTextContent('Euro')
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
    window.history.replaceState(null, '', '/account/security')
    render(<App />)
    expect(await screen.findByTestId('linked-identity')).toHaveTextContent('mei@gmail.com')
    expect(screen.getByTestId('account-email')).toHaveTextContent('mei@example.com')
  })

  it('turns the app\'s notifications on and off on the account page, and says which mail always goes', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account/mail')
    render(<App />)
    const section = await screen.findByTestId('account-mail')
    expect(await within(section).findByTestId('mail-preference')).toHaveTextContent('Welcome after registration')
    expect(section).toHaveTextContent('Mail about your transfers and your payout account is always sent.')
    await userEvent.click(within(section).getByTestId('mail-preference-qb.welcome'))
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/auth/mail/preferences',
      body: { template: 'qb.welcome', subscribed: false } }))
  })

  it('turns the sound effects off and on from the app\'s settings, saved to the account (Q57)', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 }, soundOff: true })
    window.history.replaceState(null, '', '/account/preferences')
    render(<App />)
    const sound = await screen.findByTestId('account-sound')
    expect(sound).toHaveTextContent('Sound effects')
    const toggle = within(sound).getByRole('switch', { name: 'Play sound effects' })
    // Off on this account: the device follows it.
    await waitFor(() => expect(toggle).toHaveAttribute('aria-checked', 'false'))
    expect(soundEnabled()).toBe(false)
    await userEvent.click(toggle)
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/processes/QB_SETTINGS_SAVE/latest',
      body: { soundOn: true, contentLanguages: null } }))
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(soundEnabled()).toBe(true)
    await expectAccessible()
  })

  it('shows the quizzes of the app\'s language until the taker switches others on (Q59a)', async () => {
    const profile = { displayName: 'Mei', countryCode: 'JP', city: null, preferredCurrency: 'JPY', revision: 1 }
    const { posted } = server({ profile })
    render(<App />)
    const feedLanguages = () => posted.filter((p) => p.path === '/api/queries/qb.app.feed')
      .map((p) => (p.body as { params: { languages: string } }).params.languages)
    await waitFor(() => expect(feedLanguages()).toContain('en'))
    cleanup()

    window.history.replaceState(null, '', '/account/preferences')
    render(<App />)
    const section = await screen.findByTestId('account-quiz-languages')
    const english = within(section).getByRole('checkbox', { name: 'English' })
    await waitFor(() => expect(english).toBeChecked())
    // The last one on stays on; the app's language is followed until the taker chooses.
    expect(english).toBeDisabled()
    expect(within(section).getByTestId('quiz-languages-following')).toHaveTextContent('English')
    await userEvent.click(within(section).getByRole('checkbox', { name: '日本語' }))
    await waitFor(() => expect(posted).toContainEqual({ path: '/api/processes/QB_SETTINGS_SAVE/latest',
      body: { soundOn: null, contentLanguages: ['ja', 'en'] } }))
    expect(within(section).getByRole('checkbox', { name: '日本語' })).toBeChecked()
    expect(english).toBeEnabled()
    expect(within(section).queryByTestId('quiz-languages-following')).toBeNull()
    await expectAccessible()
  })

  it('asks the feed for the languages switched on (Q59a)', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 }, contentLanguages: 'zh,en' })
    render(<App />)
    await waitFor(() => expect(posted.filter((p) => p.path === '/api/queries/qb.app.feed')
      .map((p) => (p.body as { params: { languages: string } }).params.languages)).toContain('zh,en'))
    // Never the app's language alone first, while the settings are read.
    expect(posted.filter((p) => p.path === '/api/queries/qb.app.feed')
      .map((p) => (p.body as { params: { languages: string } }).params.languages)).not.toContain('en')
  })

  it('asks before sending mail in the language the interface was switched to', async () => {
    const { posted } = server({ profile: { displayName: 'Mei', countryCode: 'JP', city: null,
      preferredCurrency: 'JPY', revision: 1 } })
    window.history.replaceState(null, '', '/account/preferences')
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
