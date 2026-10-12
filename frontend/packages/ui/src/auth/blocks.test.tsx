import { addMessages, ApiError, configureClient, i18n, runProcess, sessionFetch, session } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider } from '../components/appearance'
import { expectAccessible } from '../test/axe'
import {
  AUTH_NAMESPACE,
  AuthFrame,
  ChangePasswordForm,
  MailLinkPage,
  OfflineBanner,
  OidcCallback,
  SignInForm,
  SignUpForm,
  StepUpProvider,
  VerifyEmailBanner,
} from './index'

vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

type Answer = { status: number; body?: unknown }

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function problem(status: number, ruleCode: string, field?: string): Answer {
  return { status, body: { status, violations: [{ field: field ?? null, ruleCode, message: `${ruleCode} message` }] } }
}

/** Answers posts as given, by path; the entry offers self-registration. Returns the posts made. */
function server(answers: Record<string, Answer | Answer[]> = {}) {
  const posts: { path: string; body: unknown; headers: Headers }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return json(200, { name: path.split('/').pop(), selfRegistration: true, appPath: '/portal/' })
    }
    if (request.method === 'GET' && path === '/api/auth/oidc/providers') return json(200, [])
    const text = await request.text()
    posts.push({ path, body: text ? JSON.parse(text) : undefined, headers: request.headers })
    const planned = answers[path]
    const answer = Array.isArray(planned) ? planned.shift() : planned
    if (!answer) return json(500, {})
    return answer.body === undefined
      ? new Response(null, { status: answer.status, headers: { 'Content-Length': '0' } })
      : json(answer.status, answer.body)
  }))
  return posts
}

const user = () => userEvent.setup()

beforeEach(async () => {
  configureClient({ entry: 'portal' })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
})

describe('AuthFrame', () => {
  it('names the application in the heading and offers language and appearance', async () => {
    render(
      <AppearanceProvider>
        <AuthFrame brand="Quizbuks" title="Sign in">
          <p>content</p>
        </AuthFrame>
      </AppearanceProvider>,
    )
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Quizbuks')
    expect(screen.getByRole('combobox', { name: 'Language' })).toBeInTheDocument()
    expect(screen.getByTestId('appearance-switch')).toBeInTheDocument()
    await expectAccessible()
  })

  it('lets an application change single texts', async () => {
    addMessages(AUTH_NAMESPACE, { en: { common: { language: 'Sprache' } } })
    render(<AuthFrame brand="x" title="y">z</AuthFrame>)
    expect(screen.getByRole('combobox', { name: 'Sprache' })).toBeInTheDocument()
    addMessages(AUTH_NAMESPACE, { en: { common: { language: 'Language' } } })
  })

  it('speaks every platform language', async () => {
    await act(() => i18n.changeLanguage('ja'))
    render(<AuthFrame brand="x" title="y">z</AuthFrame>)
    expect(screen.getByRole('combobox', { name: '言語' })).toBeInTheDocument()
  })
})

