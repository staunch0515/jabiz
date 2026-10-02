import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Run } from './api'
import { nextMonth } from './api'
import DepreciationPage from './DepreciationPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ runs: vi.fn(), run: vi.fn(), reverse: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadRuns: calls.runs,
  runDepreciation: calls.run,
  reverseRun: calls.reverse,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const run = (periodKey: string, round: number, status: Run['status'], runNo: string): Run => ({
  runId: `${periodKey}-${round}`, runNo, periodKey, round, postingDate: `${periodKey}-28`, total: '4000.00',
  assetCount: 3, status, actor: 'accountant', runTime: '2026-02-01T09:00:00Z',
})

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.fa.read'])
  calls.runs.mockReset().mockResolvedValue([run('2026-02', 1, 'POSTED', 'DEP-2602'),
    run('2026-01', 2, 'POSTED', 'DEP-2601-2'), run('2026-01', 1, 'REVERSED', 'DEP-2601')])
  calls.run.mockReset()
  calls.reverse.mockReset().mockResolvedValue({ runId: '2026-02-1', runNo: 'DEP-2602' })
})

const render = () => renderAt('/assets/depreciation', [{ path: '/assets/depreciation', element: <DepreciationPage /> }])

describe('the depreciation runs', () => {
  it('follow the latest posted month', () => {
    expect(nextMonth([run('2026-12', 1, 'POSTED', 'DEP-2612')])).toBe('2027-01')
    expect(nextMonth([run('2026-03', 2, 'REVERSED', 'DEP-2603-2'), run('2026-02', 1, 'POSTED', 'DEP-2602')]))
      .toBe('2026-03')
    expect(nextMonth([])).toBeNull()
  })

  it('list the runs, read only without the run permission', async () => {
    render()
    expect(await screen.findByText('DEP-2601-2')).toBeInTheDocument()
    expect(screen.getByText('Reversed')).toBeInTheDocument()
    expect(screen.queryByTestId('run')).not.toBeInTheDocument()
    expect(screen.queryByTestId('reverse')).not.toBeInTheDocument()
  })

  it('run the next month and say what was posted', async () => {
    calls.permissions.add('fin.fa.run')
    calls.run.mockResolvedValue({ runId: 'r3', runNo: 'DEP-2603', periodKey: '2026-03', posted: true,
      total: '3322.69', assetCount: 3 })
    render()
    const month = await screen.findByTestId('run-month')
    await waitFor(() => expect(month).toHaveValue('2026-03'))
    await userEvent.click(screen.getByTestId('run'))
    expect(await screen.findByTestId('run-result')).toHaveTextContent('DEP-2603 posted: 3,322.69 for 3 assets')
    expect(calls.run).toHaveBeenCalledWith('2026-03', expect.any(String))
  })

  it('reverse only the latest posted run, with a reason', async () => {
    calls.permissions.add('fin.fa.run')
    render()
    const buttons = await screen.findAllByTestId('reverse')
    expect(buttons).toHaveLength(1)
    await userEvent.click(buttons[0])
    expect(screen.getByTestId('reverse-confirm')).toBeDisabled()
    await userEvent.type(screen.getByTestId('reverse-reason'), 'Late bill')
    await userEvent.click(screen.getByTestId('reverse-confirm'))
    await waitFor(() => expect(calls.reverse).toHaveBeenCalledWith('2026-02', 'Late bill'))
  })
})
