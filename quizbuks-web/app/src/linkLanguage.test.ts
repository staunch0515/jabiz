import { changeLanguage, i18n } from '@jabiz/client'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { applyLinkLanguage, linkLanguageOf } from './linkLanguage'

describe('a link\'s language (Q59b)', () => {
  beforeEach(() => localStorage.clear())
  afterEach(async () => {
    await changeLanguage('en')
    localStorage.clear()
  })

  it('is the language of someone new to the app, and leaves the address', () => {
    window.history.replaceState(null, '', '/p/pub-1?lang=zh&ref=poster#top')
    applyLinkLanguage()
    expect(i18n.language).toBe('zh')
    expect(localStorage.getItem('jabiz.language')).toBe('zh')
    expect(window.location.pathname + window.location.search + window.location.hash).toBe('/p/pub-1?ref=poster#top')
  })

  it('never changes a language chosen before, nor takes one that is not the app\'s', () => {
    localStorage.setItem('jabiz.language', 'en')
    window.history.replaceState(null, '', '/p/pub-1?lang=ja')
    applyLinkLanguage()
    expect(i18n.language).toBe('en')
    expect(window.location.search).toBe('')
    localStorage.clear()
    window.history.replaceState(null, '', '/p/pub-1?lang=fr')
    applyLinkLanguage()
    expect(i18n.language).toBe('en')
    expect(window.location.search).toBe('')
  })

  it('leaves an address without one as it is', () => {
    window.history.replaceState(null, '', '/p/pub-1?ref=poster')
    applyLinkLanguage()
    expect(window.location.search).toBe('?ref=poster')
  })

  it('is the language the publication it opened reads in, whatever the interface\'s (Q73b)', () => {
    localStorage.setItem('jabiz.language', 'en')
    window.history.replaceState(null, '', '/p/pub-2?lang=ja')
    applyLinkLanguage()
    expect(i18n.language).toBe('en')
    expect(linkLanguageOf('/p/pub-2')).toBe('ja')
    expect(linkLanguageOf('/p/pub-3')).toBeNull()
  })
})
