import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import TreeReviewPage from './TreeReviewPage'
import TreesPage from './TreesPage'
import TreeSupportPage from './TreeSupportPage'
import { levelsOf } from './tree-draft'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  templates: new Map<string, Row[] | Error>(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  get: vi.fn(),
  decided: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: ({ requestId, onDecided }: { requestId: string; onDecided?: (status: string) => void }) => (
      <button type="button" data-testid="approval-panel" data-request={requestId}
        onClick={() => {
          server.decided(requestId)
          onDecided?.('APPROVED')
        }}>
        decide
      </button>
    ),
  }
})

const TREE_ID = '0192f0a0-0000-7000-8000-00000000f001'
const EFFECT_ID = '0192f0a0-0000-7000-8000-00000000f0e1'
const OPEN_ID = '0192f0a0-0000-7000-8000-00000000f0e2'
const SPONSOR_ID = '0192f0a0-0000-7000-8000-0000000005a1'
const TAKER_ID = '0192f0a0-0000-7000-8000-0000000007a1'
const LIT_ID = '0192f0a0-0000-7000-8000-00000000f1a1'
const name = (en: string) => JSON.stringify({ en })

function tree(openStatus: string | null = 'REVIEW'): Row {
  return {
    treeId: TREE_ID, ownerKind: 'SPONSOR', ownerId: SPONSOR_ID, subjectKind: 'TAKER', status: 'ACTIVE',
    templateTreeId: null, revision: 3, currentVersionId: EFFECT_ID, name: name('Coffee'), versionNo: 1,
    openVersionId: openStatus ? OPEN_ID : null, openStatus, openVersionNo: openStatus ? 2 : null,
    approvalRequestId: openStatus === 'REVIEW' ? 'tree-request' : null, submittedAt: '2026-11-01T00:00:00Z',
    remindedAt: null, nodes: 2, litSubjects: 1,
  }
}

const OPEN_NODES: Row[] = [
  { nodeId: 'basics', change: 'SAME', name: name('Basics'), description: null,
    condition: JSON.stringify({ op: 'ALL', items: [{ passed: true }] }), seq: 0, runningBound: 1,
    answeredUnlit: 8, countedOn: '2026-11-09' },
  { nodeId: 'extra', change: 'ADDED', name: name('Extra'), description: name('More beans'),
    condition: JSON.stringify({ op: 'ALL', items: [{ node: 'basics', beforeAnswer: true }, { passed: true }] }),
    seq: 1, runningBound: 0 },
  { nodeId: 'advanced', change: 'REMOVED', name: name('Advanced'), description: null,
    condition: JSON.stringify({ op: 'AT_LEAST', n: 1, items: [{ node: 'basics' }, { passed: true }] }), seq: 10001,
    runningBound: 2 },
]
const EFFECT_NODES: Row[] = [
  { ...OPEN_NODES[0] },
  { ...OPEN_NODES[2], change: 'SAME', seq: 1 },
]

const LIT: Row = { litId: LIT_ID, subjectKind: 'USER', subjectId: TAKER_ID, treeId: TREE_ID, nodeId: 'advanced',
  litAt: '2026-11-02T00:00:00Z', basis: 'PASSED', revokedAt: null, revokeReason: null, requestId: null,
  requestStatus: null, approvalRequestId: null, requestedBy: null }

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.trees.read', 'qb.tree.lit.revoke', 'qb.tree.platform.write'])
  server.templates = new Map<string, Row[] | Error>([
    [TEMPLATE.trees, [tree()]],
    [TEMPLATE.treeLit, [LIT]],
  ])
  server.get.mockReset().mockResolvedValue([])
  server.runQuery.mockReset().mockImplementation(async (id: string,
    options: { params?: Row; offset?: number; limit?: number; count?: boolean } = {}) => {
    const rows = id === TEMPLATE.treeVersion
      ? (options.params?.versionId === OPEN_ID ? OPEN_NODES : EFFECT_NODES)
      : server.templates.get(id)
    if (rows instanceof Error) throw rows
    const all = rows ?? []
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({ requestId: 'r1', litId: LIT_ID, status: 'REVIEW',
    approvalRequestId: 'revoke-request' })
  server.decided.mockReset()
})