describe('SignInForm', () => {
  const signIn = vi.fn()
  const verify = vi.fn()
  beforeEach(() => {
    signIn.mockReset()
    verify.mockReset()
  })

  function form(props: Partial<Parameters<typeof SignInForm>[0]> = {}) {
    return render(
      <SignInForm brand="Portal" returnTo="/portal/orders" auth={{ signIn, verify }} providers={[]} {...props} />,
    )
  }

  it('signs in from the keyboard and hands over the page to return to', async () => {
    server()
    signIn.mockResolvedValue({ status: 'SIGNED_IN' })
    const onSignedIn = vi.fn()
    form({ onSignedIn, passwordResetPath: '/portal/auth/password-reset', signUpPath: '/portal/sign-up' })
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'ann@example.com')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/orders'))
    expect(signIn).toHaveBeenCalledWith('ann@example.com', 'secret')
    // Plain links without a router: the browser follows them.
    expect(screen.getByRole('link', { name: 'Forgot your password?' })).toHaveAttribute('href', '/portal/auth/password-reset')
    expect(await screen.findByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/portal/sign-up')
    await expectAccessible()
  })

  it('goes through the navigation it is given', async () => {
    server()
    const onNavigate = vi.fn()
    form({ passwordResetPath: '/auth/password-reset', onNavigate, hrefOf: (path) => `/admin${path}` })
    const link = screen.getByRole('link', { name: 'Forgot your password?' })
    expect(link).toHaveAttribute('href', '/admin/auth/password-reset')
    await user().click(link)
    expect(onNavigate).toHaveBeenCalledWith('/auth/password-reset')
  })

  it('offers a new link when the entry requires a verified address', async () => {
    const posts = server({ '/api/auth/verify-email/resend': { status: 202 } })
    signIn.mockRejectedValue(new ApiError(403, problem(403, 'EMAIL_NOT_VERIFIED').body as Record<string, unknown>))
    form()
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'ann@example.com')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    expect(await screen.findByTestId('login-email-not-verified')).toBeInTheDocument()
    expect(screen.queryByTestId('login-error')).toBeNull()
    await expectAccessible()
    await user().click(screen.getByRole('button', { name: 'Send a new link' }))
    await waitFor(() => expect(screen.getByTestId('resend-state')).toHaveTextContent('If the address'))
    expect(posts).toEqual([
      expect.objectContaining({ path: '/api/auth/verify-email/resend', body: { email: 'ann@example.com', entry: 'portal' } }),
    ])
  })

  it("gives only a general text when the application's rules refuse", async () => {
    server()
    signIn.mockRejectedValue(new ApiError(403, problem(403, 'SIGN_IN_REFUSED').body as Record<string, unknown>))
    form()
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'amy')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    expect(await screen.findByTestId('login-error')).toHaveTextContent('You cannot sign in here at the moment.')
    expect(screen.queryByText('SIGN_IN_REFUSED message')).toBeNull()
  })

  it('asks for the second factor and verifies it', async () => {
    server()
    signIn.mockResolvedValue({ status: 'MFA_REQUIRED', challenge: 'ch' })
    verify.mockResolvedValue(undefined)
    const onSignedIn = vi.fn()
    form({ onSignedIn })
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'amy')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    const code = await screen.findByRole('textbox', { name: 'Code' })
    expect(code).toHaveFocus()
    await expectAccessible()
    await user().type(code, ' 123456 {Enter}')
    await waitFor(() => expect(verify).toHaveBeenCalledWith('ch', '123456'))
    expect(onSignedIn).toHaveBeenCalledWith('/portal/orders')
  })

  it('leads only to paths of this site after signing in, by password or provider', async () => {
    server()
    signIn.mockResolvedValue({ status: 'SIGNED_IN' })
    const onNavigate = vi.fn()
    const startProvider = vi.fn(async () => undefined)
    render(
      <SignInForm brand="Portal" returnTo={'/\t/evil.com'} auth={{ signIn, verify }} onNavigate={onNavigate}
        startProvider={startProvider} providers={[{ id: 'corp', label: 'Corp' }]} />,
    )
    await user().click(screen.getByTestId('oidc-corp'))
    expect(startProvider).toHaveBeenCalledWith('corp', '/')
    await user().type(screen.getByRole('textbox', { name: 'User name' }), 'amy')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    await waitFor(() => expect(onNavigate).toHaveBeenCalledWith('/'))
  })

  it('offers the identity providers it loads', async () => {
    server()
    vi.stubGlobal('fetch', vi.fn(async () => json(200, [{ id: 'corp', label: 'Corporate directory' }])))
    const startProvider = vi.fn(async () => undefined)
    render(<SignInForm brand="Portal" returnTo="/portal/" auth={{ signIn, verify }} startProvider={startProvider} />)
    await user().click(await screen.findByTestId('oidc-corp'))
    expect(startProvider).toHaveBeenCalledWith('corp', '/portal/')
  })
})

