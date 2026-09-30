import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
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
    const { container } = render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter initialEntries={['/audit?entityType=Vendor&entityId=V-200']}>
            <AuditPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('new bank')).toBeTruthy()
    expect(get.mock.calls[0][0]).toBe('/api/audit/records')
    expect(get.mock.calls[0][1].params.query).toMatchObject({ entityType: 'Vendor', entityId: 'V-200',
      withApprovals: 'true', offset: 0 })
    expect(screen.getByText('bankAccount, name')).toBeTruthy()

    fireEvent.click(container.querySelector('.ant-table-row-expand-icon')!)
    const changes = await screen.findByTestId('audit-changes-7')
    await waitFor(() => expect(within(changes).getByText('Acme Ltd')).toBeTruthy())
    expect(within(changes).getAllByText('***')).toHaveLength(2)
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
        <App>
          <MemoryRouter initialEntries={['/audit']}>
            <AuditPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByText('Plain-text displays'))
    const table = await screen.findByTestId('audit-reveals')
    await waitFor(() => expect(within(table).getByText('clerk')).toBeTruthy())
    expect(within(table).getByText('One value')).toBeTruthy()
    expect(within(table).getByText('bankAccount')).toBeTruthy()
    expect(get.mock.calls.some(([path]) => path === '/api/audit/reveals')).toBe(true)
  })
})
