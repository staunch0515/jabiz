import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import ReportCatalogPage from './ReportCatalogPage'
import ReportPage from './ReportPage'

const post = vi.fn()
const exportQuery = vi.fn()

const runProcess = vi.fn()

vi.mock('../api/reports', () => ({ exportQuery: (...args: unknown[]) => exportQuery(...args) }))
vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => runProcess(...args) }))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: () => true }) }))

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
}))

vi.mock('../meta/hooks', () => ({
  useQueryCatalog: () => ({
    isLoading: false,
    data: [
      {
        id: 'commerce.stock_availability',
        title: 'Stock availability',
        version: '7f350ffac47e39613b436fa0b9ef206758e79effd444e0f612d3e4b682aac47e',
        params: { type: 'object', properties: { warehouseCode: { type: 'string' } }, required: [] },
        results: [
          { name: 'sku', label: 'SKU', kind: { type: 'text' }, operators: ['EQ', 'LIKE'] },
          { name: 'available', label: 'Available', kind: { type: 'numeric', precision: 13, scale: 0 },
            operators: ['GTE'] },
        ],
        filters: [],
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
    ],
  }),
}))

function ArchiveProbe() {
  const [params] = useSearchParams()
  return <span>archive of {params.get('template')}</span>
}

function show(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <App>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/reports" element={<ReportCatalogPage />} />
            <Route path="/reports/run" element={<ReportPage />} />
            <Route path="/reports/archive" element={<ArchiveProbe />} />
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('reports pages', () => {
  beforeEach(async () => {
    post.mockReset()
    exportQuery.mockReset()
    runProcess.mockReset()
    await i18n.changeLanguage('en')
  })

  it('lists the reports the user may run, grouped', () => {
    show('/reports')
    expect(screen.getByRole('link', { name: 'Stock availability' }).getAttribute('href'))
      .toBe('/reports/run?id=commerce.stock_availability')
    expect(screen.getByTestId('report-group-jabiz')).toBeTruthy()
    expect(screen.getByTestId('report-archive-link').getAttribute('href')).toBe('/reports/archive')
    expect(screen.queryByText('Not a report')).toBeNull()
  })

  it('runs a report without required parameters at once, amounts with parentheses', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '-3' }], total: 1, offset: 0, limit: 50 })
    show('/reports/run?id=commerce.stock_availability')

    expect(await screen.findByText('A-1')).toBeTruthy()
    expect(screen.getByText('(3)')).toBeTruthy()
    const [path, request] = post.mock.calls[0] as [string, { params: { path: unknown }; body: Record<string, unknown> }]
    expect(path).toBe('/api/queries/{queryId}')
    expect(request.params.path).toEqual({ queryId: 'commerce.stock_availability' })
    expect(request.body).toMatchObject({ params: {}, offset: 0, limit: 50 })
    // The template accepts a point in time from the request.
    expect(screen.getByText('Effective at')).toBeTruthy()
  })

  it('exports the rows on screen, all of them, in the chosen format', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    exportQuery.mockResolvedValue(undefined)
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')

    fireEvent.mouseEnter(screen.getByTestId('report-export'))
    fireEvent.click(await screen.findByText('Excel (.xlsx)'))

    await waitFor(() => expect(exportQuery).toHaveBeenCalled())
    const [id, format, body] = exportQuery.mock.calls[0] as [string, string, Record<string, unknown>]
    expect([id, format]).toEqual(['commerce.stock_availability', 'xlsx'])
    expect(body).toMatchObject({ params: {}, filters: [], sorts: [] })
    expect(body).not.toHaveProperty('limit')
  })

  it('issues the run on screen and opens the archive of the report', async () => {
    post.mockResolvedValue({ items: [{ sku: 'A-1', available: '3' }], total: 1, offset: 0, limit: 50 })
    runProcess.mockResolvedValue({ runId: 'r-1' })
    show('/reports/run?id=commerce.stock_availability')
    await screen.findByText('A-1')

    fireEvent.click(screen.getByTestId('report-issue'))
    expect(await screen.findByText(/archived for good/)).toBeTruthy()
    // Without a ConfigProvider antd confirms in its default language.
    fireEvent.click(screen.getAllByRole('button').find((b) => /^(OK|确\s*定)$/.test(b.textContent?.trim() ?? ''))!)

    await waitFor(() => expect(runProcess).toHaveBeenCalled())
    expect(runProcess.mock.calls[0]).toEqual(['REPORT_ISSUE', { templateId: 'commerce.stock_availability', params: {},
      asOf: undefined, knownAt: undefined }])
    expect(await screen.findByText('archive of commerce.stock_availability')).toBeTruthy()
  })

  it('waits for the required parameters of a report', async () => {
    show('/reports/run?id=jabiz.ledger.account_balances')
    expect(screen.getByTestId('report-not-run')).toBeTruthy()
    // Its point in time comes from its parameters, so there is no separate picker.
    expect(screen.queryByText('Effective at')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Run/ }))
    await waitFor(() => expect(screen.getByText('asOf: REQUIRED')).toBeTruthy())
    expect(post).not.toHaveBeenCalled()
  })

  it('shows what the server refuses even for a report without a form, and filters only by its columns', async () => {
    post.mockRejectedValue(new ApiError(400, { violations: [{ field: 'id', ruleCode: 'INVALID_VALUE', message: 'bad' }] }))
    show('/reports/run?id=commerce.plain')

    expect(await screen.findByTestId('report-error')).toBeTruthy()
    expect(screen.getByText('id: bad')).toBeTruthy()
    const [, request] = post.mock.calls[0] as [string, { body: Record<string, unknown> }]
    // The page's own state never reaches the filters, even for a column named id.
    expect(request.body.filters).toEqual([])
  })

  it('shows 404 for a template that is not a report', () => {
    show('/reports/run?id=commerce.hidden')
    expect(screen.getByText('commerce.hidden')).toBeTruthy()
  })
})
