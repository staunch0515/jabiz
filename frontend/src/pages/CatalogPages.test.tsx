import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import DatasetCatalogPage from './DatasetCatalogPage'
import ProcessCatalogPage from './ProcessCatalogPage'

const hooks = vi.hoisted(() => ({
  datasets: { data: undefined as unknown[] | undefined, isLoading: false },
  processes: { data: undefined as unknown[] | undefined, isLoading: false },
}))
vi.mock('../meta/hooks', () => ({
  useDatasets: () => hooks.datasets,
  useProcesses: () => hooks.processes,
}))

const dataset = (n: number, traits: Record<string, boolean> = {}) => ({
  id: `urn:jabiz:dataset:default:E${n}`,
  entity: `E${n}`,
  label: `Entity ${n}`,
  temporal: false,
  readOnly: false,
  processOnlyWrites: false,
  canWrite: false,
  isDefault: true,
  ...traits,
})

function show(page: React.ReactNode) {
  return render(<MemoryRouter>{page}</MemoryRouter>)
}

describe('catalog pages', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
    hooks.datasets = { data: undefined, isLoading: false }
    hooks.processes = { data: undefined, isLoading: false }
  })

  it('lists the datasets with their traits, a copyable id and no pager for one page', async () => {
    hooks.datasets.data = [dataset(1, { temporal: true, canWrite: true }), dataset(2, { readOnly: true })]
    show(<DatasetCatalogPage />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Data views')
    const link = screen.getByTestId('dataset-E1')
    expect(link).toHaveAttribute('href', '/data/urn%3Ajabiz%3Adataset%3Adefault%3AE1')
    const row = link.closest('tr')!
    expect(within(row).getByText('History kept')).toBeInTheDocument()
    expect(within(row).getByText('Editable')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Copy urn:jabiz:dataset:default:E1' })).toBeInTheDocument()
    expect(within(screen.getByTestId('dataset-E2').closest('tr')!).getByText('Read only')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull()
    await expectAccessible()
  })

  it('names the copy button in the word order of the language', async () => {
    await act(() => i18n.changeLanguage('ja'))
    hooks.datasets.data = [dataset(1)]
    show(<DatasetCatalogPage />)
    expect(screen.getByRole('button', { name: 'urn:jabiz:dataset:default:E1 をコピー' })).toBeInTheDocument()
  })

  it('searches the datasets by name or id and sorts them by a column (phase 17w)', async () => {
    hooks.datasets.data = [dataset(2), { ...dataset(10), label: 'Carrier rates' }, dataset(1)]
    show(<DatasetCatalogPage />)
    const names = () => screen.getAllByRole('link').map((link) => link.textContent)
    // By name at first, as the language sorts.
    expect(names()).toEqual(['Carrier rates', 'Entity 1', 'Entity 2'])
    await userEvent.click(screen.getByRole('button', { name: 'Entity' }))
    expect(names()).toEqual(['Entity 2', 'Entity 1', 'Carrier rates'])
    // By id, numbers in order (E1, E2, E10).
    await userEvent.click(screen.getByRole('button', { name: 'View' }))
    expect(names()).toEqual(['Entity 1', 'Entity 2', 'Carrier rates'])
    await userEvent.type(screen.getByTestId('list-search'), 'carrier')
    expect(names()).toEqual(['Carrier rates'])
    expect(screen.getByTestId('list-search-count')).toHaveTextContent('1 of 3')
    await userEvent.clear(screen.getByTestId('list-search'))
    await userEvent.type(screen.getByTestId('list-search'), 'dataset:default:E1 ')
    expect(names()).toEqual(['Entity 1', 'Carrier rates'])
    await userEvent.type(screen.getByTestId('list-search'), 'nothing')
    expect(screen.getByText('Nothing matches the search.')).toBeInTheDocument()
    await expectAccessible()
  })

  it('searches the processes by name, id or description', async () => {
    hooks.processes.data = [
      { name: 'SEC_USER_INVITE', version: 1, label: 'Invite a user', description: 'Mails a link', latest: true },
      { name: 'LEDGER_POST', version: 2, label: 'Post a transaction', description: 'Books the lines', latest: true },
    ]
    show(<ProcessCatalogPage />)
    await userEvent.type(screen.getByTestId('list-search'), 'ledger')
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual(['Post a transaction'])
    await userEvent.clear(screen.getByTestId('list-search'))
    await userEvent.type(screen.getByTestId('list-search'), 'mails')
    expect(screen.getAllByRole('link').map((link) => link.textContent)).toEqual(['Invite a user'])
  })

  it('pages 50 at a time in the browser', async () => {
    hooks.datasets.data = Array.from({ length: 51 }, (_, i) => dataset(i + 1))
    show(<DatasetCatalogPage />)
    expect(screen.getAllByRole('link')).toHaveLength(50)
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Next page' }))
    expect(screen.getAllByRole('link')).toHaveLength(1)
    expect(screen.getByTestId('dataset-E51')).toBeInTheDocument()
  })

  it('says when there is nothing to use', () => {
    hooks.datasets.data = []
    show(<DatasetCatalogPage />)
    expect(screen.getByText('Nothing you may use yet.')).toBeInTheDocument()
    hooks.processes.data = []
    show(<ProcessCatalogPage />)
    expect(screen.getAllByText('Nothing you may use yet.')).toHaveLength(2)
  })

  it('lists the processes with their versions and marks', async () => {
    hooks.processes.data = [
      { name: 'PRICE_ADJUST', version: 1, label: 'Adjust a price', latest: false, deprecated: true, description: 'Old' },
      { name: 'PRICE_ADJUST', version: 2, label: 'Adjust a price', latest: true, deprecated: false, description: 'New' },
    ]
    show(<ProcessCatalogPage />)
    expect(screen.getByTestId('process-PRICE_ADJUST-1')).toHaveAttribute('href', '/processes/PRICE_ADJUST/1')
    expect(within(screen.getByTestId('process-PRICE_ADJUST-1').closest('tr')!).getByText('deprecated')).toBeInTheDocument()
    expect(within(screen.getByTestId('process-PRICE_ADJUST-2').closest('tr')!).getByText('latest')).toBeInTheDocument()
    await expectAccessible()
  })
})
