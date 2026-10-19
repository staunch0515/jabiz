import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import ImportCatalogPage from './ImportCatalogPage'
import ImportPage from './ImportPage'
import ImportReportView from './ImportReportView'
import ImportRunsPage from './ImportRunsPage'

const get = vi.fn()
const put = vi.fn()
const uploadFile = vi.fn()
const inspectFile = vi.fn()
const previewImport = vi.fn()
const commitImport = vi.fn()
const exportImportRun = vi.fn()

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), PUT: (...args: unknown[]) => put(...args) },
  unwrap: (value: unknown) => value,
  uploadFile: (...args: unknown[]) => uploadFile(...args),
}))
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
const OPENING = {
  ...ENTRY, id: 'jabiz.opening', title: 'Opening balances', mappings: false,
  format: { kind: 'xlsx', adjustable: false }, extensions: ['.xlsx'],
  params: { type: 'object', properties: { period: { type: 'string' } }, required: ['period'] },
}
const catalog = { isLoading: false, data: [ENTRY, OPENING] as unknown[] }

vi.mock('../meta/hooks', () => ({ useImportCatalog: () => catalog }))

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

const INSPECTION = {
  columns: ['Item', 'Qty'], header: {}, records: 2, suggested: { quantity: 'Qty' }, issues: [
    { row: 0, field: 'sku', code: 'IMPORT_COLUMN_MISSING', message: 'No column is mapped to "SKU"' },
  ],
  sample: [{ number: 1, location: 'line 2', cells: { Item: 'A', Qty: '5' } }],
}

function renderPage(path: string, page: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>{page}</MemoryRouter>
    </QueryClientProvider>,
  )
}

/** Chooses an option of a combobox. */
async function choose(name: string | RegExp, option: string) {
  await userEvent.click(await screen.findByRole('combobox', { name }))
  await userEvent.click(await screen.findByRole('option', { name: option }))
}

/** Uploads a file through the drop area's file input. */
async function upload(name = 'stock.csv') {
  const input = await screen.findByTestId('import-upload')
  fireEvent.change(input, { target: { files: [new File(['Item,Qty\nA,5\n'], name, { type: 'text/csv' })] } })
}

/** The steps' states, in order. */
const steps = () =>
  within(screen.getByRole('list', { name: 'Progress' })).getAllByRole('listitem').map((s) => s.getAttribute('data-state'))

describe('ImportCatalogPage', () => {
  beforeEach(async () => {
    catalog.data = [ENTRY, OPENING]
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists the imports with their fields and file types, and links the history', async () => {
    renderPage('/imports', <ImportCatalogPage />)
    expect(screen.getByRole('heading', { level: 1, name: 'Imports' })).toBeInTheDocument()
    expect(screen.getByTestId('import-commerce.stock').getAttribute('href')).toBe('/imports/run?id=commerce.stock')
    expect(screen.getAllByText('SKU · Quantity')).toHaveLength(2)
    expect(screen.getByText('.xlsx')).toBeInTheDocument()
    expect(screen.getByTestId('import-history-link').getAttribute('href')).toBe('/imports/runs')
    await expectAccessible()
  })

  it('says when there is no import', () => {
    catalog.data = []
    renderPage('/imports', <ImportCatalogPage />)
    expect(screen.getByText('No imports.').closest('[data-slot="page-state"]')).toHaveAttribute('data-state', 'empty')
  })
})

