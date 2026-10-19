import { configureClient, session } from '@jabiz/client'
import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'
import { currentPath, hrefOf, navigate } from './router'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** What the portal asks before anybody signs in: its entry and its identity providers. */
function server() {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(String(input), { method: 'GET' })
    const url = new URL(request.url, window.location.origin)
    asked.push(url.pathname + url.search)
    if (url.pathname === '/api/auth/entries/portal') {
      return json(200, { name: 'portal', selfRegistration: true, requireVerifiedEmail: true, appPath: '/portal/',
        sessionPersistence: 'device' })
    }
    if (url.pathname === '/api/auth/oidc/providers') return json(200, [])
    return json(404, {})
  })
  return asked
}

beforeAll(() => configureClient({ entry: 'portal' }))
beforeEach(() => {
  session.clear()
  window.history.replaceState(null, '', '/')
})
afterEach(() => vi.restoreAllMocks())

describe('the portal', () => {
  it('sends a visitor to its sign-in, with sign-up and the identity providers of its own entry', async () => {
    const asked = server()
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
    await waitFor(() => expect(document.querySelector('a[href="/signup"]')).not.toBeNull())
    expect(document.querySelector('a[href="/password-reset"]')).not.toBeNull()
    await waitFor(() => expect(asked).toContain('/api/auth/oidc/providers?entry=portal'))
  })

  it('opens the pages of mailed links without signing in, and drops the token from the address', async () => {
    server()
    window.history.replaceState(null, '', '/auth/password-reset?entry=portal&token=abc')
    render(<App />)
    expect(await screen.findByTestId('password-reset')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelectorAll('input[type="password"]')).toHaveLength(2))
    expect(window.location.search).not.toContain('token')
  })

  it('opens its unsubscribe page without signing in and posts the token of the link once', async () => {
    const posted: unknown[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
      const url = new URL(request.url, window.location.origin)
      if (url.pathname === '/api/auth/mail/unsubscribe') {
        posted.push(await request.json())
        return new Response(null, { status: 204 })
      }
      if (url.pathname === '/api/auth/entries/portal') {
        return json(200, { name: 'portal', selfRegistration: true, requireVerifiedEmail: true, appPath: '/portal/',
          sessionPersistence: 'device' })
      }
      return json(404, {})
    })
    window.history.replaceState(null, '', '/mail/unsubscribe?token=t1')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('unsubscribe-state').dataset.state).toBe('done'))
    expect(posted).toEqual([{ token: 't1' }])
    expect(window.location.pathname).toBe('/mail/unsubscribe')
    expect(window.location.search).not.toContain('token')
  })

  it('keeps its pages to itself: a signed-out visitor of a page goes to the sign-in', async () => {
    server()
    window.history.replaceState(null, '', '/orders')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
  })
})

describe('linking a provider account (phase 16f)', () => {
  it('finishes the link where the provider sends the customer back and says so on the account page', async () => {
    session.store('access', 'refresh')
    window.sessionStorage.setItem('jabiz.session.portal.oidcPurpose', 'LINK')
    window.sessionStorage.setItem('jabiz.session.portal.oidcBinder', 'b1')
    window.sessionStorage.setItem('jabiz.session.portal.oidcReturnTo', '/account')
    window.history.replaceState(null, '', '/login/oidc?state=s1&code=c1')
    const posted: { path: string; body: unknown }[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
      const url = new URL(request.url, window.location.origin)
      if (request.method === 'POST') posted.push({ path: url.pathname, body: await request.json() })
      switch (url.pathname) {
        case '/api/auth/account/identities/callback':
          return json(200, { status: 'LINKED', provider: 'google' })
        case '/api/auth/me':
          return json(200, { userId: 'u1', displayName: 'Mei', roles: ['CUSTOMER'], permissions: [], emailVerified: true,
            idleTimeoutSeconds: 0 })
        case '/api/auth/oidc/providers':
          return json(200, [{ id: 'google', label: 'Google', kind: 'google' }])
        case '/api/auth/account/identities':
          return json(200, [{ id: 'i1', provider: 'google', label: 'Google', kind: 'google', email: 'mei@gmail.com',
            linkedAt: '2026-10-13T09:00:00Z' }])
        case '/api/auth/mfa':
          return json(200, { enrolled: false, pending: false })
        case '/api/auth/account/sessions':
          return json(200, { sessions: [], truncated: false, effectiveWithinSeconds: 900 })
        case '/api/auth/mail/preferences':
          return json(200, { templates: [{ template: 'commerce.order-shipped', subscribed: true,
            displayName: 'Shipment notices' }] })
        case '/api/auth/account/locale':
          return json(200, { locale: null, effective: 'en', languages: ['zh', 'ja', 'en'] })
        case '/api/auth/entries/portal':
          return json(200, { name: 'portal', selfRegistration: true, requireVerifiedEmail: true, appPath: '/portal/',
            sessionPersistence: 'device' })
        default:
          return json(404, {})
      }
    })
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/account'))
    expect(await screen.findByTestId('linked-identities-linked')).toHaveTextContent('Google')
    expect(posted).toContainEqual({ path: '/api/auth/account/identities/callback',
      body: { state: 's1', code: 'c1', binder: 'b1' } })
    expect(await screen.findByTestId('linked-identity')).toHaveTextContent('mei@gmail.com')
    // The portal's notifications, by their names (phase 16s).
    expect(await screen.findByRole('switch', { name: 'Shipment notices' })).toBeChecked()
  })
})

describe('the order page (phase 16n)', () => {
  it('shows its notes as Markdown with MarkdownView of @jabiz/ui', async () => {
    session.store('access', 'refresh')
    window.history.replaceState(null, '', '/order')
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
      const url = new URL(request.url, window.location.origin)
      switch (url.pathname) {
        case '/api/auth/me':
          return json(200, { userId: 'u1', displayName: 'Mei', roles: ['CUSTOMER'], permissions: [], emailVerified: true,
            idleTimeoutSeconds: 0 })
        case '/api/auth/entries/portal':
          return json(200, { name: 'portal', selfRegistration: true, requireVerifiedEmail: true, appPath: '/portal/',
            sessionPersistence: 'device' })
        case '/api/queries/commerce.portal_products':
          return json(200, { items: [{ warehouseCode: 'W1', sku: 'A-1', productName: 'Tea', unitPrice: 500 }] })
        default:
          return json(404, {})
      }
    })
    render(<App />)
    const notes = await screen.findByTestId('markdown-view')
    expect(notes.querySelector('h2')).toHaveTextContent('How ordering works')
    expect(notes.querySelectorAll('ol > li')).toHaveLength(3)
    expect(notes.querySelector('strong')).toHaveTextContent('in stock')
    expect(await screen.findByTestId('order-product')).toBeInTheDocument()
  })
})

describe('the router', () => {
  it('maps the portal\'s paths onto the build\'s base and refuses other sites', () => {
    expect(hrefOf('/orders')).toBe('/orders')
    expect(currentPath('/orders')).toBe('/orders')
    navigate('/account')
    expect(window.location.pathname).toBe('/account')
    navigate('//evil.example/x')
    expect(window.location.host).toBe(new URL(document.baseURI).host)
    expect(window.location.pathname).toBe('/')
  })
})
