import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import i18n from '../i18n'
import AccessReviewPage from './AccessReviewPage'

const get = vi.fn()
const run = vi.fn()
const permissions = new Set<string>()

vi.mock('../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/client')>()),
  api: { GET: (...args: unknown[]) => get(...args) },
  unwrap: async (value: unknown) => value,
}))
vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => run(...args) }))
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ can: (p: string) => permissions.has(p) }),
}))

function answer(path: string) {
  switch (path) {
    case '/api/security/access-reviews/changes':
      return { items: [{ recordNo: 3, entityType: 'SecUserRole', entityId: 'ur1', action: 'INSERT', actorId: 'admin',
        recordedTime: '2026-01-10T00:00:00Z', changes: { roleId: {}, userId: {} } }], hash: 'h' }
    case '/api/security/access-reviews/conflicts':
      return { items: [{ userId: 'u1', userName: 'alice', ruleCode: 'PAY', left: ['a'], right: ['b'], roles: ['AP'],
        wildcard: false }], hash: 'c' }
    default:
      return [{ reviewId: 'r1', periodFrom: '2026-01-01T00:00:00Z', periodTo: '2026-02-01T00:00:00Z',
        reviewer: 'auditor', changesCount: 1, conflicts: [], comment: 'fine', signedAt: '2026-02-02T00:00:00Z' }]
  }
}

function page() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <App>
        <MemoryRouter>
          <AccessReviewPage />
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('AccessReviewPage', () => {
  beforeEach(async () => {
    get.mockReset()
    run.mockReset()
    permissions.clear()
    get.mockImplementation((path: string) => Promise.resolve(answer(path)))
    await i18n.changeLanguage('en')
  })

  it('shows the changes of the period, the conflicts and the signed reviews', async () => {
    page()
    const changes = await screen.findByTestId('access-review-changes')
    await waitFor(() => expect(within(changes).getByText('SecUserRole')).toBeTruthy())
    expect(within(changes).getByText('roleId, userId')).toBeTruthy()
    const query = get.mock.calls.find(([path]) => path === '/api/security/access-reviews/changes')![1].params.query
    // Whole days: the end is the start of the day after the last one.
    expect(new Date(query.to).getTime() - new Date(query.from).getTime()).toBeGreaterThan(27 * 86400_000)
    expect(within(await screen.findByTestId('access-review-conflicts')).getByText('alice')).toBeTruthy()
    expect(within(await screen.findByTestId('access-review-list')).getByText('auditor')).toBeTruthy()
    // Without the permissions there is neither issuing nor signing.
    expect(screen.queryByTestId('access-review-issue')).toBeNull()
    expect(screen.queryByTestId('access-review-sign')).toBeNull()
  })

  it('issues the access report as of the end of the period, then signs with the comment', async () => {
    permissions.add('report.issue')
    permissions.add('security.access-review.sign')
    run.mockResolvedValueOnce({ runId: 'run-1' }).mockResolvedValueOnce({ reviewId: 'r2' })
    page()

    const sign = await screen.findByTestId('access-review-sign')
    expect((sign as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByTestId('access-review-issue'))
    await waitFor(() => expect(run).toHaveBeenCalledTimes(1))
    const [name, input] = run.mock.calls[0]
    expect(name).toBe('REPORT_ISSUE')
    expect(input.templateId).toBe('jabiz.security.access_review')
    expect(await screen.findByTestId('access-review-report')).toBeTruthy()

    fireEvent.change(screen.getByTestId('access-review-comment'), { target: { value: 'No findings.' } })
    await waitFor(() => expect((screen.getByTestId('access-review-sign') as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByTestId('access-review-sign'))
    await waitFor(() => expect(run).toHaveBeenCalledTimes(2))
    expect(run.mock.calls[1]).toEqual(['ACCESS_REVIEW_SIGN_OFF', {
      periodFrom: expect.any(String),
      periodTo: input.params.asOf,
      reportRunId: 'run-1',
      reviewComment: 'No findings.',
    }])
  })
})
