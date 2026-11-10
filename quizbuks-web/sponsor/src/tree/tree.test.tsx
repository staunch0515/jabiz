import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { treePathOf } from './api'
import { treeMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const BASICS = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4b01'
const MAP = { treeId: 't1', status: 'ACTIVE', revision: 3, createdAt: '2026-11-01T00:00:00Z', currentVersionId: 'v1',
  currentVersionNo: 1, name: JSON.stringify({ en: 'Baking' }), description: null, openVersionId: 'v2',
  openVersionNo: 2, openStatus: 'REJECTED', rejectReason: 'Name the node better', submittedAt: null, editedAt: null,
  nodes: 2, runningBound: 1 }
const NODES = [
  { nodeId: BASICS, name: JSON.stringify({ en: 'Bread' }), description: null, seq: 1, inEffect: true, litCount: 12,
    runningBound: 1, condition: JSON.stringify({ op: 'ALL', items: [{ passed: true }] }) },
  { nodeId: 'n2', name: JSON.stringify({ en: 'Sourdough' }), description: null, seq: 2, inEffect: true, litCount: null,
    runningBound: 0, condition: JSON.stringify({ op: 'ALL', items: [{ node: BASICS }, { passed: true }] }) },
]

const TREE = 't1'

/** The server as the map page sees it: an approved Sponsor with one map in effect and a version sent back. */
function server(maps: unknown[] = [MAP]) {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    asked.push(path)
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
      case '/api/queries/qb.sponsor.trees':
        return json(200, { items: maps, offset: 0, limit: 50 })
      case '/api/queries/qb.sponsor.tree':
        return json(200, { items: NODES, offset: 0, limit: 500 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
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

describe('the Sponsor\'s knowledge map (F-S3c)', () => {
  it('has every text in every language, and its address', () => {
    const english = keyPaths(treeMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(treeMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(treePathOf(BASICS)).toBe(`/publications/map?node=${BASICS}`)
    expect(treePathOf(null)).toBe('/publications/map')
  })

  it('draws the map in effect with the lit counts, and says the open version was sent back', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', treePathOf(BASICS))
    render(<App />)
    const map = await screen.findByTestId('tree-map')
    expect(map).toHaveAttribute('data-tree', TREE)
    expect(within(map).getByTestId('tree-version')).toHaveTextContent('Version 1 in effect')
    expect(within(map).getByTestId('tree-open')).toHaveTextContent('Version 2 was sent back: Name the node better')
    expect(await within(map).findByTestId('knowledge-map')).toBeInTheDocument()
    expect(asked).toContain('/api/queries/qb.sponsor.tree')
    await userEvent.click(within(map).getAllByTestId('knowledge-map-node')[0])
    expect(within(map).getByTestId('knowledge-map-lit-count')).toHaveTextContent('12')
    await expectAccessible()
  })

  it('says when there is no map yet', async () => {
    server([])
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    expect(await screen.findByTestId('tree-none')).toHaveTextContent('no knowledge map')
    await expectAccessible()
  })
})
