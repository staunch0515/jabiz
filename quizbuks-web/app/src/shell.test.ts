import { afterEach, describe, expect, it, vi } from 'vitest'
import { ANDROID_PACKAGE, nativeShell, rememberShell } from './shell'

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
})
