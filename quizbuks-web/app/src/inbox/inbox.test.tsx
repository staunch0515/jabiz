import { configureClient, session } from '@jabiz/client'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { APP } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { badgeText, dayEnd, dayStart, inboxItemOf, itemPath, targetOf, UNREAD_INTERVAL } from './api'
import { inboxMessages } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const texts = (en: string) => ({ en, ja: `${en}（日本語）`, zh: `${en}（中文）` })

const MESSAGE = {
  itemKind: 'MESSAGE', itemId: 'm1', type: 'REWARD', category: 'REWARD', kind: 'REWARD_PAID', sender: null,
  sponsorName: null, title: texts('You received 100 Kudos'),
  body: texts('Your reward for "Capitals [Claim](https://evil.example/x)" is in your wallet.'),
  markdown: false, publicationId: 'p1', action: 'RESULT', actionId: 'a1', at: '2026-10-18T01:00:00Z', expiresAt: null,
  readAt: null, clickedAt: null, unread: true,
}
const NOTICE = {
  itemKind: 'BROADCAST', itemId: 'b1', type: 'PROMO', category: 'PROMO', kind: null, sender: 'SPONSOR',
  sponsorName: 'Capitals Inc', title: { ja: '新しいクイズ' }, body: { ja: '**太字** <script>alert(1)</script>\n二行目' },
  markdown: false, publicationId: 'p2', action: null, actionId: null, at: '2026-10-17T01:00:00Z',
  expiresAt: '2026-10-25T00:00:00Z', readAt: null, clickedAt: null, unread: true,
}
const ANNOUNCEMENT = {
  itemKind: 'BROADCAST', itemId: 'b2', type: 'SYSTEM', category: 'SYSTEM', kind: null, sender: 'PLATFORM',
  sponsorName: null, title: texts('Maintenance'), body: { en: 'We **update** on Sunday.' }, markdown: true,
  publicationId: null, action: null, actionId: null, at: '2026-10-16T01:00:00Z', expiresAt: '2026-11-16T01:00:00Z',
  readAt: '2026-10-16T02:00:00Z', clickedAt: null, unread: false,
}

interface Server {
  unread?: { messages: number; broadcasts: number }
  items?: Record<string, unknown>[]
}

