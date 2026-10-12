import { afterEach, describe, expect, it, vi } from 'vitest'
import { configureSession } from '../api/session'
import { startProviderSignIn, takeBinder, takeReturnPath } from './oidc'

afterEach(() => {
  window.sessionStorage.clear()
  configureSession('admin')
  vi.unstubAllGlobals()
})

describe('takeBinder', () => {
  it('returns the stored binder of the entry once', () => {
    window.sessionStorage.setItem('jabiz.session.admin.oidcBinder', 'b')
    expect(takeBinder()).toBe('b')
    expect(takeBinder()).toBeNull()
  })
})

describe('takeReturnPath', () => {
  it('returns the stored path once, and only paths of this site, else the fallback the caller gives', () => {
    window.sessionStorage.setItem('jabiz.session.admin.oidcReturnTo', '/reports?id=x')
    expect(takeReturnPath('/data')).toBe('/reports?id=x')
    expect(takeReturnPath('/data')).toBe('/data')
    for (const foreign of ['//evil.example.com', 'https://evil.example.com', 'javascript:alert(1)', '/\\evil.example.com']) {
      window.sessionStorage.setItem('jabiz.session.admin.oidcReturnTo', foreign)
      expect(takeReturnPath('/portal/')).toBe('/portal/')
    }
  })

  it('keeps the round trips of different entries apart', () => {
    window.sessionStorage.setItem('jabiz.session.admin.oidcReturnTo', '/tasks')
    configureSession('portal')
    expect(takeReturnPath('/portal/')).toBe('/portal/')
    configureSession('admin')
    expect(takeReturnPath('/data')).toBe('/tasks')
  })
})

describe('startProviderSignIn', () => {
  it("starts for the frontend's entry and keeps the binder under the entry", async () => {
    configureSession('portal')
    const fetch = vi.fn<(request: Request) => Promise<Response>>(async () =>
      new Response(JSON.stringify({ authorizationUrl: 'https://idp.example.com/authorize?x=1', binder: 'bind-1' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }))
    vi.stubGlobal('fetch', fetch)
    // The page then opens the provider's address (jsdom does not navigate to other origins).
    await startProviderSignIn('corp', '/portal/orders')

    const url = new URL(fetch.mock.calls[0][0].url)
    expect(url.pathname).toBe('/api/auth/oidc/corp/start')
    expect(url.searchParams.get('entry')).toBe('portal')
    expect(window.sessionStorage.getItem('jabiz.session.portal.oidcBinder')).toBe('bind-1')
    expect(window.sessionStorage.getItem('jabiz.session.portal.oidcReturnTo')).toBe('/portal/orders')
  })
})
