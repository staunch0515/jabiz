import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApiError } from '@jabiz/admin'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OverviewRow, Period } from './api'
import { currentPeriod } from './api'
import ClosePage from './ClosePage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  periods: vi.fn(), overview: vi.fn(), exceptions: vi.fn(), artifacts: vi.fn(), reopens: vi.fn(),
  start: vi.fn(), check: vi.fn(), complete: vi.fn(), soft: vi.fn(), close: vi.fn(), reopen: vi.fn(),
  withdraw: vi.fn(), year: vi.fn(), permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadPeriods: calls.periods,
  loadOverview: calls.overview,
  loadExceptions: calls.exceptions,
  loadArtifacts: calls.artifacts,
  loadReopens: calls.reopens,
  startClose: calls.start,
  runChecks: calls.check,
  completeTask: calls.complete,
  softClose: calls.soft,
  closePeriod: calls.close,
  requestReopen: calls.reopen,
  withdrawReopen: calls.withdraw,
  closeYear: calls.year,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p), userId: 'accountant' }),
}))

const period = (periodKey: string, status: Period['status'], extra: Partial<Period> = {}): Period => ({
  periodId: periodKey, periodKey, fiscalYear: 2026, periodNo: Number(periodKey.slice(5)), adjustment: false,
  opening: false, startDate: `${periodKey}-01`, endDate: `${periodKey}-28`, status, ...extra })

const DECEMBER = period('2025-12', 'CLOSED')
const JANUARY = period('2026-01', 'OPEN')
const FEBRUARY = period('2026-02', 'OPEN')
const ADJUSTMENT = period('2026-13', 'OPEN', { adjustment: true, periodNo: 13 })

const task = (code: string, status: string, extra: Partial<OverviewRow> = {}): OverviewRow => ({
  rank: 0, section: 'TASK', code, name: `Task ${code}`, kind: 'AUTO', status, required: true, ...extra })

/** PC-009's January: 8 of 10 done, the two open manual tasks first. */
const OVERVIEW: OverviewRow[] = [
  { rank: 0, section: 'PROGRESS', code: 'PROGRESS', name: '8 of 10 done', status: 'IN_PROGRESS', done: 8, total: 10 },
  task('ACCRUALS', 'OPEN', { kind: 'MANUAL', owner: 'fin.journal.prepare', dueDate: '2026-02-03', taskId: 't-accruals',
    name: 'Accruals reviewed' }),
  task('REVIEW', 'OPEN', { kind: 'MANUAL', owner: 'fin.period.close', dueDate: '2026-02-05', taskId: 't-review' }),
  ...['ENTRIES_POSTED', 'BANK_RECONCILED', 'SUBLEDGERS', 'RECURRING_RUN', 'AUTO_REVERSALS', 'DEPRECIATION_RUN',
    'REVALUATION_RUN', 'CLEARING_ZERO'].map((code) => task(code, 'PASSED')),
  ...['GL', 'AR', 'AP', 'BANK', 'FA'].map((code) => ({ rank: 0, section: 'SUBLEDGER' as const, code, status: 'OPEN' })),
  { rank: 0, section: 'RECONCILIATION', code: 'OPERATING', name: '1010', status: 'SIGNED_OFF', owner: 'accountant',
    doneBy: 'controller', detail: 'Statement 2026-01-31, difference 0.00' },
]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.period.read'])
  calls.periods.mockReset().mockResolvedValue([DECEMBER, JANUARY, FEBRUARY, ADJUSTMENT])
  calls.overview.mockReset().mockResolvedValue(OVERVIEW)
  calls.exceptions.mockReset().mockResolvedValue([])
  calls.artifacts.mockReset().mockResolvedValue([])
  calls.reopens.mockReset().mockResolvedValue([])
  for (const action of [calls.start, calls.check, calls.complete, calls.soft, calls.close, calls.reopen, calls.withdraw,
    calls.year]) action.mockReset().mockResolvedValue({})
})

const render = (path = '/close') => renderAt(path, [{ path: '/close', element: <ClosePage /> }])

const rows = (testId: string) => within(screen.getByTestId(testId)).getAllByRole('row').slice(1)

