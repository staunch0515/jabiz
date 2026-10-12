import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { configureSession, session } from '../api/session'
import { fakeLocks, json, urlOf, withLocks } from '../test/locks'
import { forgetEntryInfo } from './account'
import { AuthProvider, useAuth } from './AuthContext'

type Me = { userId: string; entry?: string; email?: string; emailVerified?: boolean }

/** The server of these tests: the entry's settings, sign-in, refresh and /me as given. Returns the bodies posted. */
function server(options: { me: () => Me; persistence?: 'tab' | 'device' }) {
  const posts: { path: string; body: Record<string, unknown> }[] = []
  let issued = 0
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(urlOf(input), 'http://localhost')
    const request = input instanceof Request ? input : null
    const method = request?.method ?? init?.method ?? 'GET'
    if (method === 'POST') {
      const text = request ? await request.text() : String(init?.body ?? '')
      posts.push({ path: url.pathname, body: text ? JSON.parse(text) : {} })
    }
    if (url.pathname.startsWith('/api/auth/entries/')) {
      return json(200, { name: url.pathname.split('/').pop(), sessionPersistence: options.persistence })
    }
    if (url.pathname === '/api/auth/login' || url.pathname === '/api/auth/refresh') {
      issued += 1
      return json(200, { status: 'SIGNED_IN', accessToken: `a${issued}`, refreshToken: `r${issued}` })
    }
    if (url.pathname === '/api/auth/me') return json(200, options.me())
    return json(404, {})
  }))
  return posts
}

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>
  )
}

describe('AuthProvider with a sign-in entry (decision D43)', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
    withLocks(fakeLocks())
    forgetEntryInfo()
  })

  afterEach(() => {
    configureSession('admin')
    session.setPersistence('tab')
    session.clear()
    window.sessionStorage.clear()
    window.localStorage.clear()
    withLocks(undefined)
    vi.unstubAllGlobals()
  })

  it('signs in at the entry and tells who signed in, with the address', async () => {
    configureSession('portal')
    const posts = server({ me: () => ({ userId: 'u1', entry: 'portal', email: 'ann@example.com', emailVerified: false }) })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(result.current.entry).toBe('portal')

    await act(() => result.current.signIn('ann@example.com', 'secret'))
    await waitFor(() => expect(result.current.userId).toBe('u1'))
    expect(posts.find((p) => p.path === '/api/auth/login')?.body).toEqual({
      userName: 'ann@example.com',
      password: 'secret',
      entry: 'portal',
    })
    expect(result.current.email).toBe('ann@example.com')
    expect(result.current.emailVerified).toBe(false)
  })

  it('ends a session that belongs to another entry', async () => {
    configureSession('portal')
    server({ me: () => ({ userId: 'u1', entry: 'admin' }) })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(() => result.current.signIn('amy', 'secret'))
    await waitFor(() => expect(result.current.signedIn).toBe(false))
    expect(session.refreshToken()).toBeNull()
  })

  it('sees a verification after a forced refresh', async () => {
    configureSession('portal')
    let verified = false
    const posts = server({ me: () => ({ userId: 'u1', entry: 'portal', email: 'ann@example.com', emailVerified: verified }) })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(() => result.current.signIn('ann@example.com', 'secret'))
    await waitFor(() => expect(result.current.userId).toBe('u1'))
    expect(result.current.emailVerified).toBe(false)

    verified = true
    await act(() => result.current.refreshSession({ force: true }))
    await waitFor(() => expect(result.current.emailVerified).toBe(true))
    expect(posts.filter((p) => p.path === '/api/auth/refresh').map((p) => p.body)).toEqual([
      { refreshToken: 'r1', entry: 'portal' },
    ])
  })

  it("keeps the session where the entry says, restoring one kept on the device", async () => {
    configureSession('quiz')
    window.sessionStorage.setItem('jabiz.session.quiz.refresh', 'r0')
    server({ me: () => ({ userId: 'u1', entry: 'quiz' }), persistence: 'device' })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.userId).toBe('u1'))
    await waitFor(() => expect(session.persistence()).toBe('device'))
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r1')
    expect(window.sessionStorage.getItem('jabiz.session.quiz.refresh')).toBeNull()
  })

  it('reads no settings for the administration, which is never kept on the device', async () => {
    configureSession('admin')
    server({ me: () => ({ userId: 'u1', entry: 'admin' }), persistence: 'device' })
    const fetch = globalThis.fetch as ReturnType<typeof vi.fn>
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    expect(fetch.mock.calls.map(([input]) => urlOf(input))).toEqual([])
    expect(session.persistence()).toBe('tab')
  })

  it('treats a server without the setting (before phase 16m) as tab', async () => {
    configureSession('portal')
    server({ me: () => ({ userId: 'u1', entry: 'portal' }) })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await waitFor(() => expect(result.current.ready).toBe(true))
    await act(() => result.current.signIn('ann', 'secret'))
    await waitFor(() => expect(result.current.userId).toBe('u1'))
    expect(session.persistence()).toBe('tab')
    expect(window.sessionStorage.getItem('jabiz.session.portal.refresh')).toBe('r1')
  })
})
