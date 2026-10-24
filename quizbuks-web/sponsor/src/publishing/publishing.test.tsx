import { configureClient, session } from '@jabiz/client'
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
  publicationId: 'p1', sponsorId: 'u2', quizId: 'q1', versionId: 'v1', versionLabel: 'v1.0', name: 'Coffee week',
  regions: 'GLOBAL', startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z', timeLimitSec: 300,
  passScore: 2, budgetCap: null, maxWinners: null, fullScore: 2, maxReward: null, maxSpend: null, tierCount: 0,
  status: 'DRAFT', rejectReason: null, rejectCode: null, revision: 3, editedAt: '2026-10-14T01:00:00Z',
}
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
}

/** The server as the console sees it, for a signed-in, approved sponsor. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const publication = { current: options.publication === undefined ? { ...DRAFT } : options.publication }
  const turns = new Map<string, number>()
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
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
      case '/api/queries/qb.sponsor.quiz-versions':
        return json(200, { items: options.versions ?? VERSIONS, offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name: string) =>
    posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { posted, processCalls, publication }
}

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
      regions: [], startAt: null, endAt: null, timeLimitSec: null, passScore: null, budgetCap: null,
      maxWinners: null, tiers: [], baseRevision: null }])
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
    const regions = await screen.findByTestId('publication-regions')
    await userEvent.click(await within(regions).findByRole('checkbox', { name: 'Japan' }))
    await userEvent.click(screen.getByTestId('tier-add'))
    const tier = screen.getByTestId('tier')
    expect(within(tier).getByTestId('tier-from')).toHaveValue('0')
    expect(within(tier).getByTestId('tier-to')).toHaveValue('2')
    await userEvent.type(within(tier).getByTestId('tier-reward'), '100')
    await userEvent.click(screen.getByTestId('publication-next'))

    const listed = await screen.findByTestId('publication-problems')
    expect(within(listed).getAllByTestId('problem').map((li) => li.textContent)).toEqual([
      'Reward tiers: The lowest tier must start at score 0.',
      'Starts: This field is required before submitting.',
      'Your sponsor account is not approved yet.',
    ])
    expect(screen.getByTestId('publication-submit')).toBeDisabled()
    expect(document.activeElement).toBe(screen.getByTestId('publication-step-title'))
    expect(processCalls('QB_PUBLICATION_SAVE')).toEqual([{ publicationId: 'p1', versionId: 'v1', name: 'Coffee week',
      regions: ['GLOBAL', 'JP'], startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-08T00:00:00Z', timeLimitSec: 300,
      passScore: 2, budgetCap: null, maxWinners: null, baseRevision: 3,
      tiers: [{ tierId: null, fromScore: 0, toScore: 2, reward: 100 }] }])
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
    window.history.replaceState(null, '', '/publications/p1?step=1')
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
    window.history.replaceState(null, '', '/publications/p1?step=1')
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
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/p/p1`))
    expect(shareLink('a b', 'https://quizbuks.com')).toBe('https://quizbuks.com/p/a%20b')
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
    expect(decodeURIComponent(image.getAttribute('src')!.split(',')[1])).toBe(`<svg>${window.location.origin}/p/p1</svg>`)
    expect(image).toHaveAccessibleName('QR code leading to Coffee week')
    expect(within(dialog).getByTestId('publication-qr-link')).toHaveTextContent(`${window.location.origin}/p/p1`)

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
