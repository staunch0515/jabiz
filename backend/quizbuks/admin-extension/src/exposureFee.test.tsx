import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { ExposurePricesPage, ExposureSettlementsPage, OperatorExposurePricePage } from './ExposureFeePages'
import { unitOf } from './exposure-fee-api'
import { EXPOSURE_FEE } from './ids'
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
  server.prices = [{ operatorId: NORTH, operatorName: 'North', priceId: 'p1', unit: 0.4, reason: 'Launch',
    updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2' },
  { operatorId: 'o2', operatorName: 'South', priceId: null, unit: null, reason: null, updatedAt: null,
    updatedBy: null }]
  server.own = []
  server.settlements = [{ settlementId: 's1', publicationId: 'pub1', sponsorId: 'sp1', operatorId: NORTH,
    period: '2026-10', counted: 30, toCount: 30, unit: 1, amount: 30, tax: 3, total: 33, final: false,
    settledAt: '2026-11-01T15:47:00Z' }]
  server.runProcess.mockReset().mockResolvedValue({})
  server.runQuery.mockReset().mockImplementation(async (template: string) => ({ items:
    template === EXPOSURE_FEE.pricesTemplate ? server.prices
      : template === EXPOSURE_FEE.settlementsTemplate || template === EXPOSURE_FEE.operatorSettlementsTemplate
        ? server.settlements : server.own }))
})

function renderPage(page: React.ReactNode, entry = '/') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[entry]}>{page}</MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the exposure fee pages (F-M8)', () => {
  it('reads a price of more than 0 with four places at most', () => {
    expect(unitOf('0.4')).toBe('0.4')
    expect(unitOf('1,000.5')).toBe('1000.5')
    expect(unitOf('0')).toBeNull()
    expect(unitOf('1.23456')).toBeNull()
    expect(unitOf('-1')).toBeNull()
  })

  it('lists every operator, one without a price as none, and saves a picked operator\'s price with a reason',
    async () => {
      const { container } = renderPage(<ExposurePricesPage />)
      const rows = await screen.findAllByTestId('exposure-fee-price-row')
      expect(rows).toHaveLength(2)
      expect(rows[1]).toHaveTextContent('No price')
      await expectAccessible(container)
      await userEvent.click(screen.getByTestId('exposure-fee-operator'))
      await userEvent.click(await screen.findByRole('option', { name: 'North' }))
      expect(await screen.findByTestId('exposure-fee-unit')).toHaveValue('0.4')
      const save = screen.getByTestId('exposure-fee-save')
      expect(save).toBeDisabled()
      await userEvent.type(screen.getByTestId('exposure-fee-reason'), 'New season')
      await userEvent.clear(screen.getByTestId('exposure-fee-unit'))
      await userEvent.type(screen.getByTestId('exposure-fee-unit'), '0.5')
      await userEvent.click(save)
      await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(EXPOSURE_FEE.priceSet, { operatorId: NORTH,
        unit: '0.5', reason: 'New season' }))
      expect(screen.getByTestId('exposure-fee-range-note')).toHaveTextContent('another publishes')
    })

  it('shows an operator\'s own price read-only to a member who is not the lead, with the range', async () => {
    server.own = [{ operatorId: NORTH, lead: false, priceId: 'p1', unit: 0.4, reason: 'Launch',
      updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2',
      range: '{"qb.exposure.unit.min":"1.0000","qb.exposure.unit.max":"100.0000"}' }]
    const { container } = renderPage(<OperatorExposurePricePage />)
    expect(await screen.findByTestId('exposure-fee-lead-only')).toBeInTheDocument()
    expect(screen.getByTestId('exposure-fee-unit')).toHaveAttribute('readonly')
    expect(screen.queryByTestId('exposure-fee-save')).toBeNull()
    expect(screen.getByText('From 1.0000 to 100.0000')).toBeInTheDocument()
    await expectAccessible(container)
  })

  it('lets the lead save the operator\'s own price and shows the server\'s refusal', async () => {
    server.own = [{ operatorId: NORTH, lead: true, priceId: null, unit: null, reason: null, updatedAt: null,
      updatedBy: null, range: '{}' }]
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'Refused', violations: [
      { field: 'unit', ruleCode: 'QB_EXPOSURE_PRICE_RANGE', message: 'Enter a value from 1 to 100.' }] }))
    renderPage(<OperatorExposurePricePage />)
    expect(await screen.findByTestId('exposure-fee-price-none')).toBeInTheDocument()
    await userEvent.type(screen.getByTestId('exposure-fee-unit'), '500')
    await userEvent.type(screen.getByTestId('exposure-fee-reason'), 'Ours')
    await userEvent.click(screen.getByTestId('exposure-fee-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(EXPOSURE_FEE.operatorPriceSet,
      { operatorId: null, unit: '500', reason: 'Ours' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('lists the operator\'s own settlements below its price, without an operator filter', async () => {
    server.own = [{ operatorId: NORTH, lead: false, priceId: 'p1', unit: 0.4, reason: 'Launch',
      updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2', range: '{}' }]
    const { container } = renderPage(<OperatorExposurePricePage />)
    const row = await screen.findByTestId('exposure-fee-settlement-row')
    expect(row).toHaveAttribute('data-final', 'false')
    expect(row).toHaveTextContent('sp1')
    expect(server.runQuery).toHaveBeenCalledWith(EXPOSURE_FEE.operatorSettlementsTemplate,
      expect.objectContaining({ params: expect.not.objectContaining({ operatorId: null }) }))
    await expectAccessible(container)
  })

  it('shows the Sponsor\'s company in the operator\'s own settlements, and the number when there is none', async () => {
    server.own = [{ operatorId: NORTH, lead: false, priceId: 'p1', unit: 0.4, reason: 'Launch',
      updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2', range: '{}' }]
    server.settlements = [
      { ...server.settlements[0], settlementId: 's1', sponsorId: 'sp1', company: 'Acme Ltd' },
      { ...server.settlements[0], settlementId: 's2', sponsorId: 'sp-moved', company: null, period: '2026-09' }]
    renderPage(<OperatorExposurePricePage />)
    const rows = await screen.findAllByTestId('exposure-fee-settlement-row')
    expect(rows[0]).toHaveTextContent('Acme Ltd')
    expect(rows[0]).not.toHaveTextContent('sp1')
    expect(rows[1]).toHaveTextContent('sp-moved')
  })

  it('lists the settlements and filters them by month', async () => {
    const { container } = renderPage(<ExposureSettlementsPage />)
    const row = await screen.findByTestId('exposure-fee-settlement-row')
    expect(row).toHaveTextContent('2026-10')
    expect(row).toHaveTextContent('30')
    await expectAccessible(container)
    await userEvent.type(screen.getByTestId('exposure-fee-period'), '2026-10')
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(EXPOSURE_FEE.settlementsTemplate,
      expect.objectContaining({ params: expect.objectContaining({ period: '2026-10' }) })))
  })

  it('opens on the month the reconciliation page links (?period=, F-M14b)', async () => {
    renderPage(<ExposureSettlementsPage />, '/settlements?period=2031-03')
    expect(await screen.findByTestId('exposure-fee-period')).toHaveValue('2031-03')
    await waitFor(() => expect(server.runQuery).toHaveBeenLastCalledWith(EXPOSURE_FEE.settlementsTemplate,
      expect.objectContaining({ params: expect.objectContaining({ period: '2031-03' }) })))
  })
})
