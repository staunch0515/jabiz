import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import { resetServerClock } from '../qb/legal'
import { draftDocument, isReady, stillRunning, toSiteDraft } from './site/api'
import { TransferMenu, TransferTools } from './TransferMenu'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const DRAFT = {
  profile: {
    format: 'quizbuks-profile', schema: 1, languages: ['ja', 'en'],
    basics: { company: 'アイニ株式会社', intro: { ja: 'ヘルメットの会社です。', en: 'A helmet company.' } },
    page: { handle: 'aini', headline: { ja: '毎日を安心して', en: 'Ride with confidence' }, website: 'https://www.aini.example/' },
    products: [{ name: { ja: '通勤ヘルメット', en: 'City helmet' }, price: 12800, currency: 'JPY',
      shopUrl: 'https://www.aini.example/shop/ah-100' }],
  },
  sources: { 'basics.company': 'https://www.aini.example/about', 'page.headline': 'javascript:alert(1)' },
  notFound: ['page.cover', 'basics.avatar', 'products[0].image', 'products[0].shopUrl', 'page.other'],
  dropped: [{ path: 'basics.intro.ja', reason: 'Contact details were removed' },
    { path: 'products[1]', reason: 'Price is not a number' }],
  translated: ['en'],
  handle: { handle: 'aini', taken: true, alternatives: ['aini-2', 'aini-3'] },
  materials: [{ title: 'About us', body: 'x', url: 'https://www.aini.example/about' },
    { title: 'Odd', body: 'y', url: 'javascript:alert(1)' }],
  questions: [],
}

const row = (status: string, extra: Record<string, unknown> = {}) => ({
  siteQuizId: null,
  aiJobId: 'j1', externalJobId: 'x1', status,
  params: JSON.stringify({ url: 'https://www.aini.example/', language: 'ja', languages: ['ja', 'en'] }),
  candidates: status === 'READY' ? JSON.stringify(DRAFT) : null, candidateCount: 0, invalidCount: 0, acceptedCount: null,
  failureCode: null, requestedAt: new Date().toISOString(), finishedAt: null,
  expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(), ...extra,
})

const PLAN = { basics: 'SAVE', page: 'PENDING', added: ['通勤ヘルメット'], changed: [], deleted: [], mailServer: 'NONE',
  files: [], links: [], linked: [] }

interface Options {
  enabled?: boolean
  /** The site rows in turn (the last repeats). */
  rows?: Record<string, unknown>[][]
  mustAccept?: boolean
  requestFails?: boolean
  quizFails?: boolean
  /** F-M9 b1: the quote of the request (none: not found, as before the credit). */
  quote?: Record<string, unknown>
}

