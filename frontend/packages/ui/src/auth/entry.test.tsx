import {
  ApiError, configureClient, forgetEnvironment, i18n, keepInstallDismissals, keepSignInStep, pendingSignInStep,
  readInstallDismissals, session, setEnvironment,
} from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { AppearanceProvider } from '../components/appearance'
import {
  EmailLinkForm,
  EntryLayout,
  EntryLayoutProvider,
  EntryLink,
  MailLinkPage,
  OidcButtons,
  OidcCallback,
  PasswordResetRequest,
  PwaInstallBanner,
  SignInForm,
  SignUpForm,
  signInOrder,
  type SignInMethod,
} from './index'

/** Phase 17zk: the entry pages' template, and what the blocks do in it. */

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  })
}

/** 429 RATE_LIMITED with Retry-After, as the server answers too many attempts. */
function tooMany(seconds: number) {
  return json(429, { status: 429, violations: [{ field: null, ruleCode: 'RATE_LIMITED', message: `Try again in ${seconds} seconds.` }] },
    { 'Retry-After': String(seconds) })
}

let offers: Record<string, unknown> = {}

/** Answers the entry's offers and the providers; posts as given by path. */
function server(answers: Record<string, () => Response> = {}, providers: unknown[] = []) {
  const posts: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return json(200, { name: path.split('/').pop(), appPath: '/portal/', ...offers })
    }
    if (request.method === 'GET' && path === '/api/auth/oidc/providers') return json(200, providers)
    const text = await request.text()
    posts.push({ path, body: text ? JSON.parse(text) : undefined })
    return answers[path]?.() ?? json(500, {})
  }))
  return posts
}

const user = () => userEvent.setup()
let entryNo = 0

/** A fresh entry per test: what an entry offers is read once per page and name. */
function freshEntry(given: Record<string, unknown> = {}) {
  entryNo += 1
  offers = given
  configureClient({ entry: `entry${entryNo}` })
}

function setWidth(width: number) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
}

