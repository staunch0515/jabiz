import { configureClient, session } from '@jabiz/client'
import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import { useState } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import '../messages'
import type { Asset } from '../assets/api'
import { titleIn, titlesOf } from './api'
import { sharedVideosMessages } from './messages'
import { SharedVideoField, sharedVideoForeign } from './SharedVideoField'
import { MySharedVideos, ShareVideoDialog } from './Sharing'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const TOPICS = [
  { topicId: 'pets', name: JSON.stringify({ zh: '宠物', ja: 'ペット', en: 'Pets' }), active: true, mergedInto: null },
  { topicId: 'travel', name: JSON.stringify({ zh: '旅行', ja: '旅行', en: 'Travel' }), active: true,
    mergedInto: null },
]

const TITLE = JSON.stringify({ zh: '猫', ja: '猫の動画', en: 'Cats' })
const CATALOG = [
  { sharedVideoId: 'v1', providerId: 'p1', providerCompany: 'Cat Co', title: TITLE,
    url: 'https://www.bilibili.com/video/BV1GJ411x7h7', durationSec: 95, vertical: true, topics: 'pets',
    status: 'APPROVED', approvedAt: '2026-11-08T00:00:00Z' },
  { sharedVideoId: 'v2', providerId: 'p2', providerCompany: 'Dog Co', title: JSON.stringify({ zh: '狗', ja: '犬',
    en: 'Dogs' }), url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', durationSec: 30, vertical: false,
  topics: 'pets,travel', status: 'APPROVED', approvedAt: '2026-11-07T00:00:00Z' },
]
const MINE = [
  { sharedVideoId: 'm1', assetId: 'a1', title: TITLE, url: 'https://www.bilibili.com/video/BV1GJ411x7h7',
    durationSec: 95, vertical: false, topics: 'pets', status: 'APPROVED', sharedAt: '2026-11-08T00:00:00Z',
    approvedAt: '2026-11-08T01:00:00Z', rejectReason: null, withdrawnAt: null, takenDownAt: null, takedownReason: null,
    revision: 2, usesRunning: 3 },
  { sharedVideoId: 'm2', assetId: 'a2', title: JSON.stringify({ zh: '鱼', ja: '魚', en: 'Fish' }),
    url: 'https://vimeo.com/76979871', durationSec: 60, vertical: false, topics: 'pets', status: 'REJECTED',
    sharedAt: '2026-11-08T00:00:00Z', approvedAt: null, rejectReason: 'Not entertainment', withdrawnAt: null,
    takenDownAt: null, takedownReason: null, revision: 2, usesRunning: 0 },
  { sharedVideoId: 'm3', assetId: 'a3', title: JSON.stringify({ zh: '鸟', ja: '鳥', en: 'Birds' }),
    url: 'https://vimeo.com/76979871', durationSec: 60, vertical: false, topics: 'pets', status: 'TAKEN_DOWN',
    sharedAt: '2026-11-08T00:00:00Z', approvedAt: null, rejectReason: null, withdrawnAt: null,
    takenDownAt: '2026-11-08T02:00:00Z', takedownReason: 'Infringes a copyright', revision: 3, usesRunning: 0 },
]

