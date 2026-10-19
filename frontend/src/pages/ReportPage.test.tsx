import { ApiError } from '@jabiz/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import ReportCatalogPage from './ReportCatalogPage'
import ReportPage from './ReportPage'

const post = vi.fn()
const exportQuery = vi.fn()
const runProcess = vi.fn()
const permissions = { value: new Set(['report.issue', 'report.archive.read']) }
const catalog = { data: [] as unknown[], isLoading: false }

vi.mock('../api/reports', () => ({ exportQuery: (...args: unknown[]) => exportQuery(...args) }))
vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  runProcess: (...args: unknown[]) => runProcess(...args),
  useAuth: () => ({ can: (p: string) => permissions.value.has(p) }),
  api: { POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
}))


vi.mock('../meta/hooks', () => ({ useQueryCatalog: () => catalog }))

const CATALOG = [
  {
    id: 'commerce.stock_availability',
    title: 'Stock availability',
    description: 'What each warehouse holds.',
    version: '7f350ffac47e39613b436fa0b9ef206758e79effd444e0f612d3e4b682aac47e',
    params: { type: 'object', properties: { warehouseCode: { type: 'string' } }, required: [] },
    results: [
      { name: 'sku', label: 'SKU', kind: { type: 'text' }, operators: ['EQ', 'LIKE'] },
      { name: 'available', label: 'Available', kind: { type: 'numeric', precision: 13, scale: 0 },
        operators: ['GTE'] },
    ],
    filters: ['sku'],
    sorts: ['sku'],
    timeTravel: true,
    report: { landscape: false },
  },
  {
    id: 'jabiz.ledger.account_balances',
    title: 'Trial balance',
    version: 'c2741d77d343',
    params: { type: 'object', properties: { asOf: { type: 'string', format: 'date-time' } }, required: ['asOf'] },
    results: [{ name: 'balance', label: 'Balance', kind: { type: 'monetary', currency: 'JPY', scale: 0 } }],
    timeSlice: { knownAt: 'knownAt' },
    timeTravel: false,
    report: {},
  },
  {
    id: 'commerce.plain',
    title: 'Plain report',
    params: { type: 'object', properties: {}, required: [] },
    results: [{ name: 'id', label: 'Id', kind: { type: 'text' }, operators: ['EQ'] }],
    filters: ['id'],
    timeTravel: false,
    report: {},
  },
  { id: 'commerce.hidden', title: 'Not a report', params: { type: 'object', properties: {} } },
]

function ArchiveProbe() {
  const [params] = useSearchParams()
  return <span>archive of {params.get('template')}</span>
}

