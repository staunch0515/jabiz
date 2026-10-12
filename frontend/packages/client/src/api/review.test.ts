import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeLocks, json, urlOf, withLocks } from '../test/locks'

/**
 * The fixes of the review of phase 16c-1: refreshes that keep a session on passing failures, the lock only where tabs
 * share a token, a lock that cannot be had, a time limit, sign-out and moves under the lock, clearing both storages,
 * and a password change that sends the refresh token current at the time.
 */
async function load(entry: string) {
  vi.resetModules()
  const sessionModule = await import('./session')
  const client = await import('./client')
  const account = await import('../auth/account')
  sessionModule.configureSession(entry)
  return { session: sessionModule.session, configure: sessionModule.configureSession, client, account }
}

describe('session and refresh after review', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
  })

  afterEach(() => {
    window.sessionStorage.clear()
    window.localStorage.clear()
    withLocks(undefined)
    vi.unstubAllGlobals()
  })

  it('keeps the session when the refresh fails for a passing reason, and ends it when refused', async () => {
    const { session, client } = await load('portal')
    for (const status of [503, 429, 500]) {
      session.store('a', 'r1')
      vi.stubGlobal('fetch', vi.fn(async () => json(status, {})))
      expect(await client.refreshSession()).toBe(false)
      expect(session.refreshToken()).toBe('r1')
    }
    for (const status of [400, 401, 422]) {
      session.store('a', 'r1')
      vi.stubGlobal('fetch', vi.fn(async () => json(status, {})))
      expect(await client.refreshSession()).toBe(false)
      expect(session.refreshToken()).toBeNull()
    }
  })

  it('gives the refresh a time limit', async () => {
    const { session, client } = await load('portal')
    session.store('a', 'r1')
    const fetch = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
      async () => json(200, { accessToken: 'a2', refreshToken: 'r2' }))
    vi.stubGlobal('fetch', fetch)
    await client.refreshSession()
    expect(fetch.mock.calls[0][1]?.signal).toBeInstanceOf(AbortSignal)
  })

  it('takes the lock only for a session kept on the device', async () => {
    const locks = fakeLocks()
    withLocks(locks)
    const { session, client } = await load('portal')
    session.store('a', 'r1')
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { accessToken: 'a2', refreshToken: 'r2' })))
    await client.refreshSession()
    expect(locks.requested).toEqual([])
  })

  it('refreshes without the lock when the browser refuses it', async () => {
    withLocks({
      requested: [],
      request: () => {
        throw new DOMException('opaque origin', 'SecurityError')
      },
    } as unknown as ReturnType<typeof fakeLocks>)
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    const { session, client } = await load('quiz')
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { accessToken: 'a2', refreshToken: 'r2' })))
    expect(await client.refreshSession()).toBe(true)
    expect(session.refreshToken()).toBe('r2')
  })

  it("clears the entry's key from both storages", async () => {
    const { session } = await load('portal')
    window.localStorage.setItem('jabiz.session.portal.refresh', 'left-over')
    window.sessionStorage.setItem('jabiz.session.portal.refresh', 'r1')
    session.clear()
    expect(window.localStorage.getItem('jabiz.session.portal.refresh')).toBeNull()
    expect(window.sessionStorage.getItem('jabiz.session.portal.refresh')).toBeNull()
  })

  it('moves the token to where the entry keeps it under the lock', async () => {
    const locks = fakeLocks()
    withLocks(locks)
    const { session, account } = await load('quiz')
    session.store('a', 'r1')
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      urlOf(input).includes('/api/auth/entries/') ? json(200, { name: 'quiz', sessionPersistence: 'device' }) : json(404, {})))
    await account.loadSessionSettings()
    expect(session.persistence()).toBe('device')
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r1')
    expect(locks.requested).toEqual(['jabiz.refresh.quiz'])
  })

  it('signs out under the lock, ending the session another tab may just have refreshed', async () => {
    const locks = fakeLocks()
    withLocks(locks)
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    const { session, client } = await load('quiz')
    const posts: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      posts.push([new URL(urlOf(input), 'http://localhost').pathname,
        JSON.parse(input instanceof Request ? await input.text() : String(init?.body))])
      return new Response(null, { status: 204, headers: { 'Content-Length': '0' } })
    }))
    // Another tab holds the lock and rotates the token meanwhile.
    const other = locks.request('jabiz.refresh.quiz', async () => {
      await new Promise((resolve) => setTimeout(resolve, 5))
      window.localStorage.setItem('jabiz.session.quiz.refresh', 'r2')
    })
    await Promise.all([other, client.endSession()])
    expect(posts).toEqual([['/api/auth/logout', { refreshToken: 'r2' }]])
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBeNull()
    expect(session.accessToken()).toBeNull()
  })

  it('changes the password with the refresh token current when the request is sent', async () => {
    const { session, account } = await load('portal')
    session.store('expired', 'r1')
    const bodies: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = urlOf(input)
      if (url.endsWith('/api/auth/refresh')) return json(200, { accessToken: 'fresh', refreshToken: 'r2' })
      bodies.push(JSON.parse(String(init?.body)))
      return new Headers(init?.headers).get('Authorization') === 'Bearer fresh'
        ? new Response(null, { status: 204 })
        : json(401, {})
    }))
    await account.changePassword('old', 'new')
    expect(bodies).toEqual([
      { currentPassword: 'old', newPassword: 'new', refreshToken: 'r1' },
      { currentPassword: 'old', newPassword: 'new', refreshToken: 'r2' },
    ])
  })
})
