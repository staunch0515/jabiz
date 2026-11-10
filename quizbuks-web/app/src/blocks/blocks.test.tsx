import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '../App'
import '../messages'
import { feedCard, publicationBlocks, type FeedCard } from '../qb/play'
import { covers } from './api'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

const CARD = {
  publicationId: 'p-1', name: 'Cats of Tokyo', quizTitle: 'Cats', cover: null, video: null, kind: 'QUIZ',
  language: 'en', languages: 'en', maxReward: 100, rewardMethod: 'PLATFORM', digitalDelivery: null,
  digitalDescription: null, completions: 3, sponsorName: 'Ann', sponsorCompany: 'Neko Co', sponsorAvatar: null,
  sponsorCountry: 'JP', startAt: '2026-11-01T00:00:00Z', endAt: '2026-12-31T00:00:00Z', timeLimitSec: 300,
  questionCount: 2, pickCount: null, entryFee: 0, fullScore: 5, quizId: 'q-1', videoProviderId: 'v-1',
  videoProviderName: 'Pet Films',
}

const PAGE = {
  ...CARD, intro: null, materials: '[]', status: 'LIVE', passScore: 3, redeemDescription: null, sponsorIntro: null,
  last1h: 0, last24h: 0, last7d: 0, last30d: 0, statsAt: null, canStart: false, regionBlocked: false, matched: true,
  referral: null, audience: 'PUBLIC', invited: false, myAttemptId: null, myStatus: null, myDeadline: null,
  displayLanguage: 'en',
}

