import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { nodeOf, treePathOf } from './api'
import { treeMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const BASICS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01'
const ADVANCED = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b02'
const OLD = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b03'
const KEY = '0123456789abcdef0123456789abcdef'
const requires = (id: string, requirement = 'PASSED') => JSON.stringify([{ requiredPublicationId: id, requirement }])
const node = (publicationId: string, name: string, more: Record<string, unknown>) => ({ publicationId, name,
  status: 'LIVE', startAt: '2026-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', live: true, isolated: false,
  prerequisites: '[]', lit: 0, answered: 0, inProgress: 0, unlocked: 0, locked: 0, ...more })
const TREE = [
  node(BASICS, 'Coffee basics', { lit: 2, answered: 1, inProgress: 1 }),
  node(ADVANCED, 'Roasting', { prerequisites: requires(BASICS), unlocked: 2, locked: 1 }),
  node(OLD, 'Last year', { status: 'ENDED', live: false, isolated: true, endAt: '2025-01-01T00:00:00Z', lit: 5 }),
]
const PEOPLE: Record<string, Record<string, unknown>[]> = {
  [`${ADVANCED}:UNLOCKED`]: [
    { userKey: KEY, userName: 'Mei', state: 'UNLOCKED', finishedAt: null, hasConversation: true },
    { userKey: 'fedcba9876543210fedcba9876543210', userName: null, state: 'UNLOCKED', finishedAt: null,
      hasConversation: false }],
  [`${ADVANCED}:LOCKED`]: [{ userKey: '11111111111111111111111111111111', userName: 'Bo', state: 'LOCKED',
    finishedAt: null, hasConversation: false }],
  [`${BASICS}:LIT`]: [{ userKey: KEY, userName: 'Mei', state: 'LIT', finishedAt: '2026-11-01T10:00:00Z',
    hasConversation: false }],
}

/** The server as the tree page sees it: an approved sponsor, three nodes, the people on them, one customer. */
function server(options: { tree?: Record<string, unknown>[] } = {}) {
  const asked: { path: string; params: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    const params = (body?.params ?? {}) as Record<string, unknown>
    asked.push({ path, params })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.tree':
        return json(200, { items: options.tree ?? TREE, offset: 0, limit: 500 })
      case '/api/queries/qb.sponsor.tree-people':
        return json(200, { items: PEOPLE[`${params.publicationId}:${params.state}`] ?? [], offset: 0, limit: 50 })
      case '/api/queries/qb.sponsor.tree-person':
        return json(200, { items: params.userKey === KEY ? [
          { publicationId: BASICS, userName: 'Mei', state: 'LIT' },
          { publicationId: ADVANCED, userName: 'Mei', state: 'UNLOCKED' },
          { publicationId: OLD, userName: 'Mei', state: 'CLOSED' }] : [], offset: 0, limit: 500 })
      default:
        return json(404, {})
    }
  })
  return { asked }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.replaceState(null, '', '/')
})

