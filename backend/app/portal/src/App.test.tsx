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

  it('keeps its pages to itself: a signed-out visitor of a page goes to the sign-in', async () => {
    server()
    window.history.replaceState(null, '', '/orders')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/login'))
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
