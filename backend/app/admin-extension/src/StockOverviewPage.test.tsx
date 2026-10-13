import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { messages } from './messages'
import StockOverviewPage, { type StockRow } from './StockOverviewPage'

const calls = vi.hoisted(() => ({
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runQuery: calls.runQuery,
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const ROW: StockRow = { warehouseCode: 'W1', sku: 'A-1', productName: 'Bolt', onHand: '5', reserved: '2', available: '3' }

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  calls.runQuery.mockReset().mockResolvedValue({ items: [ROW], offset: 0, limit: 100 })
  calls.runProcess.mockReset()
  calls.permissions = new Set(['commerce.stock.read'])
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter>
          <StockOverviewPage />
        </MemoryRouter>
        {/* The application's toasts, as the admin's shell has them. */}
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('StockOverviewPage', () => {
  it('lists the stock from the template, filtered by warehouse', async () => {
    renderPage()
    expect(await screen.findByText('Bolt')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Stock overview' })).toBeInTheDocument()
    expect(calls.runQuery).toHaveBeenCalledWith('commerce.stock_availability', {
      params: { warehouseCode: null },
      limit: 100,
    })

    await userEvent.type(screen.getByLabelText('Filter by warehouse'), 'W1{Enter}')
    await waitFor(() =>
      expect(calls.runQuery).toHaveBeenLastCalledWith('commerce.stock_availability', {
        params: { warehouseCode: 'W1' },
        limit: 100,
      }),
    )
  })

  it('lists every warehouse again once the filter is emptied', async () => {
    renderPage()
    await screen.findByText('Bolt')
    const filter = screen.getByLabelText('Filter by warehouse')
    await userEvent.type(filter, 'W1{Enter}')
    await waitFor(() => expect(calls.runQuery).toHaveBeenLastCalledWith('commerce.stock_availability', {
      params: { warehouseCode: 'W1' }, limit: 100,
    }))
    await userEvent.clear(filter)
    await waitFor(() => expect(calls.runQuery).toHaveBeenLastCalledWith('commerce.stock_availability', {
      params: { warehouseCode: null }, limit: 100,
    }))
  })

  it('links to the generated list of receipts', async () => {
    renderPage()
    expect(await screen.findByTestId('receipts-link')).toHaveAttribute(
      'href',
      '/data/urn%3Ajabiz%3Adataset%3Adefault%3AStockReceipt',
    )
  })

  it('offers the receipt only to those who may receive', async () => {
    renderPage()
    await screen.findByText('Bolt')
    expect(screen.queryByTestId('receive-form')).not.toBeInTheDocument()
  })

  it('receives goods through the process and reloads the stock', async () => {
    calls.permissions.add('commerce.stock.receive')
    calls.runProcess.mockResolvedValue({ warehouseCode: 'W1', sku: 'A-1', onHand: '9' })
    renderPage()
    await screen.findByText('Bolt')

    await userEvent.type(screen.getByLabelText('Warehouse'), 'W1')
    await userEvent.type(screen.getByLabelText('SKU'), 'A-1')
    await userEvent.type(screen.getByLabelText('Quantity'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Receive' }))

    await waitFor(() =>
      expect(calls.runProcess).toHaveBeenCalledWith('STOCK_RECEIVE', { warehouseCode: 'W1', sku: 'A-1', quantity: 4 }),
    )
    expect(await screen.findByText('Received: A-1 in W1 now 9 on hand')).toBeInTheDocument()
    await waitFor(() => expect(calls.runQuery).toHaveBeenCalledTimes(2))
    // The quantity is emptied for the next receipt; warehouse and SKU stay.
    await waitFor(() => expect(screen.getByLabelText('Quantity')).toHaveValue(''))
    expect(screen.getByLabelText('Warehouse')).toHaveValue('W1')
  })

  it('receives nothing while a field is missing, and shows the refusal of the server', async () => {
    calls.permissions.add('commerce.stock.receive')
    calls.runProcess.mockRejectedValue(new ApiError(422, { violations: [{ ruleCode: 'X', message: 'Unknown SKU.' }] }))
    renderPage()
    await screen.findByText('Bolt')

    await userEvent.click(screen.getByRole('button', { name: 'Receive' }))
    expect(await screen.findAllByText('Required.')).toHaveLength(3)
    expect(calls.runProcess).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Warehouse'), 'W1')
    await userEvent.type(screen.getByLabelText('SKU'), 'NOPE')
    await userEvent.type(screen.getByLabelText('Quantity'), '1')
    await userEvent.click(screen.getByRole('button', { name: 'Receive' }))
    expect(await screen.findByText('Unknown SKU.')).toBeInTheDocument()
  })

  it('shows the refusal of the template instead of the rows', async () => {
    calls.runQuery.mockRejectedValue(new ApiError(403, { detail: 'Not allowed.' }))
    renderPage()
    expect(await screen.findByText('Not allowed.')).toBeInTheDocument()
  })
})
