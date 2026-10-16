import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { TEMPLATE } from './ids'
import JournalMappingPage from './JournalMappingPage'
import JournalPage from './JournalPage'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the journal pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  templates: new Map<string, Row[]>(),
  preview: {} as Row,
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  sessionFetch: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    sessionFetch: server.sessionFetch,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const SPONSOR = '0192f0a0-0000-7000-8000-00000000aa01'
const EXPORT = '0192f0a0-0000-7000-8000-00000000ee01'
const FILE = '0192f0a0-0000-7000-8000-00000000f001'
const DETAIL = '0192f0a0-0000-7000-8000-00000000f002'

const FINANCE = ['qb.admin.finance.read', 'qb.journal.export', 'qb.journal.confirm', 'file.generated.read']

function line(lineNo: number, accountCode: string, side: string, amount: number, change: Row = {}): Row {
  return { lineNo, accountCode, side, amount, transactions: 1, externalCode: `X${accountCode}`, sponsorId: null,
    ...change }
}

function preview(change: Row = {}): Row {
  return {
    month: '2026-10', periodFrom: '2026-10-01', periodTo: '2026-10-31', periodEnded: true,
    periodEnd: '2026-10-31T15:00:00Z', previousVoucher: 'QBJ-2026-00009', knownAt: '2026-11-02T00:50:00Z',
    reconStatus: 'OK', reconTime: '2026-11-01T18:37:00Z', reconOk: true, unmapped: [], invalid: [],
    lines: [line(1, '1110', 'DEBIT', 5000), line(2, '2110', 'CREDIT', 5000, { sponsorId: SPONSOR })],
    transactionCount: 1, debitTotal: 5000, creditTotal: 5000, balanced: true, ready: true, ...change,
  }
}

function exported(change: Row = {}): Row {
  return { exportId: EXPORT, periodFrom: '2026-09-01', periodTo: '2026-09-30', voucherNo: 'QBJ-2026-00009',
    lineCount: 4, transactionCount: 3, debitTotal: 7000, creditTotal: 7000, knownAt: '2026-10-02T00:50:00Z',
    exportedBy: SPONSOR, fileId: FILE, fileSha256: 'a'.repeat(64), detailFileId: DETAIL,
    detailSha256: 'b'.repeat(64), externalVoucherNo: null, confirmedBy: null, ...change }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(FINANCE)
  server.preview = preview()
  server.templates = new Map<string, Row[]>([
    [TEMPLATE.journalExports, [exported()]],
    [TEMPLATE.journalMapping, [
      { accountCode: '1110', accountName: 'Stripe balance', paramKey: 'qb.journal.account.1110', externalCode: '111',
        paramExists: true },
      { accountCode: '2110', accountName: 'Sponsor prepayments', paramKey: 'qb.journal.account.2110',
        externalCode: null, paramExists: true },
      { accountCode: '5110', accountName: 'Payment fees', paramKey: 'qb.journal.account.5110', externalCode: null,
        paramExists: false },
    ]],
  ])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { offset?: number; limit?: number;
    count?: boolean } = {}) => {
    const all = server.templates.get(id) ?? []
    const offset = options.offset ?? 0
    const limit = options.limit ?? 100
    return { items: all.slice(offset, offset + limit), offset, limit, ...(options.count ? { total: all.length } : {}) }
  })
  server.runProcess.mockReset().mockImplementation(async (name: string) => {
    if (name === 'QB_JOURNAL_PREVIEW') return server.preview
    if (name === 'QB_JOURNAL_EXPORT') return { exportId: 'new', voucherNo: 'QBJ-2026-00010', lineCount: 2 }
    return { confirmationId: 'c1' }
  })
  server.sessionFetch.mockReset().mockResolvedValue(new Response('"voucher_no"\r\n', {
    status: 200, headers: { 'Content-Disposition': 'attachment; filename="QBJ-2026-00009.csv"' } }))
  URL.createObjectURL = vi.fn(() => 'blob:journal')
  URL.revokeObjectURL = vi.fn()
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