function server(options: Options = {}) {
  const asked: { path: string; body: Record<string, unknown> | null }[] = []
  let calls = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    asked.push({ path, body })
    const page = (items: unknown[]) => json(200, { items, offset: 0, limit: items.length })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
          sessionPersistence: 'tab' })
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me', 'qb.ai.use'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/queries/qb.sponsor.ai-usage':
        return page([{ month: '2026-11', enabled: true, siteProfileEnabled: options.enabled ?? true, jobs: 0,
          failedJobs: 0, inputTokens: 0, outputTokens: 0, monthlyTokens: 2000000, reservedTokens: 0,
          remainingTokens: 2000000, dailyJobs: 20, jobsToday: 0, monthEnds: '2026-11-30T15:00:00Z',
          dayEnds: '2026-11-12T15:00:00Z', maxCount: 20 }])
      case '/api/queries/qb.sponsor.site-profile': {
        const pages = options.rows ?? [[]]
        return page(pages[Math.min(calls++, pages.length - 1)])
      }
      case '/api/queries/qb.me.consents':
        return page([{ kind: 'AI_NOTICE', versionId: 'v', versionNo: 2, title: { en: 'AI notice' }, changeSummary: null,
          requiresConsent: true, effectiveAt: '2026-10-01T00:00:00Z', toAccept: 2,
          accepted: options.mustAccept === false ? 2 : null, mustAccept: options.mustAccept ?? true }])
      case '/api/public/queries/qb.public.legal':
        return page([{ kind: 'AI_NOTICE', versionNo: 2, title: { en: 'AI notice' }, body: { en: 'What is sent.' },
          changeSummary: null, requiresConsent: true, effectiveAt: '2026-10-01T00:00:00Z', upcomingVersionNo: null,
          upcomingEffectiveAt: null }])
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ attributes: { contactName: 'Sara', company: 'Acme', reviewStatus: 'APPROVED',
          revision: 1 } }], offset: 0, limit: 1 })
      case '/api/processes/QB_SITE_PROFILE_QUOTE/latest':
        return options.quote ? json(200, { output: options.quote }) : json(404, {})
      case '/api/processes/QB_SITE_PROFILE_REQUEST/latest':
        return options.requestFails
          ? json(422, { title: 'Refused', violations: [{ field: 'siteAddress', ruleCode: 'QB_AI_SITE_URL_INVALID',
            message: 'Give the website\'s address as a host name.' }] })
          : json(200, { output: { aiJobId: 'j1', externalJobId: 'x1', status: 'QUEUED' } })
      case '/api/processes/QB_SITE_PROFILE_QUIZ/latest':
        return options.quizFails
          ? json(422, { title: 'Refused', violations: [{ field: 'aiJobId', ruleCode: 'QB_AI_SITE_QUIZ_MADE',
            message: 'A quiz was made from this draft already.' }] })
          : json(200, { output: { quizId: 'quiz-1', questionsJob: 'job-q', materials: 1, questions: 3 } })
      case '/api/processes/QB_AI_CANCEL/latest':
      case '/api/processes/QB_AI_DISCARD/latest':
        return json(200, { output: { aiJobId: 'j1', status: 'CANCELLED' } })
      case '/api/processes/QB_PROFILE_IMPORT_CHECK/latest':
        return json(200, { output: { plan: PLAN } })
      case '/api/processes/QB_PROFILE_IMPORT/latest':
        return json(200, { output: { plan: PLAN } })
      default:
        return json(404, {})
    }
  })
  return { of: (p: string) => asked.filter((a) => a.path === p).map((a) => a.body) }
}

function renderMenu() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><TransferTools><TransferMenu /></TransferTools></QueryClientProvider>)
}

async function openDialog() {
  await userEvent.click(screen.getByTestId('profile-transfer'))
  await userEvent.click(await screen.findByTestId('profile-site'))
  return screen.findByTestId('site-dialog')
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  resetServerClock()
})
afterEach(() => vi.restoreAllMocks())

describe('a draft from the website, as the page reads it', () => {
  it('reads the stored draft: the profile, where each field came from, what was not found, what was changed', () => {
    const draft = toSiteDraft(row('READY'))
    expect(draft.address).toBe('https://www.aini.example/')
    expect(draft.languages).toEqual(['ja', 'en'])
    expect(draft.profile?.basics).toMatchObject({ company: 'アイニ株式会社' })
    expect(draft.notFound).toEqual(['page.cover', 'basics.avatar', 'products[0].image', 'products[0].shopUrl', 'page.other'])
    expect(draft.dropped).toHaveLength(2)
    expect(draft.translated).toEqual(['en'])
    expect(draft.handle).toEqual({ handle: 'aini', taken: true, alternatives: ['aini-2', 'aini-3'] })
    expect(draft.materials).toBe(2)
    expect(toSiteDraft(row('QUEUED')).profile).toBeNull()
  })

  it('is being made for five minutes at most, and ready until it expires', () => {
    const now = Date.now()
    expect(stillRunning(toSiteDraft(row('QUEUED')), now)).toBe(true)
    expect(stillRunning(toSiteDraft(row('QUEUED', { requestedAt: new Date(now - 6 * 60_000).toISOString() })), now)).toBe(false)
    expect(stillRunning(toSiteDraft(row('READY')), now)).toBe(false)
    expect(isReady(toSiteDraft(row('READY')), now)).toBe(true)
    expect(isReady(toSiteDraft(row('READY', { expiresAt: new Date(now - 1000).toISOString() })), now)).toBe(false)
    expect(isReady(toSiteDraft(row('FAILED')), now)).toBe(false)
  })

  it('becomes a package for the usual check with the account\'s contact name and the chosen link name', () => {
    const draft = toSiteDraft(row('READY'))
    const document = draftDocument(draft, 'Sara', 'aini-2', new Date('2026-11-12T00:00:00Z'))
    expect(document.format).toBe('quizbuks-profile')
    expect(document.basics).toMatchObject({ contactName: 'Sara', company: 'アイニ株式会社' })
    expect(document.page).toMatchObject({ handle: 'aini-2', website: 'https://www.aini.example/' })
    expect(document.languages).toEqual(['ja', 'en'])
    expect(document.files).toEqual({})
    expect(draftDocument(draft, 'Sara').page).toMatchObject({ handle: 'aini' })
  })
})

