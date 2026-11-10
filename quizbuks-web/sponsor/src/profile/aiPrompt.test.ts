import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { BRAND_TOKEN, FORMAT_TOKEN, fixPrompt, FIX_AFTER, FIX_BEFORE, FIX_PLACEHOLDER, profilePrompt, PROMPT_AFTER, PROMPT_BEFORE } from './aiPrompt'

// The document the sponsor may also be given (F-C5): its two prompts are the ones this page fills in.
const DOCUMENT = resolve(__dirname, '../../../../docs/quizbuks/profile-package-prompts.md')
// The document spells the brand and the format out; the page takes them from the build (tokens in the constants).
const tokens = (text: string) => text.replaceAll('QuizBuks', BRAND_TOKEN).replaceAll('quizbuks-profile', FORMAT_TOKEN)
const blocks = [...readFileSync(DOCUMENT, 'utf-8').matchAll(/^````text\n([\s\S]*?)^````/gm)].map((m) => tokens(m[1]))

describe('the prompt for another AI tool', () => {
  it('is the document\'s, around the parameters', () => {
    expect(blocks).toHaveLength(2)
    const [main, fix] = blocks
    expect(main.startsWith(PROMPT_BEFORE)).toBe(true)
    expect(main.endsWith(PROMPT_AFTER)).toBe(true)
    // The parameters of the document and of the page carry the same names, in the same order.
    const labels = (text: string) => text.split('\n').filter((line) => line.startsWith('- ')).map((line) => line.split('：')[0])
    const documented = main.slice(PROMPT_BEFORE.length, main.length - PROMPT_AFTER.length)
    const typed = tokens(profilePrompt({ website: 'https://a.example/', contactName: 'Sara', baseLanguage: 'zh',
      otherLanguages: ['ja'], withProducts: true, maxProducts: 50 }))
    const filled = typed.slice(PROMPT_BEFORE.length, typed.length - PROMPT_AFTER.length)
    expect(labels(filled).map((label) => label.replace(/（.*$/, ''))).toEqual(labels(documented).map((label) => label.replace(/（.*$/, '')))
    expect(fix).toBe(FIX_BEFORE + FIX_PLACEHOLDER + FIX_AFTER)
  })

  it('fills in what the sponsor typed', () => {
    const text = profilePrompt({ website: ' https://aini.example/ ', contactName: ' 山田 花子 ', baseLanguage: 'ja',
      otherLanguages: ['ja', 'en'], withProducts: false, maxProducts: 20 })
    expect(text).toContain('- 官网地址：https://aini.example/\n')
    expect(text).toContain('：山田 花子\n')
    expect(text).toContain('- 基准语言：ja')
    expect(text).toContain('- 其他语言：en（')
    expect(text).toContain('- 要不要商品：不要\n')
    expect(text).toContain('- 商品个数上限：20（')
    expect(profilePrompt({ website: 'x', contactName: 'y', baseLanguage: 'en', otherLanguages: [], withProducts: true,
      maxProducts: 50 })).toContain('- 其他语言：无（')
  })

  it('puts the problems into the fixing prompt as they are', () => {
    const text = fixPrompt(['products[3].price: A price', 'page.handle: Not valid'])
    expect(text).toContain('products[3].price: A price\npage.handle: Not valid\n\n文件：')
    expect(text).not.toContain(FIX_PLACEHOLDER)
  })
})
