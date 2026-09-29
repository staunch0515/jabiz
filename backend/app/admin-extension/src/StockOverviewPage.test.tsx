import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from 'antd'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { EXTENSION_NAMESPACE } from '@jabiz/admin'
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
      <App>
        <StockOverviewPage />
      </App>
    </QueryClientProvider>,
  )
}

describe('StockOverviewPage', () => {
  it('lists the stock from the template, filtered by warehouse', async () => {
    renderPage()
    expect(await screen.findByText('Bolt')).toBeInTheDocument()
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
  })
})