function show(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/reports" element={<ReportCatalogPage />} />
          <Route path="/reports/run" element={<ReportPage />} />
          <Route path="/reports/archive" element={<ArchiveProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

/** The request bodies of POST /api/queries/{id}, in order. */
const bodies = () => post.mock.calls.map(([, request]) => (request as { body: Record<string, unknown> }).body)

/** Opens a dropdown menu as a pointer does (Radix opens it on pointerdown). */
function press(element: HTMLElement) {
  fireEvent.pointerDown(element, { button: 0, ctrlKey: false, pointerType: 'mouse' })
}

describe('reports pages', () => {
  beforeEach(async () => {
    post.mockReset()
    exportQuery.mockReset()
    runProcess.mockReset()
    permissions.value = new Set(['report.issue', 'report.archive.read'])
    catalog.data = CATALOG
    catalog.isLoading = false
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists the reports the user may run, grouped', async () => {
    show('/reports')
    expect(screen.getByRole('heading', { level: 1, name: 'Reports' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Stock availability' }).getAttribute('href'))
      .toBe('/reports/run?id=commerce.stock_availability')
    expect(screen.getByTestId('report-group-jabiz')).toHaveTextContent('Trial balance')
    expect(screen.getByTestId('report-group-commerce')).toHaveTextContent('What each warehouse holds.')
    expect(screen.getByTestId('report-archive-link').getAttribute('href')).toBe('/reports/archive')
    expect(screen.queryByText('Not a report')).toBeNull()
    await expectAccessible()
  })

  it('offers the archive to its readers only, and says when there is no report', () => {
    permissions.value = new Set()
    catalog.data = [CATALOG[3]]
    show('/reports')
    expect(screen.queryByTestId('report-archive-link')).toBeNull()
    expect(screen.getByText('No reports.').closest('[data-slot="page-state"]')).toHaveAttribute('data-state', 'empty')
  })

  it('runs a report without required parameters at once, amounts with parentheses', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '-3' }], total: 1, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')

    expect(await screen.findByText('A-1')).toBeTruthy()
    expect(screen.getByText('(3)')).toBeTruthy()
    const [path, request] = post.mock.calls[0] as [string, { params: { path: unknown }; body: Record<string, unknown> }]
    expect(path).toBe('/api/queries/{queryId}')
    expect(request.params.path).toEqual({ queryId: 'commerce.stock_availability' })
    expect(request.body).toMatchObject({ params: {}, offset: 0, limit: 50, filters: [], sorts: [] })
    expect(screen.getByTestId('page-title')).toHaveTextContent('Stock availability')
    expect(screen.getByText('Version 7f350ffac47e')).toBeInTheDocument()
    // The template accepts a point in time from the request.
    expect(screen.getByText('Effective at')).toBeTruthy()
    expect(screen.getByTestId('report-as-of')).toHaveAttribute('id')
    await expectAccessible()
  })

  it('filters and sorts within the template whitelist only, from the first page', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 120, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')
    // Not whitelisted for sorting: a plain header; not for filtering: no field.
    expect(within(screen.getByRole('columnheader', { name: 'Available' })).queryByRole('button')).toBeNull()
    const filters = screen.getByRole('search')
    expect(within(filters).queryByLabelText(/Available/)).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    await waitFor(() => expect(bodies().at(-1)).toMatchObject({ offset: 50, limit: 50 }))
    await userEvent.type(within(filters).getByLabelText('SKU'), 'A-')
    await userEvent.click(within(filters).getByRole('button', { name: 'Query' }))
    await waitFor(() =>
      expect(bodies().at(-1)).toMatchObject({ filters: [{ field: 'sku', op: 'like', value: '%A-%' }], offset: 0 }),
    )
    await userEvent.click(within(screen.getByRole('columnheader', { name: /SKU/ })).getByRole('button'))
    await waitFor(() => expect(bodies().at(-1)).toMatchObject({ sorts: [{ field: 'sku', asc: true }], offset: 0 }))
  })

  it('runs again with the parameters and point in time of the form, starting a new table', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')
    await userEvent.type(screen.getByLabelText('warehouseCode'), 'W1')
    await userEvent.type(screen.getByLabelText('Effective at'), '2026-01-31 09:00:00{Enter}')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    const asOf = new Date(2026, 0, 31, 9).toISOString()
    await waitFor(() =>
      expect(bodies().at(-1)).toMatchObject({ params: { warehouseCode: 'W1' }, asOf, knownAt: undefined, offset: 0 }),
    )
    const count = post.mock.calls.length
    // The same values asked again run again.
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(post.mock.calls.length).toBe(count + 1))
  })

  it('does not run while a point in time holds text that is no time', async () => {
    post.mockResolvedValue({ items: [], total: 0, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
    await userEvent.type(screen.getByLabelText('Effective at'), 'yesterday')
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(screen.getByLabelText('Effective at')).toHaveAttribute('aria-invalid', 'true')
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('exports the rows on screen, all of them, in the chosen format', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    exportQuery.mockResolvedValue(undefined)
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')
    await userEvent.type(within(screen.getByRole('search')).getByLabelText('SKU'), 'A')
    await userEvent.click(within(screen.getByRole('search')).getByRole('button', { name: 'Query' }))
    await waitFor(() => expect(bodies().at(-1)).toMatchObject({ filters: [{ field: 'sku' }] }))

    press(screen.getByTestId('report-export'))
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Excel (.xlsx)', 'PDF', 'CSV'])
    await expectAccessible()
    await userEvent.click(screen.getByRole('menuitem', { name: 'Excel (.xlsx)' }))

    await waitFor(() => expect(exportQuery).toHaveBeenCalled())
    const [id, format, body] = exportQuery.mock.calls[0] as [string, string, Record<string, unknown>]
    expect([id, format]).toEqual(['commerce.stock_availability', 'xlsx'])
    expect(body).toMatchObject({ params: {}, filters: [{ field: 'sku', op: 'like', value: '%A%' }], sorts: [] })
    expect(body).not.toHaveProperty('limit')
    expect(body).not.toHaveProperty('offset')
  })

  it('issues the run on screen after a confirmation and opens the archive of the report', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    runProcess.mockResolvedValue({ runId: 'r-1' })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')

    await userEvent.click(screen.getByTestId('report-issue'))
    const question = screen.getByRole('alertdialog', { name: /archived for good/ })
    await expectAccessible()
    await userEvent.click(within(question).getByRole('button', { name: 'Issue' }))

    await waitFor(() => expect(runProcess).toHaveBeenCalled())
    expect(runProcess.mock.calls[0]).toEqual(['REPORT_ISSUE', { templateId: 'commerce.stock_availability', params: {},
      asOf: undefined, knownAt: undefined }])
    expect(await screen.findByText('archive of commerce.stock_availability')).toBeTruthy()
  })

  it('offers issuing to holders of report.issue only, and cancels without issuing', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')
    await userEvent.click(screen.getByTestId('report-issue'))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(runProcess).not.toHaveBeenCalled()
  })

  it('has no issue button without report.issue', async () => {
    permissions.value = new Set()
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')
    expect(screen.queryByTestId('report-issue')).toBeNull()
    expect(screen.getByTestId('report-export')).toBeInTheDocument()
  })

  it('waits for the required parameters of a report', async () => {
    show('/reports/run?id=jabiz.ledger.account_balances')
    expect(screen.getByTestId('report-not-run')).toBeTruthy()
    // Its point in time comes from its parameters, so there is no separate picker.
    expect(screen.queryByText('Effective at')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(screen.getByText('asOf: REQUIRED')).toBeTruthy())
    expect(post).not.toHaveBeenCalled()
    await expectAccessible()
  })

  it('shows what the server refuses even for a report without a form, and filters only by its columns', async () => {
    post.mockRejectedValue(new ApiError(400, { violations: [{ field: 'id', ruleCode: 'INVALID_VALUE', message: 'bad' }] }))
    show('/reports/run?id=commerce.plain')

    expect(await screen.findByTestId('report-error')).toBeTruthy()
    expect(screen.getByText('id: bad')).toBeTruthy()
    const [, request] = post.mock.calls[0] as [string, { body: Record<string, unknown> }]
    // The page's own state never reaches the filters, even for a column named id.
    expect(request.body.filters).toEqual([])
    // Refused once, not asked again.
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('shows "not found" for a template that is not a report', () => {
    show('/reports/run?id=commerce.hidden')
    expect(screen.getByRole('heading', { level: 1, name: 'commerce.hidden' })).toBeTruthy()
  })
})