function renderAt(path: string, route: string, element: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('JournalPage', () => {
  it('previews the next month with its lines and exports it after asking', async () => {
    renderAt('/qb/finance/journal', '/qb/finance/journal', <JournalPage />)
    await userEvent.click(await screen.findByTestId('journal-preview'))
    const result = await screen.findByTestId('journal-preview-result')
    expect(result).toHaveAttribute('data-month', '2026-10')
    expect(within(result).getAllByTestId('journal-line').map((row) => [row.dataset.account, row.dataset.side,
      row.dataset.amount])).toEqual([['1110', 'DEBIT', '5000'], ['2110', 'CREDIT', '5000']])
    expect(within(result).getByTestId('user-name')).toHaveTextContent(SPONSOR)
    expect(screen.queryByTestId('journal-blockers')).not.toBeInTheDocument()

    await userEvent.click(screen.getByTestId('journal-export'))
    await userEvent.click(await screen.findByRole('button', { name: 'Export' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('QB_JOURNAL_EXPORT', { month: '2026-10' }))
    expect(await screen.findByText('Exported as QBJ-2026-00010.')).toBeInTheDocument()
  })

  it('says what stops the export and does not offer it', async () => {
    server.preview = preview({ periodEnded: false, reconOk: false, reconStatus: 'DIFF', unmapped: ['2110', '5110'],
      lines: [line(1, '2110', 'CREDIT', 5000, { externalCode: null })], ready: false })
    renderAt('/qb/finance/journal', '/qb/finance/journal', <JournalPage />)
    await userEvent.click(await screen.findByTestId('journal-preview'))
    const blockers = await screen.findByTestId('journal-blockers')
    expect(within(blockers).getByTestId('journal-blocker-open')).toBeInTheDocument()
    expect(within(blockers).getByTestId('journal-blocker-recon')).toHaveTextContent('DIFF')
    expect(within(blockers).getByTestId('journal-blocker-unmapped')).toHaveTextContent('2110, 5110')
    expect(within(blockers).getByTestId('journal-mapping-link')).toHaveAttribute('href', '/qb/finance/journal/mapping')
    expect(screen.getByText('Not mapped')).toBeInTheDocument()
    expect(screen.queryByTestId('journal-export')).not.toBeInTheDocument()
  })

  it('tells why the preview was refused', async () => {
    server.runProcess.mockRejectedValueOnce(new ApiError(422, { detail: 'Nothing has been posted yet',
      violations: [{ ruleCode: 'QB_JOURNAL_NOTHING_POSTED', message: 'Nothing has been posted yet' }] }))
    renderAt('/qb/finance/journal', '/qb/finance/journal', <JournalPage />)
    await userEvent.click(await screen.findByTestId('journal-preview'))
    expect(await screen.findByText(/Nothing has been posted yet/)).toBeInTheDocument()
    expect(screen.queryByTestId('journal-preview-result')).not.toBeInTheDocument()
  })

  it('downloads the kept files and records the accounting system’s number', async () => {
    renderAt('/qb/finance/journal', '/qb/finance/journal', <JournalPage />)
    const row = await screen.findByTestId('journal-export-row')
    await userEvent.click(within(row).getByTestId('journal-download'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalledWith(`/api/generated-files/${FILE}`))
    await userEvent.click(within(row).getByTestId('journal-download-detail'))
    await waitFor(() => expect(server.sessionFetch).toHaveBeenCalledWith(`/api/generated-files/${DETAIL}`))

    await userEvent.type(within(row).getByTestId('journal-confirm-input'), ' MF-1001 ')
    await userEvent.click(within(row).getByTestId('journal-confirm-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('QB_JOURNAL_EXPORT_CONFIRM',
      { exportId: EXPORT, externalVoucherNo: 'MF-1001' }))
  })

  it('offers only what the permissions allow', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    server.templates.set(TEMPLATE.journalExports, [exported({ externalVoucherNo: 'MF-0009' })])
    renderAt('/qb/finance/journal', '/qb/finance/journal', <JournalPage />)
    const row = await screen.findByTestId('journal-export-row')
    expect(within(row).getByTestId('journal-external')).toHaveTextContent('MF-0009')
    expect(screen.queryByTestId('journal-next')).not.toBeInTheDocument()
    expect(within(row).queryByTestId('journal-download')).not.toBeInTheDocument()
    expect(server.runProcess).not.toHaveBeenCalled()
  })
})

describe('JournalMappingPage', () => {
  it('shows each account’s code and leads to the controlled change', async () => {
    server.permissions = new Set(['qb.admin.finance.read', 'control.propose'])
    renderAt('/qb/finance/journal/mapping', '/qb/finance/journal/mapping', <JournalMappingPage />)
    const rows = await screen.findAllByTestId('mapping-row')
    expect(rows.map((row) => [row.dataset.account, row.dataset.code])).toEqual([['1110', '111'], ['2110', ''],
      ['5110', '']])
    expect(within(rows[2]).getByText('Not published yet')).toBeInTheDocument()
    expect(screen.getByTestId('mapping-propose')).toHaveAttribute('href', '/processes/CONTROL_CHANGE_PROPOSE/1')
    expect(screen.queryByTestId('mapping-publish')).not.toBeInTheDocument()
  })

  it('says who may change it to those who may not', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderAt('/qb/finance/journal/mapping', '/qb/finance/journal/mapping', <JournalMappingPage />)
    expect(await screen.findByTestId('mapping-no-control')).toBeInTheDocument()
    expect(screen.queryByTestId('mapping-propose')).not.toBeInTheDocument()
  })
})