describe('the menu item and the dialog', () => {
  it('is in the menu only where drafting from a website is on', async () => {
    server({ enabled: false })
    renderMenu()
    await userEvent.click(screen.getByTestId('profile-transfer'))
    await screen.findByTestId('profile-export')
    expect(screen.queryByTestId('profile-site')).toBeNull()
  })

  it('needs the address, something to draft, the declaration and the notice before it asks', async () => {
    const calls = server()
    renderMenu()
    const dialog = await openDialog()
    await within(dialog).findByTestId('site-form')
    await userEvent.click(within(dialog).getByTestId('site-submit'))
    const problems = await within(dialog).findByTestId('site-problems')
    expect(problems).toHaveTextContent('Enter the address of your website.')
    expect(problems).toHaveTextContent('Confirm that the website is yours')
    expect(problems).toHaveTextContent('Agree to the AI notice')
    expect(calls.of('/api/processes/QB_SITE_PROFILE_REQUEST/latest')).toHaveLength(0)
    await expectAccessible()
  })

  it('asks with the address, languages, choices, the declaration and the notice\'s version', async () => {
    const calls = server({ rows: [[], [row('QUEUED')]] })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.type(await within(dialog).findByTestId('site-address'), 'https://www.aini.example/')
    await userEvent.click(within(dialog).getByTestId('site-other-ja'))
    await userEvent.click(within(dialog).getByTestId('site-include-quiz'))
    await userEvent.click(within(dialog).getByTestId('site-rights'))
    await userEvent.click(await within(dialog).findByTestId('ai-notice-agree'))
    await userEvent.click(within(dialog).getByTestId('site-submit'))
    await waitFor(() => expect(calls.of('/api/processes/QB_SITE_PROFILE_REQUEST/latest')).toHaveLength(1))
    expect(calls.of('/api/processes/QB_SITE_PROFILE_REQUEST/latest')[0]).toMatchObject({
      siteAddress: 'https://www.aini.example/', otherLanguages: ['ja'], profile: true, products: true, quiz: false,
      rightsDeclared: true, aiNoticeVersionNo: 2 })
    // The reading runs: the page says so, can be left, and can be cancelled.
    expect(await within(dialog).findByTestId('site-running')).toHaveTextContent('one to three minutes')
    await userEvent.click(within(dialog).getByTestId('site-cancel'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_CANCEL/latest')).toEqual([{ aiJobId: 'j1' }]))
  })

  it('shows the server\'s reason when the request is refused', async () => {
    server({ requestFails: true, mustAccept: false })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.type(await within(dialog).findByTestId('site-address'), 'x')
    await userEvent.click(within(dialog).getByTestId('site-rights'))
    await userEvent.click(within(dialog).getByTestId('site-submit'))
    expect(await within(dialog).findByTestId('site-problems')).toHaveTextContent('siteAddress: Give the website')
  })

  it.each([
    ['QB_AI_SITE_UNREACHABLE', 'could not be opened'],
    ['QB_AI_SITE_ROBOTS', 'robots.txt'],
    ['QB_AI_SITE_NO_TEXT', 'almost no text'],
    ['QB_AI_SITE_TIMEOUT', 'took too long'],
    ['QB_AI_NO_CANDIDATES', 'found nothing usable'],
    ['EXTERNAL_CALL_FAILED', 'could not be made'],
  ])('says why a failed reading failed (%s) and offers the prompt for an AI tool', async (code, text) => {
    server({ rows: [[row('FAILED', { failureCode: code, finishedAt: new Date().toISOString() })]] })
    renderMenu()
    const dialog = await openDialog()
    const failed = await within(dialog).findByTestId('site-failed')
    expect(failed).toHaveTextContent(text)
    expect(within(dialog).getByTestId('site-failed-prompt')).toBeInTheDocument()
    // The form stays, so that the sponsor can try again.
    expect(within(dialog).getByTestId('site-form')).toBeInTheDocument()
  })

  it('keeps a ready draft for the sponsor and discards it on request', async () => {
    const calls = server({ rows: [[row('READY')]] })
    renderMenu()
    const dialog = await openDialog()
    expect(await within(dialog).findByTestId('site-ready')).toHaveTextContent('A draft from https://www.aini.example/ is ready.')
    await userEvent.click(within(dialog).getByTestId('site-discard'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_DISCARD/latest')).toEqual([{ aiJobId: 'j1' }]))
  })
})

describe('the cost of drafting from a website (F-M9 b1)', () => {
  const ENOUGH = { charged: true, maxYen: '880', availableYen: '5000', enough: true, shortYen: '0', minPurchase: null }
  const SHORT = { charged: true, maxYen: '880', availableYen: '100', enough: false, shortYen: '780', minPurchase: '1000' }

  it('says what it may cost once there is an address, and lets it be sent when the credit covers it', async () => {
    const calls = server({ quote: ENOUGH })
    renderMenu()
    const dialog = await openDialog()
    // Nothing is asked before there is an address.
    await within(dialog).findByTestId('site-form')
    expect(calls.of('/api/processes/QB_SITE_PROFILE_QUOTE/latest')).toHaveLength(0)
    await userEvent.type(within(dialog).getByTestId('site-address'), 'https://www.aini.example/')
    expect(await within(dialog).findByTestId('ai-quote')).toHaveTextContent(
      'This request may cost up to 880 Kudos; 5,000 Kudos available.')
    expect(calls.of('/api/processes/QB_SITE_PROFILE_QUOTE/latest').at(-1)).toMatchObject({
      siteAddress: 'https://www.aini.example/', profile: true, products: true, quiz: true })
    expect(within(dialog).getByTestId('site-submit')).toBeEnabled()
    await expectAccessible()
  })

  it('asks only when the text looks like a web address', async () => {
    const calls = server({ quote: ENOUGH })
    renderMenu()
    const dialog = await openDialog()
    const address = await within(dialog).findByTestId('site-address')
    await userEvent.type(address, 'abc')
    await new Promise((resolve) => setTimeout(resolve, 700))
    expect(calls.of('/api/processes/QB_SITE_PROFILE_QUOTE/latest')).toHaveLength(0)
    expect(within(dialog).queryByTestId('ai-quote')).toBeNull()
    for (const bad of ['http://127.0.0.1/', 'https://user@www.aini.example/', 'https://www.aini.example:8443/', 'ftp://www.aini.example/']) {
      await userEvent.clear(address)
      await userEvent.type(address, bad)
      await new Promise((resolve) => setTimeout(resolve, 700))
      expect(calls.of('/api/processes/QB_SITE_PROFILE_QUOTE/latest'), bad).toHaveLength(0)
    }
    await userEvent.clear(address)
    await userEvent.type(address, 'https://www.example.com/')
    expect(await within(dialog).findByTestId('ai-quote')).toBeInTheDocument()
    expect(calls.of('/api/processes/QB_SITE_PROFILE_QUOTE/latest').length).toBeGreaterThan(0)
  })

  it('says what is short with a way to buy, and does not let the request be sent', async () => {
    const calls = server({ quote: SHORT })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.type(await within(dialog).findByTestId('site-address'), 'https://www.aini.example/')
    expect(await within(dialog).findByTestId('ai-quote-short')).toHaveTextContent('780 Kudos short')
    expect(within(dialog).getByTestId('ai-quote-buy')).toHaveAttribute('href', expect.stringContaining('/money/ai'))
    expect(within(dialog).getByTestId('site-submit')).toBeDisabled()
    expect(calls.of('/api/processes/QB_SITE_PROFILE_REQUEST/latest')).toHaveLength(0)
    await expectAccessible()
  })
})

describe('a request that stays queued', () => {
  it('can be cancelled after the page has given up waiting, and the form that would be refused is not shown', async () => {
    const stale = new Date(Date.now() - 6 * 60_000).toISOString()
    const calls = server({ rows: [[row('QUEUED', { requestedAt: stale })]] })
    renderMenu()
    const dialog = await openDialog()
    expect(await within(dialog).findByTestId('site-slow')).toBeInTheDocument()
    expect(within(dialog).queryByTestId('site-address')).toBeNull()
    await userEvent.click(within(dialog).getByTestId('site-cancel'))
    await waitFor(() => expect(calls.of('/api/processes/QB_AI_CANCEL/latest')).toEqual([{ aiJobId: 'j1' }]))
  })
})

describe('reviewing the draft', () => {
  async function review() {
    const calls = server({ rows: [[row('READY')]] })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.click(await within(dialog).findByTestId('site-review'))
    const confirmation = await screen.findByTestId('profile-import-dialog')
    await within(confirmation).findByTestId('profile-import-plan')
    return { calls, confirmation }
  }

  it('goes through the usual check with the account\'s contact name, never one from the website', async () => {
    const { calls } = await review()
    const check = calls.of('/api/processes/QB_PROFILE_IMPORT_CHECK/latest')[0] as { document: Record<string, unknown> }
    expect(check.document.basics).toMatchObject({ contactName: 'Sara', company: 'アイニ株式会社' })
    expect(check.document.page).toMatchObject({ handle: 'aini' })
  })

  it('shows each field with its source, marks machine-translated texts, and lists what was not found or changed', async () => {
    const { confirmation } = await review()
    const company = within(confirmation).getAllByTestId('site-field').find((f) => f.dataset.path === 'basics.company')!
    expect(within(company).getByTestId('site-source')).toHaveAttribute('href', 'https://www.aini.example/about')
    const intro = within(confirmation).getAllByTestId('site-field').find((f) => f.dataset.path === 'basics.intro')!
    // Only the English text is the AI's translation.
    expect(within(intro).getAllByTestId('site-machine')).toHaveLength(1)
    expect(intro).toHaveTextContent('A helmet company. (machine-translated)')
    // A source that is not an https address is never a link.
    const headline = within(confirmation).getAllByTestId('site-field').find((f) => f.dataset.path === 'page.headline')!
    expect(within(headline).queryByTestId('site-source')).toBeNull()
    expect(headline).toHaveTextContent('did not say which page')
    // Fields are named as the sponsor knows them, never by their path.
    expect(within(confirmation).getByTestId('site-not-found')).toHaveTextContent('Cover image of your page')
    expect(within(confirmation).getByTestId('site-not-found')).toHaveTextContent('Your logo or picture')
    expect(within(confirmation).getByTestId('site-not-found')).toHaveTextContent('Product 1: image')
    expect(within(confirmation).getByTestId('site-not-found')).toHaveTextContent('Product 1: link to buy')
    expect(within(confirmation).getByTestId('site-not-found')).toHaveTextContent('another detail')
    expect(within(confirmation).getByTestId('site-not-found')).not.toHaveTextContent('page.cover')
    const dropped = within(confirmation).getByTestId('site-dropped')
    expect(dropped).toHaveTextContent('About you: a person\'s e-mail address, phone number or social account was removed')
    expect(dropped).toHaveTextContent('Product 2: Price is not a number')
    await expectAccessible()
  })

  it('names the draft when it imports, so that its request to review is closed with it', async () => {
    const { calls, confirmation } = await review()
    await userEvent.click(within(confirmation).getByTestId('site-handle-aini-2'))
    await waitFor(() => expect(within(screen.getByTestId('profile-import-dialog')).getByTestId('profile-import-confirm'))
      .toBeEnabled())
    await userEvent.click(within(screen.getByTestId('profile-import-dialog')).getByTestId('profile-import-confirm'))
    await waitFor(() => expect(calls.of('/api/processes/QB_PROFILE_IMPORT/latest')).toHaveLength(1))
    expect(calls.of('/api/processes/QB_PROFILE_IMPORT/latest')[0]).toMatchObject({ siteDraftId: 'j1' })
  })

  it('holds the import back while the drafted link name is another Sponsor\'s, and checks again with the chosen one', async () => {
    const { calls, confirmation } = await review()
    expect(within(confirmation).getByTestId('site-handle')).toHaveTextContent('"aini" is another Sponsor\'s')
    expect(within(confirmation).getByTestId('profile-import-confirm')).toBeDisabled()
    await userEvent.click(within(confirmation).getByTestId('site-handle-aini-2'))
    await waitFor(() => expect(calls.of('/api/processes/QB_PROFILE_IMPORT_CHECK/latest')).toHaveLength(2))
    const second = calls.of('/api/processes/QB_PROFILE_IMPORT_CHECK/latest')[1] as { document: { page: { handle: string } } }
    expect(second.document.page.handle).toBe('aini-2')
    await waitFor(() => expect(within(screen.getByTestId('profile-import-dialog')).getByTestId('profile-import-confirm'))
      .toBeEnabled())
  })
})

describe('the "get to know us" quiz', () => {
  async function review(options: Options) {
    const calls = server({ rows: [[row('READY', options.quizFails ? { siteQuizId: 'quiz-9' } : {})]], ...options })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.click(await within(dialog).findByTestId('site-review'))
    const confirmation = await screen.findByTestId('profile-import-dialog')
    await within(confirmation).findByTestId('site-quiz')
    return { calls, confirmation }
  }

  it('makes the quiz draft from the articles on request; the questions wait for review in the quiz', async () => {
    const { calls, confirmation } = await review({})
    expect(within(confirmation).getByTestId('site-quiz')).toHaveTextContent('2 articles and 0 questions')
    await userEvent.click(within(confirmation).getByTestId('site-quiz-make'))
    await waitFor(() => expect(calls.of('/api/processes/QB_SITE_PROFILE_QUIZ/latest')).toEqual([{ aiJobId: 'j1' }]))
  })

  it('lists the articles the quiz will have, each with its page as a link only when it is an https address', async () => {
    const { confirmation } = await review({})
    const articles = within(confirmation).getByTestId('site-articles')
    expect(articles).toHaveTextContent('About us')
    expect(articles).toHaveTextContent('Odd')
    const links = within(articles).getAllByTestId('site-article-source')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAttribute('href', 'https://www.aini.example/about')
  })

  it('does not offer a second quiz from the same draft', async () => {
    const { confirmation } = await review({ quizFails: true })
    expect(within(confirmation).getByTestId('site-quiz-made')).toHaveTextContent('A quiz was made from this draft')
    expect(within(confirmation).getByTestId('site-quiz-open')).toHaveAttribute('href', expect.stringContaining('quiz-9'))
    expect(within(confirmation).queryByTestId('site-quiz-make')).toBeNull()
  })

  it('shows the server\'s reason when the quiz cannot be made', async () => {
    const calls = server({ rows: [[row('READY')]], quizFails: true })
    renderMenu()
    const dialog = await openDialog()
    await userEvent.click(await within(dialog).findByTestId('site-review'))
    const confirmation = await screen.findByTestId('profile-import-dialog')
    await userEvent.click(await within(confirmation).findByTestId('site-quiz-make'))
    expect(await within(confirmation).findByTestId('site-quiz-problems')).toHaveTextContent('aiJobId: A quiz was made')
    expect(calls.of('/api/processes/QB_SITE_PROFILE_QUIZ/latest')).toHaveLength(1)
  })
})
