import { ApiError, configureClient, i18n, lastSignInMethod, session } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { EmailLinkPanel, SignInForm } from './index'

/*
 * One "continue" for signing in and signing up (docs/design/10-security.md section 15.19; decision D84): the address,
 * then a link by mail or the password; this device's last way remembered (never the address); a link to an address
 * nobody has finishes signing up with a name before the account exists.
 */

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

/** Entries named `one…` have one "continue"; the link check answers `signUp`. Returns the posts made. */
function server(check: () => Response = () => json(200, { signUp: false })) {
  const posts: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      const name = path.split('/').pop()!
      return json(200, { name, selfRegistration: true, emailLink: true, appPath: '/app/',
        unifiedSignIn: name.startsWith('one') })
    }
    if (request.method === 'GET') return json(200, [])
    const text = await request.text()
    posts.push({ path, body: text ? JSON.parse(text) : undefined })
    if (path === '/api/auth/email-link') return new Response(null, { status: 202, headers: { 'Content-Length': '0' } })
    if (path === '/api/auth/email-link/check') return check()
    return json(500, {})
  }))
  return posts
}

const user = () => userEvent.setup()
let entries = 0
/** An entry name of its own per test: the entry's settings are kept per name. */
const entryNamed = (prefix: string) => `${prefix}${++entries}`

beforeEach(async () => {
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  localStorage.clear()
  configureClient({ entry: 'admin' })
  vi.unstubAllGlobals()
})

describe('SignInForm with flow="continue"', () => {
  it('mails a link for the address, with the password as the other way, and remembers only the way', async () => {
    const entry = entryNamed('one')
    configureClient({ entry })
    const posts = server()
    const signIn = vi.fn(async () => ({ status: 'SIGNED_IN' as const }))
    const onSignedIn = vi.fn()
    const view = render(<SignInForm brand="Shop" returnTo="/app/p/1" flow="continue" emailLink passkey={false}
      passwordResetPath="/app/forgot" signUpPath="/app/sign-up" tabs auth={{ signIn, verify: vi.fn() }}
      onSignedIn={onSignedIn} />)

    // One field and one button: no password form, no separate link form, no sign-up tab.
    await user().type(await screen.findByTestId('continue-email'), ' ann@example.com {Enter}')
    expect(screen.queryByTestId('login-user-name')).toBeNull()
    expect(await screen.findByTestId('continue-link-sent')).toHaveTextContent('ann@example.com')
    expect(posts).toEqual([{ path: '/api/auth/email-link',
      body: { email: 'ann@example.com', entry, to: '/app/p/1', locale: 'en' } }])
    expect(screen.getByRole('heading', { name: 'Check your mail' })).toBeInTheDocument()
    await expectAccessible()

    // The password instead, on the same step: the address goes with it.
    await user().click(screen.getByTestId('continue-use-password'))
    expect(screen.getByTestId('forgot-password')).toHaveAttribute('href', '/app/forgot')
    await user().type(screen.getByTestId('continue-password'), 'secret{Enter}')
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/app/p/1'))
    expect(signIn).toHaveBeenCalledWith('ann@example.com', 'secret')
    expect(lastSignInMethod(entry)).toBe('password')
    expect(JSON.stringify(localStorage)).not.toContain('ann@example.com')
    view.unmount()

    // Next time on this device: straight to the password, nothing mailed; a link is still one click away.
    render(<SignInForm brand="Shop" returnTo="/app/" flow="continue" auth={{ signIn, verify: vi.fn() }} />)
    await user().type(await screen.findByTestId('continue-email'), 'ann@example.com{Enter}')
    expect(await screen.findByTestId('continue-password')).toBeInTheDocument()
    expect(posts).toHaveLength(1)
    await user().click(screen.getByTestId('continue-use-link'))
    expect(await screen.findByTestId('continue-link-sent')).toBeInTheDocument()
    expect(posts).toHaveLength(2)
    expect(lastSignInMethod(entry)).toBe('link')
    await user().click(screen.getByTestId('continue-other-address'))
    expect(screen.getByTestId('continue-email')).toHaveValue('ann@example.com')
  })

  it('says the same for a wrong password whatever the address', async () => {
    const entry = entryNamed('one')
    configureClient({ entry })
    server()
    localStorage.setItem(`jabiz.signInMethod.${entry}`, 'password')
    const signIn = vi.fn(async () => {
      throw new ApiError(401, { status: 401, violations: [{ field: null, ruleCode: 'LOGIN_FAILED',
        message: 'Wrong user name or password' }] })
    })
    render(<SignInForm brand="Shop" returnTo="/app/" flow="continue" auth={{ signIn, verify: vi.fn() }} />)
    await user().type(await screen.findByTestId('continue-email'), 'nobody@example.com{Enter}')
    await user().type(await screen.findByTestId('continue-password'), 'x{Enter}')
    expect(await screen.findByTestId('login-error')).toHaveTextContent('Wrong user name or password')
  })

  it('is the ordinary form where the entry has no "continue"', async () => {
    configureClient({ entry: entryNamed('plain') })
    server()
    render(<SignInForm brand="Shop" returnTo="/app/" flow="continue" auth={{ signIn: vi.fn(), verify: vi.fn() }} />)
    expect(await screen.findByTestId('login-user-name')).toBeInTheDocument()
    expect(screen.queryByTestId('continue-email')).toBeNull()
  })
})