beforeEach(async () => {
  freshEntry()
  setWidth(1280)
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  window.history.replaceState(null, '', '/')
  window.sessionStorage.clear()
  window.localStorage.clear()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('EntryLayout', () => {
  it('puts the brand and the language on top, the intro beside the form, the links, appearance and build below', async () => {
    render(
      <AppearanceProvider>
        <EntryLayout
          brand="Quizbuks"
          title="Sign in"
          testId="card"
          intro={{ sentence: 'Answer, learn, get rewarded', points: ['Scan', 'Answer', 'Collect'],
            illustration: <img src="data:," alt="Three people answering a quiz" /> }}
          links={<><EntryLink href="/help">Help</EntryLink><EntryLink href="/terms">Terms</EntryLink></>}
          footer="Build 1234"
          below={<span>Already answering quizzes?</span>}
          otherEntry={{ text: 'Publishing quizzes?', href: '/sponsor/' }}
          showOtherEntry
        >
          <p>the form</p>
        </EntryLayout>
      </AppearanceProvider>,
    )
    const top = screen.getByRole('banner')
    expect(within(top).getByRole('heading', { level: 1 })).toHaveTextContent('Quizbuks')
    // The language at the top right (suggestion ⑤), no longer under the card; the appearance stays in the footer.
    expect(within(top).getByRole('combobox', { name: 'Language' })).toBeInTheDocument()
    const footer = screen.getByRole('contentinfo')
    expect(within(footer).getByTestId('appearance-switch')).toBeInTheDocument()
    expect(within(footer).getByRole('navigation', { name: 'Help and legal' })).toHaveTextContent('HelpTerms')
    expect(within(footer).getByTestId('auth-footer')).toHaveTextContent('Build 1234')
    // The card: its title, the form, the hint under it; the way to the other side under the card.
    const card = screen.getByTestId('card')
    expect(within(card).getByRole('heading', { level: 2 })).toHaveTextContent('Sign in')
    expect(within(card).getByText('Already answering quizzes?')).toBeInTheDocument()
    expect(screen.getByTestId('entry-other')).toHaveTextContent('Publishing quizzes? Go there')
    expect(within(screen.getByTestId('entry-other')).getByRole('link')).toHaveAttribute('href', '/sponsor/')
    // Two columns on a wide screen; on a phone one line of the intro and the form (Tailwind's lg breakpoint).
    const layout = document.querySelector('[data-slot="entry-layout"]')!
    expect(layout).toHaveAttribute('data-layout', 'columns')
    expect(document.querySelector('[data-slot="entry-main"]')!.className).toContain('lg:grid-cols-2')
    const sentence = document.querySelector('[data-slot="entry-intro-sentence"]')!
    expect(sentence).toHaveTextContent('Answer, learn, get rewarded')
    expect(sentence.className).toContain('truncate')
    expect(sentence.className).toContain('lg:whitespace-normal')
    expect(document.querySelector('[data-slot="entry-intro-points"]')!.className).toMatch(/\bhidden\b.*\blg:flex\b/)
    expect(document.querySelector('[data-slot="entry-intro-illustration"]')!.className).toMatch(/\bhidden\b.*\blg:block\b/)
    // The intro comes before the form in the document: on a phone its line sits above the form.
    expect(sentence.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await expectAccessible()
  })

  it('stands alone in the middle without an intro, and shows the other side only where asked', async () => {
    render(
      <EntryLayout brand="Admin" title="Reset" otherEntry={{ text: 'Other', href: '/x' }} appearance={false}>
        <p>form</p>
      </EntryLayout>,
    )
    expect(document.querySelector('[data-slot="entry-layout"]')).toHaveAttribute('data-layout', 'single')
    expect(document.querySelector('[data-slot="entry-intro"]')).toBeNull()
    expect(screen.queryByTestId('entry-other')).toBeNull()
    // Nothing for the footer: no appearance (the admin), no links, no build.
    expect(screen.queryByRole('contentinfo')).toBeNull()
    await expectAccessible()
  })

  it('takes what the pages share from the provider; a page’s own props win', async () => {
    render(
      <EntryLayoutProvider brand="Shared brand" otherEntry={{ text: 'Sponsor?', href: '/sponsor/', link: 'This way' }}>
        <EntryLayout title="One" showOtherEntry><p>a</p></EntryLayout>
      </EntryLayoutProvider>,
    )
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Shared brand')
    expect(screen.getByRole('link', { name: 'This way' })).toHaveAttribute('href', '/sponsor/')
    await expectAccessible()
  })

  it('keeps the classes on <main>, lets a long name wrap, and puts the banner and notices in the banner landmark',
    async () => {
      setEnvironment('staging')
      try {
        render(<EntryLayout brand="A very long application name that does not fit on one line" title="T" className="pt-20"
          notices={[{ key: 'n', content: <p>A notice</p> }]}><p>a</p></EntryLayout>)
        expect(screen.getByRole('main')).toHaveClass('pt-20')
        expect(document.querySelector('[data-slot="entry-brand"]')!.className).not.toContain('truncate')
        const banner = screen.getByRole('banner')
        expect(within(banner).getByTestId('environment-banner')).toBeInTheDocument()
        expect(within(banner).getByText('A notice')).toBeInTheDocument()
        await expectAccessible()
      } finally {
        forgetEnvironment()
      }
    })

  it('leaves no strip for notices while none shows anything', () => {
    const { container, rerender } = render(<EntryLayout title="T" notices={[{ key: 'off', content: null, active: false }]}>
      <p>a</p></EntryLayout>)
    const area = () => container.querySelector('[data-slot="entry-notices"] > div')!
    expect(area().className).not.toContain('pt-3')
    rerender(<EntryLayout title="T" notices={[{ key: 'on', content: <p>Now</p> }]}><p>a</p></EntryLayout>)
    expect(area().className).toContain('pt-3')
  })

  it('names a page without a brand by its title', () => {
    render(<EntryLayout title="Unsubscribe"><p>a</p></EntryLayout>)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Unsubscribe')
  })

  it('shows every notice on a wide screen; on a phone one in full and the others as one line', async () => {
    const notices = [
      { key: 'offline', content: <p>Offline content</p>, summary: 'Offline', active: false },
      { key: 'update', content: <p>A new version</p>, summary: 'Update' },
      { key: 'in-app', content: <p>Open this in your browser</p>, summary: 'Open in browser' },
    ]
    const { unmount } = render(<EntryLayout title="T" notices={notices}><p>a</p></EntryLayout>)
    expect(document.querySelectorAll('[data-slot="entry-notice"][data-collapsed]')).toHaveLength(0)
    unmount()

    setWidth(375)
    render(<EntryLayout title="T" notices={notices}><p>a</p></EntryLayout>)
    const collapsed = [...document.querySelectorAll('details[data-slot="entry-notice"]')]
    // The first active one (the update) is in full, the later active one collapses to its line; the inactive one
    // shows no line of its own (it shows nothing now).
    expect(collapsed.map((d) => d.querySelector('summary')!.textContent)).toEqual(['Open in browser'])
    expect(screen.getByText('Offline content').closest('details')).toBeNull()
    expect(screen.getByText('A new version').closest('details')).toBeNull()
    await expectAccessible()
  })
})

describe('SignInForm in the template', () => {
  const signIn = vi.fn()
  const verify = vi.fn()
  beforeEach(() => {
    signIn.mockReset()
    verify.mockReset()
  })

  it('shows signing in and signing up as two tabs that keep the query (suggestion ④)', async () => {
    freshEntry({ selfRegistration: true })
    server()
    window.history.replaceState(null, '', '/login?next=%2Fp%2F42&ref=abc')
    const onNavigate = vi.fn()
    render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={[]} signInPath="/login"
      signUpPath="/signup" tabs onNavigate={onNavigate} />)
    const tabs = await screen.findByRole('navigation', { name: 'Sign in or sign up' })
    const signUp = within(tabs).getByRole('link', { name: 'Sign up' })
    expect(signUp).toHaveAttribute('href', '/signup?next=%2Fp%2F42&ref=abc')
    expect(within(tabs).getByRole('link', { name: 'Sign in' })).toHaveAttribute('aria-current', 'page')
    // The tab stands for the link of before.
    expect(screen.queryByRole('link', { name: 'Create an account' })).toBeNull()
    await expectAccessible()
    await user().click(signUp)
    expect(onNavigate).toHaveBeenCalledWith('/signup?next=%2Fp%2F42&ref=abc')
  })

  it('shows no tabs where the entry has no self-registration (the admin)', async () => {
    freshEntry({ selfRegistration: false, passkeys: true })
    server()
    render(<SignInForm brand="Admin" returnTo="/" auth={{ signIn, verify }} providers={[]} signUpPath="/signup" tabs />)
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByRole('navigation', { name: 'Sign in or sign up' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Create an account' })).toBeNull()
  })

  it('orders the ways of signing in per language: the mailed link first in Chinese, providers first in Japanese and English (gap 1)',
    async () => {
      freshEntry({ emailLink: true })
      server()
      const providers = [{ id: 'google', label: 'Google', kind: 'google' }]
      const order = (language: string): SignInMethod[] => language.startsWith('zh')
        ? ['emailLink', 'providers', 'passkey', 'password'] : ['providers', 'emailLinkButton', 'passkey', 'password']
      const view = () => render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={providers}
        emailLink userNameLabel="email" order={order} oidcLayout="icons" />)

      await act(() => i18n.changeLanguage('zh'))
      const zh = view()
      const form = await screen.findByTestId('email-link-form')
      const icons = screen.getByTestId('oidc-icons')
      const or = screen.getByTestId('login-or-password')
      expect(form.compareDocumentPosition(icons) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(icons.compareDocumentPosition(or) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(icons).toHaveTextContent('其他方式登录')
      await expectAccessible()
      zh.unmount()

      await act(() => i18n.changeLanguage('en'))
      view()
      const first = await screen.findByTestId('oidc-icons')
      // First on the page: no separator over the providers, the row of icons named as a group.
      expect(first).not.toHaveTextContent('Other ways to sign in')
      expect(screen.getByRole('group', { name: 'Other ways to sign in' })).toBe(first)
      // The mailed link is there in English too, behind a button among the other ways.
      expect(screen.queryByTestId('email-link-form')).toBeNull()
      const open = await screen.findByTestId('login-email-link-open')
      expect(first.compareDocumentPosition(open) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      await user().click(open)
      expect(screen.getByTestId('email-link-form')).toBeInTheDocument()
      expect(screen.getByTestId('login-or-password')).toBeInTheDocument()
      await expectAccessible()
    })

  it('puts the passkey first, then the providers, then the password (the sponsor)', async () => {
    freshEntry({ passkeys: true })
    vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {})
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: {} })
    try {
      server()
      render(<SignInForm brand="Sponsor" returnTo="/" auth={{ signIn, verify, signInWithPasskey: vi.fn() }} passkey
        providers={[{ id: 'line', label: 'LINE', kind: 'line' }]} order={['passkey', 'providers', 'password']} />)
      const passkey = await screen.findByTestId('login-passkey')
      const provider = screen.getByTestId('oidc-line')
      const name = screen.getByTestId('login-user-name')
      expect(passkey.compareDocumentPosition(provider) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(provider.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(screen.getByTestId('login-or-password')).toBeInTheDocument()
      await expectAccessible()
    } finally {
      Reflect.deleteProperty(navigator, 'credentials')
    }
  })

  it('never drops a way: what the order leaves out follows in the order of before', () => {
    expect(signInOrder(undefined)).toEqual(['emailLink', 'passkey', 'password', 'providers'])
    expect(signInOrder(['providers'])).toEqual(['providers', 'emailLink', 'passkey', 'password'])
    expect(signInOrder(['emailLinkButton', 'password'])).toEqual(['emailLinkButton', 'password', 'passkey', 'providers'])
    expect(signInOrder(['passkey', 'passkey', 'emailLink', 'emailLinkButton'])).toEqual(
      ['passkey', 'emailLink', 'password', 'providers'])
  })

  it('offers the providers as icons on a phone and as buttons on a wide screen', () => {
    const providers = [{ id: 'google', label: 'Google', kind: 'google' }]
    setWidth(375)
    const phone = render(<OidcButtons returnTo="/" providers={providers} layout="responsive" />)
    expect(screen.getByTestId('oidc-icons')).toBeInTheDocument()
    phone.unmount()
    setWidth(1280)
    render(<OidcButtons returnTo="/" providers={providers} layout="responsive" />)
    expect(screen.getByTestId('oidc-buttons')).toBeInTheDocument()
    expect(screen.queryByTestId('oidc-icons')).toBeNull()
  })

  it('names the full-width buttons as a group when they come first, without a separator', async () => {
    render(<OidcButtons returnTo="/" providers={[{ id: 'google', label: 'Google', kind: 'google' }]} bare />)
    expect(screen.getByRole('group', { name: 'Other ways to sign in' })).toBe(screen.getByTestId('oidc-buttons'))
    expect(screen.queryByText('or sign in with')).toBeNull()
    await expectAccessible()
  })

  it('holds the button and counts down after too many attempts (429 with Retry-After, gap 5)', async () => {
    server()
    signIn.mockRejectedValue(new ApiError(429, { status: 429, violations: [{ field: null, ruleCode: 'RATE_LIMITED',
      message: 'Too many sign-in requests; try again in 2 seconds.' }] }, 2))
    render(<SignInForm brand="Portal" returnTo="/" auth={{ signIn, verify }} providers={[]} />)
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'amy')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    expect(await screen.findByTestId('login-error')).toHaveTextContent('try again in 2 seconds')
    const held = screen.getByRole('button', { name: /Try again in [12] s/ })
    expect(held).toBeDisabled()
    await expectAccessible()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled(), { timeout: 4000 })
    // The text of the wait goes with it.
    expect(screen.queryByTestId('login-error')).toBeNull()
  })

  it('continues the second step kept by the provider’s return, with its page to return to, after a reload (gap 2)',
    async () => {
      server()
      keepSignInStep({ status: 'MFA_REQUIRED', challenge: 'ch-oidc' }, '/p/42?ref=abc')
      // A reload renders the page anew: the step is still kept in this tab.
      render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={[]} resumeStep />).unmount()
      verify.mockResolvedValue(undefined)
      const onSignedIn = vi.fn()
      render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={[]} onSignedIn={onSignedIn}
        resumeStep />)
      const code = await screen.findByRole('textbox', { name: 'Code' })
      await expectAccessible()
      await user().type(code, '123456{Enter}')
      await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/p/42?ref=abc'))
      expect(verify).toHaveBeenCalledWith('ch-oidc', '123456')
      expect(pendingSignInStep()).toBeNull()
    })

  it('forgets the kept step when the person goes back to the password', async () => {
    server()
    keepSignInStep({ status: 'MFA_REQUIRED', challenge: 'ch' }, '/p/1')
    render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={[]} resumeStep />)
    await user().click(await screen.findByRole('button', { name: 'Back to sign in' }))
    expect(screen.getByTestId('login-user-name')).toBeInTheDocument()
    expect(pendingSignInStep()).toBeNull()
  })

  it('resumes only where the frontend opts in, and never after an idle lock', async () => {
    server()
    keepSignInStep({ status: 'MFA_REQUIRED', challenge: 'ch' }, '/p/1')
    // Not opted in (the admin, an application that did not): the password, as before.
    const plain = render(<SignInForm brand="Admin" returnTo="/" auth={{ signIn, verify }} providers={[]} />)
    expect(screen.getByTestId('login-user-name')).toBeInTheDocument()
    plain.unmount()
    const idle = render(<SignInForm brand="Admin" returnTo="/" auth={{ signIn, verify }} providers={[]} resumeStep
      notice="idle" />)
    expect(screen.getByTestId('idle-locked')).toBeInTheDocument()
    expect(screen.getByTestId('login-user-name')).toBeInTheDocument()
    idle.unmount()
    render(<SignInForm brand="Admin" returnTo="/" auth={{ signIn, verify }} providers={[]} resumeStep
      initialUserName="amy" />)
    expect(screen.getByTestId('login-user-name')).toHaveValue('amy')
    // Still kept for a page that does resume it.
    expect(pendingSignInStep()).not.toBeNull()
  })

  it('drops a resumed challenge the server no longer takes and starts over at the password', async () => {
    server()
    keepSignInStep({ status: 'MFA_REQUIRED', challenge: 'expired' }, '/p/1')
    verify.mockRejectedValue(new ApiError(401, { violations: [{ field: null, ruleCode: 'LOGIN_FAILED',
      message: 'Sign-in failed.' }] }))
    render(<SignInForm brand="Quizbuks" returnTo="/" auth={{ signIn, verify }} providers={[]} resumeStep />)
    await user().type(await screen.findByRole('textbox', { name: 'Code' }), '123456{Enter}')
    expect(await screen.findByTestId('login-user-name')).toBeInTheDocument()
    expect(screen.getByTestId('login-error')).toHaveTextContent('Sign-in failed.')
    expect(pendingSignInStep()).toBeNull()
    await expectAccessible()
  })

  it('says the general failure, never "HTTP 429", when too many requests come without words', async () => {
    server()
    signIn.mockRejectedValue(new ApiError(429, {}, 30))
    render(<SignInForm brand="Portal" returnTo="/" auth={{ signIn, verify }} providers={[]} />)
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'amy')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    expect(await screen.findByTestId('login-error')).toHaveTextContent('Something went wrong')
    expect(screen.queryByText(/HTTP 429/)).toBeNull()
    expect(screen.getByRole('button', { name: /Try again in (30|29) s/ })).toBeDisabled()
  })
})

