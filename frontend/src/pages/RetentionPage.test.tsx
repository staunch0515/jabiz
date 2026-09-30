import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import RetentionPage from './RetentionPage'

const get = vi.fn()
const exportData = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../api/retention', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/retention')>()),
  exportData: (...args: unknown[]) => exportData(...args),
}))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: () => true }) }))
vi.mock('../meta/hooks', () => ({
  useDatasets: () => ({ data: [{ id: 'urn:jabiz:dataset:platform:LedgerEntry', label: 'Entries' }] }),
}))

const REPORT = {
  today: '2026-01-31', fiscalYearEnd: 12,
  policies: [{ entity: 'FreightCharge', keep: 'P7Y', from: 'shippedTime', fromFiscalYearEnd: true,
    expiredThrough: '2018-12-31', entries: 120, expired: 7, held: 2 }],
}

describe('RetentionPage', () => {
  beforeEach(async () => {
    get.mockReset()
    exportData.mockReset()
    await i18n.changeLanguage('en')
  })

  it('shows what is past its retention and exports the chosen datasets', async () => {
    get.mockResolvedValue(REPORT)
    exportData.mockResolvedValue(undefined)
    render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter>
            <RetentionPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect((await screen.findByTestId('retention-expired-FreightCharge')).textContent).toBe('7')
    expect(get).toHaveBeenCalledWith('/api/retention')
    expect(screen.getByText('after the fiscal year end')).toBeTruthy()
    expect(screen.getByTestId('retention-holds').getAttribute('href'))
      .toBe('/data/urn%3Ajabiz%3Adataset%3Aplatform%3ASysLegalHold')

    fireEvent.mouseDown(screen.getByTestId('export-datasets').querySelector('.ant-select-selector')!)
    fireEvent.click(await screen.findByText('Entries (urn:jabiz:dataset:platform:LedgerEntry)'))
    fireEvent.click(screen.getByTestId('export-submit'))
    await waitFor(() => expect(exportData).toHaveBeenCalledWith(expect.objectContaining({
      datasets: ['urn:jabiz:dataset:platform:LedgerEntry'],
    })))
  })
})
