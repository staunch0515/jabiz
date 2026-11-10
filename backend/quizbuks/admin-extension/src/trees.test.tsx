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
