import { screen } from '@testing-library/react'
import { formatDate } from '@jabiz/admin'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import ReconciliationListPage from './ReconciliationListPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ recs: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadBanks: () => Promise.resolve([{ bankCode: 'OPERATING', glAccount: '1010' }]),
  loadReconciliations: calls.recs,
  loadStatements: () => Promise.resolve([]),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.bank.activity.read'])
  calls.recs.mockReset().mockResolvedValue([
    { reconciliationId: 'r2', bankCode: 'OPERATING', statementDate: '2026-02-28', statementBalance: 305490,
      depositsInTransit: 0, outstandingPayments: 0, adjustedBalance: 305490, bookBalance: 305490, notInBooks: 0,
      difference: 0, status: 'PREPARED', preparedBy: 'accountant' },
    { reconciliationId: 'r1', bankCode: 'OPERATING', statementDate: '2026-01-31', statementBalance: '256555.00',
      depositsInTransit: 0, outstandingPayments: -45000, adjustedBalance: 211555, bookBalance: 211555, notInBooks: 0,
      difference: 0, status: 'SIGNED_OFF', preparedBy: 'accountant', reviewedBy: 'controller' },
  ])
})

const render = () => renderAt('/bank/reconciliations',
  [{ path: '/bank/reconciliations', element: <ReconciliationListPage /> }])

describe('the bank reconciliations', () => {
  it('list each with its figures and state, opening its page', async () => {
    render()
    const january = await screen.findByRole('link', { name: formatDate('2026-01-31') })
    expect(january).toHaveAttribute('href', '/bank/reconciliations/r1')
    expect(screen.getByText('Signed off')).toBeInTheDocument()
    expect(screen.getByText('256,555.00')).toBeInTheDocument()
    expect(screen.queryByTestId('prepare')).not.toBeInTheDocument()
  })

  it('offer who reconciles to prepare one', async () => {
    calls.permissions.add('fin.bank.reconcile')
    render()
    expect(await screen.findByTestId('prepare')).toBeDisabled()
  })
})
