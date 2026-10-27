import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import PlanPage from './PlanPage'
import { UsageNotice } from './usage'
import type { PlanState } from './api'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const PRICING = 'https://admin.shopify.com/store/demo/charges/quizbuks/pricing_plans'

function plan(overrides: Partial<PlanState> = {}): PlanState {
  return {
    plan: 'FREE', nextPlan: null, planUntil: null, shopifyPlan: null, unknownPlan: false, test: false,
    trialEndsAt: null, periodStart: '2026-10-01T00:00:00Z', periodEnd: '2026-11-01T00:00:00Z', used: 12, limit: 50,
    checkedAt: '2026-10-27T09:00:00Z', connection: 'CONNECTED', pricingUrl: PRICING, ...overrides,
  }
}

/** QBS_PLAN_GET's answers, one per call (the last repeats); records the refresh asked for. */
function server(...answers: PlanState[]) {
  const asked: boolean[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = new URL(request.url, window.location.origin).pathname
    if (path !== '/api/processes/QBS_PLAN_GET/latest') return json(404, {})
    asked.push(((await request.clone().json()) as { refresh: boolean }).refresh)
    return json(200, { output: answers[Math.min(asked.length - 1, answers.length - 1)] })
  })
  return asked
}

function page(onShowPlan = () => {}, onShowOverview = () => {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <UsageNotice userId="u1" onShowPlan={onShowPlan} />
      <PlanPage userId="u1" onShowOverview={onShowOverview} />
    </QueryClientProvider>,
  )
}

describe('the plan and its use (S3b)', () => {
  beforeAll(() => configureClient({ entry: 'shopify' }))
  beforeEach(() => {
    session.clear()
    session.store('a1', 'r1')
    window.history.replaceState(null, '', '/shopify/')
  })
  afterEach(() => vi.restoreAllMocks())

  it('shows the free plan, its period and its use, without a notice below 80 %', async () => {
    const asked = server(plan())
    page()
    expect(await screen.findByText('Plan: Free')).toBeInTheDocument()
    // The period's days in UTC, its last day the one before its end.
    expect(screen.getByText(`This period: ${new Date('2026-10-01T00:00:00Z').toLocaleDateString('en', { timeZone: 'UTC' })}`
      + ` – ${new Date('2026-10-31T00:00:00Z').toLocaleDateString('en', { timeZone: 'UTC' })} (UTC)`)).toBeInTheDocument()
    expect(screen.getByText('Quiz attempts completed: 12 of 50 (24%)')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('value', '12')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    // One read for the notice and the page, not asking Shopify at once.
    expect(asked).toEqual([false])
    await expectAccessible()
  })

  it('shows a paid plan in its trial, a test charge and a move down to come', async () => {
    server(plan({ plan: 'PRO', test: true, trialEndsAt: '2099-01-10T00:00:00Z', nextPlan: 'STARTER',
      planUntil: '2026-11-05T00:00:00Z', used: 100, limit: 15000 }))
    page()
    const title = await screen.findByText('Plan: Pro')
    expect(within(title).getByText('Test')).toBeInTheDocument()
    expect(screen.getByText(/^Free trial until /)).toBeInTheDocument()
    expect(screen.getByText(/: Starter$/)).toHaveTextContent(/^From /)
  })

  it('says when Shopify\'s plan is none of ours, or cannot be read, and leads to the overview to connect again', async () => {
    server(plan({ unknownPlan: true, shopifyPlan: 'Enterprise', connection: 'RECONNECT' }))
    const showOverview = vi.fn()
    const user = userEvent.setup()
    page(() => {}, showOverview)
    expect(await screen.findByText(/"Enterprise" is not one QuizBuks knows/)).toBeInTheDocument()
    expect(screen.getByText(/until the store is connected again/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Go to the overview' }))
    expect(showOverview).toHaveBeenCalled()
  })

  it('says what failed when Shopify cannot be read again', async () => {
    let calls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => (++calls === 1 ? json(200, { output: plan() })
      : json(503, { status: 503, title: 'Busy', violations: [{ field: null, ruleCode: 'PROCESS_BUSY',
        message: 'Busy, try again.' }] })))
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Read from Shopify again' }))
    expect(await screen.findByText('Could not read from Shopify: Busy, try again.')).toBeInTheDocument()
  })

  it('draws a used-up bar when the plan allows none', async () => {
    server(plan({ used: 0, limit: 0 }))
    page()
    const bar = await screen.findByRole('progressbar')
    expect(bar).toHaveAttribute('value', '1')
    expect(bar).toHaveAttribute('max', '1')
  })

  it('opens Shopify\'s plan page at the top of the admin, and reads Shopify again when asked', async () => {
    const asked = server(plan(), plan({ plan: 'GROWTH', limit: 3000 }))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const user = userEvent.setup()
    page()
    await user.click(await screen.findByRole('button', { name: 'Choose a plan' }))
    expect(open).toHaveBeenCalledWith(PRICING, '_top')
    await user.click(screen.getByRole('button', { name: 'Read from Shopify again' }))
    expect(await screen.findByText('Plan: Growth')).toBeInTheDocument()
    expect(asked).toEqual([false, true])
  })

  it('reads Shopify at once when back from its plan page', async () => {
    window.history.replaceState(null, '', '/shopify/?shop=demo.myshopify.com&charge_id=123')
    const asked = server(plan())
    const first = page()
    await screen.findByText('Plan: Free')
    expect(asked).toEqual([true])
    // Once: the parameter is taken off (Shopify's own stay), so a later page reads as usual.
    expect(window.location.search).toBe('?shop=demo.myshopify.com')
    first.unmount()
    page()
    await screen.findByText('Plan: Free')
    expect(asked).toEqual([true, false])
  })

  it('warns from 80 % and says the quiz is off at the limit', async () => {
    server(plan({ used: 41 }))
    const showPlan = vi.fn()
    const user = userEvent.setup()
    const first = page(showPlan)
    expect(await screen.findByText(/82% of this period's quiz attempts are used/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'See the plan' }))
    expect(showPlan).toHaveBeenCalled()
    first.unmount()

    vi.restoreAllMocks()
    server(plan({ used: 53 }))
    page()
    const full = await screen.findByText(/quiz attempts are used up/)
    expect(full).toHaveTextContent(/comes back on /)
    expect(screen.getByText('Quiz attempts completed: 53 of 50 (100%)')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Choose a plan' })).toHaveLength(2)
    await expectAccessible()
  })
})
