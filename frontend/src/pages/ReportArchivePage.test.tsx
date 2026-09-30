import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import ReportArchivePage from './ReportArchivePage'

const get = vi.fn()
const post = vi.fn()
const exportRun = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../api/reports', () => ({ exportRun: (...args: unknown[]) => exportRun(...args) }))

const RUN = {
  runId: 'r-1', templateId: 'jabiz.ledger.account_balances', title: 'Trial balance', period: '– 2026-02-05',
  issuedBy: 'alice', issuedTime: '2026-02-05T15:00:00Z', rowCount: 4,
  contentHash: 'c2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9a', recomputable: true,
  supersededBy: 'r-2',
}

describe('ReportArchivePage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    exportRun.mockReset()
    await i18n.changeLanguage('en')
  })

  it('lists the issued runs of a template, saves one as issued and verifies it', async () => {
    get.mockResolvedValue([RUN])
    post.mockResolvedValue({ verdict: 'identical', recomputable: true, contentHash: RUN.contentHash })
    exportRun.mockResolvedValue(undefined)
    render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter initialEntries={['/reports/archive?template=jabiz.ledger.account_balances']}>
            <ReportArchivePage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('Trial balance')).toBeTruthy()
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { template: 'jabiz.ledger.account_balances', limit: 200 } } })
    expect(screen.getByText('c2741d77d343')).toBeTruthy()
    expect(screen.getByText('Superseded')).toBeTruthy()

    fireEvent.click(screen.getByTestId('run-verify-r-1'))
    await waitFor(() => expect(screen.getByTestId('verdict-r-1').textContent).toBe('Matches the data'))

    fireEvent.mouseEnter(screen.getByTestId('run-save-r-1'))
    fireEvent.click(await screen.findByText('PDF'))
    await waitFor(() => expect(exportRun).toHaveBeenCalledWith('r-1', 'pdf'))
  })
})
