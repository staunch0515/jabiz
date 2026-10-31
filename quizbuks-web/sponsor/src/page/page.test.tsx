import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor } from '@testing-library/react'
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

const DRAFT = { userId: 's1', handle: 'acme-bakery', language: 'en', headline: 'Fresh bread', story: null, logo: null,
  cover: null, website: null, status: 'DRAFT', rejectReason: null, takedownReason: null, takenDownAt: null,
  publishedAt: null, reviewRequired: false, revision: 1 }

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
        return json(200, { items: [{ id: 's', version: 1, attributes: { reviewStatus: 'APPROVED' } }] })
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

describe("the sponsor's page editor (Q60a)", () => {
  it('has every text in every language', () => {
    const english = keyPaths(pageEn)
    for (const [lng, texts] of [['zh', pageZh], ['ja', pageJa]] as const) expect(keyPaths(texts), lng).toEqual(english)
    for (const lng of LANGUAGES) {
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })

  it('creates the draft, then publishes it once saved', async () => {
    const { calls } = server(null)
    window.history.replaceState(null, '', '/page')
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
      website: 'https://example.com', story: null, logo: null, cover: null, baseRevision: null })

    await userEvent.click(await screen.findByTestId('sponsor-page-submit'))
    await waitFor(() => expect(screen.getByTestId('sponsor-page-status')).toHaveAttribute('data-status', 'PENDING'))
    expect(calls('QB_SPONSOR_PAGE_SUBMIT')).toEqual([{ baseRevision: 1 }])
    expect(await screen.findByTestId('sponsor-page-done')).toHaveTextContent('Sent for review.')
    expect(screen.getByTestId('sponsor-page-unpublish')).toBeInTheDocument()
    await expectAccessible()
  })

  it('asks to save before publishing changes', async () => {
    server(DRAFT)
    window.history.replaceState(null, '', '/page')
    render(<App />)
    const submit = await screen.findByTestId('sponsor-page-submit')
    expect(submit).toBeEnabled()
    await userEvent.type(screen.getByTestId('sponsor-page-headline'), '!')
    expect(submit).toBeDisabled()
    expect(screen.getByText('Save your changes before publishing.')).toBeInTheDocument()
  })

  it('shows the reasons of a takedown and of a review that sent it back', async () => {
    server({ ...DRAFT, status: 'TAKEN_DOWN', takedownReason: 'QB_ACCOUNT_SUSPENDED', reviewRequired: true })
    window.history.replaceState(null, '', '/page')
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
    window.history.replaceState(null, '', '/page')
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
    window.history.replaceState(null, '', '/page')
    render(<App />)
    await userEvent.click(await screen.findByTestId('sponsor-page-save'))
    expect(await screen.findByText('This link name is taken. Choose another.')).toBeInTheDocument()
    expect(screen.getByTestId('sponsor-page-handle')).toHaveAttribute('aria-invalid', 'true')
    await expectAccessible()
  })
})
