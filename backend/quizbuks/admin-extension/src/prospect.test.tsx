import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AppearanceProvider, EXTENSION_NAMESPACE, Toaster } from '@jabiz/admin'
import { expectAccessible } from '@jabiz/admin/testing'
import ProspectPage from './ProspectPage'
import ProspectSettingsPage from './ProspectSettingsPage'
import ProspectsPage from './ProspectsPage'
import { PERMISSION, PROSPECT_PROCESS, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the prospecting pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  prospects: [] as Record<string, unknown>[],
  setting: {} as Record<string, unknown>,
  directions: [] as Record<string, unknown>[],
  runQuery: vi.fn(),
  runProcess: vi.fn(),
  get: vi.fn(),
}))

vi.mock('@jabiz/admin', async (original) => {
  const actual = await original<typeof import('@jabiz/admin')>()
  return {
    ...actual,
    api: { GET: server.get },
    unwrap: async (call: Promise<unknown>) => call,
    runQuery: server.runQuery,
    runProcess: server.runProcess,
    useAuth: () => ({ can: (p: string) => server.permissions.has(p) }),
    UserName: ({ id }: { id: string | null }) => <span data-testid="user-name">{id}</span>,
  }
})

const PROSPECT = '0192f0a0-0000-7000-8000-00000000e001'

const trial = () => ({
  title: 'Know your sencha',
  intro: 'Five questions.',
  questions: [1, 2, 3, 4, 5].map((n) => ({ stem: `Question ${n}`, options: [
    { text: `Right ${n}`, correct: true }, { text: 'Wrong a', correct: false }, { text: 'Wrong b', correct: false },
    { text: 'Wrong c', correct: false }] })),
})

const prospect = (change: Row = {}): Row => ({ prospectId: PROSPECT, owner: 'op-1', domain: 'greenleaf.example',
  websiteUrl: 'https://greenleaf.example/', company: 'Green Leaf Tea', status: 'DRAFTED', foundAt: '2026-11-02T01:00:00Z',
  language: 'ja', country: 'JP', sellsOnline: true, shopify: true, publicEmail: 'info@greenleaf.example',
  refusesMarketing: false, score: 85, scoreReasons: JSON.stringify(['Teas with a story']),
  products: JSON.stringify([{ name: 'Sencha', description: 'A green tea' }]), trial: JSON.stringify(trial()),
  mailSubject: 'A quiz about your sencha', mailBody: 'Your sencha caught our eye.', revision: 4, ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.prospectUse, PERMISSION.operatorUse])
  server.prospects = [prospect()]
  server.setting = { owner: 'op-1', enabled: true, dailyCap: 10, minScore: 70, monthlyCostCap: '50.00',
    senderName: 'Kansai Partners', senderAddress: 'Osaka', senderContact: 'ops@kansai.example', revision: 2,
    serverHost: 'smtp.kansai.example', serverPort: 465, serverTls: 'SMTPS', serverUsername: 'ops',
    fromAddress: 'ops@kansai.example', fromName: 'Kansai', verifiedAt: '2026-11-01T01:00:00Z', lastError: null }
  server.directions = [{ directionId: 'd1', name: 'Tea shops', brief: 'Independent tea shops', country: 'JP',
    language: 'ja', active: true, perSearch: 5, lastSearchAt: null, revision: 1, found: 3, sent: 1 }]
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.operatorProspects || id === TEMPLATE.adminProspects ? server.prospects
      : id === TEMPLATE.operatorProspectSetting || id === TEMPLATE.adminProspectSetting ? [server.setting]
        : id === TEMPLATE.operatorProspectDirections || id === TEMPLATE.adminProspectDirections ? server.directions
          : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockImplementation(async (name: string) =>
    (name === PROSPECT_PROCESS.mailServerSet ? { verified: true, error: null, sentTo: 'me@kansai.example' }
      : { prospectId: PROSPECT, status: 'APPROVED', revision: 3 }))
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'DRAFTED', label: 'To review' }]
    throw new ApiError(404, { detail: 'Not found' })
  })
})

function renderAt(path: string, route: string, element: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AppearanceProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={route} element={element} />
            <Route path="*" element={<p data-testid="elsewhere">elsewhere</p>} />
          </Routes>
        </MemoryRouter>
        <Toaster />
      </AppearanceProvider>
    </QueryClientProvider>,
  )
}

