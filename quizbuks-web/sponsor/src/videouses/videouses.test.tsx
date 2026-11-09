import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { videoUseMessages } from './messages'
import { VideoUseSection } from './VideoUseSection'
import { VideoUsesProvided } from './VideoUsesProvided'

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const TITLE = JSON.stringify({ zh: '猫', ja: '猫の動画', en: 'Cats' })

/** A row of either template: the publication and its figures, with the use when given. */
function row(use: Record<string, unknown> | null, figures: Record<string, unknown> = {}) {
  return {
    publicationId: 'pub-1', name: 'Spring quiz', publicationStatus: 'LIVE', wentOnAt: '2026-11-01T01:00:00Z',
    endAt: '2026-11-30T01:00:00Z', endedAt: null, videoTitle: TITLE, providerCompany: 'Cat Co',
    userCompany: 'Bakery Co', minGroup: 5, refreshedAt: null, finalisedAt: null, opens: null, successes: null,
    goes: null, qualified: null, amount: null, videoUseId: null, ...figures, ...(use ?? {}),
  }
}

const USE = { videoUseId: 'use-1', status: 'DRAFT', periodFrom: '2026-11-01', periodTo: '2026-11-30',
  pricing: 'PER_TAKER', price: 100, note: 'Agreed by mail', submittedAt: null, confirmedAt: null,
  confirmedRevision: null, rejectedAt: null, rejectReason: null, revision: 1 }

/** The server: the two templates answer `rows`, the processes answer their output. */
function serve(rows: Record<string, unknown>[], history: Record<string, unknown>[] = []) {
  const posted: { path: string; body: Record<string, unknown> }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input
      : new Request(new URL(String(input), window.location.origin), { method: init?.method, body: init?.body })
    const path = decodeURIComponent(new URL(request.url, window.location.origin).pathname)
    const body = request.method === 'POST' ? await request.clone().json().catch(() => null) : null
    posted.push({ path, body })
    if (path.includes('video-use-history')) return json(200, { items: history, offset: 0, limit: 200 })
    if (path.startsWith('/api/queries/')) return json(200, { items: rows, offset: 0, limit: 100 })
    if (path.startsWith('/api/processes/')) {
      return json(200, { output: { videoUseId: 'use-1', status: 'DRAFT', revision: 2 } })
    }
    return json(404, {})
  })
  return posted
}

function wrap(children: ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      {children}
    </QueryClientProvider>,
  )
}

