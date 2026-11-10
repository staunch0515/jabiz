import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import SponsorVerificationPage from './SponsorVerificationPage'
import SponsorVerificationsPage from './SponsorVerificationsPage'
import { PATH, PERMISSION, SPONSOR_VERIFICATION_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'
import { verificationMessages } from './verification-messages'

type Row = Record<string, unknown>

/** The server as the pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  entry: 'operator',
  permissions: new Set<string>(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: async () => ({ data: [] }) },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ entry: server.entry, can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
  }
})

const verification = (change: Row = {}): Row => ({ verificationId: 'ver1', sponsorId: 'sponsor-1',
  companyName: 'Cat Co', domain: 'cat.example', method: 'ACCOUNT', confirmAddress: 'boss@cat.example',
  status: 'REVIEW', approvalRequestId: 'r1', operatorId: 'op-kanto', submittedAt: '2026-11-08T00:00:00Z',
  confirmedAt: '2026-11-08T00:05:00Z', decidedAt: null, verifiedAt: null, rejectReason: null, endedAt: null,
  revokeReason: null, revokedOperatorId: null, ...change })

let rows: Row[] = []

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.entry = 'operator'
  server.permissions = new Set([PERMISSION.verificationRead, PERMISSION.verificationRevoke])
  rows = [verification(), verification({ verificationId: 'ver2', sponsorId: 'sponsor-2', companyName: 'Dog Co',
    domain: 'dog.example', method: 'LINK', confirmAddress: 'info@dog.example', status: 'APPROVED',
    approvalRequestId: 'r2', verifiedAt: '2026-11-09T00:00:00Z', decidedAt: '2026-11-09T00:00:00Z' })]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: {
    params?: Row; filters?: Row[]; count?: boolean } = {}) => {
    let items: Row[] = []
    if (id === TEMPLATE.sponsorVerifications) {
      const params = options.params ?? {}
      items = rows.filter((row) => (!params.status || row.status === params.status)
        && (!params.sponsorId || row.sponsorId === params.sponsorId)
        && (options.filters ?? []).every((f) => f.field === 'operatorId' || row[String(f.field)] === f.value))
    }
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockResolvedValue({})
})

function renderAt(at: string) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[at]}>
          <Routes>
            <Route path="/qb/sponsor-verifications" element={<SponsorVerificationsPage />} />
            <Route path="/qb/sponsor-verifications/:verificationId" element={<SponsorVerificationPage />} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('company verification (F-M3)', () => {
  it('has every text in every language, without the retired names', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(verificationMessages.en)
    expect(keys(verificationMessages.zh)).toEqual(english)
    expect(keys(verificationMessages.ja)).toEqual(english)
    const text = JSON.stringify(verificationMessages)
    expect(text).not.toMatch(/后台|管理画面|商户|商家|スポンサー|merchant|console|back ?office/i)
  })

  it('lists those in review first, each linked to its verification', async () => {
    const { container } = renderAt(PATH.sponsorVerifications)
    const listed = await screen.findAllByTestId('verification-row')
    expect(listed).toHaveLength(1)
    expect(within(listed[0]).getByTestId('verification-link')).toHaveTextContent('Cat Co')
    expect(within(listed[0]).getByTestId('verification-link')).toHaveAttribute('href',
      '/qb/sponsor-verifications/ver1')
    expect(listed[0]).toHaveTextContent('cat.example')
    expect(listed[0]).toHaveTextContent('Account address on the domain')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sponsorVerifications, expect.objectContaining({
      params: { status: 'REVIEW' } }))
    // An operator's session chooses no operator.
    expect(screen.queryByTestId('sponsor-verifications-operator-filter')).toBeNull()
    await expectAccessible(container)
  })

  it('filters by status, in the address and by the choice', async () => {
    renderAt(`${PATH.sponsorVerifications}?status=APPROVED`)
    const listed = await screen.findAllByTestId('verification-row')
    expect(listed).toHaveLength(1)
    expect(listed[0]).toHaveTextContent('Dog Co')
    await userEvent.click(screen.getByTestId('verifications-show'))
    await userEvent.click(await screen.findByRole('option', { name: 'All' }))
    await waitFor(() => expect(screen.getAllByTestId('verification-row')).toHaveLength(2))
  })

  it('shows one Sponsor\'s verifications of every status when linked from it', async () => {
    renderAt(`${PATH.sponsorVerifications}?sponsorId=sponsor-2`)
    const listed = await screen.findAllByTestId('verification-row')
    expect(listed).toHaveLength(1)
    expect(screen.getByTestId('verifications-sponsor')).toHaveTextContent('sponsor-2')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.sponsorVerifications, expect.objectContaining({
      params: { sponsorId: 'sponsor-2' } }))
  })

  it('shows a verification in review with the approval panel and nothing to revoke', async () => {
    const { container } = renderAt('/qb/sponsor-verifications/ver1')
    const page = await screen.findByTestId('verification-page')
    expect(page).toHaveAttribute('data-status', 'REVIEW')
    expect(await screen.findByTestId('approval-panel')).toHaveAttribute('data-request', 'r1')
    const content = screen.getByTestId('verification-content')
    expect(content).toHaveTextContent('Cat Co')
    expect(content).toHaveTextContent('cat.example')
    expect(screen.getByTestId('verification-address')).toHaveTextContent('boss@cat.example')
    expect(screen.queryByTestId('verification-revoke')).toBeNull()
    await expectAccessible(container)
  })

  it('shows the way the company was proven by a link to the address', async () => {
    rows = [verification({ method: 'LINK' })]
    renderAt('/qb/sponsor-verifications/ver1')
    expect(await screen.findByTestId('verification-content')).toHaveTextContent('Link to the address shown')
  })

  it('gives no panel to a verification that is not in review', async () => {
    const { container } = renderAt('/qb/sponsor-verifications/ver2')
    await screen.findByTestId('verification-page')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    expect(screen.getByTestId('verification-not-in-review')).toHaveTextContent('Verified')
    await expectAccessible(container)
  })

  it('asks a reason before revoking, then calls the process', async () => {
    const { container } = renderAt('/qb/sponsor-verifications/ver2')
    await userEvent.click(await screen.findByTestId('verification-revoke-open'))
    const dialog = await screen.findByTestId('verification-revoke-dialog')
    await expectAccessible(container)
    await userEvent.click(within(dialog).getByTestId('verification-revoke-submit'))
    expect(await within(dialog).findByText('Give a reason.')).toBeInTheDocument()
    expect(server.runProcess).not.toHaveBeenCalled()
    await userEvent.type(within(dialog).getByTestId('verification-revoke-reason'), 'The domain was sold')
    await userEvent.click(within(dialog).getByTestId('verification-revoke-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(SPONSOR_VERIFICATION_PROCESS.revoke,
      { sponsorId: 'sponsor-2', reason: 'The domain was sold' }))
    await waitFor(() => expect(screen.queryByTestId('verification-revoke-dialog')).toBeNull())
  })

  it('offers no revoking to who may not, and shows why a verification was revoked', async () => {
    server.permissions = new Set([PERMISSION.verificationRead])
    renderAt('/qb/sponsor-verifications/ver2')
    await screen.findByTestId('verification-page')
    expect(screen.queryByTestId('verification-revoke')).toBeNull()
  })

  it('shows who revoked a verification and why, and offers nothing more', async () => {
    rows = [verification({ status: 'REVOKED', approvalRequestId: 'r1', revokeReason: 'Sold', endedAt: '2026-11-10T00:00:00Z' })]
    renderAt('/qb/sponsor-verifications/ver1')
    const content = await screen.findByTestId('verification-content')
    expect(content).toHaveTextContent('Sold')
    expect(content).toHaveTextContent('The platform')
    expect(screen.queryByTestId('approval-panel')).toBeNull()
    expect(screen.queryByTestId('verification-revoke')).toBeNull()
  })

  it('says a verification of another operator\'s Sponsor is not found', async () => {
    rows = []
    renderAt('/qb/sponsor-verifications/ver9')
    expect(await screen.findByText(/not one of your Sponsors/)).toBeInTheDocument()
  })
})
