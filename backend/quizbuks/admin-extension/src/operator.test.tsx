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
import OperatorHomePage from './OperatorHomePage'
import OperatorPage from './OperatorPage'
import OperatorReviewsPage from './OperatorReviewsPage'
import OperatorsPage from './OperatorsPage'
import OperatorSponsorsPage from './OperatorSponsorsPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import { OPERATOR_PROCESS, PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the operator pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  operators: [] as Record<string, unknown>[],
  members: [] as Record<string, unknown>[],
  reviews: [] as Record<string, unknown>[],
  sponsors: [] as Record<string, unknown>[],
  team: {} as Record<string, unknown>,
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
    ApprovalPanel: ({ requestId }: { requestId: string }) => (
      <div data-testid="approval-panel" data-request={requestId}>decide</div>
    ),
  }
})

const OPERATOR = '0192f0a0-0000-7000-8000-00000000a001'
const MEMBER = '0192f0a0-0000-7000-8000-00000000b001'
const LEAD = '0192f0a0-0000-7000-8000-00000000b002'
const SPONSOR = '0192f0a0-0000-7000-8000-00000000c001'
const DECISION = '0192f0a0-0000-7000-8000-00000000d001'

const operator = (change: Row = {}): Row => ({ operatorId: OPERATOR, name: 'Kansai Partners', contactName: 'Ken',
  contactEmail: 'ops@kansai.example', collection: 'PLATFORM', status: 'ACTIVE', note: null,
  createdAt: '2026-10-20T01:00:00Z', revision: 3, members: 2, sponsors: 1, ...change })

const sponsor = (change: Row = {}): Row => ({ onboardingId: 'o1', userId: SPONSOR, company: 'Acme', contactName: 'Alice',
  email: 'alice@acme.example', reviewStatus: 'APPROVED', mine: true, banned: false, bannedByOperator: false,
  operatorId: OPERATOR, operatorName: 'Kansai Partners', runningPublications: 2, ...change })

beforeAll(async () => {
  await i18next.use(initReactI18next).init({ lng: 'en', resources: { en: { [EXTENSION_NAMESPACE]: messages.en } } })
})

