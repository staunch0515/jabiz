import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import RetentionPage from './RetentionPage'

const user = () => userEvent.setup()

const get = vi.fn()
const exportData = vi.fn()

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: (value: unknown) => value,
  useAuth: () => ({ can: () => true }),
}))
vi.mock('../api/retention', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/retention')>()),
  exportData: (...args: unknown[]) => exportData(...args),
}))
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
        <MemoryRouter>
          <RetentionPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect((await screen.findByTestId('retention-expired-FreightCharge')).textContent).toBe('7')
    expect(get).toHaveBeenCalledWith('/api/retention')
    expect(screen.getByText('after the fiscal year end')).toBeTruthy()
    expect(screen.getByTestId('retention-holds').getAttribute('href'))
      .toBe('/data/urn%3Ajabiz%3Adataset%3Aplatform%3ASysLegalHold')

    // At least one dataset.
    fireEvent.click(screen.getByTestId('export-submit'))
    expect(await screen.findByText('Choose at least one dataset.')).toBeTruthy()
    expect(exportData).not.toHaveBeenCalled()

    await user().click(screen.getByRole('combobox', { name: 'Datasets' }))
    await user().click(await screen.findByRole('option', { name: /Entries \(urn:jabiz:dataset:platform:LedgerEntry\)/ }))
    expect(within(screen.getByTestId('export-chosen')).getByText('Entries (urn:jabiz:dataset:platform:LedgerEntry)'))
      .toBeTruthy()
    await user().click(screen.getByRole('checkbox', { name: 'Add the issued reports' }))
    await user().type(screen.getByLabelText('Issued from'), '2026-01-01{Enter}')
    await user().click(screen.getByTestId('export-submit'))
    await waitFor(() => expect(exportData).toHaveBeenCalledWith({
      datasets: ['urn:jabiz:dataset:platform:LedgerEntry'],
      asOf: undefined,
      reports: true,
      reportsFrom: new Date(2026, 0, 1).toISOString(),
      reportsTo: undefined,
    }))
    await expectAccessible()
  })
})
