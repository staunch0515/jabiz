import { ApiError } from '@jabiz/client'
import { describe, expect, it, vi } from 'vitest'
import { CONSENTS_QUERY, newQbQueryClient, noticeConsentProblem } from './ConsentGate'
import { consentRequired } from './problems'

const refused = new ApiError(422, {
  detail: 'Refused',
  violations: [{ field: null, ruleCode: 'QB_CONSENT_REQUIRED', message: 'Accept version 2 of TERMS_APP first.' }],
})
const other = new ApiError(422, {
  violations: [{ field: 'amount', ruleCode: 'QB_TOPUP_AMOUNT_RANGE', message: 'Out of range.' }],
})

/**
 * Review M3: the server refuses money and contract actions while a version must be accepted. Wherever that refusal
 * arrives, the caller's standing is read again, so that the prompt comes up at once.
 */
describe('a refusal for consent', () => {
  it('is told apart from other refusals', () => {
    expect(consentRequired(refused)).toBe(true)
    expect(consentRequired(other)).toBe(false)
    expect(consentRequired(new TypeError('offline'))).toBe(false)
  })

  it('reads the standing again, from a mutation or a query of the pages', async () => {
    const client = newQbQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    await expect(client.getMutationCache().build(client, { mutationFn: () => Promise.reject(other) }).execute(
      undefined)).rejects.toBe(other)
    expect(invalidate).not.toHaveBeenCalled()
    await expect(client.getMutationCache().build(client, { mutationFn: () => Promise.reject(refused) }).execute(
      undefined)).rejects.toBe(refused)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: CONSENTS_QUERY })
    invalidate.mockClear()
    await expect(client.fetchQuery({ queryKey: ['other'], queryFn: () => Promise.reject(refused) }))
      .rejects.toBe(refused)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: CONSENTS_QUERY })
  })

  it('reads the standing again from a call outside React Query', () => {
    const client = newQbQueryClient()
    const invalidate = vi.spyOn(client, 'invalidateQueries')
    noticeConsentProblem(client, other)
    expect(invalidate).not.toHaveBeenCalled()
    noticeConsentProblem(client, refused)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: CONSENTS_QUERY })
  })
})
