import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import BankSettingsPage from './BankSettingsPage'
import { CONTROL, PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  userId: 'me',
  settings: [] as Row[],
  changes: [] as Row[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: vi.fn(), POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p), userId: server.userId }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const KEYS = ['qb.bank.receiving.bank', 'qb.bank.receiving.branch', 'qb.bank.receiving.account-type',
  'qb.bank.receiving.account-number', 'qb.bank.receiving.account-holder', 'qb.topup.bank.min', 'qb.topup.bank.max']

const change = (id: string, key: string, value: string, proposedBy: string): Row => ({
  changeId: id, targetEntity: 'SysParam', status: 'PROPOSED', proposedBy, reason: 'Open bank top-ups',
  changeValues: JSON.stringify({ paramKey: key, value }),
})

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set(['control.propose', 'qb.admin.finance.read'])
  server.userId = 'me'
  // A new installation: the account declared but empty, the limits not declared at all.
  server.settings = KEYS.map((key, i) => ({ paramKey: key, value: i < 5 ? '' : null, paramExists: i < 5, seq: i + 1 }))
  server.changes = []
  server.runQuery.mockReset().mockImplementation(async (id: string) => {
    if (id !== TEMPLATE.bankSettings) throw new Error(`unexpected ${id}`)
    return { items: server.settings, offset: 0, limit: 7 }
  })
  server.post.mockReset().mockImplementation(async (_path: string, options: { params: { path: { resourceId: string } } }) => {
    if (options.params.path.resourceId !== CONTROL.changes) throw new ApiError(403, { detail: 'Forbidden' })
    return { items: server.changes.map((attributes) => ({ id: attributes.changeId, attributes })) }
  })
  server.runProcess.mockReset().mockResolvedValue({ changeIds: ['c1'], keys: KEYS })
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={['/qb/finance/bank-settings']}><BankSettingsPage /></MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('BankSettingsPage', () => {
  it('says bank top-ups are not ready and proposes every value at once', async () => {
    renderPage()
    expect(await screen.findByTestId('bank-settings-ready')).toHaveAttribute('data-ready', 'false')
    const user = userEvent.setup()
    await user.type(screen.getByTestId('bank-settings-bank'), 'Mizuho (0001)')
    await user.type(screen.getByTestId('bank-settings-branch'), 'Shibuya (123)')
    await user.type(screen.getByTestId('bank-settings-accountType'), 'Ordinary')
    await user.type(screen.getByTestId('bank-settings-accountNumber'), '1234567')
    await user.type(screen.getByTestId('bank-settings-accountHolder'), 'QUIZBUKS')
    await user.type(screen.getByTestId('bank-settings-min'), '1,000')
    await user.type(screen.getByTestId('bank-settings-max'), '500000')
    await user.type(screen.getByTestId('bank-settings-reason'), 'Open bank top-ups')
    await user.click(screen.getByTestId('bank-settings-propose'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROCESS.bankSettingsPropose, {
      bank: 'Mizuho (0001)', branch: 'Shibuya (123)', accountType: 'Ordinary', accountNumber: '1234567',
      accountHolder: 'QUIZBUKS', min: 1000, max: 500000, reason: 'Open bank top-ups' }))
    expect(await screen.findByTestId('bank-settings-proposed')).toBeInTheDocument()
  })

  it('starts the form from the values in force', async () => {
    server.settings = KEYS.map((key, i) => ({ paramKey: key, value: ['Mizuho (0001)', 'Shibuya (123)', 'Ordinary',
      '1234567', 'QUIZBUKS', '1000', '500000'][i], paramExists: true, seq: i + 1 }))
    renderPage()
    expect(await screen.findByTestId('bank-settings-ready')).toHaveAttribute('data-ready', 'true')
    expect(screen.getByTestId('bank-settings-bank')).toHaveValue('Mizuho (0001)')
    expect(screen.getByTestId('bank-settings-max')).toHaveValue('500000')
  })

  it('shows the open proposals beside the values in force, and publishes only another\'s', async () => {
    server.permissions = new Set(['control.publish', 'qb.admin.finance.read'])
    server.changes = [change('c-min', 'qb.topup.bank.min', '1000', 'alice'),
      change('c-bank', 'qb.bank.receiving.bank', 'Mizuho (0001)', 'alice'),
      change('c-mine', 'qb.bank.receiving.branch', 'Shibuya (123)', 'me'),
      change('c-other', 'qb.ai.enabled', 'true', 'alice')]
    renderPage()
    const proposals = await screen.findAllByTestId('bank-settings-proposal')
    // In the page's order, only the bank top-up's settings.
    expect(proposals.map((p) => p.dataset.key)).toEqual(['qb.bank.receiving.bank', 'qb.bank.receiving.branch',
      'qb.topup.bank.min'])
    expect(proposals[0]).toHaveTextContent('Mizuho (0001)')
    expect(proposals[0]).toHaveTextContent('Not set')
    expect(within(proposals[1]).getByText(/Your proposal/)).toBeInTheDocument()
    expect(screen.queryByTestId('bank-settings-form')).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('bank-settings-publish'))
    // One step on the server: all of them or none.
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledTimes(1))
    expect(server.runProcess).toHaveBeenCalledWith(PROCESS.bankSettingsPublish, {})
  })

  it('says why a proposal is refused', async () => {
    server.runProcess.mockRejectedValue(new ApiError(422, { detail: 'refused', violations: [
      { field: null, ruleCode: 'QB_BANK_SETTINGS_UNCHANGED', message: 'Nothing changed.' }] }))
    renderPage()
    await screen.findByTestId('bank-settings-form')
    const user = userEvent.setup()
    for (const field of ['bank', 'branch', 'accountType', 'accountNumber', 'accountHolder', 'min', 'max', 'reason']) {
      await user.type(screen.getByTestId(`bank-settings-${field}`), field === 'min' || field === 'max' ? '1' : 'x')
    }
    await user.click(screen.getByTestId('bank-settings-propose'))
    expect(await screen.findByTestId('bank-settings-problem')).toBeInTheDocument()
  })

  it('offers nothing to change without the permissions', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderPage()
    expect(await screen.findByText(/needs the permission/)).toBeInTheDocument()
    expect(screen.queryByTestId('bank-settings-form')).not.toBeInTheDocument()
    expect(server.post).not.toHaveBeenCalled()
  })
})
