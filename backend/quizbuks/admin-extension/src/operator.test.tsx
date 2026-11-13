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
import OperatorBillingPage from './OperatorBillingPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import { FEE_PROCESS, OPERATOR_PROCESS, PERMISSION, TEMPLATE } from './ids'
import { messages } from './messages'

type Row = Record<string, unknown>

/** The server as the operator pages see it through `@jabiz/admin`. */
const server = vi.hoisted(() => ({
  permissions: new Set<string>(),
  entry: 'admin',
  operators: [] as Record<string, unknown>[],
  members: [] as Record<string, unknown>[],
  reviews: [] as Record<string, unknown>[],
  sponsors: [] as Record<string, unknown>[],
  billing: [] as Record<string, unknown>[],
  creditNotes: [] as Record<string, unknown>[],
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
    useAuth: () => ({ entry: server.entry, can: (p: string) => server.permissions.has(p) }),
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
  server.entry = 'admin'
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
    ownSponsor: true,
    members: [
      { userId: LEAD, displayName: 'Lena Lead', email: 'lena@kansai.example', lead: true, addedAt: null, self: true },
      { userId: MEMBER, displayName: 'Mo Member', email: 'mo@kansai.example', lead: false, addedAt: null, self: false },
    ] }
  server.runQuery.mockReset().mockImplementation(async (id: string, options: { count?: boolean } = {}) => {
    const items = id === TEMPLATE.operators ? server.operators
      : id === TEMPLATE.operatorMembers ? server.members
        : id === TEMPLATE.operatorReviews ? server.reviews
          : id === TEMPLATE.operatorSponsors ? server.sponsors
            : id === TEMPLATE.operatorBilling ? server.billing
            : id === TEMPLATE.operatorFeeCreditNotes ? server.creditNotes
              : id === TEMPLATE.operatorOverview ? [{ mySponsors: 1, sponsors: 9, sponsorsInReview: 2,
                publicationsInReview: 3, livePublications: 4, myLivePublications: 1, myCompletions: 120 }]
                : id === TEMPLATE.sponsors ? [{ userId: SPONSOR, company: 'Acme', reviewStatus: 'APPROVED' }]
                  : id === TEMPLATE.users ? [{ userId: LEAD, userName: 'lena', email: 'lena@kansai.example',
                    emailVerified: true, displayName: 'Lena Lead' }] : []
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
    // F-S1: its lead, chosen by name, is required; there is a default operator already, so it may become it.
    expect(within(dialog).getByTestId('operator-create')).toBeDisabled()
    await user.click(within(dialog).getByTestId('operator-lead'))
    await user.type(await screen.findByPlaceholderText('Name, address or company'), 'lena')
    await user.click(await screen.findByRole('option', { name: /Lena Lead/ }))
    expect(within(dialog).getByTestId('operator-default-new')).toBeInTheDocument()
    await user.click(within(dialog).getByTestId('operator-create'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.save, {
      name: 'Kyushu', contactName: 'Kei', contactEmail: 'ops@kyushu.example', language: 'zh', note: null,
      leadUserId: LEAD, defaultForNew: false }))
  })

  it('makes the first operator the default one, and marks the default in the list (F-S1)', async () => {
    server.operators = []
    renderAt('/qb/operators', '/qb/operators', <OperatorsPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('operator-new'))
    const dialog = await screen.findByTestId('operator-new-dialog')
    expect(await within(dialog).findByTestId('operator-first-default')).toBeInTheDocument()
    expect(within(dialog).queryByTestId('operator-default-new')).not.toBeInTheDocument()
  })

  it('shows the default operator, and makes another one the default after confirming (F-S1)', async () => {
    server.operators = [operator({ defaultForNew: true })]
    const { unmount } = renderAt('/qb/operators', '/qb/operators', <OperatorsPage />)
    expect(await screen.findByTestId('operator-row-default')).toBeInTheDocument()
    unmount()
    server.operators = [operator({ defaultForNew: false })]
    renderAt(`/qb/operators/${OPERATOR}`, '/qb/operators/:operatorId', <OperatorPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('operator-make-default'))
    const confirm = await screen.findByRole('alertdialog')
    expect(server.runProcess).not.toHaveBeenCalledWith(OPERATOR_PROCESS.save, expect.anything())
    await user.click(within(confirm).getByRole('button', { name: 'Make it the default operator' }))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.save, expect.objectContaining({
      operatorId: OPERATOR, baseRevision: 3, defaultForNew: true })))
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

  it('saves the operator with the revision it read, and moves Sponsors with a reason', async () => {
    renderAt(`/qb/operators/${OPERATOR}`, '/qb/operators/:operatorId', <OperatorPage />)
    const user = userEvent.setup()
    await user.click(await screen.findByTestId('operator-save'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.save, expect.objectContaining({
      operatorId: OPERATOR, baseRevision: 3, status: 'ACTIVE' })))
    expect(await screen.findByTestId('operator-sponsor-row')).toHaveTextContent('Acme')
    expect(screen.getByTestId('assign-here')).toBeDisabled()
    // F-S1: there is no "back to headquarters"; a Sponsor moves here from another operator, chosen by name.
    expect(screen.queryByTestId('assign-back')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('assign-user'))
    await user.type(screen.getByPlaceholderText('Name, address or company'), 'lena')
    await user.click(await screen.findByRole('option', { name: /Lena Lead/ }))
    await user.type(screen.getByTestId('assign-reason'), 'Signed with both')
    await user.click(screen.getByTestId('assign-here'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(OPERATOR_PROCESS.assign,
      { userId: LEAD, operatorId: OPERATOR, reason: 'Signed with both' }))
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
    server.entry = 'operator'
    server.permissions = new Set([PERMISSION.operatorUse, PERMISSION.reviewSponsor, PERMISSION.reviewPublication,
      PERMISSION.feeBilling])
  })

  it('reads its totals and team; the lead removes a member', async () => {
    const { container } = renderAt('/qb/operator', '/qb/operator', <OperatorHomePage />)
    expect(await screen.findByTestId('figure-myCompletions')).toHaveTextContent('120')
    const rows = await screen.findAllByTestId('team-row')
    // F-S1: the lead's account is the operator's own Sponsor, who joins /sponsor/ to publish.
    expect(screen.getByTestId('operator-own-sponsor')).toHaveTextContent('/sponsor/')
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

  it('bans a Sponsor with a reason, and lifts only its own bans', async () => {
    const { container } = renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    const rows = await screen.findAllByTestId('sponsor-row')
    // The platform's ban: no lift offered.
    expect(within(rows[1]).queryByTestId('sponsor-unban')).not.toBeInTheDocument()
    expect(within(rows[1]).getByText('By the platform')).toBeInTheDocument()
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

  it('shows no batch confirmation to a member who may not confirm the onboarding Kudos', async () => {
    renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    await screen.findAllByTestId('sponsor-row')
    expect(screen.queryByTestId('operator-sponsors-confirm-selected')).not.toBeInTheDocument()
    expect(screen.queryByTestId('sponsor-grant-select')).not.toBeInTheDocument()
  })

  it('confirms the ticked Sponsors one by one, one batch at a time, and leaves a failed one ticked (F-F11b)', async () => {
    server.permissions.add(PERMISSION.feeCreditOnboarding)
    const FIRST = '0192f0a0-0000-7000-8000-00000000c101'
    const SECOND = '0192f0a0-0000-7000-8000-00000000c102'
    server.sponsors = [
      sponsor({ onboardingId: 'a1', userId: FIRST, company: 'Alpha' }),
      sponsor({ onboardingId: 'a2', userId: SECOND, company: 'Beta' }),
      // Not offered: another operator's, one not approved yet, and a banned one.
      sponsor({ onboardingId: 'a3', userId: 'x3', company: 'Gamma', mine: false }),
      sponsor({ onboardingId: 'a4', userId: 'x4', company: 'Delta', reviewStatus: 'IN_REVIEW' }),
      sponsor({ onboardingId: 'a5', userId: 'x5', company: 'Epsilon', banned: true }),
    ]
    let release: () => void = () => undefined
    const gate = new Promise<void>((resolve) => { release = resolve })
    server.runProcess.mockImplementation(async (name: string, input: { sponsorId?: string }) => {
      if (name !== FEE_PROCESS.onboarding) return { operatorId: OPERATOR, changed: true }
      await gate
      if (input.sponsorId === SECOND) throw new ApiError(422, { detail: 'The grant is switched off.' })
      return { grantId: 'g1', granted: true, amount: 100, expiresAt: null }
    })
    const { container } = renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    const rows = await screen.findAllByTestId('sponsor-row')
    expect(screen.getAllByTestId('sponsor-grant-select')).toHaveLength(2)
    expect(within(rows[2]).queryByTestId('sponsor-grant-select')).not.toBeInTheDocument()
    expect(screen.getByTestId('operator-sponsors-confirm-selected')).toBeDisabled()
    await expectAccessible(container)

    const user = userEvent.setup()
    await user.click(within(rows[0]).getByTestId('sponsor-grant-select'))
    await user.click(within(rows[1]).getByTestId('sponsor-grant-select'))
    const button = screen.getByTestId('operator-sponsors-confirm-selected')
    expect(button).toHaveTextContent('2 selected')
    await user.click(button)
    // One batch at a time: while it runs nothing can be started or changed again.
    await waitFor(() => expect(screen.getByTestId('sponsors-grant-progress')).toBeInTheDocument())
    expect(button).toBeDisabled()
    screen.getAllByTestId('sponsor-grant-select').forEach((box) => expect(box).toBeDisabled())
    release()
    await waitFor(() => expect(screen.queryByTestId('sponsors-grant-progress')).not.toBeInTheDocument())
    expect(server.runProcess.mock.calls.filter(([name]) => name === FEE_PROCESS.onboarding)
      .map(([, input]) => (input as { sponsorId: string }).sponsorId)).toEqual([FIRST, SECOND])

    const [first, second] = screen.getAllByTestId('sponsor-row')
    expect(within(first).getByTestId('sponsor-grant-result')).toHaveAttribute('data-state', 'ok')
    // The granted one is done (no box); the failed one says why and stays ticked.
    expect(within(first).queryByTestId('sponsor-grant-select')).not.toBeInTheDocument()
    expect(within(second).getByTestId('sponsor-grant-result')).toHaveAttribute('data-state', 'error')
    expect(within(second).getByTestId('sponsor-grant-result')).toHaveTextContent('switched off')
    expect(within(second).getByTestId('sponsor-grant-select')).toBeChecked()
    expect(screen.getByTestId('operator-sponsors-confirm-selected')).toHaveTextContent('1 selected')
    await expectAccessible(container)
  })

  it('sets how its own Sponsor is billed, with a reason (Q87)', async () => {
    server.get.mockImplementation(async (path: string) => {
      if (path === '/api/dictionaries/{urn}') return [{ code: 'PREPAID', label: 'Prepaid' },
        { code: 'POSTPAID', label: 'Postpaid' }, { code: 'APPROVED', label: 'Approved' }]
      throw new ApiError(404, { detail: 'Not found' })
    })
    server.sponsors = [sponsor({ billingMode: 'PREPAID', creditLimit: 0 }),
      sponsor({ onboardingId: 'o2', userId: 'other', company: 'Beta', mine: false, billingMode: 'POSTPAID', creditLimit: 50000 })]
    const { container } = renderAt('/qb/operator/sponsors', '/qb/operator/sponsors', <OperatorSponsorsPage />)
    const rows = await screen.findAllByTestId('sponsor-row')
    expect(within(rows[0]).getByTestId('sponsor-billing')).toHaveAttribute('data-mode', 'PREPAID')
    expect(within(rows[1]).getByTestId('sponsor-billing')).toHaveTextContent('50,000')
    // Another operator's sponsor is not the caller's to bill.
    expect(within(rows[1]).queryByTestId('sponsor-billing-set')).not.toBeInTheDocument()
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(rows[0]).getByTestId('sponsor-billing-set'))
    const dialog = await screen.findByTestId('billing-dialog')
    expect(within(dialog).getByTestId('billing-submit')).toBeDisabled()
    expect(within(dialog).queryByTestId('billing-credit')).not.toBeInTheDocument()
    await user.click(within(dialog).getByTestId('billing-mode-POSTPAID'))
    await user.clear(within(dialog).getByTestId('billing-credit'))
    await user.type(within(dialog).getByTestId('billing-credit'), '20,000')
    await user.type(within(dialog).getByTestId('billing-reason'), 'Known company')
    await user.click(within(dialog).getByTestId('billing-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.billingSet,
      { userId: SPONSOR, billingMode: 'POSTPAID', creditLimit: 20000, reason: 'Known company' }))
    await waitFor(() => expect(screen.queryByTestId('billing-dialog')).not.toBeInTheDocument())
  })

  it('lists how its Sponsors are billed and sets one with a reason (Q87b)', async () => {
    server.get.mockImplementation(async (path: string) => {
      if (path === '/api/dictionaries/{urn}') return [{ code: 'PREPAID', label: 'Prepaid' },
        { code: 'POSTPAID', label: 'Postpaid' }]
      throw new ApiError(404, { detail: 'Not found' })
    })
    server.billing = [
      { userId: SPONSOR, company: 'Acme', contactName: 'Alice', reviewStatus: 'APPROVED', billingMode: 'POSTPAID',
        own: true, creditLimit: 50000, unpaidTotal: 1100, overdueCount: 1, uninvoicedTotal: 2200,
        billingReason: 'Known company', billingSetBy: LEAD, billingSetAt: '2026-10-30T01:00:00Z' },
      { userId: 'other', company: 'Beta', contactName: 'Bob', reviewStatus: 'APPROVED', billingMode: 'PREPAID',
        own: false, creditLimit: 0, unpaidTotal: 0, overdueCount: 0, uninvoicedTotal: 0, billingReason: null,
        billingSetBy: null, billingSetAt: null },
    ]
    const { container } = renderAt('/qb/operator/billing', '/qb/operator/billing', <OperatorBillingPage />)
    const rows = await screen.findAllByTestId('billing-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByTestId('billing-row-mode')).toHaveAttribute('data-mode', 'POSTPAID')
    expect(within(rows[0]).getByTestId('billing-row-mode')).toHaveTextContent('50,000')
    expect(within(rows[0]).getByTestId('billing-row-unpaid')).toHaveTextContent('1,100')
    expect(within(rows[0]).getByTestId('billing-row-unpaid')).toHaveTextContent('Overdue: 1')
    expect(within(rows[0]).getByTestId('billing-row-uninvoiced')).toHaveTextContent('2,200')
    expect(rows[0]).toHaveTextContent('Known company')
    expect(within(rows[0]).getByTestId('user-name')).toHaveTextContent(LEAD)
    expect(within(rows[1]).getByTestId('billing-row-mode')).toHaveTextContent('Platform default')
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorBilling, expect.objectContaining({
      params: { q: null } }))
    await expectAccessible(container)
    const user = userEvent.setup()
    await user.click(within(rows[1]).getByTestId('billing-row-set'))
    const dialog = await screen.findByTestId('billing-dialog')
    await user.click(within(dialog).getByTestId('billing-mode-POSTPAID'))
    await user.clear(within(dialog).getByTestId('billing-credit'))
    await user.type(within(dialog).getByTestId('billing-credit'), '10000')
    await user.type(within(dialog).getByTestId('billing-reason'), 'Trusted')
    await user.click(within(dialog).getByTestId('billing-submit'))
    await waitFor(() => expect(server.runProcess).toHaveBeenCalledWith(FEE_PROCESS.billingSet,
      { userId: 'other', billingMode: 'POSTPAID', creditLimit: 10000, reason: 'Trusted' }))
    await waitFor(() => expect(screen.queryByTestId('billing-dialog')).not.toBeInTheDocument())
  })

  it('lists the credit notes on its Sponsors\' fees, read only and never who issued them (F-M7)', async () => {
    server.get.mockImplementation(async (path: string) => {
      if (path === '/api/dictionaries/{urn}') return []
      throw new ApiError(404, { detail: 'Not found' })
    })
    server.creditNotes = [{ creditNoteId: 'cn-1', creditNoteNo: 'QBC-2026-00001', company: 'Acme', invoiceNo: 'QBF-2026-00001',
      billingMode: 'PREPAID', reason: 'AUDIENCE_UNUSED', endReason: 'WITHDRAWN', usedDays: 3, note: null, amount: 400, tax: 40,
      total: 440, appliedOwn: 0, refundedCash: 100, refundedCredit: 340, voidedCredit: 0, used: 40, creditLeft: 300,
      issuedAt: '2026-11-05T01:00:00Z', issuedBy: null }]
    const { container } = renderAt('/qb/operator/billing', '/qb/operator/billing', <OperatorBillingPage />)
    const section = await screen.findByTestId('operator-credit-notes')
    const row = await within(section).findByTestId('fee-credit-note-row')
    expect(within(row).getByTestId('fee-credit-note-no')).toHaveTextContent('QBC-2026-00001')
    // The operator sees points, not yen.
    expect(within(row).getByTestId('fee-credit-note-total')).toHaveTextContent('440 Kudos')
    expect(within(row).getByTestId('fee-credit-note-left')).toHaveTextContent('300 Kudos')
    expect(within(section).queryByRole('columnheader', { name: 'Issued by' })).not.toBeInTheDocument()
    expect(within(row).queryByTestId('user-name')).not.toBeInTheDocument()
    expect(server.runQuery).toHaveBeenCalledWith(TEMPLATE.operatorFeeCreditNotes, expect.anything())
    expect(server.runQuery).not.toHaveBeenCalledWith(TEMPLATE.feeCreditNotes, expect.anything())
    await expectAccessible(container)
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
