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
})
