import { act, render, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import { configureSession, session, setSharedTokens } from '../api/session'
import { fakeLocks, json, urlOf, withLocks } from '../test/locks'
import {
  changePassword,
  confirmPasswordReset,
  entryInfo,
  forgetEntryInfo,
  finishIdentityLink,
  isProblem,
  listIdentities,
  listSessions,
  register,
  requestPasswordReset,
  resendVerification,
  revokeOtherSessions,
  revokeSession,
  unlinkIdentity,
  useOnline,
} from './account'
import { parseMailLink, readMailLink, useMailLink } from './mailLink'

/** Records what is posted and answers 202, or as given. */
function server(answer: () => Response = () => new Response(null, { status: 202, headers: { 'Content-Length': '0' } })) {
  const posts: { path: string; body: unknown; auth: string | null }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(urlOf(input), 'http://localhost')
    const request = input instanceof Request ? input : null
    if ((request?.method ?? init?.method) === 'POST') {
      const text = request ? await request.text() : String(init?.body ?? '')
      const headers = new Headers(request?.headers ?? init?.headers)
      posts.push({ path: url.pathname, body: JSON.parse(text), auth: headers.get('Authorization') })
      return answer()
    }
    if (url.pathname.startsWith('/api/auth/entries/')) return json(200, { name: 'portal', appPath: '/portal/' })
    return json(404, {})
  }))
  return posts
}

describe('account functions (decision D43)', () => {
  beforeEach(() => configureSession('portal'))
  afterEach(() => {
    session.clear()
    configureSession('admin')
    forgetEntryInfo()
    vi.unstubAllGlobals()
  })

  it("act for the frontend's entry unless another is named", async () => {
    const posts = server()
    await register({ email: 'ann@example.com', password: 'correct horse', displayName: 'Ann', locale: 'en' })
    await requestPasswordReset(' ann@example.com ')
    await resendVerification('ann@example.com', 'shop')
    expect(posts.map((p) => [p.path, p.body])).toEqual([
      ['/api/auth/register', { email: 'ann@example.com', password: 'correct horse', displayName: 'Ann', locale: 'en', entry: 'portal' }],
      ['/api/auth/password-reset/request', { email: 'ann@example.com', entry: 'portal' }],
      ['/api/auth/verify-email/resend', { email: 'ann@example.com', entry: 'shop' }],
    ])
  })

  it('throw the refusal with its rule code', async () => {
    server(() => json(422, { violations: [{ field: null, ruleCode: 'TOKEN_INVALID', message: 'No.' }] }))
    const refused = await confirmPasswordReset('old', 'new password').catch((e: unknown) => e)
    expect(isProblem(refused, 'TOKEN_INVALID')).toBe(true)
    expect(isProblem(refused, 'TOKEN_INVALID', 422)).toBe(true)
    expect(isProblem(refused, 'TOKEN_INVALID', 400)).toBe(false)
    expect(isProblem(new Error('x'), 'TOKEN_INVALID')).toBe(false)
  })

  it('change the own password with the current one, keeping this session', async () => {
    session.store('access', 'refresh-1')
    const posts = server(() => new Response(null, { status: 204 }))
    await changePassword('old one', 'new one')
    expect(posts).toEqual([{
      path: '/api/auth/account/password',
      body: { currentPassword: 'old one', newPassword: 'new one', refreshToken: 'refresh-1' },
      auth: 'Bearer access',
    }])

    server(() => json(422, { violations: [{ field: 'currentPassword', ruleCode: 'PASSWORD_INCORRECT', message: 'Wrong.' }] }))
    const refused = await changePassword('wrong', 'new one').catch((e: unknown) => e)
    expect(refused).toBeInstanceOf(ApiError)
    expect(isProblem(refused, 'PASSWORD_INCORRECT')).toBe(true)
  })

  it("send the session's token as the other tabs see it, not this tab's late view of localStorage", async () => {
    // A session kept on the device: the token is read from the shared copy (IndexedDB in the browser) under the lock.
    withLocks(fakeLocks())
    window.localStorage.setItem('jabiz.session.portal.refresh', 'refresh-1')
    configureSession('portal')
    const copies = new Map([['jabiz.session.portal.refresh', 'refresh-2']])
    setSharedTokens({
      get: async (key) => copies.get(key),
      put: async (key, value) => void copies.set(key, value),
      delete: async (key) => void copies.delete(key),
    })
    session.storeAccess('access')
    try {
      const posts = server(() => new Response(null, { status: 204 }))
      await changePassword('old one', 'new one')
      expect(posts[0].body).toEqual({ currentPassword: 'old one', newPassword: 'new one', refreshToken: 'refresh-2' })
    } finally {
      setSharedTokens(null)
      withLocks(undefined)
      window.localStorage.clear()
    }
  })

  it("read an entry's settings once", async () => {
    server()
    const fetch = globalThis.fetch as ReturnType<typeof vi.fn>
    expect((await entryInfo()).appPath).toBe('/portal/')
    await entryInfo('portal')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('follow whether the browser is online', () => {
    const { result } = renderHook(() => useOnline())
    expect(result.current).toBe(true)
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(result.current).toBe(false)
    onLine.mockReturnValue(true)
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(result.current).toBe(true)
    onLine.mockRestore()
  })
})

describe('mailed links (decision D43 item 4)', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/')
    forgetEntryInfo()
    vi.unstubAllGlobals()
  })

  it('read the entry and token, and take the token out of the address bar', () => {
    window.history.replaceState(null, '', '/portal/auth/verify-email?entry=portal&token=tok-1#x')
    expect(readMailLink()).toEqual({ entry: 'portal', token: 'tok-1', page: 'verify-email' })
    expect(window.location.pathname + window.location.search + window.location.hash).toBe('/portal/auth/verify-email?entry=portal#x')
    expect(readMailLink().token).toBeNull()
  })

  it("keep to entry names, else the frontend's entry", () => {
    window.history.replaceState(null, '', '/auth/join?entry=../evil&token=t')
    expect(parseMailLink()).toEqual({ entry: 'admin', token: 't', page: 'join' })
    expect(parseMailLink('?entry=shop')).toEqual({ entry: 'shop', token: null, page: 'join' })
  })

  it('send no referrer while the page is open, and lead to the entry once done', async () => {
    server()
    window.history.replaceState(null, '', '/portal/auth/password-reset?entry=portal&token=r1')
    function Probe() {
      const link = useMailLink()
      return <a data-testid="probe" data-token={link.token ?? ''} href={link.appPath} />
    }
    const { getByTestId, unmount } = render(<Probe />)
    expect(getByTestId('probe').dataset.token).toBe('r1')
    expect(window.location.search).toBe('?entry=portal')
    expect(document.querySelector('meta[name="referrer"]')?.getAttribute('content')).toBe('no-referrer')
    await waitFor(() => expect(getByTestId('probe').getAttribute('href')).toBe('/portal/'))
    unmount()
    expect(document.querySelector('meta[name="referrer"]')).toBeNull()
  })
})

