import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import IntegrityPage from './IntegrityPage'

const get = vi.fn()
const runProcess = vi.fn()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: (value: unknown) => value,
}))
vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => runProcess(...args) }))
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ can: () => true }) }))

const HEAD = {
  sealNo: 12, sealedTime: '2026-02-05T15:00:00Z', rowCount: 40, keyId: 'k1', currentKeyId: 'k1',
  sealHash: 'c2741d77d343f5ede1c4130251ff5848eb9b3dabcfb1e1eb4f402d774be36e9a',
}
const CHECK = {
  checkNo: 3, checkedTime: '2026-02-05T16:00:00Z', actorId: 'auditor', fromSeal: 1, toSeal: 12, sealCount: 12,
  rowCount: 400, unsealedCount: 2, problemCount: 1, intact: false, keyId: 'k1',
}
const PROBLEM = { kind: 'MODIFIED', sealNo: 4, table: 'ledger_entry_version', key: '[17]', detail: 'changed' }

describe('IntegrityPage', () => {
  beforeEach(async () => {
    get.mockReset()
    runProcess.mockReset()
    await i18n.changeLanguage('en')
  })

  it('shows the head, runs a verification and lists what it found', async () => {
    get.mockImplementation((path: string) => {
      if (path === '/api/integrity/head') return Promise.resolve(HEAD)
      if (path === '/api/integrity/checks') return Promise.resolve({ items: [CHECK], offset: 0, limit: 50 })
      return Promise.resolve({ check: CHECK, problems: [PROBLEM] })
    })
    runProcess.mockResolvedValue({ checkNo: 3, intact: false, problemCount: 1, sealCount: 12, rowCount: 400,
      unsealedCount: 2 })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter>
            <IntegrityPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect((await screen.findByTestId('integrity-head-hash')).textContent).toContain(HEAD.sealHash)
    expect((await screen.findByTestId('integrity-outcome-3')).textContent).toBe('1 problems')

    fireEvent.click(screen.getByTestId('integrity-verify'))
    await waitFor(() => expect(runProcess).toHaveBeenCalledWith('INTEGRITY_VERIFY', {}))
    const problems = await screen.findByTestId('integrity-problems')
    expect(within(problems).getByText('Changed')).toBeTruthy()
    expect(within(problems).getByText('ledger_entry_version')).toBeTruthy()
    expect(get).toHaveBeenCalledWith('/api/integrity/checks/{checkNo}', { params: { path: { checkNo: 3 } } })
  })

  it('says when nothing is sealed yet', async () => {
    get.mockImplementation((path: string) => Promise.resolve(path === '/api/integrity/head'
      ? { currentKeyId: 'k1' } : { items: [], offset: 0, limit: 50 }))
    render(
      <QueryClientProvider client={new QueryClient()}>
        <App>
          <MemoryRouter>
            <IntegrityPage />
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )
    expect(await screen.findByText('Nothing is sealed yet.')).toBeTruthy()
  })
})
