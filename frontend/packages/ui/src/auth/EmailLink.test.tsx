import { ApiError, configureClient, i18n, session } from '@jabiz/client'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { EmailLinkForm, EmailLinkPanel, MailLinkPage } from './index'

vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function refused(status: number, ruleCode: string) {
  return new ApiError(status, { status, violations: [{ field: null, ruleCode, message: `${ruleCode} message` }] })
}

/** Answers the entry's settings and accepts every request for a link. Returns the posts made. */
function server() {
  const posts: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init)
    const path = new URL(request.url).pathname
    if (request.method === 'GET' && path.startsWith('/api/auth/entries/')) {
      return json(200, { name: path.split('/').pop(), selfRegistration: true, emailLink: true, appPath: '/portal/' })
    }
    const text = await request.text()
    posts.push({ path, body: text ? JSON.parse(text) : undefined })
    if (path === '/api/auth/email-link') return new Response(null, { status: 202, headers: { 'Content-Length': '0' } })
    return json(500, {})
  }))
  return posts
}

const user = () => userEvent.setup()
const link = (token: string | null) => ({ entry: 'portal', token, appPath: '/portal/' })

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

describe('EmailLinkForm', () => {
  it('asks for a link back to its path, in the interface language, and says the same for every address', async () => {
    const posts = server()
    await act(() => i18n.changeLanguage('ja'))
    const onSent = vi.fn()
    render(<EmailLinkForm to="/portal/p/7" onSent={onSent} />)
    await user().type(screen.getByTestId('email-link-email'), ' ann@example.com {Enter}')
    expect(await screen.findByTestId('email-link-sent')).toHaveTextContent('ann@example.com')
    expect(posts).toEqual([{ path: '/api/auth/email-link',
      body: { email: 'ann@example.com', entry: 'portal', to: '/portal/p/7', locale: 'ja' } }])
    expect(onSent).toHaveBeenCalledWith('ann@example.com')
    await expectAccessible()
    // Another address: the form again.
    await user().click(screen.getByTestId('email-link-again'))
    expect(screen.getByTestId('email-link-email')).toBeInTheDocument()
  })

  it('needs an address', async () => {
    const posts = server()
    render(<EmailLinkForm />)
    await user().click(screen.getByRole('button', { name: 'Send me a link' }))
    expect(await screen.findByText('Enter your e-mail address.')).toBeInTheDocument()
    expect(posts).toEqual([])
  })
})