describe('SignUpForm', () => {
  it('registers at the entry and then says to look in the mailbox, with a new mail on request', async () => {
    const posts = server({ '/api/auth/register': { status: 202 }, '/api/auth/verify-email/resend': { status: 202 } })
    const onSubmitted = vi.fn()
    render(
      <SignUpForm
        brand="Portal"
        signInPath="/portal/login"
        onSubmitted={onSubmitted}
        extra={(f) => (
          <label className="flex gap-2 text-sm">
            <input type="checkbox" {...f.register('terms', { validate: (v) => v === true || 'Accept the terms.' })} />
            I accept the terms
          </label>
        )}
      />,
    )
    await expectAccessible()
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), ' ann@example.com ')
    await user().type(screen.getByRole('textbox', { name: 'Your name' }), 'Ann')
    await user().type(screen.getByLabelText('New password'), 'correct horse')
    await user().type(screen.getByLabelText('Repeat the new password'), 'correct horse')
    await user().click(screen.getByRole('button', { name: 'Create the account' }))
    // The application's own field stops it.
    expect(posts).toEqual([])
    await user().click(screen.getByRole('checkbox', { name: 'I accept the terms' }))
    await user().click(screen.getByRole('button', { name: 'Create the account' }))

    await waitFor(() => expect(screen.getByTestId('sign-up-state')).toHaveTextContent('ann@example.com'))
    expect(posts[0]).toEqual(expect.objectContaining({
      path: '/api/auth/register',
      body: { email: 'ann@example.com', password: 'correct horse', displayName: 'Ann', locale: 'en', entry: 'portal' },
    }))
    expect(onSubmitted).toHaveBeenCalledWith(expect.objectContaining({ email: 'ann@example.com', terms: true }))
    expect(onSubmitted.mock.calls[0][0]).not.toHaveProperty('password')
    await expectAccessible()

    await user().click(screen.getByRole('button', { name: 'Send a new link' }))
    await waitFor(() => expect(screen.getByTestId('resend-state')).toBeInTheDocument())
    expect(posts[1]).toEqual(expect.objectContaining({ body: { email: 'ann@example.com', entry: 'portal' } }))
  })

  it('checks the repeated password and shows the rules of the server under it', async () => {
    const posts = server({ '/api/auth/register': problem(400, 'PASSWORD_TOO_SHORT', 'password') })
    render(<SignUpForm brand="Portal" />)
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'ann@example.com')
    await user().type(screen.getByLabelText('New password'), 'short')
    await user().type(screen.getByLabelText('Repeat the new password'), 'other')
    await user().click(screen.getByRole('button', { name: 'Create the account' }))
    expect(await screen.findByText('The passwords do not match.')).toBeInTheDocument()
    expect(posts).toEqual([])
    await user().clear(screen.getByLabelText('Repeat the new password'))
    await user().type(screen.getByLabelText('Repeat the new password'), 'short')
    await user().click(screen.getByRole('button', { name: 'Create the account' }))
    expect(await screen.findByText('PASSWORD_TOO_SHORT message')).toBeInTheDocument()
    expect(screen.getByLabelText('New password')).toHaveAccessibleDescription('PASSWORD_TOO_SHORT message')
  })
})

