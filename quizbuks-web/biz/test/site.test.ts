import { describe, expect, it, vi } from 'vitest'
import { isPhone, openSignUp, parsePath, pathFor, pickLanguage, redirectTarget, PHONE_WINDOW } from '../src/assets/site.js'

describe('language from the browser', () => {
  it('takes zh and ja by their primary tag, English otherwise', () => {
    expect(pickLanguage(['zh-CN', 'en'])).toBe('zh')
    expect(pickLanguage(['ja-JP'])).toBe('ja')
    expect(pickLanguage(['en-US', 'ja'])).toBe('en')
    expect(pickLanguage(['fr-FR', 'de'])).toBe('en')
    expect(pickLanguage(['zh-Hant-TW'])).toBe('zh')
    expect(pickLanguage([])).toBe('en')
    expect(pickLanguage(undefined)).toBe('en')
  })
})

describe('paths under the base', () => {
  it('parses the language and the page', () => {
    expect(parsePath('/biz/', '/biz/')).toEqual({ language: 'en', page: '' })
    expect(parsePath('/biz/pricing/', '/biz/')).toEqual({ language: 'en', page: 'pricing/' })
    expect(parsePath('/biz/zh/', '/biz/')).toEqual({ language: 'zh', page: '' })
    expect(parsePath('/biz/ja/pricing/', '/biz/')).toEqual({ language: 'ja', page: 'pricing/' })
    expect(parsePath('/taker/', '/biz/')).toBeNull()
  })

  it('builds the same page in another language', () => {
    expect(pathFor('zh', 'pricing/', '/biz/')).toBe('/biz/zh/pricing/')
    expect(pathFor('en', 'pricing/', '/biz/')).toBe('/biz/pricing/')
    expect(pathFor('ja', '', '/biz/')).toBe('/biz/ja/')
  })
})

describe('the first visit', () => {
  const base = '/biz/'
  it('sends a Chinese browser from the English root to the Chinese page, keeping the page', () => {
    expect(redirectTarget({ pathname: '/biz/', base, stored: null, browserLanguages: ['zh-CN'] })).toBe('/biz/zh/')
    expect(redirectTarget({ pathname: '/biz/pricing/', base, stored: null, browserLanguages: ['ja'] }))
      .toBe('/biz/ja/pricing/')
  })

  it('leaves an English browser and a page already in the right language alone', () => {
    expect(redirectTarget({ pathname: '/biz/', base, stored: null, browserLanguages: ['en-GB'] })).toBeNull()
    expect(redirectTarget({ pathname: '/biz/zh/', base, stored: null, browserLanguages: ['zh'] })).toBeNull()
  })

  it('follows a remembered choice over the browser, and never moves a visitor off a language page', () => {
    expect(redirectTarget({ pathname: '/biz/', base, stored: 'ja', browserLanguages: ['zh'] })).toBe('/biz/ja/')
    expect(redirectTarget({ pathname: '/biz/', base, stored: 'en', browserLanguages: ['zh'] })).toBeNull()
    // A shared Japanese link opened by a Chinese browser stays Japanese: the visitor chose that link.
    expect(redirectTarget({ pathname: '/biz/ja/', base, stored: null, browserLanguages: ['zh'] })).toBeNull()
    expect(redirectTarget({ pathname: '/biz/ja/', base, stored: 'zh', browserLanguages: ['zh'] })).toBeNull()
  })

  it('ignores paths outside the base and nonsense in storage', () => {
    expect(redirectTarget({ pathname: '/taker/', base, stored: null, browserLanguages: ['zh'] })).toBeNull()
    expect(redirectTarget({ pathname: '/biz/', base, stored: 'xx', browserLanguages: ['zh'] })).toBe('/biz/zh/')
  })
})

function fakeWindow({ coarse = false, width = 1280 } = {}) {
  return {
    matchMedia: (query: string) => ({ matches: query.includes('coarse') ? coarse : false }),
    innerWidth: width,
    open: vi.fn(() => ({})),
    location: { assign: vi.fn() },
  }
}

describe('sign-up buttons', () => {
  it('open a phone-sized window for a taker on a desktop', () => {
    const win = fakeWindow()
    openSignUp('taker', '/taker/signup', win as never)
    expect(win.open).toHaveBeenCalledWith('/taker/signup', 'quizbuks-taker', PHONE_WINDOW)
    expect(PHONE_WINDOW).toContain('width=390')
    expect(PHONE_WINDOW).toContain('height=844')
    expect(win.location.assign).not.toHaveBeenCalled()
  })

  it('fall back to a new tab when the browser blocks the window', () => {
    const win = fakeWindow()
    win.open = vi.fn().mockReturnValueOnce(null).mockReturnValueOnce({})
    openSignUp('taker', '/taker/signup', win as never)
    expect(win.open).toHaveBeenLastCalledWith('/taker/signup', '_blank', 'noopener')
  })

  it('navigate directly on a phone', () => {
    const coarse = fakeWindow({ coarse: true })
    openSignUp('taker', '/taker/signup', coarse as never)
    expect(coarse.location.assign).toHaveBeenCalledWith('/taker/signup')
    expect(coarse.open).not.toHaveBeenCalled()
    const narrow = fakeWindow({ width: 390 })
    expect(isPhone(narrow as never)).toBe(true)
  })

  it('always give a merchant a new tab', () => {
    for (const win of [fakeWindow(), fakeWindow({ coarse: true })]) {
      openSignUp('sponsor', '/sponsor/signup', win as never)
      expect(win.open).toHaveBeenCalledWith('/sponsor/signup', '_blank', 'noopener')
    }
  })
})