beforeEach(async () => {
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe("the user's side of a shared video's ledger (F-S14)", () => {
  it('says the figures are not counted yet and writes the entry with the publication\'s dates', async () => {
    const posted = serve([row(null)])
    const user = userEvent.setup()
    const { container } = wrap(<VideoUseSection publicationId="pub-1" />)
    expect(await screen.findByTestId('video-use-none')).toHaveTextContent('No ledger entry yet.')
    expect(screen.getByTestId('video-use-not-counted')).toBeInTheDocument()
    expect(screen.getByTestId('video-use-section')).toHaveTextContent('Cat Co')
    expect(screen.getByTestId('video-use-section')).toHaveTextContent('"Cats"')
    expect(posted[0]).toMatchObject({ path: '/api/queries/qb.sponsor.video-uses', body: { params: {
      publicationId: 'pub-1' } } })
    await expectAccessible(container)

    await user.click(screen.getByTestId('video-use-edit'))
    const dialog = await screen.findByTestId('video-use-dialog')
    expect(within(dialog).getByTestId('video-use-from')).toHaveValue('2026-11-01')
    expect(within(dialog).getByTestId('video-use-to')).toHaveValue('2026-11-30')
    await user.click(within(dialog).getByTestId('video-use-pricing-PER_MILLE'))
    await user.type(within(dialog).getByTestId('video-use-price'), '2000')
    await user.type(within(dialog).getByTestId('video-use-note-input'), 'By mail')
    await expectAccessible(document.body)
    await user.click(within(dialog).getByTestId('video-use-save'))
    await waitFor(() => expect(posted.map((p) => p.path)).toContain('/api/processes/QB_VIDEO_USE_SAVE/latest'))
    expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_SAVE/latest')!.body).toMatchObject({
      publicationId: 'pub-1', periodFrom: '2026-11-01', periodTo: '2026-11-30', pricing: 'PER_MILLE', price: '2000',
      note: 'By mail' })
  })

  it('shows the figures (fewer than the minimum group where they are), the amount and sends a draft', async () => {
    const posted = serve([row(USE, { refreshedAt: '2026-11-08T19:53:00Z', opens: null, successes: 1200, goes: 40,
      qualified: null, amount: 4000 })])
    const user = userEvent.setup()
    const { container } = wrap(<VideoUseSection publicationId="pub-1" />)
    expect(await screen.findByTestId('video-use-figures-successes-value')).toHaveTextContent('1,200')
    expect(screen.getByTestId('video-use-figures-opens-value')).toHaveTextContent('Fewer than 5')
    expect(screen.getByTestId('video-use-figures-qualified-value')).toHaveTextContent('Fewer than 5')
    expect(screen.getByTestId('video-use-figures-goes-value')).toHaveTextContent('40')
    expect(screen.getByTestId('video-use-amount')).toHaveTextContent('4,000 yen')
    expect(screen.getByTestId('video-use-pricing')).toHaveTextContent('100 yen per Taker')
    expect(screen.getByTestId('video-use-status')).toHaveAttribute('data-status', 'DRAFT')
    await expectAccessible(container)
    await user.click(screen.getByTestId('video-use-submit'))
    await waitFor(() => expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_SUBMIT/latest')?.body)
      .toEqual({ videoUseId: 'use-1', baseRevision: 1 }))
  })

  it('shows the provider\'s reason when sent back, and lets the Sponsor correct it with the revision read', async () => {
    const posted = serve([row({ ...USE, status: 'REJECTED', rejectReason: 'We agreed 60,000 yen', revision: 3,
      pricing: 'FIXED', price: 50000 }, { amount: 50000 })])
    const user = userEvent.setup()
    const { container } = wrap(<VideoUseSection publicationId="pub-1" />)
    expect(await screen.findByTestId('video-use-reason')).toHaveTextContent('The provider sent it back: We agreed 60,000 yen')
    expect(screen.queryByTestId('video-use-submit')).toBeNull()
    await expectAccessible(container)
    await user.click(screen.getByTestId('video-use-edit'))
    const price = within(await screen.findByTestId('video-use-dialog')).getByTestId('video-use-price')
    expect(price).toHaveValue('50000')
    await user.clear(price)
    await user.type(price, '60000')
    await user.click(screen.getByTestId('video-use-save'))
    await waitFor(() => expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_SAVE/latest')?.body)
      .toMatchObject({ videoUseId: 'use-1', baseRevision: 3, price: '60000', pricing: 'FIXED' }))
    expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_SAVE/latest')!.body.publicationId)
      .toBeUndefined()
  })

  it('waits for the provider without a way to change it, and shows nothing for its own videos', async () => {
    serve([row({ ...USE, status: 'SUBMITTED', revision: 2, submittedAt: '2026-11-08T00:00:00Z' })])
    const first = wrap(<VideoUseSection publicationId="pub-1" />)
    expect(await screen.findByTestId('video-use-waiting')).toBeInTheDocument()
    expect(screen.queryByTestId('video-use-edit')).toBeNull()
    first.unmount()
    vi.restoreAllMocks()
    const asked = serve([])
    const { container } = wrap(<VideoUseSection publicationId="pub-1" />)
    await waitFor(() => expect(asked.length).toBe(1))
    expect(container).toBeEmptyDOMElement()
  })
})

describe("the provider's side of the ledger (F-S14)", () => {
  it('confirms an entry as read, and sends one back only with a reason', async () => {
    const submitted = { ...USE, status: 'SUBMITTED', revision: 2, submittedAt: '2026-11-08T00:00:00Z' }
    const posted = serve([row(submitted, { refreshedAt: '2026-11-08T19:53:00Z', finalisedAt: '2026-11-09T19:53:00Z',
      opens: 12, successes: 300, goes: 25, qualified: 9, amount: 2500 })])
    const user = userEvent.setup()
    const { container } = wrap(<VideoUsesProvided />)
    const item = await screen.findByTestId('video-use-provided')
    expect(item).toHaveTextContent('Spring quiz')
    expect(item).toHaveTextContent('Bakery Co')
    expect(within(item).getByTestId('video-use-counted')).toHaveTextContent('Final')
    expect(within(item).getByTestId('video-use-provided-figures-qualified-value')).toHaveTextContent('9')
    expect(posted[0].path).toBe('/api/queries/qb.sponsor.video-uses-provided')
    await expectAccessible(container)

    await user.click(within(item).getByTestId('video-use-confirm'))
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_CONFIRM/latest')?.body)
      .toEqual({ videoUseId: 'use-1', baseRevision: 2 }))

    await user.click(within(item).getByTestId('video-use-reject'))
    const dialog = await screen.findByTestId('video-use-reject-dialog')
    expect(within(dialog).getByTestId('video-use-reject-send')).toBeDisabled()
    await user.type(within(dialog).getByTestId('video-use-reason-input'), 'The price is 60,000 yen')
    await expectAccessible(document.body)
    await user.click(within(dialog).getByTestId('video-use-reject-send'))
    await waitFor(() => expect(posted.find((p) => p.path === '/api/processes/QB_VIDEO_USE_REJECT/latest')?.body)
      .toEqual({ videoUseId: 'use-1', reason: 'The price is 60,000 yen', baseRevision: 2 }))
  })

  it('shows the figures of a use not sent yet, and says when no publication uses the videos', async () => {
    serve([row(null, { refreshedAt: '2026-11-08T19:53:00Z', opens: 6, successes: 7, goes: 8, qualified: null })])
    const first = wrap(<VideoUsesProvided />)
    const item = await screen.findByTestId('video-use-provided')
    expect(within(item).getByTestId('video-use-not-sent')).toHaveTextContent('Not sent to you yet')
    expect(within(item).queryByTestId('video-use-confirm')).toBeNull()
    first.unmount()
    vi.restoreAllMocks()
    serve([])
    const { container } = wrap(<VideoUsesProvided />)
    expect(await screen.findByTestId('video-uses-provided-empty')).toBeInTheDocument()
    await expectAccessible(container)
  })
})

