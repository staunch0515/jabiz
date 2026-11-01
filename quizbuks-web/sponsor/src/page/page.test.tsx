import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { pageEn, pageJa, pageZh } from './messages'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'sponsor', selfRegistration: true, requireVerifiedEmail: true, appPath: '/',
  sessionPersistence: 'tab' }

const DRAFT = { userId: 's1', handle: 'acme-bakery', language: 'en', headline: 'Fresh bread', cover: null,
  website: null, status: 'DRAFT', rejectReason: null, takedownReason: null, takenDownAt: null, publishedAt: null,
  reviewRequired: false, revision: 1 }

/** The approved onboarding: the basic profile, whose logo and introduction the shop page shows (Q65b). */
const SPONSOR_ROW = { contactName: 'Sara', company: 'Acme Bakery', intro: 'Since **1990**.', avatar: null,
  reviewStatus: 'APPROVED', approvedAt: '2026-10-14T00:00:00Z', revision: 2 }

/** The sponsor profile, which holds the shop page since Q65b (`/page` leads there). */
const PROFILE_PATH = '/onboarding'
const OLD_PAGE_PATH = '/page'

/** The server as the page editor sees it, for an approved sponsor. Records what the page sent. */
function server(initial: Record<string, unknown> | null, answers: Record<string, () => Response> = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let page = initial
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin), init)
    const url = new URL(request.url, window.location.origin)
    const path = decodeURIComponent(url.pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    const name = /^\/api\/processes\/([A-Z_]+)\/latest$/.exec(path)?.[1]
    if (name && answers[name]) return answers[name]()
    switch (path) {
      case '/api/auth/entries/sponsor':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 's1', displayName: 'Sara', email: 'sara@example.com', roles: ['QB_SPONSOR'],
          permissions: ['qb.sponsor.me'], emailVerified: true, idleTimeoutSeconds: 1800 })
      case '/api/datasets/urn:jabiz:dataset:sponsor:QbSponsor/query':
        return json(200, { items: [{ id: 's', version: 1, attributes: SPONSOR_ROW }] })
      case '/api/processes/QB_SPONSOR_PROFILE_SAVE/latest':
        return json(200, { output: { userId: 's1', onboardingId: 'o1', reviewStatus: 'APPROVED', revision: 3,
          approval: null, approvalRequestId: null } })
      case '/api/datasets/urn:jabiz:dataset:me:QbSponsorPage/query':
        return json(200, { items: page ? [{ id: 's1', version: 1, attributes: page }] : [], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/processes/QB_SPONSOR_PAGE_SAVE/latest': {
        const was = page?.status
        const saved: Record<string, unknown> = { ...DRAFT, ...page, ...body,
          status: was === 'PENDING' || !was ? 'DRAFT' : was, revision: Number(page?.revision ?? 0) + 1 }
        page = saved
        return json(200, { output: { userId: 's1', handle: saved.handle, status: saved.status,
          revision: saved.revision, approval: null, approvalRequestId: null } })
      }
      case '/api/processes/QB_SPONSOR_PAGE_SUBMIT/latest':
        page = { ...page, status: 'PENDING', revision: Number(page?.revision) + 1 }
        return json(200, { output: { userId: 's1', handle: page.handle, status: 'PENDING', revision: page.revision,
          approval: 'PENDING', approvalRequestId: 'r1' } })
      case '/api/processes/QB_SPONSOR_PAGE_UNPUBLISH/latest':
        page = { ...page, status: 'DRAFT', revision: Number(page?.revision) + 1 }
        return json(200, { output: { userId: 's1', handle: page.handle, status: 'DRAFT', revision: page.revision,
          approval: null, approvalRequestId: null } })
      default:
        return json(404, {})
    }
  })
  const calls = (process: string) => posted.filter((p) => p.path === `/api/processes/${process}/latest`)
    .map((p) => p.body)
  return { calls }
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

