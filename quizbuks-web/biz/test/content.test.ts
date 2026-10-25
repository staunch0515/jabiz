import { describe, expect, it } from 'vitest'
import { LANGUAGES, PAGES, contentOf } from '../src/content/index.mjs'
import { renderPage, pagePath } from '../src/templates/page.mjs'

/** Every key path of an object, arrays by length. */
function shape(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) {
    return [`${prefix}[${value.length}]`, ...value.flatMap((item, i) => shape(item, `${prefix}[${i}]`))]
  }
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, child]) => shape(child, prefix ? `${prefix}.${key}` : key))
  }
  return [prefix]
}

describe('the three languages', () => {
  it('have the same shape, so that no language misses a text', () => {
    const [en, zh, ja] = LANGUAGES.map((language) => shape(contentOf(language)))
    expect(zh).toEqual(en)
    expect(ja).toEqual(en)
  })

  it('name the contact address once per language and the same one', () => {
    for (const language of LANGUAGES) {
      expect(contentOf(language).contact.email.address).toBe('staunch0515@gmail.com')
    }
  })
})

describe('the rendered pages', () => {
  const assets = { css: '/biz/assets/site-x.css', js: '/biz/assets/site-x.js' }
  const tryQuizzes = { en: { url: '', qr: '' }, zh: { url: 'https://quizbuks.com/taker/p/abc', qr: 'qr-zh.png' },
    ja: { url: '', qr: '' } }

  it('render every page of every language with its language, alternates and no inline script', () => {
    for (const language of LANGUAGES) {
      for (const page of PAGES) {
        const html = renderPage({ language, page, content: contentOf(language), base: '/biz/', tryQuizzes, assets })
        expect(html).toContain(`<html lang="${{ en: 'en', zh: 'zh-Hans', ja: 'ja' }[language]}"`)
        expect(html).toContain(`hreflang="x-default" href="${pagePath('en', page, '/biz/')}"`)
        expect(html).toContain(`<script type="module" src="${assets.js}"></script>`)
        expect(html).not.toMatch(/<script>[^<]/)
        expect(html.replace(/ style="--w:\d+%"/g, '')).not.toMatch(/ style="/)
        expect(html).not.toMatch(/ on[a-z]+="/)
        expect(html).toContain('data-signup="taker"')
        expect(html).toContain('data-signup="sponsor"')
        expect(html.match(/<h1[ >]/g)).toHaveLength(1)
      }
    }
  })

  it('escape the content and link the languages to the same page', () => {
    const content = structuredClone(contentOf('en'))
    content.home.title = 'Fish & <chips>'
    const html = renderPage({ language: 'en', page: 'home', content, base: '/biz/', tryQuizzes, assets })
    expect(html).toContain('Fish &amp; &lt;chips&gt;')
    const pricing = renderPage({ language: 'zh', page: 'pricing', content: contentOf('zh'), base: '/biz/', tryQuizzes,
      assets })
    expect(pricing).toContain('href="/biz/pricing/" lang="en" hreflang="en" data-lang="en"')
    expect(pricing).toContain('href="/biz/ja/pricing/" lang="ja" hreflang="ja" data-lang="ja"')
  })

  it('show the try-it quiz as a button and a QR code when given, a placeholder otherwise', () => {
    const html = renderPage({ language: 'en', page: 'home', content: contentOf('en'), base: '/biz/', tryQuizzes, assets })
    expect(html).toContain('href="https://quizbuks.com/taker/p/abc"')
    expect(html).toContain('src="/biz/qr-zh.png"')
    expect(html.match(/QR code coming soon/g)!.length).toBeGreaterThanOrEqual(2)
    expect(html).toContain('Coming soon')
  })
})