const HISTORY = [
  { versionNo: 1, processName: 'QB_VIDEO_USE_SAVE', recordedAt: '2026-11-01T01:00:00Z', status: 'DRAFT',
    pricing: 'FIXED', price: 50000, periodFrom: '2026-11-01', periodTo: '2026-11-30', rejectReason: null, revision: 1 },
  { versionNo: 2, processName: 'QB_VIDEO_USE_SUBMIT', recordedAt: '2026-11-01T02:00:00Z', status: 'SUBMITTED',
    pricing: 'FIXED', price: 50000, periodFrom: '2026-11-01', periodTo: '2026-11-30', rejectReason: null, revision: 2 },
  { versionNo: 3, processName: 'QB_VIDEO_USE_REJECT', recordedAt: '2026-11-02T02:00:00Z', status: 'REJECTED',
    pricing: 'FIXED', price: 50000, periodFrom: '2026-11-01', periodTo: '2026-11-30',
    rejectReason: 'We agreed 60,000 yen', revision: 3 },
  { versionNo: 4, processName: 'QB_VIDEO_USE_SAVE', recordedAt: '2026-11-03T02:00:00Z', status: 'DRAFT',
    pricing: 'FIXED', price: 60000, periodFrom: '2026-11-01', periodTo: '2026-11-30',
    rejectReason: 'We agreed 60,000 yen', revision: 4 },
]

describe('the history of a ledger entry (F-S14)', () => {
  it('shows the user every version on request: the act, the side, the time and the reason sent back', async () => {
    const posted = serve([row({ ...USE, status: 'DRAFT', revision: 4, pricing: 'FIXED', price: 60000,
      rejectReason: 'We agreed 60,000 yen' })], HISTORY)
    const user = userEvent.setup()
    const { container } = wrap(<VideoUseSection publicationId="pub-1" />)
    const toggle = await screen.findByTestId('video-use-history-toggle')
    expect(screen.queryByTestId('video-use-history')).toBeNull()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    const entries = await screen.findAllByTestId('video-use-history-entry')
    expect(entries.map((e) => e.dataset.act)).toEqual(['CREATED', 'SUBMITTED', 'REJECTED', 'CORRECTED'])
    expect(entries[0]).toHaveTextContent('Written · You')
    expect(entries[2]).toHaveTextContent('Sent back · Cat Co')
    expect(within(entries[2]).getByTestId('video-use-history-reason')).toHaveTextContent('Reason: We agreed 60,000 yen')
    expect(entries[3]).toHaveTextContent('60,000 yen')
    expect(posted.find((p) => p.path === '/api/queries/qb.sponsor.video-use-history')?.body)
      .toMatchObject({ params: { videoUseId: 'use-1' } })
    await expectAccessible(container)
  })

  it('shows the provider the same history through its own side', async () => {
    const posted = serve([row({ ...USE, status: 'REJECTED', revision: 3, pricing: 'FIXED', price: 50000,
      submittedAt: '2026-11-01T02:00:00Z', rejectReason: 'We agreed 60,000 yen' })], HISTORY.slice(0, 3))
    const user = userEvent.setup()
    const { container } = wrap(<VideoUsesProvided />)
    await user.click(await screen.findByTestId('video-use-history-toggle'))
    const entries = await screen.findAllByTestId('video-use-history-entry')
    expect(entries).toHaveLength(3)
    expect(entries[0]).toHaveTextContent('Written · Bakery Co')
    expect(entries[2]).toHaveTextContent('Sent back · You')
    expect(posted.map((p) => p.path)).toContain('/api/queries/qb.sponsor.video-use-history-provided')
    await expectAccessible(container)
  })
})

describe('the texts of the ledger (F-S14)', () => {
  it('has the same keys and placeholders in every language, none empty', () => {
    const english = keyPaths(videoUseMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(videoUseMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
    const placeholders = (text: string) => [...text.matchAll(/{{(\w+)}}/g)].map((m) => m[1]).sort()
    const texts = (value: unknown, prefix = ''): [string, string][] => Object.entries(value as object)
      .flatMap(([key, child]) => typeof child === 'string' ? [[`${prefix}${key}`, child] as [string, string]]
        : texts(child, `${prefix}${key}.`))
    const en = new Map(texts(videoUseMessages.en))
    for (const lng of ['zh', 'ja'] as const) {
      for (const [key, text] of texts(videoUseMessages[lng])) {
        expect(placeholders(text), `${lng} ${key}`).toEqual(placeholders(en.get(key)!))
      }
    }
  })
})