/** The server: the word list, the catalog (filtered by the parameters as the template would) and the processes. */
function server() {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    if (request.method === 'POST') posted.push({ path, body })
    switch (path) {
      case '/api/queries/qb.sponsor.topics':
        return json(200, { items: TOPICS, offset: 0, limit: 500 })
      case '/api/queries/qb.sponsor.shared-videos': {
        const params = (body?.params ?? {}) as Record<string, unknown>
        const items = CATALOG.filter((row) => (params.sharedVideoId ? row.sharedVideoId === params.sharedVideoId
          : true) && (params.vertical === undefined || row.vertical === params.vertical)
          && (!params.topic || row.topics.split(',').includes(String(params.topic)))
          && (!params.q || row.title.includes(String(params.q)) || row.providerCompany.includes(String(params.q))))
        return json(200, { items, offset: 0, limit: 50 })
      }
      case '/api/queries/qb.sponsor.my-shared-videos':
        return json(200, { items: MINE, offset: 0, limit: 200 })
      case '/api/processes/QB_SHARED_VIDEO_SHARE/latest':
        return json(200, { output: { sharedVideoId: 'new', status: 'PENDING', approval: 'PENDING',
          approvalRequestId: 'r1' } })
      case '/api/processes/QB_SHARED_VIDEO_WITHDRAW/latest':
        return json(200, { output: { sharedVideoId: 'm1', status: 'WITHDRAWN', approval: null,
          approvalRequestId: null } })
      default:
        return json(404, {})
    }
  })
  return {
    calls: (name: string) => posted.filter((p) => p.path === `/api/processes/${name}/latest`).map((p) => p.body),
    queries: (id: string) => posted.filter((p) => p.path === `/api/queries/${id}`).map((p) => p.body),
  }
}

function wrap(children: React.ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {children}
    </QueryClientProvider>
  )
}

const ASSET = { assetId: 'a9', kind: 'VIDEO_LINK', name: 'Funny cats', description: null, language: 'en', tags: [],
  translations: {}, translationHashes: {}, revision: 1, editedAt: '', image: null, pdf: null, audio: null,
  url: 'https://www.youtube.com/shorts/dQw4w9WgXcQ', durationSec: 95, body: null, usage: null, prizeMethod: null,
  prizeDescription: null, digitalDelivery: null, digitalSendDays: null } as Asset

