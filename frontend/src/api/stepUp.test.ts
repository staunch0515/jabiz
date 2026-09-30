import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, sessionFetch, setStepUpHandler, stepUp } from './client'
import { session } from './session'

const MFA_REQUIRED = { status: 403, violations: [{ ruleCode: 'MFA_REQUIRED', message: 'confirm' }] }

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('step-up (docs/design/10-security.md section 10)', () => {
  beforeEach(() => {
    session.store('old-access', 'refresh')
  })

  afterEach(() => {
    setStepUpHandler(null)
    session.clear()
    vi.unstubAllGlobals()
  })

  it('asks for a second factor on MFA_REQUIRED and repeats the request with the new token', async () => {
    const fetch = vi.fn(async (request: Request) =>
      request.headers.get('Authorization') === 'Bearer new-access' ? json(200, { processSeqId: 1 }) : json(403, MFA_REQUIRED))
    vi.stubGlobal('fetch', fetch)
    const handler = vi.fn(async () => {
      session.storeAccess('new-access')
      return true
    })
    setStepUpHandler(handler)

    const result = await api.POST('/api/processes/{name}/{version}', {
      params: { path: { name: 'PRICE_ADJUST', version: 'latest' } },
      body: { priceId: 'p' },
    })

    expect(result.response.status).toBe(200)
    expect(handler).toHaveBeenCalledOnce()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(await (fetch.mock.calls[1][0] as Request).text()).toBe('{"priceId":"p"}')
  })

  it('leaves other refusals and a cancelled prompt to the caller', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(403, { violations: [{ ruleCode: 'PERMISSION_DENIED' }] })))
    const handler = vi.fn(async () => true)
    setStepUpHandler(handler)
    expect((await sessionFetch('/api/files')).status).toBe(403)
    expect(handler).not.toHaveBeenCalled()

    vi.stubGlobal('fetch', vi.fn(async () => json(403, MFA_REQUIRED)))
    setStepUpHandler(async () => false)
    expect((await sessionFetch('/api/files')).status).toBe(403)
  })

  it('stores the access token of a step-up', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { accessToken: 'stepped', mfaAt: '2026-01-31T09:00:00Z' })))
    await stepUp('123456')
    expect(session.accessToken()).toBe('stepped')
    expect(session.refreshToken()).toBe('refresh')
  })
})
