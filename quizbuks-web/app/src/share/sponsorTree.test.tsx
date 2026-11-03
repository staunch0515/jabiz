import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
  maxReward: '500', rewardMethod: 'PLATFORM', prerequisites: '[]' }
const requires = (id: string, name: string, requirement = 'PASSED') =>
  JSON.stringify([{ requiredPublicationId: id, requirement, name }])
const TREE = [
  { publicationId: 'pub1', name: 'Bread quiz', kind: 'QUIZ', endAt: '2099-01-01T00:00:00Z', maxReward: '500',
    live: true, state: 'LIT', prerequisites: '[]' },
  { publicationId: 'pub2', name: 'Sourdough', kind: 'QUIZ', endAt: '2099-01-01T00:00:00Z', maxReward: '800',
    live: true, state: 'UNLOCKED', prerequisites: requires('pub1', 'Bread quiz') },
  { publicationId: 'pub3', name: 'Master baker', kind: 'QUIZ', endAt: '2099-01-01T00:00:00Z', maxReward: '1000',
    live: true, state: 'LOCKED', prerequisites: requires('pub2', 'Sourdough') },
  { publicationId: 'pub4', name: 'Bread survey', kind: 'SURVEY', endAt: '2099-01-01T00:00:00Z', maxReward: '10',
    live: true, state: 'UNLOCKED', prerequisites: requires('pub1', 'Bread quiz', 'ANSWERED') },
]

/** The server for a signed-in taker on a sponsor's page: the public templates, and the tree as they stand on it. */
function server(options: { tree?: Record<string, unknown>[]; signedIn?: boolean } = {}) {
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
        return json(200, { items: [LIVE, { ...LIVE, publicationId: 'pub2', name: 'Sourdough',
          prerequisites: requires('pub1', 'Bread quiz') }], offset: 0, limit: 50 })
      case '/api/public/queries/qb.public.sponsor-page-products':
        return json(200, { items: [], offset: 0, limit: 50 })
      case '/api/queries/qb.app.sponsor-tree':
        return json(200, { items: options.tree ?? TREE, offset: 0, limit: 200 })
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

describe('a sponsor\'s tree on their page (Q85)', () => {
  it('draws the tree as the taker stands on it, with the progress and the next step', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/s/acme-bakery?node=pub2')
    render(<App />)
    const tree = await screen.findByTestId('sponsor-page-tree')
    expect(within(tree).getByTestId('tree-progress')).toHaveTextContent('Lit 1 of 4 · Next: Sourdough, Bread survey')
    const nodes = within(tree).getAllByTestId('tech-tree-node')
    expect(nodes.map((node) => [node.getAttribute('data-id'), node.getAttribute('data-state')])).toEqual([
      ['pub1', 'LIT'], ['pub4', 'UNLOCKED'], ['pub2', 'UNLOCKED'], ['pub3', 'LOCKED']])
    expect(nodes[0]).toHaveAttribute('href', '/p/pub1')
    expect(nodes[3]).toHaveAccessibleName('Master baker. Locked. After: Sourdough')
    // The marked node, the edges (a dashed one for "answered is enough"), the legend.
    expect(nodes[2]).toHaveClass('ring-2')
    const edges = within(tree).getAllByTestId('tech-tree-edge')
    expect(edges.map((edge) => `${edge.getAttribute('data-from')}>${edge.getAttribute('data-to')}`).sort())
      .toEqual(['pub1>pub2', 'pub1>pub4', 'pub2>pub3'])
    expect(edges.find((edge) => edge.getAttribute('data-to') === 'pub4')).toHaveAttribute('stroke-dasharray')
    expect(within(tree).getByTestId('tree-legend')).toHaveTextContent('Lit')
    // Read with the caller's time and language.
    const call = asked.find((a) => a.path === '/api/queries/qb.app.sponsor-tree')!
    expect(call.params).toMatchObject({ handle: 'acme-bakery', lang: 'en' })
    expect(call.params.at).toBeTruthy()
    // Zoom.
    await userEvent.click(within(tree).getByTestId('tech-tree-zoom-in'))
    expect(within(tree).getByTestId('tech-tree-svg')).toHaveAttribute('width', String(Math.round(
      Number(within(tree).getByTestId('tech-tree-svg').getAttribute('viewBox')!.split(' ')[2]) * 1.25)))
    // The list is a tap away, and is the levels as before.
    await userEvent.click(screen.getByRole('radio', { name: 'List' }))
    expect(screen.queryByTestId('sponsor-page-tree')).toBeNull()
    expect(await screen.findAllByTestId('sponsor-page-level')).toHaveLength(2)
    await expectAccessible()
  })

  it('shows the list alone to a visitor, and when the tree is empty', async () => {
    server({ signedIn: false })
    session.clear()
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    expect(await screen.findAllByTestId('sponsor-page-level')).toHaveLength(2)
    expect(screen.queryByTestId('sponsor-page-view')).toBeNull()
    expect(screen.queryByTestId('sponsor-page-tree')).toBeNull()
    cleanup()
    vi.restoreAllMocks()
    server({ tree: [] })
    session.store('access', 'refresh')
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    expect(await screen.findAllByTestId('sponsor-page-level')).toHaveLength(2)
    await waitFor(() => expect(screen.queryByTestId('sponsor-page-view')).toBeNull())
  })

  it('folds a large tree by level and opens a level', async () => {
    const many = Array.from({ length: 70 }, (_, i) => ({ publicationId: `n${i}`, name: `Quiz ${i}`, kind: 'QUIZ',
      endAt: '2099-01-01T00:00:00Z', maxReward: '1', live: true, state: i < 30 ? 'LIT' : 'LOCKED',
      prerequisites: i < 35 ? '[]' : requires(`n${i - 35}`, `Quiz ${i - 35}`) }))
    server({ tree: many })
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    const tree = await screen.findByTestId('sponsor-page-tree')
    const folded = within(tree).getAllByTestId('tech-tree-folded')
    expect(folded.map((f) => f.getAttribute('data-level'))).toEqual(['1', '2'])
    expect(folded[0]).toHaveTextContent('35 quizzes')
    expect(within(tree).queryAllByTestId('tech-tree-node')).toHaveLength(0)
    expect(within(tree).getAllByTestId('tech-tree-edge')).toHaveLength(1)
    await userEvent.click(folded[1])
    expect(within(tree).getAllByTestId('tech-tree-node')).toHaveLength(35)
    expect(within(tree).getAllByTestId('tech-tree-folded')).toHaveLength(1)
    expect(within(tree).getByTestId('tech-tree-folded-hint')).toBeInTheDocument()
  })
})
