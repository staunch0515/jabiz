import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import i18next from 'i18next'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { ExposureSection } from './ExposureCounts'
import { exposureMessages } from './messages'

function serve(rows: Record<string, unknown>[]) {
  const asked: { path: string; body: unknown }[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input
      : new Request(new URL(String(input), window.location.origin), { method: init?.method, body: init?.body })
    asked.push({ path: decodeURIComponent(new URL(request.url, window.location.origin).pathname),
      body: await request.clone().json().catch(() => null) })
    return new Response(JSON.stringify({ items: rows, offset: 0, limit: 1 }), { status: 200,
      headers: { 'Content-Type': 'application/json' } })
  })
  return asked
}

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ExposureSection publicationId="pub-1" />
    </QueryClientProvider>,
  )
}

beforeEach(async () => {
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe('the exposures of a shared video (F-S13)', () => {
  it('shows what was bought, counted and charged, and what went beyond without charging it', async () => {
    const asked = serve([{ publicationId: 'pub-1', status: 'ENDED', purchased: 2, successes: 3, billable: 2,
      overshoot: 1, opens: 5, goes: 1 }])
    const { container } = renderSection()
    expect(await screen.findByTestId('exposure-purchased-value')).toHaveTextContent('2')
    expect(screen.getByTestId('exposure-successes-value')).toHaveTextContent('3')
    expect(screen.getByTestId('exposure-billable')).toHaveTextContent('Charged 2')
    expect(screen.getByTestId('exposure-overshoot')).toHaveTextContent('Beyond what you bought: 1 (not charged)')
    expect(screen.getByTestId('exposure-opens-value')).toHaveTextContent('5')
    expect(screen.getByTestId('exposure-goes-value')).toHaveTextContent('1')
    expect(screen.queryByTestId('exposure-used-up')).toBeNull()
    expect(asked.map((a) => a.path)).toContain('/api/queries/qb.sponsor.exposure')
    expect(asked[0].body).toMatchObject({ params: { publicationId: 'pub-1' } })
    await expectAccessible(container)
  })

  it('says unlimited when nothing was bought, and when the purchase is used up', async () => {
    serve([{ publicationId: 'pub-1', status: 'LIVE', purchased: null, successes: 40, billable: 40, overshoot: 0,
      opens: 0, goes: 0 }])
    const first = renderSection()
    expect(await screen.findByTestId('exposure-purchased-value')).toHaveTextContent('Unlimited')
    expect(screen.queryByTestId('exposure-billable')).toBeNull()
    first.unmount()
    vi.restoreAllMocks()
    serve([{ publicationId: 'pub-1', status: 'LIVE', purchased: 2, successes: 2, billable: 2, overshoot: 0, opens: 0,
      goes: 0 }])
    renderSection()
    expect(await screen.findByTestId('exposure-used-up')).toHaveTextContent('used')
  })

  it('shows nothing for a publication using its own videos', async () => {
    const asked = serve([])
    const { container } = renderSection()
    await vi.waitFor(() => expect(asked.length).toBe(1))
    expect(container).toBeEmptyDOMElement()
  })

  it('has the same keys and placeholders in every language, none empty', () => {
    const english = keyPaths(exposureMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(exposureMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })
})