describe('the other blocks in the template', () => {
  it('signing up: no tabs where the entry has no self-registration, the sign-in link instead', async () => {
    freshEntry({ selfRegistration: false })
    server()
    render(<SignUpForm brand="Sponsor" signInPath="/login" signUpPath="/signup" tabs />)
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByRole('navigation', { name: 'Sign in or sign up' })).toBeNull()
    expect(screen.getByTestId('sign-in-link')).toHaveAttribute('href', '/login')
  })

  it('signing up: tabs back to signing in with the query (?src=), and the wait after 429', async () => {
    freshEntry({ selfRegistration: true })
    server({ '/api/auth/register': () => tooMany(2) })
    window.history.replaceState(null, '', '/signup?src=trial-1')
    render(<SignUpForm brand="Sponsor" signInPath="/login" signUpPath="/signup" tabs />)
    const tabs = await screen.findByRole('navigation', { name: 'Sign in or sign up' })
    expect(within(tabs).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login?src=trial-1')
    expect(within(tabs).getByRole('link', { name: 'Sign up' })).toHaveAttribute('aria-current', 'page')
    expect(screen.queryByText('Already have an account? Sign in')).toBeNull()
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'ann@example.com')
    await user().type(screen.getByLabelText('New password'), 'correct horse')
    await user().type(screen.getByLabelText('Repeat the new password'), 'correct horse')
    await user().click(screen.getByRole('button', { name: 'Create the account' }))
    expect(await screen.findByTestId('sign-up-error')).toHaveTextContent('Try again in 2 seconds.')
    expect(screen.getByRole('button', { name: /Try again in [12] s/ })).toBeDisabled()
    await expectAccessible()
  })

  it('signing up says the general failure when too many requests come without words', async () => {
    server({ '/api/auth/register': () => new Response(null, { status: 429, headers: { 'Retry-After': '30' } }) })
    render(<SignUpForm brand="Portal" />)
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'ann@example.com')
    await user().type(screen.getByLabelText('New password'), 'correct horse')
    await user().type(screen.getByLabelText('Repeat the new password'), 'correct horse')
    await user().click(screen.getByRole('button', { name: 'Create the account' }))
    expect(await screen.findByTestId('sign-up-error')).toHaveTextContent('Something went wrong. Please try again later.')
    expect(screen.queryByText(/HTTP 429/)).toBeNull()
  })

  it('the mailed link drops the text of the wait when it ends', async () => {
    server({ '/api/auth/email-link': () => tooMany(1) })
    render(<EmailLinkForm />)
    await user().type(screen.getByTestId('email-link-email'), 'ann@example.com{Enter}')
    expect(await screen.findByText('Try again in 1 seconds.')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Try again in 1 seconds.')).toBeNull(), { timeout: 4000 })
    expect(screen.getByRole('button', { name: 'Send me a link' })).toBeEnabled()
  })

  it('the password reset says the general failure, never "HTTP 429"', async () => {
    server({ '/api/auth/password-reset/request': () => new Response(null, { status: 429, headers: { 'Retry-After': '9' } }) })
    render(<PasswordResetRequest brand="Portal" signInPath="/login" />)
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'ann@example.com{Enter}')
    expect(await screen.findByText('Something went wrong. Please try again later.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Try again in [89] s/ })).toBeDisabled()
  })

  it('the mailed link and the password reset wait after 429 too', async () => {
    server({ '/api/auth/email-link': () => tooMany(30), '/api/auth/password-reset/request': () => tooMany(30) })
    const { unmount } = render(<EmailLinkForm />)
    await user().type(screen.getByTestId('email-link-email'), 'ann@example.com{Enter}')
    expect(await screen.findByText('Try again in 30 seconds.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Try again in (30|29) s/ })).toBeDisabled()
    unmount()
    render(<PasswordResetRequest brand="Portal" signInPath="/login" />)
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'ann@example.com{Enter}')
    expect(await screen.findByText('Try again in 30 seconds.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Try again in (30|29) s/ })).toBeDisabled()
    await expectAccessible()
  })

  it('the provider buttons wait after a start refused with 429', async () => {
    const start = vi.fn(async () => {
      throw new ApiError(429, { violations: [{ field: null, ruleCode: 'RATE_LIMITED', message: 'Slow down.' }] }, 30)
    })
    const onError = vi.fn()
    render(<OidcButtons returnTo="/" providers={[{ id: 'google', label: 'Google', kind: 'google' }]} start={start}
      onError={onError} />)
    await user().click(screen.getByTestId('oidc-google'))
    await waitFor(() => expect(screen.getByTestId('oidc-google')).toBeDisabled())
    expect(screen.getByTestId('oidc-wait')).toHaveTextContent(/Try again in (30|29) s/)
    expect(onError).toHaveBeenLastCalledWith(expect.any(ApiError))
  })

  it('the identity provider’s return is an entry page: brand, language, and the step kept for the sign-in page (gaps 2, 4)',
    async () => {
      const onSecondStep = vi.fn()
      render(
        <EntryLayoutProvider brand="Quizbuks">
          <OidcCallback search="?state=s&code=c" fallbackPath="/" onSignedIn={vi.fn()} onBack={vi.fn()}
            onSecondStep={onSecondStep} takeBinder={() => 'b'} takeReturnPath={() => '/p/7'} pendingPurpose={() => 'SIGN_IN'}
            auth={{ signInWithProvider: async () => ({ status: 'MFA_REQUIRED', challenge: 'ch7' }) }} />
        </EntryLayoutProvider>,
      )
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Quizbuks')
      expect(screen.getByRole('combobox', { name: 'Language' })).toBeInTheDocument()
      await waitFor(() => expect(onSecondStep).toHaveBeenCalledWith({ status: 'MFA_REQUIRED', challenge: 'ch7' }, '/p/7'))
      expect(pendingSignInStep()).toEqual({ step: { status: 'MFA_REQUIRED', challenge: 'ch7' }, returnTo: '/p/7' })
    })

  it('the identity provider’s refusal is said inside the template', async () => {
    render(<OidcCallback brand="Quizbuks" search="?error=access_denied" fallbackPath="/" onSignedIn={vi.fn()}
      onBack={vi.fn()} onSecondStep={vi.fn()} pendingPurpose={() => 'SIGN_IN'} />)
    expect(screen.getByTestId('oidc-error')).toHaveTextContent('access_denied')
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Signing in through the identity provider did not work.')
    // The warning as before (PageState), its title the card's only heading.
    expect(document.querySelector('[data-slot="page-state"]')).toHaveAttribute('data-state', 'warning')
    expect(screen.getAllByRole('heading', { level: 2 })).toHaveLength(1)
    expect(screen.getByRole('button', { name: 'Back to sign in' })).toBeInTheDocument()
    await expectAccessible()
  })

  it('the return of linking an account says it is loading, not "Sign in"', () => {
    render(<OidcCallback brand="Quizbuks" search="?state=s&code=c" fallbackPath="/" onSignedIn={vi.fn()} onBack={vi.fn()}
      onSecondStep={vi.fn()} pendingPurpose={() => 'LINK'} takeBinder={() => 'b'} takeReturnPath={() => '/account'}
      finishLink={() => new Promise(() => {})} />)
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Loading…')
    expect(screen.queryByRole('heading', { name: 'Sign in' })).toBeNull()
  })

  it('the passkey confirmation offers to sign in when signed out (gap 10)', async () => {
    vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {})
    Object.defineProperty(navigator, 'credentials', { configurable: true, value: {} })
    try {
      server()
      window.history.replaceState(null, '', '/portal/auth/passkey-confirm?entry=portal&token=k1')
      const onNavigate = vi.fn()
      render(<MailLinkPage brand="Portal" signInPath="/login" onNavigate={onNavigate} hrefOf={(p) => `/portal${p}`} />)
      expect(await screen.findByTestId('passkey-confirm-state')).toHaveTextContent('Sign in on this device first')
      const link = screen.getByTestId('passkey-confirm-sign-in')
      expect(link).toHaveAttribute('href', '/portal/login')
      await expectAccessible()
      await user().click(link)
      expect(onNavigate).toHaveBeenCalledWith('/login')
    } finally {
      Reflect.deleteProperty(navigator, 'credentials')
    }
  })

  it('counts the closes of the note inside another app’s browser apart from the offer to install (gap 11)', async () => {
    const noop = async () => 'unavailable' as const
    const { unmount } = render(<PwaInstallBanner appName="Quizbuks" rememberAs="qb"
      prompt={{ situation: 'in-app', browser: 'wechat', install: noop }} />)
    await expectAccessible()
    await user().click(screen.getByTestId('pwa-install-dismiss'))
    expect(readInstallDismissals('qb.in-app')?.count).toBe(1)
    expect(readInstallDismissals('qb')).toBeNull()
    unmount()
    // Once in the browser, the offer to install is still there.
    render(<PwaInstallBanner appName="Quizbuks" rememberAs="qb" prompt={{ situation: 'prompt', browser: 'other', install: noop }} />)
    expect(screen.getByTestId('pwa-install')).toHaveAttribute('data-situation', 'prompt')
  })

  it('carries the closes counted together before over to the note, so a note closed for good stays closed', () => {
    const noop = async () => 'unavailable' as const
    keepInstallDismissals('old', { at: 1000, count: 3 })
    render(<PwaInstallBanner appName="Quizbuks" rememberAs="old" now={() => 2000}
      prompt={{ situation: 'in-app', browser: 'wechat', install: noop }} />)
    expect(screen.queryByTestId('pwa-install')).toBeNull()
    expect(readInstallDismissals('old.in-app')).toEqual({ at: 1000, count: 3 })
    expect(readInstallDismissals('old')).toEqual({ at: 1000, count: 3 })
  })
})