describe('the businesses found', () => {
  it('lists the drafts first, from the operator’s own template', async () => {
    const { container } = renderAt('/qb/prospects', '/qb/prospects', <ProspectsPage />)
    const row = await screen.findByTestId('prospect-row')
    expect(within(row).getByTestId('prospect-link')).toHaveAttribute('href', `/qb/prospects/${PROSPECT}`)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorProspects, expect.objectContaining({
      params: { status: 'DRAFTED', q: undefined } }))
    expect(screen.queryByTestId('prospects-owner')).not.toBeInTheDocument()
    await expectAccessible(container)
  })

  it('reads headquarters’ own, or an owner’s, through the administration’s template', async () => {
    server.permissions = new Set([PERMISSION.prospectUse, PERMISSION.adminProspect])
    renderAt('/qb/prospects?owner=op-1', '/qb/prospects', <ProspectsPage />)
    await screen.findByTestId('prospect-row')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.adminProspects, expect.objectContaining({
      params: { owner: 'op-1', status: 'DRAFTED', q: undefined } }))
    expect(screen.getByTestId('prospects-owner')).toHaveValue('op-1')
  })
})

describe('a draft', () => {
  it('shows the research, and approves the mail and the quiz as edited, with the revision read', async () => {
    const { container } = renderAt(`/qb/prospects/${PROSPECT}`, '/qb/prospects/:prospectId', <ProspectPage />)
    expect(await screen.findByTestId('prospect-research')).toHaveTextContent('info@greenleaf.example')
    expect(screen.getByTestId('prospect-research')).toHaveTextContent('Teas with a story')
    expect(screen.getAllByTestId('trial-question')).toHaveLength(5)
    await expectAccessible(container)
    const user = userEvent.setup()
    const subject = screen.getByTestId('draft-subject')
    await user.clear(subject)
    await user.type(subject, ' Our quiz ')
    const second = screen.getAllByTestId('trial-question')[0]
    await user.click(within(second).getAllByRole('radio')[1])
    await user.click(screen.getByTestId('draft-approve'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROSPECT_PROCESS.approve, expect.objectContaining({
      prospectId: PROSPECT, mailSubject: 'Our quiz', mailBody: 'Your sencha caught our eye.', baseRevision: 4 })))
    const sent = server.runProcess.mock.calls[0][1] as { trial: { questions: { options: { correct: boolean }[] }[] } }
    expect(sent.trial.questions[0].options.map((o) => o.correct)).toEqual([false, true, false, false])
  })

  it('discards with a block', async () => {
    renderAt(`/qb/prospects/${PROSPECT}`, '/qb/prospects/:prospectId', <ProspectPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('draft-discard'))
    const dialog = await screen.findByTestId('discard-dialog')
    await user.click(within(dialog).getByTestId('discard-block'))
    await user.click(within(dialog).getByTestId('discard-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROSPECT_PROCESS.discard,
      { prospectId: PROSPECT, block: true, note: null }))
  })

  it('offers nothing to decide once sent, nor on another owner’s for headquarters', async () => {
    server.prospects = [prospect({ status: 'SENT', sentAt: '2026-11-03T01:00:00Z' })]
    renderAt(`/qb/prospects/${PROSPECT}`, '/qb/prospects/:prospectId', <ProspectPage />)
    expect(await screen.findByTestId('prospect-read-only')).toBeInTheDocument()
    expect(screen.queryByTestId('draft-approve')).not.toBeInTheDocument()
  })

  it('shows headquarters another owner’s draft without the buttons', async () => {
    server.permissions = new Set([PERMISSION.prospectUse, PERMISSION.adminProspect])
    renderAt(`/qb/prospects/${PROSPECT}?owner=op-1`, '/qb/prospects/:prospectId', <ProspectPage />)
    await screen.findByTestId('prospect-draft')
    expect(screen.queryByTestId('draft-approve')).not.toBeInTheDocument()
  })
})

describe('the settings', () => {
  it('saves the settings with the revision read, tests the mail server and adds a direction', async () => {
    const { container } = renderAt('/qb/prospects/settings', '/qb/prospects/settings', <ProspectSettingsPage />)
    expect(await screen.findByTestId('server-state')).toHaveTextContent('Verified')
    expect(await screen.findByTestId('direction-row')).toHaveTextContent('Tea shops')
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('setting-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROSPECT_PROCESS.settingsSave,
      expect.objectContaining({ enabled: true, dailyCap: 10, monthlyCostCap: '50.00', baseRevision: 2 })))
    await user.click(screen.getByTestId('server-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROSPECT_PROCESS.mailServerSet,
      expect.objectContaining({ host: 'smtp.kansai.example', port: 465, tls: 'SMTPS', password: null })))
    await user.click(screen.getByTestId('direction-add'))
    await user.type(screen.getByTestId('direction-name'), 'Sake breweries')
    await user.type(screen.getByTestId('direction-brief'), 'Small breweries with a shop')
    await user.click(screen.getByTestId('direction-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(PROSPECT_PROCESS.directionSave,
      expect.objectContaining({ name: 'Sake breweries', brief: 'Small breweries with a shop', perSearch: 5,
        active: true })))
  })
})
