import { describe, expect, it } from 'vitest'
import { adminBrand, BRAND_NAME } from './brand'
import { messages } from './messages'

describe('the brand (Q55b)', () => {
  it('is QuizBuks unless the build chooses another profile', () => {
    expect(BRAND_NAME).toBe('QuizBuks')
    expect(adminBrand('sample')).toMatchObject({ name: 'Sample Quiz' })
    expect(adminBrand('sample').logo).toMatch(/^data:image\/svg\+xml/)
    expect(adminBrand('sample').favicon).toMatch(/^data:image\/png;base64,/)
    expect(() => adminBrand('nope')).toThrow('no brand profile')
  })

  it('names the brand in the menu, as every text that named QuizBuks', () => {
    expect((messages.en as { menu: { group: string } }).menu.group).toBe(BRAND_NAME)
    expect((messages.ja as { finance?: unknown; menu: { finance: string } }).menu.finance).toBe(`${BRAND_NAME} 財務`)
  })
})
