import { AuthProvider, PasskeyCancelledError, configureClient, i18n, runProcess, session } from '@jabiz/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { MailLinkPage, Passkeys, SignInForm, StepUpProvider, passkeyDeviceName } from './index'

type Answer = { status: number; body?: unknown }

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function problem(status: number, ruleCode: string): Answer {
  return { status, body: { status, violations: [{ field: null, ruleCode, message: `${ruleCode} message` }] } }
}

const CREATION = { publicKey: { challenge: 'AQID', rp: { id: 'localhost', name: 'Portal' },
  user: { id: 'AQ', name: 'ann@example.com', displayName: 'Ann' }, pubKeyCredParams: [], excludeCredentials: [] } }
const REQUEST = { publicKey: { challenge: 'AQID', rpId: 'localhost', allowCredentials: [] } }
const PASSKEY = { id: 'p1', name: 'iPhone', createdAt: '2026-11-01T08:00:00Z', lastUsedAt: null, backedUp: true }

/**
 * Answers by method and path (a list is answered in turn); the entry offers passkeys unless told otherwise. Returns
 * what was sent.
 */
function server(answers: Record<string, Answer | Answer[]>, passkeys = true) {
  const sent: { method: string; path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return json(200, { name: path.split('/').pop(), appPath: '/portal/', passkeys })
    }
    if (request.method === 'GET' && path === '/api/auth/oidc/providers') return json(200, [])
    const text = await request.text()
    sent.push({ method: request.method, path, body: text ? JSON.parse(text) : undefined })
    const planned = answers[`${request.method} ${path}`]
    const answer = Array.isArray(planned) ? planned.shift() : planned
    if (!answer) return json(500, {})
    return answer.body === undefined
      ? new Response(null, { status: answer.status, headers: { 'Content-Length': '0' } })
      : json(answer.status, answer.body)
  }))
  return sent
}

/** A browser with WebAuthn whose dialog answers (or is closed). */
function webAuthn({ closed = false, autofill = false }: { closed?: boolean; autofill?: boolean } = {}) {
  const credential = { toJSON: () => ({ id: 'cred', type: 'public-key', response: {} }) }
  const answer = vi.fn(async () => {
    if (closed) throw new DOMException('closed', 'NotAllowedError')
    return credential
  })
  const PublicKeyCredential = Object.assign(function PublicKeyCredential() {}, {
    isConditionalMediationAvailable: async () => autofill,
  })
  vi.stubGlobal('PublicKeyCredential', PublicKeyCredential)
  Object.defineProperty(navigator, 'credentials', { configurable: true, value: { create: answer, get: answer } })
  return answer
}

const user = () => userEvent.setup()
let entries = 0

beforeEach(async () => {
  // An entry of its own per test: what an entry offers is read once per page and name.
  configureClient({ entry: `keyed-${++entries}` })
  await act(() => i18n.changeLanguage('en'))
})

afterEach(() => {
  session.clear()
  configureClient({ entry: 'admin' })
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  Reflect.deleteProperty(navigator, 'credentials')
})

