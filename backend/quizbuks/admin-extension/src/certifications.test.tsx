import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import CertificationsPage from './CertificationsPage'
import { CERTIFICATION_PROCESS, DATASET, PATH, PERMISSION } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  overview: {} as Record<string, unknown>,
  official: [] as Record<string, unknown>[],
  runProcess: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get, POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const PUBLICATION = '0192f0a0-0000-7000-8000-00000000a001'
const NEW_PUBLICATION = '0192f0a0-0000-7000-8000-00000000a002'
const TAKER = '0192f0a0-0000-7000-8000-00000000b001'
const ADMIN = '0192f0a0-0000-7000-8000-00000000c001'
const SPONSOR = '0192f0a0-0000-7000-8000-00000000d001'

const overview = () => ({
  owner: 'direct',
  rounds: [
    { roundId: 'r1', publicationId: PUBLICATION, publicationName: 'Sponsor basics', certification: 'SPONSOR_APPLY',
      certificationVersion: 1, addedAt: '2026-10-01T01:00:00Z', closedAt: null, holders: 12 },
    { roundId: 'r0', publicationId: NEW_PUBLICATION, publicationName: null, certification: 'SPONSOR_APPLY',
      certificationVersion: 1, addedAt: '2026-09-01T01:00:00Z', closedAt: '2026-09-30T01:00:00Z', holders: 3 },
  ],
  byHand: [
    { recordId: 'h1', userId: TAKER, certification: 'SPONSOR_APPLY', kind: 'GRANTED', reason: 'Met in person',
      actor: ADMIN, owner: 'direct', at: '2026-10-10T01:00:00Z', validUntil: '2027-10-10T01:00:00Z' },
    { recordId: 'h2', userId: TAKER, certification: 'SPONSOR_APPLY', kind: 'REVOKED', reason: 'Shared answers',
      actor: ADMIN, owner: 'direct', at: '2026-10-20T01:00:00Z', validUntil: null },
  ],
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.certificationManage])
  server.overview = overview()
  server.official = [{ userId: SPONSOR, owner: 'direct', active: true, setBy: ADMIN, setAt: '2026-09-01T01:00:00Z' }]
  server.runProcess.mockReset().mockImplementation(async (name: string) => {
    if (name === CERTIFICATION_PROCESS.overview) return server.overview
    return { roundId: 'r2', publicationId: NEW_PUBLICATION, certification: 'SPONSOR_APPLY', certificationVersion: 1,
      closedAt: null, recordId: 'h3', kind: 'GRANTED', validUntil: null }
  })
  server.get.mockReset().mockRejectedValue(new ApiError(404, { detail: 'Not found' }))
  server.post.mockReset().mockImplementation(async (_path: string, options: { params: { path: { resourceId: string } } }) => {
    if (options.params.path.resourceId !== DATASET.officialPublisher) throw new ApiError(403, { detail: 'Forbidden' })
    return { items: server.official.map((attributes) => ({ attributes })) }
  })
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[PATH.certifications]}>
          <Routes>
            <Route path={PATH.certifications} element={<CertificationsPage />} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the certifications page', () => {
  it('shows the rounds and the records by hand', async () => {
    const { container } = renderPage()
    const rows = await screen.findAllByTestId('round-row')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Sponsor basics')
    expect(rows[0]).toHaveTextContent('May apply to become a sponsor')
    expect(rows[0]).toHaveTextContent('12')
    expect(within(rows[0]).getByTestId('round-close')).toBeInTheDocument()
    expect(within(rows[1]).queryByTestId('round-close')).not.toBeInTheDocument()
    expect(within(rows[1]).getByTestId('round-closed')).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent(NEW_PUBLICATION)
    // Without reading publications, the name is no link.
    expect(screen.queryByTestId('round-publication-link')).not.toBeInTheDocument()
    expect(screen.getByTestId('certifications-owner')).toHaveTextContent('Headquarters (direct)')
    const records = screen.getAllByTestId('record-row')
    expect(records[0]).toHaveAttribute('data-kind', 'GRANTED')
    expect(records[0]).toHaveTextContent('Met in person')
    expect(within(records[0]).getAllByTestId('user-name').map((e) => e.textContent)).toEqual([TAKER, ADMIN])
    expect(records[1]).toHaveTextContent('Revoked')
    expect(records[1]).toHaveTextContent('—')
    expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.overview, {})
    await expectAccessible(container)
  })

  it('links the publication for those who read publications', async () => {
    server.permissions.add(PERMISSION.publicationRead)
    renderPage()
    const link = await screen.findAllByTestId('round-publication-link')
    expect(link[0]).toHaveAttribute('href', PATH.publication(PUBLICATION))
  })

  it('adds a round of a publication', async () => {
    renderPage()
    const user = userEvent.setup()
    const form = await screen.findByTestId('round-add-form')
    expect(within(form).getByTestId('round-add')).toBeDisabled()
    await user.type(within(form).getByTestId('round-publication'), 'not-an-id')
    expect(within(form).getByTestId('round-add')).toBeDisabled()
    expect(within(form).getByTestId('round-publication-hint')).toHaveTextContent('a UUID')
    await user.clear(within(form).getByTestId('round-publication'))
    await user.type(within(form).getByTestId('round-publication'), NEW_PUBLICATION)
    await user.click(within(form).getByTestId('round-add'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.roundAdd,
      { publicationId: NEW_PUBLICATION, certification: 'SPONSOR_APPLY' }))
  })

  it('shows the server’s refusal of a round', async () => {
    renderPage()
    const user = userEvent.setup()
    const form = await screen.findByTestId('round-add-form')
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'Not official',
      violations: [{ field: 'publicationId', code: 'QB_CERTIFICATION_NOT_OFFICIAL',
        message: 'Only a publication of your official publisher can be a certification round.' }] }))
    await user.type(within(form).getByTestId('round-publication'), NEW_PUBLICATION)
    await user.click(within(form).getByTestId('round-add'))
    expect(await screen.findByTestId('round-problem')).toBeInTheDocument()
  })

  it('closes a round only after confirming', async () => {
    renderPage()
    const user = userEvent.setup()
    const rows = await screen.findAllByTestId('round-row')
    await user.click(within(rows[0]).getByTestId('round-close'))
    const dialog = await screen.findByRole('alertdialog')
    expect(server.runProcess).not.toHaveBeenCalledWith(CERTIFICATION_PROCESS.roundClose, expect.anything())
    await user.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.roundClose,
      { roundId: 'r1' }))
  })

  it('grants only with a reason, after confirming', async () => {
    renderPage()
    const user = userEvent.setup()
    const form = await screen.findByTestId('by-hand-form')
    await user.type(within(form).getByTestId('by-hand-user'), TAKER)
    expect(within(form).getByTestId('by-hand-grant')).toBeDisabled()
    expect(within(form).getByTestId('by-hand-revoke')).toBeDisabled()
    await user.type(within(form).getByTestId('by-hand-reason'), '   ')
    expect(within(form).getByTestId('by-hand-grant')).toBeDisabled()
    await user.type(within(form).getByTestId('by-hand-reason'), 'Known partner')
    await user.click(within(form).getByTestId('by-hand-grant'))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Grant' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.grant,
      { userId: TAKER, certification: 'SPONSOR_APPLY', reason: 'Known partner' }))
  })

  it('revokes with a reason, after confirming', async () => {
    renderPage()
    const user = userEvent.setup()
    const form = await screen.findByTestId('by-hand-form')
    await user.type(within(form).getByTestId('by-hand-user'), TAKER)
    await user.type(within(form).getByTestId('by-hand-reason'), 'Shared answers')
    await user.click(within(form).getByTestId('by-hand-revoke'))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.revoke,
      { userId: TAKER, certification: 'SPONSOR_APPLY', reason: 'Shared answers' }))
  })

  it('hides the official publishers without qb.certification.admin', async () => {
    renderPage()
    await screen.findAllByTestId('round-row')
    expect(screen.queryByTestId('official-section')).not.toBeInTheDocument()
    expect(server.post).not.toHaveBeenCalled()
  })

  it('lists and sets the official publishers for headquarters', async () => {
    server.permissions.add(PERMISSION.certificationAdmin)
    const { container } = renderPage()
    const section = await screen.findByTestId('official-section')
    const row = await within(section).findByTestId('official-row')
    expect(row).toHaveTextContent(SPONSOR)
    expect(row).toHaveTextContent('Headquarters (direct)')
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.type(within(section).getByTestId('official-user'), SPONSOR)
    await user.click(within(section).getByTestId('official-active'))
    await user.click(within(section).getByTestId('official-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(CERTIFICATION_PROCESS.officialSet,
      { userId: SPONSOR, operatorId: null, active: false }))
  })
})