describe('MailLinkPage', () => {
  it("finds its page in the address, without a router, and keeps the token out of the address bar", async () => {
    window.history.replaceState(null, '', '/portal/auth/verify-email?entry=portal&token=t1')
    const posts = server({
      '/api/auth/verify-email/check': { status: 200, body: { passwordRequired: false } },
      '/api/auth/verify-email': { status: 204 },
    })
    render(<MailLinkPage brand="Portal" />)
    expect(window.location.search).toBe('?entry=portal')
    expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe('no-referrer')
    await user().click(screen.getByTestId('verify-start'))
    await waitFor(() => expect(screen.getByTestId('verify-state')).toHaveTextContent('is verified'))
    expect(posts.map((p) => p.body)).toEqual([{ token: 't1' }, { token: 't1' }])
    await waitFor(() => expect(screen.getByTestId('auth-continue')).toHaveAttribute('href', '/portal/'))
    await expectAccessible()
  })

  it('asks for the link to reset a password without a token, leading back to the entry', async () => {
    window.history.replaceState(null, '', '/portal/auth/password-reset?entry=portal')
    const posts = server({ '/api/auth/password-reset/request': { status: 202 } })
    render(<MailLinkPage brand="Portal" />)
    await waitFor(() => expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/portal/'))
    await expectAccessible()
    await user().type(screen.getByRole('textbox', { name: 'E-mail address' }), 'bob@example.com{Enter}')
    await waitFor(() => expect(screen.getByTestId('reset-state')).toHaveTextContent('If the address'))
    expect(posts[0].body).toEqual({ email: 'bob@example.com', entry: 'portal' })
  })

  it('leads from a link that is no good to asking for a new one on the same page', async () => {
    window.history.replaceState(null, '', '/portal/auth/password-reset?entry=portal&token=old')
    server({ '/api/auth/password-reset/confirm': problem(422, 'TOKEN_INVALID') })
    render(<MailLinkPage brand="Portal" />)
    await user().type(screen.getByLabelText('New password'), 'brand new horse')
    await user().type(screen.getByLabelText('Repeat the new password'), 'brand new horse{Enter}')
    await waitFor(() => expect(screen.getByTestId('reset-state')).toHaveTextContent('invalid'))
    expect(screen.getByRole('link', { name: 'Send the link' })).toHaveAttribute('href', '/portal/auth/password-reset?entry=portal')
  })

  it('says so for a page it does not know', () => {
    window.history.replaceState(null, '', '/portal/auth/elsewhere?token=x')
    server()
    render(<MailLinkPage brand="Portal" />)
    expect(screen.getByTestId('mail-link-unknown')).toBeInTheDocument()
  })
})

describe('StepUpProvider', () => {
  it('repeats the request with the same idempotency key after the second factor', async () => {
    session.store('old', 'refresh')
    const keys: (string | null)[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname
      if (path === '/api/auth/step-up') return json(200, { accessToken: 'stepped' })
      keys.push(input.headers.get('Idempotency-Key'))
      return input.headers.get('Authorization') === 'Bearer stepped'
        ? json(200, { output: { done: true } })
        : json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
    }))
    render(<StepUpProvider securityPath="/portal/security"><div /></StepUpProvider>)
    const result = runProcess('ORDER_PLACE', { qty: 1 }, { idempotencyKey: 'k-1' })
    const dialog = await screen.findByRole('dialog', { name: 'Confirm with your authenticator' })
    await expectAccessible()
    await user().type(screen.getByTestId('step-up-code'), '123456{Enter}')
    expect(await result).toEqual({ done: true })
    expect(keys).toEqual(['k-1', 'k-1'])
    expect(dialog).not.toBeInTheDocument()
  })

  it('points users without two-step verification to where it is set up', async () => {
    session.store('old', 'refresh')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      return url.endsWith('/api/auth/step-up')
        ? json(422, { violations: [{ ruleCode: 'MFA_NOT_ENROLLED', message: 'Set up first.' }] })
        : json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
    }))
    const onNavigate = vi.fn()
    render(<StepUpProvider securityPath="/security" onNavigate={onNavigate}><div /></StepUpProvider>)
    const response = sessionFetch('/api/x', { method: 'POST' })
    await user().type(await screen.findByTestId('step-up-code'), '123456{Enter}')
    await user().click(await screen.findByRole('link', { name: 'Security' }))
    expect(onNavigate).toHaveBeenCalledWith('/security')
    expect((await response).status).toBe(403)
  })
})

