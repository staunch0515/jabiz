import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import { shareLink } from './paths'
import '../messages'

// The QR code (Q21) is drawn by `qrcode`; jsdom has no canvas, so the drawing is mocked and the files are checked.
vi.mock('qrcode', () => ({ default: {
  toString: vi.fn(async (text: string) => `<svg>${text}</svg>`),
  toCanvas: vi.fn(async () => undefined),
} }))

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const DRAFT = {
  publicationId: 'p1', publisherKind: 'SPONSOR', publisherId: 'u2', quizId: 'q1', versionId: 'v1', versionLabel: 'v1.0',
  name: 'Coffee week',
  regions: 'GLOBAL', startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z', timeLimitSec: 300,
  passScore: 2, budgetCap: null, maxWinners: null, fullScore: 2, maxReward: null, maxSpend: null, tierCount: 0,
  status: 'DRAFT', rejectReason: null, rejectCode: null, revision: 3, editedAt: '2026-10-14T01:00:00Z',
}
const SNAPSHOT = { title: 'Coffee basics', intro: 'Two **easy** questions about beans.', cover: null, timeLimitSec: 300,
  materials: [{ no: 1, kind: 'ARTICLE', title: 'Bean guide', description: 'Arabica and robusta', body: 'Arabica is **sweet**.',
    url: null, pdf: null, audio: null, images: [] }],
  questions: [{ no: 1, stem: 'Which bean is sweeter?', image: null, points: 2,
    options: [{ no: 1, text: 'Arabica', image: null, correct: true }, { no: 2, text: 'Robusta', image: null, correct: false }] }] }
const VERSIONS = [{ versionId: 'v1', versionNo: 1, label: 'v1.0', title: 'Coffee basics', questionCount: 2,
  fullScore: 2, versionedAt: '2026-10-13T00:00:00Z', contentHash: 'h' }]
const ROW = {
  publicationId: 'p1', name: 'Coffee week', quizTitle: 'Coffee basics', versionLabel: 'v1.0', status: 'LIVE',
  regions: 'GLOBAL,JP', starts: 12, completions: 10, passes: 6, winners: 5, maxWinners: 50, spent: '500',
  fundsShort: true, budgetCap: '1000', maxSpend: '1000', startAt: '2026-10-01T00:00:00Z',
  endAt: '2099-01-01T00:00:00Z', rejectReason: null, rejectCode: null, editedAt: '2026-10-14T01:00:00Z',
}

type Answer = { status: number; body: unknown }

interface Server {
  processes?: Record<string, Answer[]>
  publication?: Record<string, unknown> | null
  tiers?: Record<string, unknown>[]
  /** Holds the answers to searches of the quizzes (with `q`) until it resolves. */
  quizSearch?: Promise<void>
  versions?: Record<string, unknown>[]
  /** The sponsor's reward methods setting (Q22); none means the platform's Kudos only. */
  methods?: string
  /** The highest reward one attempt may win (`qb.sponsor.reward-methods`); none for no cap. */
  maxReward?: number
  cashRewards?: Record<string, unknown>[]
  /** The digital prizes (Q33), and a publication's code pool: its counts and its codes. */
  digitalPrizes?: Record<string, unknown>[]
  digitalPool?: { available: number; issued: number; revoked: number }
  digitalCodes?: Record<string, unknown>[]
  /** The prizes of the redeem page (Q22b), answered to every search of `qb.sponsor.redemptions`. */
  redemptions?: Record<string, unknown>[]
  /** The invitation list of an invited publication (Q25), answered to every search and summed up. */
  invitations?: Record<string, unknown>[]
  /** The sponsor's own mail server (Q29); none before one is saved. */
  mailServer?: Record<string, unknown> | null
  /** The draft's stored matching conditions (Q47), one row per option or range. */
  conditions?: Record<string, unknown>[]
  /** The sponsor's staff (Q39). */
  staff?: Record<string, unknown>[]
}

