import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import { FundSwitches } from './FundSwitches'
import { MANUAL_ROWS, nameId, sameValue, stateOf, WAITING_ROWS } from './first-launch-api'
import { CONTROL, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the card sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  switches: [] as Row[] | Error,
  changes: [] as Row[],
  runQuery: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: vi.fn(), POST: server.post },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const FINANCE = ['qb.admin.finance.read', 'approval.read', 'control.propose']

/** A row of the template, as the server answers it. */
function row(paramKey: string, grp: string, expected: string | null, value: string | null, paramExists: boolean): Row {
  return { paramKey, grp, expected, value, paramExists, seq: 1 }
}

function change(key: string, value: string, by = 'proposer-1', reason = 'First launch value of the switch'): Row {
  return { changeId: `c-${key}`, targetEntity: 'SysParam', status: 'PROPOSED', proposedBy: by, reason,
    changeValues: JSON.stringify({ paramKey: key, value }) }
}

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', fallbackLng: 'en',
    resources: { en: { [EXTENSION_NAMESPACE]: messages.en }, zh: { [EXTENSION_NAMESPACE]: messages.zh },
      ja: { [EXTENSION_NAMESPACE]: messages.ja } } })
})

beforeEach(async () => {
  await i18next.changeLanguage('en')
  server.permissions = new Set(FINANCE)
  server.switches = [
    row('qb.fee-credit.grant.enabled', 'grant', 'true', 'true', true),
    row('qb.fee-credit.grant.basic', 'grant', null, '1000', true),
    row('qb.help.enabled', 'help', 'true', null, false),
    row('qb.ai.enabled', 'ai', 'true', null, false),
    row('qb.creator-fee-rate', 'fees', '0', '0.0000', true),
    row('qb.kudos.buy.enabled', 'kudos', 'false', 'true', true),
    row('qb.reward.default-methods', 'kudos', '', '', true),
    row('qb.reward.kudos.enabled', 'kudos', 'false', 'true', true),
    row('qb.topup.targets', 'topup', 'CASH', 'KUDOS,CASH', true),
  ]
  server.changes = [change('qb.ai.enabled', 'true'), change('qb.reward.kudos.enabled', 'false', 'proposer-2'),
    change('qb.unrelated.enabled', 'true')]
  server.runQuery.mockReset().mockImplementation(async (id: string) => {
    if (id !== TEMPLATE.adminFirstLaunchSwitches) throw new Error(`unexpected template ${id}`)
    if (server.switches instanceof Error) throw server.switches
    return { items: server.switches, offset: 0, limit: 100 }
  })
  server.post.mockReset().mockImplementation(async () => ({ items: server.changes.map((attributes) => ({ attributes })) }))
})

afterEach(cleanup)

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter>
          <FundSwitches />
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const stateOfRow = (key: string) => screen.getByTestId('fund-switches').querySelector(`[data-key="${key}"]`)!