describe('OidcCallback', () => {
  it('hands state and code over once and goes where the sign-in started', async () => {
    window.history.replaceState(null, '', '/portal/login/oidc?state=s1&code=c1')
    window.sessionStorage.setItem('jabiz.session.portal.oidcBinder', 'b1')
    window.sessionStorage.setItem('jabiz.session.portal.oidcReturnTo', '/portal/orders')
    const signInWithProvider = vi.fn(async () => ({ status: 'SIGNED_IN' as const }))
    const onSignedIn = vi.fn()
    render(
      <OidcCallback fallbackPath="/portal/" auth={{ signInWithProvider }} onSignedIn={onSignedIn} onSecondStep={vi.fn()} onBack={vi.fn()} />,
    )
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/orders'))
    expect(signInWithProvider).toHaveBeenCalledExactlyOnceWith('s1', 'c1', 'b1')
  })

  it('shows what the provider refused, and goes back', async () => {
    window.history.replaceState(null, '', '/portal/login/oidc?error=access_denied')
    const onBack = vi.fn()
    render(<OidcCallback fallbackPath="/portal/" auth={{ signInWithProvider: vi.fn() }} onSignedIn={vi.fn()} onSecondStep={vi.fn()} onBack={onBack} />)
    expect(screen.getByTestId('oidc-error')).toHaveTextContent('access_denied')
    await expectAccessible()
    await user().click(screen.getByRole('button', { name: 'Back to sign in' }))
    expect(onBack).toHaveBeenCalled()
  })
})

describe('ChangePasswordForm', () => {
  it('needs the current password, shows a wrong one under it, and says when done', async () => {
    const change = vi.fn()
      .mockRejectedValueOnce(new ApiError(422, problem(422, 'PASSWORD_INCORRECT', 'currentPassword').body as Record<string, unknown>))
      .mockResolvedValueOnce(undefined)
    render(<ChangePasswordForm change={change} />)
    await expectAccessible()
    await user().click(screen.getByRole('button', { name: 'Change the password' }))
    expect(await screen.findByText('Enter your current password.')).toBeInTheDocument()
    await user().type(screen.getByLabelText('Current password'), 'wrong')
    await user().type(screen.getByLabelText('New password'), 'brand new horse')
    await user().type(screen.getByLabelText('Repeat the new password'), 'brand new horse{Enter}')
    expect(await screen.findByText('PASSWORD_INCORRECT message')).toBeInTheDocument()
    expect(screen.getByLabelText('Current password')).toHaveAccessibleDescription('PASSWORD_INCORRECT message')

    await user().clear(screen.getByLabelText('Current password'))
    await user().type(screen.getByLabelText('Current password'), 'right{Enter}')
    await waitFor(() => expect(screen.getByTestId('change-password-state')).toHaveTextContent('has been changed'))
    expect(change).toHaveBeenLastCalledWith('right', 'brand new horse')
    expect(screen.getByLabelText('Current password')).toHaveValue('')
    await expectAccessible()
  })
})

describe('banners', () => {
  it('tell a user with an unverified address and send the link again', async () => {
    const posts = server({ '/api/auth/verify-email/resend': { status: 202 } })
    const { rerender } = render(<VerifyEmailBanner email="ann@example.com" emailVerified={false} />)
    expect(screen.getByTestId('verify-email-banner')).toHaveTextContent('ann@example.com')
    await expectAccessible()
    await user().click(screen.getByRole('button', { name: 'Send the link again' }))
    expect(await screen.findByRole('status')).toHaveTextContent('A new link is on its way.')
    expect(posts[0].body).toEqual({ email: 'ann@example.com', entry: 'portal' })
    rerender(<VerifyEmailBanner email="ann@example.com" emailVerified />)
    expect(screen.queryByTestId('verify-email-banner')).toBeNull()
  })

  it('say when the browser is offline', async () => {
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true)
    render(<OfflineBanner />)
    expect(screen.queryByTestId('offline-banner')).toBeNull()
    onLine.mockReturnValue(false)
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(screen.getByTestId('offline-banner')).toHaveTextContent('You are offline')
    await expectAccessible()
    onLine.mockRestore()
  })
})
