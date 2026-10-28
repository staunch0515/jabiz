import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App, { supportEmail } from './App'
import type { Shop } from './api'
import './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'shopify', selfRegistration: true, requireVerifiedEmail: false, appPath: '/shopify/',
  sessionPersistence: 'tab' }

function shop(overrides: Partial<Shop> = {}): Shop {
  return {
    shopId: 's1', shopDomain: 'demo.myshopify.com', shopName: null, connection: 'NONE', role: 'ADMIN', joined: true,
    reinstalled: false,
    members: [
      { userId: 'u1', staffId: '101', role: 'ADMIN', joinedAt: '2026-10-27T00:00:00Z' },
      { userId: 'u2', staffId: '102', role: 'MEMBER', joinedAt: '2026-10-27T01:00:00Z' },
    ],
    ...overrides,
  }
}

interface Server {
  /** The answers of QBS_SHOP_JOIN, one per call (the last repeats). */
  joins: Shop[]
  connect?: { status: number; body: unknown }
}

/** The server as the embedded pages see it; records what was posted. */
function server(options: Server) {
  const posted: { path: string; body: Record<string, unknown> | null }[] = []
  let joinCalls = 0
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/shopify':
        return json(200, ENTRY)
      case '/api/auth/external-token':
        return json(200, { status: 'SIGNED_IN', accessToken: 'a1', refreshToken: 'r1' })
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'demo.myshopify.com/101', roles: ['QBS_SHOP'],
          permissions: ['qbs.shop.use'], emailVerified: false })
      case '/api/processes/QBS_SHOP_JOIN/latest': {
        const answer = options.joins[Math.min(joinCalls, options.joins.length - 1)]
        joinCalls += 1
        return json(200, { output: answer, processSeqId: joinCalls })
      }
      case '/api/processes/QBS_SHOP_ADMIN_TRANSFER/latest':
        return json(200, { output: { shopId: 's1', adminUserId: 'u2' } })
      case '/api/processes/QBS_SHOP_CONNECT/latest':
        return options.connect ? json(options.connect.status, options.connect.body)
          : json(200, { output: { connection: 'CONNECTED', shopName: 'Demo' } })
      case '/api/processes/QBS_SHOP_CHECK/latest':
        return json(200, { output: { connection: 'RECONNECT', shopName: 'Demo' } })
      case '/api/processes/QBS_CAMPAIGN_LIST/latest':
        return json(200, { output: { campaigns: [], pool: { available: 0, issuedToday: 0, issuedWeek: 0 } } })
      case '/api/auth/logout':
        return json(204, {})
      default:
        return json(404, {})
    }
  })
  return posted
}

let tokens = 0

