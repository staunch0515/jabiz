import { describe, expect, it } from 'vitest'
import { ApiError, toApiError } from './problem'

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
})