describe('FundSwitches', () => {
  it('shows each switch with the first launch value, the value in force and how they stand', async () => {
    renderCard()
    await screen.findByTestId('fund-switches')
    await screen.findAllByTestId('fund-switch-proposal')
    const state = (key: string) => stateOfRow(key).getAttribute('data-state')
    // As the first launch wants it; a number is compared as a number (a rate of 0 is stored 0.0000), an empty list as empty.
    expect(state('qb.fee-credit.grant.enabled')).toBe('match')
    expect(state('qb.creator-fee-rate')).toBe('match')
    expect(state('qb.reward.default-methods')).toBe('match')
    // Not as it wants it.
    expect(state('qb.kudos.buy.enabled')).toBe('differs')
    expect(state('qb.topup.targets')).toBe('differs')
    // Not created yet: no value, and nothing proposed for it, or a proposal that waits.
    expect(state('qb.help.enabled')).toBe('missing')
    expect(state('qb.ai.enabled')).toBe('proposed')
    // The value in force differs and the proposal brings it to the first launch's.
    expect(state('qb.reward.kudos.enabled')).toBe('proposed')
    // An amount: the declared value stands, there is nothing to compare.
    expect(state('qb.fee-credit.grant.basic')).toBe('amount')

    const ai = within(stateOfRow('qb.ai.enabled') as HTMLElement)
    expect(ai.getByTestId('fund-switch-state')).toHaveTextContent('Waiting to be published')
    expect(ai.getByTestId('fund-switch-proposal')).toHaveTextContent('Proposed: true')
    expect(ai.getByTestId('user-name')).toHaveTextContent('proposer-1')
    expect(ai.getByTestId('fund-switch-value')).toHaveTextContent('not set')
    const kudos = within(stateOfRow('qb.reward.kudos.enabled') as HTMLElement)
    expect(kudos.getByTestId('fund-switch-expected')).toHaveTextContent('false')
    expect(kudos.getByTestId('fund-switch-value')).toHaveTextContent('true')
    expect(within(stateOfRow('qb.help.enabled') as HTMLElement).getByTestId('fund-switch-state'))
      .toHaveTextContent('Not created yet (waits for QB_SETUP and its publication)')
    expect(within(stateOfRow('qb.reward.default-methods') as HTMLElement).getByTestId('fund-switch-expected'))
      .toHaveTextContent('empty (none)')
    expect(within(stateOfRow('qb.fee-credit.grant.basic') as HTMLElement).getByTestId('fund-switch-expected'))
      .toHaveTextContent('amount: the declared value stands')
    // A proposal of a parameter that is no switch of the card is not listed.
    expect(screen.queryByText(/qb\.unrelated/)).not.toBeInTheDocument()

    // Three of the six with a value to compare match (the amount, the rows that wait and the manual ones are not counted).
    expect(screen.getByTestId('fund-switches-summary')).toHaveAttribute('data-match', '3')
    expect(screen.getByTestId('fund-switches-summary')).toHaveAttribute('data-total', '8')
  })

  it('lists the rows that wait for a feature and the ones set up by hand, with what they wait for', async () => {
    renderCard()
    await screen.findByTestId('fund-switches')
    for (const waiting of WAITING_ROWS) {
      const line = within(stateOfRow(waiting.key) as HTMLElement)
      expect(stateOfRow(waiting.key)).toHaveAttribute('data-state', 'waiting')
      expect(line.getByTestId('fund-switch-state')).toHaveTextContent(`Waits for ${waiting.waitingFor}`)
    }
    expect(WAITING_ROWS.map((r) => r.key)).toContain('qb.audience.billing.enabled')
    for (const manual of MANUAL_ROWS) {
      expect(stateOfRow(manual.key)).toHaveAttribute('data-state', 'manual')
      expect(within(stateOfRow(manual.key) as HTMLElement).getByTestId('fund-switch-state'))
        .toHaveTextContent('Proposed by maintenance staff through a controlled change')
    }
  })

  it('says all match when they do, and links to the controlled changes', async () => {
    server.switches = [row('qb.kudos.buy.enabled', 'kudos', 'false', 'false', true)]
    renderCard()
    expect(await screen.findByTestId('fund-switches-summary'))
      .toHaveTextContent('Every switch with a value to compare matches the first launch.')
    expect(screen.getByTestId('fund-switches-changes')).toHaveAttribute('href',
      `/data/${encodeURIComponent(CONTROL.changes)}`)
  })

  it('shows the values in force only, and says so, without the permission to read approvals', async () => {
    server.permissions = new Set(['qb.admin.finance.read'])
    renderCard()
    await screen.findByTestId('fund-switches')
    expect(server.post).not.toHaveBeenCalled()
    expect(screen.getByTestId('fund-switches-no-proposals')).toBeInTheDocument()
    expect(screen.queryByTestId('fund-switch-proposal')).not.toBeInTheDocument()
    // Not created yet and not known to be proposed: it reads as missing.
    expect(stateOfRow('qb.ai.enabled')).toHaveAttribute('data-state', 'missing')
    expect(stateOfRow('qb.kudos.buy.enabled')).toHaveAttribute('data-state', 'differs')
    expect(screen.queryByTestId('fund-switches-changes')).not.toBeInTheDocument()
  })

  it('reads the open proposals of the platform parameters only', async () => {
    renderCard()
    await screen.findAllByTestId('fund-switch-proposal')
    expect(server.post).toHaveBeenCalledTimes(1)
    const [path, options] = server.post.mock.calls[0]
    expect(path).toBe('/api/datasets/{resourceId}/query')
    expect(options.params.path.resourceId).toBe(CONTROL.changes)
    expect(options.body.filters).toEqual([{ field: 'targetEntity', op: 'eq', value: 'SysParam' },
      { field: 'status', op: 'eq', value: 'PROPOSED' }])
  })

  it('says why when the switches cannot be read', async () => {
    server.switches = new ApiError(403, { detail: 'Forbidden' })
    renderCard()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByTestId('fund-switches')).not.toBeInTheDocument()
  })

  it('is accessible in the light and the dark appearance', async () => {
    const { container } = renderCard()
    await screen.findByTestId('fund-switches')
    await screen.findAllByTestId('fund-switch-proposal')
    await expectAccessible(container)
  })

  it.each(['zh', 'ja'] as const)('is written in %s, with no key left over', async (language) => {
    await i18next.changeLanguage(language)
    renderCard()
    await screen.findByTestId('fund-switches')
    const text = screen.getByTestId('finance-fund-switches').textContent ?? ''
    // A missing text would show as its key.
    expect(text).not.toMatch(/fundSwitch\./)
    expect(text).not.toContain('names.')
    for (const key of [...WAITING_ROWS, ...MANUAL_ROWS].map((r) => r.key)) {
      expect(i18next.exists(`fundSwitch.names.${nameId(key)}`, { ns: EXTENSION_NAMESPACE }), key).toBe(true)
    }
  })
})

describe('first launch values', () => {
  it('compares numbers as numbers, switches without case, and nothing with an empty text', () => {
    expect(sameValue('0', '0.0000')).toBe(true)
    expect(sameValue('0', '0.1')).toBe(false)
    expect(sameValue('false', 'False')).toBe(true)
    expect(sameValue('', null)).toBe(true)
    expect(sameValue('', 'PLATFORM')).toBe(false)
    expect(sameValue('CASH', 'KUDOS,CASH')).toBe(false)
  })

  it('stands as missing, proposed, differs, match or amount', () => {
    const base = { key: 'k', group: 'g', expected: 'true', value: 'false', exists: true }
    const proposal = { changeId: 'c', key: 'k', value: 'true', proposedBy: 'u', reason: '' }
    expect(stateOf({ ...base, exists: false, value: null }, [])).toBe('missing')
    expect(stateOf({ ...base, exists: false, value: null }, [proposal])).toBe('proposed')
    expect(stateOf(base, [])).toBe('differs')
    expect(stateOf(base, [{ ...proposal, value: 'false' }])).toBe('differs')
    expect(stateOf(base, [proposal])).toBe('proposed')
    expect(stateOf({ ...base, value: 'true' }, [])).toBe('match')
    expect(stateOf({ ...base, expected: null }, [])).toBe('amount')
  })
})
