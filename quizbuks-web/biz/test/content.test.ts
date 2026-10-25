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
  const siteUrl = 'https://quizbuks.com'
  const tryQuizzes = { en: { url: '', qr: '' }, zh: { url: 'https://quizbuks.com/taker/p/abc', qr: 'qr-zh.png' },
    ja: { url: '', qr: '' } }

  it('render every page of every language with its language, alternates and no inline script', () => {
    for (const language of LANGUAGES) {
      for (const page of PAGES) {
        const html = renderPage({ language, page, content: contentOf(language), base: '/biz/', tryQuizzes, assets, siteUrl })
        expect(html).toContain(`<html lang="${{ en: 'en', zh: 'zh-Hans', ja: 'ja' }[language]}" data-base="/biz/" data-page="${page}" data-language="${language}"`)
        // Search engines want absolute canonical and alternate links.
        expect(html).toContain(`hreflang="x-default" href="${siteUrl}${pagePath('en', page, '/biz/')}"`)
        expect(html).toContain(`<link rel="canonical" href="${siteUrl}${pagePath(language, page, '/biz/')}" />`)
        // A classic script in the head: the language redirect runs before the page is painted.
        expect(html).toContain(`<script src="${assets.js}"></script>`)
        expect(html).not.toMatch(/<script>[^<]/)
        expect(html.replace(/ style="--w:\d+%"/g, '')).not.toMatch(/ style="/)
        expect(html).not.toMatch(/ on[a-z]+="/)
        expect(html).toContain('data-signup="taker"')
        expect(html).toContain('data-signup="sponsor"')
        expect(html.match(/<h1[ >]/g)).toHaveLength(1)
      }
    }
  })

  it('carry the wordmark, and the drawings only as decoration (Q32)', () => {
    const home = renderPage({ language: 'en', page: 'home', content: contentOf('en'), base: '/biz/', tryQuizzes, assets, siteUrl })
    // The header and the footer: the coin beside the name, no image file.
    expect(home.match(/<a class="brand" href="\/biz\/"><svg class="coin"/g)).toHaveLength(2)
    expect(home).not.toContain('icon-192.png" alt=""')
    // The four steps and the hero carry a drawing each; every drawing is hidden from assistive technology.
    expect(home.match(/<svg class="art"/g)).toHaveLength(5)
    expect(home).not.toMatch(/<svg class="(art|hero-art|coin)"(?![^>]*aria-hidden="true")/)
    for (const page of ['merchants', 'takers', 'surveys'] as const) {
      const html = renderPage({ language: 'ja', page, content: contentOf('ja'), base: '/biz/', tryQuizzes, assets, siteUrl })
      expect(html.match(/<svg class="hero-art"/g)).toHaveLength(1)
    }
    const pricing = renderPage({ language: 'zh', page: 'pricing', content: contentOf('zh'), base: '/biz/', tryQuizzes, assets, siteUrl })
    expect(pricing).not.toContain('class="hero-art"')
  })

  it('escape the content and link the languages to the same page', () => {
    const content = structuredClone(contentOf('en'))
    content.home.title = 'Fish & <chips>'
    const html = renderPage({ language: 'en', page: 'home', content, base: '/biz/', tryQuizzes, assets, siteUrl })
    expect(html).toContain('Fish &amp; &lt;chips&gt;')
    const pricing = renderPage({ language: 'zh', page: 'pricing', content: contentOf('zh'), base: '/biz/', tryQuizzes,
      assets, siteUrl })
    expect(pricing).toContain('href="/biz/pricing/" lang="en" hreflang="en" data-lang="en"')
    expect(pricing).toContain('href="/biz/ja/pricing/" lang="ja" hreflang="ja" data-lang="ja"')
    // A plan's "talk to us" goes to the contact page of the same language under the base, not a hard-coded path.
    expect(pricing).toContain('href="/biz/zh/contact/">联系我们</a>')
    expect(pricing).not.toContain('href="/biz/contact/"')
  })

  it('show the try-it quiz as a button and a QR code when given, a placeholder otherwise', () => {
    const html = renderPage({ language: 'en', page: 'home', content: contentOf('en'), base: '/biz/', tryQuizzes, assets, siteUrl })
    expect(html).toContain('href="https://quizbuks.com/taker/p/abc"')
    expect(html).toContain('src="/biz/qr-zh.png"')
    expect(html.match(/QR code coming soon/g)!.length).toBeGreaterThanOrEqual(2)
    expect(html).toContain('Coming soon')
  })
})
