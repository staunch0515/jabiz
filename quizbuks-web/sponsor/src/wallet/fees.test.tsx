import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import { FeesSection } from './FeesSection'
import { invoiceLanguage, modeOf, owedOutsideInvoices, toInvoice, type InvoiceRow } from './fees'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const invoice = (status: string, due: string, extra: Record<string, unknown> = {}) => ({
  invoiceId: `i-${status}`, invoiceNo: `QBF-2026-0000${status.length}`, period: '2026-10', total: '1155', due,
  status, issuedAt: '2026-11-02T01:00:00Z', dueOn: status === 'PAID' ? null : '2026-12-02', ...extra,
})

interface Server {
  standing?: Record<string, unknown>
  invoices?: Record<string, unknown>[]
  pay?: { status: number; body: unknown }
}

function server(options: Server = {}) {
  const posted: { path: string; body: unknown }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/queries/qb.sponsor.fee-standing':
        return json(200, { items: [options.standing ?? { billingMode: 'PREPAID', creditLimit: '0', unpaid: '0',
          overdue: false }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbFeeInvoice/query':
        return json(200, { items: (options.invoices ?? []).map((row, i) => ({ id: `r${i}`, version: 1,
          attributes: row })) })
      case '/api/processes/QB_FEE_PAY/latest':
        return json(options.pay?.status ?? 200, options.pay?.body ?? { amount: 1155, cashBalance: 845 })
      default:
        return json(404, {})
    }
  })
  return { of: (p: string) => posted.filter((a) => a.path === p).map((a) => a.body) }
}

function wrap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><FeesSection /></QueryClientProvider>)
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('fees', () => {
  it('reads what the server sends', () => {
    expect(modeOf('POSTPAID')).toBe('POSTPAID')
    expect(modeOf('x')).toBe('PREPAID')
    expect(invoiceLanguage('ja-JP')).toBe('ja')
    expect(invoiceLanguage('fr')).toBe('en')
    const rows: InvoiceRow[] = [toInvoice(invoice('ISSUED', '1155')), toInvoice(invoice('PAID', '0'))]
    expect(owedOutsideInvoices({ billingMode: 'PREPAID', creditLimit: 0, unpaid: 1500, overdue: false }, rows))
      .toBe(345)
    expect(owedOutsideInvoices({ billingMode: 'PREPAID', creditLimit: 0, unpaid: 1155, overdue: false }, rows))
      .toBe(0)
  })

  it('shows nothing to a prepaid sponsor with no fees', async () => {
    server()
    const { container } = wrap()
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled())
    expect(container.querySelector('[data-testid="wallet-fees"]')).toBeNull()
  })

  it('shows an overdue invoice, pays it and what is owed outside invoices', async () => {
    const asked = server({
      standing: { billingMode: 'POSTPAID', creditLimit: '5000', unpaid: '1500', overdue: true },
      invoices: [invoice('OVERDUE', '1155'), invoice('PAID', '0')],
    })
    const { container } = wrap()
    const section = await screen.findByTestId('wallet-fees')
    expect(within(section).getByTestId('fees-overdue')).toBeTruthy()
    expect(within(section).getByTestId('fees-mode').dataset.mode).toBe('POSTPAID')
    expect(within(section).getAllByTestId('invoice-row')).toHaveLength(2)
    // Only the unpaid invoice has "Pay".
    expect(within(section).getAllByTestId('invoice-row-pay')).toHaveLength(1)
    await userEvent.click(within(section).getByTestId('invoice-row-pay'))
    await waitFor(() => expect(asked.of('/api/processes/QB_FEE_PAY/latest')).toEqual([{ invoiceId: 'i-OVERDUE' }]))
    await userEvent.click(within(section).getByTestId('fees-pay-owed'))
    await waitFor(() => expect(asked.of('/api/processes/QB_FEE_PAY/latest')).toHaveLength(2))
    expect(asked.of('/api/processes/QB_FEE_PAY/latest')[1]).toEqual({})
    await expectAccessible(container)
  })

  it('shows the server\'s refusal', async () => {
    server({
      standing: { billingMode: 'PREPAID', creditLimit: '0', unpaid: '1155', overdue: false },
      invoices: [invoice('ISSUED', '1155')],
      pay: { status: 422, body: { status: 422, title: 'Unprocessable', detail: 'No cash',
        violations: [{ ruleCode: 'QB_FEE_INVOICE_FUNDS', message: 'Your cash account does not cover it' }] } },
    })
    wrap()
    await userEvent.click(await screen.findByTestId('invoice-row-pay'))
    expect(await screen.findByTestId('fees-problem')).toBeTruthy()
  })
})