describe('session functions (phase 16g)', () => {
  beforeEach(() => configureSession('portal'))
  afterEach(() => {
    session.clear()
    configureSession('admin')
    vi.unstubAllGlobals()
  })

  /** Answers by method and path; records the calls. */
  function sessionServer() {
    const calls: { method: string; path: string; auth: string | null }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : null
      const method = request?.method ?? init?.method ?? 'GET'
      const path = new URL(urlOf(input), 'http://localhost').pathname
      calls.push({ method, path, auth: new Headers(request?.headers ?? init?.headers).get('Authorization') })
      if (method === 'GET') return json(200, { sessions: [{ id: 's1', current: true }], truncated: false, effectiveWithinSeconds: 900 })
      if (path.endsWith('/revoke-others')) return json(200, { revoked: 2, effectiveWithinSeconds: 900 })
      return new Response(null, { status: 204 })
    }))
    return calls
  }

  it('list, end one and end the others of the signed-in user', async () => {
    session.store('access', 'refresh-1')
    const calls = sessionServer()
    expect((await listSessions()).sessions).toEqual([{ id: 's1', current: true }])
    await revokeSession('s2')
    expect(await revokeOtherSessions()).toEqual({ revoked: 2, effectiveWithinSeconds: 900 })
    expect(calls).toEqual([
      { method: 'GET', path: '/api/auth/account/sessions', auth: 'Bearer access' },
      { method: 'POST', path: '/api/auth/account/sessions/s2/revoke', auth: 'Bearer access' },
      { method: 'POST', path: '/api/auth/account/sessions/revoke-others', auth: 'Bearer access' },
    ])
    // Another session ended: this one stays.
    expect(session.refreshToken()).toBe('refresh-1')
  })

  it('ending this session signs it out here', async () => {
    session.store('access', 'refresh-1')
    sessionServer()
    await revokeSession('s1', true)
    expect(session.accessToken()).toBeNull()
    expect(session.refreshToken()).toBeNull()
  })
})

describe('provider account functions (phase 16f)', () => {
  beforeEach(() => configureSession('portal'))
  afterEach(() => {
    session.clear()
    configureSession('admin')
    vi.unstubAllGlobals()
  })

  it("list, finish linking and remove the signed-in user's provider accounts", async () => {
    session.store('access', 'refresh-1')
    const calls: { method: string; path: string; body: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : null
      const method = request?.method ?? init?.method ?? 'GET'
      const path = new URL(urlOf(input), 'http://localhost').pathname
      const text = request ? await request.text() : ''
      calls.push({ method, path, body: text ? JSON.parse(text) : undefined })
      if (method === 'GET') return json(200, [{ id: 'i1', provider: 'google', label: 'Google' }])
      if (path.endsWith('/callback')) return json(200, { status: 'LINKED', provider: 'google' })
      return new Response(null, { status: 204 })
    }))
    expect(await listIdentities()).toEqual([{ id: 'i1', provider: 'google', label: 'Google' }])
    expect(await finishIdentityLink('s', 'c', 'b')).toEqual({ status: 'LINKED', provider: 'google' })
    await unlinkIdentity('i1')
    expect(calls).toEqual([
      { method: 'GET', path: '/api/auth/account/identities', body: undefined },
      { method: 'POST', path: '/api/auth/account/identities/callback', body: { state: 's', code: 'c', binder: 'b' } },
      { method: 'DELETE', path: '/api/auth/account/identities/i1', body: undefined },
    ])
    // Linking leaves the session as it is.
    expect(session.refreshToken()).toBe('refresh-1')
  })
})
