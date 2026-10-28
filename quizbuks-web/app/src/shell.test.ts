import { afterEach, describe, expect, it, vi } from 'vitest'
import { ANDROID_PACKAGE, IOS_USER_AGENT, nativeShell, rememberShell } from './shell'

function at(address: string) {
  window.history.replaceState(null, '', address)
}

afterEach(() => {
  vi.restoreAllMocks()
  window.sessionStorage.clear()
  at('/')
})

describe('telling the store app from a browser (Q49b)', () => {
  it('is a browser without the mark or the referrer', () => {
    at('/taker/')
    rememberShell(window)
    expect(nativeShell(window)).toBeNull()
  })

  it('takes the Android shell\'s parameter and drops it from the address, keeping the rest', () => {
    at('/taker/mail/verify?token=abc&shell=android#top')
    rememberShell(window)
    expect(window.location.pathname + window.location.search + window.location.hash).toBe('/taker/mail/verify?token=abc#top')
    // Still known after the address lost the mark (a navigation within the app).
    at('/taker/account')
    expect(nativeShell(window)).toBe('android')
  })

  it('takes Chrome\'s referrer of a Trusted Web Activity, and no other app\'s', () => {
    const referrer = vi.spyOn(document, 'referrer', 'get')
    referrer.mockReturnValue('android-app://com.example.other/')
    rememberShell(window)
    expect(nativeShell(window)).toBeNull()
    referrer.mockReturnValue(`android-app://${ANDROID_PACKAGE}/`)
    rememberShell(window)
    referrer.mockReturnValue('')
    expect(nativeShell(window)).toBe('android')
  })

  it('still knows on the first page when the tab\'s storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(document, 'referrer', 'get').mockReturnValue(`android-app://${ANDROID_PACKAGE}`)
    expect(() => rememberShell(window)).not.toThrow()
    expect(nativeShell(window)).toBe('android')
  })

  it('takes the iOS shell\'s parameter, and its user agent on any page', () => {
    at('/taker/?shell=ios')
    rememberShell(window)
    expect(window.location.search).toBe('')
    expect(nativeShell(window)).toBe('ios')
    window.sessionStorage.clear()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
      `Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 ${IOS_USER_AGENT}`)
    expect(nativeShell(window)).toBe('ios')
  })

  it('ignores an unknown shell in the address', () => {
    at('/taker/?shell=desktop')
    rememberShell(window)
    expect(nativeShell(window)).toBeNull()
  })
})
