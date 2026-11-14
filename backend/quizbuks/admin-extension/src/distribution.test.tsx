import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { DistributionPricesPage, DistributionSettlementsPage, OperatorDistributionPricePage } from './DistributionPages'
import { decimalOf, wholeOf } from './distribution-api'
import { DISTRIBUTION } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  prices: [] as Record<string, unknown>[],
  own: [] as Record<string, unknown>[],
  settlements: [] as Record<string, unknown>[],
  runProcess: vi.fn(),
  runQuery: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runProcess: server.runProcess,
    runQuery: server.runQuery,
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const NORTH = '0192f0a0-0000-7000-8000-00000000e001'

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.prices = [{ operatorId: NORTH, operatorName: 'North', priceId: 'p1', rate: 0.05, unitFloor: 1, unitCeiling: 20,
    minimumDeposit: 500, reason: 'Launch', updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2' },
  { operatorId: 'o2', operatorName: 'South', priceId: null, rate: null, unitFloor: null, unitCeiling: null,
    minimumDeposit: null, reason: null, updatedAt: null, updatedBy: null }]
  server.own = []
  server.settlements = [{ settlementId: 's1', publicationId: 'pub1', sponsorId: 'sp1', operatorId: NORTH,
    operatorName: 'North Ops', period: '2026-10', starts: 3, toStarts: 3, unit: 1, atFloor: true, amount: 3, tax: 0, total: 3, final: false,
    settledAt: '2026-11-01T15:37:00Z' }]
  server.runProcess.mockReset().mockResolvedValue({})
  server.runQuery.mockReset().mockImplementation(async (template: string) => ({ items:
    template === DISTRIBUTION.pricesTemplate ? server.prices
      : template === DISTRIBUTION.settlementsTemplate || template === DISTRIBUTION.operatorSettlementsTemplate
        ? server.settlements : server.own }))
})

function renderPage(page: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter>{page}</MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the distribution fee pages (F-M6)', () => {
  it('reads decimals with four places and whole deposits only', () => {
    expect(decimalOf('0.05')).toBe('0.05')
    expect(decimalOf('1.23456')).toBeNull()
    expect(decimalOf('-1')).toBeNull()
    expect(wholeOf('500')).toBe(500)
    expect(wholeOf('1.5')).toBeNull()
  })

  it('lists every operator, one without a price as none, and saves a picked operator\'s price with a reason',
    async () => {
      const { container } = renderPage(<DistributionPricesPage />)
      const rows = await screen.findAllByTestId('distribution-price-row')
      expect(rows).toHaveLength(2)
      expect(rows[1]).toHaveTextContent('No price')
      await expectAccessible(container)
      await userEvent.click(screen.getByTestId('distribution-operator'))
      await userEvent.click(await screen.findByRole('option', { name: 'North' }))
      expect(await screen.findByTestId('distribution-rate')).toHaveValue('0.05')
      const save = screen.getByTestId('distribution-save')
      expect(save).toBeDisabled()
      await userEvent.type(screen.getByTestId('distribution-reason'), 'New season')
      await userEvent.clear(screen.getByTestId('distribution-floor'))
      await userEvent.type(screen.getByTestId('distribution-floor'), '1.5')
      await userEvent.click(save)
      await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(DISTRIBUTION.priceSet, { operatorId: NORTH,
        rate: '0.05', unitFloor: '1.5', unitCeiling: '20', minimumDeposit: 500, reason: 'New season' }))
      expect(screen.getByTestId('distribution-range-note')).toHaveTextContent('another publishes')
    })

  it('shows an operator\'s own price read-only to a member who is not the lead, with the range', async () => {
    server.own = [{ operatorId: NORTH, lead: false, priceId: 'p1', rate: 0.05, unitFloor: 1, unitCeiling: 20,
      minimumDeposit: 500, reason: 'Launch', updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2',
      range: '{"qb.distribution.rate.min":"0.0000","qb.distribution.rate.max":"1.0000"}' }]
    const { container } = renderPage(<OperatorDistributionPricePage />)
    expect(await screen.findByTestId('distribution-lead-only')).toBeInTheDocument()
    expect(screen.getByTestId('distribution-rate')).toHaveAttribute('readonly')
    expect(screen.queryByTestId('distribution-save')).toBeNull()
    expect(screen.getByText('From 0.0000 to 1.0000')).toBeInTheDocument()
    await expectAccessible(container)
  })

  it('lets the lead save the operator\'s own price and shows the server\'s refusal', async () => {
    server.own = [{ operatorId: NORTH, lead: true, priceId: null, rate: null, unitFloor: null, unitCeiling: null,
      minimumDeposit: null, reason: null, updatedAt: null, updatedBy: null, range: '{}' }]
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'Refused', violations: [
      { field: 'rate', ruleCode: 'QB_DISTRIBUTION_PRICE_RANGE', message: 'Enter a value from 0 to 1.' }] }))
    renderPage(<OperatorDistributionPricePage />)
    expect(await screen.findByTestId('distribution-price-none')).toBeInTheDocument()
    await userEvent.type(screen.getByTestId('distribution-rate'), '2')
    await userEvent.type(screen.getByTestId('distribution-floor'), '1')
    await userEvent.type(screen.getByTestId('distribution-ceiling'), '20')
    await userEvent.type(screen.getByTestId('distribution-deposit'), '500')
    await userEvent.type(screen.getByTestId('distribution-reason'), 'Ours')
    await userEvent.click(screen.getByTestId('distribution-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(DISTRIBUTION.operatorPriceSet,
      expect.objectContaining({ operatorId: null, rate: '2', minimumDeposit: 500 })))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('lists the operator\'s own settlements below its price, the floor marked, without an operator filter', async () => {
    server.own = [{ operatorId: NORTH, lead: false, priceId: 'p1', rate: 0.05, unitFloor: 1, unitCeiling: 20,
      minimumDeposit: 500, reason: 'Launch', updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2', range: '{}' }]
    const { container } = renderPage(<OperatorDistributionPricePage />)
    const row = await screen.findByTestId('distribution-settlement-row')
    expect(row).toHaveAttribute('data-floor', 'true')
    expect(row).toHaveTextContent('sp1')
    // Its own list: no operator column.
    expect(screen.queryByRole('columnheader', { name: 'Operator' })).not.toBeInTheDocument()
    expect(server.runQuery).toHaveBeenCalledWith(DISTRIBUTION.operatorSettlementsTemplate,
      expect.objectContaining({ params: expect.not.objectContaining({ operatorId: null }) }))
    await expectAccessible(container)
  })

  it('lists the settlements, marks those at the least price, and filters by month and the floor', async () => {
    const { container } = renderPage(<DistributionSettlementsPage />)
    const row = await screen.findByTestId('distribution-settlement-row')
    expect(row).toHaveAttribute('data-floor', 'true')
    expect(row).toHaveTextContent('1 (least price)')
    // The operator by its name, not its id.
    expect(screen.getByRole('columnheader', { name: 'Operator' })).toBeInTheDocument()
    expect(row).toHaveTextContent('North Ops')
    expect(row).not.toHaveTextContent(NORTH)
    await expectAccessible(container)
    await userEvent.type(screen.getByTestId('distribution-period'), '2026-10')
    await userEvent.click(screen.getByTestId('distribution-floor-only'))
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(DISTRIBUTION.settlementsTemplate,
      expect.objectContaining({ params: expect.objectContaining({ period: '2026-10', atFloor: true }) })))
  })
})