/** The server as the console sees it, for a signed-in, approved sponsor. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const uploads: { policy: string | null; name: string }[] = []
  const publication = { current: options.publication === undefined ? { ...DRAFT } : options.publication }
  const turns = new Map<string, number>()
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    // Q64: an upload's form, read before a Request is made of it (jsdom's cannot hold one).
    if (!(input instanceof Request) && init?.method === 'POST'
      && new URL(String(input), window.location.origin).pathname === '/api/files') {
      const policy = new URL(String(input), window.location.origin).searchParams.get('policy')
      uploads.push({ policy, name: ((init.body as FormData).get('file') as File).name })
      return json(201, { fileId: `up-${uploads.length}` })
    }
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const rows = (items: Record<string, unknown>[]) =>
      json(200, { items: items.map((attributes, i) => ({ id: String(i), version: 1, attributes })), offset: 0,
        limit: 100 })
    const process = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)
    if (process) {
      const answers = options.processes?.[process[1]] ?? [{ status: 404, body: {} }]
      const turn = turns.get(process[1]) ?? 0
      turns.set(process[1], turn + 1)
      const answer = answers[Math.min(turn, answers.length - 1)]
      return json(answer.status, answer.body)
    }
    // A content image's record: no variants (a small picture), as the server says of one.
    if (/^\/api\/files\/[^/]+$/.test(path)) return json(200, { fileId: path.split('/').at(-1), variants: [] })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.publication.write'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return rows([{ contactName: 'Sara', company: 'Acme', reviewStatus: 'APPROVED', revision: 1 }])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbPublication/entities/p1':
        return publication.current ? json(200, { id: 'p1', version: 1, attributes: publication.current })
          : json(404, { detail: 'Not found' })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbRewardTier/query':
        return rows(options.tiers ?? [])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbPublicationCriterion/query':
        return rows(options.conditions ?? [])
      case '/api/queries/qb.sponsor.reward-methods':
        return json(200, { items: [{ methods: options.methods ?? 'PLATFORM', own: Boolean(options.methods),
          maxRewardPerAttempt: options.maxReward ?? null }], offset: 0, limit: 1 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:reward-method':
        return json(200, [{ code: 'PLATFORM', label: 'Kudos paid by QuizBuks' },
          { code: 'SPONSOR_CASH', label: 'Cash from the sponsor' },
          { code: 'OFFLINE_REDEEM', label: "Prize at the sponsor's place" },
          { code: 'DIGITAL_PRIZE', label: 'Digital prize' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:digital-delivery':
        return json(200, [{ code: 'POOL', label: 'One code each from a pool' },
          { code: 'SHARED', label: 'One code for everyone' }, { code: 'SPONSOR_SENT', label: 'Sent by the sponsor' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:digital-prize-status':
        return json(200, [{ code: 'DUE', label: 'To be sent by the sponsor' }, { code: 'DELIVERED', label: 'Delivered' },
          { code: 'CONFIRMED', label: 'Confirmed' }, { code: 'DISPUTED', label: 'Disputed' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:digital-code-status':
        return json(200, [{ code: 'AVAILABLE', label: 'Available' }, { code: 'ISSUED', label: 'Handed out' },
          { code: 'REVOKED', label: 'Taken back' }])
      case '/api/queries/qb.sponsor.digital-prizes':
        return json(200, { items: options.digitalPrizes ?? [], offset: 0, limit: 20, total: options.digitalPrizes?.length ?? 0 })
      case '/api/queries/qb.sponsor.digital-pool-summary':
        return json(200, { items: [{ publicationId: 'p1', ...(options.digitalPool ?? { available: 0, issued: 0, revoked: 0 }) }],
          offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.digital-codes':
        return json(200, { items: options.digitalCodes ?? [], offset: 0, limit: 20, total: options.digitalCodes?.length ?? 0 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:cash-reward-status':
        return json(200, [{ code: 'DUE', label: 'To be paid by the sponsor' }, { code: 'MARKED_PAID', label: 'Paid, awaiting confirmation' },
          { code: 'CONFIRMED', label: 'Confirmed' }, { code: 'DISPUTED', label: 'Disputed' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:redemption-status':
        return json(200, [{ code: 'DUE', label: 'To be collected' }, { code: 'CLAIMED', label: 'Claimed, awaiting the sponsor' },
          { code: 'REDEEMED', label: 'Collected' }, { code: 'EXPIRED', label: 'Expired' }, { code: 'VOID', label: 'Void' }])
      case '/api/queries/qb.sponsor.redemptions': {
        const filter = (body as { filters?: { field: string; value: string }[] } | null)?.filters?.find((f) => f.field === 'status')
        const items = (options.redemptions ?? []).filter((row) => !filter || row.status === filter.value)
        return json(200, { items, offset: 0, limit: 20, total: items.length })
      }
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:publication-audience':
        return json(200, [{ code: 'PUBLIC', label: 'Everyone in the regions' },
          { code: 'INVITED', label: 'Invited e-mail addresses only' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:invitation-status':
        return json(200, [{ code: 'ADDED', label: 'Added' }, { code: 'QUEUED', label: 'Sending' },
          { code: 'SENT', label: 'Sent' }, { code: 'STARTED', label: 'Started' }, { code: 'ANSWERED', label: 'Answered' }])
      case '/api/queries/qb.sponsor.invitations':
        return json(200, { items: options.invitations ?? [], offset: 0, limit: 20, total: options.invitations?.length ?? 0 })
      case '/api/queries/qb.sponsor.mail-server': {
        // Once saved, the row exists (as it does in the system), whatever the test started with.
        const set = posted.find((c) => c.path === '/api/processes/QB_MAIL_SERVER_SET/latest')?.body as
          { input?: Record<string, unknown> } | undefined
        const row = options.mailServer ?? (set ? { ...VERIFIED_SERVER, ...set.input, verifiedAt: null, lastError: 'Could not connect to host',
          testedAt: '2026-10-21T00:00:00Z' } : null)
        return json(200, { items: row ? [row] : [], offset: 0, limit: 1 })
      }
      case '/api/queries/qb.sponsor.invitation-summary': {
        const rows = options.invitations ?? []
        const count = (status: string) => rows.filter((row) => row.status === status).length
        return json(200, { items: [{ publicationId: 'p1', total: rows.length, added: count('ADDED'), queued: count('QUEUED'),
          sent: count('SENT'), started: count('STARTED'), answered: count('ANSWERED') }], offset: 0, limit: 1 })
      }
      case '/api/queries/qb.sponsor.staff':
        return json(200, { items: options.staff ?? [], offset: 0, limit: 100 })
      case '/api/queries/qb.sponsor.cash-rewards':
        return json(200, { items: options.cashRewards ?? [], offset: 0, limit: 20, total: options.cashRewards?.length ?? 0 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:publication-status':
        return json(200, [{ code: 'DRAFT', label: 'Draft' }, { code: 'PENDING_REVIEW', label: 'In review' },
          { code: 'LIVE', label: 'Live' }, { code: 'PAUSED', label: 'Paused' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:region':
        return json(200, [{ code: 'GLOBAL', label: 'Global' }, { code: 'JP', label: 'Japan' },
          { code: 'ASIA', label: 'Asia' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:publication-end-reason':
        return json(200, [{ code: 'EXPIRED', label: 'End time reached' }])
      case '/api/queries/qb.sponsor.publications':
        return json(200, { items: [ROW], offset: 0, limit: 20, total: 1 })
      case '/api/queries/qb.sponsor.quizzes':
        if ((body as { params?: { q?: string } } | null)?.params?.q) await options.quizSearch
        return json(200, { items: [{ quizId: 'q1', title: 'Coffee basics', versionLabel: 'v1.0', latestVersionNo: 1,
          status: 'VERSIONED' }], offset: 0, limit: 20, total: 1 })
      case '/api/queries/qb.sponsor.quiz-version':
        return json(200, { items: [{ versionId: 'v1', versionNo: 1, label: 'v1.0', title: 'Coffee basics',
          language: 'zh', questionCount: 1, quizId: 'q1', timeLimitSec: 300, materialCount: 1, content: JSON.stringify(SNAPSHOT) }],
          offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.quiz-versions':
        return json(200, { items: options.versions ?? VERSIONS, offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { posted, processCalls, publication, uploads }
}

const VERIFIED_SERVER = { sponsorId: 'u2', host: 'smtp.example.com', port: 587, tls: 'STARTTLS', username: 'shop',
  fromAddress: 'shop@example.com', fromName: 'The Shop', verifiedAt: '2026-10-20T00:00:00Z',
  testedAt: '2026-10-20T00:00:00Z', lastError: null }

const saved = (revision: number, extra: Record<string, unknown> = {}) =>
  ({ status: 200, body: { output: { publicationId: 'p1', status: 'DRAFT', revision, editedAt: '2026-10-14T02:00:00Z',
    fullScore: 2, maxReward: '100', maxSpend: null, tierIds: [], problems: [], ...extra } } })

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => vi.restoreAllMocks())

describe('the publication list', () => {
  it('shows each publication with its state, counts and Kudos, searched and filtered on the server', async () => {
    const { posted } = server()
    window.history.replaceState(null, '', '/publications')
    render(<App />)
    const row = await screen.findByTestId('publication-row')
    expect(within(row).getByRole('link', { name: 'Coffee week' })).toHaveAttribute('href', '/publications/p1')
    expect(row).toHaveTextContent('Coffee basics (v1.0)')
    await waitFor(() => expect(row).toHaveTextContent('Live'))
    await waitFor(() => expect(row).toHaveTextContent('Global, Japan'))
    expect(row).toHaveTextContent('5 of 50')
    expect(row).toHaveTextContent('500 Kudos of 1,000 Kudos')
    expect(within(row).getByTestId('publication-funds-short')).toBeInTheDocument()
    expect(row).not.toHaveTextContent('JPY')

    await userEvent.type(screen.getByTestId('publication-search'), 'cof')
    await waitFor(() => expect(posted.some((p) => p.path === '/api/queries/qb.sponsor.publications'
      && (p.body.params as Record<string, unknown>)?.q === 'cof')).toBe(true))
  })
})

describe('the list views (Q34b)', () => {
  it('shows the publications as cards with their quiz\'s cover, and as the table when asked, remembered', async () => {
    window.localStorage.removeItem('qb.sponsor.view.publications')
    server()
    window.history.replaceState(null, '', '/publications')
    const { unmount } = render(<App />)
    const cards = await screen.findByTestId('publication-cards')
    const card = within(cards).getByTestId('publication-row')
    // The whole card leads to the publication through its name.
    expect(within(card).getByRole('link', { name: 'Coffee week' })).toHaveAttribute('href', '/publications/p1')
    expect(screen.queryByRole('table')).toBeNull()
    await userEvent.click(screen.getByRole('radio', { name: 'Table' }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(window.localStorage.getItem('qb.sponsor.view.publications')).toBe('table')
    unmount()
    render(<App />)
    expect(await screen.findByRole('table')).toBeInTheDocument()
    window.localStorage.removeItem('qb.sponsor.view.publications')
  })
})

describe('the version a publication shows (Q34b)', () => {
  it('names it, and says when the quiz has a newer one this publication does not show', async () => {
    server({ publication: { ...DRAFT, status: 'LIVE' }, versions: [
      { versionId: 'v2', versionNo: 2, label: 'v2.0', title: 'Coffee basics', questionCount: 2, fullScore: 2,
        versionedAt: '2026-10-20T00:00:00Z', contentHash: 'b' },
      { versionId: 'v1', versionNo: 1, label: 'v1.0', title: 'Coffee basics', questionCount: 2, fullScore: 2,
        versionedAt: '2026-10-10T00:00:00Z', contentHash: 'a' }] })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const notice = await screen.findByTestId('publication-version')
    expect(notice).toHaveTextContent('Takers see version v1.0 of the quiz')
    expect(await within(notice).findByTestId('publication-version-newer')).toHaveTextContent('version v2.0')
  })
})

describe('switching the version of a publication that runs (Q51)', () => {
  const TWO = [
    { versionId: 'v2', versionNo: 2, label: 'v2.0', title: 'Coffee basics', questionCount: 2, fullScore: 2,
      versionedAt: '2026-10-20T00:00:00Z', contentHash: 'b' },
    { versionId: 'v1', versionNo: 1, label: 'v1.0', title: 'Coffee basics', questionCount: 2, fullScore: 2,
      versionedAt: '2026-10-10T00:00:00Z', contentHash: 'a' }]
  const switched = { status: 200, body: { output: { publicationId: 'p1', status: 'LIVE', revision: 4 } } }

  it('asks for the switch to the newest version, said to be reviewed, with the revision seen', async () => {
    const { processCalls } = server({ publication: { ...DRAFT, status: 'LIVE' }, versions: TWO,
      processes: { QB_PUBLICATION_SWITCH_VERSION: [switched] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const notice = await screen.findByTestId('publication-version')
    expect(await within(notice).findByTestId('publication-version-newer')).toHaveTextContent('the switch is reviewed')
    await userEvent.click(within(notice).getByTestId('publication-switch'))
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('Takers who answered do not answer again')
    await expectAccessible()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Switch to v2.0' }))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SWITCH_VERSION')).toEqual([
      { publicationId: 'p1', versionId: 'v2', baseRevision: 3 }]))
  })

  it('shows the switch in review, which can be withdrawn, and never offers a second', async () => {
    const { processCalls } = server({ publication: { ...DRAFT, status: 'LIVE', pendingVersionId: 'v2',
      pendingVersionLabel: 'v2.0', pendingSince: '2026-10-21T00:00:00Z' }, versions: TWO,
      processes: { QB_PUBLICATION_SWITCH_WITHDRAW: [switched] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const pending = await screen.findByTestId('publication-switch-pending')
    expect(pending).toHaveTextContent('The switch to version v2.0 is in review')
    expect(pending).toHaveTextContent('Takers see version v1.0 until it is approved')
    expect(screen.queryByTestId('publication-switch')).toBeNull()
    await userEvent.click(within(pending).getByTestId('publication-switch-withdraw'))
    const dialog = await screen.findByRole('alertdialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Withdraw the switch' }))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SWITCH_WITHDRAW')).toEqual([
      { publicationId: 'p1', baseRevision: 3 }]))
  })

  it('offers no switch to another full score, nor after an administrator took it down', async () => {
    server({ publication: { ...DRAFT, status: 'LIVE' }, versions: [{ ...TWO[0], fullScore: 3 }, TWO[1]] })
    window.history.replaceState(null, '', '/publications/p1')
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('publication-version-newer'))
      .toHaveTextContent('its full score is 3, not 2')
    expect(screen.queryByTestId('publication-switch')).toBeNull()
    unmount()
    vi.restoreAllMocks()
    server({ publication: { ...DRAFT, status: 'PAUSED', pausedBy: 'ADMIN' }, versions: TWO })
    render(<App />)
    expect(await screen.findByTestId('publication-version-newer')).toHaveTextContent('publish again to use it')
    expect(screen.queryByTestId('publication-switch')).toBeNull()
  })

  it('says why the last switch was rejected or not made', async () => {
    server({ publication: { ...DRAFT, status: 'LIVE', switchRejectReason: 'The cover is misleading.' }, versions: TWO })
    window.history.replaceState(null, '', '/publications/p1')
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('publication-switch-rejected'))
      .toHaveTextContent('The switch to another version was rejected: The cover is misleading.')
    unmount()
    vi.restoreAllMocks()
    server({ publication: { ...DRAFT, status: 'ENDED', switchRejectCode: 'QB_PUBLICATION_SWITCH_ENDED' } })
    render(<App />)
    expect(await screen.findByTestId('publication-switch-rejected'))
      .toHaveTextContent('was not made: the publication ended first.')
  })
})

describe('the preview (Q34b)', () => {
  it('draws the card, the quiz page and the questions as takers see them, without the right answers', async () => {
    server({ publication: { ...DRAFT, status: 'LIVE', rewardMethod: 'OFFLINE_REDEEM', redeemDescription: 'A free espresso' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('preview-open'))
    expect(window.location.pathname).toBe('/publications/p1/preview')
    const frame = await screen.findByTestId('preview-frame')
    // One page heading (the preview's); the publication's name is a heading under it.
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(within(frame).getByRole('heading', { level: 2, name: 'Coffee week' })).toBeInTheDocument()
    // The quiz page first: the taker app's own layout, from the version's snapshot and the sponsor's profile.
    expect(within(frame).getByTestId('detail-quiz-title')).toHaveTextContent('Coffee basics')
    expect(within(frame).getByTestId('detail-sponsor')).toHaveTextContent('Acme')
    expect(within(frame).getByTestId('material-title')).toHaveTextContent('Bean guide')
    expect(within(frame).getByTestId('detail-prize')).toHaveTextContent('A free espresso')
    expect(within(frame).getByText('easy').tagName).toBe('STRONG')
    expect(within(frame).getByTestId('preview-start')).toBeDisabled()
    await expectAccessible()
    // The home's card leads nowhere here.
    await userEvent.click(screen.getByRole('radio', { name: 'Home card' }))
    const card = within(screen.getByTestId('preview-frame')).getByTestId('feed-card')
    expect(card.querySelector('a')).toBeNull()
    expect(card).toHaveTextContent('Coffee week')
    // The questions: the options, never which is right.
    await userEvent.click(screen.getByRole('radio', { name: 'Questions' }))
    const question = within(screen.getByTestId('preview-frame')).getByTestId('preview-question')
    expect(question).toHaveTextContent('Which bean is sweeter?')
    expect(within(question).getAllByTestId('preview-option').map((o) => o.textContent)).toEqual(['AArabica', 'BRobusta'])
    expect(question).not.toHaveTextContent(/correct/i)
  })
})

describe('the publication wizard', () => {
  it('creates a draft by its first save and opens it at the next step', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(1, { publicationId: 'p9' })] } })
    window.history.replaceState(null, '', '/publications/new')
    render(<App />)
    await userEvent.type(await screen.findByTestId('publication-name'), 'Tea week')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(window.location.pathname).toBe('/publications/p9'))
    expect(window.location.search).toBe('?step=1')
    expect(processCalls('QB_PUBLICATION_SAVE')).toEqual([{ publicationId: null, versionId: null, name: 'Tea week',
      cover: null, regions: [], startAt: null, endAt: null, timeLimitSec: null, passScore: null, budgetCap: null,
      maxWinners: null, tiers: [], baseRevision: null, rewardMethod: 'PLATFORM', redeemDescription: null, audience: 'PUBLIC', referral: null }])
  })

  it('offers the version\'s other languages with the publication\'s texts in them (Q73b)', async () => {
    const { processCalls } = server({ versions: [{ ...VERSIONS[0], language: 'en', languages: 'en,zh,ja' }],
      processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const section = await screen.findByTestId('publication-languages')
    // Nothing chosen: the base only, no texts asked.
    expect(within(section).getByTestId('publication-language-en')).toBeChecked()
    expect(within(section).queryByTestId('publication-texts-zh')).toBeNull()
    await userEvent.click(within(section).getByTestId('publication-language-zh'))
    const texts = within(section).getByTestId('publication-texts-zh')
    expect(texts).toHaveTextContent('Coffee week')
    await userEvent.type(within(texts).getByTestId('publication-text-zh-name'), '咖啡周')
    await userEvent.click(within(section).getByTestId('publication-language-main-zh'))
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ languages: ['zh', 'en'],
      translations: { zh: { name: '咖啡周' } } })
  })

  it('uploads the publication\'s own cover and sends it with the save (Q64)', async () => {
    const { processCalls, uploads } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const field = await screen.findByTestId('publication-cover')
    expect(screen.getByTestId('publication-cover-hint'))
      .toHaveTextContent("shown on the home card and the start page. When empty, the quiz's cover is used.")
    expect(within(field).queryByTestId('publication-cover-current')).toBeNull()
    await userEvent.upload(within(field).getByTestId('publication-cover-upload'),
      new File(['x'], 'My photo.png', { type: 'image/png' }))
    expect(await within(field).findByTestId('publication-cover-current')).toBeInTheDocument()
    // The content images' policy, under a neutral name (takers read the file's metadata).
    expect(uploads).toEqual([{ policy: 'qb.content.image', name: 'image.png' }])
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ publicationId: 'p1', cover: 'up-1' })
  })

  it('removes the publication\'s own cover: the save sends none, the quiz\'s is shown (Q64)', async () => {
    const { processCalls } = server({ publication: { ...DRAFT, cover: 'own-1' },
      processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const field = await screen.findByTestId('publication-cover')
    await userEvent.click(await within(field).findByTestId('publication-cover-remove'))
    expect(within(field).queryByTestId('publication-cover-current')).toBeNull()
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ publicationId: 'p1', cover: null })
  })

  it('chooses the newest version with the quiz', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(1, { publicationId: 'p9' })] } })
    window.history.replaceState(null, '', '/publications/new')
    render(<App />)
    await userEvent.type(await screen.findByTestId('publication-name'), 'Tea week')
    await userEvent.click(screen.getByTestId('publication-quiz'))
    await userEvent.click(await screen.findByRole('option', { name: /Coffee basics/ }))
    await waitFor(() => expect(screen.getByTestId('publication-version'))
      .toHaveTextContent('v1.0: 2 questions, full score 2'))
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ versionId: 'v1', name: 'Tea week' })
  })

  it('keeps the quizzes shown while a search is on its way: the open list never holds anything else', async () => {
    let answer = () => {}
    server({ quizSearch: new Promise<void>((resolve) => { answer = resolve }) })
    window.history.replaceState(null, '', '/publications/new')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-quiz'))
    expect(await screen.findByRole('option', { name: /Coffee basics/ })).toBeInTheDocument()
    await userEvent.type(screen.getByPlaceholderText('Search your quizzes'), 'Coffee')
    // The search goes out after the pause in typing and is not answered yet.
    await new Promise((resolve) => setTimeout(resolve, 400))
    const list = screen.getByRole('listbox')
    expect(within(list).getByRole('option', { name: /Coffee basics/ })).toBeInTheDocument()
    expect(list.querySelector('[role="status"], .animate-spin')).toBeNull()
    answer()
    expect(await within(list).findByRole('option', { name: /Coffee basics/ })).toBeInTheDocument()
  })

  it('saves the audience and tiers, then lists every problem and holds the submission', async () => {
    const problems = [
      { field: 'tiers[0].fromScore', code: 'QB_TIER_NOT_FROM_ZERO', message: 'The lowest tier must start at score 0.' },
      { field: 'startAt', code: 'QB_PUBLICATION_FIELD_REQUIRED', message: 'This field is required before submitting.' },
      { field: null, code: 'QB_SPONSOR_NOT_APPROVED', message: 'Your sponsor account is not approved yet.' },
    ]
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4, { tierIds: ['t9'], problems })] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('tier-add'))
    const tier = screen.getByTestId('tier')
    expect(within(tier).getByTestId('tier-from')).toHaveValue('0')
    expect(within(tier).getByTestId('tier-to')).toHaveValue('2')
    await userEvent.type(within(tier).getByTestId('tier-reward'), '100')
    await userEvent.click(screen.getByTestId('publication-next'))
    // Q47: who it is for is a step of its own.
    const regions = await screen.findByTestId('publication-regions')
    await userEvent.click(await within(regions).findByRole('checkbox', { name: 'Japan' }))
    await userEvent.click(screen.getByTestId('publication-next'))

    const listed = await screen.findByTestId('publication-problems')
    expect(within(listed).getAllByTestId('problem').map((li) => li.textContent)).toEqual([
      'Reward tiers: The lowest tier must start at score 0.',
      'Starts: This field is required before submitting.',
      'Your sponsor account is not approved yet.',
    ])
    expect(screen.getByTestId('publication-submit')).toBeDisabled()
    expect(document.activeElement).toBe(screen.getByTestId('publication-step-title'))
    expect(processCalls('QB_PUBLICATION_SAVE').at(-1)).toEqual({ publicationId: 'p1', versionId: 'v1', name: 'Coffee week',
      cover: null, regions: ['GLOBAL', 'JP'], startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z', timeLimitSec: 300,
      passScore: 2, budgetCap: null, maxWinners: null, baseRevision: 4, rewardMethod: 'PLATFORM', redeemDescription: null, audience: 'PUBLIC', referral: null,
      tiers: [{ tierId: 't9', fromScore: 0, toScore: 2, reward: 100 }] })
    // The conditions were not touched: not sent.
    expect(processCalls('QB_PUBLICATION_AUDIENCE_SAVE')).toEqual([])
  })

  it('asks a survey for its one reward and sends the pass mark and tiers it stands for (Q12)', async () => {
    const { processCalls } = server({ versions: [{ ...VERSIONS[0], kind: 'SURVEY' }],
      processes: { QB_PUBLICATION_SAVE: [saved(4, { tierIds: ['t1', 't2'] })] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const reward = await screen.findByTestId('publication-survey-reward')
    expect(screen.queryByTestId('publication-pass-score')).toBeNull()
    expect(screen.queryByTestId('tier-add')).toBeNull()
    await userEvent.type(reward, '50')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ passScore: 2, tiers: [
      { tierId: null, fromScore: 0, toScore: 1, reward: 0 }, { tierId: null, fromScore: 2, toScore: 2, reward: 50 }] })
  })

  it('refuses a tier value that is not a whole number before asking the server', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('tier-add'))
    const from = screen.getByTestId('tier-from')
    await userEvent.clear(from)
    await userEvent.type(from, '1.5')
    await userEvent.click(screen.getByTestId('publication-next'))
    expect(from).toHaveAccessibleDescription(/whole number/)
    expect(from).toHaveAttribute('aria-invalid', 'true')
    expect(processCalls('QB_PUBLICATION_SAVE')).toEqual([])
  })

  it('submits a draft without problems with the revision it saved, then shows it in review', async () => {
    const pending = { status: 200, body: { output: { publicationId: 'p1', status: 'PENDING_REVIEW', revision: 5,
      approval: 'PENDING', approvalRequestId: 'r1' } } }
    const tiers = [{ tierId: 't1', publicationId: 'p1', seq: 1, fromScore: 0, toScore: 2, reward: '100' }]
    const { processCalls, publication } = server({ tiers, processes: {
      QB_PUBLICATION_SAVE: [saved(4, { tierIds: ['t1'], maxSpend: null })], QB_PUBLICATION_SUBMIT: [pending] } })
    window.history.replaceState(null, '', '/publications/p1?step=2')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-next'))
    expect(await screen.findByTestId('publication-ready')).toBeInTheDocument()
    const summary = screen.getByTestId('publication-summary')
    expect(within(summary).getByTestId('summary-max-spend')).toHaveTextContent('No limit')
    expect(within(summary).getByTestId('summary-tier')).toHaveTextContent('100 Kudos')

    publication.current = { ...DRAFT, status: 'PENDING_REVIEW', revision: 5, approvalRequestId: 'r1' }
    await userEvent.click(screen.getByTestId('publication-submit'))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit' }))
    expect(await screen.findByTestId('publication-view')).toHaveAttribute('data-status', 'PENDING_REVIEW')
    expect(processCalls('QB_PUBLICATION_SUBMIT')).toEqual([{ publicationId: 'p1', baseRevision: 4 }])
    expect(screen.getByTestId('publication-withdraw')).toBeInTheDocument()
    expect(screen.queryByTestId('publication-pause')).toBeNull()
  })

  it('lists every reason a submission was refused', async () => {
    const refused = { status: 422, body: { detail: 'Refused', violations: [
      { field: 'endAt', ruleCode: 'QB_PUBLICATION_END_PASSED', message: 'The end time has passed.' },
      { field: null, ruleCode: 'QB_REVIEW_NOT_CONFIGURED', message: 'Publications cannot be reviewed yet.' },
    ] } }
    server({ processes: { QB_PUBLICATION_SAVE: [saved(4)], QB_PUBLICATION_SUBMIT: [refused] } })
    window.history.replaceState(null, '', '/publications/p1?step=2')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-next'))
    await userEvent.click(await screen.findByTestId('publication-submit'))
    await userEvent.click(await screen.findByRole('button', { name: 'Submit' }))
    const listed = await screen.findByTestId('publication-submit-problems')
    expect(within(listed).getAllByTestId('problem').map((li) => li.textContent)).toEqual([
      'Ends: The end time has passed.', 'Publications cannot be reviewed yet.'])
  })

  it('asks to reload when the publication changed elsewhere', async () => {
    const changed = { status: 422, body: { detail: 'Refused', violations: [{ field: 'baseRevision',
      ruleCode: 'QB_PUBLICATION_CHANGED', message: 'The publication was changed in another window.' }] } }
    const { processCalls, publication } = server({ processes: { QB_PUBLICATION_SAVE: [changed, saved(9)] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-save'))
    const stale = await screen.findByTestId('publication-stale')
    expect(screen.getByTestId('publication-next')).toBeDisabled()
    expect(screen.queryByTestId('publication-save-problems')).toBeNull()

    publication.current = { ...DRAFT, name: 'Renamed elsewhere', revision: 8 }
    await userEvent.click(within(stale).getByTestId('publication-reload'))
    await waitFor(() => expect(screen.getByTestId('publication-name')).toHaveValue('Renamed elsewhere'))
    await userEvent.click(screen.getByTestId('publication-save'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(2))
    expect(processCalls('QB_PUBLICATION_SAVE').map((b) => b.baseRevision)).toEqual([3, 8])
  })

  it('shows why a draft was sent back', async () => {
    server({ publication: { ...DRAFT, rejectReason: 'The rewards are too high.' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByTestId('publication-sent-back-reason')).toHaveTextContent('The rewards are too high.')
  })

  it('words a draft sent back by a ban, not its code', async () => {
    server({ publication: { ...DRAFT, rejectCode: 'QB_USER_BANNED' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const reason = await screen.findByTestId('publication-sent-back-reason')
    expect(reason).toHaveTextContent('Your account was suspended while it was in review')
    expect(reason).not.toHaveTextContent('QB_USER_BANNED')
  })
})

describe('referrals welcome (Q70)', () => {
  it('sends the referral policy as set, "not a regular" ticked by default, and shows it in the summary', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const section = await screen.findByTestId('publication-referral')
    expect(within(section).queryByTestId('publication-referral-quota')).toBeNull()
    await userEvent.click(within(section).getByTestId('publication-referral-on'))
    expect(within(section).getByTestId('publication-referral-rule-NOT_REGULAR')).toBeChecked()
    await userEvent.type(within(section).getByTestId('publication-referral-quota'), '20')
    await userEvent.type(within(section).getByTestId('publication-referral-amount'), '300')
    await userEvent.click(within(section).getByTestId('publication-referral-requires-ANSWERED'))
    await userEvent.click(within(section).getByTestId('publication-referral-rule-NEW_TO_PLATFORM'))
    await expectAccessible()
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ referral: { quota: 20, method: 'PLATFORM',
      amount: 300, description: null, referrerRequires: 'ANSWERED', refereeRules: ['NOT_REGULAR', 'NEW_TO_PLATFORM'],
      days: 7 } })
    await userEvent.click(await screen.findByTestId('publication-conditions').then(() => screen.getByTestId('publication-next')))
    await waitFor(() => expect(screen.getByTestId('summary-referral')).toHaveTextContent('20 places'))
  })

  it('sends none while switched off', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    await screen.findByTestId('publication-referral')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ referral: null })
  })
})

describe('the reward method (Q22)', () => {
  it('offers no choice while only the platform pays, and sends the platform\'s method', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    await screen.findByTestId('publication-start')
    expect(screen.queryByTestId('publication-method')).toBeNull()
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ rewardMethod: 'PLATFORM', redeemDescription: null, audience: 'PUBLIC' })
  })

  it('offers the allowed methods, asks a prize for its description, and shows both in the summary', async () => {
    const { processCalls } = server({ methods: 'PLATFORM,SPONSOR_CASH,OFFLINE_REDEEM',
      processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const choice = await screen.findByTestId('publication-method')
    expect(within(choice).getAllByRole('radio')).toHaveLength(3)
    expect(screen.queryByTestId('publication-redeem')).toBeNull()
    await userEvent.click(within(choice).getByTestId('publication-method-OFFLINE_REDEEM'))
    await userEvent.type(await screen.findByTestId('publication-redeem'), 'A coffee at our counter')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ rewardMethod: 'OFFLINE_REDEEM',
      redeemDescription: 'A coffee at our counter' })
    await userEvent.click(await screen.findByTestId('publication-conditions').then(() => screen.getByTestId('publication-next')))
    await waitFor(() => expect(screen.getByTestId('summary-reward-method')).toHaveTextContent("Prize at the sponsor's place"))
    expect(screen.getByTestId('summary-redeem')).toHaveTextContent('A coffee at our counter')
  })

  it('shows the highest reward one attempt may win when the platform caps it (Q28)', async () => {
    server({ methods: 'PLATFORM', maxReward: 500, processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    expect(await screen.findByTestId('publication-reward-cap')).toHaveTextContent('The highest reward one attempt may win is 500 Kudos.')
    expect(screen.queryByTestId('publication-method')).toBeNull()
  })

  it('starts a new draft with the first method allowed when the platform\'s is not', async () => {
    const { processCalls } = server({ methods: 'SPONSOR_CASH', processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/new')
    render(<App />)
    await screen.findByTestId('publication-name')
    await userEvent.type(screen.getByTestId('publication-name'), 'Cash week')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ rewardMethod: 'SPONSOR_CASH' })
  })

  it('lists the cash rewards owed and marks one paid with a note', async () => {
    const reward = { cashRewardId: 'c1', code: 'C-ABCD1234', publicationId: 'p1', publicationName: 'Coffee week',
      takerName: 'Mei', amount: '100', status: 'DUE', dueAt: '2026-10-20T01:00:00Z', payBy: '2026-11-03T01:00:00Z',
      markedPaidAt: null, markedPaidNote: null, confirmBy: null, confirmedAt: null, disputedAt: null,
      disputeNote: null, resolution: null, resolveNote: null }
    const { processCalls } = server({ cashRewards: [reward], processes: { QB_CASH_REWARD_MARK_PAID: [
      { status: 200, body: { output: { cashRewardId: 'c1', status: 'MARKED_PAID', revision: 2 } } }] } })
    window.history.replaceState(null, '', '/cash-rewards')
    render(<App />)
    const row = await screen.findByTestId('cash-row')
    expect(row).toHaveTextContent('C-ABCD1234')
    expect(row).toHaveTextContent('Mei')
    expect(row).toHaveTextContent('100 Kudos')
    await waitFor(() => expect(within(row).getByTestId('cash-status')).toHaveTextContent('To be paid by the sponsor'))
    await userEvent.click(within(row).getByTestId('cash-mark'))
    await userEvent.type(await screen.findByTestId('cash-mark-note'), 'Receipt 17')
    await userEvent.click(screen.getByTestId('cash-mark-confirm'))
    await waitFor(() => expect(processCalls('QB_CASH_REWARD_MARK_PAID')).toEqual([{ cashRewardId: 'c1', note: 'Receipt 17' }]))
    await waitFor(() => expect(screen.queryByTestId('cash-mark-dialog')).toBeNull())
  })
})

describe('digital prizes (Q33)', () => {
  it('asks a digital prize for its delivery and description, sends the shared content once typed, and sums it up', async () => {
    const { processCalls } = server({ methods: 'PLATFORM,DIGITAL_PRIZE', processes: { QB_PUBLICATION_SAVE: [saved(4)] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const choice = await screen.findByTestId('publication-method')
    expect(screen.queryByTestId('publication-digital-delivery')).toBeNull()
    await userEvent.click(within(choice).getByTestId('publication-method-DIGITAL_PRIZE'))
    const delivery = await screen.findByTestId('publication-digital-delivery')
    expect(within(delivery).getAllByRole('radio')).toHaveLength(3)
    await waitFor(() => expect(delivery).toHaveTextContent('One code for everyone'))
    expect(screen.queryByTestId('publication-digital-content')).toBeNull()
    // A pool says what it needs; the shared way asks for the content, which is a secret.
    await userEvent.click(within(delivery).getByTestId('publication-digital-delivery-POOL'))
    expect(screen.getByTestId('publication-digital-pool-note')).toBeInTheDocument()
    await userEvent.click(within(delivery).getByTestId('publication-digital-delivery-SHARED'))
    expect(screen.queryByTestId('publication-digital-pool-note')).toBeNull()
    await userEvent.type(screen.getByTestId('publication-digital-description'), 'A month of streaming')
    const content = screen.getByTestId('publication-digital-content')
    expect(content).toHaveAttribute('autocomplete', 'off')
    expect(content).toHaveAccessibleDescription(/never shown again/)
    await userEvent.type(content, 'STREAM-2026')
    await expectAccessible()
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED',
      digitalDescription: 'A month of streaming', digitalContent: 'STREAM-2026', digitalSendDays: null })
    await userEvent.click(await screen.findByTestId('publication-conditions').then(() => screen.getByTestId('publication-next')))
    await waitFor(() => expect(screen.getByTestId('summary-reward-method')).toHaveTextContent('Digital prize'))
    expect(screen.getByTestId('summary-digital-delivery')).toHaveTextContent('One code for everyone')
    expect(screen.getByTestId('summary-digital')).toHaveTextContent('A month of streaming')
    expect(screen.queryByTestId('summary-digital-send-days')).toBeNull()
  })

  it('leaves the sealed content alone when nothing is typed, and asks the days of a prize the sponsor sends', async () => {
    const { processCalls } = server({ methods: 'DIGITAL_PRIZE', processes: { QB_PUBLICATION_SAVE: [saved(4), saved(5)] },
      publication: { ...DRAFT, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED', digitalDescription: 'A month' } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    const content = await screen.findByTestId('publication-digital-content')
    expect(content).toHaveValue('')
    await userEvent.click(screen.getByTestId('publication-save'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1))
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).toMatchObject({ digitalDelivery: 'SHARED', digitalDescription: 'A month' })
    expect(processCalls('QB_PUBLICATION_SAVE')[0]).not.toHaveProperty('digitalContent')

    await userEvent.click(screen.getByTestId('publication-digital-delivery-SPONSOR_SENT'))
    expect(screen.queryByTestId('publication-digital-content')).toBeNull()
    await userEvent.type(screen.getByTestId('publication-digital-send-days'), '7')
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(2))
    expect(processCalls('QB_PUBLICATION_SAVE')[1]).toMatchObject({ digitalDelivery: 'SPONSOR_SENT', digitalSendDays: 7 })
    await userEvent.click(await screen.findByTestId('publication-conditions').then(() => screen.getByTestId('publication-next')))
    expect(await screen.findByTestId('summary-digital-send-days')).toHaveTextContent('7 days')
  })

  it('shows the code pool of a pooled prize: loads codes, lists their hints and takes the unused back', async () => {
    const codes = [
      { codeId: 'k1', seq: 1, hint: '…AAAA', status: 'ISSUED', loadedAt: '2026-10-20T01:00:00Z', issuedAt: '2026-10-21T01:00:00Z', revokedAt: null },
      { codeId: 'k2', seq: 2, hint: '…BBBB', status: 'AVAILABLE', loadedAt: '2026-10-20T01:00:00Z', issuedAt: null, revokedAt: null },
      { codeId: 'k3', seq: 3, hint: null, status: 'REVOKED', loadedAt: '2026-10-20T01:00:00Z', issuedAt: null, revokedAt: '2026-10-22T01:00:00Z' },
    ]
    const { processCalls } = server({
      publication: { ...DRAFT, status: 'ENDED', endedAt: '2026-10-23T00:00:00Z', endReason: 'EXPIRED',
        rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL', digitalDescription: 'A coffee voucher', maxWinners: 2 },
      digitalPool: { available: 1, issued: 1, revoked: 1 },
      digitalCodes: codes,
      processes: {
        QB_DIGITAL_CODES_LOAD: [{ status: 200, body: { output: { publicationId: 'p1', loaded: 2, available: 3 } } }],
        QB_DIGITAL_CODES_REVOKE: [{ status: 200, body: { output: { publicationId: 'p1', revoked: 3, available: 0 } } }],
      },
    })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const pool = await screen.findByTestId('digital-pool')
    expect(await within(pool).findByTestId('digital-pool-available')).toHaveTextContent('1')
    expect(within(pool).getByTestId('digital-pool-issued')).toHaveTextContent('1')
    expect(within(pool).getByTestId('digital-pool-revoked')).toHaveTextContent('1')
    await waitFor(() => expect(screen.getByTestId('summary-digital-delivery')).toHaveTextContent('One code each from a pool'))
    expect(screen.getByTestId('summary-digital')).toHaveTextContent('A coffee voucher')
    const rows = await within(pool).findAllByTestId('digital-code-row')
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['ISSUED', 'AVAILABLE', 'REVOKED'])
    expect(within(rows[0]).getByTestId('digital-code-hint')).toHaveTextContent('…AAAA')
    await waitFor(() => expect(within(rows[1]).getByTestId('digital-code-status')).toHaveTextContent('Available'))
    expect(within(rows[2]).queryByTestId('digital-code-hint')).toBeNull()
    await expectAccessible()

    // Nothing to load until something is typed; the lines go as one text.
    expect(within(pool).getByTestId('digital-pool-load')).toBeDisabled()
    await userEvent.type(within(pool).getByTestId('digital-pool-lines'), 'CODE-1\n\nCODE-2')
    await userEvent.click(within(pool).getByTestId('digital-pool-load'))
    await waitFor(() => expect(processCalls('QB_DIGITAL_CODES_LOAD')).toEqual([{ publicationId: 'p1', pastedCodes: 'CODE-1\n\nCODE-2' }]))
    await waitFor(() => expect(within(pool).getByTestId('digital-pool-lines')).toHaveValue(''))
    expect(await screen.findByText('2 codes loaded; 3 available.')).toBeInTheDocument()

    await userEvent.click(within(pool).getByTestId('digital-pool-revoke'))
    await userEvent.click(await screen.findByRole('button', { name: 'Take back the unused codes' }))
    await waitFor(() => expect(processCalls('QB_DIGITAL_CODES_REVOKE')).toEqual([{ publicationId: 'p1' }]))
    expect(await screen.findByText('3 codes taken back.')).toBeInTheDocument()
  })

  it('offers no taking back while the publication runs, and shows the pool under a draft\'s wizard', async () => {
    server({ publication: { ...DRAFT, status: 'LIVE', rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL',
      digitalDescription: 'A coffee voucher', maxWinners: 2 }, digitalPool: { available: 2, issued: 0, revoked: 0 } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const pool = await screen.findByTestId('digital-pool')
    expect(await within(pool).findByTestId('digital-pool-available')).toHaveTextContent('2')
    expect(within(pool).queryByTestId('digital-pool-revoke')).toBeNull()
  })

  it('shows the pool below a draft\'s wizard with the way to take codes back', async () => {
    server({ methods: 'DIGITAL_PRIZE', publication: { ...DRAFT, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'POOL',
      digitalDescription: 'A coffee voucher' }, digitalPool: { available: 2, issued: 0, revoked: 0 } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    expect(await screen.findByTestId('publication-wizard')).toBeInTheDocument()
    const pool = await screen.findByTestId('digital-pool')
    expect(await within(pool).findByTestId('digital-pool-revoke')).toBeEnabled()
  })

  it('lists the digital prizes with their states, searched and filtered on the server', async () => {
    const prize = (over: Record<string, unknown>) => ({ digitalPrizeId: 'd1', publicationId: 'p1', publicationName: 'Coffee week',
      takerName: 'Mei', amount: '100', description: 'A coffee voucher', delivery: 'POOL', hint: '…AAAA', status: 'DELIVERED',
      dueAt: '2026-10-20T01:00:00Z', sendBy: null, deliveredAt: '2026-10-20T01:00:00Z', deliveredBy: 'SYSTEM',
      openedAt: '2026-10-20T02:00:00Z', confirmBy: '2026-11-03T01:00:00Z', confirmedAt: null, disputedAt: null,
      disputeNote: null, resolution: null, resolveNote: null, ...over })
    const { posted } = server({ digitalPrizes: [
      prize({}),
      prize({ digitalPrizeId: 'd2', takerName: 'Ken', delivery: 'SPONSOR_SENT', hint: null, status: 'DUE', deliveredAt: null,
        deliveredBy: null, openedAt: null, confirmBy: null, sendBy: '2026-10-23T01:00:00Z' }),
      prize({ digitalPrizeId: 'd3', takerName: 'Lee', status: 'DISPUTED', disputedAt: '2026-10-21T01:00:00Z',
        disputeNote: 'The code was already used.' }),
    ] })
    window.history.replaceState(null, '', '/digital-prizes')
    render(<App />)
    const rows = await screen.findAllByTestId('digital-row')
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['DELIVERED', 'DUE', 'DISPUTED'])
    expect(rows[0]).toHaveTextContent('Mei')
    expect(within(rows[0]).getByRole('link', { name: 'Coffee week' })).toHaveAttribute('href', '/publications/p1')
    expect(within(rows[0]).getByTestId('digital-description')).toHaveTextContent('A coffee voucher')
    expect(within(rows[0]).getByTestId('digital-hint')).toHaveTextContent('…AAAA')
    expect(rows[0]).toHaveTextContent('100 Kudos')
    await waitFor(() => expect(within(rows[0]).getByTestId('digital-delivery')).toHaveTextContent('One code each from a pool'))
    await waitFor(() => expect(within(rows[0]).getByTestId('digital-status')).toHaveTextContent('Delivered'))
    expect(rows[0]).toHaveTextContent('Taker confirms by')
    expect(within(rows[0]).getByTestId('digital-opened')).toHaveTextContent('First shown')
    expect(rows[1]).toHaveTextContent('Send by')
    expect(within(rows[1]).queryByTestId('digital-hint')).toBeNull()
    expect(within(rows[2]).getByTestId('digital-dispute-note')).toHaveTextContent('Taker: The code was already used.')
    await expectAccessible()

    await userEvent.type(screen.getByTestId('digital-search'), 'mei{Enter}')
    await waitFor(() => expect(posted.some((p) => p.path === '/api/queries/qb.sponsor.digital-prizes'
      && (p.body.params as Record<string, unknown>)?.q === 'mei')).toBe(true))
    await userEvent.click(screen.getByTestId('digital-status-filter'))
    await userEvent.click(await screen.findByRole('option', { name: 'Disputed' }))
    await waitFor(() => expect(posted.some((p) => p.path === '/api/queries/qb.sponsor.digital-prizes'
      && JSON.stringify(p.body.filters) === JSON.stringify([{ field: 'status', op: 'eq', value: 'DISPUTED' }]))).toBe(true))
  })
})

describe('the redeem page (Q22b)', () => {
  const issued = (kind: string, key: string) => ({ status: 200, body: { output: { redeemKey: key, kind,
    issuedAt: '2026-10-20T01:00:00Z', expiresAt: kind === 'ROTATING' ? '2026-10-20T01:02:00Z' : null } } })
  const prize = (over: Record<string, unknown>) => ({ redemptionId: 'r1', code: 'C-ABCD1234', publicationId: 'p1',
    publicationName: 'Coffee week', takerName: 'Mei', amount: '100', description: 'A coffee at our counter',
    status: 'DUE', dueAt: '2026-10-20T00:00:00Z', expiresAt: '2026-11-19T00:00:00Z', claimKind: null, claimedAt: null,
    confirmBy: null, redeemedAt: null, ...over })

  it('shows the rotating code as a link into the app, and the printed one on request with its files', async () => {
    const { processCalls } = server({ processes: { QB_REDEEM_CODE_ISSUE: [issued('ROTATING', 'rot-key-1'),
      issued('PRINTED', 'print-key-1')] } })
    window.history.replaceState(null, '', '/redeem')
    render(<App />)
    const link = await screen.findByTestId('redeem-rotating-link')
    expect(link).toHaveTextContent(`${window.location.origin}/taker/redeem/rot-key-1`)
    await screen.findByTestId('redeem-rotating-image')
    expect(screen.getByTestId('redeem-rotating-expires')).toHaveTextContent('This code works until')
    expect(processCalls('QB_REDEEM_CODE_ISSUE')).toEqual([{ kind: 'ROTATING' }])
    expect(screen.queryByTestId('redeem-printed-link')).toBeNull()
    await userEvent.click(screen.getByTestId('redeem-printed-show'))
    expect(await screen.findByTestId('redeem-printed-link')).toHaveTextContent("/taker/redeem/print-key-1")
    await waitFor(() => expect(processCalls('QB_REDEEM_CODE_ISSUE')).toEqual([{ kind: 'ROTATING' }, { kind: 'PRINTED' }]))
    expect(screen.getByTestId('redeem-printed-png')).toBeEnabled()
    expect(screen.getByTestId('redeem-printed-poster')).toBeEnabled()
    expect(screen.getByTestId('redeem-today')).toHaveTextContent('Nothing collected yet today.')
  })

  it('lists the claims from the printed code and confirms or declines them, and shows every prize', async () => {
    const { processCalls } = server({
      redemptions: [prize({ status: 'CLAIMED', claimKind: 'PRINTED', claimedAt: '2026-10-20T01:00:00Z',
        confirmBy: '2099-01-01T00:10:00Z' }), prize({ redemptionId: 'r2', code: 'C-00000002', takerName: 'Aki',
        status: 'REDEEMED', redeemedAt: '2026-10-20T00:30:00Z' })],
      processes: { QB_REDEEM_CODE_ISSUE: [issued('ROTATING', 'rot-key-2')],
        QB_REDEMPTION_CONFIRM: [{ status: 200, body: { output: { redemptionId: 'r1', status: 'REDEEMED', revision: 2 } } }] },
    })
    window.history.replaceState(null, '', '/redeem')
    render(<App />)
    const claim = await screen.findByTestId('redeem-claim')
    expect(claim).toHaveTextContent('C-ABCD1234')
    expect(claim).toHaveTextContent('Mei')
    expect(claim).toHaveTextContent('A coffee at our counter')
    await userEvent.click(within(claim).getByTestId('redeem-claim-confirm'))
    await waitFor(() => expect(processCalls('QB_REDEMPTION_CONFIRM')).toEqual([{ redemptionId: 'r1' }]))
    const rows = await screen.findAllByTestId('redemption-row')
    expect(rows).toHaveLength(2)
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['CLAIMED', 'REDEEMED'])
    await waitFor(() => expect(within(rows[1]).getByTestId('redemption-status')).toHaveTextContent('Collected'))
  })

  it('names the staff member who handed a prize over (Q39), and nobody for the sponsor\'s own', async () => {
    server({
      redemptions: [prize({ status: 'REDEEMED', redeemedAt: '2026-10-20T00:30:00Z', redeemedByName: 'Kai' }),
        prize({ redemptionId: 'r2', code: 'C-00000002', status: 'REDEEMED', redeemedAt: '2026-10-20T00:40:00Z',
          redeemedByName: null })],
      processes: { QB_REDEEM_CODE_ISSUE: [issued('ROTATING', 'rot-key-3')] },
    })
    window.history.replaceState(null, '', '/redeem')
    render(<App />)
    const rows = await screen.findAllByTestId('redemption-row')
    expect(within(rows[0]).getByTestId('redemption-staff')).toHaveTextContent('Handed over by Kai')
    expect(within(rows[1]).queryByTestId('redemption-staff')).toBeNull()
  })
})

describe('the staff (Q39)', () => {
  const KAI = '01a0f4fe-4660-7000-8000-000000000001'
  const found = (onStaff: boolean) => ({ status: 200, body: { output: { userId: KAI, displayName: 'Kai', onStaff } } })
  const done = { status: 200, body: { output: { userId: KAI, changed: true } } }

  it('looks an ID up, shows whose it is and adds them; the list shows the staff', async () => {
    const { processCalls } = server({ processes: { QB_SPONSOR_STAFF_FIND: [found(false)], QB_SPONSOR_STAFF_ADD: [done] } })
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    await waitFor(() => expect(screen.getByTestId('staff-list')).toHaveTextContent('No staff yet.'))
    await userEvent.type(screen.getByLabelText('Their ID'), `  ${KAI} `)
    await userEvent.click(screen.getByTestId('staff-find'))
    expect(await screen.findByTestId('staff-candidate')).toHaveTextContent('This ID is Kai.')
    expect(processCalls('QB_SPONSOR_STAFF_FIND')).toEqual([{ userId: KAI }])
    await userEvent.click(screen.getByRole('button', { name: 'Add Kai' }))
    await waitFor(() => expect(processCalls('QB_SPONSOR_STAFF_ADD')).toEqual([{ userId: KAI }]))
    await waitFor(() => expect(screen.queryByTestId('staff-candidate')).toBeNull())
    await expectAccessible()
  })

  it('says the server\'s reason for an ID that cannot be added, and offers no button for one already added', async () => {
    server({ processes: { QB_SPONSOR_STAFF_FIND: [{ status: 422, body: { title: 'Rejected', status: 422,
      detail: 'This ID is not a quiz taker\'s.', violations: [{ field: 'userId', ruleCode: 'QB_STAFF_NOT_A_TAKER',
        message: 'This ID is not a quiz taker\'s.' }] } }, found(true)] } })
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    await userEvent.type(await screen.findByLabelText('Their ID'), 'nobody')
    await userEvent.click(screen.getByTestId('staff-find'))
    expect(await screen.findByTestId('staff-find-failed')).toHaveTextContent("This ID is not a quiz taker's.")
    await userEvent.clear(screen.getByLabelText('Their ID'))
    await userEvent.type(screen.getByLabelText('Their ID'), KAI)
    await userEvent.click(screen.getByTestId('staff-find'))
    expect(await screen.findByTestId('staff-candidate')).toHaveTextContent('Kai is already on your staff.')
    expect(screen.queryByTestId('staff-add-confirm')).toBeNull()
  })

  it('removes a staff member after the sponsor confirms', async () => {
    const { processCalls } = server({ staff: [{ userId: KAI, displayName: 'Kai', addedAt: '2026-10-20T00:00:00Z' }],
      processes: { QB_SPONSOR_STAFF_REMOVE: [done] } })
    window.history.replaceState(null, '', '/staff')
    render(<App />)
    const row = await screen.findByTestId('staff-row')
    expect(row).toHaveTextContent('Kai')
    expect(row).toHaveTextContent(KAI)
    await expectAccessible()
    await userEvent.click(within(row).getByTestId('staff-remove'))
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(processCalls('QB_SPONSOR_STAFF_REMOVE')).toEqual([{ userId: KAI }]))
  })
})

describe('a submitted publication', () => {
  it('is shown frozen, and pauses after the sponsor confirms', async () => {
    const pausedOut = { status: 200, body: { output: { publicationId: 'p1', status: 'PAUSED', revision: 7,
      approval: null, approvalRequestId: null } } }
    const { processCalls, publication } = server({ publication: { ...DRAFT, status: 'LIVE', revision: 6,
      budgetCap: '1000', maxReward: '100', maxSpend: '1000' }, processes: { QB_PUBLICATION_PAUSE: [pausedOut] } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const view = await screen.findByTestId('publication-view')
    expect(view).toHaveTextContent('1,000 Kudos')
    expect(screen.queryByTestId('publication-wizard')).toBeNull()
    expect(screen.queryByTestId('publication-delete')).toBeNull()

    publication.current = { ...DRAFT, status: 'PAUSED', revision: 7, pausedAt: '2026-10-14T03:00:00Z' }
    await userEvent.click(screen.getByTestId('publication-pause'))
    await userEvent.click(await screen.findByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(screen.getByTestId('publication-view')).toHaveAttribute('data-status', 'PAUSED'))
    expect(processCalls('QB_PUBLICATION_PAUSE')).toEqual([{ publicationId: 'p1' }])
    expect(screen.getByTestId('publication-resume')).toBeInTheDocument()
  })

  it('copies the share link of a live publication (Q15), and offers none once it is paused', async () => {
    const writeText = vi.fn(async () => undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    server({ publication: { ...DRAFT, status: 'LIVE', revision: 6, budgetCap: '1000', maxReward: '100',
      maxSpend: '1000' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-share'))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/taker/p/p1?lang=zh`))
    expect(shareLink('a b', null, 'https://quizbuks.com')).toBe('https://quizbuks.com/taker/p/a%20b')
    expect(shareLink('a b', 'zh', 'https://quizbuks.com')).toBe('https://quizbuks.com/taker/p/a%20b?lang=zh')
  })

  it('shows the QR code of a live publication with its link, and saves the poster (Q21)', async () => {
    const saved: string[] = []
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => ({
      fillRect() {}, fillText() {}, drawImage() {}, measureText: (text: string) => ({ width: text.length * 60 }),
    }) as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(new Blob(['png'])))
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:qr')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push(this.download)
    })
    server({ publication: { ...DRAFT, status: 'LIVE', revision: 6, budgetCap: '1000', maxReward: '100',
      maxSpend: '1000' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('publication-qr'))
    const dialog = await screen.findByTestId('publication-qr-dialog')
    const image = await within(dialog).findByTestId('publication-qr-image')
    expect(decodeURIComponent(image.getAttribute('src')!.split(',')[1]))
      .toBe(`<svg>${window.location.origin}/taker/p/p1?lang=zh</svg>`)
    expect(image).toHaveAccessibleName('QR code leading to Coffee week')
    expect(within(dialog).getByTestId('publication-qr-link')).toHaveTextContent(`${window.location.origin}/taker/p/p1?lang=zh`)

    await userEvent.click(within(dialog).getByTestId('publication-qr-poster'))
    await waitFor(() => expect(saved).toEqual(['Coffee week poster.png']))
    await userEvent.click(within(dialog).getByTestId('publication-qr-svg'))
    await waitFor(() => expect(saved).toEqual(['Coffee week poster.png', 'Coffee week QR.svg']))
    await userEvent.click(within(dialog).getByTestId('publication-qr-png'))
    await waitFor(() => expect(saved).toEqual(['Coffee week poster.png', 'Coffee week QR.svg', 'Coffee week QR.png']))
  })

  it('offers no share link for a paused publication', async () => {
    server({ publication: { ...DRAFT, status: 'PAUSED', revision: 7, pausedAt: '2026-10-14T03:00:00Z' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByTestId('publication-view')).toBeInTheDocument()
    expect(screen.queryByTestId('publication-share')).toBeNull()
    expect(screen.queryByTestId('publication-qr')).toBeNull()
  })

  it('says an administrator paused it, with the reason, and offers no resume (Q10b)', async () => {
    server({ publication: { ...DRAFT, status: 'PAUSED', revision: 8, pausedAt: '2026-10-14T03:00:00Z',
      pausedBy: 'ADMIN', adminReason: 'Reported as misleading' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByTestId('publication-admin-notice')).toHaveTextContent('Paused by QuizBuks')
    expect(screen.getByTestId('publication-admin-reason')).toHaveTextContent('Reason: Reported as misleading')
    expect(screen.queryByTestId('publication-resume')).toBeNull()
    expect(screen.queryByTestId('publication-pause')).toBeNull()
    // Taken down: not copied either (review M-d).
    expect(screen.queryByTestId('publication-copy')).toBeNull()
  })

  it('words the pause of a ban without the ban\'s reason (Q10b, review L3)', async () => {
    server({ publication: { ...DRAFT, status: 'PAUSED', revision: 8, pausedAt: '2026-10-14T03:00:00Z',
      pausedBy: 'ADMIN', adminReason: 'QB_ACCOUNT_SUSPENDED' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByTestId('publication-admin-reason'))
      .toHaveTextContent('Reason: Your account was suspended.')
  })

  it('says an administrator ended it', async () => {
    server({ publication: { ...DRAFT, status: 'ENDED', revision: 8, endedAt: '2026-10-14T03:00:00Z',
      endReason: 'ADMIN', adminReason: 'Fraudulent' } })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByTestId('publication-admin-notice')).toHaveTextContent('Ended by QuizBuks')
    expect(screen.getByTestId('publication-admin-reason')).toHaveTextContent('Fraudulent')
  })

  it('says so when the publication is not the sponsor\'s', async () => {
    server({ publication: null })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    expect(await screen.findByText('This publication does not exist or is not yours.')).toBeInTheDocument()
  })
})

describe('customer matching (Q47)', () => {
  const audienceSaved = (revision: number) => ({ status: 200, body: { output: { publicationId: 'p1', status: 'DRAFT',
    revision, approval: null, approvalRequestId: null } } })

  it('shows the stored conditions, saves them with the draft, and counts the takers rounded', async () => {
    const { processCalls } = server({
      conditions: [{ publicationId: 'p1', criterionKey: 'age', minValue: 20, maxValue: 39 }],
      processes: {
        QB_PUBLICATION_SAVE: [saved(4), saved(6)],
        QB_PUBLICATION_AUDIENCE_SAVE: [audienceSaved(5), audienceSaved(7)],
        QB_AUDIENCE_COUNT: [{ status: 200, body: { output: { count: null, fewerThan: 20 } } },
          { status: 200, body: { output: { count: 120, fewerThan: null } } }],
      },
    })
    window.history.replaceState(null, '', '/publications/p1?step=2')
    render(<App />)
    const age = await screen.findByTestId('audience-age')
    expect(await within(age).findByTestId('audience-age-min')).toHaveValue('20')
    expect(within(age).getByTestId('audience-age-max')).toHaveValue('39')
    // Nothing chosen of gender until it is switched on.
    expect(screen.queryByTestId('audience-gender-FEMALE')).toBeNull()
    await userEvent.click(screen.getByTestId('audience-gender-on'))
    await userEvent.click(screen.getByTestId('audience-gender-NO_ANSWER'))
    await userEvent.click(screen.getByTestId('audience-gender-FEMALE'))
    await expectAccessible()

    // Counting saves the draft and its conditions first, then counts what is stored.
    await userEvent.click(screen.getByTestId('audience-count-button'))
    expect(await screen.findByTestId('audience-count-result')).toHaveTextContent('Fewer than 20 takers')
    expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(1)
    expect(processCalls('QB_PUBLICATION_AUDIENCE_SAVE')).toEqual([{ publicationId: 'p1', baseRevision: 4, criteria: [
      { key: 'gender', options: ['FEMALE', 'NO_ANSWER'] }, { key: 'age', min: 20, max: 39 }] }])
    expect(processCalls('QB_AUDIENCE_COUNT')).toEqual([{ publicationId: 'p1' }])

    // A change clears the count; the next save carries the revision the conditions' save answered.
    await userEvent.click(screen.getByTestId('audience-age-on'))
    expect(screen.getByTestId('audience-count-result')).toBeEmptyDOMElement()
    await userEvent.click(screen.getByTestId('audience-count-button'))
    await waitFor(() => expect(screen.getByTestId('audience-count-result')).toHaveTextContent('About 120 takers'))
    expect(processCalls('QB_PUBLICATION_SAVE')[1]).toMatchObject({ baseRevision: 5 })
    expect(processCalls('QB_PUBLICATION_AUDIENCE_SAVE')[1]).toEqual({ publicationId: 'p1', baseRevision: 6, criteria: [
      { key: 'gender', options: ['FEMALE', 'NO_ANSWER'] }] })

    await userEvent.click(screen.getByTestId('publication-next'))
    expect(await screen.findByTestId('summary-conditions')).toHaveTextContent('Gender: Female, Prefer not to say')
  })

  it('asks for an option before sending, and keeps the draft\'s revision when the conditions are refused', async () => {
    const refused = { status: 422, body: { detail: 'Refused', violations: [{ field: 'criteria[0].options',
      ruleCode: 'QB_AUDIENCE_OPTION', message: 'Not an option of this criterion.' }] } }
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4), saved(6)],
      QB_PUBLICATION_AUDIENCE_SAVE: [refused, audienceSaved(7)] } })
    window.history.replaceState(null, '', '/publications/p1?step=2')
    render(<App />)
    await userEvent.click(await screen.findByTestId('audience-interest-on'))
    await userEvent.click(screen.getByTestId('publication-save'))
    // Switched on without an option: nothing is sent.
    expect(processCalls('QB_PUBLICATION_SAVE')).toEqual([])
    await userEvent.click(screen.getByTestId('audience-interest-PETS'))
    await userEvent.click(screen.getByTestId('publication-save'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_AUDIENCE_SAVE')).toHaveLength(1))
    expect(await screen.findByTestId('publication-save-problems')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('publication-save'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(2))
    // The draft's save went through the first time: the second carries its revision, not a stale one.
    expect(processCalls('QB_PUBLICATION_SAVE')[1]).toMatchObject({ baseRevision: 4 })
    expect(screen.queryByTestId('publication-stale')).toBeNull()
  })
})

describe('invitation-only publications (Q25)', () => {
  it('asks for the list instead of the regions and saves the audience', async () => {
    const { processCalls } = server({ processes: { QB_PUBLICATION_SAVE: [saved(4, { tierIds: ['t1'] })] } })
    window.history.replaceState(null, '', '/publications/p1?step=1')
    render(<App />)
    await userEvent.click(await screen.findByTestId('tier-add'))
    await userEvent.type(within(screen.getByTestId('tier')).getByTestId('tier-reward'), '100')
    await userEvent.click(screen.getByTestId('publication-next'))
    expect(await screen.findByTestId('publication-regions')).toBeInTheDocument()
    expect(screen.getByTestId('publication-conditions')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('publication-audience-INVITED'))
    expect(screen.queryByTestId('publication-regions')).toBeNull()
    expect(screen.queryByTestId('publication-conditions')).toBeNull()
    await userEvent.click(screen.getByTestId('publication-next'))
    await waitFor(() => expect(processCalls('QB_PUBLICATION_SAVE')).toHaveLength(2))
    expect(processCalls('QB_PUBLICATION_SAVE')[1]).toMatchObject({ audience: 'INVITED', regions: [] })
    expect(await screen.findByTestId('summary-audience')).toHaveTextContent('Invited e-mail addresses only')
  })

  it('shows the list of a live invited publication, adds addresses, sends and removes', async () => {
    const live = { ...DRAFT, status: 'LIVE', audience: 'INVITED', regions: 'GLOBAL', approvedAt: '2026-10-15T00:00:00Z' }
    const { processCalls } = server({
      publication: live,
      mailServer: VERIFIED_SERVER,
      invitations: [
        { invitationId: 'i1', email: 'alice@example.com', status: 'SENT', addedAt: '2026-10-15T01:00:00Z', sentCount: 1,
          lastSentAt: '2026-10-15T02:00:00Z', startedAt: null, answeredAt: null, score: null, reward: null },
        { invitationId: 'i2', email: 'bob@example.com', status: 'ANSWERED', addedAt: '2026-10-15T01:00:00Z', sentCount: 1,
          lastSentAt: '2026-10-15T02:00:00Z', startedAt: '2026-10-16T01:00:00Z', answeredAt: '2026-10-16T01:05:00Z',
          score: 2, reward: '100' },
        { invitationId: 'i3', email: 'carol@example.com', status: 'ADDED', addedAt: '2026-10-17T01:00:00Z', sentCount: 0,
          lastSentAt: null, startedAt: null, answeredAt: null, score: null, reward: null },
      ],
      processes: {
        QB_INVITATION_ADD: [{ status: 200, body: { output: { publicationId: 'p1', added: 2, skipped: 1, total: 5 } } }],
        QB_INVITATION_SEND: [{ status: 200, body: { output: { publicationId: 'p1', queued: 3, remainingToday: 1997 } } }],
        QB_INVITATION_REMOVE: [{ status: 200, body: { output: { invitationId: 'i3' } } }],
      },
    })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const section = await screen.findByTestId('invitations')
    // The dictionary's label replaces the code once the server answered.
    await waitFor(() => expect(screen.getByTestId('summary-audience')).toHaveTextContent('Invited e-mail addresses only'))
    expect(screen.queryByText('Regions')).toBeNull()
    expect(await within(section).findByTestId('invitations-summary'))
      .toHaveTextContent('3 invited · 1 not yet sent · 1 sent · 0 started · 1 answered')
    const rows = await within(section).findAllByTestId('invitation-row')
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['SENT', 'ANSWERED', 'ADDED'])
    expect(within(rows[1]).getByTestId('invitation-answer')).toHaveTextContent('score 2')
    // An answered or started invitation stays; the others may go.
    expect(within(rows[1]).queryByTestId('invitation-remove')).toBeNull()
    expect(within(rows[2]).getByTestId('invitation-remove')).toBeInTheDocument()

    await userEvent.type(within(section).getByTestId('invitations-addresses'), 'Dan@example.com, eve@example.com\nalice@example.com')
    await userEvent.click(within(section).getByTestId('invitations-add'))
    await waitFor(() => expect(processCalls('QB_INVITATION_ADD')).toEqual([{ publicationId: 'p1',
      emails: ['Dan@example.com', 'eve@example.com', 'alice@example.com'] }]))
    expect(within(section).getByTestId('invitations-addresses')).toHaveValue('')

    await userEvent.click(within(section).getByTestId('invitations-send'))
    await userEvent.click(await screen.findByRole('button', { name: 'Send the invitations' }))
    await waitFor(() => expect(processCalls('QB_INVITATION_SEND')).toEqual([{ publicationId: 'p1' }]))

    await userEvent.click(within(rows[2]).getByTestId('invitation-remove'))
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    await waitFor(() => expect(processCalls('QB_INVITATION_REMOVE')).toEqual([{ invitationId: 'i3' }]))
  })
})

describe('the mail server (Q29)', () => {
  it('saves the settings, sends the test mail and shows the server verified', async () => {
    const { processCalls } = server({ mailServer: null, processes: {
      QB_MAIL_SERVER_SET: [{ status: 200, body: { output: { sponsorId: 'u2', verified: true, error: null,
        testedAt: '2026-10-20T00:00:00Z', sentTo: 'sara@example.com' } } }],
    } })
    window.history.replaceState(null, '', '/mail-server')
    render(<App />)
    expect(await screen.findByTestId('mail-server-status')).toHaveAttribute('data-status', 'NONE')
    await userEvent.type(screen.getByTestId('mail-server-host'), 'smtp.example.com')
    await userEvent.type(screen.getByTestId('mail-server-username'), 'shop')
    await userEvent.type(screen.getByTestId('mail-server-password'), 'secret-1')
    await userEvent.type(screen.getByTestId('mail-server-from'), 'shop@example.com')
    await userEvent.type(screen.getByTestId('mail-server-from-name'), 'The Shop')
    await userEvent.click(screen.getByTestId('mail-server-save'))
    await waitFor(() => expect(processCalls('QB_MAIL_SERVER_SET')).toEqual([{ host: 'smtp.example.com', port: 587,
      tls: 'STARTTLS', username: 'shop', password: 'secret-1', fromAddress: 'shop@example.com', fromName: 'The Shop' }]))
    expect(await screen.findByTestId('mail-server-result')).toHaveAttribute('data-verified', 'true')
    expect(screen.getByTestId('mail-server-result')).toHaveTextContent('sent to sara@example.com')
    // The password is never kept on the page.
    expect(screen.getByTestId('mail-server-password')).toHaveValue('')
  })

  it('shows the first save\'s answer when the test mail did not leave (the row appears with the save)', async () => {
    const { processCalls } = server({ mailServer: null, processes: {
      QB_MAIL_SERVER_SET: [{ status: 200, body: { output: { sponsorId: 'u2', verified: false,
        error: 'Could not connect to host', testedAt: '2026-10-21T00:00:00Z', sentTo: null } } }],
    } })
    window.history.replaceState(null, '', '/mail-server')
    render(<App />)
    expect(await screen.findByTestId('mail-server-status')).toHaveAttribute('data-status', 'NONE')
    await userEvent.type(screen.getByTestId('mail-server-host'), 'smtp.example.com')
    await userEvent.type(screen.getByTestId('mail-server-username'), 'shop')
    await userEvent.type(screen.getByTestId('mail-server-password'), 'secret-1')
    await userEvent.type(screen.getByTestId('mail-server-from'), 'shop@example.com')
    await userEvent.click(screen.getByTestId('mail-server-save'))
    await waitFor(() => expect(processCalls('QB_MAIL_SERVER_SET')).toHaveLength(1))
    // The row now exists, so the status changes; the answer to this save stays on the page.
    expect(await screen.findByTestId('mail-server-status')).toHaveAttribute('data-status', 'FAILED')
    expect(await screen.findByTestId('mail-server-result')).toHaveAttribute('data-verified', 'false')
    expect(screen.getByTestId('mail-server-result')).toHaveTextContent('Could not connect to host')
    // The settings typed stay in the form, the password field is blank: the stored one is kept.
    expect(screen.getByTestId('mail-server-host')).toHaveValue('smtp.example.com')
    expect(screen.getByTestId('mail-server-password')).toHaveValue('')
  })

  it('shows the server\'s answer when the test mail did not leave, and the refusal of a setting on its field', async () => {
    server({ mailServer: { ...VERIFIED_SERVER, verifiedAt: null, lastError: '535 Authentication failed' },
      processes: { QB_MAIL_SERVER_SET: [
        { status: 422, body: { violations: [{ field: 'host', ruleCode: 'QB_MAIL_SERVER_INVALID',
          message: 'The platform cannot use this setting: host must be a public host name, not localhost.' }] } },
        { status: 200, body: { output: { sponsorId: 'u2', verified: false, error: 'Could not connect to host',
          testedAt: '2026-10-21T00:00:00Z', sentTo: null } } },
      ] } })
    window.history.replaceState(null, '', '/mail-server')
    render(<App />)
    expect(await screen.findByTestId('mail-server-status')).toHaveAttribute('data-status', 'FAILED')
    expect(screen.getByTestId('mail-server-status')).toHaveTextContent('535 Authentication failed')
    // The stored settings are shown; the password is not asked again.
    expect(screen.getByTestId('mail-server-host')).toHaveValue('smtp.example.com')
    expect(screen.getByTestId('mail-server-password')).not.toBeRequired()
    await userEvent.click(screen.getByTestId('mail-server-save'))
    const host = screen.getByTestId('mail-server-host')
    await waitFor(() => expect(host).toHaveAttribute('aria-invalid', 'true'))
    expect(host).toHaveAccessibleDescription(/public host name/)
    await userEvent.click(screen.getByTestId('mail-server-save'))
    expect(await screen.findByTestId('mail-server-result')).toHaveAttribute('data-verified', 'false')
    expect(screen.getByTestId('mail-server-result')).toHaveTextContent('Could not connect to host')
  })

  it('removes the server after confirming', async () => {
    const { processCalls } = server({ mailServer: VERIFIED_SERVER, processes: {
      QB_MAIL_SERVER_REMOVE: [{ status: 200, body: { output: { sponsorId: 'u2' } } }] } })
    window.history.replaceState(null, '', '/mail-server')
    render(<App />)
    expect(await screen.findByTestId('mail-server-status')).toHaveAttribute('data-status', 'VERIFIED')
    await userEvent.click(screen.getByTestId('mail-server-remove'))
    await userEvent.click(await screen.findByRole('button', { name: 'Remove the mail server' }))
    await waitFor(() => expect(processCalls('QB_MAIL_SERVER_REMOVE')).toEqual([{}]))
  })

  it('holds the invitations of a live publication until a server is verified', async () => {
    const live = { ...DRAFT, status: 'LIVE', audience: 'INVITED', regions: 'GLOBAL', approvedAt: '2026-10-15T00:00:00Z' }
    server({ publication: live, mailServer: null, invitations: [
      { invitationId: 'i3', email: 'carol@example.com', status: 'ADDED', addedAt: '2026-10-17T01:00:00Z', sentCount: 0,
        lastSentAt: null, startedAt: null, answeredAt: null, score: null, reward: null }] })
    window.history.replaceState(null, '', '/publications/p1')
    render(<App />)
    const section = await screen.findByTestId('invitations')
    expect(await within(section).findByTestId('invitations-needs-server')).toHaveTextContent('own mail server')
    expect(within(section).getByTestId('invitations-send')).toBeDisabled()
    await userEvent.click(within(section).getByRole('link', { name: 'Set up the mail server' }))
    expect(window.location.pathname).toBe('/mail-server')
  })
})
