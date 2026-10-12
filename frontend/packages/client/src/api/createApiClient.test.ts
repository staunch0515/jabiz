import { afterEach, describe, expect, it, vi } from 'vitest'
import { json, urlOf } from '../test/locks'
import { createApiClient, setStepUpHandler } from './client'
import { configureSession, session } from './session'

/** The types an application would generate from its own OpenAPI snapshot. */
interface AppPaths {
  '/api/app/quizzes/{id}': {
    get: {
      parameters: { path: { id: string } }
      responses: { 200: { content: { 'application/json': { id: string; title: string } } } }
    }
  }
}

describe('createApiClient (decision D43; ROADMAP 16c-7)', () => {
  afterEach(() => {
    setStepUpHandler(null)
    session.clear()
    configureSession('admin')
    vi.unstubAllGlobals()
  })

  it("calls an application's own paths with the platform's session: refresh on 401, step-up on MFA_REQUIRED", async () => {
    configureSession('quiz')
    session.store('expired', 'r1')
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = urlOf(input)
      if (url.endsWith('/api/auth/refresh')) {
        seen.push(`refresh ${String(init?.body)}`)
        return json(200, { accessToken: 'fresh', refreshToken: 'r2' })
      }
      const auth = (input as Request).headers.get('Authorization')
      seen.push(`${new URL(url).pathname} ${auth} ${(input as Request).headers.get('Accept-Language')}`)
      if (auth === 'Bearer expired') return json(401, {})
      if (auth === 'Bearer fresh') return json(403, { violations: [{ ruleCode: 'MFA_REQUIRED' }] })
      return json(200, { id: 'q1', title: 'Capitals' })
    }))
    const handler = vi.fn(async () => {
      session.storeAccess('stepped')
      return true
    })
    setStepUpHandler(handler)

    const app = createApiClient<AppPaths>()
    const { data } = await app.GET('/api/app/quizzes/{id}', { params: { path: { id: 'q1' } } })

    expect(data?.title).toBe('Capitals')
    expect(handler).toHaveBeenCalledOnce()
    expect(seen.map((s) => s.replace(/ (zh|ja|en)$/, ''))).toEqual([
      '/api/app/quizzes/q1 Bearer expired',
      'refresh {"refreshToken":"r1","entry":"quiz"}',
      '/api/app/quizzes/q1 Bearer fresh',
      '/api/app/quizzes/q1 Bearer stepped',
    ])
  })
})
