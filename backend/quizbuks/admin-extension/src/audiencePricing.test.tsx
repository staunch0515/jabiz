import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { AudiencePricesPage, AudienceRulesPage, OperatorAudiencePricesPage } from './AudiencePricingPages'
import { AUDIENCE_PRICING } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  rules: [] as Record<string, unknown>[],
  lists: [] as Record<string, unknown>[],
  own: [] as Record<string, unknown>[],
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
  server.rules = [{ ruleKey: 'interest', enabled: false, billable: true, sortOrder: 5, updatedAt: '2026-11-01T01:00:00Z',
    updatedBy: 'u1' }]
  server.lists = [{ priceListId: 'l1', owner: 'direct', operatorName: null, minimumFee: 1000,
    prices: '{"gender":100,"age":200}', reason: 'Launch', updatedAt: '2026-11-01T01:00:00Z', updatedBy: 'u1' },
  { priceListId: 'l2', owner: NORTH, operatorName: 'North', minimumFee: 500, prices: '{"gender":50}',
    reason: 'Ours', updatedAt: '2026-11-02T01:00:00Z', updatedBy: 'u2' }]
  server.own = []
  server.runProcess.mockReset().mockResolvedValue({})
  server.runQuery.mockReset().mockImplementation(async (template: string) => ({ items:
    template === AUDIENCE_PRICING.rulesTemplate ? server.rules
      : template === AUDIENCE_PRICING.pricesTemplate ? server.lists : server.own }))
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

describe('the audience rules page (Q47c)', () => {
  it('lists every kind, a kind without a record as shipped, and saves them all', async () => {
    const { container } = renderPage(<AudienceRulesPage />)
    const rows = await screen.findAllByTestId('audience-rule-row')
    // The managed order: interest (5) first, then the code's order at its defaults.
    expect(rows.map((row) => row.dataset.rule)).toEqual(['interest', 'gender', 'age', 'occupation'])
    expect(within(rows[0]).getByTestId('audience-rule-enabled')).not.toBeChecked()
    expect(rows[1]).toHaveTextContent('As shipped')
    await expectAccessible(container)
    await userEvent.click(within(rows[1]).getByTestId('audience-rule-billable'))
    await userEvent.click(screen.getByTestId('audience-rules-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(AUDIENCE_PRICING.rulesSet, { rules: [
      { ruleKey: 'interest', enabled: false, billable: true, sortOrder: 5 },
      { ruleKey: 'gender', enabled: true, billable: false, sortOrder: 10 },
      { ruleKey: 'age', enabled: true, billable: true, sortOrder: 20 },
      { ruleKey: 'occupation', enabled: true, billable: true, sortOrder: 30 }] }))
  })
})

describe('the audience prices pages (Q47c)', () => {
  it('shows headquarters\' own list, and saves an operator\'s with a reason', async () => {
    const { container } = renderPage(<AudiencePricesPage />)
    const prices = await screen.findAllByTestId('price-list-price')
    expect(screen.getByTestId('price-list-minimum')).toHaveValue('1000')
    expect(prices.find((input) => input.dataset.rule === 'age')).toHaveValue('200')
    expect(prices.find((input) => input.dataset.rule === 'interest')).toHaveValue('0')
    await expectAccessible(container)

    await userEvent.type(screen.getByTestId('price-list-operator-id'), NORTH)
    expect(await screen.findByTestId('price-list-minimum')).toHaveValue('500')
    const save = screen.getByTestId('price-list-save')
    // A reason is needed, and only whole amounts.
    expect(save).toBeDisabled()
    await userEvent.type(screen.getByTestId('price-list-reason'), 'New season')
    const minimum = screen.getByTestId('price-list-minimum')
    await userEvent.clear(minimum)
    await userEvent.type(minimum, '1.5')
    expect(save).toBeDisabled()
    await userEvent.clear(minimum)
    await userEvent.type(minimum, '800')
    await userEvent.click(save)
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(AUDIENCE_PRICING.pricesSet, { owner: NORTH,
      minimumFee: 800, reason: 'New season', prices: [{ ruleKey: 'gender', dailyPrice: 50 },
        { ruleKey: 'age', dailyPrice: 0 }, { ruleKey: 'occupation', dailyPrice: 0 },
        { ruleKey: 'interest', dailyPrice: 0 }] }))
  })

  it('lets an operator save its own and shows the server\'s refusal of a member who is not the lead', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'Refused', violations: [
      { field: null, ruleCode: 'QB_OPERATOR_NOT_LEAD', message: 'Only the operator\'s lead may do this.' }] }))
    const { container } = renderPage(<OperatorAudiencePricesPage />)
    expect(await screen.findByTestId('price-list-none')).toBeInTheDocument()
    await expectAccessible(container)
    await userEvent.type(screen.getByTestId('price-list-reason'), 'Ours')
    await userEvent.click(screen.getByTestId('price-list-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(AUDIENCE_PRICING.operatorPricesSet,
      expect.objectContaining({ owner: null, minimumFee: 0 })))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
