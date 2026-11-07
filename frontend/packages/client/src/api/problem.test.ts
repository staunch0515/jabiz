import { describe, expect, it } from 'vitest'
import { ApiError, retryAfterSeconds, toApiError } from './problem'

describe('ApiError', () => {
  it('carries the violations of a ProblemDetail', async () => {
    const response = new Response(
      JSON.stringify({
        title: 'Bad Request',
        detail: 'Validation failed',
        violations: [
          { field: 'code', ruleCode: 'TOO_LONG', message: 'Too long.' },
          { field: null, ruleCode: 'DATASET_READ_ONLY', message: 'Read-only.' },
        ],
      }),
      { status: 400 },
    )
    const error = await toApiError(response)
    expect(error.status).toBe(400)
    expect(error.forField('code').map((v) => v.ruleCode)).toEqual(['TOO_LONG'])
    expect(error.general.map((v) => v.ruleCode)).toEqual(['DATASET_READ_ONLY'])
    expect(error.display).toBe('Too long. Read-only.')
    expect(error.message).toBe('Validation failed')
  })

  it('works without a body', async () => {
    const error = await toApiError(new Response('not json', { status: 502 }))
    expect(error).toBeInstanceOf(ApiError)
    expect(error.violations).toEqual([])
    expect(error.display).toBe('HTTP 502')
  })

  it('carries the seconds of Retry-After on 429, so that a form can wait as long (phase 17zk)', async () => {
    const response = new Response(JSON.stringify({ violations: [{ field: null, ruleCode: 'RATE_LIMITED', message: 'Wait.' }] }),
      { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '58' } })
    const error = await toApiError(response)
    expect(error.status).toBe(429)
    expect(error.retryAfter).toBe(58)
    expect((await toApiError(new Response(null, { status: 429 }))).retryAfter).toBeUndefined()
  })
})

describe('retryAfterSeconds', () => {
  it('reads seconds and HTTP dates, and nothing else', () => {
    const now = Date.parse('2026-11-07T10:00:00Z')
    expect(retryAfterSeconds('30', now)).toBe(30)
    expect(retryAfterSeconds(' 0 ', now)).toBe(0)
    expect(retryAfterSeconds('Sat, 07 Nov 2026 10:01:00 GMT', now)).toBe(60)
    expect(retryAfterSeconds('Sat, 07 Nov 2026 09:00:00 GMT', now)).toBe(0)
    expect(retryAfterSeconds('soon', now)).toBeUndefined()
    expect(retryAfterSeconds(null, now)).toBeUndefined()
  })
})