function renderAt(path: string, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const renderReview = () => renderAt(`/qb/trees/${TREE_ID}`, '/qb/trees/:treeId', <TreeReviewPage />)

describe('TreesPage', () => {
  it('lists every map for the platform, linking to its review', async () => {
    const { container } = renderAt('/qb/trees', '/qb/trees', <TreesPage />)
    const link = await screen.findByTestId('tree-link')
    expect(link).toHaveTextContent('Coffee')
    expect(link).toHaveAttribute('href', `/qb/trees/${TREE_ID}`)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.trees, expect.objectContaining({ offset: 0, count: true }))
    const row = screen.getByTestId('tree-row')
    expect(within(row).getByTestId('user-name')).toHaveTextContent(SPONSOR_ID)
    expect(row).toHaveTextContent('Version 1')
    expect(row).toHaveTextContent('Version 2')
    await expectAccessible(container)
  })

  it('lists an operator’s own maps for its members', async () => {
    server.permissions = new Set(['qb.operator.use'])
    server.templates.set(TEMPLATE.operatorTrees, [tree(null)])
    renderAt('/qb/trees', '/qb/trees', <TreesPage />)
    expect(await screen.findByTestId('tree-link')).toHaveTextContent('Coffee')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorTrees, expect.anything())
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.trees, expect.anything())
    expect(screen.getByText('The knowledge maps of your operator and of the Sponsors it looks after.')).toBeInTheDocument()
  })
})