describe('ImportPage', () => {
  beforeEach(async () => {
    for (const mock of [get, put, uploadFile, inspectFile, previewImport, commitImport, exportImportRun]) mock.mockReset()
    catalog.data = [ENTRY, OPENING]
    await act(() => i18n.changeLanguage('en'))
  })

  it('uploads, maps a column, previews every row and commits', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue(INSPECTION)
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    commitImport.mockResolvedValue({ committed: true, report: { ...REPORT, committed: true, runId: 'run-1' } })
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)

    expect(steps()).toEqual(['current', 'upcoming', 'upcoming', 'upcoming'])
    await upload()
    await waitFor(() => expect(uploadFile).toHaveBeenCalledWith('app.import', expect.any(File), 'stock.csv'))
    expect(await screen.findByText('No column is mapped to "SKU"')).toBeTruthy()
    expect(inspectFile).toHaveBeenCalledWith('commerce.stock', 'f-1', {})
    expect(within(screen.getByTestId('import-sample')).getByText('A')).toBeTruthy()
    expect(steps()).toEqual(['complete', 'current', 'upcoming', 'upcoming'])
    // The suggested column is taken; the drop area names the file.
    expect(screen.getByRole('combobox', { name: 'Source column of Quantity' })).toHaveTextContent('Qty')
    // The drop area names the file and what dropping another does.
    expect(screen.getByTestId('import-upload')).toHaveAccessibleName('stock.csv — click or drop a file to replace it')
    await expectAccessible()

    await choose('Source column of SKU', 'Item')
    fireEvent.change(screen.getByTestId('import-constant-quantity'), { target: { value: '' } })

    await userEvent.click(screen.getByTestId('import-preview'))
    await waitFor(() => expect(previewImport).toHaveBeenCalledWith('commerce.stock', 'f-1',
      { columns: { quantity: 'Qty', sku: 'Item' }, constants: {}, options: {} }, undefined))
    expect(await screen.findByText('No problems: the file can be imported.')).toBeTruthy()
    expect(screen.getByTestId('import-total-quantity').textContent).toBe('5')
    expect(within(screen.getByTestId('import-rows')).getByText('Duplicate')).toBeTruthy()
    expect(steps()).toEqual(['complete', 'complete', 'current', 'upcoming'])

    await userEvent.type(screen.getByTestId('import-notes'), 'counted twice')
    await userEvent.click(screen.getByTestId('import-commit'))
    const question = screen.getByRole('alertdialog', { name: /Import this file\?/ })
    await expectAccessible()
    await userEvent.click(within(question).getByRole('button', { name: 'Import' }))
    await waitFor(() => expect(commitImport).toHaveBeenCalledWith('commerce.stock', 'f-1', expect.anything(),
      undefined, 'counted twice'))
    expect(await screen.findByText('run-1')).toBeTruthy()
    expect(steps()).toEqual(['complete', 'complete', 'complete', 'current'])
    // Imported: nothing more to preview or commit.
    expect(screen.queryByTestId('import-commit')).toBeNull()
    expect(screen.getByTestId('import-preview')).toBeDisabled()
  })

  it('adjusts the layout of a CSV file and reads it again', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue(INSPECTION)
    get.mockResolvedValue([])
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()
    await screen.findByTestId('import-layout')

    await choose('Delimiter', ';')
    await userEvent.clear(screen.getByLabelText('Lines to skip'))
    await userEvent.type(screen.getByLabelText('Lines to skip'), '1')
    await choose('Encoding', 'windows-1252')
    await userEvent.click(screen.getByRole('switch', { name: 'Header' }))
    await userEvent.click(screen.getByTestId('import-reread'))
    await waitFor(() => expect(inspectFile).toHaveBeenLastCalledWith('commerce.stock', 'f-1',
      { delimiter: ';', skipLines: 1, charset: 'windows-1252', header: false }))
  })

  it('saves a mapping and applies a saved one', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue(INSPECTION)
    const savedMapping = { columns: { sku: 'Item', quantity: 'Qty' }, constants: {}, options: { delimiter: ';' } }
    get.mockResolvedValue([{ name: 'shop', mapping: savedMapping }])
    put.mockResolvedValue(undefined)
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()

    await choose('Saved mappings', 'shop')
    await waitFor(() => expect(inspectFile).toHaveBeenLastCalledWith('commerce.stock', 'f-1', { delimiter: ';' }))
    expect(screen.getByRole('combobox', { name: 'Source column of SKU' })).toHaveTextContent('Item')
    expect(screen.getByTestId('import-mapping-name')).toHaveValue('shop')

    await userEvent.clear(screen.getByTestId('import-mapping-name'))
    await userEvent.type(screen.getByTestId('import-mapping-name'), 'shop 2')
    await userEvent.click(screen.getByRole('button', { name: 'Save mapping' }))
    await waitFor(() => expect(put).toHaveBeenCalledWith('/api/imports/{importId}/mappings/{name}', {
      params: { path: { importId: 'commerce.stock', name: 'shop 2' } },
      body: { mapping: { columns: { sku: 'Item', quantity: 'Qty' }, constants: {}, options: { delimiter: ';' } } },
    }))
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
    await upload('bad.csv')
    await userEvent.click(await screen.findByTestId('import-preview'))
    expect(await screen.findByText('1 problem(s) found.')).toBeTruthy()
    expect(screen.getByText('Quantity: "x" cannot be read')).toBeTruthy()
    expect(screen.getByText('Fix the file or the mapping and preview again.')).toBeTruthy()
    expect(screen.getByTestId('import-commit')).toBeDisabled()
  })

  it('shows the report of a commit the server rejected', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-3' })
    inspectFile.mockResolvedValue({ ...INSPECTION, issues: [] })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    commitImport.mockResolvedValue({ committed: false, report: { ...REPORT, accepted: false, runId: 'run-9', issues: [
      { row: 2, location: 'line 3', field: 'sku', code: 'IMPORT_ROW_REJECTED', message: 'Unknown SKU' },
    ] } })
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()
    await userEvent.click(await screen.findByTestId('import-preview'))
    await screen.findByText('No problems: the file can be imported.')
    await userEvent.click(screen.getByTestId('import-commit'))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Import' }))
    expect(await screen.findByText('Nothing was imported; the attempt is recorded.')).toBeInTheDocument()
    expect(screen.getByText('Unknown SKU')).toBeInTheDocument()
    expect(screen.getByTestId('import-commit')).toBeDisabled()
    expect(steps()).toEqual(['complete', 'complete', 'complete', 'current'])
  })

  it('checks the parameters before previewing, and sends them with the preview and the commit', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-4' })
    inspectFile.mockResolvedValue({ ...INSPECTION, issues: [] })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    commitImport.mockResolvedValue({ committed: true, report: { ...REPORT, committed: true, runId: 'run-2' } })
    renderPage('/imports/run?id=jabiz.opening', <ImportPage />)
    await upload('opening.xlsx')
    const parameters = await screen.findByRole('group', { name: 'Parameters' })
    // A layout that cannot be adjusted offers no controls for it.
    expect(screen.queryByTestId('import-layout')).toBeNull()

    await userEvent.click(screen.getByTestId('import-preview'))
    expect(within(parameters).getByText('period: REQUIRED')).toBeInTheDocument()
    expect(previewImport).not.toHaveBeenCalled()

    await userEvent.type(within(parameters).getByLabelText(/period/), '2026-01')
    await userEvent.click(screen.getByTestId('import-preview'))
    await waitFor(() => expect(previewImport).toHaveBeenCalledWith('jabiz.opening', 'f-4', expect.anything(),
      { period: '2026-01' }))
    await userEvent.click(await screen.findByTestId('import-commit'))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Import' }))
    await waitFor(() => expect(commitImport).toHaveBeenCalledWith('jabiz.opening', 'f-4', expect.anything(),
      { period: '2026-01' }, undefined))
  })

  it('lets a number of the layout be retyped, taking its fallback only when left empty', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue(INSPECTION)
    get.mockResolvedValue([])
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()
    const skip = await screen.findByLabelText('Lines to skip')
    await userEvent.clear(skip)
    await userEvent.type(skip, '1')
    await userEvent.clear(skip)
    // Empty while typing: not the fallback 0, so the next digit does not become "02".
    expect(skip).toHaveValue('')
    await userEvent.type(skip, '2')
    expect(skip).toHaveValue('2')
    await userEvent.click(screen.getByTestId('import-reread'))
    await waitFor(() => expect(inspectFile).toHaveBeenLastCalledWith('commerce.stock', 'f-1', { skipLines: 2 }))

    await userEvent.clear(skip)
    await userEvent.tab()
    expect(skip).toHaveValue('0')
    await userEvent.click(screen.getByTestId('import-reread'))
    await waitFor(() => expect(inspectFile).toHaveBeenLastCalledWith('commerce.stock', 'f-1', { skipLines: 0 }))
  })

  it('shows the saved mapping in use: none for a new file, the new name after saving', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue(INSPECTION)
    const savedMapping = { columns: { sku: 'Item' }, constants: {}, options: {} }
    get.mockResolvedValue([{ name: 'shop', mapping: savedMapping }, { name: 'shop 2', mapping: savedMapping }])
    put.mockResolvedValue(undefined)
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()
    await choose('Saved mappings', 'shop')
    expect(screen.getByRole('combobox', { name: 'Saved mappings' })).toHaveTextContent('shop')

    await userEvent.clear(screen.getByTestId('import-mapping-name'))
    await userEvent.type(screen.getByTestId('import-mapping-name'), 'shop 2')
    await userEvent.click(screen.getByRole('button', { name: 'Save mapping' }))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Saved mappings' })).toHaveTextContent('shop 2'))

    await upload('next.csv')
    await waitFor(() => expect(uploadFile).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Saved mappings' })).toHaveTextContent('Choose…'))
  })

  it('drops the preview when the mapping changes, so nothing unpreviewed can be committed', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-1' })
    inspectFile.mockResolvedValue({ ...INSPECTION, issues: [] })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    renderPage('/imports/run?id=commerce.stock', <ImportPage />)
    await upload()
    await userEvent.click(await screen.findByTestId('import-preview'))
    expect(await screen.findByTestId('import-commit')).toBeEnabled()

    await userEvent.type(screen.getByTestId('import-constant-sku'), 'X')
    expect(screen.queryByTestId('import-commit')).toBeNull()
    expect(screen.queryByTestId('import-report')).toBeNull()
    expect(steps()).toEqual(['complete', 'current', 'upcoming', 'upcoming'])
    await userEvent.click(screen.getByTestId('import-preview'))
    expect(await screen.findByTestId('import-commit')).toBeEnabled()
    expect(previewImport).toHaveBeenLastCalledWith('commerce.stock', 'f-1',
      expect.objectContaining({ constants: { sku: 'X' } }), undefined)
    expect(commitImport).not.toHaveBeenCalled()
  })

  it('does not commit parameters that were changed after the preview', async () => {
    uploadFile.mockResolvedValue({ fileId: 'f-4' })
    inspectFile.mockResolvedValue({ ...INSPECTION, issues: [] })
    get.mockResolvedValue([])
    previewImport.mockResolvedValue(REPORT)
    renderPage('/imports/run?id=jabiz.opening', <ImportPage />)
    await upload('opening.xlsx')
    const parameters = await screen.findByRole('group', { name: 'Parameters' })
    await userEvent.type(within(parameters).getByLabelText(/period/), '2026-01')
    await userEvent.click(screen.getByTestId('import-preview'))
    await userEvent.type(within(parameters).getByLabelText(/period/), 'X')
    await userEvent.click(await screen.findByTestId('import-commit'))
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Import' }))
    await waitFor(() => expect(screen.queryByTestId('import-commit')).toBeNull())
    expect(commitImport).not.toHaveBeenCalled()
    expect(previewImport).toHaveBeenCalledTimes(1)
  })

  it('shows "not found" for an unknown import', () => {
    renderPage('/imports/run?id=nope', <ImportPage />)
    expect(screen.getByRole('heading', { level: 1, name: 'nope' })).toBeInTheDocument()
  })
})