describe('signing in with a passkey (decision D71)', () => {
  const signIn = vi.fn()
  const verify = vi.fn()
  const signInWithPasskey = vi.fn()
  const verifyWithPasskey = vi.fn()
  beforeEach(() => {
    for (const mock of [signIn, verify, signInWithPasskey, verifyWithPasskey]) mock.mockReset()
  })

  function form(props: Partial<Parameters<typeof SignInForm>[0]> = {}) {
    return render(<SignInForm brand="Portal" returnTo="/portal/orders" providers={[]} passkey
      auth={{ signIn, verify, signInWithPasskey, verifyWithPasskey }} {...props} />)
  }

  it('offers a passkey above the password and signs in with it', async () => {
    server({})
    webAuthn()
    signInWithPasskey.mockResolvedValue(undefined)
    const onSignedIn = vi.fn()
    form({ onSignedIn })
    const button = await screen.findByRole('button', { name: 'Sign in with a passkey' })
    expect(button.compareDocumentPosition(screen.getByTestId('login-user-name')) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy()
    // The browser may suggest the passkeys in the name field.
    expect(screen.getByTestId('login-user-name')).toHaveAttribute('autocomplete', 'username webauthn')
    await expectAccessible()
    await user().click(button)
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/orders'))
    expect(signInWithPasskey).toHaveBeenCalledWith(undefined)
  })

  it('suggests the passkeys in the name field where the browser can, and stops when the button is used', async () => {
    server({})
    webAuthn({ autofill: true })
    let suggested: AbortSignal | undefined
    signInWithPasskey.mockImplementationOnce((options: { signal: AbortSignal }) => {
      suggested = options.signal
      return new Promise((_, reject) => options.signal.addEventListener('abort', () =>
        reject(new PasskeyCancelledError())))
    }).mockResolvedValue(undefined)
    form({ onSignedIn: vi.fn() })
    await waitFor(() => expect(signInWithPasskey).toHaveBeenCalledWith(expect.objectContaining({ autofill: true })))
    await user().click(await screen.findByRole('button', { name: 'Sign in with a passkey' }))
    expect(suggested?.aborted).toBe(true)
    // Closing the suggestions said nothing.
    expect(screen.queryByTestId('login-error')).toBeNull()
  })

  it('says nothing when the dialog is closed', async () => {
    server({})
    webAuthn()
    signInWithPasskey.mockRejectedValueOnce(new PasskeyCancelledError())
    form()
    await user().click(await screen.findByRole('button', { name: 'Sign in with a passkey' }))
    expect(screen.queryByTestId('login-error')).toBeNull()
  })

  it('is not offered where the entry does not take passkeys, nor without WebAuthn', async () => {
    server({}, false)
    webAuthn()
    form({ signUpPath: '/portal/sign-up' })
    await screen.findByTestId('login-user-name')
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByTestId('login-passkey')).toBeNull()
    expect(screen.getByTestId('login-user-name')).toHaveAttribute('autocomplete', 'username')
  })

  it('tells people inside WeChat to open the browser', async () => {
    server({})
    webAuthn()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) '
      + 'AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.47')
    form()
    expect(await screen.findByTestId('login-passkey-in-app')).toHaveTextContent('Open in browser')
    expect(screen.queryByTestId('login-passkey')).toBeNull()
  })

  it('completes the second step with a passkey instead of a code', async () => {
    server({})
    webAuthn()
    signIn.mockResolvedValue({ status: 'MFA_REQUIRED', challenge: 'c1' })
    verifyWithPasskey.mockRejectedValueOnce(Object.assign(new Error('x'), { status: 422,
      violations: [{ ruleCode: 'MFA_NOT_ENROLLED', message: 'none' }] }))
    const onSignedIn = vi.fn()
    form({ onSignedIn })
    await user().type(await screen.findByTestId('login-user-name'), 'ann')
    await user().type(screen.getByLabelText('Password'), 'secret{Enter}')
    await user().click(await screen.findByRole('button', { name: 'Use a passkey instead' }))
    expect(verifyWithPasskey).toHaveBeenCalledWith('c1')
    verifyWithPasskey.mockResolvedValue(undefined)
    await user().click(screen.getByRole('button', { name: 'Use a passkey instead' }))
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/orders'))
  })

  it('enrols a passkey instead of TOTP when a role requires a second factor', async () => {
    const sent = server({
      'POST /api/auth/challenge/passkey/register/options': { status: 200, body: CREATION },
      'POST /api/auth/challenge/passkey/register': { status: 201, body: { passkeyId: 'p1' } },
    })
    webAuthn()
    form({ initialStep: { status: 'MFA_ENROLLMENT_REQUIRED', challenge: 'e1' } })
    await user().click(await screen.findByRole('button', { name: 'Add a passkey instead' }))
    expect(await screen.findByTestId('mfa-enrolled')).toHaveTextContent('Your passkey is added')
    expect(sent.find((s) => s.path === '/api/auth/challenge/passkey/register')?.body)
      .toMatchObject({ challenge: 'e1', credential: { id: 'cred' }, name: passkeyDeviceName() })
  })
})

