import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  captureInstallPrompt, installBrowser, installOfferAllowed, installSituation, isIos, readInstallDismissals, rememberInstallDismissed,
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
  androidTabletChrome: 'Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  samsung: 'Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36',
  edge: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36 EdgA/130.0.0.0',
  huawei: 'Mozilla/5.0 (Linux; Android 12; HarmonyOS; NOH-AN00; HMSCore 6.13.0.302) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.196 HuaweiBrowser/15.0.4.312 Mobile Safari/537.36',
  huaweiNext: 'Mozilla/5.0 (Phone; OpenHarmony 5.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 ArkWeb/4.1.6.1 Mobile HuaweiBrowser/5.0.4.300',
  xiaomi: 'Mozilla/5.0 (Linux; U; Android 14; zh-cn; 23116PN5BC Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/118.0.5993.80 Mobile Safari/537.36 XiaoMi/MiuiBrowser/18.4.30612',
  qqBrowser: 'Mozilla/5.0 (Linux; U; Android 14; zh-cn; PJA110 Build/UKQ1.230924.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/109.0.5414.86 MQQBrowser/15.1 Mobile Safari/537.36 COVC/046913',
  uc: 'Mozilla/5.0 (Linux; U; Android 14; zh-CN; V2307A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/17.2.9.1300 Mobile Safari/537.36',
  vivo: 'Mozilla/5.0 (Linux; Android 14; V2307A Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Mobile Safari/537.36 VivoBrowser/20.1.0.0',
  wechatX5: 'Mozilla/5.0 (Linux; Android 14; 23116PN5BC Build/UKQ1.230804.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 XWEB/1160117 MMWEBSDK/20240404 MMWEBID/2795 MicroMessenger/8.0.49.2600(0x28003133) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64',
  wechatIos: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.50(0x1800323c) NetType/WIFI Language/zh_CN',
  qqApp: 'Mozilla/5.0 (Linux; Android 14; PJA110 Build/UKQ1.230924.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/109.0.5414.86 MQQBrowser/6.2 TBS/047205 Mobile Safari/537.36 V1_AND_SQ_9.0.56_6372_YYB_D QQ/9.0.56.16050 NetType/WIFI WebP/0.3.0 AppId/537215475',
  alipay: 'Mozilla/5.0 (Linux; U; Android 14; zh-CN; 23116PN5BC Build/UKQ1.230804.001) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/101.0.4951.61 UWS/3.22.2.65 Mobile Safari/537.36 AlipayClient/10.5.86.8000 Language/zh-Hans',
  webView: 'Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36',
  iphoneQqBrowser: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 MQQBrowser/15.1.0 Mobile Safari/604.1',
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

  it('guides the Android browsers without the event through their own menu (phase 17m)', () => {
    for (const ua of [UA.huawei, UA.huaweiNext, UA.xiaomi, UA.qqBrowser, UA.uc]) {
      expect(installSituation(env(ua)), ua).toBe('menu')
      // Where the browser does offer its own confirmation, that comes first.
      expect(installSituation(env(ua, { canPrompt: true })), ua).toBe('prompt')
    }
    // A browser it does not know: the general guide.
    expect(installSituation(env(UA.vivo))).toBe('menu')
    expect(installSituation(env(UA.edge))).toBe('menu')
    // Chrome (also on a tablet) and Samsung Internet offer the event: nothing until they do.
    expect(installSituation(env(UA.androidTabletChrome))).toBe('none')
    expect(installSituation(env(UA.samsung))).toBe('none')
    // iOS browsers all add from the Share menu.
    expect(installSituation(env(UA.iphoneQqBrowser, { maxTouchPoints: 5 }))).toBe('ios')
  })

  it('tells the browsers apart where the steps differ', () => {
    expect(installBrowser(UA.huawei)).toBe('huawei')
    expect(installBrowser(UA.huaweiNext)).toBe('huawei')
    expect(installBrowser(UA.xiaomi)).toBe('xiaomi')
    expect(installBrowser(UA.qqBrowser)).toBe('qq')
    expect(installBrowser(UA.uc)).toBe('uc')
    expect(installBrowser(UA.androidFirefox)).toBe('firefox')
    // WeChat and QQ show pages with QQ's engine (MQQBrowser): the app, not the browser.
    expect(installBrowser(UA.wechatX5)).toBe('wechat')
    expect(installBrowser(UA.wechatIos)).toBe('wechat')
    expect(installBrowser(UA.qqApp)).toBe('qq-app')
    expect(installBrowser(UA.vivo)).toBe('other')
    expect(installBrowser(UA.androidChrome)).toBe('other')
  })

  it('sends the browsers inside the apps used in China to a real browser', () => {
    for (const ua of [UA.wechatX5, UA.qqApp, UA.alipay, UA.webView]) {
      expect(installSituation(env(ua, { canPrompt: true })), ua).toBe('in-app')
    }
    expect(installSituation(env(UA.wechatIos, { maxTouchPoints: 5 }))).toBe('in-app')
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