/** The server as the inbox sees it, for a verified taker in Japan. Records what was posted. */
function server(options: Server = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  const items = options.items ?? [MESSAGE, NOTICE, ANNOUNCEMENT]
  let promoOff = false
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Mei', email: 'mei@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Mei', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbInboxState/query':
        return json(200, { items: [{ id: 'u1', version: 1, attributes: { promoOff, readAllBefore: null } }],
          offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [options.unread ?? { messages: 1, broadcasts: 2 }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox': {
        const params = (body?.params ?? {}) as Record<string, unknown>
        const found = params.itemId ? items.filter((item) => item.itemId === params.itemId) : items
        return json(200, { items: found, offset: 0, limit: 20 })
      }
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_MESSAGE_MARK/latest':
        return json(200, { output: { changed: true } })
      case '/api/processes/QB_INBOX_READ_ALL/latest':
        return json(200, { output: { promoOff, readAllBefore: '2026-10-18T02:00:00Z' } })
      case '/api/processes/QB_INBOX_SETTINGS_SAVE/latest':
        promoOff = body?.promoOff === true
        return json(200, { output: { promoOff, readAllBefore: null } })
      default:
        return json(404, {})
    }
  })
  const processCalls = (name?: string) => posted.filter((p) => p.path.startsWith('/api/processes/')
    && (!name || p.path === `/api/processes/${name}/latest`))
  const queryBodies = (id: string) => posted.filter((p) => p.path === `/api/queries/${id}`).map((p) => p.body)
  return { processCalls, queryBodies }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('the inbox helpers', () => {
  it('name the pages, where an item leads and the badge', () => {
    expect(itemPath('BROADCAST', 'b1')).toBe('/inbox/broadcast/b1')
    expect(inboxItemOf('/inbox/message/m1')).toEqual({ kind: 'MESSAGE', id: 'm1' })
    expect(inboxItemOf('/inbox/other/m1')).toBeNull()
    expect(inboxItemOf('/inbox/message/%E0%A4%A')).toBeNull()
    expect(targetOf({ itemKind: 'MESSAGE', action: 'RESULT', actionId: 'a1', publicationId: 'p1' })).toBe('/result/a1')
    expect(targetOf({ itemKind: 'MESSAGE', action: 'PAYOUT', actionId: null, publicationId: null }))
      .toBe('/wallet/payout')
    expect(targetOf({ itemKind: 'BROADCAST', action: null, actionId: null, publicationId: 'p2' })).toBe('/p/p2')
    expect(targetOf({ itemKind: 'BROADCAST', action: null, actionId: null, publicationId: null })).toBeNull()
    expect(badgeText(7)).toBe('7')
    expect(badgeText(150)).toBe('99+')
    // A day of the device's calendar: from its start to the next day's.
    expect(new Date(dayEnd('2026-10-18')).getTime() - new Date(dayStart('2026-10-18')).getTime())
      .toBeGreaterThanOrEqual(23 * 3600_000)
  })

  it('have every text in every language', () => {
    const english = keyPaths(inboxMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(inboxMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(APP, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(APP, lng), lng).toEqual([])
    }
  })
})

describe('the badge', () => {
  it('shows the unread count on the fifth entry, its name saying it too, and "99+" past 99', async () => {
    server({ unread: { messages: 1, broadcasts: 2 } })
    window.history.replaceState(null, '', '/')
    render(<App />)
    const nav = await screen.findByRole('navigation', { name: 'App' })
    expect(within(nav).getAllByRole('link')).toHaveLength(5)
    expect(await screen.findByTestId('nav-inbox-badge')).toHaveTextContent('3')
    expect(screen.getByTestId('nav-inbox')).toHaveAccessibleName('Inbox, 3 unread')
  })

  it('says 99+ when there are more', async () => {
    server({ unread: { messages: 99, broadcasts: 60 } })
    window.history.replaceState(null, '', '/')
    render(<App />)
    expect(await screen.findByTestId('nav-inbox-badge')).toHaveTextContent('99+')
  })

  it('shows nothing when nothing is unread', async () => {
    const { queryBodies } = server({ unread: { messages: 0, broadcasts: 0 } })
    window.history.replaceState(null, '', '/')
    render(<App />)
    await waitFor(() => expect(queryBodies('qb.app.inbox-unread')).toHaveLength(1))
    expect(screen.queryByTestId('nav-inbox-badge')).toBeNull()
    expect(screen.getByTestId('nav-inbox')).toHaveAccessibleName('Inbox')
  })

  it('asks on entering, on coming back to the front and every five minutes, not more often', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { queryBodies } = server()
    window.history.replaceState(null, '', '/history')
    render(<App />)
    await waitFor(() => expect(queryBodies('qb.app.inbox-unread')).toHaveLength(1))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4 * 60_000)
    })
    expect(queryBodies('qb.app.inbox-unread')).toHaveLength(1)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(UNREAD_INTERVAL - 4 * 60_000 + 1_000)
    })
    await waitFor(() => expect(queryBodies('qb.app.inbox-unread')).toHaveLength(2))
    // Back to the front.
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await waitFor(() => expect(queryBodies('qb.app.inbox-unread')).toHaveLength(3))
    // Once, not again for the window's focus (review L4).
    act(() => {
      window.dispatchEvent(new Event('focus'))
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000)
    })
    expect(queryBodies('qb.app.inbox-unread')).toHaveLength(3)
  })
})

describe('the inbox', () => {
  it('lists the items, filters them on the server and marks them all read', async () => {
    const { processCalls, queryBodies } = server()
    window.history.replaceState(null, '', '/inbox')
    const user = userEvent.setup()
    render(<App />)
    const rows = await screen.findAllByTestId('inbox-row')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent('You received 100 Kudos')
    expect(rows[0]).toHaveTextContent('From QuizBuks')
    expect(within(rows[0]).getByTestId('inbox-unread')).toBeInTheDocument()
    // A notice written in Japanese only is shown so, marked as Japanese.
    expect(rows[1]).toHaveTextContent('新しいクイズ')
    expect(rows[1]).toHaveTextContent('From Capitals Inc')
    expect(rows[1].querySelector('[lang="ja"]')).not.toBeNull()
    expect(within(rows[2]).queryByTestId('inbox-unread')).toBeNull()
    expect(screen.getByTestId('nav-inbox')).toHaveAttribute('aria-current', 'page')
    expect(queryBodies('qb.app.inbox').at(-1)).toMatchObject({ params: { lang: 'en' } })
    // The device's clock is no parameter (review L1).
    expect(queryBodies('qb.app.inbox').at(-1)?.params).not.toHaveProperty('at')

    await user.type(screen.getByTestId('inbox-search'), 'Kudos{Enter}')
    await waitFor(() => expect(queryBodies('qb.app.inbox').at(-1)).toMatchObject({ params: { q: 'Kudos' } }))
    await user.click(screen.getByTestId('inbox-unread-only'))
    await waitFor(() => expect(queryBodies('qb.app.inbox').at(-1)).toMatchObject({ params: { unread: true } }))
    await user.click(screen.getByTestId('inbox-category'))
    await user.click(await screen.findByRole('option', { name: 'Promotions' }))
    await waitFor(() => expect(queryBodies('qb.app.inbox').at(-1)).toMatchObject({ params: { category: 'PROMO' } }))
    await user.type(screen.getByTestId('inbox-from'), '2026-10-01')
    await waitFor(() => expect(queryBodies('qb.app.inbox').at(-1)).toMatchObject({
      params: { from: dayStart('2026-10-01') } }))

    await user.click(screen.getByTestId('inbox-read-all'))
    await waitFor(() => expect(processCalls('QB_INBOX_READ_ALL')).toHaveLength(1))
    expect(await screen.findByTestId('inbox-read-all-done')).toHaveTextContent('Everything is marked as read.')
  })

  it('turns promotions off, and only promotions', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/inbox')
    const user = userEvent.setup()
    render(<App />)
    const toggle = await screen.findByTestId('inbox-promotions')
    await waitFor(() => expect(toggle).toBeEnabled())
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('inbox-settings')).toHaveTextContent('System messages, rewards and reminders always come.')
    await user.click(toggle)
    await waitFor(() => expect(processCalls('QB_INBOX_SETTINGS_SAVE')).toHaveLength(1))
    expect(processCalls('QB_INBOX_SETTINGS_SAVE')[0].body).toEqual({ promoOff: true })
    await waitFor(() => expect(screen.getByTestId('inbox-promotions')).toHaveAttribute('aria-checked', 'false'))
  })

  it('says so when there is nothing', async () => {
    server({ items: [] })
    window.history.replaceState(null, '', '/inbox')
    render(<App />)
    expect(await screen.findByTestId('inbox-empty')).toHaveTextContent('Rewards, transfers and news appear here.')
  })
})