describe('the close workspace', () => {
  it('starts at the earliest regular period not closed', () => {
    expect(currentPeriod([DECEMBER, JANUARY, FEBRUARY, ADJUSTMENT])).toBe('2026-01')
    expect(currentPeriod([DECEMBER])).toBe('2025-12')
    expect(currentPeriod([])).toBeNull()
  })

  it('shows the progress and the open tasks first with their owners and due dates (FIN-PC-009)', async () => {
    render()
    expect(await screen.findByText('8 of 10 done')).toBeInTheDocument()
    await waitFor(() => expect(calls.overview).toHaveBeenCalledWith('2026-01'))
    const tasks = rows('task-table')
    expect(tasks).toHaveLength(10)
    expect(tasks[0]).toHaveTextContent('ACCRUALS')
    expect(tasks[0]).toHaveTextContent('fin.journal.prepare')
    expect(tasks[0]).toHaveTextContent('Open')
    expect(tasks[1]).toHaveTextContent('REVIEW')
    expect(tasks[1]).toHaveTextContent('fin.period.close')
    expect(rows('subledger-table').map((r) => r.textContent)).toEqual(expect.arrayContaining(['General ledgerOpen']))
    expect(rows('reconciliation-table')[0]).toHaveTextContent('OPERATING1010Signed off')
    // Read only: nothing to start, check, complete or close.
    expect(screen.queryByTestId('close-start')).not.toBeInTheDocument()
    expect(screen.queryByTestId('close-close')).not.toBeInTheDocument()
    expect(screen.queryByTestId('complete-ACCRUALS')).not.toBeInTheDocument()
    // The exceptions need the journal, receivables and payables reads.
    expect(screen.queryByTestId('exception-table')).not.toBeInTheDocument()
  })

  it('runs the start and the checks, and completes a manual task with a note and evidence', async () => {
    calls.permissions.add('fin.close.task').add('fin.journal.prepare')
    render()
    await userEvent.click(await screen.findByTestId('close-start'))
    await waitFor(() => expect(calls.start).toHaveBeenCalledWith('2026-01'))
    expect(await screen.findByTestId('close-notice')).toHaveTextContent('The close of 2026-01 is started')
    await userEvent.click(screen.getByTestId('close-check'))
    await waitFor(() => expect(calls.check).toHaveBeenCalledWith('2026-01'))
    // Only the tasks the user's permissions own.
    expect(screen.queryByTestId('complete-REVIEW')).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('complete-ACCRUALS'))
    await userEvent.type(screen.getByTestId('complete-note'), 'Agreed to the schedule')
    const file = new File(['%PDF-1.4'], 'accruals.pdf', { type: 'application/pdf' })
    await userEvent.upload(screen.getByTestId('complete-evidence'), file)
    expect(screen.getByTestId('complete-file')).toHaveTextContent('accruals.pdf')
    await userEvent.click(screen.getByTestId('complete-submit'))
    await waitFor(() => expect(calls.complete).toHaveBeenCalledWith('t-accruals', 'Agreed to the schedule', file))
    expect(await screen.findByTestId('close-notice')).toHaveTextContent('ACCRUALS is done')
    expect(screen.queryByTestId('complete-card')).not.toBeInTheDocument()
  })

  it('soft-closes and closes in one action, and lists every item a refused close names', async () => {
    calls.permissions.add('fin.period.close')
    calls.close.mockRejectedValueOnce(new ApiError(422, { violations: [
      { field: null, ruleCode: 'FIN_CLOSE_CHECKS_FAILED', message: 'BANK_RECONCILED (Bank accounts reconciled) failed' },
      { field: null, ruleCode: 'FIN_CLOSE_CHECKS_FAILED', message: 'REVIEW (Trial balance reviewed) is not done' },
    ] }))
    render()
    await userEvent.click(await screen.findByTestId('close-soft'))
    await waitFor(() => expect(calls.soft).toHaveBeenCalledWith('2026-01'))
    await userEvent.click(screen.getByTestId('close-close'))
    const refusal = await screen.findByTestId('close-error')
    expect(within(refusal).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      'BANK_RECONCILED (Bank accounts reconciled) failed', 'REVIEW (Trial balance reviewed) is not done'])
    calls.close.mockResolvedValueOnce({ periodKey: '2026-01', status: 'CLOSED', artifactId: 'a1', seq: 1 })
    await userEvent.click(screen.getByTestId('close-close'))
    await waitFor(() => expect(calls.close).toHaveBeenLastCalledWith('2026-01', expect.any(String)))
    expect(await screen.findByTestId('close-notice')).toHaveTextContent('2026-01 is closed (close 1)')
    expect(screen.queryByTestId('close-error')).not.toBeInTheDocument()
  })

  it('shows a closed period\'s artifacts and its reopening requests, and asks for a reopening', async () => {
    calls.permissions.add('fin.period.reopen.request').add('fin.period.close')
    calls.artifacts.mockResolvedValue([
      { artifactId: 'a2', periodKey: '2025-12', seq: 2, closedBy: 'controller', closedAt: '2026-02-10T10:00:00Z',
        totalDebit: '1000.00', totalCredit: '1000.00', trialBalanceHash: 'h2', contentHash: 'c2c2c2c2c2c2c2c2',
        supersedes: 'a1', supersededBy: null },
      { artifactId: 'a1', periodKey: '2025-12', seq: 1, closedBy: 'controller', closedAt: '2026-01-05T10:00:00Z',
        totalDebit: '900.00', totalCredit: '900.00', trialBalanceHash: 'h1', contentHash: 'c1c1c1c1c1c1c1c1',
        supersedes: null, supersededBy: 'a2' },
    ])
    calls.reopens.mockResolvedValue([{ reopenId: 'r1', periodKey: '2025-12', reason: 'Late invoice',
      requestedBy: 'accountant', requestedAt: '2026-02-01T09:00:00Z', status: 'APPROVED', decidedBy: 'controller' }])
    render('/close?period=2025-12')
    expect(await screen.findByTestId('period-status')).toHaveTextContent('Closed')
    expect(screen.queryByTestId('close-close')).not.toBeInTheDocument()
    expect(screen.queryByTestId('close-start')).not.toBeInTheDocument()
    await waitFor(() => expect(rows('artifact-table')).toHaveLength(2))
    expect(rows('artifact-table')[0]).toHaveTextContent('Supersedes close 1')
    expect(rows('artifact-table')[1]).toHaveTextContent('Superseded by close 2')
    expect(screen.getByText('Issued trial balances')).toHaveAttribute('href',
      '/reports/archive?template=finance.gl.trial_balance')
    expect(rows('reopen-table')[0]).toHaveTextContent('Late invoice')
    await userEvent.type(screen.getByTestId('reopen-reason'), 'Audit adjustment')
    await userEvent.click(screen.getByTestId('reopen-request'))
    await waitFor(() => expect(calls.reopen).toHaveBeenCalledWith('2025-12', 'Audit adjustment'))
    expect(await screen.findByTestId('close-notice')).toHaveTextContent('Reopening 2025-12 waits for approval')
  })

  it('withdraws a pending request instead of asking again', async () => {
    calls.permissions.add('fin.period.reopen.request')
    calls.reopens.mockResolvedValue([{ reopenId: 'r2', periodKey: '2025-12', reason: 'Late invoice',
      requestedBy: 'accountant', requestedAt: '2026-02-01T09:00:00Z', status: 'PENDING' }])
    render('/close?period=2025-12')
    await userEvent.click(await screen.findByTestId('reopen-withdraw'))
    await waitFor(() => expect(calls.withdraw).toHaveBeenCalledWith('r2'))
    expect(screen.queryByTestId('reopen-request')).not.toBeInTheDocument()
  })

  it('offers the withdrawal only to the requester', async () => {
    calls.permissions.add('fin.period.reopen.request')
    calls.reopens.mockResolvedValue([{ reopenId: 'r3', periodKey: '2025-12', reason: 'Late invoice',
      requestedBy: 'someone-else', requestedAt: '2026-02-01T09:00:00Z', status: 'PENDING' }])
    render('/close?period=2025-12')
    await waitFor(() => expect(rows('reopen-table')[0]).toHaveTextContent('someone-else'))
    expect(screen.queryByTestId('reopen-withdraw')).not.toBeInTheDocument()
  })

  it('says a period not started is not started, and offers no task to one without the close task permission',
    async () => {
      calls.permissions.add('fin.journal.prepare')
      calls.overview.mockResolvedValueOnce([{ rank: 0, section: 'PROGRESS', code: 'PROGRESS', name: '0 of 0 done',
        status: 'NOT_STARTED', done: 0, total: 0 }]).mockResolvedValue(OVERVIEW)
      render('/close?period=2026-02')
      expect(await screen.findByTestId('close-progress')).toHaveTextContent('has not started')
      expect(screen.queryByText('0 of 0 done')).not.toBeInTheDocument()
      await userEvent.click(screen.getByRole('combobox', { name: 'Period' }))
      await userEvent.click(await screen.findByTitle('2026-01'))
      expect(await screen.findByText('8 of 10 done')).toBeInTheDocument()
      expect(screen.queryByTestId('complete-ACCRUALS')).not.toBeInTheDocument()
    })

  it('closes the year from its adjustment period', async () => {
    calls.permissions.add('fin.period.close').add('fin.close.task')
    calls.overview.mockResolvedValue(OVERVIEW.filter((r) => r.section === 'SUBLEDGER'))
    calls.year.mockResolvedValue({ fiscalYear: 2026, seq: 1, journalNo: 'CLS-2026', netIncome: '57120.00',
      artifactId: 'a9' })
    render('/close?period=2026-13')
    await userEvent.click(await screen.findByTestId('close-year'))
    await waitFor(() => expect(calls.year).toHaveBeenCalledWith(2026, expect.any(String)))
    expect(await screen.findByTestId('close-notice')).toHaveTextContent('CLS-2026 carries net income 57,120.00')
    expect(screen.queryByTestId('close-start')).not.toBeInTheDocument()
    expect(screen.queryByTestId('task-table')).not.toBeInTheDocument()
  })

  it('switches periods from the selector', async () => {
    render()
    await screen.findByText('8 of 10 done')
    await userEvent.click(screen.getByRole('combobox', { name: 'Period' }))
    await userEvent.click(await screen.findByTitle('2026-02'))
    await waitFor(() => expect(calls.overview).toHaveBeenCalledWith('2026-02'))
  })
})