describe('ImportReportView', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('shows the figures and control totals, and filters the records by outcome', async () => {
    renderPage('/', <ImportReportView entry={ENTRY as never} report={{ ...REPORT, results: [...REPORT.results,
      { number: 3, location: 'line 4', status: 'error', values: { sku: 'B' } }] } as never} />)
    const figures = screen.getAllByRole('term').map((term) => term.textContent)
    expect(figures).toEqual(['Records', 'Rows to import', 'Already imported', 'Processed', 'Total Quantity'])
    expect(screen.getByText('1 / 1')).toBeInTheDocument()
    const rows = () => within(screen.getByTestId('import-rows')).getAllByRole('row').slice(1)
    expect(rows()).toHaveLength(3)
    const show = screen.getByRole('radiogroup', { name: 'Show' })
    await userEvent.click(within(show).getByRole('radio', { name: 'Errors' }))
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toHaveTextContent('Error')
    await userEvent.click(within(show).getByRole('radio', { name: 'Duplicates' }))
    expect(rows()).toHaveLength(1)
    expect(rows()[0]).toHaveTextContent('Duplicate')
    await expectAccessible()
  })
})

describe('ImportReportView paging', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('starts another choice of what to show at its first page', async () => {
    const results = Array.from({ length: 120 }, (_, i) => ({
      number: i + 1, location: `line ${i + 2}`, status: i < 60 ? 'ok' : 'error', values: { sku: `S${i + 1}` },
    }))
    renderPage('/', <ImportReportView entry={ENTRY as never} report={{ ...REPORT, results } as never} />)
    const firstRow = () => within(screen.getByTestId('import-rows')).getAllByRole('row')[1]
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(firstRow()).toHaveTextContent('S51')
    await userEvent.click(screen.getByRole('radio', { name: 'Errors' }))
    expect(firstRow()).toHaveTextContent('S61')
  })
})

