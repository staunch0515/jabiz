import { screen, within } from '@testing-library/react'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Asset } from './api'
import AssetPage from './AssetPage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({ asset: vi.fn(), schedule: vi.fn(), project: vi.fn(), permissions: new Set<string>() }))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  loadAsset: calls.asset,
  loadSchedule: calls.schedule,
  project: calls.project,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
}))

const FA003: Asset = {
  assetId: 'a3', assetNo: 'FA-003', description: 'Application server', classCode: 'COMP', costAccount: '1520',
  cost: '12000.00', inServiceDate: '2026-01-15', method: 'SL', lifeMonths: 36, salvage: 0, status: 'IN_SERVICE',
  accumulated: '333.33', depreciatedThrough: '2026-01', active: true,
}

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.fa.read'])
  calls.asset.mockReset().mockResolvedValue(FA003)
  calls.schedule.mockReset().mockResolvedValue([{ periodKey: '2026-01', assetNo: 'FA-003', source: 'RUN',
    documentNo: 'DEP-2601', amount: '333.33', accumulated: '333.33' }])
  calls.project.mockReset().mockResolvedValue({ assetNo: 'FA-003', fromPeriod: '2026-02', byUse: false,
    accumulated: '333.33', months: [{ periodKey: '2026-02', amount: '333.33', accumulated: '666.66',
      netBookValue: '11333.34' }] })
})

const render = () => renderAt('/assets/a3', [{ path: '/assets/:assetId', element: <AssetPage /> }])

describe('an asset', () => {
  it('shows its figures, the months taken and the months ahead', async () => {
    render()
    expect(await screen.findByTestId('page-title')).toHaveTextContent('FA-003 · Application server')
    expect(screen.getByTestId('asset-nbv')).toHaveTextContent('11,666.67')
    expect(await within(screen.getByTestId('taken-table')).findByText('DEP-2601')).toBeInTheDocument()
    expect(await within(screen.getByTestId('ahead-table')).findByText('11,333.34')).toBeInTheDocument()
    expect(calls.project).toHaveBeenCalledWith('a3')
    expect(screen.queryByTestId('asset-actions')).not.toBeInTheDocument()
  })

  it('has no months ahead once disposed of, and says when it is missing', async () => {
    calls.asset.mockResolvedValueOnce({ ...FA003, status: 'DISPOSED' })
    render()
    await screen.findByTestId('page-title')
    expect(screen.queryByTestId('ahead-table')).not.toBeInTheDocument()
    expect(calls.project).not.toHaveBeenCalled()
  })

  it('says when there is no such asset', async () => {
    calls.asset.mockResolvedValueOnce(null)
    render()
    expect(await screen.findByTestId('asset-missing')).toBeInTheDocument()
  })
})
