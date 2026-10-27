import { mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { build, settings } from '../scripts/build.mjs'
import { LANGUAGES, PAGES, contentOf } from '../src/content/index.mjs'
import { esc, withEmail } from '../src/templates/page.mjs'

const out = mkdtempSync(join(tmpdir(), 'shopify-site-'))
afterAll(() => rmSync(out, { recursive: true, force: true }))

/** The keys of a content object, nested ones joined with dots (arrays count by their length). */
function shape(value: unknown, prefix = ''): string[] {
  if (Array.isArray(value)) return [`${prefix}[${value.length}]`, ...value.flatMap((item, i) => shape(item, `${prefix}[${i}]`))]
  if (value && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, item]) => shape(item, prefix ? `${prefix}.${key}` : key))
  }
  return [prefix]
}

describe('the content', () => {
  it('has the same texts in every language', () => {
    const english = shape(contentOf('en'))
    for (const language of LANGUAGES) expect(shape(contentOf(language)), language).toEqual(english)
  })
})

describe('the built site', () => {
  const dir = build(['--outDir', out, '--emptyOutDir'], { SITE_URL: 'https://example.com',
    QB_SHOPIFY_SUPPORT_EMAIL: 'help@example.com' })
  const page = (path: string) => readFileSync(join(dir, path, 'index.html'), 'utf8')

  it('has every page in every language, linked to each other', () => {
    for (const language of LANGUAGES) {
      for (const name of PAGES) {
        const path = join(language === 'en' ? '' : language, name === 'home' ? '' : name)
        const html = page(path)
        expect(html).toContain(`<html lang="${language === 'zh' ? 'zh-Hans' : language}">`)
        expect(html).toContain(`href="/shopify-app/${language === 'en' ? '' : `${language}/`}privacy/"`)
        expect(html).toContain('hreflang="ja" href="https://example.com/shopify-app/ja/')
      }
    }
    expect(readFileSync(join(dir, 'sitemap.xml'), 'utf8').match(/<loc>/g)).toHaveLength(LANGUAGES.length * PAGES.length)
  })

  it('names the support address as a mail link, and has no script or inline style', () => {
    for (const path of ['', 'privacy', 'zh/support', 'ja']) {
      const html = page(path)
      expect(html, path).toContain('<a href="mailto:help@example.com">help@example.com</a>')
      expect(html, path).not.toMatch(/<script|style=|\son[a-z]+=/i)
    }
    expect(readdirSync(join(dir, 'assets'))).toEqual([expect.stringMatching(/^site-[0-9a-f]{8}\.css$/)])
  })

  it('says what the privacy policy must: the data, its use, its retention and the contact', () => {
    const html = page('privacy')
    for (const text of ['Shopify customer ID', 'one-way digest', 'do not collect', '48 hours', 'append-only',
      'help@example.com']) {
      expect(html).toContain(esc(text))
    }
  })
})

describe('the settings', () => {
  it('refuses a bad base, origin or support address', () => {
    expect(settings({})).toEqual({ base: '/shopify-app/', siteUrl: 'https://quizbuks.com',
      supportEmail: 'shopify-support@quizbuks.com' })
    expect(() => settings({ VITE_BASE: '/x' })).toThrow(/VITE_BASE/)
    expect(() => settings({ SITE_URL: 'http://example.com' })).toThrow(/SITE_URL/)
    expect(() => settings({ QB_SHOPIFY_SUPPORT_EMAIL: 'not an address"><x' })).toThrow(/email/)
  })

  it('escapes everything around the address', () => {
    expect(withEmail('<b>{email}</b>', 'a@b.co')).toBe('&lt;b&gt;<a href="mailto:a@b.co">a@b.co</a>&lt;/b&gt;')
  })
})
