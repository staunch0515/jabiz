import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/*
 * The font stacks of the four front ends' stylesheets (F-F12): each language has its own, the Latin letters first, the
 * language's own CJK fonts after them, system fonts only. A Chinese page must not meet a Japanese font before a Chinese
 * one (it was set in Japanese glyphs, with the characters that font lacks falling to another family one by one).
 */
const here = dirname(fileURLToPath(import.meta.url))
const SHEETS = {
  biz: { file: resolve(here, '../src/assets/site.css'), selector: 'html' },
  'shopify-site': { file: resolve(here, '../../shopify-site/src/assets/site.css'), selector: 'html' },
  app: { file: resolve(here, '../../app/src/index.css'), selector: 'html:lang\\((zh|ja|en)\\) body' },
  sponsor: { file: resolve(here, '../../sponsor/src/index.css'), selector: 'html:lang\\((zh|ja|en)\\) body' },
} as const

const CHINESE = ['PingFang SC', 'Hiragino Sans GB', 'Noto Sans SC', 'Noto Sans CJK SC', 'Source Han Sans SC', 'Microsoft YaHei']
const JAPANESE = ['Hiragino Sans', 'Hiragino Kaku Gothic ProN', 'Noto Sans JP', 'Noto Sans CJK JP', 'Yu Gothic', 'Meiryo']
const CJK = [...CHINESE, ...JAPANESE]

/** The families of the stack a sheet gives a language. */
function stack(name: keyof typeof SHEETS, language: 'zh' | 'ja' | 'en'): string[] {
  const css = readFileSync(SHEETS[name].file, 'utf8')
  const selector = name === 'biz' || name === 'shopify-site' ? `html:lang\\(${language}\\)` : `html:lang\\(${language}\\) body`
  const match = new RegExp(`${selector}\\s*\\{\\s*font-family:\\s*([^;]+);`).exec(css)
  if (!match) throw new Error(`${name}: no font stack for ${language}`)
  return match[1].split(',').map((family) => family.trim().replace(/^"|"$/g, ''))
}

describe.each(Object.keys(SHEETS) as (keyof typeof SHEETS)[])('the font stacks of %s', (name) => {
  it('puts the language\'s own CJK font before any other CJK font', () => {
    const zh = stack(name, 'zh')
    expect(CHINESE).toContain(zh.find((family) => CJK.includes(family)))
    const ja = stack(name, 'ja')
    expect(JAPANESE).toContain(ja.find((family) => CJK.includes(family)))
  })

  it('has no font of the other language in a Chinese or Japanese stack, and none of the CJK in English', () => {
    expect(stack(name, 'zh').filter((family) => JAPANESE.includes(family))).toEqual([])
    expect(stack(name, 'ja').filter((family) => CHINESE.includes(family))).toEqual([])
    expect(stack(name, 'en').filter((family) => CJK.includes(family))).toEqual([])
  })

  it('has the Latin letters first, a generic family last, and only system fonts', () => {
    for (const language of ['zh', 'ja'] as const) {
      const families = stack(name, language)
      expect(families.indexOf('Roboto')).toBeLessThan(families.findIndex((family) => CJK.includes(family)))
      expect(families.at(-1)).toBe('sans-serif')
    }
    const css = readFileSync(SHEETS[name].file, 'utf8')
    expect(css).not.toMatch(/@font-face|fonts\.googleapis|fonts\.gstatic|@import\s+url/)
  })

  it('has no letter spacing in Chinese and Japanese', () => {
    const css = readFileSync(SHEETS[name].file, 'utf8')
    expect(css).toMatch(/html:lang\(zh\) \*, html:lang\(ja\) \* \{ letter-spacing: 0 !important; \}/)
  })
})

describe('the weights of the headings of the business site', () => {
  it('are 600 in Chinese and Japanese, which every CJK font above has', () => {
    const css = readFileSync(SHEETS.biz.file, 'utf8')
    expect(css).toMatch(/html:lang\(zh\) :is\(h1, h2, h3[^)]*\), html:lang\(ja\) :is\(h1, h2, h3[^)]*\) \{ font-weight: 600; \}/)
  })
})