beforeEach(() => {
  server.permissions = new Set([PERMISSION.operatorsRead, PERMISSION.operatorsWrite, PERMISSION.spotCheck,
    PERMISSION.usersRead])
  server.operators = [operator()]
  server.members = [
    { memberId: 'm1', userId: LEAD, displayName: 'Lena Lead', email: 'lena@kansai.example', lead: true,
      addedAt: '2026-10-21T01:00:00Z', removedAt: null, hasRole: true },
    { memberId: 'm2', userId: MEMBER, displayName: 'Mo Member', email: 'mo@kansai.example', lead: false,
      addedAt: '2026-10-22T01:00:00Z', removedAt: null, hasRole: false },
  ]
  server.reviews = [{ decisionId: DECISION, decidedAt: '2026-10-25T01:00:00Z', decision: 'APPROVE', reason: null,
    approverId: MEMBER, subject: 'qb.sponsor', entityId: 'o1', sponsorId: SPONSOR, title: 'Acme',
    operatorId: OPERATOR, operatorName: 'Kansai Partners', checkResult: null }]
  server.sponsors = [sponsor(), sponsor({ onboardingId: 'o2', userId: 'other', company: 'Beta', mine: false,
    operatorId: null, operatorName: null, banned: true, bannedByOperator: false })]
  server.team = { operatorId: OPERATOR, name: 'Kansai Partners', status: 'ACTIVE', collection: 'PLATFORM', lead: true,
    members: [
      { userId: LEAD, displayName: 'Lena Lead', email: 'lena@kansai.example', lead: true, addedAt: null, self: true },
      { userId: MEMBER, displayName: 'Mo Member', email: 'mo@kansai.example', lead: false, addedAt: null, self: false },
    ] }
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.operators ? server.operators
      : id === TEMPLATE.operatorMembers ? server.members
        : id === TEMPLATE.operatorReviews ? server.reviews
          : id === TEMPLATE.operatorSponsors ? server.sponsors
            : id === TEMPLATE.operatorOverview ? [{ mySponsors: 1, sponsors: 9, sponsorsInReview: 2,
              publicationsInReview: 3, livePublications: 4, myLivePublications: 1, myCompletions: 120 }]
              : id === TEMPLATE.sponsors ? [{ userId: SPONSOR, company: 'Acme', reviewStatus: 'APPROVED' }] : []
    return { items, offset: 0, limit: 20, ...(options.count ? { total: items.length } : {}) }
  })
  server.runProcess.mockReset().mockImplementation(async (name: string) =>
    (name === OPERATOR_PROCESS.team ? server.team : { operatorId: OPERATOR, changed: true }))
  server.get.mockReset().mockImplementation(async (path: string) => {
    if (path === '/api/dictionaries/{urn}') return [{ code: 'ACTIVE', label: 'Active' }]
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

describe('headquarters', () => {
  it('lists the operators and creates one', async () => {
    const { container } = renderAt('/qb/operators', '/qb/operators', <OperatorsPage />)
    const row = await screen.findByTestId('operator-row')
    expect(within(row).getByTestId('operator-row-link')).toHaveAttribute('href', `/qb/operators/${OPERATOR}`)
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(screen.getByTestId('operator-new'))
    const dialog = await screen.findByTestId('operator-new-dialog')
    expect(within(dialog).getByTestId('operator-create')).toBeDisabled()
    await user.type(within(dialog).getByTestId('operator-name'), ' Kyushu ')
    await user.type(within(dialog).getByTestId('operator-contact'), 'Kei')
    await user.type(within(dialog).getByTestId('operator-email'), 'ops@kyushu.example')
    // Q59c: the language whose new sponsors it takes; none until chosen.
    expect(within(dialog).getByTestId('operator-language')).toHaveTextContent('None')
    await user.click(within(dialog).getByTestId('operator-language'))
    await user.click(await screen.findByRole('option', { name: 'zh' }))
    await user.click(within(dialog).getByTestId('operator-create'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.save, {
      name: 'Kyushu', contactName: 'Kei', contactEmail: 'ops@kyushu.example', language: 'zh', note: null }))
  })

  it('does not offer creating one without the permission', async () => {
    server.permissions = new Set([PERMISSION.operatorsRead])
    renderAt('/qb/operators', '/qb/operators', <OperatorsPage />)
    await screen.findByTestId('operator-row')
    expect(screen.queryByTestId('operator-new')).not.toBeInTheDocument()
  })

  it('shows the members, who lacks the role, and names and removes them', async () => {
    const { container } = renderAt(`/qb/operators/${OPERATOR}`, '/qb/operators/:operatorId', <OperatorPage />)
    const rows = await screen.findAllByTestId('member-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[1]).getByTestId('member-no-role')).toBeInTheDocument()
    expect(within(rows[0]).queryByTestId('member-no-role')).not.toBeInTheDocument()
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.type(screen.getByTestId('member-user'), ` ${MEMBER} `)
    await user.click(screen.getByTestId('member-lead'))
    await user.click(screen.getByTestId('member-add'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.memberAdd,
      { operatorId: OPERATOR, userId: MEMBER, lead: true }))
    await user.click(within(rows[1]).getByTestId('member-remove'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.memberRemove,
      { userId: MEMBER }))
  })

  it('saves the operator with the revision it read, and moves sponsors with a reason', async () => {
    renderAt(`/qb/operators/${OPERATOR}`, '/qb/operators/:operatorId', <OperatorPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('operator-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.save, expect.objectContaining({
      operatorId: OPERATOR, baseRevision: 3, status: 'ACTIVE' })))
    expect(await screen.findByTestId('operator-sponsor-row')).toHaveTextContent('Acme')
    expect(screen.getByTestId('assign-here')).toBeDisabled()
    await user.type(screen.getByTestId('assign-user'), SPONSOR)
    await user.type(screen.getByTestId('assign-reason'), 'Signed with both')
    await user.click(screen.getByTestId('assign-back'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.assign,
      { userId: SPONSOR, operatorId: null, reason: 'Signed with both' }))
  })

  it('spot-checks a review: a problem needs a note', async () => {
    const { container } = renderAt('/qb/operator-reviews', '/qb/operator-reviews', <OperatorReviewsPage />)
    const row = await screen.findByTestId('review-row')
    expect(within(row).getByTestId('review-about')).toHaveAttribute('href', `/qb/reviews/sponsors/${SPONSOR}`)
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(row).getByTestId('review-problem'))
    const dialog = await screen.findByTestId('spot-check-dialog')
    expect(within(dialog).getByTestId('spot-check-submit')).toBeDisabled()
    await user.type(within(dialog).getByTestId('spot-check-note'), 'Company not verified')
    await user.click(within(dialog).getByTestId('spot-check-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.spotCheck,
      { decisionId: DECISION, result: 'PROBLEM', note: 'Company not verified' }))
    // Only the unchecked by default; the box shows all.
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorReviews, expect.objectContaining({
      params: { unchecked: true } }))
  })
})