beforeAll(() => configureClient({ entry: 'sponsor' }))
beforeEach(async () => {
  session.clear()
  session.store('access', 'refresh')
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe('shared videos in the sponsor console (F-S12)', () => {
  it('have every text in every language', () => {
    const keys = (value: object, prefix = ''): string[] => Object.entries(value).flatMap(([key, child]) =>
      typeof child === 'object' && child !== null ? keys(child, `${prefix}${key}.`) : [`${prefix}${key}`])
    const english = keys(sharedVideosMessages.en)
    expect(keys(sharedVideosMessages.zh)).toEqual(english)
    expect(keys(sharedVideosMessages.ja)).toEqual(english)
    // Never "后台" in text people read (glossary.md).
    expect(JSON.stringify(sharedVideosMessages)).not.toMatch(/后台|管理画面|コンソール/)
  })

  it('reads titles in the reader\'s language and counts a foreign video as the server does', () => {
    expect(titleIn(titlesOf(TITLE), 'ja-JP')).toBe('猫の動画')
    expect(titleIn(titlesOf(TITLE), 'fr')).toBe('Cats')
    expect(titlesOf('broken')).toEqual({})
    expect(sharedVideoForeign({ url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' } as never)).toBe(true)
    expect(sharedVideoForeign({ url: 'https://www.bilibili.com/video/BV1GJ411x7h7' } as never)).toBe(false)
    expect(sharedVideoForeign(null)).toBe(false)
  })

  it('shares a video link of the library with its titles, topics and orientation', async () => {
    const { calls } = server()
    const onShared = vi.fn()
    const { container } = render(wrap(<ShareVideoDialog asset={ASSET} onClose={() => {}} onShared={onShared} />))
    const dialog = await screen.findByTestId('share-video-dialog')
    // A Shorts link is vertical to start with; the asset's name is every title to start from.
    expect(within(dialog).getByTestId('share-video-vertical')).toBeChecked()
    expect(within(dialog).getByTestId('share-video-titleEn')).toHaveValue('Funny cats')
    await userEvent.clear(within(dialog).getByTestId('share-video-titleZh'))
    await userEvent.type(within(dialog).getByTestId('share-video-titleZh'), '有趣的猫')
    await userEvent.click(within(dialog).getByTestId('share-video-topic'))
    await userEvent.click(await screen.findByRole('option', { name: 'Pets' }))
    expect(within(dialog).getByTestId('share-video-topic-pets')).toHaveTextContent('Pets')
    await expectAccessible(container.ownerDocument.body)
    await userEvent.click(within(dialog).getByTestId('share-video-submit'))
    await waitFor(() => expect(calls('QB_SHARED_VIDEO_SHARE')).toContainEqual({ assetId: 'a9', titleZh: '有趣的猫',
      titleJa: 'Funny cats', titleEn: 'Funny cats', topics: ['pets'], vertical: true }))
    await waitFor(() => expect(onShared).toHaveBeenCalled())
  })

  it('lists the provider\'s shares with the review\'s result and withdraws one', async () => {
    const { calls } = server()
    const { container } = render(wrap(<MySharedVideos />))
    const rows = await screen.findAllByTestId('my-shared-video')
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByTestId('shared-video-status')).toHaveTextContent('Shared')
    expect(within(rows[0]).getByTestId('shared-video-uses')).toHaveTextContent('Used by 3 publications')
    expect(within(rows[1]).getByTestId('shared-video-reason')).toHaveTextContent('Sent back: Not entertainment')
    expect(within(rows[2]).getByTestId('shared-video-reason')).toHaveTextContent('Taken down: Infringes a copyright')
    // Only a share that stands is withdrawn.
    expect(within(rows[1]).queryByTestId('shared-video-withdraw')).toBeNull()
    await expectAccessible(container)
    await userEvent.click(within(rows[0]).getByTestId('shared-video-withdraw'))
    await userEvent.click(await screen.findByRole('button', { name: 'Withdraw' }))
    await waitFor(() => expect(calls('QB_SHARED_VIDEO_WITHDRAW')).toContainEqual({ sharedVideoId: 'm1' }))
  })

  it('lets the wizard pick a video from the catalog, searched and filtered, and drop it', async () => {
    const { queries } = server()
    let latest: string | null = null
    function Host() {
      const [value, setValue] = useState<string | null>(null)
      latest = value
      return <SharedVideoField value={value} onChange={setValue} disabled={false} />
    }
    const { container } = render(wrap(<Host />))
    expect(screen.getByTestId('publication-shared-video-none')).toBeInTheDocument()
    await userEvent.click(screen.getByTestId('publication-shared-video-choose'))
    const picker = await screen.findByTestId('shared-video-picker')
    await waitFor(() => expect(within(picker).getAllByTestId('shared-video-option')).toHaveLength(2))
    await userEvent.type(within(picker).getByTestId('shared-video-search'), 'Dog')
    await waitFor(() => expect(within(picker).getAllByTestId('shared-video-option')).toHaveLength(1))
    expect(queries('qb.sponsor.shared-videos').at(-1)).toEqual(expect.objectContaining({
      params: { q: 'Dog' } }))
    await expectAccessible(container.ownerDocument.body)
    await userEvent.click(within(picker).getByRole('button', { name: 'Use Dogs' }))
    expect(latest).toBe('v2')
    const picked = await screen.findByTestId('publication-shared-video-picked')
    expect(picked).toHaveTextContent('Dogs')
    expect(picked).toHaveTextContent('Shared by Dog Co')
    await userEvent.click(screen.getByTestId('publication-shared-video-remove'))
    expect(latest).toBeNull()
  })

  it('says when the video picked is no longer in the catalog', async () => {
    server()
    CATALOG.push({ ...CATALOG[0], sharedVideoId: 'gone', status: 'WITHDRAWN' })
    try {
      const { container } = render(wrap(<SharedVideoField value="gone" onChange={() => {}} disabled={false} />))
      expect(await screen.findByTestId('publication-shared-video-unavailable')).toHaveTextContent('Withdrawn')
      await expectAccessible(container)
    } finally {
      CATALOG.pop()
    }
  })
})
