import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import { expectAccessible } from '@jabiz/ui/testing'
import ReportArchivePage from './ReportArchivePage'

const get = vi.fn()
const post = vi.fn()
const exportRun = vi.fn()

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
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

function show(path = '/reports/archive?template=jabiz.ledger.account_balances') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <ReportArchivePage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReportArchivePage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    exportRun.mockReset()
    await act(() => i18n.changeLanguage('en'))
  })

  it('lists the issued runs of a template, saves one as issued and verifies it', async () => {
    get.mockResolvedValue([RUN])
    post.mockResolvedValue({ verdict: 'identical', recomputable: true, contentHash: RUN.contentHash })
    exportRun.mockResolvedValue(undefined)
    show()

    expect(await screen.findByText('Trial balance')).toBeTruthy()
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { template: 'jabiz.ledger.account_balances', limit: 200 } } })
    expect(screen.getByText('c2741d77d343')).toBeTruthy()
    expect(screen.getByText('Superseded')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: 'Issued reports' })).toBeInTheDocument()
    await expectAccessible()

    await userEvent.click(screen.getByTestId('run-verify-r-1'))
    await waitFor(() => expect(screen.getByTestId('verdict-r-1').textContent).toBe('Matches the data'))
    expect(post.mock.calls[0]).toEqual(['/api/reports/runs/{runId}/verify', { params: { path: { runId: 'r-1' } } }])

    fireEvent.pointerDown(screen.getByTestId('run-save-r-1'), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['PDF', 'Excel (.xlsx)', 'CSV'])
    await userEvent.click(screen.getByRole('menuitem', { name: 'PDF' }))
    await waitFor(() => expect(exportRun).toHaveBeenCalledWith('r-1', 'pdf'))
  })

  it('marks a run that can only be shown as issued, and a verdict that differs', async () => {
    get.mockResolvedValue([{ ...RUN, supersededBy: undefined, recomputable: false }])
    post.mockResolvedValue({ verdict: 'differs', recomputable: false })
    show('/reports/archive')
    expect(await screen.findByText('Archive only')).toBeInTheDocument()
    expect(screen.queryByText('Superseded')).toBeNull()
    expect(get.mock.calls[0][1]).toEqual({ params: { query: { template: undefined, limit: 200 } } })
    await userEvent.click(screen.getByTestId('run-verify-r-1'))
    const verdict = await screen.findByTestId('verdict-r-1')
    expect(verdict).toHaveTextContent('Differs from the data')
    expect(verdict.getAttribute('class')).toContain('bg-destructive')
    expect(within(screen.getByTestId('report-archive')).getByText('alice')).toBeInTheDocument()
  })

  it('keeps focus on the Verify button when the verdict arrives', async () => {
    get.mockResolvedValue([RUN, { ...RUN, runId: 'r-2', supersededBy: undefined }])
    post.mockResolvedValue({ verdict: 'identical', recomputable: true })
    show()
    const verify = await screen.findByTestId('run-verify-r-1')
    await userEvent.click(verify)
    await screen.findByTestId('verdict-r-1')
    // The same element, still in the page and focused: the cells were not mounted anew.
    expect(screen.getByTestId('run-verify-r-1')).toBe(verify)
    expect(verify).toHaveFocus()
  })
})
