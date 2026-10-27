import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  captureInstallPrompt, installOfferAllowed, installSituation, isIos, readInstallDismissals, rememberInstallDismissed,
  resetInstallPrompt, useInstallPrompt, type InstallEnvironment,
} from './install'

const UA = {
  iphoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1',
  ipad: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
  mac: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
  androidChrome: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
  androidFirefox: 'Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0',
  line: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Safari Line/14.17.0',
  wechat: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50',
  instagram: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0.0',
  windowsChrome: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
}

const env = (userAgent: string, more: Partial<InstallEnvironment> = {}): InstallEnvironment =>
  ({ userAgent, maxTouchPoints: 0, standalone: false, canPrompt: false, ...more })

afterEach(() => {
  resetInstallPrompt()
  window.localStorage.clear()
})

describe('installSituation', () => {
  it('says how each browser installs', () => {
    expect(installSituation(env(UA.iphoneSafari, { maxTouchPoints: 5 }))).toBe('ios')
    expect(installSituation(env(UA.iphoneChrome, { maxTouchPoints: 5 }))).toBe('ios')
    // An iPad says it is a Mac; only the touch screen tells it apart.
    expect(installSituation(env(UA.ipad, { maxTouchPoints: 5 }))).toBe('ios')
    expect(installSituation(env(UA.mac))).toBe('none')
    expect(installSituation(env(UA.androidChrome, { canPrompt: true }))).toBe('prompt')
    // Chrome without its event: nothing (not qualified yet, or installed already).
    expect(installSituation(env(UA.androidChrome))).toBe('none')
    expect(installSituation(env(UA.androidFirefox))).toBe('menu')
    expect(installSituation(env(UA.windowsChrome, { canPrompt: true }))).toBe('none')
  })

  it('sends the browsers inside other apps to a real browser', () => {
    expect(installSituation(env(UA.line, { maxTouchPoints: 5 }))).toBe('in-app')
    expect(installSituation(env(UA.wechat, { canPrompt: true }))).toBe('in-app')
    expect(installSituation(env(UA.instagram, { maxTouchPoints: 5 }))).toBe('in-app')
  })

  it('offers nothing once opened from the home screen', () => {
    expect(installSituation(env(UA.iphoneSafari, { standalone: true }))).toBe('installed')
    expect(installSituation(env(UA.androidChrome, { standalone: true, canPrompt: true }))).toBe('installed')
  })

  it('tells an iPad from a Mac by touch', () => {
    expect(isIos(UA.ipad, 5)).toBe(true)
    expect(isIos(UA.mac, 0)).toBe(false)
    expect(isIos(UA.iphoneSafari, 0)).toBe(true)
  })
})

/** The browser's `beforeinstallprompt`, answered with `outcome`. */
function offerEvent(outcome: 'accepted' | 'dismissed') {
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & {
    prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }>
  }
  event.prompt = vi.fn(async () => undefined)
  event.userChoice = Promise.resolve({ outcome })
  return event
}

describe('useInstallPrompt', () => {
  it('keeps the browser\'s offer, suppresses its own bar and shows its confirmation once', async () => {
    captureInstallPrompt()
    const { result } = renderHook(() => useInstallPrompt({ userAgent: UA.androidChrome, standalone: false }))
    expect(result.current.situation).toBe('none')
    const event = offerEvent('accepted')
    act(() => {
      window.dispatchEvent(event)
    })
    expect(event.defaultPrevented).toBe(true)
    expect(result.current.situation).toBe('prompt')
    let outcome = ''
    await act(async () => {
      outcome = await result.current.install()
    })
    expect(outcome).toBe('accepted')
    expect(event.prompt).toHaveBeenCalledOnce()
    expect(result.current.situation).toBe('installed')
    // The offer works once.
    await act(async () => {
      outcome = await result.current.install()
    })
    expect(outcome).toBe('unavailable')
  })

  it('offers again later when the person said no, and stops once the app is installed', async () => {
    captureInstallPrompt()
    const { result } = renderHook(() => useInstallPrompt({ userAgent: UA.androidChrome, standalone: false }))
    act(() => {
      window.dispatchEvent(offerEvent('dismissed'))
    })
    let outcome = ''
    await act(async () => {
      outcome = await result.current.install()
    })
    expect(outcome).toBe('dismissed')
    expect(result.current.situation).toBe('none')
    act(() => {
      window.dispatchEvent(new Event('appinstalled'))
    })
    expect(result.current.situation).toBe('installed')
  })
})

describe('the offer the person closed', () => {
  it('comes back after 30 days, at most three times', () => {
    const day = 86_400_000
    expect(installOfferAllowed(null, 0)).toBe(true)
    expect(installOfferAllowed({ at: 0, count: 1 }, 29 * day)).toBe(false)
    expect(installOfferAllowed({ at: 0, count: 1 }, 30 * day)).toBe(true)
    expect(installOfferAllowed({ at: 0, count: 3 }, 365 * day)).toBe(false)
    expect(installOfferAllowed({ at: 0, count: 1 }, 2 * day, { days: 1, max: 5 })).toBe(true)
  })

  it('is remembered in this browser, per key', () => {
    expect(readInstallDismissals('taker')).toBeNull()
    rememberInstallDismissed('taker', 10)
    expect(rememberInstallDismissed('taker', 20)).toEqual({ at: 20, count: 2 })
    expect(readInstallDismissals('taker')).toEqual({ at: 20, count: 2 })
    expect(readInstallDismissals('other')).toBeNull()
    window.localStorage.setItem('jabiz.pwa.install.bad', '{not json')
    expect(readInstallDismissals('bad')).toBeNull()
  })

  it('costs nothing but the memory where storage is blocked', () => {
    const blocked = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError')
    })
    expect(rememberInstallDismissed('taker', 5)).toEqual({ at: 5, count: 1 })
    blocked.mockRestore()
  })
})
