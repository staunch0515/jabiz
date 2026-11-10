import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }
const APPROVED = { contactName: 'Sara', company: 'Acme Bakery', intro: 'Since 1990.', avatar: 'f-logo',
  reviewStatus: 'APPROVED', approvedAt: '2026-10-14T00:00:00Z', revision: 2 }
const PUBLISHED = { userId: 's1', handle: 'acme-bakery', language: 'en', headline: 'Fresh bread', cover: 'f-cover',
  website: null, status: 'PUBLISHED', rejectReason: null, takedownReason: null, takenDownAt: null,
  publishedAt: '2026-10-15T00:00:00Z', reviewRequired: false, revision: 3 }
const PRODUCT = { productId: 'pr1', name: 'Rye', description: null, image: null, price: null, currency: null,
  shopUrl: null, seq: 0, status: 'LISTED', revision: 1 }
const OPERATOR = { operatorId: 'op1', name: 'Kanto Partners', language: 'ja', contactName: 'Ken',
  contactEmail: 'ken@kanto.example', own: false, assignedAt: '2026-10-14T00:00:00Z' }

interface Server {
  sponsor?: Record<string, unknown> | null
  page?: Record<string, unknown> | null
  products?: Record<string, unknown>[]
  staff?: Record<string, unknown>[]
  mailServer?: Record<string, unknown> | null
  methods?: string
  operator?: Record<string, unknown> | null
}

/** The server as the hub sees it: the sponsor, the page, the products, the staff, the mail server, the methods. */
function server(options: Server = {}) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query': {
        const sponsor = options.sponsor === undefined ? APPROVED : options.sponsor
        return json(200, { items: sponsor ? [{ id: 's', version: 1, attributes: sponsor }] : [] })
      }
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorPage/query': {
        const page = options.page === undefined ? PUBLISHED : options.page
        return json(200, { items: page ? [{ id: 's1', version: 1, attributes: page }] : [], offset: 0, limit: 1 })
      }
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorProduct/query':
        return json(200, { items: (options.products ?? []).map((row) => ({ id: String(row.productId), version: 1,
          attributes: row })), offset: 0, limit: 200 })
      case '/api/queries/qb.sponsor.staff':
        return json(200, { items: options.staff ?? [], offset: 0, limit: 100 })
      case '/api/queries/qb.sponsor.mail-server':
        return json(200, { items: options.mailServer ? [options.mailServer] : [], offset: 0, limit: 1 })
      case '/api/queries/qb.sponsor.reward-methods':
        return json(200, { items: [{ methods: options.methods ?? 'PLATFORM,OFFLINE_REDEEM', own: true,
          maxRewardPerAttempt: 500 }], offset: 0, limit: 1 })
      case '/api/dictionaries/urn:jabiz:dict:quizbuks:reward-method':
        return json(200, [{ code: 'PLATFORM', label: 'Kudos paid by QuizBuks' },
          { code: 'OFFLINE_REDEEM', label: "Prize at the Sponsor's place" }])
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.sponsor.operator': {
        const operator = options.operator === undefined ? OPERATOR : options.operator
        return json(200, { items: operator ? [operator] : [], offset: 0, limit: 1 })
      }
      default:
        if (/^\/api\/files\/[^/]+$/.test(path)) return json(200, { fileId: path.split('/').at(-1), variants: [] })
        return json(404, {})
    }
  })
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