describe('QuizBuks for Shopify (S1)', () => {
  beforeAll(() => configureClient({ entry: 'shopify' }))

  beforeEach(() => {
    session.clear()
    window.sessionStorage.clear()
    tokens = 0
    window.shopify = { idToken: vi.fn(async () => `session-token-${++tokens}`) }
  })

  afterEach(() => {
    vi.restoreAllMocks()
    delete window.shopify
  })

  it('signs in with a session token, joins with a fresh one and shows the store', async () => {
    const posted = server({ joins: [shop()] })
    render(<App />)
    expect(await screen.findByText('Store: demo.myshopify.com')).toBeInTheDocument()
    expect(screen.getByText('You are the admin of this store in QuizBuks.')).toBeInTheDocument()
    expect(posted.find((p) => p.path === '/api/auth/external-token')?.body)
      .toEqual({ token: 'session-token-1', entry: 'shopify' })
    // Each session token is used once: the join gets a new one.
    expect(posted.find((p) => p.path === '/api/processes/QBS_SHOP_JOIN/latest')?.body)
      .toEqual({ sessionToken: 'session-token-2' })
    const rows = screen.getAllByRole('row')
    expect(within(rows[1]).getByText(/101/)).toHaveTextContent('(you)')
    expect(within(rows[2]).getByRole('button', { name: 'Make admin' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button')).not.toBeInTheDocument()
    await expectAccessible()
  })

  it('keeps a campaign being edited while the overview is shown', async () => {
    server({ joins: [shop({ connection: 'CONNECTED' })] })
    const user = userEvent.setup()
    render(<App />)
    await user.click(await screen.findByRole('tab', { name: 'Quiz campaigns' }))
    await user.click(await screen.findByRole('button', { name: 'New campaign' }))
    await user.type(screen.getByLabelText('Name'), 'Autumn quiz')
    await user.click(screen.getByRole('tab', { name: 'Overview' }))
    expect(await screen.findByText('Store: demo.myshopify.com')).toBeVisible()
    await user.click(screen.getByRole('tab', { name: 'Quiz campaigns' }))
    expect(screen.getByLabelText('Name')).toHaveValue('Autumn quiz')
  })

  it('points to help in the page\'s language and to the support address the server wrote in', async () => {
    const meta = document.createElement('meta')
    meta.name = 'quizbuks-support-email'
    meta.content = 'help@example.com'
    document.head.append(meta)
    try {
      server({ joins: [shop()] })
      render(<App />)
      expect(await screen.findByText('Store: demo.myshopify.com')).toBeInTheDocument()
      const help = screen.getByRole('link', { name: 'Help and frequently asked questions' })
      expect(help).toHaveAttribute('href', '/shopify-app/support/')
      expect(help).toHaveAttribute('target', '_blank')
      expect(screen.getByRole('link', { name: 'help@example.com' })).toHaveAttribute('href', 'mailto:help@example.com')
      // A page the server did not write into (the dev server) names no address.
      meta.content = '{{jabiz:support-email}}'
      expect(supportEmail()).toBeNull()
    } finally {
      meta.remove()
    }
  })

  it('offers no hand-over to a member', async () => {
    server({ joins: [shop({ role: 'MEMBER' })] })
    render(<App />)
    expect(await screen.findByText('You are a member of this store in QuizBuks.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Make admin' })).not.toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Actions' })).not.toBeInTheDocument()
  })

  it('hands the admin role on after asking, and shows the store again', async () => {
    const after = shop({ role: 'MEMBER', members: [
      { userId: 'u1', staffId: '101', role: 'MEMBER', joinedAt: '2026-10-27T00:00:00Z' },
      { userId: 'u2', staffId: '102', role: 'ADMIN', joinedAt: '2026-10-27T01:00:00Z' },
    ] })
    const posted = server({ joins: [shop(), after] })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Make admin' }))
    expect(await screen.findByText('The admin role was handed on.')).toBeInTheDocument()
    expect(posted.find((p) => p.path === '/api/processes/QBS_SHOP_ADMIN_TRANSFER/latest')?.body)
      .toEqual({ toUserId: 'u2' })
    expect(screen.getByText('You are a member of this store in QuizBuks.')).toBeInTheDocument()
  })

  it('connects the store with a fresh session token, and says when Shopify refused', async () => {
    const posted = server({ joins: [shop(), shop({ connection: 'CONNECTED', shopName: 'Demo' })] })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Connect' }))
    expect(await screen.findByText('Demo')).toBeInTheDocument()
    expect(screen.getByText('Connected')).toBeInTheDocument()
    const connect = posted.find((p) => p.path === '/api/processes/QBS_SHOP_CONNECT/latest')
    expect(connect?.body?.sessionToken).toMatch(/^session-token-\d+$/)
    // A check that finds the connection gone says so.
    await userEvent.click(screen.getByRole('button', { name: 'Check the connection' }))
    expect(await screen.findByText('Shopify no longer accepts the connection: connect again.')).toBeInTheDocument()
  })

  it('shows what the server refused', async () => {
    server({ joins: [shop()], connect: { status: 422, body: { title: 'Unprocessable', status: 422,
      detail: 'Shopify did not grant access', violations: [{ field: 'sessionToken', ruleCode: 'QBS_EXCHANGE_REFUSED',
        message: 'Shopify did not grant access to the shop (status 401).' }] } } })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Connect' }))
    await waitFor(() => expect(screen.getAllByText(/Shopify did not grant access/).length).toBeGreaterThan(0))
  })

  it('signs in again when the tab\'s session is another store\'s', async () => {
    let joins = 0
    const posted: string[] = []
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
      const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
      if (request.method === 'POST') posted.push(path)
      switch (path) {
        case '/api/auth/entries/shopify':
          return json(200, ENTRY)
        case '/api/auth/external-token':
          return json(200, { status: 'SIGNED_IN', accessToken: 'a2', refreshToken: 'r2' })
        case '/api/auth/me':
          return json(200, { userId: 'u1', roles: ['QBS_SHOP'], permissions: ['qbs.shop.use'] })
        case '/api/processes/QBS_CAMPAIGN_LIST/latest':
        return json(200, { output: { campaigns: [], pool: { available: 0, issuedToday: 0, issuedWeek: 0 } } })
      case '/api/auth/logout':
          return new Response(null, { status: 204 })
        case '/api/processes/QBS_SHOP_JOIN/latest':
          joins += 1
          return joins === 1 ? json(403, { status: 403, title: 'Forbidden' }) : json(200, { output: shop() })
        default:
          return json(404, {})
      }
    })
    session.store('a1', 'r1')
    render(<App />)
    expect(await screen.findByText('Store: demo.myshopify.com')).toBeInTheDocument()
    // Refused as the other store's staff member, then signed in as this one, then joined.
    // (The overview also reads the plan once the store is open: not part of signing in.)
    expect(posted.filter((p) => p !== '/api/auth/logout' && p !== '/api/processes/QBS_PLAN_GET/latest'))
      .toEqual(['/api/processes/QBS_SHOP_JOIN/latest',
      '/api/auth/external-token', '/api/processes/QBS_SHOP_JOIN/latest'])
  })

  it('tells where to open it when not inside the Shopify admin', async () => {
    delete window.shopify
    server({ joins: [shop()] })
    render(<App />)
    expect(await screen.findByText(/Open QuizBuks from your store's Shopify admin/)).toBeInTheDocument()
  })
})
