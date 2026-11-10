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
    answeredUnlit: 6, countedOn: '2026-11-09', runningBound: 1, condition: JSON.stringify({ op: 'ALL', items: [{ passed: true }] }) },
  { nodeId: 'n2', name: JSON.stringify({ en: 'Sourdough' }), description: null, seq: 2, inEffect: true, litCount: null,
    runningBound: 0, condition: JSON.stringify({ op: 'ALL', items: [{ node: BASICS }, { passed: true }] }) },
]

const TREE = 't1'
const VERSIONS = [
  { versionId: 'v2', versionNo: 2, status: 'REJECTED', name: JSON.stringify({ en: 'Baking' }), submittedAt: null,
    decidedAt: null, rejectReason: 'Name the node better', editedAt: null },
  { versionId: 'v1', versionNo: 1, status: 'ACTIVE', name: JSON.stringify({ en: 'Baking' }), submittedAt: null,
    decidedAt: null, rejectReason: null, editedAt: null },
]
const output = (extra: Record<string, unknown>) => json(200, { output: { treeId: TREE, versionId: 'v2', versionNo: 2,
  status: 'DRAFT', revision: 4, nodeIds: {}, problems: [], approval: null, approvalRequestId: null, ...extra } })

/** The server as the map page sees it: an approved Sponsor with one map in effect and a version sent back. */
function server(maps: unknown[] = [MAP], processes: Record<string, (input: Record<string, unknown>) => Response> = {}) {
  const asked: string[] = []
  const sent: { name: string; input: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    asked.push(path)
    const process = /^\/api\/processes\/([^/]+)\//.exec(path)
    if (process && request.method === 'POST') {
      const input = await request.clone().json() as Record<string, unknown>
      sent.push({ name: process[1], input })
      const answer = processes[process[1]]
      return answer ? answer(input) : json(404, {})
    }
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
      case '/api/queries/qb.sponsor.tree-versions':
        return json(200, { items: VERSIONS, offset: 0, limit: 100 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.conversations-unread':
        return json(200, { items: [{ unread: 0 }], offset: 0, limit: 1 })
      default:
        return json(404, {})
    }
  })
  return { asked, sent }
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
    expect(within(map).getByTestId('knowledge-map-unlit-count')).toHaveTextContent('6 Takers answered and have not lit it (as of 2026-11-09)')
    await expectAccessible()
  })

  it('says when there is no map yet', async () => {
    server([])
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    expect(await screen.findByTestId('tree-none')).toHaveTextContent('no knowledge map')
    await expectAccessible()
  })

  it('lists the versions, and shows an older one', async () => {
    server()
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    const map = await screen.findByTestId('tree-map')
    await userEvent.click(within(map).getByText('Versions'))
    const rows = await within(map).findAllByTestId('tree-history-version')
    expect(rows.map((row) => row.textContent)).toEqual([expect.stringContaining('Version 2: Sent back'),
      expect.stringContaining('Version 1: In effect')])
    await expectAccessible()
  })

  it('creates a map for a Sponsor without one', async () => {
    const { sent } = server([], { QB_TREE_CREATE: () => output({ status: 'DRAFT' }) })
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    await userEvent.click(await screen.findByTestId('tree-create'))
    await vi.waitFor(() => expect(sent).toEqual([{ name: 'QB_TREE_CREATE',
      input: { ownerKind: 'SPONSOR', name: { en: 'My knowledge map' } } }]))
  })
})

