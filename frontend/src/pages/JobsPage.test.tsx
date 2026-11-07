import { ApiError } from '@jabiz/client'
import { notify } from '@jabiz/ui'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import JobsPage from './JobsPage'

const get = vi.fn()
const post = vi.fn()
let permissions = ['job.read', 'job.run']

vi.mock('@jabiz/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@jabiz/client')>()),
  api: { GET: (...args: unknown[]) => get(...args), POST: (...args: unknown[]) => post(...args) },
  unwrap: async (value: unknown) => {
    const answer = await value
    if (answer instanceof Error) throw answer
    return answer
  },
  useAuth: () => ({ can: (permission: string) => permissions.includes(permission) }),
  UserName: ({ id }: { id: string }) => <span>{`user ${id}`}</span>,
}))

const PARTIAL_RUN = {
  scheduledTime: '2026-02-05T03:10:00Z', instanceId: 'i-1', outcome: 'PARTIAL', processSeqId: null, error: null,
  startedTime: '2026-02-05T03:10:00Z', finishedTime: '2026-02-05T03:10:05Z', requestedBy: null, requestSeqId: null,
  items: { selected: 5, succeeded: 4, replayed: 0, busy: 0, failed: 1, deferred: 0, more: false },
}
const OK_RUN = {
  ...PARTIAL_RUN, scheduledTime: '2026-02-06T03:10:00Z', startedTime: '2026-02-06T03:10:00Z',
  finishedTime: '2026-02-06T03:10:02Z', outcome: 'SUCCEEDED',
  items: { selected: 2, succeeded: 2, replayed: 0, busy: 0, failed: 0, deferred: 0, more: false },
}
const RERUN = {
  ...PARTIAL_RUN, outcome: 'SUCCEEDED', startedTime: '2026-02-06T09:00:00Z', finishedTime: '2026-02-06T09:00:01Z',
  requestedBy: 'admin-1', requestSeqId: 42,
  items: { selected: 5, succeeded: 1, replayed: 4, busy: 0, failed: 0, deferred: 0, more: false },
}
const JOB = {
  name: 'commerce.unpaid-order-expire', kind: 'PER_ITEM', cron: '0 10 3 * * *', zone: 'Asia/Tokyo',
  processName: 'ORDER_EXPIRE', processVersion: 1, nextTime: '2026-02-07T03:10:00Z', running: false,
  rerunTimes: ['2026-02-06T03:10:00Z', '2026-02-05T03:10:00Z', '2026-02-04T03:10:00Z'],
  recentRuns: [OK_RUN, PARTIAL_RUN],
  failingItems: [{ itemKey: 'o-1', failures: 1, lastError: 'stock is gone', lastFailureTime: '2026-02-05T03:10:04Z',
    retryAt: '2026-02-05T03:10:04Z' }],
}

function page() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <JobsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('JobsPage', () => {
  beforeEach(async () => {
    get.mockReset()
    post.mockReset()
    permissions = ['job.read', 'job.run']
    await i18n.changeLanguage('en')
  })
  afterEach(() => vi.restoreAllMocks())

  it('lists the jobs with their latest run and shows the runs and failing items', async () => {
    get.mockResolvedValue([JOB])
    page()
    const last = await screen.findByTestId(`job-last-${JOB.name}`)
    expect(within(last).getByText('Succeeded')).toBeTruthy()
    expect(within(last).getByText(/2 items: 2 done/)).toBeTruthy()
    expect(screen.getByText('Item by item')).toBeTruthy()

    await userEvent.click(screen.getByTestId(`job-runs-${JOB.name}`))
    const sheet = await screen.findByRole('dialog', { name: `Runs of ${JOB.name}` })
    expect(within(sheet).getByText('Some items failed')).toBeTruthy()
    expect(within(sheet).getByText('stock is gone')).toBeTruthy()
    expect(within(sheet).getAllByText('Schedule').length).toBeGreaterThan(0)
    await expectAccessible()
  })

  it('re-runs the scheduled time that left something undone, with the reason, and says how it ended', async () => {
    const success = vi.spyOn(notify, 'success')
    let answered = false
    get.mockImplementation(async () => (answered ? [{ ...JOB, recentRuns: [RERUN, OK_RUN, PARTIAL_RUN] }] : [JOB]))
    post.mockImplementation(async () => {
      answered = true
      return { requestSeqId: 42, job: JOB.name, scheduledTime: PARTIAL_RUN.scheduledTime, replayed: false }
    })
    page()
    await userEvent.click(await screen.findByTestId(`job-rerun-${JOB.name}`))
    const dialog = await screen.findByRole('dialog', { name: `Re-run ${JOB.name}` })
    // The time offered first is the one whose run left items undone.
    expect(within(dialog).getByTestId('job-rerun-time').textContent).toContain('Some items failed')
    await expectAccessible()

    // A reason is needed.
    await userEvent.click(within(dialog).getByTestId('job-rerun-submit'))
    expect(within(dialog).getByText('Say why the job runs again.')).toBeTruthy()
    expect(post).not.toHaveBeenCalled()

    await userEvent.type(within(dialog).getByLabelText('Reason'), 'stock was corrected')
    await userEvent.click(within(dialog).getByTestId('job-rerun-submit'))
    await waitFor(() => expect(post).toHaveBeenCalledWith('/api/jobs/{job}/runs', {
      params: { path: { job: JOB.name } },
      body: { scheduledTime: PARTIAL_RUN.scheduledTime, reason: 'stock was corrected' },
    }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Once the run naming the request shows, the page says how it ended.
    await waitFor(() => expect(success).toHaveBeenCalledWith(expect.stringContaining('Succeeded')), { timeout: 5000 })
  })

  it('shows why the server refused a re-run', async () => {
    const error = vi.spyOn(notify, 'error')
    get.mockResolvedValue([JOB])
    post.mockResolvedValue(new ApiError(409, { title: 'Conflict', violations: [{ ruleCode: 'JOB_BUSY',
      message: 'The job is running; try again shortly.' }] }))
    page()
    await userEvent.click(await screen.findByTestId(`job-rerun-${JOB.name}`))
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Reason'), 'again')
    await userEvent.click(within(dialog).getByTestId('job-rerun-submit'))
    await waitFor(() => expect(error).toHaveBeenCalled())
    // The dialog stays open, to try again later.
    expect(screen.getByRole('dialog')).toBeTruthy()
  })

  it('offers no re-run without job.run, and none while the job runs', async () => {
    permissions = ['job.read']
    get.mockResolvedValue([JOB])
    page()
    await screen.findByTestId(`job-last-${JOB.name}`)
    expect(screen.queryByTestId(`job-rerun-${JOB.name}`)).toBeNull()
  })

  it('shows a running job and does not offer to re-run it meanwhile', async () => {
    get.mockResolvedValue([{ ...JOB, running: true }])
    page()
    expect(await screen.findByTestId(`job-running-${JOB.name}`)).toBeTruthy()
    expect((screen.getByTestId(`job-rerun-${JOB.name}`) as HTMLButtonElement).disabled).toBe(true)
  })
})
