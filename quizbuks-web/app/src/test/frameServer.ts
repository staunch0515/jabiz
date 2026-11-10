import { vi } from 'vitest'

/*
 * The server of the F-F4 frame tests: a verified taker in Japan, the prizes and the wallet as the test says. A call no
 * case answers gets a 404, as in the other tests of the app.
 */

export function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

export const ENTRY = { name: 'app', selfRegistration: true, requireVerifiedEmail: false, appPath: '/',
  sessionPersistence: 'device' }

export interface FrameServer {
  /** The rows `qb.app.prizes` answers (all of them; `open` and the filters are applied here as the server would). */
  prizes?: Record<string, unknown>[]
  /** The prizes query fails. */
  prizesFail?: boolean
  /** The row `qb.app.wallet` answers; none when absent (the query fails). */
  wallet?: Record<string, unknown>
  certificates?: unknown[]
  sponsor?: boolean
}

export const OPEN_STATUSES: Record<string, string[]> = {
  REDEEM: ['DUE', 'CLAIMED'], CASH: ['DUE', 'MARKED_PAID', 'OVERDUE', 'DISPUTED'],
  DIGITAL: ['DUE', 'DELIVERED', 'OVERDUE', 'DISPUTED'],
}

export function prize(over: Record<string, unknown> = {}): Record<string, unknown> {
  return { kind: 'REDEEM', prizeId: 'r1', attemptId: 'a1', sourceKind: null, code: 'R-1234', publicationId: 'p1',
    publicationName: 'Capitals week', sponsorCompany: 'Ramen B', amount: 100, description: 'A bowl of noodles',
    status: 'DUE', dueAt: '2026-10-20T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', confirmBy: null, ...over }
}

/** Installs the fetch mock; returns what was posted. */
export function frameServer(options: FrameServer = {}) {
  const posted: { path: string; body: Record<string, unknown> | null }[] = []
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
          permissions: ['qb.me', 'qb.account.read'], emailVerified: true, idleTimeoutSeconds: 0 })
      case '/api/datasets/urn:jabiz:dataset:me:QbProfile/query':
        return json(200, { items: [{ id: 'p1', version: 1, attributes: { displayName: 'Kai', countryCode: 'JP',
          city: null, preferredCurrency: 'JPY', revision: 1 } }], offset: 0, limit: 1 })
      case '/api/queries/qb.app.inbox-unread':
        return json(200, { items: [{ messages: 0, broadcasts: 0 }], offset: 0, limit: 1 })
      case '/api/queries/qb.me.consents':
        return json(200, { items: [], offset: 0, limit: 20 })
      case '/api/queries/qb.app.staff-of':
        return json(200, { items: [], offset: 0, limit: 1 })
      case '/api/queries/qb.app.prizes': {
        if (options.prizesFail) return json(500, { status: 500, title: 'Failed' })
        const params = (body?.params ?? {}) as { open?: boolean }
        const filters = (body?.filters ?? []) as { field: string; value: string }[]
        const rows = (options.prizes ?? []).filter((row) => (!params.open
          || OPEN_STATUSES[String(row.kind)].includes(String(row.status)))
          && filters.every((f) => String(row[f.field]) === f.value))
        const limit = Number(body?.limit ?? 20)
        const offset = Number(body?.offset ?? 0)
        return json(200, { items: rows.slice(offset, offset + limit), offset, limit,
          ...(body?.count ? { total: rows.length } : {}) })
      }
      case '/api/queries/qb.app.wallet':
        return options.wallet ? json(200, { items: [options.wallet], offset: 0, limit: 1 })
          : json(500, { status: 500, title: 'Failed' })
      case '/api/processes/QB_MY_CERTIFICATIONS/latest':
        return json(200, { output: { certificates: options.certificates ?? [] } })
      case '/api/queries/qb.app.my-sponsor':
        return json(200, { items: options.sponsor ? [{ sponsorId: 's1', company: 'Neko Co', reviewStatus: 'APPROVED' }] : [],
          offset: 0, limit: 1 })
      case '/api/processes/QB_REDEMPTION_PASS_ISSUE/latest':
        return json(200, { output: { redemptionId: 'r1', passKey: 'pass-key-1', passDigits: '123456',
          expiresAt: '2099-01-01T00:01:00Z' } })
      default:
        return json(404, {})
    }
  })
  const queries = (id: string) => posted.filter((p) => p.path === `/api/queries/${id}`)
  return { posted, queries }
}