describe('ImportRunsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    exportImportRun.mockReset()
    catalog.data = [ENTRY, OPENING]
    await act(() => i18n.changeLanguage('en'))
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
    await expectAccessible()

    await userEvent.click(screen.getByTestId('run-open-r-1'))
    const sheet = await screen.findByRole('dialog', { name: 'Stock receipts' })
    expect(within(sheet).getByText('bad quantity')).toBeTruthy()
    // The field by its label, from the import's definition.
    expect(within(sheet).getByText('Quantity')).toBeTruthy()
    expect(within(sheet).getByRole('button', { name: 'Copy SHA-256' })).toBeInTheDocument()
    await expectAccessible()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    fireEvent.pointerDown(screen.getByTestId('run-save-r-1'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    await userEvent.click(await screen.findByRole('menuitem', { name: 'PDF' }))
    await waitFor(() => expect(exportImportRun).toHaveBeenCalledWith('r-1', 'pdf'))
  })

  it('lists the runs of every import without a filter', async () => {
    get.mockResolvedValue([])
    renderPage('/imports/runs', <ImportRunsPage />)
    await waitFor(() => expect(get).toHaveBeenCalled())
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { import: undefined, limit: 200 } } })
    expect(await screen.findByText('No data')).toBeInTheDocument()
  })
})
