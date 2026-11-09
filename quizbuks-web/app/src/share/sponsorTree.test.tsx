import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }
const PAGE = { handle: 'acme-bakery', language: 'en', headline: 'Fresh bread daily', story: null, logo: null,
  cover: null, website: null, company: 'Acme' }
const LIVE = { publicationId: 'pub1', name: 'Bread quiz', quizTitle: 'Bread', kind: 'QUIZ',
  startAt: '2026-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', timeLimitSec: 300, questionCount: 5,
  maxReward: '500', rewardMethod: 'PLATFORM' }
/** The server for a taker on a sponsor's page: the public templates (the old tree's is gone since F-S2c2). */
function server(options: { signedIn?: boolean } = {}) {
  const asked: { path: string; params: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    const params = (body?.params ?? {}) as Record<string, unknown>
    asked.push({ path, params })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return options.signedIn === false ? json(401, {}) : json(200, { userId: 'u1', displayName: 'Mei',
          email: 'mei@example.com', roles: ['QB_TAKER'], permissions: ['qb.me'], emailVerified: true,
          idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: [], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/public/queries/qb.public.sponsor-page':
        return json(200, { items: [PAGE], offset: 0, limit: 1 })
      case '/api/public/queries/qb.public.sponsor-page-publications':
        return json(200, { items: [LIVE, { ...LIVE, publicationId: 'pub2', name: 'Sourdough' }], offset: 0,
          limit: 50 })
      case '/api/public/queries/qb.public.sponsor-page-products':
        return json(200, { items: [], offset: 0, limit: 50 })
      default:
        return json(404, {})
    }
  })
  return { asked }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('a Sponsor\'s tree on their page (Q85, being updated since F-S2c2)', () => {
  it('lists the quizzes and says the tree is coming soon, signed in or not, reading no tree', async () => {
    for (const signedIn of [true, false]) {
      const { asked } = server({ signedIn })
      if (!signedIn) session.clear()
      window.history.replaceState(null, '', '/s/acme-bakery?node=pub2')
      render(<App />)
      expect(await screen.findAllByTestId('sponsor-page-publication')).toHaveLength(2)
      // One level (no template names what a quiz requires any more): a plain list.
      expect(screen.getAllByTestId('sponsor-page-level')).toHaveLength(1)
      expect(screen.getByTestId('sponsor-page-tree-soon')).toHaveTextContent('Coming soon')
      expect(screen.queryByTestId('sponsor-page-view')).toBeNull()
      expect(asked.some((q) => q.path === '/api/queries/qb.app.sponsor-tree')).toBe(false)
      if (signedIn) await expectAccessible()
      cleanup()
      vi.restoreAllMocks()
    }
  })
})
