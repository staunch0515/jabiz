import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import './messages'
import { certificationNodeId, certificationOfNode, edgesOf } from './qb/tree'
import { prerequisitesOf } from './qb/play'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }
const ROUND = '01a0f4fe-4660-7000-8000-0000000000r1'
const UNDERSTANDING = [
  { certification: 'TERMS_APP_UNDERSTOOD', enabled: true, heldUntil: null, roundPublicationId: ROUND, platformHandle: 'quizbuks-hq' },
  { certification: 'CONTEST_RULES_UNDERSTOOD', enabled: true, heldUntil: '2027-11-01T00:00:00Z', roundPublicationId: null,
    platformHandle: 'quizbuks-hq' },
  { certification: 'TERMS_SPONSOR_UNDERSTOOD', enabled: true, heldUntil: null, roundPublicationId: ROUND, platformHandle: 'quizbuks-hq' },
]
const PAGE = { handle: 'acme-bakery', language: 'en', headline: 'Fresh bread daily', story: null, logo: null,
  cover: null, website: null, company: 'Acme' }
const TREE = [
  { publicationId: 'pub1', name: 'Bread quiz', kind: 'QUIZ', endAt: '2099-01-01T00:00:00Z', maxReward: '500', live: true,
    state: 'LOCKED', prerequisites: JSON.stringify([{ requiredPublicationId: null, certification: 'CONTEST_RULES_UNDERSTOOD',
      requirement: 'PASSED', name: null, held: false, roundPublicationId: ROUND }]) },
]

function server(options: { understanding?: Record<string, unknown>[]; held?: Record<string, unknown>[] } = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 })
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
      case '/api/queries/qb.app.understanding':
        return json(200, { items: options.understanding ?? UNDERSTANDING, offset: 0, limit: 10 })
      case '/api/processes/QB_MY_CERTIFICATIONS/latest':
        return json(200, { output: { held: options.held ?? [] } })
      case '/api/public/queries/qb.public.sponsor-page':
        return json(200, { items: [PAGE], offset: 0, limit: 1 })
      case '/api/public/queries/qb.public.sponsor-page-publications':
        return json(200, { items: [], offset: 0, limit: 50 })
      case '/api/public/queries/qb.public.sponsor-page-products':
        return json(200, { items: [], offset: 0, limit: 50 })
      case '/api/queries/qb.app.sponsor-tree':
        return json(200, { items: TREE, offset: 0, limit: 200 })
      default:
        if (path.startsWith('/api/queries/qb.app.feed')) return json(200, { items: [], offset: 0, limit: 20 })
        return json(200, { items: [], offset: 0, limit: 20 })
    }
  })
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

describe('the understanding quizzes (Q86)', () => {
  it('parses a certification required, as an edge to a node of its own', () => {
    const json = JSON.stringify([{ requiredPublicationId: null, certification: 'REFERRER', requirement: 'PASSED',
      name: null, held: true, roundPublicationId: ROUND }, { requiredPublicationId: 'p1', requirement: 'ANSWERED', name: 'B' }])
    expect(edgesOf(json)).toEqual([{ id: certificationNodeId('REFERRER'), requirement: 'PASSED', certification: 'REFERRER',
      certificationNames: null },
      { id: 'p1', requirement: 'ANSWERED' }])
    expect(certificationOfNode(certificationNodeId('REFERRER'))).toBe('REFERRER')
    expect(certificationOfNode('p1')).toBeNull()
    expect(prerequisitesOf(json)).toMatchObject([
      { requiredPublicationId: null, certification: 'REFERRER', met: true, roundPublicationId: ROUND },
      { requiredPublicationId: 'p1', certification: null, requirement: 'ANSWERED', met: false, roundPublicationId: null }])
  })

  it('offers the open quiz on the home, not the one held, and nothing when switched off', async () => {
    server()
    render(<App />)
    const cards = await screen.findByTestId('home-understanding')
    const card = within(cards).getAllByTestId('understanding-card')
    expect(card).toHaveLength(1)
    expect(card[0]).toHaveAttribute('data-certification', 'TERMS_APP_UNDERSTOOD')
    expect(card[0]).toHaveTextContent('Understood: Terms and Privacy Policy')
    expect(within(card[0]).getByTestId('understanding-start')).toHaveAttribute('href', `/p/${ROUND}`)
    await expectAccessible()
    cleanup()
    vi.restoreAllMocks()
    server({ understanding: UNDERSTANDING.map((u) => ({ ...u, enabled: false })) })
    render(<App />)
    await screen.findByTestId('home')
    await waitFor(() => expect(screen.queryByTestId('home-understanding')).toBeNull())
  })

  it('shows the held and the open quizzes in the account, and the way to the platform\'s tree', async () => {
    server()
    window.history.replaceState(null, '', '/account/certificates')
    render(<App />)
    const cards = await screen.findByTestId('account-understanding')
    expect(within(cards).getAllByTestId('understanding-card').map((c) => c.getAttribute('data-held')))
      .toEqual(['false', 'true'])
    expect(within(cards).getAllByTestId('understanding-card')[1]).toHaveTextContent('Passed · until')
    expect(await screen.findByTestId('platform-tree')).toHaveAttribute('href', '/s/quizbuks-hq')
    await expectAccessible()
  })

  it('draws a certification required as a platform node leading to the platform\'s tree', async () => {
    server()
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    const tree = await screen.findByTestId('sponsor-page-tree')
    const nodes = within(tree).getAllByTestId('tech-tree-node')
    expect(nodes.map((n) => [n.getAttribute('data-kind'), n.getAttribute('data-state')]))
      .toEqual([['certification', 'LOCKED'], ['publication', 'LOCKED']])
    expect(nodes[0]).toHaveTextContent('Understood: Contest Rules')
    expect(nodes[0]).toHaveAttribute('href', '/s/quizbuks-hq')
    expect(within(tree).getAllByTestId('tech-tree-edge')).toHaveLength(1)
    expect(within(tree).getByTestId('tree-progress')).toHaveTextContent('Lit 0 of 1')
    await expectAccessible()
  })
})
