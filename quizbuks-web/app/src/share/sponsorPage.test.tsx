import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { sponsorPageOf } from './SponsorPage'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device', emailLink: true }

const PAGE = { handle: 'acme-bakery', language: 'en', headline: 'Fresh bread daily', story: 'Since **1990**.',
  logo: null, cover: null, website: 'https://example.com/shop', company: 'Acme' }

const LIVE = { publicationId: 'pub1', name: 'Bread quiz', quizTitle: 'Bread', kind: 'QUIZ',
  startAt: '2026-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', timeLimitSec: 300, questionCount: 5,
  maxReward: '500', rewardMethod: 'PLATFORM' }

/** The public templates of a sponsor's page and of a share link; nobody is signed in. */
function server(options: { page?: Record<string, unknown> | null; live?: Record<string, unknown>[];
  shared?: Record<string, unknown> } = {}) {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    asked.push(path + url.search)
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/public/queries/qb.public.sponsor-page':
        return json(200, { items: options.page === null ? [] : [options.page ?? PAGE], offset: 0, limit: 1 })
      case '/api/public/queries/qb.public.sponsor-page-publications':
        return json(200, { items: options.live ?? [LIVE], offset: 0, limit: 50 })
      case '/api/public/queries/qb.public.publication':
        return json(200, { items: [options.shared], offset: 0, limit: 1 })
      case '/api/auth/me':
        return json(401, {})
      default:
        return json(404, {})
    }
  })
  return { asked }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(() => {
  session.clear()
  configureClient({ entry: 'app' })
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe("a sponsor's page (Q60a)", () => {
  it('knows its path', () => {
    expect(sponsorPageOf('/s/Acme-Bakery')).toBe('acme-bakery')
    expect(sponsorPageOf('/s/acme/more')).toBeNull()
    expect(sponsorPageOf('/p/acme')).toBeNull()
    expect(sponsorPageOf('/s/%E0%A4%A')).toBeNull()
  })

  it('shows the page and its live quizzes to anybody, each leading to its own page', async () => {
    const { asked } = server()
    window.history.replaceState(null, '', '/s/Acme-Bakery')
    render(<App />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Acme' })).toBeInTheDocument()
    expect(screen.getByTestId('sponsor-page-headline')).toHaveTextContent('Fresh bread daily')
    expect(screen.getByTestId('sponsor-page-story')).toHaveAttribute('lang', 'en')
    // Signing in returns here.
    expect(screen.getByTestId('public-back')).toHaveAttribute('href', '/login?next=%2Fs%2FAcme-Bakery')
    // Markdown, rendered.
    expect(within(screen.getByTestId('sponsor-page')).getByText('1990').tagName).toBe('STRONG')
    expect(screen.getByTestId('sponsor-page-website')).toHaveAttribute('href', 'https://example.com/shop')
    const quiz = await screen.findByTestId('sponsor-page-publication')
    expect(quiz).toHaveTextContent('Bread quiz')
    expect(quiz).toHaveAttribute('href', '/p/pub1')
    // The handle is looked up in lowercase, whatever was typed.
    expect(asked.some((a) => a.startsWith('/api/public/queries/qb.public.sponsor-page?p.handle=acme-bakery&')))
      .toBe(true)
    expect(asked).not.toContain('/api/auth/me')
    await expectAccessible()
  })

  it('makes no link of a website that is not https', async () => {
    server({ page: { ...PAGE, website: 'javascript:alert(1)' } })
    window.history.replaceState(null, '', '/s/acme-bakery')
    render(<App />)
    expect(await screen.findByTestId('sponsor-page-headline')).toBeInTheDocument()
    expect(screen.queryByTestId('sponsor-page-website')).toBeNull()
  })

  it('says when no quiz is open, and when there is no such page', async () => {
    server({ live: [] })
    window.history.replaceState(null, '', '/s/acme-bakery')
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('sponsor-page-none')).toBeInTheDocument()
    unmount()
    vi.restoreAllMocks()
    server({ page: null })
    window.history.replaceState(null, '', '/s/gone')
    render(<App />)
    expect(await screen.findByText('This page is not available')).toBeInTheDocument()
  })

  it("links the sponsor of a share link's page to their page", async () => {
    server({ shared: { publicationId: 'pub1', name: 'Bread quiz', quizTitle: 'Bread', kind: 'QUIZ',
      startAt: '2026-01-01T00:00:00Z', endAt: '2099-01-01T00:00:00Z', timeLimitSec: 300, questionCount: 5,
      fullScore: 5, passScore: 3, maxReward: '500', sponsorCompany: 'Acme', sponsorHandle: 'acme-bakery' } })
    window.history.replaceState(null, '', '/p/pub1')
    render(<App />)
    expect(await screen.findByTestId('share-sponsor-page')).toHaveAttribute('href', '/s/acme-bakery')
  })
})
