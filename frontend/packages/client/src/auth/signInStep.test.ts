import { afterEach, describe, expect, it } from 'vitest'
import { configureSession } from '../api/session'
import { forgetSignInStep, keepSignInStep, pendingSignInStep } from './signInStep'

afterEach(() => {
  window.sessionStorage.clear()
  configureSession('admin')
})

describe('the kept second step (phase 17zk, gap 2)', () => {
  it('keeps the step and the page to return to per entry, read until forgotten', () => {
    configureSession('taker')
    keepSignInStep({ status: 'MFA_REQUIRED', challenge: 'ch' }, '/p/42?ref=abc', 1000)
    expect(pendingSignInStep(2000)).toEqual({ step: { status: 'MFA_REQUIRED', challenge: 'ch' }, returnTo: '/p/42?ref=abc' })
    // Read, not taken: a reload of the sign-in page still finds it.
    expect(pendingSignInStep(2000)).not.toBeNull()
    // Another entry's frontend does not see it.
    configureSession('sponsor')
    expect(pendingSignInStep(2000)).toBeNull()
    configureSession('taker')
    forgetSignInStep()
    expect(pendingSignInStep(2000)).toBeNull()
  })

  it('keeps only paths of this site, nothing for a finished sign-in, and forgets a stale step', () => {
    keepSignInStep({ status: 'SIGNED_IN' }, '/x', 0)
    expect(pendingSignInStep(0)).toBeNull()
    keepSignInStep({ status: 'MFA_ENROLLMENT_REQUIRED', challenge: 'e' }, '//evil.example/x', 0)
    expect(pendingSignInStep(0)).toEqual({ step: { status: 'MFA_ENROLLMENT_REQUIRED', challenge: 'e' }, returnTo: '/' })
    expect(pendingSignInStep(5 * 60_000)).not.toBeNull()
    expect(pendingSignInStep(5 * 60_000 + 1)).toBeNull()
    window.sessionStorage.setItem('jabiz.session.admin.pendingStep', '{not json')
    expect(pendingSignInStep(0)).toBeNull()
  })
})