describe('the operator', () => {
  beforeEach(() => {
    server.permissions = new Set([PERMISSION.operatorUse, PERMISSION.reviewSponsor, PERMISSION.reviewPublication])
  })

  it('reads its totals and team; the lead removes a member', async () => {
    const { container } = renderAt('/qb/operator', '/qb/operator', <OperatorHomePage />)
    expect(await screen.findByTestId('figure-myCompletions')).toHaveTextContent('120')
    const rows = await screen.findAllByTestId('team-row')
    expect(within(rows[0]).queryByTestId('member-drop')).not.toBeInTheDocument()
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(rows[1]).getByTestId('member-drop'))
    await user.click(await screen.findByTestId('member-drop-confirm'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.memberDrop,
      { userId: MEMBER }))
  })

  it('offers no removal to a member who does not lead', async () => {
    server.team = { ...server.team, lead: false }
    renderAt('/qb/operator', '/qb/operator', <OperatorHomePage />)
    await screen.findAllByTestId('team-row')
    expect(screen.queryByTestId('member-drop')).not.toBeInTheDocument()
  })

  it('bans a sponsor with a reason, and lifts only its own bans', async () => {
    const { container } = renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    const rows = await screen.findAllByTestId('sponsor-row')
    // Headquarters' ban: no lift offered.
    expect(within(rows[1]).queryByTestId('sponsor-unban')).not.toBeInTheDocument()
    expect(within(rows[1]).getByText('By headquarters')).toBeInTheDocument()
    expect(within(rows[0]).getByTestId('sponsor-link')).toHaveAttribute('href', `/qb/reviews/sponsors/${SPONSOR}`)
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(rows[0]).getByTestId('sponsor-ban'))
    const dialog = await screen.findByTestId('ban-dialog')
    expect(within(dialog).getByTestId('ban-submit')).toBeDisabled()
    await user.type(within(dialog).getByTestId('ban-text'), 'Fake prizes')
    await user.click(within(dialog).getByTestId('ban-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.ban,
      { userId: SPONSOR, reason: 'Fake prizes' }))
  })

  it('lifts a ban its operator made', async () => {
    server.sponsors = [sponsor({ banned: true, bannedByOperator: true })]
    renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('sponsor-unban'))
    await user.click(within(await screen.findByTestId('ban-dialog')).getByTestId('ban-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.unban,
      { userId: SPONSOR, note: null }))
  })

  it('reviews applications through its own template, without the user summary', async () => {
    server.sponsors = [sponsor({ reviewStatus: 'PENDING', approvalRequestId: 'r1', submittedAt: '2026-10-26T01:00:00Z',
      termsAcceptedAt: '2026-10-26T00:59:00Z', emailVerified: true, avatar: null, intro: 'We roast coffee.' })]
    renderAt('/qb/reviews?tab=sponsors', '/qb/reviews', <ReviewsPage />)
    expect(await screen.findByTestId('queue-sponsor-link')).toHaveAttribute('href', `/qb/reviews/sponsors/${SPONSOR}`)
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorSponsors, expect.anything())
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.sponsors, expect.anything())
  })

  it('opens an application with its decision and no link to the user summary', async () => {
    server.sponsors = [sponsor({ reviewStatus: 'PENDING', approvalRequestId: 'r1', emailVerified: true })]
    renderAt(`/qb/reviews/sponsors/${SPONSOR}`, '/qb/reviews/sponsors/:userId', <SponsorReviewPage />)
    expect(await screen.findByTestId('approval-panel')).toHaveAttribute('data-request', 'r1')
    expect(screen.getByTestId('sponsor-email')).toHaveTextContent('alice@acme.example')
    expect(screen.queryByTestId('sponsor-summary')).not.toBeInTheDocument()
  })
})
