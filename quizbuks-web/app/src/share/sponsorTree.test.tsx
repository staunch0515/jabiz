import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
const TREE = 't1'
const NAME = JSON.stringify({ en: 'Baking' })
/** The Sponsor's map: bread lit, sourdough after it. */
const NODES = [
  { treeId: TREE, ownerKind: 'SPONSOR', treeName: NAME, treeDescription: null, nodeId: 'n1',
    name: JSON.stringify({ en: 'Bread' }), description: null, seq: 1, refs: '[]', lit: true, unmet: '[]',
    condition: JSON.stringify({ op: 'ALL', items: [{ passed: true }] }) },
  { treeId: TREE, ownerKind: 'SPONSOR', treeName: NAME, treeDescription: null, nodeId: 'n2',
    name: JSON.stringify({ en: 'Sourdough' }), description: null, seq: 2, lit: false, unmet: '[]',
    refs: JSON.stringify([{ node: 'n1', tree: TREE, name: { en: 'Bread' }, lit: true }]),
    condition: JSON.stringify({ op: 'ALL', items: [{ node: 'n1' }, { passed: true }] }) },
]

/** The server for a taker on a Sponsor's page and "my maps" (F-S3c): the public templates and the map's reads. */
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
      case '/api/public/queries/qb.public.sponsor-page-tree':
        return json(200, { items: NODES.map((node) => Object.fromEntries(
          Object.entries(node).filter(([key]) => key !== 'lit' && key !== 'unmet'))), offset: 0, limit: 500 })
      case '/api/queries/qb.app.trees':
        return json(200, { items: [{ treeId: TREE, ownerKind: 'SPONSOR', ownerId: 's1', sponsorCompany: 'Acme',
          name: NAME, description: null, nodes: 2, litNodes: 1 }], offset: 0, limit: 10 })
      case '/api/queries/qb.app.tree':
        return json(200, { items: params.treeId === TREE ? NODES : [], offset: 0, limit: 500 })
      case '/api/queries/qb.app.feed':
        return json(200, { items: params.tree === TREE ? [{ ...LIVE, publicationId: 'pub2', name: 'Sourdough',
          treeId: TREE, treeNodeId: 'n2' }] : [], offset: 0, limit: 200 })
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

describe('a Sponsor\'s knowledge map on their page (F-S3c)', () => {
  it('shows the signed-in Taker\'s states and what they can answer, from their own reads', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    expect(await screen.findAllByTestId('sponsor-page-publication')).toHaveLength(2)
    const map = await screen.findByTestId('sponsor-page-map')
    expect(await within(map).findByTestId('map-title')).toHaveTextContent('Baking')
    expect(asked.find((q) => q.path === '/api/queries/qb.app.trees')?.params).toEqual({ handle: 'acme-bakery' })
    expect(asked.some((q) => q.path === '/api/public/queries/qb.public.sponsor-page-tree')).toBe(false)
    await userEvent.click(within(map).getByTestId('knowledge-map-view-list'))
    expect(within(map).getAllByTestId('knowledge-map-list-node').map((node) => node.getAttribute('data-state')))
      .toEqual(['LIT', 'CAN_ANSWER'])
    await expectAccessible()
  })

  it('shows anybody the names and conditions, asking them to sign in for the rest', async () => {
    const { asked } = server({ signedIn: false })
    session.clear()
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    const map = await screen.findByTestId('sponsor-page-map')
    expect(within(map).getByTestId('sponsor-page-map-sign-in')).toHaveTextContent('Sign in')
    await userEvent.click(within(map).getByTestId('knowledge-map-view-list'))
    expect(within(map).getAllByTestId('knowledge-map-list-node').map((node) => node.getAttribute('data-state')))
      .toEqual(['', ''])
    expect(asked.some((q) => q.path.startsWith('/api/queries/'))).toBe(false)
    await expectAccessible()
  })

  it('lists the quizzes on their own (the old levels are gone)', async () => {
    server()
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    expect(await screen.findAllByTestId('sponsor-page-publication')).toHaveLength(2)
    expect(screen.queryByTestId('sponsor-page-level')).toBeNull()
    expect(screen.queryByTestId('sponsor-page-tree-soon')).toBeNull()
  })
})

describe('my knowledge maps (F-S3c)', () => {
  it('lists the maps from the account with what the Taker lit, and opens one', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/account')
    render(<App />)
    await userEvent.click(await screen.findByTestId('account-menu-maps'))
    const item = await screen.findByTestId('maps-item')
    expect(item).toHaveTextContent('Baking')
    expect(item).toHaveTextContent('By Acme')
    expect(within(item).getByTestId('maps-progress')).toHaveTextContent('1 of 2 nodes lit')
    expect(asked.find((q) => q.path === '/api/queries/qb.app.trees')?.params).toEqual({})
    // The bottom navigation keeps the account marked.
    expect(screen.getByTestId('nav-account')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
    await userEvent.click(item)
    expect(await screen.findByTestId('map-title')).toHaveTextContent('Baking')
    expect(window.location.pathname).toBe('/maps/t1')
    // The feed's tree parameter says what can be started: the sourdough quiz from its node.
    expect(asked.find((q) => q.path === '/api/queries/qb.app.feed')?.params).toMatchObject({ tree: TREE })
    await userEvent.click(screen.getAllByTestId('knowledge-map-node')[1])
    expect(screen.getByTestId('map-publication')).toHaveAttribute('href', expect.stringContaining('/p/pub2'))
    await expectAccessible()
  })

  it('says a map the Taker may not see is not there', async () => {
    server()
    window.history.replaceState(null, '', '/maps/other')
    render(<App />)
    expect(await screen.findByText('This is not a map you can see.')).toBeInTheDocument()
    expect(screen.getByTestId('map-back')).toHaveAttribute('href', expect.stringContaining('/maps'))
  })
})
