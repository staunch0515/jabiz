import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '../test/axe'
import AuditPage from './AuditPage'

const get = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: (value: unknown) => value,
}))

const RECORD = {
  recordNo: 7, processSeqId: 12, processName: 'VENDOR_UPDATE', entityType: 'Vendor', entityId: 'V-200',
  action: 'UPDATE', versionNo: 3, recordedTime: '2026-02-05T15:00:00Z', actorId: 'alice', reason: 'new bank',
  changes: { bankAccount: { before: '***', after: '***' }, name: { before: 'Acme', after: 'Acme Ltd' } },
}

describe('AuditPage', () => {
  beforeEach(async () => {
    get.mockReset()
    await i18n.changeLanguage('en')
  })

  it('starts from the entry in the URL with its approvals and shows each change before and after', async () => {
    get.mockResolvedValue({ items: [RECORD], total: 1, offset: 0, limit: 50 })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/audit?entityType=Vendor&entityId=V-200']}>
          <AuditPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('new bank')).toBeTruthy()
    expect(get.mock.calls[0][0]).toBe('/api/audit/records')
    expect(get.mock.calls[0][1].params.query).toMatchObject({ entityType: 'Vendor', entityId: 'V-200',
      withApprovals: 'true', offset: 0, limit: 50 })
    expect(screen.getByText('bankAccount, name')).toBeTruthy()
    // The filters from the URL are in the filter bar.
    expect((screen.getByRole('textbox', { name: 'Entry' }) as HTMLInputElement).value).toBe('V-200')

    // The expand button is named, and says whether the row is open.
    const expand = screen.getByRole('button', { name: 'Expand row' })
    expect(expand.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(expand)
    expect(screen.getByRole('button', { name: 'Collapse row' }).getAttribute('aria-expanded')).toBe('true')
    const changes = await screen.findByTestId('audit-changes-7')
    await waitFor(() => expect(within(changes).getByText('Acme Ltd')).toBeTruthy())
    expect(within(changes).getAllByText('***')).toHaveLength(2)
    await expectAccessible()
  })

  it('asks again with the filters, from the first page, and without approvals for no single entry', async () => {
    get.mockResolvedValue({ items: [RECORD], total: 120, offset: 0, limit: 50 })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/audit']}>
          <AuditPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await screen.findByText('new bank')
    expect(get.mock.calls[0][1].params.query).toEqual({ offset: 0, limit: 50 })
    fireEvent.click(screen.getByRole('button', { name: /next/i }))
    await waitFor(() => expect(get.mock.calls.at(-1)![1].params.query).toEqual({ offset: 50, limit: 50 }))

    fireEvent.change(screen.getByRole('textbox', { name: 'Operation' }), { target: { value: ' VENDOR_UPDATE ' } })
    fireEvent.submit(screen.getByRole('textbox', { name: 'Operation' }).closest('form')!)
    await waitFor(() => expect(get.mock.calls.at(-1)![1].params.query).toEqual({ offset: 0, limit: 50,
      processName: 'VENDOR_UPDATE' }))
  })

  it('lists the plain-text displays of masked values in their own tab', async () => {
    get.mockImplementation((path: string) =>
      Promise.resolve(path === '/api/audit/reveals'
        ? { items: [{ revealId: 'r1', revealedAt: '2026-02-05T15:00:00Z', actorId: 'clerk', kind: 'VALUE',
          resource: 'urn:jabiz:dataset:default:Vendor', entity: 'Vendor', entityId: 'V-200', fields: ['bankAccount'],
          rowCount: null }], total: 1, offset: 0, limit: 50 }
        : { items: [], total: 0, offset: 0, limit: 50 }))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/audit']}>
          <AuditPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.mouseDown(await screen.findByRole('tab', { name: 'Plain-text displays' }))
    const table = await screen.findByTestId('audit-reveals')
    await waitFor(() => expect(within(table).getByText('clerk')).toBeTruthy())
    expect(within(table).getByText('One value')).toBeTruthy()
    expect(within(table).getByText('bankAccount')).toBeTruthy()
    expect(get.mock.calls.some(([path]) => path === '/api/audit/reveals')).toBe(true)
  })

  it('keeps the filters and page of each tab while the other is shown', async () => {
    get.mockImplementation((path: string) =>
      Promise.resolve(path === '/api/audit/reveals'
        ? { items: [], total: 0, offset: 0, limit: 50 }
        : { items: [RECORD], total: 500, offset: 0, limit: 50 }))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/audit']}>
          <AuditPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    await screen.findByText('new bank')
    const records = screen.getByTestId('audit-records').closest('[role="tabpanel"]') as HTMLElement
    const operation = within(records).getByRole('textbox', { name: 'Operation' })
    fireEvent.change(operation, { target: { value: 'VENDOR_UPDATE' } })
    fireEvent.submit(operation.closest('form')!)
    fireEvent.click(within(records).getByRole('button', { name: /next/i }))
    await waitFor(() => expect(get.mock.calls.at(-1)![1].params.query).toMatchObject({ offset: 50 }))
    fireEvent.click(within(records).getByRole('button', { name: /next/i }))
    await waitFor(() => expect(get.mock.calls.at(-1)![1].params.query).toEqual({ offset: 100, limit: 50,
      processName: 'VENDOR_UPDATE' }))

    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Plain-text displays' }))
    const reveals = (await screen.findByTestId('audit-reveals')).closest('[role="tabpanel"]') as HTMLElement
    const actor = within(reveals).getByRole('textbox', { name: 'By' })
    fireEvent.change(actor, { target: { value: 'clerk' } })
    fireEvent.submit(actor.closest('form')!)
    await waitFor(() => expect(get.mock.calls.at(-1)).toEqual(['/api/audit/reveals',
      { params: { query: { offset: 0, limit: 50, actorId: 'clerk' } } }]))

    const calls = get.mock.calls.length
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Changes' }))
    await waitFor(() => expect(screen.getByTestId('audit-records')).toBeVisible())
    expect((within(records).getByRole('textbox', { name: 'Operation' }) as HTMLInputElement).value).toBe('VENDOR_UPDATE')
    expect(within(records).getByText('Page 3 of 10')).toBeTruthy()
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Plain-text displays' }))
    expect((within(reveals).getByRole('textbox', { name: 'By' }) as HTMLInputElement).value).toBe('clerk')
    // Nothing was asked again: both tabs kept what they showed.
    expect(get.mock.calls.length).toBe(calls)
  })
})