describe('TreeReviewPage', () => {
  it('shows the version under review node by node, its conditions in words, and the decision', async () => {
    const { container } = renderReview()
    const panel = await screen.findByTestId('approval-panel')
    expect(panel).toHaveAttribute('data-request', 'tree-request')
    const nodes = screen.getAllByTestId('tree-node')
    expect(nodes.map((n) => within(n).getByTestId('tree-node-change').getAttribute('data-change')))
      .toEqual(['SAME', 'ADDED', 'REMOVED'])
    expect(within(nodes[1]).getByTestId('tree-node-condition'))
      .toHaveTextContent('all of: node “Basics” lit before answering, passed a bound publication')
    expect(within(nodes[2]).getByTestId('tree-node-condition'))
      .toHaveTextContent('at least 1 of: node “Basics” lit, passed a bound publication')
    expect(within(nodes[2]).getByTestId('tree-node-running-removed'))
      .toHaveTextContent('2 running publications are bound to this removed node')
    // F-S3f: the daily count, as of its day; a node not counted says so.
    expect(within(nodes[0]).getByTestId('tree-node-unlit')).toHaveTextContent('8as of 2026-11-09')
    expect(within(nodes[1]).getByTestId('tree-node-unlit')).toHaveTextContent('Not counted yet')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.treeVersion, expect.objectContaining({
      params: { versionId: OPEN_ID } }))
    // The lit records name their node, here one the version removes.
    const lit = await screen.findByTestId('tree-lit-row')
    expect(lit).toHaveTextContent('Advanced')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.treeLit, expect.objectContaining({
      params: { treeId: TREE_ID } }))
    await expectAccessible(container)
    await userEvent.click(panel)
    expect(server.decided).toHaveBeenCalledWith('tree-request')
  })

  it('offers no decision while a read of the version failed, nor when nothing is in review', async () => {
    server.runQuery.mockImplementation(async (id: string) => {
      if (id === TEMPLATE.treeVersion) throw new Error('down')
      const rows = server.templates.get(id) ?? []
      return { items: rows, offset: 0, limit: 100, total: (rows as Row[]).length }
    })
    const { unmount } = renderReview()
    await waitFor(() => expect(screen.getAllByTestId('tree-nodes').length).toBe(1))
    await screen.findAllByRole('button', { name: /try again|retry/i })
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    unmount()

    server.runQuery.mockReset().mockImplementation(async (id: string) => {
      const rows = id === TEMPLATE.treeVersion ? EFFECT_NODES : id === TEMPLATE.trees ? [tree('DRAFT')]
        : server.templates.get(id) ?? []
      return { items: rows, offset: 0, limit: 100, total: (rows as Row[]).length }
    })
    renderReview()
    expect(await screen.findByTestId('tree-not-pending')).toHaveTextContent('No version is waiting for review')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
  })

  it('asks for a revocation with a required reason, only with the permission', async () => {
    renderReview()
    // Once the nodes are read, the lit records name them (the row is drawn again).
    await waitFor(() => expect(screen.getByTestId('tree-lit-row')).toHaveTextContent('Advanced'))
    await userEvent.click(screen.getByTestId('tree-lit-revoke'))
    const dialog = await screen.findByTestId('tree-revoke-dialog')
    const submit = within(dialog).getByTestId('tree-revoke-submit')
    expect(submit).toBeDisabled()
    await userEvent.type(within(dialog).getByTestId('tree-revoke-reason'), '  Shared answers ')
    await userEvent.click(submit)
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeLitRevoke,
      { litId: LIT_ID, reason: 'Shared answers' }))
    await waitFor(() => expect(screen.queryByTestId('tree-revoke-dialog')).toBeNull())
  })

  it('shows a revocation in review and a revoked node, and no revoke button without the permission', async () => {
    server.permissions = new Set(['qb.admin.trees.read'])
    server.templates.set(TEMPLATE.treeLit, [
      { ...LIT, requestId: 'r1', requestStatus: 'REVIEW', approvalRequestId: 'revoke-request', requestedBy: SPONSOR_ID },
      { ...LIT, litId: 'other', nodeId: 'basics', revokedAt: '2026-11-03T00:00:00Z', revokeReason: 'Copied' },
    ])
    renderReview()
    expect(await screen.findByTestId('tree-lit-in-review')).toHaveTextContent('Revocation in review')
    expect(screen.getByTestId('tree-lit-revoked')).toHaveTextContent('Revoked: Copied')
    expect(screen.queryByTestId('tree-lit-revoke')).toBeNull()
    // Without a reviewer's permission the decision is not offered.
    expect(await screen.findByText('You may not decide this review.')).toBeInTheDocument()
  })

  it('shows an operator’s member its map through the operator’s templates, with the decision and revoking', async () => {
    server.permissions = new Set(['qb.operator.use', 'qb.review.tree', 'qb.tree.lit.revoke'])
    server.templates.set(TEMPLATE.operatorTrees, [tree()])
    server.templates.set(TEMPLATE.operatorTreeLit, [LIT])
    server.runQuery.mockImplementation(async (id: string, options: { params?: Row } = {}) => {
      const rows = id === TEMPLATE.operatorTreeVersion
        ? (options.params?.versionId === OPEN_ID ? OPEN_NODES : EFFECT_NODES)
        : server.templates.get(id) ?? []
      return { items: rows, offset: 0, limit: 100, total: (rows as Row[]).length }
    })
    const { container } = renderReview()
    expect(await screen.findByTestId('approval-panel')).toBeInTheDocument()
    expect(screen.getAllByTestId('tree-node')).toHaveLength(3)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorTreeVersion, expect.objectContaining({
      params: { versionId: OPEN_ID } }))
    await waitFor(() => expect(screen.getByTestId('tree-lit-row')).toHaveTextContent('Advanced'))
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorTreeLit, expect.objectContaining({
      params: { treeId: TREE_ID } }))
    for (const platform of [TEMPLATE.trees, TEMPLATE.treeVersion, TEMPLATE.treeLit]) {
      expect(server.runQuery).not.toHaveBeenCalledWith(platform, expect.anything())
    }
    await expectAccessible(container)
    await userEvent.click(screen.getByTestId('tree-lit-revoke'))
    const dialog = await screen.findByTestId('tree-revoke-dialog')
    await userEvent.type(within(dialog).getByTestId('tree-revoke-reason'), 'Shared answers')
    await userEvent.click(within(dialog).getByTestId('tree-revoke-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeLitRevoke,
      { litId: LIT_ID, reason: 'Shared answers' }))
  })
})

