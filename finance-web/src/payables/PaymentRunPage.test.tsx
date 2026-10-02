import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@jabiz/admin'
import type { LoadedRun } from './api'
import PaymentRunPage from './PaymentRunPage'
import ProposeRunPage from './ProposeRunPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  loadRun: vi.fn(),
  proposeRun: vi.fn(),
  findBill: vi.fn(),
  download: vi.fn(),
  runProcess: vi.fn(),
  permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadRun: calls.loadRun,
  proposeRun: calls.proposeRun,
  findBill: calls.findBill,
  loadDefaultBank: async () => 'OPERATING',
  downloadGeneratedFile: calls.download,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  runProcess: calls.runProcess,
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
  ApprovalPanel: ({ requestId }: { requestId: string }) => <div data-testid="approval-panel">{requestId}</div>,
}))

const ROUTES = [
  { path: '/payables/runs/new', element: <ProposeRunPage /> },
  { path: '/payables/runs/:runId', element: <PaymentRunPage /> },
]

const DRAFT: LoadedRun = {
  run: { runId: 'r-1', runNo: 'PAY-RUN-01', bankCode: 'OPERATING', paymentDate: '2026-01-08', method: 'ACH',
    status: 'DRAFT', total: '32300.00', lineCount: 2, preparedBy: 'clerk' },
  lines: [
    { lineId: 'l-1', runId: 'r-1', kind: 'BILL', billId: 'b-1', billNo: 'P-7781', vendorCode: 'V100',
      payee: 'Precision Parts Co.', amount: '28300.00' },
    { lineId: 'l-2', runId: 'r-1', kind: 'BILL', billId: 'b-2', billNo: 'CPL-1225', vendorCode: 'V600',
      payee: 'City Power & Light', amount: '4000.00' },
  ],
  payments: [],
  files: [],
}

const RELEASED: LoadedRun = {
  ...DRAFT,
  run: { ...DRAFT.run, status: 'RELEASED', approvedBy: 'controller', releasedBy: 'treasurer' },
  payments: [
    { paymentId: 'p-1', paymentNo: 'PMT-0001', runId: 'r-1', runNo: 'PAY-RUN-01', kind: 'BILL', vendorCode: 'V100',
      payee: 'Precision Parts Co.', method: 'ACH', paymentDate: '2026-01-08', amount: '28300.00', status: 'POSTED' },
  ],
  files: [
    { paymentFileId: 'f-1', runId: 'r-1', fileKind: 'NACHA', fileName: 'PAY-RUN-01-ACH.txt', generatedFileId: 'g-1',
      sha256: 'abc', entryCount: 2, total: '32300.00', status: 'ACTIVE', generatedBy: 'treasurer' },
  ],
}

beforeAll(setUpTexts)

beforeEach(() => {
  for (const fn of [calls.loadRun, calls.proposeRun, calls.findBill, calls.download, calls.runProcess]) fn.mockReset()
  calls.permissions = new Set(['fin.ap.read', 'fin.payment.prepare'])
})