describe('the Sponsor\'s map editor (F-S3d)', () => {
  const open = async () => {
    window.history.replaceState(null, '', '/tree')
    render(<App />)
    await userEvent.click(await screen.findByTestId('tree-edit'))
    return screen.findByTestId('tree-editor')
  }

  it('saves the whole draft with new nodes by key, shows the problems by node, and submits once clean', async () => {
    let saves = 0
    const { sent } = server([MAP], {
      QB_TREE_DRAFT_SAVE: () => ++saves === 1
        ? output({ nodeIds: { 'new-3': 'n3' }, problems: [{ nodeId: 'n3', code: 'QB_TREE_EMPTY_GROUP', params: {} }] })
        : output({ revision: 5 }),
      QB_TREE_SUBMIT: () => output({ status: 'REVIEW', revision: 6 }),
    })
    const editor = await open()
    // The version sent back says why.
    expect(within(editor).getByTestId('tree-editor-rejected')).toHaveTextContent('Name the node better')
    await userEvent.click(within(editor).getByTestId('tree-editor-add'))
    const nodes = within(editor).getAllByTestId('tree-editor-node')
    expect(nodes).toHaveLength(3)
    // The new node's pass is its only one: no second pass is offered; before answering is not offered on a pass.
    expect(within(nodes[2]).queryByTestId('condition-add-pass')).toBeNull()
    expect(within(nodes[2]).queryByTestId('condition-before-answer')).toBeNull()
    // Make its group empty (the server names the problem).
    await userEvent.click(within(nodes[2]).getByTestId('condition-remove'))
    await userEvent.click(within(editor).getByTestId('tree-editor-save'))
    await vi.waitFor(() => expect(sent.filter((s) => s.name === 'QB_TREE_DRAFT_SAVE')).toHaveLength(1))
    const first = sent[0].input as { baseRevision: number; nodes: { nodeId: string | null; key: string | null;
      condition: unknown }[] }
    expect(first.baseRevision).toBe(3)
    expect(first.nodes.map((n) => [n.nodeId, n.key])).toEqual([[BASICS, null], ['n2', null], [null, 'new-3']])
    expect(first.nodes[2].condition).toEqual({ op: 'ALL', items: [] })
    expect(await within(editor).findByTestId('tree-editor-node-problems')).toHaveTextContent('at least one item')
    expect(within(editor).getByTestId('tree-editor-submit')).toBeDisabled()
    await expectAccessible()

    // Fixed and saved again (by id now): it can be submitted.
    const third = within(editor).getAllByTestId('tree-editor-node')[2]
    await userEvent.click(within(third).getByTestId('condition-add-pass'))
    await userEvent.click(within(editor).getByTestId('tree-editor-save'))
    await vi.waitFor(() => expect(sent.filter((s) => s.name === 'QB_TREE_DRAFT_SAVE')).toHaveLength(2))
    const second = sent[1].input as { baseRevision: number; nodes: { nodeId: string | null }[] }
    expect(second.baseRevision).toBe(4)
    expect(second.nodes[2].nodeId).toBe('n3')
    await vi.waitFor(() => expect(within(editor).getByTestId('tree-editor-submit')).toBeEnabled())
    await userEvent.click(within(editor).getByTestId('tree-editor-submit'))
    await vi.waitFor(() => expect(sent.at(-1)).toEqual({ name: 'QB_TREE_SUBMIT', input: { treeId: TREE,
      baseRevision: 5 } }))
    expect(await within(editor).findByTestId('tree-editor-in-review')).toBeInTheDocument()
    expect(within(editor).getByTestId('tree-editor-withdraw')).toBeEnabled()
  })

  it('keeps "before answering" to the outermost "all", and a node in use cannot be removed', async () => {
    server()
    const editor = await open()
    const [basics, sourdough] = within(editor).getAllByTestId('tree-editor-node')
    // Bread has a publication running.
    expect(within(basics).getByTestId('tree-editor-remove')).toBeDisabled()
    expect(within(basics).getByTestId('tree-editor-in-use')).toHaveTextContent('1 publications')
    // Sourdough names Bread inside its outermost "all": before answering is offered there.
    expect(within(sourdough).getByTestId('condition-before-answer')).toBeInTheDocument()
    await userEvent.click(within(sourdough).getByTestId('condition-add-group'))
    const group = within(sourdough).getByTestId('condition-group')
    expect(within(group).queryByTestId('condition-before-answer')).toBeNull()
    expect(within(group).queryByTestId('condition-add-group')).toBeNull()
  })

  it('says when the map was changed elsewhere, and sends nothing more', async () => {
    const { sent } = server([MAP], { QB_TREE_DRAFT_SAVE: () => json(409, { status: 409, title: 'Conflict',
      violations: [{ ruleCode: 'QB_TREE_CHANGED', message: 'The map was changed.' }] }) })
    const editor = await open()
    await userEvent.click(within(editor).getByTestId('tree-editor-save'))
    expect(await within(editor).findByTestId('tree-editor-stale')).toBeInTheDocument()
    expect(within(editor).getByTestId('tree-editor-save')).toBeDisabled()
    expect(sent).toHaveLength(1)
  })
})