describe('the map editor (F-S3e)', () => {
  const template = (openStatus: string | null = 'DRAFT'): Row => ({ ...tree(openStatus), ownerKind: 'PLATFORM',
    ownerId: null, approvalRequestId: null, draftName: name('Coffee'), description: null })
  const saved = (extra: Row = {}) => ({ treeId: TREE_ID, versionId: OPEN_ID, versionNo: 2, status: 'DRAFT',
    revision: 4, nodeIds: {}, problems: [], approval: null, approvalRequestId: null, ...extra })

  it('lists a template\'s draft by level, saves it whole, and shows the server\'s problems by node', async () => {
    server.templates.set(TEMPLATE.trees, [template()])
    server.runProcess.mockImplementation(async (processName: string) => processName === PROCESS.treeSave
      ? saved({ nodeIds: { 'new-3': 'fresh' }, problems: [{ nodeId: 'fresh', code: 'QB_TREE_EMPTY_GROUP', params: {} }] })
      : saved())
    const { container } = renderReview()
    await userEvent.click(await screen.findByTestId('tree-edit'))
    const editor = await screen.findByTestId('tree-editor')
    const nodes = within(editor).getAllByTestId('tree-editor-node')
    // The removed node is not in the draft; Extra needs Basics, so it is a level below.
    expect(nodes.map((n) => [n.getAttribute('data-node'), n.getAttribute('data-level')]))
      .toEqual([['basics', '1'], ['extra', '2']])
    expect(within(nodes[0]).getByTestId('tree-editor-remove')).toBeDisabled()
    expect(within(nodes[0]).getByTestId('tree-editor-in-use')).toHaveTextContent('1 publications')
    // Before answering only on the outermost "all"; one pass at most.
    expect(within(nodes[1]).getByTestId('condition-before-answer')).toBeInTheDocument()
    expect(within(nodes[1]).queryByTestId('condition-add-pass')).toBeNull()
    await userEvent.click(within(editor).getByTestId('tree-editor-add'))
    const added = within(editor).getAllByTestId('tree-editor-node').find((n) => n.getAttribute('data-node') === 'new-3')!
    await userEvent.click(within(added).getByTestId('condition-remove'))
    await userEvent.click(within(editor).getByTestId('tree-editor-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeSave, expect.objectContaining({
      treeId: TREE_ID, baseRevision: 3, name: { en: 'Coffee' } })))
    const input = server.runProcess.mock.calls.find((call) => call[0] === PROCESS.treeSave)![1] as
      { nodes: { nodeId: string | null; key: string | null; condition: unknown }[] }
    expect(input.nodes.map((n) => [n.nodeId, n.key])).toEqual([['basics', null], ['extra', null], [null, 'new-3']])
    expect(input.nodes[2].condition).toEqual({ op: 'ALL', items: [] })
    expect(await within(editor).findByTestId('tree-editor-node-problems')).toHaveTextContent('at least one item')
    expect(within(editor).getByTestId('tree-editor-submit')).toBeDisabled()
    await expectAccessible(container)
  })

  it('submits a clean draft and takes it back from review', async () => {
    server.templates.set(TEMPLATE.trees, [template()])
    server.runProcess.mockImplementation(async (processName: string) => processName === PROCESS.treeSubmit
      ? saved({ status: 'REVIEW', revision: 5 }) : saved({ revision: 6 }))
    renderReview()
    await userEvent.click(await screen.findByTestId('tree-edit'))
    const editor = await screen.findByTestId('tree-editor')
    await userEvent.click(within(editor).getByTestId('tree-editor-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeSubmit, { treeId: TREE_ID,
      baseRevision: 3 }))
    await userEvent.click(await within(editor).findByTestId('tree-editor-withdraw'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeWithdraw, { treeId: TREE_ID,
      baseRevision: 5 }))
  })

  it('offers no editing of a Sponsor\'s map, nor without the permission', async () => {
    renderReview()
    await screen.findByTestId('tree-review')
    expect(screen.queryByTestId('tree-edit')).toBeNull()
  })

  it('shows an operator\'s copy the template\'s changes and takes the chosen ones', async () => {
    server.permissions = new Set(['qb.operator.use', 'qb.tree.edit'])
    server.templates.set(TEMPLATE.operatorTrees, [{ ...tree('DRAFT'), ownerKind: 'OPERATOR', ownerId: 'op1',
      subjectKind: 'SPONSOR', templateTreeId: 'tmpl', approvalRequestId: null }])
    server.templates.set(TEMPLATE.operatorTreeLit, [])
    server.templates.set(TEMPLATE.operatorTreeVersion, OPEN_NODES)
    server.templates.set(TEMPLATE.operatorTreeTemplateDiff, [
      { nodeId: 'basics', change: 'CHANGED', name: name('Basics better'), copyName: name('Basics'), condition: null,
        templateVersionNo: 3, takenVersionNo: 2 },
      { nodeId: 'extra', change: 'SAME', name: name('Extra'), copyName: name('Extra'), condition: null,
        templateVersionNo: 3, takenVersionNo: 2 },
      { nodeId: 'gone', change: 'NOT_IN_TEMPLATE', name: name('Gone'), copyName: name('Gone'), condition: null,
        templateVersionNo: 3, takenVersionNo: 2 },
    ])
    server.runProcess.mockResolvedValue(saved())
    const { container } = renderReview()
    const adopt = await screen.findByTestId('tree-adopt')
    expect(await within(adopt).findByTestId('tree-adopt-versions')).toHaveTextContent('version 3')
    const rows = await within(adopt).findAllByTestId('tree-adopt-node')
    expect(rows.map((r) => r.getAttribute('data-change'))).toEqual(['CHANGED', 'NOT_IN_TEMPLATE'])
    expect(within(adopt).getByTestId('tree-adopt-submit')).toBeDisabled()
    await userEvent.click(within(rows[0]).getByRole('checkbox'))
    await userEvent.click(within(adopt).getByTestId('tree-adopt-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeAdopt, { treeId: TREE_ID,
      baseRevision: 3, nodeIds: ['basics'] }))
    expect(screen.getByTestId('tree-edit')).toBeInTheDocument()
    await expectAccessible(container)
  })

  it('gives a template Sponsors light its grant, sent with the draft; the review and a copy show it', async () => {
    const granted = OPEN_NODES.map((node, i) => (i === 0 ? { ...node, grantAmount: '200' } : node))
    const read = server.runQuery.getMockImplementation()!
    server.runQuery.mockImplementation(async (id: string, options: { params?: Row } = {}) =>
      (id === TEMPLATE.treeVersion || id === TEMPLATE.operatorTreeVersion)
        ? { items: granted, offset: 0, limit: 100 } : read(id, options))
    server.templates.set(TEMPLATE.trees, [{ ...template(), subjectKind: 'SPONSOR' }])
    server.runProcess.mockResolvedValue(saved())
    const { container, unmount } = renderReview()
    expect((await screen.findAllByTestId('tree-node-grant'))[0]).toHaveTextContent('200')
    await userEvent.click(await screen.findByTestId('tree-edit'))
    const editor = await screen.findByTestId('tree-editor')
    const fields = within(editor).getAllByTestId('tree-editor-node-grant')
    expect(fields[0]).toHaveValue(200)
    expect(fields[1]).toHaveValue(null)
    await userEvent.clear(fields[0])
    await userEvent.type(fields[0], '300')
    await userEvent.click(within(editor).getByTestId('tree-editor-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeSave, expect.anything()))
    const input = server.runProcess.mock.calls.find((call) => call[0] === PROCESS.treeSave)![1] as
      { nodes: { grantAmount: number | null }[] }
    expect(input.nodes.map((n) => n.grantAmount)).toEqual([300, null])
    await expectAccessible(container)
    unmount()

    // An operator's copy: the template's grant is shown, not edited (it is kept on save).
    server.permissions = new Set(['qb.operator.use', 'qb.tree.edit'])
    server.templates.set(TEMPLATE.operatorTrees, [{ ...tree('DRAFT'), ownerKind: 'OPERATOR', ownerId: 'op1',
      subjectKind: 'SPONSOR', templateTreeId: 'tmpl', approvalRequestId: null }])
    server.templates.set(TEMPLATE.operatorTreeLit, [])
    server.templates.set(TEMPLATE.operatorTreeTemplateDiff, [])
    renderReview()
    await userEvent.click(await screen.findByTestId('tree-edit'))
    const copy = await screen.findByTestId('tree-editor')
    expect(within(copy).queryByTestId('tree-editor-node-grant')).toBeNull()
    expect(within(copy).getByTestId('tree-editor-node-grant-copy')).toHaveTextContent('200')
  })

  it('creates a platform template from the list', async () => {
    server.runProcess.mockResolvedValue(saved({ treeId: 'new-tree' }))
    renderAt('/qb/trees', '/qb/trees', <TreesPage />)
    await userEvent.click(await screen.findByTestId('tree-create'))
    await userEvent.click(screen.getByTestId('tree-create-submit'))
    expect(await screen.findByText('Enter a name.')).toBeInTheDocument()
    await userEvent.type(screen.getByTestId('tree-create-name'), 'Learning')
    await userEvent.click(screen.getByTestId('tree-create-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.treeCreate, { ownerKind: 'PLATFORM',
      subjectKind: 'TAKER', name: { en: 'Learning' } }))
  })

  it('places each node one level above the highest it needs, cutting cycles', () => {
    const node = (key: string, refs: string[]) => ({ nodeId: key, key, name: {}, description: null, runningBound: 0,
      condition: { op: 'ALL' as const, items: refs.map((ref) => ({ node: ref })) } })
    const levels = levelsOf([node('c', ['b']), node('a', []), node('b', ['a', 'other']), node('x', ['y']),
      node('y', ['x'])])
    // A cycle is cut where it is met: still some order, never a loop.
    expect(Object.fromEntries(levels)).toEqual({ a: 0, b: 1, c: 2, x: 2, y: 1 })
  })
})

