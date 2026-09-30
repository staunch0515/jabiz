import { afterEach, describe, expect, it } from 'vitest'
import { takeReturnPath } from './oidc'

describe('takeReturnPath', () => {
  afterEach(() => window.sessionStorage.clear())

  it('returns the stored path once, and only paths of this application', () => {
    window.sessionStorage.setItem('jabiz.oidcReturnTo', '/reports?id=x')
    expect(takeReturnPath()).toBe('/reports?id=x')
    expect(takeReturnPath()).toBe('/data')
    for (const foreign of ['//evil.example.com', 'https://evil.example.com', 'javascript:alert(1)']) {
      window.sessionStorage.setItem('jabiz.oidcReturnTo', foreign)
      expect(takeReturnPath()).toBe('/data')
    }
  })
})