describe('EmailLinkPanel', () => {
  it('signs in only on the click and goes back to the path of the link', async () => {
    server()
    const signInWithEmailLink = vi.fn(async () => ({ status: 'SIGNED_IN' as const }))
    const onSignedIn = vi.fn()
    render(<EmailLinkPanel brand="Portal" link={link('t1')} to="/portal/p/7?ref=share" onSignedIn={onSignedIn}
      auth={{ signInWithEmailLink, verify: vi.fn() }} />)
    // A mail scanner opening the page uses nothing.
    expect(signInWithEmailLink).not.toHaveBeenCalled()
    await expectAccessible()
    await user().click(screen.getByTestId('email-link-start'))
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/p/7?ref=share'))
    expect(signInWithEmailLink).toHaveBeenCalledExactlyOnceWith('t1')
  })

  it("leads only to paths of the entry's front end", async () => {
    server()
    for (const to of ['//evil.example/', 'https://evil.example/', '/\\evil.example', '/admin/', '/portalx/', null]) {
      const onSignedIn = vi.fn()
      const { unmount } = render(<EmailLinkPanel brand="Portal" link={link('t1')} to={to} onSignedIn={onSignedIn}
        auth={{ signInWithEmailLink: async () => ({ status: 'SIGNED_IN' }), verify: vi.fn() }} />)
      await user().click(screen.getByTestId('email-link-start'))
      await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/'))
      unmount()
    }
  })

  it('asks for the second factor after the link', async () => {
    server()
    const verify = vi.fn(async () => undefined)
    const onSignedIn = vi.fn()
    render(<EmailLinkPanel brand="Portal" link={link('t1')} to="/portal/p/7" onSignedIn={onSignedIn}
      auth={{ signInWithEmailLink: async () => ({ status: 'MFA_REQUIRED', challenge: 'c1' }), verify }} />)
    await user().click(screen.getByTestId('email-link-start'))
    await user().type(await screen.findByRole('textbox', { name: 'Code' }), '123456{Enter}')
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/p/7'))
    expect(verify).toHaveBeenCalledWith('c1', '123456')
  })

  it('says when the sign-in was refused, and offers a new link', async () => {
    server()
    render(<EmailLinkPanel brand="Portal" link={link('t1')} to="/portal/p/7"
      auth={{ signInWithEmailLink: () => Promise.reject(refused(403, 'SIGN_IN_REFUSED')), verify: vi.fn() }} />)
    await user().click(screen.getByTestId('email-link-start'))
    expect(await screen.findByTestId('email-link-state')).toHaveTextContent('You cannot sign in here at the moment.')
    // The link is used: no button to try it again, a form for a new one.
    expect(screen.queryByTestId('email-link-start')).toBeNull()
    expect(screen.getByTestId('email-link-email')).toBeInTheDocument()
    await expectAccessible()
  })

  it('goes back from the second step to asking for a new link', async () => {
    server()
    render(<EmailLinkPanel brand="Portal" link={link('t1')}
      auth={{ signInWithEmailLink: async () => ({ status: 'MFA_REQUIRED', challenge: 'c1' }), verify: vi.fn() }} />)
    await user().click(screen.getByTestId('email-link-start'))
    expect(await screen.findByText('Two-step verification')).toBeInTheDocument()
    await user().click(screen.getByRole('button', { name: 'Back to sign in' }))
    expect(screen.getByTestId('email-link-email')).toBeInTheDocument()
    expect(screen.queryByTestId('email-link-state')).toBeNull()
  })

  it('offers a new link for the same path when the link is no good', async () => {
    const posts = server()
    render(<EmailLinkPanel brand="Portal" link={link('old')} to="/portal/p/7"
      auth={{ signInWithEmailLink: () => Promise.reject(refused(422, 'TOKEN_INVALID')), verify: vi.fn() }} />)
    await user().click(screen.getByTestId('email-link-start'))
    expect(await screen.findByTestId('email-link-state')).toHaveTextContent('invalid')
    await expectAccessible()
    await user().type(screen.getByTestId('email-link-email'), 'ann@example.com{Enter}')
    await screen.findByTestId('email-link-sent')
    expect(posts.at(-1)?.body).toEqual({ email: 'ann@example.com', entry: 'portal', to: '/portal/p/7', locale: 'en' })
  })

  it('may be tried again after a failure that is not the link', async () => {
    server()
    const signInWithEmailLink = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ status: 'SIGNED_IN' })
    const onSignedIn = vi.fn()
    render(<EmailLinkPanel brand="Portal" link={link('t1')} onSignedIn={onSignedIn}
      auth={{ signInWithEmailLink, verify: vi.fn() }} />)
    await user().click(screen.getByTestId('email-link-start'))
    await user().click(await screen.findByTestId('email-link-retry'))
    await user().click(screen.getByTestId('email-link-start'))
    await waitFor(() => expect(onSignedIn).toHaveBeenCalledWith('/portal/'))
  })
})

describe('MailLinkPage', () => {
  it('opens the sign-in link page, keeping the token out of the address bar', async () => {
    server()
    window.history.replaceState(null, '', '/portal/auth/email-link?entry=portal&token=t1&to=%2Fportal%2Fp%2F7')
    render(<MailLinkPage brand="Portal" />)
    expect(screen.getByTestId('email-link-start')).toBeInTheDocument()
    expect(window.location.search).toBe('?entry=portal&to=%2Fportal%2Fp%2F7')
    expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe('no-referrer')
  })
})
