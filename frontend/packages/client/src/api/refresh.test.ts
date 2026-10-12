import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeLocks, json, urlOf, withLocks } from '../test/locks'

/**
 * The refresh of a session (docs/design/12-frontend.md section 4; decision D43 item 2). A "tab" is a fresh copy of the
 * client's modules: its own access token and its own refresh in flight, sharing the browser's storage and locks.
 */
async function openTab(entry: string) {
  vi.resetModules()
  const sessionModule = await import('./session')
  const client = await import('./client')
  sessionModule.configureSession(entry)
  return { session: sessionModule.session, client }
}

/** A server that rotates refresh tokens: each works once; a second use answers 401 (and would revoke the family). */
function rotatingServer(first: string) {
  let current = first
  let issued = 0
  const sent: { refreshToken: string; entry?: string }[] = []
  const reused: string[] = []
  const fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = urlOf(input)
    if (url.endsWith('/api/auth/refresh')) {
      const body = JSON.parse(String(init?.body ?? (input instanceof Request ? await input.text() : '{}')))
      sent.push(body)
      // Answer after a moment, so that the tabs' refreshes overlap.
      await new Promise((resolve) => setTimeout(resolve, 5))
      if (body.refreshToken !== current) {
        reused.push(body.refreshToken)
        return json(401, { violations: [{ ruleCode: 'REFRESH_INVALID' }] })
      }
      issued += 1
      current = `r${issued + 1}`
      return json(200, { accessToken: `a${issued}`, refreshToken: current })
    }
    return json(404, {})
  })
  return { fetch, sent, reused }
}

