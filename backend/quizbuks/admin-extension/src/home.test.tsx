import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppearanceProvider, EXTENSION_NAMESPACE } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import HomePage from './HomePage'
import { PATH, PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>
type Options = { params?: Row; offset?: number; limit?: number; count?: boolean; sorts?: { field: string; asc: boolean }[] }

/** The server as the home page sees it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  entry: 'admin',
  permissions: new Set<string>(),
  health: [] as Record<string, unknown>[],
  workload: [] as Record<string, unknown>[],
  runQuery: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    useAuth: () => ({ entry: server.entry, can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

vi.mock('./OperatorHomePage', () => ({ default: () => <p data-testid="operator-home">operator</p> }))

const NORTH = '0192f0a0-0000-7000-8000-00000000d001'
const SOUTH = '0192f0a0-0000-7000-8000-00000000d002'
const ORPHAN = '0192f0a0-0000-7000-8000-00000000d003'

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.entry = 'admin'
  server.permissions = new Set([PERMISSION.operatorsRead])
  server.health = []
  server.workload = [
    { operatorId: NORTH, name: 'North', status: 'ACTIVE', defaultForNew: true, pending: 4, decided: 9 },
    { operatorId: SOUTH, name: 'South', status: 'ACTIVE', defaultForNew: false, pending: 0, decided: 2 },
  ]
  server.get.mockReset().mockResolvedValue([{ code: 'ACTIVE', label: 'Active' }])
  server.runQuery.mockReset().mockImplementation(async (id: string, options: Options) => {
    if (id === TEMPLATE.operatorHealth) return { items: server.health, offset: 0, limit: 200 }
    if (id === TEMPLATE.operatorWorkload) {
      return { items: server.workload, offset: options.offset ?? 0, limit: options.limit ?? 20,
        total: server.workload.length }
    }
    return { items: [], offset: 0, limit: 1 }
  })
})

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname}</p>
}

function renderHome() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[PATH.home]}>
          <Routes>
            <Route path={PATH.home} element={<HomePage />} />
            <Route path="*" element={<Where />} />
          </Routes>
        </MemoryRouter>
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

const workloadCalls = () => server.runQuery.mock.calls.filter(([id]) => id === TEMPLATE.operatorWorkload)
  .map(([, options]) => options as Options)

describe('the platform’s home', () => {
  it('asks for a default operator first, and leads to the operators', async () => {
    server.health = [{ checkCode: 'NO_DEFAULT_OPERATOR', found: 0 },
      { checkCode: 'NO_OPERATOR', sponsorId: ORPHAN, found: 0 }]
    const { container } = renderHome()
    const alert = await screen.findByTestId('home-no-default')
    expect(alert).toHaveTextContent('Create the default operator first')
    const problems = await screen.findAllByTestId('home-problem')
    // The default operator is the alert, not one more line.
    expect(problems).toHaveLength(1)
    expect(problems[0]).toHaveAttribute('data-code', 'NO_OPERATOR')
    expect(within(problems[0]).getByTestId('user-name')).toHaveTextContent(ORPHAN)
    await expectAccessible(container)
    await userEvent.setup().click(within(alert).getByRole('link', { name: 'Go to the operators' }))
    expect(await screen.findByTestId('where')).toHaveTextContent(PATH.operators)
  })

  it('names the operator at fault, with how many', async () => {
    server.health = [{ checkCode: 'FEW_REVIEWERS', operatorId: SOUTH, operatorName: 'South', found: 1 }]
    renderHome()
    const [problem] = await screen.findAllByTestId('home-problem')
    expect(problem).toHaveTextContent('An operator with only 1 member who can review:')
    expect(within(problem).getByRole('link', { name: 'South' })).toHaveAttribute('href', PATH.operator(SOUTH))
    expect(screen.queryByTestId('home-no-default')).not.toBeInTheDocument()
  })

  it('says when all is well, and lists each operator’s reviews from 30 days back', async () => {
    const { container } = renderHome()
    expect(await screen.findByTestId('home-all-well')).toBeInTheDocument()
    const rows = await screen.findAllByTestId('workload-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('workload-pending')).toHaveTextContent('4')
    expect(within(rows[0]).getByTestId('workload-decided')).toHaveTextContent('9')
    expect(rows[0]).toHaveTextContent('Default')
    expect(within(rows[1]).getByTestId('workload-operator')).toHaveAttribute('href', PATH.operator(SOUTH))
    const since = Date.parse(String(workloadCalls()[0].params?.since))
    expect(Date.now() - since).toBeGreaterThanOrEqual(30 * 24 * 3_600_000)
    expect(Date.now() - since).toBeLessThan(30 * 24 * 3_600_000 + 60_000)
    expect(workloadCalls()[0]).toMatchObject({ count: true })
    await expectAccessible(container)
  })

  it('sorts by the waiting reviews on the server, the most first', async () => {
    renderHome()
    await screen.findAllByTestId('workload-row')
    await userEvent.setup().click(screen.getByRole('button', { name: /Waiting/ }))
    await waitFor(() => expect(workloadCalls().at(-1)?.sorts).toEqual([{ field: 'pending', asc: false }]))
  })

  it('points who does not read the operators to the menu', async () => {
    server.permissions = new Set()
    renderHome()
    expect(await screen.findByTestId('home-menu-hint')).toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalled()
  })

  it('is the operator’s own home in the Operator Management System', async () => {
    server.entry = 'operator'
    renderHome()
    expect(await screen.findByTestId('operator-home')).toBeInTheDocument()
    expect(server.runQuery).not.toHaveBeenCalled()
  })
})
