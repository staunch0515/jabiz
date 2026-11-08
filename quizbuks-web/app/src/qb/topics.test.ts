import { describe, expect, it } from 'vitest'
import { byCode, codesOf, customOf, normalizedWord, offered, resolvedCode, sameWord, selectable, shownCodes,
  topicName, type Topic } from './topics'

const topics: Topic[] = [
  { topicId: 'travel', names: { zh: '旅行', ja: '旅行', en: 'Travel' }, active: true, mergedInto: null },
  { topicId: 'hiking', names: { zh: '徒步', ja: 'ハイキング', en: 'Hiking' }, active: false, mergedInto: 'travel' },
  { topicId: 'art', names: { zh: '艺术', ja: 'アート', en: 'Art' }, active: true, mergedInto: null },
  { topicId: 'retired', names: { zh: '旧', ja: '旧', en: 'Retired' }, active: false, mergedInto: null },
]
const words = byCode(topics)

describe('topics (F-S9)', () => {
  it('shows a word in the reader\'s language, a merged one by the word it counts as', () => {
    expect(topicName('travel', words, 'zh-CN')).toBe('旅行')
    expect(topicName('hiking', words, 'en')).toBe('Travel')
    expect(topicName('retired', words, 'ja')).toBe('旧')
    expect(topicName('ghost', words, 'en')).toBe('ghost')
    expect(resolvedCode('hiking', words)).toBe('travel')
  })

  it('offers only active words not merged, sorted by name', () => {
    expect(offered(topics, 'en').map((topic) => topic.topicId)).toEqual(['art', 'travel'])
    expect(selectable(words.get('retired'))).toBe(false)
    expect(selectable(words.get('hiking'))).toBe(false)
    expect(selectable(undefined)).toBe(false)
  })

  it('shows a merged word and its target once', () => {
    expect(shownCodes(['hiking', 'travel', 'art'], words)).toEqual(['hiking', 'art'])
  })

  it('reads the columns as the server writes them', () => {
    expect(codesOf('a, b,,a')).toEqual(['a', 'b'])
    expect(codesOf(null)).toEqual([])
    expect(customOf('["Edo period","Ukiyo-e"]')).toEqual(['Edo period', 'Ukiyo-e'])
    expect(customOf('broken')).toEqual([])
    expect(normalizedWord('  Edo   period ')).toBe('Edo period')
    expect(sameWord('edo PERIOD', ' Edo period')).toBe(true)
  })
})
