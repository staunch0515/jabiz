import { vi } from 'vitest'
import type { PublicQueryId, QueryRow } from '../api/public-queries'

type Rows = { [Q in PublicQueryId]?: (search: URLSearchParams) => Partial<QueryRow<Q>>[] }

/** Answers /api/public/queries/{id} from `rows`; records every request. Templates not listed answer no rows. */
export function mockPublicApi(rows: Rows) {
  const requests: URL[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://site.test')
    requests.push(url)
    const match = /^\/api\/public\/queries\/([^/]+)$/.exec(url.pathname)
    if (!match) return new Response('not found', { status: 404 })
    const id = decodeURIComponent(match[1]) as PublicQueryId
    const items = (rows[id] as ((s: URLSearchParams) => unknown[]) | undefined)?.(url.searchParams) ?? []
    return new Response(JSON.stringify({ items, total: null, offset: 0, limit: 100 }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { requests, of: (id: PublicQueryId) => requests.filter((u) => u.pathname.endsWith(`/${id}`)) }
}
