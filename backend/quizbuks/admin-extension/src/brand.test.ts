import { describe, expect, it } from 'vitest'
import { adminBrand, BRAND_NAMES } from './brand'
import { messages } from './messages'

describe('the brand (Q55b)', () => {
  it('is QuizBuks unless the build chooses another profile', () => {
    expect(BRAND_NAMES).toEqual({ en: 'QuizBuks', zh: '答题拼好价', ja: 'クイズdeおトク' })
    // The platform shows the name in the interface's language (Q61).
    expect(adminBrand('sample')).toMatchObject({ name: { en: 'Sample Quiz', zh: '示例答题', ja: 'サンプルクイズ' } })
    expect(adminBrand('sample').logo).toMatch(/^data:image\/svg\+xml/)
    expect(adminBrand('sample').favicon).toMatch(/^data:image\/png;base64,/)
    expect(() => adminBrand('nope')).toThrow('no brand profile')
  })

  it('names the brand in the menu in each language, as every text that named QuizBuks', () => {
    expect((messages.en as { menu: { group: string } }).menu.group).toBe(BRAND_NAMES.en)
    expect((messages.zh as { menu: { group: string } }).menu.group).toBe(BRAND_NAMES.zh)
    expect((messages.ja as { finance?: unknown; menu: { finance: string } }).menu.finance).toBe(`${BRAND_NAMES.ja} 財務`)
  })
})
