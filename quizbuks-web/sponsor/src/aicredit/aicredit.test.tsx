import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { MOVEMENT_KINDS } from './api'
import { aiCreditMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const STANDING = { feeOn: true, remaining: 5000, reserved: 300, unbilledWithTax: 220, available: 4480,
  returnable: 4480, minPurchase: 1000, lowAmount: 500, low: false }

interface Options {
  standing?: Record<string, unknown>
  /** The refusal of buying, or giving back. */
  buy?: { status: number; body: unknown }
  giveBack?: { status: number; body: unknown }
  lots?: Record<string, unknown>[]
}

/** The server as the AI credit's page sees it; the standing moves on with a purchase or a return. */
function server(options: Options = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let standing = { ...STANDING, ...options.standing }
  const movements: Record<string, unknown>[] = [
    { movementId: 'm1', kind: 'CHARGE', amount: '-220', credit: '-20', cash: '-200', balance: '5000',
      movedAt: '2026-11-01T00:30:00Z' },
    { movementId: 'm0', kind: 'BUY', amount: '5220', credit: '1000', cash: '4220', balance: '5220',
      movedAt: '2026-10-20T01:00:00Z' },
  ]
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST' && path.startsWith('/api/processes/')) posted.push({ path, body })
    const page = (items: unknown[]) => json(200, { items, offset: 0, limit: 10, total: items.length })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u2', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.finance.read'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/processes/QB_AI_CREDIT_STANDING/latest':
        return json(200, { output: standing })
      case '/api/processes/QB_AI_CREDIT_BUY/latest':
        if (options.buy) return json(options.buy.status, options.buy.body)
        standing = { ...standing, remaining: Number(standing.remaining) + Number(body?.amount) }
        movements.unshift({ movementId: 'm2', kind: 'BUY', amount: String(body?.amount), credit: '400',
          cash: String(Number(body?.amount) - 400), balance: String(standing.remaining), movedAt: '2026-11-14T01:00:00Z' })
        return json(200, { output: { prepaymentId: 'p1', amount: body?.amount, credit: 400,
          cash: Number(body?.amount) - 400, balance: standing.remaining } })
      case '/api/processes/QB_AI_CREDIT_RETURN/latest':
        if (options.giveBack) return json(options.giveBack.status, options.giveBack.body)
        movements.unshift({ movementId: 'm3', kind: 'RETURN', amount: `-${body?.amount}`, credit: '0',
          cash: `-${body?.amount}`, balance: '0', movedAt: '2026-11-14T02:00:00Z' })
        standing = { ...standing, remaining: Number(standing.remaining) - Number(body?.amount), returnable: 0 }
        return json(200, { output: { amount: body?.amount } })
      case '/api/queries/qb.sponsor.wallet':
        return json(200, { items: [{ balance: '0', held: '0', cashBalance: '2000', cashHeld: '500',
          feeCreditBalance: '700' }], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.ai-credit-lots':
        return page(options.lots ?? [
          { subAccount: 'FEE_CREDIT', lotId: 'l1', amount: '1000', expiresAt: '2027-03-31T14:59:00Z', voidedAt: null },
          { subAccount: 'CASH', lotId: 'l2', amount: '4220', expiresAt: null, voidedAt: null },
          { subAccount: 'FEE_CREDIT', lotId: 'l3', amount: '300', expiresAt: '2026-10-01T00:00:00Z',
            voidedAt: '2026-10-01T00:05:00Z' }])
      case '/api/queries/qb.sponsor.ai-credit-jobs':
        return page([
          { aiJobId: 'j2', kind: 'QUESTIONS', status: 'READY', requestedAt: '2026-11-13T01:00:00Z',
            finishedAt: '2026-11-13T01:02:00Z', reservedYen: '0', feeYen: '200', feePeriod: '2026-11', charged: false },
          { aiJobId: 'j1', kind: 'TRANSLATION', status: 'ACCEPTED', requestedAt: '2026-10-13T01:00:00Z',
            finishedAt: '2026-10-13T01:02:00Z', reservedYen: '0', feeYen: '100', feePeriod: '2026-10', charged: true }])
      case '/api/queries/qb.sponsor.ai-credit-movements':
        return page(movements)
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:ai-job-kind':
        return json(200, [{ code: 'QUESTIONS', label: 'Questions' }, { code: 'TRANSLATION', label: 'Translation' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:ai-job-status':
        return json(200, [{ code: 'READY', label: 'Ready' }, { code: 'ACCEPTED', label: 'Accepted' }])
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:fee-sub-account':
        return json(200, [{ code: 'FEE_CREDIT', label: 'Granted credit' }, { code: 'CASH', label: 'Cash' }])
      default:
        return json(404, {})
    }
  })
  return { posted }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
  window.sessionStorage.clear()
})
afterEach(() => {
  vi.restoreAllMocks()
})

async function open() {
  window.history.replaceState(null, '', '/money/ai')
  render(<App />)
  return await screen.findByTestId('ai-credit-glance')
}

describe('the AI credit page (F-M9 b1)', () => {
  it('shows what the server gives: available, left, held, used not charged, and the four tables', async () => {
    server()
    await open()
    expect(screen.getByTestId('ai-credit-available')).toHaveTextContent('4,480 Kudos')
    expect(screen.getByTestId('ai-credit-remaining')).toHaveTextContent('5,000 Kudos')
    expect(screen.getByTestId('ai-credit-reserved')).toHaveTextContent('300 Kudos')
    expect(screen.getByTestId('ai-credit-unbilled')).toHaveTextContent('220 Kudos')
    expect(screen.queryByTestId('ai-credit-low')).toBeNull()
    expect(screen.queryByTestId('ai-credit-off')).toBeNull()
    const lots = await screen.findAllByTestId('ai-credit-lot')
    expect(lots.map((row) => row.getAttribute('data-sub-account'))).toEqual(['FEE_CREDIT', 'CASH', 'FEE_CREDIT'])
    expect(lots[0]).toHaveTextContent('Granted credit')
    expect(lots[1]).toHaveTextContent('Does not expire')
    const jobs = await screen.findAllByTestId('ai-credit-job')
    expect(jobs).toHaveLength(2)
    expect(jobs[0]).toHaveTextContent('Questions')
    expect(jobs[0]).toHaveTextContent('Waiting for the month\'s charge')
    expect(jobs[1]).toHaveTextContent('Charged')
    const moves = await screen.findAllByTestId('ai-credit-movement')
    expect(moves.map((row) => row.getAttribute('data-kind'))).toEqual(['CHARGE', 'BUY'])
    expect(moves[1]).toHaveTextContent('Bought')
    expect(document.body).not.toHaveTextContent(/JPY|yen/i)
    expect(screen.getByTestId('page-tab-ai')).toHaveAttribute('aria-current', 'page')
    await expectAccessible()
  })

  it('notices a low credit and says when the fee is off, where nothing can be bought', async () => {
    server({ standing: { low: true } })
    await open()
    expect(screen.getByTestId('ai-credit-low')).toBeInTheDocument()
    expect(screen.getByTestId('ai-credit-buy')).toBeInTheDocument()
    await expectAccessible()
  })

  it('has no buying while the fee is off, and says the AI is free', async () => {
    server({ standing: { feeOn: false, available: null, returnable: 0 } })
    await open()
    expect(screen.getByTestId('ai-credit-off')).toBeInTheDocument()
    expect(screen.queryByTestId('ai-credit-buy')).toBeNull()
    expect(screen.getByTestId('ai-credit-available')).toHaveTextContent('—')
    expect(screen.getByTestId('ai-credit-return')).toBeDisabled()
  })

  it('buys: the minimum and the account\'s funds are told, the server\'s split is shown and the tables follow', async () => {
    const { posted } = server()
    const user = userEvent.setup()
    await open()
    await user.click(screen.getByTestId('ai-credit-buy'))
    const dialog = await screen.findByTestId('ai-credit-buy-dialog')
    expect(within(dialog).getByTestId('ai-credit-buy-min')).toHaveTextContent('1,000 Kudos')
    expect(await within(dialog).findByTestId('ai-credit-buy-funds')).toHaveTextContent(
      'granted credit 700 Kudos, cash 1,500 Kudos')
    expect(dialog).toHaveTextContent('granted credit is used first, then the cash')
    await expectAccessible()
    await user.type(within(dialog).getByTestId('ai-credit-buy-amount'), '2000')
    await user.click(within(dialog).getByTestId('ai-credit-buy-submit'))
    const bought = await screen.findByTestId('ai-credit-bought')
    expect(bought).toHaveTextContent('2,000 Kudos')
    expect(bought).toHaveTextContent('400 Kudos from granted credit and 1,600 Kudos from cash')
    expect(posted.filter((p) => p.path.endsWith('QB_AI_CREDIT_BUY/latest'))).toEqual([{
      path: '/api/processes/QB_AI_CREDIT_BUY/latest', body: { amount: 2000, requestKey: expect.stringMatching(/-/) } }])
    await expectAccessible()
    await user.click(within(dialog).getByTestId('ai-credit-buy-close'))
    await waitFor(() => expect(screen.getByTestId('ai-credit-remaining')).toHaveTextContent('7,000 Kudos'))
    await waitFor(() => expect(screen.getAllByTestId('ai-credit-movement')).toHaveLength(3))
  })

  it('words the server\'s refusal of a purchase below the minimum', async () => {
    server({ buy: { status: 422, body: { title: 'Refused', violations: [{ ruleCode: 'QB_AI_CREDIT_MIN',
      message: 'The smallest purchase is 1,000.' }] } } })
    const user = userEvent.setup()
    await open()
    await user.click(screen.getByTestId('ai-credit-buy'))
    await user.type(await screen.findByTestId('ai-credit-buy-amount'), '10')
    await user.click(screen.getByTestId('ai-credit-buy-submit'))
    expect(await screen.findByRole('alert')).toHaveTextContent('The smallest purchase is 1,000.')
    expect(screen.queryByTestId('ai-credit-bought')).toBeNull()
  })

  it('refuses an amount that is not a whole number before asking the server', async () => {
    const { posted } = server()
    const user = userEvent.setup()
    await open()
    await user.click(screen.getByTestId('ai-credit-buy'))
    await user.type(await screen.findByTestId('ai-credit-buy-amount'), 'abc')
    await user.click(screen.getByTestId('ai-credit-buy-submit'))
    expect(await screen.findByText('Enter a whole amount above zero.')).toBeInTheDocument()
    expect(posted.filter((p) => p.path.endsWith('QB_AI_CREDIT_BUY/latest'))).toEqual([])
  })

  it('gives back all that may be given back, the number the server gave', async () => {
    const { posted } = server()
    const user = userEvent.setup()
    await open()
    await user.click(screen.getByTestId('ai-credit-return'))
    const dialog = await screen.findByTestId('ai-credit-return-dialog')
    expect(within(dialog).getByTestId('ai-credit-return-hint')).toHaveTextContent('up to 4,480 Kudos')
    await user.click(within(dialog).getByTestId('ai-credit-return-all'))
    expect(within(dialog).getByTestId('ai-credit-return-amount')).toHaveValue('4480')
    await expectAccessible()
    await user.click(within(dialog).getByTestId('ai-credit-return-submit'))
    expect(await screen.findByTestId('ai-credit-returned')).toHaveTextContent('4,480 Kudos')
    expect(posted.filter((p) => p.path.endsWith('QB_AI_CREDIT_RETURN/latest'))[0].body)
      .toEqual({ amount: 4480, requestKey: expect.any(String) })
    await user.click(within(dialog).getByTestId('ai-credit-return-close'))
    await waitFor(() => expect(screen.getByTestId('ai-credit-return')).toBeDisabled())
    await waitFor(() => expect(screen.getAllByTestId('ai-credit-movement').map((row) => row.getAttribute('data-kind')))
      .toContain('RETURN'))
  })

  it('cannot give back what is not known or nothing', async () => {
    server({ standing: { returnable: null, available: null } })
    await open()
    expect(screen.getByTestId('ai-credit-return')).toBeDisabled()
  })

  it('shows an empty state for each table', async () => {
    server({ lots: [] })
    await open()
    await waitFor(() => expect(screen.queryAllByTestId('ai-credit-lot')).toHaveLength(0))
    expect(await screen.findByText('No AI credit yet.')).toBeInTheDocument()
  })
})

describe('the AI credit texts', () => {
  it('have the same keys and placeholders in every language, none empty, with the agreed words', () => {
    const english = keyPaths(aiCreditMessages.en)
    const placeholders = (lng: 'en' | 'zh' | 'ja') => english.map((key) => {
      const text = key.split('.').reduce<unknown>((at, part) => (at as Record<string, unknown>)[part],
        aiCreditMessages[lng]) as string
      return [key, [...text.matchAll(/\{\{(\w+)\}\}/g)].map((m) => m[1]).sort()]
    })
    for (const lng of LANGUAGES) {
      expect(keyPaths(aiCreditMessages[lng]), lng).toEqual(english)
      expect(placeholders(lng), lng).toEqual(placeholders('en'))
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      expect(JSON.stringify(aiCreditMessages[lng]), lng).not.toMatch(/JPY|yen|円|日元|USD|\$|スポンサー|商户|商家|merchant|后台|console/i)
      for (const kind of MOVEMENT_KINDS) expect(aiCreditMessages[lng].aiCredit.movements.kinds[kind], kind).toBeTruthy()
    }
  })
})
