import { describe, expect, it } from 'vitest'
import { differs, lineDiff } from './lineDiff'
import { missingRequired } from './mailText'

describe('lineDiff', () => {
  it('aligns the common lines and marks the removed and added ones', () => {
    expect(lineDiff('a\nb\nc', 'a\nx\nc\nd')).toEqual([
      { kind: 'same', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'added', text: 'x' },
      { kind: 'same', text: 'c' },
      { kind: 'added', text: 'd' },
    ])
  })

  it('takes absent and empty texts as no lines', () => {
    expect(lineDiff(null, 'a')).toEqual([{ kind: 'added', text: 'a' }])
    expect(lineDiff('a', '')).toEqual([{ kind: 'removed', text: 'a' }])
    expect(lineDiff(undefined, null)).toEqual([])
  })

  it('tells whether anything differs', () => {
    expect(differs(lineDiff('a\nb', 'a\nb'))).toBe(false)
    expect(differs(lineDiff('a', 'b'))).toBe(true)
  })
})

describe('missingRequired', () => {
  it('names the required placeholders neither the subject nor the body has', () => {
    expect(missingRequired(['orderNo', 'unsubscribeUrl'], { subject: 'Order {orderNo}', body: 'Shipped' }))
      .toEqual(['unsubscribeUrl'])
    expect(missingRequired(['orderNo'], { subject: '', body: '{orderNo}' })).toEqual([])
  })
})
