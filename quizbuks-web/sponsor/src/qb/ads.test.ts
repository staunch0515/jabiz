import { afterEach, describe, expect, it } from 'vitest'
import { ADSENSE_CLIENT, loadAds } from './ads'

afterEach(() => {
  Reflect.deleteProperty(navigator, 'webdriver')
  document.head.querySelectorAll('script').forEach((script) => script.remove())
})

describe('Google AdSense', () => {
  it('adds the publisher\'s loader to the head once, as AdSense gives it', () => {
    expect(loadAds()).toBe(true)
    const scripts = document.head.querySelectorAll('script')
    expect(scripts).toHaveLength(1)
    const script = scripts[0]
    expect(script.src).toBe(`https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${ADSENSE_CLIENT}`)
    expect(script.async).toBe(true)
    expect(script.crossOrigin).toBe('anonymous')
    expect(ADSENSE_CLIENT).toBe('ca-pub-8399814237019515')
    expect(loadAds()).toBe(false)
    expect(document.head.querySelectorAll('script')).toHaveLength(1)
  })

  it('adds nothing in a store app\'s shell, nor in an automated browser', () => {
    expect(loadAds({ skip: true })).toBe(false)
    expect(document.head.querySelectorAll('script')).toHaveLength(0)
    Object.defineProperty(navigator, 'webdriver', { configurable: true, get: () => true })
    expect(loadAds()).toBe(false)
    expect(document.head.querySelectorAll('script')).toHaveLength(0)
  })
})