describe('the Sponsor hub (Q79)', () => {
  it('shows the status, the checklist, the page card with its link and the reward methods', async () => {
    server({ products: [PRODUCT], mailServer: { host: 'smtp.example.com', port: 587, tls: 'STARTTLS',
      username: 'u', fromAddress: 'mail@acme.example', fromName: null, verifiedAt: '2026-10-20T00:00:00Z',
      testedAt: '2026-10-20T00:00:00Z', lastError: null } })
    window.history.replaceState(null, '', '/profile')
    render(<App />)
    const status = await screen.findByTestId('profile-status')
    expect(status).toHaveAttribute('data-status', 'APPROVED')
    expect(screen.queryByTestId('profile-next')).toBeNull()
    // The checklist: four of five, the staff left (optional).
    const checklist = screen.getByTestId('profile-checklist')
    await waitFor(() => expect(checklist).toHaveTextContent('4 of 5 done'))
    expect(within(checklist).getByTestId('profile-check-basics')).toHaveAttribute('data-done', 'true')
    expect(within(checklist).getByTestId('profile-check-page')).toHaveAttribute('data-done', 'true')
    expect(within(checklist).getByTestId('profile-check-products')).toHaveAttribute('data-done', 'true')
    // F-C1c: the listed products are still counted here; their page is the asset library's tab.
    expect(within(within(checklist).getByTestId('profile-check-products')).getByRole('link'))
      .toHaveAttribute('href', '/assets/products')
    expect(within(checklist).getByTestId('profile-check-staff')).toHaveAttribute('data-done', 'false')
    expect(within(checklist).getByTestId('profile-check-staff')).toHaveTextContent('optional')
    expect(within(within(checklist).getByTestId('profile-check-staff')).getByRole('link', { name: 'Go' }))
      .toHaveAttribute('href', '/profile/staff')
    expect(within(checklist).getByTestId('profile-check-mailServer')).toHaveAttribute('data-done', 'true')
    // The page card and its link.
    const preview = screen.getByTestId('profile-preview')
    expect(preview).toHaveAttribute('data-status', 'PUBLISHED')
    expect(within(preview).getByTestId('profile-preview-company')).toHaveTextContent('Acme Bakery')
    expect(within(preview).getByTestId('profile-preview-headline')).toHaveTextContent('Fresh bread')
    expect(within(preview).getByTestId('profile-preview-link')).toHaveTextContent('/taker/s/acme-bakery')
    expect(screen.getByTestId('profile-preview-open')).toHaveAttribute('href', expect.stringContaining('/taker/s/acme-bakery'))
    // The methods, as the platform allows them.
    const methods = screen.getByTestId('profile-methods')
    await waitFor(() => expect(within(methods).getAllByTestId('profile-method')).toHaveLength(2))
    expect(methods).toHaveTextContent('Kudos paid by QuizBuks')
    expect(methods).toHaveTextContent('500 Kudos')
    // The hub's tabs (F-F3): six sections (F-C1c: the products moved to the asset library; F-S4c: the challenges),
    // the overview current.
    const nav = screen.getByRole('navigation', { name: 'Sponsor hub' })
    expect(within(nav).getAllByRole('link')).toHaveLength(6)
    expect(within(nav).getByTestId('page-tab-challenges')).toBeInTheDocument()
    expect(within(nav).queryByTestId('page-tab-products')).toBeNull()
    expect(within(nav).getByTestId('page-tab-overview')).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByTestId('page-tab-staff')).not.toHaveAttribute('aria-current')
    // F-S1b: the operator that looks after the Sponsor, with how to reach it.
    const operator = screen.getByTestId('profile-operator')
    expect(await within(operator).findByTestId('profile-operator-name')).toHaveTextContent('Kanto Partners')
    expect(within(operator).getByTestId('profile-operator-email')).toHaveAttribute('href', 'mailto:ken@kanto.example')
    expect(within(operator).queryByTestId('profile-operator-own')).toBeNull()
    expect(within(operator).getByTestId('profile-operator-help')).toHaveAttribute('href', '/help')
    await expectAccessible()
    // The QR code of the page.
    await userEvent.click(screen.getByTestId('profile-preview-qr'))
    const dialog = await screen.findByTestId('profile-qr-dialog')
    expect(await within(dialog).findByTestId('profile-qr-image')).toHaveAttribute('src', expect.stringContaining('data:image/svg+xml'))
    expect(dialog).toHaveTextContent('/taker/s/acme-bakery')
  })

  it('leads a new Sponsor to the basics, and from the old addresses to the sections', async () => {
    server({ sponsor: null, page: null })
    window.history.replaceState(null, '', '/')
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe('/profile/basics'))
    expect(await screen.findByTestId('onboarding-status')).toHaveAttribute('data-status', 'NONE')
    expect(screen.getByTestId('page-tab-basics')).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('profile-basics-aside')).toBeInTheDocument()
    await expectAccessible()
    // The overview of a sponsor who has not applied: next is the basics; nothing is done.
    await userEvent.click(screen.getByTestId('page-tab-overview'))
    expect(await screen.findByTestId('profile-status')).toHaveAttribute('data-status', 'NONE')
    expect(screen.getByTestId('profile-next')).toHaveAttribute('href', '/profile/basics')
    await waitFor(() => expect(screen.getByTestId('profile-checklist')).toHaveTextContent('0 of 5 done'))
    expect(screen.getByTestId('profile-preview')).toHaveAttribute('data-status', 'NONE')
    expect(screen.queryByTestId('profile-links')).toBeNull()
  })

  it.each([['/onboarding', '/profile/basics'], ['/page', '/profile/page'],
    ['/staff', '/profile/staff'], ['/mail-server', '/profile/mail-server']])('leads %s to %s', async (from, to) => {
    server()
    window.history.replaceState(null, '', from)
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe(to))
    expect(await screen.findByTestId('profile')).toHaveAttribute('data-section', to.slice('/profile/'.length))
  })

  it('says when the Sponsor leads their own operator, and when none looks after them yet (F-S1b)', async () => {
    server({ operator: { ...OPERATOR, own: true } })
    window.history.replaceState(null, '', '/profile')
    render(<App />)
    expect(await screen.findByTestId('profile-operator-own')).toHaveTextContent('you lead it')
    await expectAccessible()
    cleanup()
    server({ operator: null })
    render(<App />)
    expect(await screen.findByTestId('profile-operator-none')).toBeInTheDocument()
  })

  it('tells an approved Sponsor without a page to publish it next', async () => {
    server({ page: { ...PUBLISHED, status: 'DRAFT', publishedAt: null } })
    window.history.replaceState(null, '', '/profile')
    render(<App />)
    expect(await screen.findByTestId('profile-next')).toHaveAttribute('href', '/profile/page')
    expect(screen.getByTestId('profile-preview')).toHaveAttribute('data-status', 'DRAFT')
    await waitFor(() => expect(screen.getByTestId('profile-check-page')).toHaveAttribute('data-done', 'false'))
  })
})
