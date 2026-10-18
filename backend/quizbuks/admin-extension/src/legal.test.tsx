import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { DATASET, TEMPLATE } from './ids'
import LegalPage from './LegalPage'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the legal texts' page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  drafts: [] as Row[],
  versions: [] as Row[],
  datasetQuery: vi.fn(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { POST: server.datasetQuery },
    unwrap: async (call: Promise<{ data?: unknown }>) => (await call).data,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const ADMIN = '0192f0a0-0000-7000-8000-00000000ad01'
const LATER = '2099-01-01T00:00:00Z'

function draft(kind: string, version: number, latestVersionNo: number | null): Row {
  return { id: `doc-${kind}`, version, attributes: { kind, title: { en: `${kind} title`, ja: `${kind} 題名` },
    body: { en: `${kind} **text**`, ja: `${kind} 本文` }, changeSummary: { en: 'Clearer.' }, latestVersionNo } }
}

function version(kind: string, versionNo: number, effectiveAt: string, change: Row = {}): Row {
  return { legalVersionId: `${kind}-${versionNo}`, kind, versionNo, title: { en: `${kind} v${versionNo}` },
    requiresConsent: true, effectiveAt, publishedAt: '2026-10-01T00:00:00Z', publishedBy: ADMIN, withdrawn: false,
    withdrawnAt: null, withdrawnBy: null, reason: null, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.drafts = [draft('TERMS_APP', 4, 2), draft('PRIVACY', 1, null)]
  server.versions = [
    version('TERMS_APP', 3, LATER, { withdrawn: true, withdrawnAt: '2026-10-02T00:00:00Z', withdrawnBy: ADMIN,
      reason: 'Announced by mistake' }),
    version('TERMS_APP', 2, '2026-10-01T00:00:00Z'),
    version('TERMS_APP', 1, '2026-09-01T00:00:00Z'),
    version('TERMS_SPONSOR', 2, LATER),
    version('TERMS_SPONSOR', 1, '2026-09-01T00:00:00Z'),
  ]
  server.datasetQuery.mockReset().mockImplementation(async (_: string, options: { params: { path: {
    resourceId: string } } }) => ({ data: options.params.path.resourceId === DATASET.legalDoc
    ? { items: server.drafts, offset: 0, limit: 100 } : { items: [] } }))
  server.runQuery.mockReset().mockImplementation(async (id: string) =>
    ({ items: id === TEMPLATE.legalVersions ? server.versions : [], offset: 0, limit: 100 }))
  server.runProcess.mockReset().mockImplementation(async (name: string) =>
    (name === 'QB_LEGAL_PUBLISH' ? { kind: 'TERMS_APP', versionNo: 4, effectiveAt: '2026-10-18T00:00:00Z' }
      : { kind: 'TERMS_SPONSOR', versionNo: 2, withdrawnAt: '2026-10-18T00:00:00Z' }))
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/legal']}>
          <LegalPage />
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('LegalPage', () => {
  it('shows each kind\'s draft and versions, a withdrawn one with who withdrew it and why', async () => {
    renderPage()
    const terms = await screen.findByTestId('legal-kind-TERMS_APP')
    await waitFor(() => expect(within(terms).getAllByTestId('legal-version-row')).toHaveLength(3))
    expect(within(terms).getAllByTestId('legal-version-row').map((row) => [row.dataset.version, row.dataset.state]))
      .toEqual([['3', 'withdrawn'], ['2', 'inEffect'], ['1', 'replaced']])
    expect(within(terms).getByTestId('legal-withdrawal')).toHaveTextContent('Announced by mistake')
    expect(within(terms).getByTestId('legal-draft')).toHaveTextContent('revision 4')
    // Only a version announced for later is offered to withdraw.
    expect(within(terms).queryByTestId('legal-withdraw-form')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('legal-kind-TERMS_SPONSOR')).getAllByTestId('legal-withdraw-form'))
      .toHaveLength(1)
    // The first version of a kind always asks for consent.
    expect(within(screen.getByTestId('legal-kind-PRIVACY')).getByTestId('legal-requires-consent')).toBeDisabled()
  })

  it('publishes the draft as read, naming its revision (review L1)', async () => {
    renderPage()
    const terms = await screen.findByTestId('legal-kind-TERMS_APP')
    await within(terms).findByTestId('legal-draft')
    await userEvent.click(within(terms).getByTestId('legal-requires-consent'))
    await userEvent.click(within(terms).getByTestId('legal-publish'))
    await userEvent.click(await screen.findByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('QB_LEGAL_PUBLISH', { docId: 'doc-TERMS_APP',
      requiresConsent: true, baseRevision: 4 }))
    expect(await screen.findByText('Version 4 published.')).toBeInTheDocument()
  })

  it('withdraws an announced version with the reason, after asking (review M1)', async () => {
    renderPage()
    const sponsor = await screen.findByTestId('legal-kind-TERMS_SPONSOR')
    const form = await within(sponsor).findByTestId('legal-withdraw-form')
    expect(within(form).getByTestId('legal-withdraw')).toBeDisabled()
    await userEvent.type(within(form).getByTestId('legal-withdraw-reason'), 'Wrong date')
    await userEvent.click(within(form).getByTestId('legal-withdraw'))
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('QB_LEGAL_WITHDRAW', {
      legalVersionId: 'TERMS_SPONSOR-2', reason: 'Wrong date' }))
    expect(await screen.findByText('Version 2 withdrawn.')).toBeInTheDocument()
  })

  it('says why the server refused, and keeps the dialog open', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'Refused', violations: [{ field: 'legalVersionId',
      ruleCode: 'QB_LEGAL_WITHDRAW_IN_EFFECT', message: 'Version 2 is in effect and cannot be withdrawn.' }] }))
    renderPage()
    const form = await within(await screen.findByTestId('legal-kind-TERMS_SPONSOR'))
      .findByTestId('legal-withdraw-form')
    await userEvent.type(within(form).getByTestId('legal-withdraw-reason'), 'Late')
    await userEvent.click(within(form).getByTestId('legal-withdraw'))
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }))
    expect(await screen.findByText('Version 2 is in effect and cannot be withdrawn.')).toBeInTheDocument()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })
})