describe('the sponsor\'s tree (Q85)', () => {
  it('has every text in every language, and reads its addresses', () => {
    const english = keyPaths(treeMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(treeMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(nodeOf(`?node=${BASICS}`)).toBe(BASICS)
    expect(nodeOf('')).toBeNull()
    expect(treePathOf(BASICS)).toBe(`/tree?node=${BASICS}`)
  })

  it('draws the nodes with their counts, lists the people on a picked node, and hides what is ended', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', treePathOf(ADVANCED))
    render(<App />)
    const page = await screen.findByTestId('tree-page')
    const nodes = await within(page).findAllByTestId('tech-tree-node')
    expect(nodes.map((n) => n.getAttribute('data-id'))).toEqual([BASICS, OLD, ADVANCED])
    expect(within(nodes[0]).getByTestId('tree-counts')).toHaveTextContent(/✓ 2\s*◐ 1\s*▷ 0/)
    expect(within(nodes[2]).getByTestId('tree-counts')).toHaveTextContent(/✓ 0\s*◐ 0\s*▷ 2/)
    expect(nodes[2]).toHaveAttribute('aria-pressed', 'true')
    // The marked node's panel: its counts and, first, who lit it (nobody); then who could start it.
    const panel = within(page).getByTestId('tree-node-panel')
    expect(within(panel).getByTestId('tree-node-name')).toHaveTextContent('Roasting')
    expect(panel).toHaveTextContent('0 lit · 0 answered without passing · 0 in progress · 2 could start · 1 locked out')
    expect(await within(panel).findByTestId('tree-people-none')).toBeInTheDocument()
    await userEvent.click(within(panel).getByRole('radio', { name: 'Ready 2' }))
    const people = await within(panel).findAllByTestId('tree-person')
    expect(people).toHaveLength(2)
    expect(people[0]).toHaveTextContent('Mei')
    expect(people[0]).toHaveTextContent(KEY)
    expect(within(people[0]).getByTestId('tree-person-transactions'))
      .toHaveAttribute('href', `/transactions?publication=${ADVANCED}&user=${KEY}`)
    expect(within(people[0]).getByTestId('tree-person-conversation')).toBeInTheDocument()
    expect(people[1]).toHaveTextContent('A taker')
    expect(within(people[1]).queryByTestId('tree-person-conversation')).toBeNull()
    expect(asked.filter((a) => a.path === '/api/queries/qb.sponsor.tree-people').at(-1)!.params)
      .toMatchObject({ publicationId: ADVANCED, state: 'UNLOCKED' })
    // Locked out: a name, nothing of what they are missing.
    await userEvent.click(within(panel).getByRole('radio', { name: 'Locked 1' }))
    expect(await within(panel).findByTestId('tree-person')).toHaveTextContent('Bo')
    // Another node picked.
    await userEvent.click(nodes[0])
    expect(within(panel).getByTestId('tree-node-name')).toHaveTextContent('Coffee basics')
    expect(await within(panel).findByTestId('tree-person')).toHaveTextContent('Finished')
    // Ended quizzes hidden: the old one goes, with nothing else.
    await userEvent.click(within(page).getByTestId('tree-hide-ended'))
    await waitFor(() => expect(within(page).getAllByTestId('tech-tree-node')).toHaveLength(2))
    await userEvent.click(within(page).getByTestId('tree-hide-ended'))
    await userEvent.click(within(page).getByTestId('tree-hide-isolated'))
    await waitFor(() => expect(within(page).getAllByTestId('tech-tree-node')).toHaveLength(2))
    await expectAccessible()
  })

  it('lights the tree for one customer by their key, from the box or from a person', async () => {
    server()
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    const page = await screen.findByTestId('tree-page')
    await within(page).findAllByTestId('tech-tree-node')
    // A bad key is refused before asking; a key that is nobody's says so.
    await userEvent.type(within(page).getByTestId('tree-customer-key'), 'short')
    await userEvent.click(within(page).getByTestId('tree-customer-show'))
    expect(within(page).getByTestId('tree-customer-problem')).toHaveTextContent('32 letters and digits')
    await userEvent.clear(within(page).getByTestId('tree-customer-key'))
    await userEvent.type(within(page).getByTestId('tree-customer-key'), 'ffffffffffffffffffffffffffffffff')
    await userEvent.click(within(page).getByTestId('tree-customer-show'))
    await waitFor(() => expect(within(page).getByTestId('tree-customer-problem'))
      .toHaveTextContent('No customer of yours has this key.'))
    // A customer's key: the nodes take their states, the progress is theirs.
    await userEvent.clear(within(page).getByTestId('tree-customer-key'))
    await userEvent.type(within(page).getByTestId('tree-customer-key'), KEY.toUpperCase())
    await userEvent.click(within(page).getByTestId('tree-customer-show'))
    expect(await within(page).findByTestId('tree-customer-shown')).toHaveTextContent('Showing Mei · Lit 1 of 3')
    const nodes = within(page).getAllByTestId('tech-tree-node')
    expect(nodes.map((n) => n.getAttribute('data-state'))).toEqual(['LIT', 'CLOSED', 'UNLOCKED'])
    expect(within(page).queryAllByTestId('tree-counts')).toHaveLength(0)
    // Back to everyone.
    await userEvent.click(within(page).getByTestId('tree-customer-clear'))
    await waitFor(() => expect(within(page).getAllByTestId('tree-counts')).toHaveLength(3))
    // From a person on a node.
    await userEvent.click(within(page).getAllByTestId('tech-tree-node')[2])
    await userEvent.click(await within(page).findByRole('radio', { name: 'Ready 2' }))
    await userEvent.click((await within(page).findAllByTestId('tree-person-light'))[0])
    expect(await within(page).findByTestId('tree-customer-shown')).toHaveTextContent('Showing Mei')
    expect(within(page).getByTestId('tree-customer-key')).toHaveValue(KEY)
    await expectAccessible()
  })

  it('draws a certification required as a platform node without counts or a panel (Q86)', async () => {
    server({ tree: [
      node(BASICS, 'Coffee basics', { prerequisites: JSON.stringify([{ requiredPublicationId: null,
        certification: 'TERMS_APP_UNDERSTOOD', requirement: 'PASSED' }]), lit: 1 }),
      node(ADVANCED, 'Roasting', { prerequisites: JSON.stringify([{ requiredPublicationId: BASICS, requirement: 'PASSED' },
        { requiredPublicationId: null, certification: 'TERMS_APP_UNDERSTOOD', requirement: 'PASSED' }]) }),
    ] })
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    const page = await screen.findByTestId('tree-page')
    const nodes = await within(page).findAllByTestId('tech-tree-node')
    expect(nodes.map((n) => `${n.getAttribute('data-kind')}:${n.getAttribute('data-id')}`))
      .toEqual(['certification:cert:TERMS_APP_UNDERSTOOD', `publication:${BASICS}`, `publication:${ADVANCED}`])
    expect(nodes[0]).toHaveTextContent('Understood: Terms and Privacy Policy')
    expect(within(nodes[0]).queryByTestId('tree-counts')).toBeNull()
    expect(within(page).queryByTestId('tree-no-edges')).toBeNull()
    expect(within(page).getAllByTestId('tech-tree-edge')).toHaveLength(3)
    await expectAccessible()
    // The certification has no people of the sponsor's: picking it changes nothing.
    await userEvent.click(nodes[0])
    expect(within(page).getByTestId('tree-node-panel')).toHaveTextContent('Pick a node')
    await userEvent.click(nodes[1])
    expect(within(within(page).getByTestId('tree-node-panel')).getByTestId('tree-node-name')).toHaveTextContent('Coffee basics')
  })

  it('says when nothing leads anywhere, and when nothing was submitted', async () => {
    server({ tree: [node(BASICS, 'Coffee basics', { isolated: true })] })
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    expect(await screen.findByTestId('tree-no-edges')).toBeInTheDocument()
    expect(screen.getAllByTestId('tech-tree-node')).toHaveLength(1)
    cleanup()
    vi.restoreAllMocks()
    server({ tree: [] })
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    expect(await screen.findByTestId('tree-empty')).toBeInTheDocument()
  })
})
