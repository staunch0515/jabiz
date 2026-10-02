import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Run } from './api'
import { nextMonth } from './api'
import RevaluationPage from './RevaluationPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ runs: vi.fn(), revalue: vi.fn(), simulate: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadRuns: calls.runs,
  revalue: calls.revalue,
  simulate: calls.simulate,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const JANUARY: Run = { runId: 'r1', runNo: 'FXR-2601', periodKey: '2026-01', revaluationDate: '2026-01-31',
  reversalDate: '2026-02-01', rateType: 'CLOSING', total: '413.00', lineCount: 2, actor: 'accountant',
  runTime: '2026-02-02T09:00:00Z' }

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.master.read'])
  calls.runs.mockReset().mockResolvedValue([JANUARY])
  calls.revalue.mockReset()
  calls.simulate.mockReset()
})

const render = () => renderAt('/fx/revaluations', [{ path: '/fx/revaluations', element: <RevaluationPage /> }])

describe('the foreign currency revaluations', () => {
  it('follow the latest month', () => {
    expect(nextMonth([{ ...JANUARY, periodKey: '2026-12' }])).toBe('2027-01')
    expect(nextMonth([JANUARY])).toBe('2026-02')
    expect(nextMonth([])).toBeNull()
  })

  it('list the runs, read only without the run permission', async () => {
    render()
    expect(await screen.findByText('FXR-2601')).toBeInTheDocument()
    expect(screen.getByText('413.00')).toBeInTheDocument()
    expect(screen.queryByTestId('run')).not.toBeInTheDocument()
    expect(screen.queryByTestId('simulate')).not.toBeInTheDocument()
  })

  it('revalue the next month and say what was posted and when it reverses', async () => {
    calls.permissions.add('fin.fx.run')
    calls.revalue.mockResolvedValue({ runId: 'r2', runNo: 'FXR-2602', periodKey: '2026-02',
      revaluationDate: '2026-02-28', reversalDate: '2026-03-01', total: '-120.00', lineCount: 3, created: true })
    render()
    expect(await screen.findByTestId('run-month')).toHaveValue('2026-02')
    await userEvent.click(screen.getByTestId('run'))
    await waitFor(() => expect(calls.revalue).toHaveBeenCalledWith('2026-02', expect.any(String)))
    expect(await screen.findByTestId('run-result')).toHaveTextContent('FXR-2602 posted: -120.00 on 3 items')
  })

  it('say a month run already posts nothing again, and show a refusal', async () => {
    calls.permissions.add('fin.fx.run')
    calls.revalue.mockResolvedValueOnce({ ...JANUARY, lineCount: 2, created: false })
    render()
    await userEvent.clear(await screen.findByTestId('run-month'))
    await userEvent.type(screen.getByTestId('run-month'), '2026-01')
    await userEvent.click(screen.getByTestId('run'))
    expect(await screen.findByTestId('run-result')).toHaveTextContent('FXR-2601 was run already')
    calls.revalue.mockRejectedValueOnce(new Error('Period 2026-03 is closed'))
    await userEvent.clear(screen.getByTestId('run-month'))
    await userEvent.type(screen.getByTestId('run-month'), '2026-03')
    await userEvent.click(screen.getByTestId('run'))
    expect(await screen.findByTestId('run-error')).toHaveTextContent('Period 2026-03 is closed')
  })

  it('simulate a month beside its run, now or as recorded', async () => {
    calls.permissions.add('fin.fx.run')
    calls.simulate.mockResolvedValue({ periodKey: '2026-01', revaluationDate: '2026-01-31', runNo: 'FXR-2601',
      total: '590.00', originalTotal: '413.00', change: '177.00', lines: [
        { kind: 'RECEIVABLE', documentId: 'i1', documentNo: 'INV-1004', currency: 'EUR', openAmount: '50000.00',
          carryingUsd: '54250.00', rate: '1.0950000000', revaluedUsd: '54750.00', difference: '500.00',
          originalRate: '1.0920000000', originalDifference: '350.00', change: '150.00' },
      ] })
    render()
    expect(await screen.findByTestId('simulate-month')).toHaveValue('2026-01')
    await userEvent.click(screen.getByTestId('simulate-recorded'))
    await userEvent.click(screen.getByTestId('simulate'))
    await waitFor(() => expect(calls.simulate).toHaveBeenCalledWith('2026-01', true))
    expect(await screen.findByTestId('simulate-summary'))
      .toHaveTextContent('Now 590.00; FXR-2601 posted 413.00: a change of 177.00')
    expect(screen.getByTestId('simulate-table')).toHaveTextContent('INV-1004')
    expect(screen.getByTestId('simulate-table')).toHaveTextContent('1.095')
    expect(screen.getByTestId('simulate-table')).toHaveTextContent('150.00')
  })
})