describe('Passkeys', () => {
  it('lists, renames and removes the user\'s passkeys', async () => {
    const sent = server({
      'GET /api/auth/account/passkeys': [{ status: 200, body: [PASSKEY] }, { status: 200, body: [{ ...PASSKEY,
        name: 'Work phone' }] }, { status: 200, body: [] }],
      'POST /api/auth/account/passkeys/p1/rename': { status: 200, body: { passkeyId: 'p1' } },
      'POST /api/auth/account/passkeys/p1/remove': { status: 204 },
    })
    webAuthn()
    render(<Passkeys />)
    const item = await screen.findByTestId('passkey')
    expect(item).toHaveTextContent('iPhone')
    expect(item).toHaveTextContent('Synced')
    expect(item).toHaveTextContent('Never')
    await expectAccessible()
    await user().click(within(item).getByRole('button', { name: 'Rename iPhone' }))
    const name = await screen.findByTestId('passkey-new-name')
    await user().clear(name)
    await user().type(name, 'Work phone{Enter}')
    expect(await screen.findByTestId('passkeys-state')).toHaveTextContent('The passkey is renamed.')
    expect(sent.find((s) => s.path.endsWith('/rename'))?.body).toEqual({ name: 'Work phone' })
    await user().click(await screen.findByRole('button', { name: 'Remove Work phone' }))
    await user().click(await screen.findByRole('button', { name: 'Remove' }))
    expect(await screen.findByTestId('passkeys-none')).toBeInTheDocument()
    expect(screen.getByTestId('passkeys-state')).toHaveTextContent('"Work phone" has been removed.')
  })

  it('adds a passkey after the current password', async () => {
    const sent = server({
      'GET /api/auth/account/passkeys': [{ status: 200, body: [] }, { status: 200, body: [PASSKEY] }],
      'POST /api/auth/account/passkeys/options': [problem(422, 'REAUTHENTICATION_REQUIRED'),
        problem(422, 'PASSWORD_INCORRECT'), { status: 200, body: CREATION }],
      'POST /api/auth/account/passkeys': { status: 201, body: { passkeyId: 'p1' } },
    })
    const dialog = webAuthn()
    render(<Passkeys />)
    await user().click(await screen.findByRole('button', { name: 'Add a passkey' }))
    await user().type(await screen.findByTestId('passkey-password'), 'wrong{Enter}')
    expect(await screen.findByTestId('passkey-password-error')).toHaveTextContent('PASSWORD_INCORRECT message')
    await expectAccessible()
    await user().clear(screen.getByTestId('passkey-password'))
    await user().type(screen.getByTestId('passkey-password'), 'right{Enter}')
    expect(await screen.findByTestId('passkeys-state')).toHaveTextContent(`"${passkeyDeviceName()}" is added.`)
    expect(dialog).toHaveBeenCalledTimes(1)
    expect(sent.filter((s) => s.path === '/api/auth/account/passkeys/options').map((s) => s.body))
      .toEqual([{}, { currentPassword: 'wrong' }, { currentPassword: 'right' }])
    expect(sent.find((s) => s.method === 'POST' && s.path === '/api/auth/account/passkeys')?.body)
      .toEqual({ credential: { id: 'cred', type: 'public-key', response: {} }, name: passkeyDeviceName() })
    expect(await screen.findByTestId('passkey')).toHaveTextContent('iPhone')
  })

  it('mails a confirmation to a user without a password', async () => {
    const sent = server({
      'GET /api/auth/account/passkeys': { status: 200, body: [] },
      'POST /api/auth/account/passkeys/options': problem(422, 'REAUTHENTICATION_UNAVAILABLE'),
      'POST /api/auth/account/passkeys/confirmation': { status: 202 },
    })
    webAuthn()
    render(<Passkeys />)
    await user().click(await screen.findByRole('button', { name: 'Add a passkey' }))
    await user().click(await screen.findByRole('button', { name: 'Send the link' }))
    expect(await screen.findByTestId('passkey-mail-sent')).toHaveTextContent('Open it on this device')
    expect(sent.map((s) => s.path)).toContain('/api/auth/account/passkeys/confirmation')
  })

  it('says why passkeys cannot be added in this browser, and is nothing where the entry takes none', async () => {
    server({ 'GET /api/auth/account/passkeys': { status: 200, body: [PASSKEY] } })
    const { unmount } = render(<Passkeys />)
    expect(await screen.findByTestId('passkeys-unavailable')).toHaveTextContent('This browser cannot use passkeys.')
    expect(screen.queryByTestId('passkey-add')).toBeNull()
    // Still listed: they can be removed from here (the list may load after the notice).
    expect(await screen.findByTestId('passkey')).toBeInTheDocument()
    unmount()
    const { container } = render(<Passkeys offered={false} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('the confirmation link (passkey-confirm)', () => {
  function page() {
    return render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider>
          <MailLinkPage brand="Portal" page="passkey-confirm" search="?entry=portal&token=t1" />
        </AuthProvider>
      </QueryClientProvider>,
    )
  }

  it('adds a passkey of this device in the user\'s session with the mail\'s token', async () => {
    session.store('access', 'refresh')
    const sent = server({
      'GET /api/auth/me': { status: 200, body: { userId: 'u1', entry: undefined, permissions: [] } },
      'POST /api/auth/account/passkeys/options': { status: 200, body: CREATION },
      'POST /api/auth/account/passkeys': { status: 201, body: { passkeyId: 'p1' } },
    })
    webAuthn()
    page()
    await user().click(await screen.findByRole('button', { name: 'Add a passkey' }))
    expect(await screen.findByTestId('passkey-confirm-state')).toHaveTextContent('is added')
    expect(sent.find((s) => s.path === '/api/auth/account/passkeys/options')?.body).toEqual({ passkeyToken: 't1' })
    await expectAccessible()
  })

  it('asks to sign in first, and says when the link is used up', async () => {
    server({})
    webAuthn()
    const { unmount } = page()
    expect(await screen.findByTestId('passkey-confirm-state')).toHaveTextContent('Sign in on this device first')
    unmount()
    session.store('access', 'refresh')
    server({
      'GET /api/auth/me': { status: 200, body: { userId: 'u1', permissions: [] } },
      'POST /api/auth/account/passkeys/options': problem(422, 'TOKEN_INVALID'),
    })
    page()
    await user().click(await screen.findByRole('button', { name: 'Add a passkey' }))
    expect(await screen.findByTestId('passkey-confirm-state')).toHaveTextContent('This link is invalid')
  })
})

describe('step-up with a passkey', () => {
  it('confirms with a passkey where the user has one', async () => {
    session.store('old', 'refresh')
    webAuthn()
    vi.stubGlobal('fetch', vi.fn(async (input: Request) => {
      const path = new URL(input.url).pathname
      if (path === '/api/auth/mfa') return json(200, { enrolled: false, pending: false, recoveryCodesLeft: 0, passkeys: 1 })
      if (path === '/api/auth/step-up/passkey/options') return json(200, REQUEST)
      if (path === '/api/auth/step-up/passkey') return json(200, { accessToken: 'stepped' })
      return input.headers.get('Authorization') === 'Bearer stepped'
        ? json(200, { output: { done: true } })
        : json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
    }))
    render(<StepUpProvider><div /></StepUpProvider>)
    const result = runProcess('ORDER_PLACE', { qty: 1 })
    await user().click(await screen.findByRole('button', { name: 'Use a passkey' }))
    expect(await result).toEqual({ done: true })
  })
})
