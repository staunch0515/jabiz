import { ApiError } from '@jabiz/client'
import { describe, expect, it } from 'vitest'
import './messages'
import { fieldProblem, generalProblem, problemText } from './problems'

const banned = new ApiError(422, {
  detail: 'Refused',
  violations: [{ field: null, ruleCode: 'QB_USER_BANNED', message: 'Your account is banned.' }],
})
const byField = new ApiError(400, {
  violations: [
    { field: 'displayName', ruleCode: 'QB_PROFILE_NAME_BLANK', message: 'Enter a name.' },
    { field: 'revision', ruleCode: 'X', message: 'Other.' },
  ],
})

describe('problems', () => {
  it('shows what the server said, translated by the server', () => {
    expect(problemText(banned)).toBe('Your account is banned.')
    expect(problemText(new ApiError(403, { detail: 'Verify your address first.' }))).toBe('Verify your address first.')
  })

  it('falls back to the generic text for server errors and anything else', () => {
    expect(problemText(new ApiError(500, { detail: 'NullPointerException' }))).toMatch(/went wrong|出错|エラー/)
    expect(problemText(new ApiError(404, undefined))).toMatch(/went wrong|出错|エラー/)
    expect(problemText(new TypeError('Failed to fetch'))).toMatch(/went wrong|出错|エラー/)
  })

  it('splits a refusal into the fields of the form and the rest', () => {
    expect(fieldProblem(byField, 'displayName')).toBe('Enter a name.')
    expect(fieldProblem(byField, 'city')).toBeUndefined()
    expect(generalProblem(byField, ['displayName'])).toBe('Other.')
    expect(generalProblem(byField, ['displayName', 'revision'])).toBeUndefined()
    expect(generalProblem(banned, ['displayName'])).toBe('Your account is banned.')
    expect(generalProblem(undefined, [])).toBeUndefined()
  })
})
