import { expectAccessible } from '@jabiz/ui/testing'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import i18next from 'i18next'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { BlockersMetric } from './BlockersMetric'
import { blockerMessages } from './messages'

function serve(row: Record<string, unknown>) {
  const asked: string[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input: RequestInfo | URL) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), window.location.origin))
    asked.push(decodeURIComponent(new URL(request.url, window.location.origin).pathname))
    return new Response(JSON.stringify({ items: [row], offset: 0, limit: 1 }), { status: 200,
      headers: { 'Content-Type': 'application/json' } })
  })
  return asked
}

function renderMetric() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <BlockersMetric />
    </QueryClientProvider>,
  )
}

beforeEach(async () => {
  await i18next.changeLanguage('en')
})
afterEach(() => vi.restoreAllMocks())

describe('the takers who blocked the Sponsor (F-S8)', () => {
  it('shows the server\'s rounded count', async () => {
    const asked = serve({ blockers: 30, minGroup: 20 })
    const { container } = renderMetric()
    expect(await screen.findByTestId('metric-blockers-value')).toHaveTextContent('About 30')
    expect(screen.getByTestId('metric-blockers')).toHaveTextContent('Rounded down to tens')
    expect(asked).toContain('/api/queries/qb.sponsor.blockers')
    await expectAccessible(container)
  })

  it('says "fewer than" the minimum group when the server gives no count', async () => {
    serve({ blockers: null, minGroup: 20 })
    renderMetric()
    expect(await screen.findByTestId('metric-blockers-value')).toHaveTextContent('Fewer than 20')
  })

  it('has the same keys and placeholders in every language, none empty', () => {
    const english = keyPaths(blockerMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(blockerMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
    }
  })
})
