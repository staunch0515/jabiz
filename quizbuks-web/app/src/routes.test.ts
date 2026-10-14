import { describe, expect, it } from 'vitest'
import { itemPage } from './routes'

describe('the pages of one item', () => {
  it('are a publication, an attempt to play or an attempt\'s result, by id', () => {
    expect(itemPage('/p/abc')).toEqual({ page: 'p', id: 'abc' })
    expect(itemPage('/play/a%20b')).toEqual({ page: 'play', id: 'a b' })
    expect(itemPage('/result/x')).toEqual({ page: 'result', id: 'x' })
  })

  it('are nothing else', () => {
    expect(itemPage('/')).toBeNull()
    expect(itemPage('/p/')).toBeNull()
    expect(itemPage('/p/a/b')).toBeNull()
    expect(itemPage('/history')).toBeNull()
    // A malformed escape is no page rather than an error.
    expect(itemPage('/p/%E0%A4%A')).toBeNull()
  })
})