describe('payment runs', () => {
  it('proposes a run and shows what it pays and what it held, and why', async () => {
    calls.proposeRun.mockResolvedValue({ runId: 'r-1', runNo: 'PAY-RUN-01', status: 'DRAFT', lineCount: 2,
      held: [{ billId: 'b-3', billNo: 'DC-2025-12', vendorCode: 'V200', amount: '9000.00',
        reason: 'bank details pending approval' }], payments: [] })
    calls.loadRun.mockResolvedValue(DRAFT)
    renderAt('/payables/runs/new', ROUTES)
    expect(await screen.findByTestId('run-date')).toHaveFocus()
    await userEvent.type(screen.getByTestId('run-date'), '2026-01-08')
    await userEvent.type(screen.getByTestId('run-due'), '2026-01-20')
    await userEvent.type(screen.getByTestId('run-vendors'), 'v100, v600')
    await userEvent.click(screen.getByTestId('run-propose'))
    await waitFor(() => expect(calls.proposeRun).toHaveBeenCalled())
    expect(calls.proposeRun.mock.calls[0][0]).toEqual({ paymentDate: '2026-01-08', method: 'ACH', bankCode: null,
      dueThrough: '2026-01-20', vendorCodes: ['V100', 'V600'], takeDiscounts: false, description: null,
      currency: null, exchangeRate: null })
    expect(await screen.findByTestId('run-page')).toBeInTheDocument()
    expect(screen.getByTestId('held-table')).toHaveTextContent('bank details pending approval')
    expect(screen.getByTestId('run-total')).toHaveTextContent('32,300.00')
    expect(screen.getByTestId('run-lines')).toHaveTextContent('Precision Parts Co.')
  })

  it('proposes a run in euros, by wire, without discounts, at the rate given', async () => {
    calls.proposeRun.mockResolvedValue({ runId: 'r-1', runNo: 'PAY-RUN-01', status: 'DRAFT', lineCount: 2,
      held: [], payments: [] })
    calls.loadRun.mockResolvedValue(DRAFT)
    renderAt('/payables/runs/new', ROUTES)
    await userEvent.type(await screen.findByTestId('run-date'), '2026-01-31')
    expect(screen.queryByTestId('run-rate')).not.toBeInTheDocument()
    await userEvent.type(screen.getByTestId('run-currency'), 'eur')
    expect(screen.getByTestId('run-foreign-help')).toHaveTextContent('A run in EUR pays only bills in EUR')
    await userEvent.type(screen.getByTestId('run-rate'), '1.0920')
    await userEvent.click(screen.getByTestId('run-propose'))
    await waitFor(() => expect(calls.proposeRun).toHaveBeenCalled())
    expect(calls.proposeRun.mock.calls[0][0]).toEqual(expect.objectContaining({ currency: 'EUR',
      exchangeRate: '1.0920', takeDiscounts: false, method: 'WIRE' }))
  })

  it('adds a bill by its number, removes a line and submits the draft', async () => {
    calls.loadRun.mockResolvedValue(DRAFT)
    calls.findBill.mockResolvedValue({ billId: 'b-3', billNo: 'DC-2025-12' })
    calls.runProcess.mockResolvedValue({})
    renderAt('/payables/runs/r-1', ROUTES)
    await userEvent.type(await screen.findByTestId('add-bill-no'), 'dc-2025-12{Enter}')
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_RUN_ADD',
      { runId: 'r-1', kind: 'BILL', billId: 'b-3', amount: null }, { idempotencyKey: expect.any(String) }))
    await userEvent.click(screen.getByTestId('remove-CPL-1225'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_RUN_REMOVE',
      { runId: 'r-1', lineId: 'l-2' }))
    await userEvent.click(screen.getByTestId('run-submit'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_RUN_SUBMIT', { runId: 'r-1' }))
  })

  it('says why a line was not added', async () => {
    calls.loadRun.mockResolvedValue(DRAFT)
    calls.findBill.mockResolvedValueOnce(null).mockResolvedValueOnce({ billId: 'b-4', billNo: 'P-7902' })
    calls.runProcess.mockRejectedValue(new ApiError(422, { title: 'Refused', detail: 'P-7902 is held: not approved' }))
    renderAt('/payables/runs/r-1', ROUTES)
    await userEvent.type(await screen.findByTestId('add-bill-no'), 'NOPE{Enter}')
    expect(await screen.findByText('There is no bill NOPE.')).toBeInTheDocument()
    await userEvent.clear(screen.getByTestId('add-bill-no'))
    await userEvent.type(screen.getByTestId('add-bill-no'), 'P-7902{Enter}')
    expect(await screen.findByText('P-7902 is held: not approved')).toBeInTheDocument()
  })

  it('lets the approver decide a submitted run and the treasury release an approved one', async () => {
    calls.permissions = new Set(['fin.ap.read', 'approval.decide'])
    calls.loadRun.mockResolvedValue({ ...DRAFT, run: { ...DRAFT.run, status: 'SUBMITTED', approvalRequestId: 'q-1' } })
    const view = renderAt('/payables/runs/r-1', ROUTES)
    expect(await screen.findByTestId('approval-panel')).toHaveTextContent('q-1')
    expect(screen.queryByTestId('run-release')).not.toBeInTheDocument()
    view.unmount()

    calls.permissions = new Set(['fin.ap.read', 'fin.payment.release'])
    calls.loadRun.mockResolvedValue({ ...DRAFT, run: { ...DRAFT.run, status: 'APPROVED' } })
    calls.runProcess.mockResolvedValue({})
    renderAt('/payables/runs/r-1', ROUTES)
    await userEvent.click(await screen.findByTestId('run-release'))
    // The confirmation's own button, after the one that opened it.
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Release' })).toHaveLength(2))
    await userEvent.click(screen.getAllByRole('button', { name: 'Release' }).at(-1) as HTMLElement)
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_RUN_RELEASE', { runId: 'r-1' }))
    expect(screen.queryByTestId('run-submit')).not.toBeInTheDocument()
  })

  it('makes, downloads and cancels the bank files of a released run, and voids a payment', async () => {
    calls.permissions = new Set(['fin.ap.read', 'fin.payment.release', 'fin.payment.void', 'file.generated.read'])
    calls.loadRun.mockResolvedValue(RELEASED)
    calls.runProcess.mockResolvedValue({})
    calls.download.mockResolvedValue({ blob: new Blob(['1']), fileName: 'PAY-RUN-01-ACH.txt' })
    URL.createObjectURL = vi.fn(() => 'blob:x')
    URL.revokeObjectURL = vi.fn()
    renderAt('/payables/runs/r-1', ROUTES)
    await userEvent.click(await screen.findByTestId('generate-NACHA'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_FILE_GENERATE',
      { runId: 'r-1', fileKind: 'NACHA' }))
    await userEvent.click(screen.getByTestId('download-NACHA'))
    await waitFor(() => expect(calls.download).toHaveBeenCalledWith('g-1'))
    const files = screen.getByTestId('run-files')
    await userEvent.click(within(files).getByRole('button', { name: 'Cancel the file' }))
    await userEvent.type(screen.getAllByLabelText('Why').at(-1) as HTMLElement, 'Bank refused it')
    await userEvent.click(screen.getAllByRole('button', { name: 'Cancel the file' }).at(-1) as HTMLElement)
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_FILE_CANCEL',
      { paymentFileId: 'f-1', reason: 'Bank refused it' }))

    await userEvent.click(screen.getByTestId('void-PMT-0001'))
    await userEvent.type(screen.getByLabelText('Void date'), '2026-02-10')
    await userEvent.type(screen.getByLabelText('Reason'), 'Returned by the bank')
    await userEvent.click(screen.getByTestId('void-payment-confirm'))
    await waitFor(() => expect(calls.runProcess).toHaveBeenCalledWith('FIN_PAYMENT_VOID',
      { paymentId: 'p-1', voidDate: '2026-02-10', reason: 'Returned by the bank' }))
  })
})
