import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import CertificatesPage from './CertificatesPage'
import { certificateState } from './certificate-state'
import { TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  templates: new Map<string, Row[] | Error>(),
  runQuery: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const KEY = '0123456789abcdef0123456789abcdef'
const OTHER_KEY = 'fedcba9876543210fedcba9876543210'
const TREE = 'tree-1'
const node = (extra: Row = {}): Row => ({ certificateId: 'c1', userId: 'u1', source: 'NODE', subjectKind: 'USER',
  certification: null, litId: 'lit-1', treeId: TREE, nodeId: 'n1', certificateNames: JSON.stringify({ en: 'Fair dealing' }),
  issuerName: 'Beans Platform', shared: true, holderName: 'Mei', heldFrom: '2026-11-02T00:00:00Z', validUntil: null,
  verifyKey: KEY, language: null, issuedAt: '2026-11-02T00:00:00Z', revokedAt: null, ...extra })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.users.read'])
  server.templates = new Map<string, Row[] | Error>([
    [TEMPLATE.certificates, [node(), node({ certificateId: 'c2', verifyKey: OTHER_KEY, revokedAt: '2026-11-05T00:00:00Z' }),
      node({ certificateId: 'c3', verifyKey: 'a'.repeat(32), shared: false })]],
    [TEMPLATE.operatorCertificates, [node({ subjectKind: 'SPONSOR' })]],
  ])
  server.get.mockReset().mockResolvedValue([])
  server.runQuery.mockReset().mockImplementation(async (id: string,
    options: { filters?: { field: string; value: string }[]; offset?: number; limit?: number } = {}) => {
    const rows = server.templates.get(id)
    if (rows instanceof Error) throw rows
    const key = options.filters?.find((f) => f.field === 'verifyKey')?.value
    const all = (rows ?? []).filter((row) => !key || row.verifyKey === key)
    return { items: all, offset: options.offset ?? 0, limit: options.limit ?? 20, total: all.length }
  })
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/certificates']}>
          <Routes><Route path="/qb/certificates" element={<CertificatesPage />} /></Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the certificate lookup (F-S5b)', () => {
  it('reads the state of a certificate: revoked, the holder\'s link off, Q77\'s end', () => {
    const now = Date.parse('2027-01-01T00:00:00Z')
    expect(certificateState({ revokedAt: null, shared: true, validUntil: null }, now)).toBe('VALID')
    expect(certificateState({ revokedAt: '2026-11-05T00:00:00Z', shared: false, validUntil: null }, now)).toBe('REVOKED')
    expect(certificateState({ revokedAt: null, shared: false, validUntil: null }, now)).toBe('SHARING_OFF')
    expect(certificateState({ revokedAt: null, shared: true, validUntil: '2026-12-31T00:00:00Z' }, now)).toBe('EXPIRED')
  })

  it('lists every certificate for the platform with its state, the map of its lit record and its verify page', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('certificate-row')
    expect(rows).toHaveLength(3)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.certificates, expect.objectContaining({ params: {}, offset: 0 }))
    expect(within(rows[0]).getByTestId('certificate-name')).toHaveTextContent('Fair dealing')
    expect(within(rows[0]).getByTestId('user-name')).toHaveTextContent('u1')
    expect(rows.map((row) => within(row).getByTestId('certificate-state').textContent))
      .toEqual(['Valid', 'Revoked', 'Link turned off'])
    expect(within(rows[0]).getByTestId('certificate-lit-link')).toHaveAttribute('href', `/qb/trees/${TREE}`)
    expect(within(rows[0]).getByTestId('certificate-verify-link')).toHaveAttribute('href', `/taker/verify/${KEY}`)
    // Nothing says a node's certificate has an end.
    expect(rows[0]).not.toHaveTextContent(/until|expires/i)
    await expectAccessible(container)
  })

  it('finds one by its number, in capitals and with spaces too, and refuses a malformed number', async () => {
    renderPage()
    await screen.findAllByTestId('certificate-row')
    await userEvent.type(screen.getByTestId('certificate-lookup-number'), 'xyz')
    await userEvent.click(screen.getByTestId('certificate-lookup-submit'))
    expect(screen.getByTestId('certificate-lookup-submit')).toBeDisabled()
    await userEvent.clear(screen.getByTestId('certificate-lookup-number'))
    await userEvent.type(screen.getByTestId('certificate-lookup-number'), `${OTHER_KEY.slice(0, 16).toUpperCase()} ${OTHER_KEY.slice(16)}`)
    await userEvent.click(screen.getByTestId('certificate-lookup-submit'))
    await waitFor(() => expect(screen.getAllByTestId('certificate-row')).toHaveLength(1))
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.certificates, expect.objectContaining({
      filters: [{ field: 'verifyKey', op: 'eq', value: OTHER_KEY }] }))
    expect(screen.getByTestId('certificate-state')).toHaveTextContent('Revoked')
    await userEvent.click(screen.getByTestId('certificate-lookup-clear'))
    await waitFor(() => expect(screen.getAllByTestId('certificate-row')).toHaveLength(3))
  })

  it('lists only the operator\'s own maps\' certificates for its members, by the holder\'s ID', async () => {
    server.permissions = new Set(['qb.operator.use'])
    renderPage()
    const rows = await screen.findAllByTestId('certificate-row')
    expect(rows).toHaveLength(1)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorCertificates, expect.anything())
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.certificates, expect.anything())
    // A member without the users' page types the ID; the holder's user is not shown.
    expect(within(rows[0]).queryByTestId('user-name')).toBeNull()
    await userEvent.type(screen.getByTestId('certificate-lookup-user-id'), 'u1')
    await userEvent.click(screen.getByTestId('certificate-lookup-submit'))
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorCertificates,
      expect.objectContaining({ params: { userId: 'u1' } })))
  })

  it('says when there is none', async () => {
    server.templates.set(TEMPLATE.certificates, [])
    renderPage()
    expect(await screen.findByText('No certificate found.')).toBeInTheDocument()
  })
})