describe('an item', () => {
  it('is read when opened and leads to its result', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/inbox/message/m1')
    const user = userEvent.setup()
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'You received 100 Kudos' })).toBeInTheDocument()
    // Review H2: a message is plain text; a publication's name with link syntax stays text, never a link.
    expect(screen.getByTestId('inbox-item-plain')).toHaveTextContent(
      'Your reward for "Capitals [Claim](https://evil.example/x)" is in your wallet.')
    expect(screen.queryByTestId('markdown-view')).toBeNull()
    expect(screen.queryAllByRole('link').filter((link) => link.getAttribute('href')?.startsWith('http'))).toEqual([])
    await waitFor(() => expect(processCalls('QB_MESSAGE_MARK')).toHaveLength(1))
    expect(processCalls('QB_MESSAGE_MARK')[0].body).toEqual({ kind: 'MESSAGE', id: 'm1', action: 'READ' })
    expect(screen.getByTestId('nav-inbox')).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByTestId('inbox-item-go'))
    await waitFor(() => expect(window.location.pathname).toBe('/result/a1'))
    expect(processCalls('QB_MESSAGE_MARK').at(-1)?.body).toEqual({ kind: 'MESSAGE', id: 'm1', action: 'CLICK' })
  })

  it('shows a sponsor\'s notice as plain text and records the click before the quiz', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/inbox/broadcast/b1')
    const user = userEvent.setup()
    render(<App />)
    const plain = await screen.findByTestId('inbox-item-plain')
    // Exactly as written: no Markdown, no HTML, no script.
    expect(plain.textContent).toBe('**太字** <script>alert(1)</script>\n二行目')
    expect(plain.querySelector('script, strong')).toBeNull()
    expect(screen.queryByTestId('markdown-view')).toBeNull()
    expect(screen.getByTestId('inbox-item-sender')).toHaveTextContent('From Capitals Inc')
    expect(screen.queryAllByRole('link').filter((link) => link.getAttribute('href')?.startsWith('http'))).toEqual([])
    await user.click(screen.getByTestId('inbox-item-go'))
    await waitFor(() => expect(window.location.pathname).toBe('/p/p2'))
    expect(processCalls('QB_MESSAGE_MARK').map((call) => call.body.action)).toEqual(['READ', 'CLICK'])
  })

  it('renders the platform\'s Markdown and is not marked again once read', async () => {
    const { processCalls } = server()
    window.history.replaceState(null, '', '/inbox/broadcast/b2')
    render(<App />)
    expect(await screen.findByTestId('markdown-view')).toContainHTML('<strong>update</strong>')
    expect(screen.queryByTestId('inbox-item-go')).toBeNull()
    expect(processCalls('QB_MESSAGE_MARK')).toHaveLength(0)
  })

  it('is gone when it left the inbox', async () => {
    server()
    window.history.replaceState(null, '', '/inbox/broadcast/withdrawn')
    render(<App />)
    expect(await screen.findByTestId('inbox-item-gone')).toHaveTextContent('This message is no longer in your inbox.')
  })
})