describe('refreshSession', () => {
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

  it("sends the entry along, and ends a session the server refuses", async () => {
    const { session, client } = await openTab('portal')
    session.store('a0', 'r1')
    const server = rotatingServer('r1')
    vi.stubGlobal('fetch', server.fetch)
    expect(await client.refreshSession()).toBe(true)
    expect(server.sent).toEqual([{ refreshToken: 'r1', entry: 'portal' }])
    expect(session.refreshToken()).toBe('r2')

    window.sessionStorage.setItem('jabiz.session.portal.refresh', 'stolen-or-old')
    expect(await client.refreshSession()).toBe(false)
    expect(session.refreshToken()).toBeNull()
    expect(session.accessToken()).toBeNull()
  })

  it('lets two tabs of a session kept on the device refresh at once without using a token twice', async () => {
    const locks = fakeLocks()
    withLocks(locks)
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    const tabA = await openTab('quiz')
    const tabB = await openTab('quiz')
    expect(tabA.session.persistence()).toBe('device')
    const server = rotatingServer('r1')
    vi.stubGlobal('fetch', server.fetch)

    const [a, b] = await Promise.all([tabA.client.refreshSession(), tabB.client.refreshSession()])

    expect([a, b]).toEqual([true, true])
    // Each read the token once it held the lock: the second used the token the first had just received.
    expect(server.sent.map((s) => s.refreshToken)).toEqual(['r1', 'r2'])
    expect(server.reused).toEqual([])
    expect(locks.requested).toEqual(['jabiz.refresh.quiz', 'jabiz.refresh.quiz'])
    expect(tabA.session.accessToken()).not.toBeNull()
    expect(tabB.session.accessToken()).not.toBeNull()
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r3')
  })

  it("reads the shared copy under the lock, not a tab's late view of localStorage", async () => {
    withLocks(fakeLocks())
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    // IndexedDB in the browser: every tab reads it alike.
    const copies = new Map<string, string>()
    const shared = {
      get: vi.fn(async (key: string) => copies.get(key)),
      put: vi.fn(async (key: string, value: string) => {
        copies.set(key, value)
      }),
      delete: vi.fn(async (key: string) => void copies.delete(key)),
    }
    // Each tab takes the same shared copy (one IndexedDB per origin).
    const tabA = await openTab('quiz')
    ;(await import('./session')).setSharedTokens(shared)
    const tabB = await openTab('quiz')
    ;(await import('./session')).setSharedTokens(shared)
    const server = rotatingServer('r1')
    vi.stubGlobal('fetch', server.fetch)

    // No copy yet (a token stored before it existed): localStorage's, then the copy follows.
    expect(await tabA.client.refreshSession()).toBe(true)
    expect(copies.get('jabiz.session.quiz.refresh')).toBe('r2')
    // Chromium updates a tab's view of localStorage from other tabs a little later: tab B still sees r1 there.
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    expect(await tabB.client.refreshSession()).toBe(true)
    expect(server.sent.map((s) => s.refreshToken)).toEqual(['r1', 'r2'])
    expect(server.reused).toEqual([])
    expect(window.localStorage.getItem('jabiz.session.quiz.refresh')).toBe('r3')
    expect(tabA.session.persistence()).toBe('device')

    // Signing out leaves a tombstone, so that nobody falls back to a stale localStorage.
    tabB.session.clear()
    await vi.waitFor(() => expect(copies.get('jabiz.session.quiz.refresh')).toBe(''))
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r3')
    expect(await tabB.session.lockedRefreshToken()).toBeNull()
  })

  it('never prefers a shared copy that could not be updated over the token in localStorage', async () => {
    withLocks(fakeLocks())
    window.localStorage.setItem('jabiz.session.quiz.refresh', 'r1')
    // An older copy is there, and writing the next one fails (quota, private mode, a closed database).
    const copies = new Map([['jabiz.session.quiz.refresh', 'r1']])
    let failing = true
    const shared = {
      get: vi.fn(async (key: string) => copies.get(key)),
      put: vi.fn(async (key: string, value: string) => {
        if (failing) throw new Error('QuotaExceededError')
        copies.set(key, value)
      }),
      delete: vi.fn(async (key: string) => void copies.delete(key)),
    }
    const tabA = await openTab('quiz')
    ;(await import('./session')).setSharedTokens(shared)
    const server = rotatingServer('r1')
    vi.stubGlobal('fetch', server.fetch)

    expect(await tabA.client.refreshSession()).toBe(true)
    // The stale copy is gone: the next turn reads localStorage, not the rotated r1.
    expect(copies.has('jabiz.session.quiz.refresh')).toBe(false)
    expect(await tabA.session.lockedRefreshToken()).toBe('r2')
    expect(await tabA.client.refreshSession({ force: true })).toBe(true)
    expect(server.sent.map((s) => s.refreshToken)).toEqual(['r1', 'r2'])
    expect(server.reused).toEqual([])

    // A sign-out marker left behind, and the next sign-in's copy cannot be written: the new token is not lost.
    failing = false
    tabA.session.clear()
    await vi.waitFor(() => expect(copies.get('jabiz.session.quiz.refresh')).toBe(''))
    failing = true
    tabA.session.store('a9', 'r9')
    await vi.waitFor(() => expect(copies.has('jabiz.session.quiz.refresh')).toBe(false))
    expect(await tabA.session.lockedRefreshToken()).toBe('r9')
  })

  it('shares one refresh among the calls of a tab, unless one is forced', async () => {
    withLocks(fakeLocks())
    const { session, client } = await openTab('portal')
    session.store('a0', 'r1')
    const server = rotatingServer('r1')
    vi.stubGlobal('fetch', server.fetch)

    await Promise.all([client.refreshSession(), client.refreshSession()])
    expect(server.sent).toHaveLength(1)

    // A forced refresh does not count one already under way: it waits for it and then refreshes again.
    const under = client.refreshSession()
    const forced = client.refreshSession({ force: true })
    await Promise.all([under, forced])
    expect(server.sent.map((s) => s.refreshToken)).toEqual(['r1', 'r2', 'r3'])
    expect(server.reused).toEqual([])
  })
})
