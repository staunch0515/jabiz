import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RegisterRow } from './api'
import JournalListPage from './JournalListPage'
import { renderAt, setUpTexts } from './testing'

const calls = vi.hoisted(() => ({ loadRegister: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadRegister: calls.loadRegister,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const ROWS: RegisterRow[] = [
  { journalId: 'a', journalNo: 'JE-0001', postingDate: '2026-01-15', description: 'Bonus payout', source: 'MANUAL',
    status: 'POSTED', totalDebit: 15000, totalCredit: 15000, preparer: 'accountant', glNo: 'GJ-MAN-2026-000001' },
  { journalId: 'b', journalNo: null, postingDate: '2026-01-31', description: 'Tax provision', source: 'MANUAL',
    status: 'DRAFT', totalDebit: '1362.90', totalCredit: '1362.90', preparer: 'accountant' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.loadRegister.mockReset().mockResolvedValue({ items: ROWS, offset: 0, limit: 500, total: 2 })
  calls.permissions = new Set(['fin.journal.read'])
})

describe('JournalListPage', () => {
  it('lists the register of the year with exact totals and links to each entry', async () => {
    renderAt('/gl/journals', [{ path: '/gl/journals', element: <JournalListPage /> }])
    expect(await screen.findByText('Bonus payout')).toBeInTheDocument()
    const year = new Date().getFullYear()
    expect(calls.loadRegister).toHaveBeenCalledWith({ from: `${year}-01-01`, to: `${year}-12-31`, status: null })
    expect(screen.getByTestId('register-total')).toHaveTextContent('16,362.90')
    expect(screen.getByRole('link', { name: 'JE-0001' })).toHaveAttribute('href', '/gl/journals/a')
    expect(screen.getByRole('link', { name: '(draft)' })).toHaveAttribute('href', '/gl/journals/b')
    expect(screen.queryByTestId('new-journal')).not.toBeInTheDocument()
  })

  it('shows the latest numbered entries first', async () => {
    const later = { ...ROWS[0], journalId: 'c', journalNo: 'JE-0002', description: 'Rent accrual' }
    calls.loadRegister.mockResolvedValue({ items: [ROWS[0], ROWS[1], later], offset: 0, limit: 500, total: 3 })
    renderAt('/gl/journals', [{ path: '/gl/journals', element: <JournalListPage /> }])
    await screen.findByText('Rent accrual')
    const order = screen.getAllByRole('link').map((link) => link.textContent)
    expect(order).toEqual(['JE-0002', 'JE-0001', '(draft)'])
  })

  it('says when the register shows only the first entries', async () => {
    calls.loadRegister.mockResolvedValue({ items: ROWS, offset: 0, limit: 500, total: 812 })
    renderAt('/gl/journals', [{ path: '/gl/journals', element: <JournalListPage /> }])
    expect(await screen.findByTestId('register-capped')).toHaveTextContent('Only the first 2 of 812 entries')
  })

  it('narrows the register by date and offers a new entry to preparers', async () => {
    calls.permissions.add('fin.journal.prepare')
    renderAt('/gl/journals', [{ path: '/gl/journals', element: <JournalListPage /> }])
    await screen.findByText('Bonus payout')
    expect(screen.getByTestId('new-journal')).toBeInTheDocument()
    const from = screen.getByLabelText('From')
    await userEvent.clear(from)
    await userEvent.type(from, '2026-01-20')
    await waitFor(() =>
      expect(calls.loadRegister).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-01-20' })),
    )
  })
})
