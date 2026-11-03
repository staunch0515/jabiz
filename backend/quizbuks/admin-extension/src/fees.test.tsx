import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { FeesPage, OperatorBillingPage } from './FeesPages'
import { wholeOf } from './fees-api'
import { FEES } from './ids'
import { messages } from './messages'

const server = vi.hoisted(() => ({
  invoices: [] as Record<string, unknown>[],
  requests: [] as Record<string, unknown>[],
  billing: [] as Record<string, unknown>[],
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

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.invoices = [{ invoiceId: 'i1', invoiceNo: 'QBF-2026-00001', sponsorId: 's1', company: 'Acme', period: '2026-10',
    billingMode: 'POSTPAID', total: 1155, due: 1155, status: 'OVERDUE', issuedAt: '2026-11-02T01:00:00Z',
    dueOn: '2026-12-02', reminders: 1 },
  { invoiceId: 'i2', invoiceNo: 'QBF-2026-00002', sponsorId: 's2', company: null, period: '2026-10',
    billingMode: 'PREPAID', total: 1155, due: 0, status: 'VOID', issuedAt: '2026-11-02T01:00:00Z', dueOn: null,
    reminders: 0 }]
  server.requests = [{ requestId: 'r1', kind: 'CHARGE', sponsorId: 's1', company: 'Acme', invoiceNo: null, amount: 500,
    reason: 'Setup', status: 'REVIEW', requestedBy: 'u1', requestedAt: '2026-11-03T01:00:00Z' }]
  server.billing = [{ sponsorId: 's1', company: 'Acme', billingMode: 'PREPAID', own: false, creditLimit: 0,
    unpaid: 0, reason: null, updatedAt: null }]
  server.runProcess.mockReset().mockResolvedValue({ requestId: 'r2', status: 'REVIEW', approvalRequestId: 'a1' })
  server.runQuery.mockReset().mockImplementation(async (template: string) => ({ items:
    template === FEES.invoicesTemplate ? server.invoices
      : template === FEES.requestsTemplate ? server.requests : server.billing }))
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

describe('the platform fees pages (Q87b)', () => {
  it('reads whole amounts', () => {
    expect(wholeOf('1,000')).toBe(1000)
    expect(wholeOf('0')).toBe(0)
    expect(wholeOf('1.5')).toBeNull()
    expect(wholeOf('99999999')).toBeNull()
  })

  it('lists the invoices and asks for a voiding with its reason', async () => {
    const { container } = renderPage(<FeesPage />)
    const rows = await screen.findAllByTestId('fee-invoice-row')
    expect(rows).toHaveLength(2)
    // A void invoice is not voided again.
    expect(within(rows[1]).queryByTestId('fee-invoice-void')).toBeNull()
    await userEvent.click(within(rows[0]).getByTestId('fee-invoice-void'))
    await userEvent.type(screen.getByTestId('void-reason'), 'Charged twice')
    await userEvent.click(screen.getByTestId('void-ask'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEES.request, { kind: 'VOID',
      sponsorId: 's1', invoiceId: 'i1', reason: 'Charged twice' }))
    expect(await screen.findAllByTestId('fee-request-row')).toHaveLength(1)
    await expectAccessible(container)
  })

  it('asks for a charge by hand and sets a billing', async () => {
    renderPage(<FeesPage />)
    const charge = await screen.findByTestId('charge-form')
    await userEvent.type(within(charge).getByTestId('charge-sponsor'), 's1')
    await userEvent.type(within(charge).getByTestId('charge-amount'), '500')
    await userEvent.type(within(charge).getByTestId('charge-reason'), 'Setup')
    await userEvent.click(within(charge).getByTestId('charge-ask'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEES.request, { kind: 'CHARGE',
      sponsorId: 's1', amount: 500, reason: 'Setup' }))
    const billing = screen.getByTestId('billing-form')
    await userEvent.type(within(billing).getByTestId('billing-sponsor'), 's2')
    await userEvent.type(within(billing).getByTestId('billing-reason'), 'Monthly invoice')
    await userEvent.click(within(billing).getByTestId('billing-save'))
    // Prepaid: no credit sent.
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEES.billingSet, { sponsorId: 's2',
      billingMode: 'PREPAID', creditLimit: 0, reason: 'Monthly invoice' }))
  })

  it('lets an operator change its sponsor\'s billing', async () => {
    const { container } = renderPage(<OperatorBillingPage />)
    const row = await screen.findByTestId('billing-row')
    expect(row).toHaveTextContent('(platform default)')
    await userEvent.click(within(row).getByTestId('billing-edit'))
    const form = screen.getByTestId('billing-form')
    expect(within(form).queryByTestId('billing-sponsor')).toBeNull()
    await userEvent.type(within(form).getByTestId('billing-reason'), 'Trusted')
    await userEvent.click(within(form).getByTestId('billing-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEES.billingSet, { sponsorId: 's1',
      billingMode: 'PREPAID', creditLimit: 0, reason: 'Trusted' }))
    await expectAccessible(container)
  })
})
