import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { periodOf } from './api'
import { statsEn, statsJa, statsZh } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

/** The server as the statistics page sees it, for an approved sponsor; records the templates' parameters. */
function server(options: { products?: Record<string, unknown>[] } = {}) {
  const asked: { template: string; params: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    const template = /^\/api\/queries\/(.+)$/.exec(path)?.[1]
    if (template) asked.push({ template, params: body?.params ?? {} })
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.page-stats':
        return json(200, { items: [{ day: '2026-10-30', views: '3', clicks: '1' },
          { day: '2026-10-31', views: '1200', clicks: '0' }], offset: 0, limit: 400 })
      case '/api/queries/qb.sponsor.product-clicks':
        return json(200, { items: options.products ?? [
          { productId: 'p1', name: 'Rye loaf', status: 'LISTED', seq: 0, clicks: '1' },
          { productId: 'p2', name: 'Cake', status: 'TAKEN_DOWN', seq: 1, clicks: '0' }], offset: 0, limit: 100 })
      default:
        return json(404, {})
    }
  })
  return { asked }
}

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(() => {
  session.clear()
  session.store('access', 'refresh')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe("the sponsor's page statistics (Q60c-2)", () => {
  it('have every text in every language, and take periods ending today', () => {
    const english = keyPaths(statsEn)
    for (const [lng, texts] of [['zh', statsZh], ['ja', statsJa]] as const) {
      expect(keyPaths(texts), lng).toEqual(english)
    }
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    expect(periodOf(7, new Date(2026, 9, 31))).toEqual({ from: '2026-10-25', to: '2026-10-31' })
    expect(periodOf(30, new Date(2026, 2, 1))).toEqual({ from: '2026-01-31', to: '2026-03-01' })
  })

  it('show the views and clicks by day and by product, and say they are approximate', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/stats')
    render(<App />)
    expect(await screen.findByTestId('stats-views-caption')).toHaveTextContent('1,203')
    expect(screen.getByTestId('stats-clicks-caption')).toHaveTextContent('1')
    expect(screen.getAllByTestId('stats-day')).toHaveLength(2)
    expect(screen.getByTestId('stats-approximate')).toBeInTheDocument()
    const products = await screen.findAllByTestId('stats-product')
    expect(products[0]).toHaveTextContent('Rye loaf')
    expect(within(products[1]).getByText('Taken down')).toBeInTheDocument()
    expect(asked.find((a) => a.template === 'qb.sponsor.page-stats')?.params).toEqual(periodOf(30))
    await expectAccessible()

    await userEvent.click(screen.getByTestId('stats-period'))
    await userEvent.click(await screen.findByRole('option', { name: 'Last 7 days' }))
    await waitFor(() => expect(asked.filter((a) => a.template === 'qb.sponsor.page-stats').at(-1)?.params)
      .toEqual(periodOf(7)))
  })

  it('say when there are no products', async () => {
    server({ products: [] })
    window.history.replaceState(null, '', '/stats')
    render(<App />)
    expect(await screen.findByTestId('stats-no-products')).toBeInTheDocument()
  })
})