describe('TreeSupportPage (F-S3e)', () => {
  const renderSupport = () => renderAt('/qb/tree-support', '/qb/tree-support', <TreeSupportPage />)

  it('looks up one person\'s lighting of one map, each node with its state and records', async () => {
    server.permissions = new Set(['qb.operator.use', 'qb.admin.tree-state.read'])
    server.templates.set(TEMPLATE.operatorTrees, [tree(null)])
    server.runProcess.mockResolvedValue({ treeId: TREE_ID, ownerKind: 'SPONSOR', ownerId: SPONSOR_ID, nodes: [
      { nodeId: 'basics', name: { en: 'Basics' }, state: 'LIT', unmet: [], lits: [{ litId: 'l1',
        litAt: '2026-11-02T00:00:00Z', revokedAt: null, revokeReason: null }] },
      { nodeId: 'extra', name: name('Extra'), state: 'UNMET', unmet: [`${TREE_ID}/basics`], lits: [] },
    ] })
    const { container } = renderSupport()
    await userEvent.type(await screen.findByTestId('tree-support-user-id'), TAKER_ID)
    await userEvent.click(screen.getByTestId('tree-support-map'))
    await userEvent.click(await screen.findByRole('option', { name: 'Coffee' }))
    await userEvent.click(screen.getByTestId('tree-support-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.supportTreeState, { userId: TAKER_ID,
      treeId: TREE_ID }))
    const nodes = await screen.findAllByTestId('tree-support-node')
    expect(nodes.map((n) => n.getAttribute('data-state'))).toEqual(['LIT', 'UNMET'])
    expect(nodes[1]).toHaveTextContent('Light first: Basics')
    await expectAccessible(container)
  })
})