/** The server for a verified taker: the home's card, a publication's page, the blocks and the two processes. */
function server({ card = CARD as Record<string, unknown>, page = PAGE, blocks = [] as Record<string, unknown>[],
  verified = true } = {}) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  let list = [...blocks]
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/auth/entries/app':
        return json(200, ENTRY)
      case '/api/auth/oidc/providers':
        return json(200, [])
      case '/api/auth/me':
        return json(200, { userId: 'u1', displayName: 'Kai', email: 'kai@example.com', roles: ['QB_TAKER'],
          permissions: ['qb.me', 'qb.account.read', 'qb.play'], emailVerified: verified, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/datasets/urn:jabiz:dataset:me:QbUserSettings/query':
        return json(200, { items: [{ id: 'u1', version: 1, attributes: { soundOff: false } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.feed':
        return json(200, { items: list.length > 0 ? [] : [card], offset: 0, limit: 10 })
      case '/api/queries/qb.app.publication':
        return json(200, { items: [{ ...page, blocks: list.length > 0
          ? JSON.stringify(list.map((b) => ({ blockId: b.blockId, kind: b.kind }))) : null }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.blocks':
        return json(200, { items: list, offset: 0, limit: 1000 })
      case '/api/processes/QB_BLOCK_ADD/latest': {
        const given = body as { publicationId: string; kind: string }
        const block = { blockId: `b-${list.length + 1}`, kind: given.kind, objectId: given.kind === 'PROVIDER' ? 'v-1'
          : given.publicationId, name: 'Cats of Tokyo', publicationId: given.publicationId,
        createdAt: '2026-11-08T00:00:00Z' }
        list = [...list, block]
        return json(200, { output: { blockId: block.blockId, kind: block.kind, objectId: block.objectId,
          created: true } })
      }
      case '/api/processes/QB_BLOCK_REMOVE/latest': {
        const given = body as { blockId: string }
        const removed = list.find((b) => b.blockId === given.blockId)
        list = list.filter((b) => b.blockId !== given.blockId)
        return json(200, { output: { ...removed, created: false } })
      }
      default:
        return json(404, {})
    }
  })
  const calls = (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body)
  return { calls }
}

beforeAll(() => configureClient({ entry: 'app' }))
beforeEach(async () => {
  session.clear()
  session.store('access', 'refresh')
  configureClient({ entry: 'app' })
  await i18next.changeLanguage('en')
})
afterEach(() => {
  vi.restoreAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('blocking (F-S8)', () => {
  it('offers not interested, the quiz, the Sponsor and the video provider on a card, and undoes a block', async () => {
    const { calls } = server()
    const { container } = render(<App />)
    const card = await screen.findByTestId('feed-card')
    await userEvent.click(within(card).getByTestId('block-menu'))
    expect(await screen.findByTestId('block-PUBLICATION')).toHaveTextContent('Not interested')
    expect(screen.getByTestId('block-QUIZ')).toHaveTextContent('Block every round of this quiz')
    expect(screen.getByTestId('block-SPONSOR')).toHaveTextContent('Block the Sponsor Neko Co')
    expect(screen.getByTestId('block-PROVIDER')).toHaveTextContent('Block the video provider Pet Films')
    await expectAccessible(container.ownerDocument.body)
    await userEvent.click(screen.getByTestId('block-PUBLICATION'))
    await waitFor(() => expect(calls('QB_BLOCK_ADD')).toEqual([{ publicationId: 'p-1', kind: 'PUBLICATION' }]))
    // The home is read again without it; the message offers to undo.
    await waitFor(() => expect(screen.queryByTestId('feed-card')).toBeNull())
    expect(await screen.findByText('You will not see this quiz any more.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Undo' }))
    await waitFor(() => expect(calls('QB_BLOCK_REMOVE')).toEqual([{ blockId: 'b-1' }]))
    expect(await screen.findByTestId('feed-card')).toBeInTheDocument()
  })

  it('offers the same menu in the vertical mode, which moves on (here: closes) after a block', async () => {
    const vertical = { ...CARD, video: 'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ', videoVertical: true }
    const { calls } = server({ card: vertical })
    window.history.replaceState(null, '', '/?shorts=p-1')
    render(<App />)
    const viewer = await screen.findByTestId('shorts-viewer')
    await userEvent.click(within(viewer).getByTestId('block-menu'))
    await userEvent.click(await screen.findByTestId('block-PROVIDER'))
    await waitFor(() => expect(calls('QB_BLOCK_ADD')).toEqual([{ publicationId: 'p-1', kind: 'PROVIDER' }]))
    await waitFor(() => expect(screen.queryByTestId('shorts-viewer')).toBeNull())
    document.body.style.overflow = ''
  })

  it('offers no menu to an unverified account (blocking is a write)', async () => {
    server({ verified: false })
    render(<App />)
    expect(await screen.findByTestId('feed-card')).toBeInTheDocument()
    expect(screen.queryByTestId('block-menu')).toBeNull()
  })

  it('offers no video provider for a card without a shared video', async () => {
    server({ card: { ...CARD, videoProviderId: null, videoProviderName: null } })
    render(<App />)
    await userEvent.click(within(await screen.findByTestId('feed-card')).getByTestId('block-menu'))
    expect(await screen.findByTestId('block-SPONSOR')).toBeInTheDocument()
    expect(screen.queryByTestId('block-PROVIDER')).toBeNull()
  })

  it('says "you blocked this" on a blocked publication\'s page, and unblocks it there', async () => {
    const { calls } = server({ blocks: [{ blockId: 'b-9', kind: 'PROVIDER', objectId: 'v-1', name: 'Pet Films',
      publicationId: null, createdAt: '2026-11-08T00:00:00Z' }] })
    window.history.replaceState(null, '', '/p/p-1')
    const { container } = render(<App />)
    expect(await screen.findByTestId('detail-blocked-page')).toHaveTextContent('You blocked this')
    expect(screen.getByTestId('detail-blocked-item')).toHaveTextContent('You blocked the video provider Pet Films.')
    // Nothing of the publication is shown, and no start.
    expect(screen.queryByTestId('detail-start')).toBeNull()
    await expectAccessible(container)
    await userEvent.click(screen.getByTestId('detail-unblock'))
    await waitFor(() => expect(calls('QB_BLOCK_REMOVE')).toEqual([{ blockId: 'b-9' }]))
    expect(await screen.findByTestId('detail')).toBeInTheDocument()
  })

  it('lists every block in "Blocks and topics", each unblocked on its own', async () => {
    const { calls } = server({ blocks: [
      { blockId: 'b-1', kind: 'SPONSOR', objectId: 's-1', name: 'Neko Co', publicationId: null,
        createdAt: '2026-11-08T00:00:00Z' },
      { blockId: 'b-2', kind: 'PUBLICATION', objectId: 'p-2', name: 'Dogs', publicationId: 'p-2',
        createdAt: '2026-11-07T00:00:00Z' },
      { blockId: 'b-3', kind: 'QUIZ', objectId: 'q-3', name: null, publicationId: null,
        createdAt: '2026-11-06T00:00:00Z' },
    ] })
    window.history.replaceState(null, '', '/account/blocks')
    const { container } = render(<App />)
    const section = await screen.findByTestId('account-blocks')
    expect(section).toHaveTextContent('Blocked')
    const items = await within(section).findAllByTestId('block-list-item')
    expect(items.map((item) => item.dataset.kind)).toEqual(['SPONSOR', 'PUBLICATION', 'QUIZ'])
    expect(items[0]).toHaveTextContent('Neko Co')
    expect(within(items[1]).getByTestId('block-list-name')).toHaveAttribute('href', expect.stringContaining('/p/p-2'))
    expect(items[2]).toHaveTextContent('No longer available')
    await expectAccessible(container)
    await userEvent.click(within(items[1]).getByRole('button', { name: 'Unblock Dogs' }))
    await waitFor(() => expect(calls('QB_BLOCK_REMOVE')).toEqual([{ blockId: 'b-2' }]))
    await waitFor(() => expect(within(section).getAllByTestId('block-list-item')).toHaveLength(2))
  })

  it('says when nothing is blocked', async () => {
    server()
    window.history.replaceState(null, '', '/account/blocks')
    render(<App />)
    expect(await screen.findByTestId('block-list-empty')).toHaveTextContent('You have not blocked anything.')
  })
})

describe('the blocks of a page and what a block takes off the screen', () => {
  it('reads the page\'s blocks and ignores anything else', () => {
    expect(publicationBlocks(JSON.stringify([{ blockId: 'b', kind: 'QUIZ' }, { blockId: 'c', kind: 'TOPIC' }])))
      .toEqual([{ blockId: 'b', kind: 'QUIZ' }])
    expect(publicationBlocks(null)).toEqual([])
    expect(publicationBlocks('not json')).toEqual([])
    expect(publicationBlocks('{"blockId":"b"}')).toEqual([])
  })

  it('covers the cards of the same publication, quiz, Sponsor or provider', () => {
    const a = feedCard(CARD) as FeedCard
    const sameQuiz = feedCard({ ...CARD, publicationId: 'p-2', videoProviderId: null }) as FeedCard
    const other = feedCard({ ...CARD, publicationId: 'p-3', quizId: 'q-3', sponsorCompany: 'Inu Co',
      videoProviderId: 'v-1' }) as FeedCard
    expect(covers('PUBLICATION', a, a)).toBe(true)
    expect(covers('PUBLICATION', a, sameQuiz)).toBe(false)
    expect(covers('QUIZ', a, sameQuiz)).toBe(true)
    expect(covers('QUIZ', a, other)).toBe(false)
    expect(covers('SPONSOR', a, sameQuiz)).toBe(true)
    expect(covers('SPONSOR', a, other)).toBe(false)
    expect(covers('PROVIDER', a, other)).toBe(true)
    expect(covers('PROVIDER', a, sameQuiz)).toBe(false)
  })
})