describe('EmailLinkPanel at an entry with one "continue"', () => {
  it('finishes signing up with a name before the account exists', async () => {
    const entry = entryNamed('one')
    configureClient({ entry })
    const posts = server(() => json(200, { signUp: true }))
    await act(() => i18n.changeLanguage('ja'))
    const signInWithEmailLink = vi.fn(async () => ({ status: 'SIGNED_IN' as const }))
    const onSignedIn = vi.fn()
    render(<EmailLinkPanel brand="Shop" link={{ entry, token: 't0k', appPath: '/app/' }} to="/app/p/2"
      signUpNote="By signing up you agree to the terms." auth={{ signInWithEmailLink, verify: vi.fn() }}
      onSignedIn={onSignedIn} />)

    expect(await screen.findByTestId('complete-sign-up')).toBeInTheDocument()
    expect(posts).toEqual([{ path: '/api/auth/email-link/check', body: { token: 't0k' } }])
    expect(screen.getByTestId('complete-sign-up-note')).toHaveTextContent('agree to the terms')
    await expectAccessible()
    // A name is needed.
    await user().click(screen.getByRole('button', { name: '登録を完了する' }))
    expect(await screen.findByText('名前を入力してください')).toBeInTheDocument()
    expect(signInWithEmailLink).not.toHaveBeenCalled()

    await user().type(screen.getByTestId('complete-sign-up-name'), ' Sora {Enter}')
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/app/p/2'))
    expect(signInWithEmailLink).toHaveBeenCalledWith('t0k', { displayName: 'Sora', locale: 'ja' })
    expect(lastSignInMethod(entry)).toBe('link')
  })

  it('signs in on the click when the link is an account\'s, and shows a guard\'s refusal before any name', async () => {
    const entry = entryNamed('one')
    configureClient({ entry })
    server()
    const signInWithEmailLink = vi.fn(async () => ({ status: 'SIGNED_IN' as const }))
    const view = render(<EmailLinkPanel brand="Shop" link={{ entry, token: 'a', appPath: '/app/' }}
      auth={{ signInWithEmailLink, verify: vi.fn() }} onSignedIn={vi.fn()} />)
    await user().click(await screen.findByTestId('email-link-start'))
    expect(signInWithEmailLink).toHaveBeenCalledWith('a')
    view.unmount()

    server(() => json(403, { status: 403, violations: [{ field: null, ruleCode: 'SIGN_UP_REFUSED',
      message: 'Not open to you' }] }))
    render(<EmailLinkPanel brand="Shop" link={{ entry, token: 'b', appPath: '/app/' }}
      auth={{ signInWithEmailLink, verify: vi.fn() }} />)
    expect(await screen.findByTestId('email-link-state')).toHaveTextContent('Not open to you')
    expect(screen.queryByTestId('complete-sign-up')).toBeNull()
  })

  it('asks nothing before the click where the entry has no "continue"', async () => {
    const entry = entryNamed('plain')
    configureClient({ entry })
    const posts = server()
    render(<EmailLinkPanel brand="Shop" link={{ entry, token: 'c', appPath: '/app/' }}
      auth={{ signInWithEmailLink: vi.fn(), verify: vi.fn() }} />)
    expect(await screen.findByTestId('email-link-start')).toBeInTheDocument()
    expect(posts).toEqual([])
  })
})
