import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoadedReconciliation, Reconciliation } from './api'
import ReconciliationPage from './ReconciliationPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ load: vi.fn(), prepare: vi.fn(), run: vi.fn(), download: vi.fn(),
  permissions: new Set<string>(), userId: 'accountant' }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadReconciliation: calls.load,
  prepare: calls.prepare,
  downloadReport: calls.download,
  save: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p), userId: calls.userId }),
  runProcess: calls.run,
  ApprovalPanel: ({ requestId }: { requestId: string }) => <div data-testid="approval-panel">{requestId}</div>,
}))

const REC: Reconciliation = {
  reconciliationId: 'r1', bankCode: 'OPERATING', statementDate: '2026-01-31', statementBalance: '256555.00',
  depositsInTransit: 0, outstandingPayments: '-45000.00', adjustedBalance: '211555.00', bookBalance: '211555.00',
  notInBooks: 0, difference: 0, status: 'PREPARED', preparedBy: 'accountant',
}

const loaded = (rec: Partial<Reconciliation>): LoadedReconciliation => ({
  rec: { ...REC, ...rec },
  rows: [
    { seq: 1, section: 'STATEMENT_BALANCE', itemDate: '2026-01-31', description: 'Balance per bank statement', amount: 256555 },
    { seq: 2, section: 'OUTSTANDING_PAYMENT', itemDate: '2026-01-30', reference: 'PAYROLL-2601', description: 'Payroll',
      amount: -45000 },
    { seq: 3, section: 'ADJUSTED_BANK_BALANCE', itemDate: '2026-01-31', amount: 211555 },
    { seq: 4, section: 'BOOK_BALANCE', itemDate: '2026-01-31', amount: 211555 },
    { seq: 5, section: 'DIFFERENCE', itemDate: '2026-01-31', amount: 0 },
    { seq: 6, section: 'PREPARED_BY', description: 'Prepared by accountant' },
  ],
})

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.bank.activity.read', 'fin.bank.reconcile'])
  calls.userId = 'accountant'
  calls.load.mockReset().mockResolvedValue(loaded({}))
  calls.prepare.mockReset().mockResolvedValue(REC)
  calls.run.mockReset().mockResolvedValue({})
  calls.download.mockReset().mockResolvedValue({ blob: new Blob(['x']), fileName: 'r.pdf' })
})

const render = () => renderAt('/bank/reconciliations/r1',
  [{ path: '/bank/reconciliations/:reconciliationId', element: <ReconciliationPage /> }])

describe('a bank reconciliation', () => {
  it('shows its figures and sections, and is completed by its preparer', async () => {
    render()
    expect(await screen.findByTestId('rec-difference')).toHaveTextContent('0.00')
    expect(screen.getByTestId('rec-adjusted')).toHaveTextContent('211,555.00')
    expect(screen.getByText('Outstanding payment')).toBeInTheDocument()
    expect(screen.getByText('PAYROLL-2601')).toBeInTheDocument()
    expect(screen.queryByText('Prepared by accountant')).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('rec-prepare'))
    await waitFor(() => expect(calls.prepare).toHaveBeenCalledWith('OPERATING', '2026-01-31'))
    await userEvent.click(screen.getByTestId('rec-complete'))
    await userEvent.click(await screen.findByTestId('rec-complete-confirm'))
    await waitFor(() => expect(calls.run).toHaveBeenCalledWith('FIN_BANK_REC_COMPLETE', { reconciliationId: 'r1' }))
  })

  it('shows the server refusing to complete it', async () => {
    calls.run.mockRejectedValue(new Error('The difference is -10.00'))
    render()
    await userEvent.click(await screen.findByTestId('rec-complete'))
    await userEvent.click(await screen.findByTestId('rec-complete-confirm'))
    expect(await screen.findByTestId('rec-error')).toHaveTextContent('The difference is -10.00')
  })

  it('waiting for review, offers its preparer the withdrawal and not the decision', async () => {
    calls.load.mockResolvedValue(loaded({ status: 'SUBMITTED', approvalRequestId: 'req-1' }))
    calls.permissions = new Set([...calls.permissions, 'approval.decide', 'fin.bank.rec.review'])
    render()
    await userEvent.click(await screen.findByTestId('rec-withdraw'))
    await waitFor(() => expect(calls.run).toHaveBeenCalledWith('FIN_BANK_REC_WITHDRAW', { reconciliationId: 'r1' }))
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
    expect(screen.queryByTestId('rec-complete')).not.toBeInTheDocument()
  })

  it('waiting for review, offers a reviewer the decision and not the withdrawal', async () => {
    calls.load.mockResolvedValue(loaded({ status: 'SUBMITTED', approvalRequestId: 'req-1' }))
    calls.userId = 'controller'
    calls.permissions = new Set([...calls.permissions, 'approval.decide', 'fin.bank.rec.review'])
    render()
    expect(await screen.findByTestId('approval-panel')).toHaveTextContent('req-1')
    expect(screen.queryByTestId('rec-withdraw')).not.toBeInTheDocument()
  })

  it('waiting for review, offers no decision to who may approve but not review reconciliations', async () => {
    calls.load.mockResolvedValue(loaded({ status: 'SUBMITTED', approvalRequestId: 'req-1' }))
    calls.userId = 'approver'
    calls.permissions = new Set(['fin.bank.activity.read', 'approval.decide'])
    render()
    expect(await screen.findByTestId('rec-status')).toHaveTextContent('Waiting for review')
    expect(screen.queryByTestId('approval-panel')).not.toBeInTheDocument()
  })

  it('signed off, is issued once and then downloaded from the archive', async () => {
    calls.load.mockResolvedValue(loaded({ status: 'SIGNED_OFF', reviewedBy: 'controller' }))
    render()
    await userEvent.click(await screen.findByTestId('rec-issue'))
    await waitFor(() => expect(calls.run).toHaveBeenCalledWith('FIN_BANK_REC_ISSUE', { reconciliationId: 'r1' }))
  })

  it('issued, offers its archived report', async () => {
    calls.load.mockResolvedValue(loaded({ status: 'SIGNED_OFF', reportRunId: 'run-1', reportHash: 'abcdef0123456789' }))
    calls.permissions.add('report.archive.read')
    render()
    expect(await screen.findByTestId('rec-report')).toHaveTextContent('abcdef012345')
    expect(screen.queryByTestId('rec-issue')).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('rec-pdf'))
    await waitFor(() => expect(calls.download).toHaveBeenCalledWith('run-1', 'pdf'))
  })
})
