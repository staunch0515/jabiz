import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import ImportPage from './ImportPage'
import ImportRunsPage from './ImportRunsPage'

const get = vi.fn()
const put = vi.fn()
const uploadFile = vi.fn()
const inspectFile = vi.fn()
const previewImport = vi.fn()
const commitImport = vi.fn()
const exportImportRun = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), PUT: (...args: unknown[]) => put(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../api/files', () => ({ uploadFile: (...args: unknown[]) => uploadFile(...args) }))
vi.mock('../api/imports', () => ({
  inspectFile: (...args: unknown[]) => inspectFile(...args),
  previewImport: (...args: unknown[]) => previewImport(...args),
  commitImport: (...args: unknown[]) => commitImport(...args),
  exportImportRun: (...args: unknown[]) => exportImportRun(...args),
}))

const ENTRY = {
  id: 'commerce.stock', version: 1, title: 'Stock receipts', filePolicy: 'app.import',
  accept: ['text/plain'], extensions: ['.csv'], mappings: true, externalRef: true, onDuplicate: 'SKIP',
  format: { kind: 'csv', adjustable: true, delimiter: ',', header: true, skipLines: 0, charset: 'UTF-8',
    charsets: ['UTF-8', 'windows-1252'] },
  fields: [
    { name: 'sku', label: 'SKU', kind: { type: 'text' }, required: true, columns: ['SKU'] },
    { name: 'quantity', label: 'Quantity', kind: { type: 'numeric' }, required: true, columns: ['Qty'] },
  ],
  totals: ['quantity'],
}

vi.mock('../meta/hooks', () => ({ useImportCatalog: () => ({ isLoading: false, data: [ENTRY] }) }))

const REPORT = {
  importId: 'commerce.stock', importVersion: 1, fileId: 'f-1', sha256: 'abc', committed: false, accepted: true,
  records: 2, rows: 1, processed: 1, units: 1, duplicates: 1, columns: { sku: 'Item', quantity: 'Qty' }, constants: {},
  totals: { quantity: 5 },
  results: [
    { number: 1, location: 'line 2', status: 'ok', values: { sku: 'A', quantity: 5 } },
    { number: 2, location: 'line 3', status: 'duplicate', values: { sku: 'A', quantity: 5 } },
  ],
  issues: [],
}

function renderPage(path: string, page: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <App>
        <MemoryRouter initialEntries={[path]}>{page}</MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('ImportPage', () => {
  beforeEach(async () => {
    for (const mock of [get, put, uploadFile, inspectFile, previewImport, commitImport, exportImportRun]) mock.mockReset()
    await i18n.changeLanguage('en')
  })

  it('uploads, maps a column, previews every row and commits', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue({
      columns: ['Item', 'Qty'], header: {}, records: 2, suggested: { quantity: 'Qty' }, issues: [
        { row: 0, field: 'sku', code: 'IMPORT_COLUMN_MISSING', message: 'No column is mapped to "SKU"' },
      ],
      sample: [{ number: 1, location: 'line 2', cells: { Item: 'A', Qty: '5' } }],
    })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    commitImport.mockResolvedValue({ committed: true, report: { ...REPORT, committed: true, runId: 'run-1' } })
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)

    const input = (await screen.findByText('Click or drop a file here'), document.querySelector('input[type=file]') as HTMLInputElement)
    fireEvent.change(input, { target: { files: [new File(['Item,Qty\nA,5\n'], 'stock.csv', { type: 'text/csv' })] } })
    await waitFor(() => expect(uploadFile).toHaveBeenCalledWith('app.import', expect.any(File), 'stock.csv'))
    expect(await screen.findByText('No column is mapped to "SKU"')).toBeTruthy()
    expect(within(screen.getByTestId('import-sample')).getByText('A')).toBeTruthy()

    fireEvent.mouseDown(within(screen.getByTestId('import-column-sku')).getByRole('combobox'))
    fireEvent.click(await screen.findByTitle('Item'))
    fireEvent.change(screen.getByTestId('import-constant-quantity'), { target: { value: '' } })

    fireEvent.click(screen.getByTestId('import-preview'))
    await waitFor(() => expect(previewImport).toHaveBeenCalledWith('commerce.stock', 'f-1',
      { columns: { quantity: 'Qty', sku: 'Item' }, constants: {}, options: {} }, undefined))
    expect(await screen.findByText('No problems: the file can be imported.')).toBeTruthy()
    expect(screen.getByTestId('import-total-quantity').textContent).toBe('5')
    expect(within(screen.getByTestId('import-rows')).getByText('Duplicate')).toBeTruthy()

    fireEvent.change(screen.getByTestId('import-notes'), { target: { value: 'counted twice' } })
    fireEvent.click(screen.getByTestId('import-commit'))
    await screen.findByText(/Import this file\?/)
    fireEvent.click(screen.getAllByRole('button', { name: 'Import' }).at(-1)!)
    await waitFor(() => expect(commitImport).toHaveBeenCalledWith('commerce.stock', 'f-1', expect.anything(),
      undefined, 'counted twice'))
    expect(await screen.findByText('run-1')).toBeTruthy()
  })

  it('shows a rejected commit with its problems and cannot commit a file with problems', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-2' })
    inspectFile.mockResolvedValue({ columns: ['SKU', 'Qty'], header: {}, records: 1, suggested: { sku: 'SKU',
      quantity: 'Qty' }, issues: [], sample: [] })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue({ ...REPORT, accepted: false, issues: [
      { row: 1, location: 'line 2', field: 'quantity', code: 'IMPORT_VALUE_INVALID', message: 'Quantity: "x" cannot be read' },
    ] })
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    const input = (await screen.findByText('Click or drop a file here'), document.querySelector('input[type=file]') as HTMLInputElement)
    fireEvent.change(input, { target: { files: [new File(['x'], 'bad.csv')] } })
    fireEvent.click(await screen.findByTestId('import-preview'))
    expect(await screen.findByText('1 problem(s) found.')).toBeTruthy()
    expect(screen.getByText('Quantity: "x" cannot be read')).toBeTruthy()
    expect((screen.getByTestId('import-commit') as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('ImportRunsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    exportImportRun.mockReset()
    await i18n.changeLanguage('en')
  })

  it('lists runs with their outcome, shows the problems and saves the report', async () => {
    get.mockImplementation(async (path: string) => path === '/api/imports/runs'
      ? [{ runId: 'r-1', importId: 'commerce.stock', title: 'Stock receipts', outcome: 'rejected', rows: 1,
        duplicates: 0, issueCount: 1, importedBy: 'alice', importedTime: '2026-02-05T15:00:00Z', notes: 'first try' }]
      : { run: { runId: 'r-1', importId: 'commerce.stock', title: 'Stock receipts', notes: 'first try', sha256: 'abc' },
        issues: [{ row: 1, location: 'line 2', field: 'quantity', code: 'IMPORT_VALUE_INVALID', message: 'bad quantity' }] })
    exportImportRun.mockResolvedValue(undefined)
    renderPage('/imports/runs?import=commerce.stock', <ImportRunsPage />)

    expect(await screen.findByTestId('run-outcome-r-1')).toBeTruthy()
    expect(screen.getByTestId('run-outcome-r-1').textContent).toBe('Rejected')
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { import: 'commerce.stock', limit: 200 } } })

    fireEvent.click(screen.getByTestId('run-open-r-1'))
    expect(await screen.findByText('bad quantity')).toBeTruthy()
    expect(screen.getAllByText('Quantity').length).toBeGreaterThan(0)

    fireEvent.mouseEnter(screen.getByTestId('run-save-r-1'))
    fireEvent.click(await screen.findByText('PDF'))
    await waitFor(() => expect(exportImportRun).toHaveBeenCalledWith('r-1', 'pdf'))
  })
})
