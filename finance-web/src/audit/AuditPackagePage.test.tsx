import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { sha256, type PackageOutput } from './api'
import AuditPackagePage from './AuditPackagePage'
import { renderAt, setUpTexts } from '../journal/testing'

const calls = vi.hoisted(() => ({
  issue: vi.fn(), download: vi.fn(), save: vi.fn(), runProcess: vi.fn(), permissions: new Set<string>(),
}))

vi.mock('./api', async (original) => ({
  ...(await original<typeof import('./api')>()),
  issuePackage: calls.issue,
  downloadPackage: calls.download,
  saveAnswer: calls.save,
}))

vi.mock('@jabiz/admin', async (original) => ({
  ...(await original<typeof import('@jabiz/admin')>()),
  useAuth: () => ({ can: (p: string) => calls.permissions.has(p) }),
  runProcess: calls.runProcess,
}))

const ANSWER: PackageOutput = {
  request: 'Manual entries above 10,000.00 in January',
  issuedTime: '2026-02-03T15:00:00Z',
  reports: [
    { templateId: 'finance.audit.manual_entries', runId: 'run-1', contentHash: 'a'.repeat(64), rows: 2 },
    { templateId: 'jabiz.security.access_review', runId: 'run-2', contentHash: 'b'.repeat(64), rows: 9 },
  ],
  export: { datasets: ['urn:jabiz:dataset:default:FinJournal'], reports: true,
    reportsFrom: '2026-02-03T15:00:00Z', reportsTo: '2026-02-03T15:00:00.001Z' },
}

const routes = [{ path: '/audit-package', element: <AuditPackagePage /> }]

beforeAll(setUpTexts)

beforeEach(() => {
  calls.permissions = new Set(['fin.audit.package', 'data.export', 'report.archive.read', 'fin.journal.read'])
  calls.issue.mockReset().mockResolvedValue(ANSWER)
  calls.download.mockReset().mockResolvedValue({ fileName: 'jabiz-export-1.zip', bytes: 10, sha256: 'c'.repeat(64) })
  calls.save.mockReset()
})

async function fill() {
  await userEvent.type(screen.getByTestId('field-request'), ANSWER.request)
  await userEvent.type(screen.getByTestId('field-from'), '2026-01-01')
  await userEvent.type(screen.getByTestId('field-to'), '2026-01-31')
  await userEvent.type(screen.getByTestId('field-minAmount'), '10000')
}