describe("the sponsor's page editor (Q60a), a section of the sponsor profile (Q65b)", () => {
  it('has every text in every language', () => {
    const english = keyPaths(pageEn)
    for (const [lng, texts] of [['zh', pageZh], ['ja', pageJa]] as const) expect(keyPaths(texts), lng).toEqual(english)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })

  it('is one entry of the menu: the sponsor profile, with the basic profile and the shop page', async () => {
    server(DRAFT)
    // The old address of "My page" leads to the sponsor profile.
    window.history.replaceState(null, '', OLD_PAGE_PATH)
    render(<App />)
    await waitFor(() => expect(window.location.pathname).toBe(PROFILE_PATH))
    const nav = screen.getByRole('navigation', { name: 'Sponsor console' })
    expect(within(nav).getByRole('link', { name: 'Sponsor profile' })).toHaveAttribute('href', PROFILE_PATH)
    expect(within(nav).queryByRole('link', { name: 'My page' })).toBeNull()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Sponsor profile')
    expect(await screen.findByRole('heading', { level: 2, name: 'Basic profile' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: 'Shop page' })).toBeInTheDocument()
    // The page's logo and "About us" are the basic profile's: the shop page has no fields of its own for them.
    expect(screen.getByTestId('sponsor-page-from-profile')).toHaveTextContent('from the basic profile above')
    expect(screen.queryByTestId('sponsor-page-story')).toBeNull()
    expect(screen.queryByTestId('sponsor-page-logo')).toBeNull()
    expect(screen.getByTestId('sponsor-page-cover')).toBeInTheDocument()
    // The introduction takes up to 5,000 characters (the page's "About us").
    expect(screen.getByTestId('onboarding-intro')).toHaveAttribute('maxlength', '5000')
    expect(screen.getByText('Up to 5,000 characters. Markdown is allowed (no HTML).')).toBeInTheDocument()
    await expectAccessible()
  })

  it('saves the basic profile and the shop page each with its own process', async () => {
    const { calls } = server(DRAFT)
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    const intro = await screen.findByTestId('onboarding-intro')
    await userEvent.clear(intro)
    await userEvent.type(intro, 'Bread since 1990.')
    await userEvent.click(screen.getByTestId('onboarding-submit'))
    await waitFor(() => expect(calls('QB_SPONSOR_PROFILE_SAVE')).toHaveLength(1))
    expect(calls('QB_SPONSOR_PROFILE_SAVE')[0]).toMatchObject({ company: 'Acme Bakery', intro: 'Bread since 1990.' })
    expect(calls('QB_SPONSOR_PAGE_SAVE')).toEqual([])

    await userEvent.type(await screen.findByTestId('sponsor-page-headline'), '!')
    await userEvent.click(screen.getByTestId('sponsor-page-save'))
    await waitFor(() => expect(calls('QB_SPONSOR_PAGE_SAVE')).toHaveLength(1))
    const saved = calls('QB_SPONSOR_PAGE_SAVE')[0]
    expect(saved).toMatchObject({ handle: 'acme-bakery', headline: 'Fresh bread!', baseRevision: 1 })
    expect(Object.keys(saved)).not.toContain('story')
    expect(Object.keys(saved)).not.toContain('logo')
    expect(calls('QB_SPONSOR_PROFILE_SAVE')).toHaveLength(1)
  })

  it('offers the page in more languages, its headline and the introduction in each (Q73c)', async () => {
    const { calls } = server(DRAFT)
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    // The introduction in Chinese too (optional), sent with the basic profile.
    await userEvent.click(await screen.findByTestId('onboarding-intro-language-zh'))
    const introZh = within(screen.getByTestId('onboarding-intro-translations-zh'))
      .getByTestId('onboarding-intro-translations-input')
    await userEvent.type(introZh, '面包')
    await userEvent.click(screen.getByTestId('onboarding-submit'))
    await waitFor(() => expect(calls('QB_SPONSOR_PROFILE_SAVE')).toHaveLength(1))
    expect(calls('QB_SPONSOR_PROFILE_SAVE')[0]).toMatchObject({ introTranslations: { zh: { intro: '面包' } },
      introConfirmed: [] })

    // The page in Chinese as well, Chinese its main language, with its headline.
    await userEvent.click(await screen.findByTestId('sponsor-page-offer-zh'))
    const headlineZh = within(screen.getByTestId('sponsor-page-translations-zh'))
      .getByTestId('sponsor-page-translations-input')
    await userEvent.type(headlineZh, '新鲜面包')
    await userEvent.click(screen.getByTestId('sponsor-page-main-zh'))
    await userEvent.click(screen.getByTestId('sponsor-page-save'))
    await waitFor(() => expect(calls('QB_SPONSOR_PAGE_SAVE')).toHaveLength(1))
    expect(calls('QB_SPONSOR_PAGE_SAVE')[0]).toMatchObject({ languages: ['zh', 'en'],
      translations: { zh: { headline: '新鲜面包' } }, confirmed: [] })
    await expectAccessible()
  })

  it('creates the draft, then publishes it once saved', async () => {
    const { calls } = server(null)
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    expect(await screen.findByTestId('sponsor-page-status')).toHaveAttribute('data-status', 'NONE')
    // Nothing to publish before the first save.
    expect(screen.queryByTestId('sponsor-page-submit')).toBeNull()
    await userEvent.type(screen.getByTestId('sponsor-page-handle'), 'Acme-Bakery')
    await userEvent.type(screen.getByTestId('sponsor-page-headline'), 'Fresh bread daily')
    await userEvent.type(screen.getByTestId('sponsor-page-website'), 'https://example.com')
    await userEvent.click(screen.getByTestId('sponsor-page-save'))
    await waitFor(() => expect(screen.getByTestId('sponsor-page-status')).toHaveAttribute('data-status', 'DRAFT'))
    // The message outlives the form's new start from the saved revision.
    expect(await screen.findByTestId('sponsor-page-done')).toHaveTextContent('Saved.')
    expect(calls('QB_SPONSOR_PAGE_SAVE')[0]).toMatchObject({ handle: 'acme-bakery', headline: 'Fresh bread daily',
      website: 'https://example.com', cover: null, baseRevision: null })

    await userEvent.click(await screen.findByTestId('sponsor-page-submit'))
    await waitFor(() => expect(screen.getByTestId('sponsor-page-status')).toHaveAttribute('data-status', 'PENDING'))
    expect(calls('QB_SPONSOR_PAGE_SUBMIT')).toEqual([{ baseRevision: 1 }])
    expect(await screen.findByTestId('sponsor-page-done')).toHaveTextContent('Sent for review.')
    expect(screen.getByTestId('sponsor-page-unpublish')).toBeInTheDocument()
    await expectAccessible()
  })

  it('asks to save before publishing changes', async () => {
    server(DRAFT)
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    const submit = await screen.findByTestId('sponsor-page-submit')
    expect(submit).toBeEnabled()
    await userEvent.type(screen.getByTestId('sponsor-page-headline'), '!')
    expect(submit).toBeDisabled()
    expect(screen.getByText('Save your changes before publishing.')).toBeInTheDocument()
  })

  it('shows the reasons of a takedown and of a review that sent it back', async () => {
    server({ ...DRAFT, status: 'TAKEN_DOWN', takedownReason: 'QB_ACCOUNT_SUSPENDED', reviewRequired: true })
    window.history.replaceState(null, '', PROFILE_PATH)
    const { unmount } = render(<App />)
    expect(await screen.findByTestId('sponsor-page-takedown-reason')).toHaveTextContent('Your account was suspended.')
    unmount()
    vi.restoreAllMocks()
    server({ ...DRAFT, status: 'REJECTED', rejectReason: 'Tone it down' })
    render(<App />)
    expect(await screen.findByTestId('sponsor-page-reject-reason')).toHaveTextContent('Tone it down')
  })

  it('offers the link of a published page, and unpublishes it on confirmation', async () => {
    const { calls } = server({ ...DRAFT, status: 'PUBLISHED', publishedAt: '2026-10-30T00:00:00Z', revision: 4 })
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    expect(await screen.findByTestId('sponsor-page-open'))
      .toHaveAttribute('href', `${window.location.origin}/taker/s/acme-bakery`)
    expect(screen.queryByTestId('sponsor-page-submit')).toBeNull()
    await userEvent.click(screen.getByTestId('sponsor-page-unpublish'))
    await userEvent.click(await screen.findByRole('button', { name: 'Unpublish' }))
    await waitFor(() => expect(calls('QB_SPONSOR_PAGE_UNPUBLISH')).toEqual([{ baseRevision: 4 }]))
  })

  it("shows the server's refusal at the field", async () => {
    server(DRAFT, {
      QB_SPONSOR_PAGE_SAVE: () => json(422, { title: 'Unprocessable', status: 422, violations: [{ field: 'handle',
        ruleCode: 'QB_SPONSOR_PAGE_HANDLE_TAKEN', message: 'This link name is taken. Choose another.' }] }),
    })
    window.history.replaceState(null, '', PROFILE_PATH)
    render(<App />)
    await userEvent.click(await screen.findByTestId('sponsor-page-save'))
    expect(await screen.findByText('This link name is taken. Choose another.')).toBeInTheDocument()
    expect(screen.getByTestId('sponsor-page-handle')).toHaveAttribute('aria-invalid', 'true')
    await expectAccessible()
  })
})
