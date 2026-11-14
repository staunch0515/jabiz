import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import AiPage from './AiPage'
import { failureRate, validAiFeeSpan } from './ai-api'
import { TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the AI page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  settings: [] as Row[],
  usage: [] as Row[],
  jobs: [] as Row[],
  fees: [] as Row[],
  sessionFetch: vi.fn(),
  runQuery: vi.fn(),
  runProcess: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    sessionFetch: server.sessionFetch,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

// The pickers search the server as they are typed in; here a click chooses one fixed value.
vi.mock('./pickers', () => ({
  UserPicker: ({ id, onChange, 'data-testid': testId }: { id: string; onChange: (v: string | null) => void; 'data-testid': string }) => (
    <button id={id} type="button" data-testid={testId} onClick={() => onChange('0192f0a0-0000-7000-8000-0000000000s1')}>Pick a Sponsor</button>
  ),
  OperatorPicker: ({ id, onChange, 'data-testid': testId }: { id: string; onChange: (v: string | null) => void; 'data-testid': string }) => (
    <button id={id} type="button" data-testid={testId} onClick={() => onChange('op-1')}>Pick an operator</button>
  ),
}))

const SPONSOR = '0192f0a0-0000-7000-8000-0000000000s1'

beforeAll(async () => {
  vi.useFakeTimers({ toFake: ['Date'] })
  // 2026-11-14 in Japan: the month the fees section opens on.
  vi.setSystemTime(new Date('2026-11-14T03:00:00Z'))
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['qb.admin.ai.read', 'control.propose'])
  server.settings = [
    { paramKey: 'qb.ai.enabled', paramId: 'p1', value: 'true', paramExists: true, seq: 1 },
    { paramKey: 'qb.ai.model', paramId: 'p2', value: 'claude-opus-5-5', paramExists: true, seq: 2 },
    { paramKey: 'qb.ai.sponsor.monthly-tokens', paramId: null, value: null, paramExists: false, seq: 5 },
  ]
  server.usage = [
    { usageId: 'u0', sponsorId: '*', company: null, month: '2026-10', jobs: 10, failedJobs: 2, inputTokens: 100000,
      outputTokens: 40000, estimatedUsd: '1.20' },
    { usageId: 'u1', sponsorId: SPONSOR, company: 'Acme', month: '2026-10', jobs: 4, failedJobs: 1,
      inputTokens: 50000, outputTokens: 20000, estimatedUsd: '0.60' },
  ]
  server.fees = [
    { month: '2026-11', sponsorId: SPONSOR, company: 'Acme', operatorId: 'op-1', operatorName: 'Kansai Partners', jobs: 4,
      costUsd: '1.234500', feeYen: '300', absorbedYen: '40', unrecordedUsd: '0.100000', absorbedTotalYen: '55',
      unbilledYen: '120', aiCredit: null },
  ]
  server.jobs = [
    { aiJobId: 'j1', sponsorId: SPONSOR, status: 'FAILED', model: 'claude-opus-5-5', servedModel: null, attempts: 1,
      candidateCount: null, failureCode: 'QB_AI_REFUSED', failureMessage: 'Claude declined the request',
      inputTokens: 500, outputTokens: 0, requestedAt: '2026-10-18T01:00:00Z' },
    { aiJobId: 'j2', sponsorId: SPONSOR, kind: 'COVER', status: 'READY', model: 'claude-opus-5-5', servedModel: 'claude-opus-5',
      attempts: 2, candidateCount: 5, failureCode: null, failureMessage: null, inputTokens: 1200, outputTokens: 800,
      requestedAt: '2026-10-18T00:00:00Z', feeMode: 'CREDIT', feeOperatorName: 'Kansai Partners' },
  ]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { params?: Row }) => {
    const items = id === TEMPLATE.aiSettings ? server.settings
      : id === TEMPLATE.aiUsage ? server.usage
        : id === TEMPLATE.aiFees ? server.fees
        : id === TEMPLATE.aiJobs ? server.jobs.filter((j) => !options.params?.failedOnly || j.status === 'FAILED')
          : []
    return { items, offset: 0, limit: 100 }
  })
  server.sessionFetch.mockReset().mockResolvedValue(new Response('a,b', { status: 200,
    headers: { 'Content-Disposition': 'attachment; filename="ai-fees.csv"' } }))
  server.runProcess.mockReset().mockResolvedValue({ changeId: 'c1', status: 'PROPOSED' })
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/ai']}>
          <AiPage />
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('AiPage', () => {
  it('shows the switch, the parameters, the usage with failures and the requests', async () => {
    renderPage()
    expect(await screen.findByTestId('ai-switch-state')).toHaveTextContent('On')
    const settings = screen.getByTestId('ai-settings')
    expect(within(settings).getAllByTestId('ai-setting').map((row) => row.dataset.key)).toEqual(['qb.ai.enabled',
      'qb.ai.model', 'qb.ai.sponsor.monthly-tokens'])
    expect(settings).toHaveTextContent('Tokens per Sponsor and month')
    expect(settings).toHaveTextContent('Not published yet')
    const usage = await screen.findByTestId('ai-usage')
    const rows = within(usage).getAllByTestId('ai-usage-row')
    expect(rows[0]).toHaveTextContent('All Sponsors')
    expect(rows[1]).toHaveTextContent('Acme')
    expect(rows[1]).toHaveTextContent('25.0% (1)')
    expect(rows[1]).toHaveTextContent('0.60')
    const jobs = await screen.findByTestId('ai-jobs')
    expect(within(jobs).getAllByTestId('ai-job-row')[0]).toHaveTextContent('QB_AI_REFUSED')
    // The fallback model that answered is marked.
    expect(within(jobs).getAllByTestId('ai-job-row')[1]).toHaveTextContent('claude-opus-5')
    // Q8b: the kind of each request; a row without one is of questions.
    expect(within(jobs).getAllByTestId('ai-job-kind').map((cell) => cell.textContent)).toEqual(['Questions', 'Covers'])
    // F-M9: how a request is charged and the operator its fee is frozen with, never an amount.
    const fees = within(jobs).getAllByTestId('ai-job-fee-mode').map((cell) => cell.textContent)
    expect(fees).toEqual(['', 'AI credit'])
    expect(within(jobs).getAllByTestId('ai-job-fee-operator').map((cell) => cell.textContent))
      .toEqual(['', 'Kansai Partners'])
    expect(jobs).not.toHaveTextContent(/yen|¥|Kudos/i)
    await expectAccessible()
  })

  it('proposes switching AI off with a reason, for another administrator to publish', async () => {
    renderPage()
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('ai-switch-propose'))
    expect(await screen.findByText('Give a reason.')).toBeInTheDocument()
    expect(server.runProcess).not.toHaveBeenCalled()
    await user.type(screen.getByTestId('ai-switch-reason'), 'Provider outage')
    await user.click(screen.getByTestId('ai-switch-propose'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith('CONTROL_CHANGE_PROPOSE', {
      targetEntity: 'SysParam', values: { paramKey: 'qb.ai.enabled', value: 'false' }, reason: 'Provider outage' }))
    expect(await screen.findByTestId('ai-switch-proposed')).toBeInTheDocument()
  })

  it('offers no proposal without the permission, and filters the failed requests and the month', async () => {
    server.permissions = new Set(['qb.admin.ai.read'])
    renderPage()
    expect(await screen.findByTestId('ai-no-control')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-switch-propose')).toBeNull()
    const user = userEvent.setup()
    await user.click(screen.getByTestId('ai-failed-only'))
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.aiJobs, expect.objectContaining({
      params: { failedOnly: true } })))
    await waitFor(() => expect(within(screen.getByTestId('ai-jobs')).getAllByTestId('ai-job-row')).toHaveLength(1))
    fireEvent.change(screen.getByTestId('ai-month'), { target: { value: '2026-09' } })
    await waitFor(() => expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.aiUsage, expect.objectContaining({
      params: { month: '2026-09' } })))
  })

  it('works out the failure rate only with requests', () => {
    expect(failureRate({ jobs: 4, failedJobs: 1 })).toBe(0.25)
    expect(failureRate({ jobs: 0, failedJobs: 0 })).toBeNull()
  })

  describe('AI fees (finance)', () => {
    const feeCalls = () => server.runQuery.mock.calls.filter(([id]) => id === TEMPLATE.aiFees)
      .map(([, options]) => options as { params: Row })

    it('is hidden without the finance permission and asks nothing', async () => {
      renderPage()
      await screen.findByTestId('ai-jobs')
      expect(screen.queryByTestId('ai-fees-section')).toBeNull()
      expect(feeCalls()).toHaveLength(0)
    })

    it('opens on the current month, shows the template\'s columns and no credit without a sponsor', async () => {
      server.permissions = new Set(['qb.admin.ai.read', 'qb.admin.finance.read'])
      renderPage()
      const table = await screen.findByTestId('ai-fees')
      expect(screen.getByTestId('ai-fees-month')).toHaveValue('2026-11')
      expect(feeCalls()[0].params).toEqual({ month: '2026-11' })
      const headers = within(table).getAllByRole('columnheader').map((h) => h.textContent)
      expect(headers).toEqual(['Month', 'Sponsor', 'Operator', 'Requests', 'Cost (USD)', 'Charged', 'Overrun taken on',
        'No answer (USD, estimate)', 'Taken on in all', 'Not charged yet'])
      const row = within(table).getByTestId('ai-fee-row')
      expect(row).toHaveTextContent('Acme')
      expect(row).toHaveTextContent('Kansai Partners')
      expect(row).toHaveTextContent('1.234500')
      // The total is the server's, not worked out from the two parts shown beside it.
      expect(within(row).getByTestId('ai-fee-absorbed-total')).toHaveTextContent('55')
      await expectAccessible()
    })

    it('shows the AI credit only when one sponsor is asked for, and asks by the span and the filters', async () => {
      server.permissions = new Set(['qb.admin.ai.read', 'qb.admin.finance.read', 'qb.admin.users.read',
        'qb.admin.operators.read'])
      renderPage()
      await screen.findByTestId('ai-fees')
      expect(screen.queryByTestId('ai-fee-credit')).toBeNull()
      expect(within(screen.getByTestId('ai-fees')).queryByRole('columnheader', { name: 'AI credit now' })).toBeNull()
      server.fees = [{ ...server.fees[0], aiCredit: '8000' }]
      const user = userEvent.setup()
      await user.click(screen.getByTestId('ai-fees-sponsor'))
      expect(await screen.findByTestId('ai-fee-credit')).toHaveTextContent('8,000')
      expect(feeCalls().at(-1)!.params).toEqual({ month: '2026-11', sponsorId: SPONSOR })
      await user.click(screen.getByTestId('ai-fees-operator'))
      await waitFor(() => expect(feeCalls().at(-1)!.params).toEqual({ month: '2026-11', sponsorId: SPONSOR,
        operatorId: 'op-1' }))
      fireEvent.change(screen.getByTestId('ai-fees-to'), { target: { value: '2026-12' } })
      await waitFor(() => expect(feeCalls().at(-1)!.params).toMatchObject({ month: '2026-11', toMonth: '2026-12' }))
      await expectAccessible()
    })

    it('refuses a span of more than 12 months or a reversed one, and does not ask or export it', async () => {
      server.permissions = new Set(['qb.admin.ai.read', 'qb.admin.finance.read'])
      renderPage()
      await screen.findByTestId('ai-fees')
      const asked = feeCalls().length
      fireEvent.change(screen.getByTestId('ai-fees-to'), { target: { value: '2027-11' } })
      expect(await screen.findByTestId('ai-fees-invalid')).toHaveTextContent('within 12 months')
      expect(screen.getByTestId('ai-fees-export-csv')).toBeDisabled()
      fireEvent.change(screen.getByTestId('ai-fees-to'), { target: { value: '2026-09' } })
      expect(await screen.findByTestId('ai-fees-invalid')).toBeInTheDocument()
      expect(screen.getByTestId('ai-fees-export-xlsx')).toBeDisabled()
      expect(feeCalls()).toHaveLength(asked)
      // Exactly 12 months in all is allowed.
      fireEvent.change(screen.getByTestId('ai-fees-to'), { target: { value: '2027-10' } })
      await waitFor(() => expect(screen.queryByTestId('ai-fees-invalid')).toBeNull())
      await expectAccessible()
    })

    it('exports every row through the platform', async () => {
      server.permissions = new Set(['qb.admin.ai.read', 'qb.admin.finance.read'])
      renderPage()
      await screen.findByTestId('ai-fees')
      await userEvent.click(screen.getByTestId('ai-fees-export-csv'))
      await waitFor(() => expect(server.sessionFetch).toHaveBeenCalled())
      const [url, init] = server.sessionFetch.mock.calls[0]
      expect(url).toBe('/api/queries/qb.admin.ai-fees/export?format=csv')
      expect(JSON.parse(init.body)).toEqual({ params: { month: '2026-11' } })
    })

    it('knows the spans the server answers', () => {
      expect(validAiFeeSpan({ month: '2026-11', toMonth: '' })).toBe(true)
      expect(validAiFeeSpan({ month: '2026-11', toMonth: '2027-10' })).toBe(true)
      expect(validAiFeeSpan({ month: '2026-11', toMonth: '2027-11' })).toBe(false)
      expect(validAiFeeSpan({ month: '2026-11', toMonth: '2026-10' })).toBe(false)
      expect(validAiFeeSpan({ month: '', toMonth: '' })).toBe(false)
    })
  })
})
