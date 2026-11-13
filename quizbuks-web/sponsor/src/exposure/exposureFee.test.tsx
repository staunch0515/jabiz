import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ExposureSection } from './ExposureCounts'
import { isRunningLow } from './fee'

interface Served { fee?: Record<string, unknown>[]; settlements?: Record<string, unknown>[]; status?: string;
  topUp?: { status: number; body: unknown } }

function serve({ fee = [], settlements = [], status = 'LIVE', topUp }: Served) {
  const posted: { path: string; body: unknown }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input
      : new Request(new URL(String(input), window.location.origin), { method: init?.method, body: init?.body })
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = await request.clone().json().catch(() => null)
    posted.push({ path, body })
    const json = (code: number, value: unknown) => new Response(JSON.stringify(value),
      { status: code, headers: { 'Content-Type': 'application/json' } })
    switch (path) {
      case '/api/queries/qb.sponsor.exposure':
        return json(200, { items: [{ publicationId: 'pub-1', status, purchased: 100, successes: 95, billable: 95,
          overshoot: 0, opens: 0, goes: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.publication-exposure':
        return json(200, { items: fee, offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.publication-exposure-settlements':
        return json(200, { items: settlements, offset: 0, limit: 120 })
      case '/api/processes/QB_EXPOSURE_TOP_UP/latest':
        return json(topUp?.status ?? 200, topUp?.body ?? { output: { publicationId: 'pub-1', purchased: 150,
          frozen: 55, revision: 9 } })
      default:
        return json(200, { items: [], offset: 0, limit: 1 })
    }
  })
  return { calls: (path: string) => posted.filter((p) => p.path === path).map((p) => p.body) }
}

const FEE = { unit: '1.0000', purchased: 100, counted: 95, settledCount: 40, charged: 40, pendingEstimate: 55,
  settlements: 1, finalSettled: false, lowRatio: '0.1000' }
const SETTLEMENT = { settlementId: 's1', period: '2026-10', counted: 40, toCount: 40, unit: '1.0000', amount: 40,
  tax: 4, total: 44, final: false }

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ExposureSection publicationId="pub-1" />
    </QueryClientProvider>,
  )
}

beforeEach(async () => {
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe('the exposure fee on a publication (F-M8)', () => {
  it('shows the price, what is left, what the exposures since the last settlement come to and the settlements', async () => {
    serve({ fee: [FEE], settlements: [SETTLEMENT] })
    const { container } = renderSection()
    expect(await screen.findByTestId('exposure-fee-price')).toHaveTextContent('Price per exposure 1')
    expect(screen.getByTestId('exposure-fee-left')).toHaveTextContent('5 of 100 left')
    expect(screen.getByTestId('exposure-fee-bar')).toHaveAttribute('aria-valuenow', '95')
    expect(screen.getByTestId('exposure-fee-pending')).toHaveTextContent('55')
    expect(await screen.findByTestId('exposure-settlement')).toHaveAttribute('data-period', '2026-10')
    await expectAccessible(container)
  })

  it('tells when little is left and buys more, with the exposures the page saw and the server\'s answer', async () => {
    const served = serve({ fee: [FEE] })
    renderSection()
    expect(await screen.findByTestId('exposure-low')).toHaveTextContent('Only 5 of the 100 exposures')
    await userEvent.click(screen.getByTestId('exposure-buy-more'))
    await userEvent.type(await screen.findByTestId('exposure-buy-additional'), '50')
    expect(screen.getByTestId('exposure-buy-estimate')).toHaveTextContent('50')
    await userEvent.click(screen.getByTestId('exposure-buy-confirm'))
    await vi.waitFor(() => expect(served.calls('/api/processes/QB_EXPOSURE_TOP_UP/latest')).toHaveLength(1))
    expect(served.calls('/api/processes/QB_EXPOSURE_TOP_UP/latest')[0]).toMatchObject({
      publicationId: 'pub-1', expectedPurchased: 100, additional: 50 })
  })

  it('says why a purchase is refused and keeps the dialog open', async () => {
    serve({ fee: [FEE], topUp: { status: 422, body: { status: 422, title: 'Unprocessable', detail: 'Short of funds',
      violations: [{ field: null, ruleCode: 'QB_PUBLICATION_FUNDS', message: 'Your platform fee account is short.' }] } } })
    renderSection()
    await userEvent.click(await screen.findByTestId('exposure-buy-more'))
    await userEvent.type(await screen.findByTestId('exposure-buy-additional'), '5000')
    await userEvent.click(screen.getByTestId('exposure-buy-confirm'))
    expect(await screen.findByTestId('exposure-buy-problem')).toHaveTextContent('short')
    expect(screen.getByTestId('exposure-buy-dialog')).toBeInTheDocument()
  })

  it('offers no purchase and no low notice once the publication ended, and nothing when none was bought', async () => {
    serve({ fee: [FEE], status: 'ENDED' })
    const first = renderSection()
    expect(await screen.findByTestId('exposure-fee-price')).toBeInTheDocument()
    expect(screen.queryByTestId('exposure-buy-more')).toBeNull()
    expect(screen.queryByTestId('exposure-low')).toBeNull()
    first.unmount()
    vi.restoreAllMocks()
    serve({ fee: [{ ...FEE, unit: null, purchased: null }] })
    renderSection()
    expect(await screen.findByTestId('exposure-section')).toBeInTheDocument()
    expect(screen.queryByTestId('exposure-fee')).toBeNull()
  })

  it('runs low only at or below the share set, never when the reminder is off', () => {
    const fee = { unit: 1, purchased: 100, counted: 90, settledCount: 0, charged: 0, pendingEstimate: 0,
      settlements: 0, finalSettled: false, lowRatio: 0.1 }
    expect(isRunningLow(fee)).toBe(true)
    expect(isRunningLow({ ...fee, counted: 89 })).toBe(false)
    expect(isRunningLow({ ...fee, lowRatio: 0 })).toBe(false)
    expect(isRunningLow({ ...fee, purchased: null })).toBe(false)
    expect(isRunningLow({ ...fee, lowRatio: 0.9, counted: 49 })).toBe(false)
  })
})