// Typing a whole request takes a while on a busy machine.
describe('the audit evidence package', { timeout: 20_000 }, () => {
  it('issues the request, lists its reports, downloads the package and shows how to check it', async () => {
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.type(screen.getByTestId('field-accessReviewAsOf'), '2026-02-01T06:00:00Z')
    await userEvent.click(screen.getByTestId('issue'))
    await waitFor(() => expect(calls.issue).toHaveBeenCalledTimes(1))
    expect(calls.issue.mock.calls[0][0]).toMatchObject({ request: ANSWER.request, from: '2026-01-01',
      to: '2026-01-31', minAmount: '10000', accessReviewAsOf: '2026-02-01T06:00:00Z' })
    const reports = await screen.findByTestId('reports')
    expect(within(reports).getByText('Manual entries with approvals')).toHaveAttribute('href',
      '/reports/archive?template=finance.audit.manual_entries')
    expect(within(reports).getByText('User access review')).toBeInTheDocument()
    expect(within(reports).getByText('a'.repeat(64))).toBeInTheDocument()

    await userEvent.click(screen.getByTestId('download'))
    expect(calls.download).toHaveBeenCalledWith(ANSWER.export)
    expect(await screen.findByTestId('package-sha256')).toHaveTextContent('c'.repeat(64))
    expect(screen.getByTestId('verify-command')).toHaveTextContent(
      `python3 verify-package.py jabiz-export-1.zip --expect package.json --package-sha256 ${'c'.repeat(64)}`)
    await userEvent.click(screen.getByTestId('save-answer'))
    expect(calls.save).toHaveBeenCalledWith(ANSWER)
  })

  it('issues the same request once, and a changed request as a new operation', async () => {
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.click(screen.getByTestId('issue'))
    await waitFor(() => expect(calls.issue).toHaveBeenCalledTimes(1))
    await userEvent.click(screen.getByTestId('issue'))
    await waitFor(() => expect(calls.issue).toHaveBeenCalledTimes(2))
    expect(calls.issue.mock.calls[1][1]).toBe(calls.issue.mock.calls[0][1])
    await userEvent.type(screen.getByTestId('field-minAmount'), '0')
    await userEvent.click(screen.getByTestId('issue'))
    await waitFor(() => expect(calls.issue).toHaveBeenCalledTimes(3))
    expect(calls.issue.mock.calls[2][1]).not.toBe(calls.issue.mock.calls[0][1])
  })

  it('checks the days before asking and shows the server refusal', async () => {
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.clear(screen.getByTestId('field-to'))
    await userEvent.type(screen.getByTestId('field-to'), '2026-1-31')
    await userEvent.click(screen.getByTestId('issue'))
    await screen.findByText(/does not match/i)
    expect(calls.issue).not.toHaveBeenCalled()

    const { ApiError } = await import('@jabiz/admin')
    calls.issue.mockRejectedValue(new ApiError(422, { title: 'Unprocessable', violations: [{ field: 'bankCode',
      ruleCode: 'FIN_AUDIT_NO_RECONCILIATION', message: 'There is no signed-off reconciliation of OPERATING' }] }))
    await userEvent.clear(screen.getByTestId('field-to'))
    await userEvent.type(screen.getByTestId('field-to'), '2026-01-31')
    await userEvent.click(screen.getByTestId('issue'))
    expect(await screen.findByTestId('errors')).toHaveTextContent('There is no signed-off reconciliation of OPERATING')
  })

  it('offers neither the issue nor the download without their permissions', async () => {
    calls.permissions = new Set(['fin.journal.read'])
    const { unmount } = renderAt('/audit-package', routes)
    expect(screen.queryByTestId('issue')).not.toBeInTheDocument()
    expect(screen.getByTestId('manual-entries-report')).toHaveAttribute('href',
      '/reports/run?id=finance.audit.manual_entries')
    unmount()
    // Issued, but the export also needs the archived reports' read.
    calls.permissions = new Set(['fin.audit.package', 'data.export'])
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.click(screen.getByTestId('issue'))
    await screen.findByTestId('reports')
    expect(screen.queryByTestId('download')).not.toBeInTheDocument()
  })

  it('takes the package away when the request changes, and when a new issue is refused', async () => {
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.click(screen.getByTestId('issue'))
    await screen.findByTestId('reports')
    await userEvent.type(screen.getByTestId('field-minAmount'), '0')
    expect(screen.queryByTestId('package')).not.toBeInTheDocument()
    const { ApiError } = await import('@jabiz/admin')
    calls.issue.mockRejectedValue(new ApiError(422, { title: 'Unprocessable' }))
    await userEvent.click(screen.getByTestId('issue'))
    await screen.findByTestId('errors')
    expect(screen.queryByTestId('package')).not.toBeInTheDocument()
  })

  it('checks the access review time before asking', async () => {
    renderAt('/audit-package', routes)
    await fill()
    await userEvent.type(screen.getByTestId('field-accessReviewAsOf'), '2026-02-01 06:00')
    await userEvent.click(screen.getByTestId('issue'))
    await screen.findByText(/does not match/i)
    expect(calls.issue).not.toHaveBeenCalled()
  })

  it('sends only the fields given', async () => {
    const real = await vi.importActual<typeof import('./api')>('./api')
    calls.runProcess.mockResolvedValue(ANSWER)
    await real.issuePackage({ request: 'r', from: '2026-01-01', to: '2026-01-31', minAmount: '10000',
      accessReviewAsOf: '', bankCode: undefined }, 'key-1')
    expect(calls.runProcess).toHaveBeenCalledWith('FIN_AUDIT_PACKAGE', { request: 'r', from: '2026-01-01',
      to: '2026-01-31', minAmount: '10000' }, { idempotencyKey: 'key-1' })
  })

  it('hashes as the offline check does', async () => {
    const bytes = new TextEncoder().encode('abc')
    expect(await sha256(bytes.buffer as ArrayBuffer))
      .toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')
  })
})
