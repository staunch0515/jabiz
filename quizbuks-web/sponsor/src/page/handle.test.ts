import { describe, expect, it } from 'vitest'
import { HANDLE_PATTERN } from './api'
import cases from './handle-cases.json'
import { addressInHandle } from './handle'

describe('an address typed as the link name (F-F9, T10)', () => {
  it.each([
    ['https://www.aini-helmet.com/', 'aini-helmet', 'https://www.aini-helmet.com/'],
    ['http://aini-helmet.com', 'aini-helmet', 'http://aini-helmet.com'],
    ['www.aini-helmet.com', 'aini-helmet', 'https://www.aini-helmet.com'],
    ['aini-helmet.com/shop?x=1', 'aini-helmet', 'https://aini-helmet.com/shop?x=1'],
    ['https://shop.aini-helmet.co.jp/', 'aini-helmet', 'https://shop.aini-helmet.co.jp/'],
    ['https://Green-Leaf.com.cn', 'green-leaf', 'https://Green-Leaf.com.cn'],
    ['https://www.example.co.uk/about', 'example', 'https://www.example.co.uk/about'],
  ])('%s suggests %s and the website %s', (input, handle, website) => {
    expect(addressInHandle(input)).toEqual({ handle, website })
  })

  it('suggests nothing from a domain not in Latin letters, but still offers to move the address', () => {
    expect(addressInHandle('https://例子.中国')).toEqual({ handle: null, website: 'https://例子.中国' })
    expect(addressInHandle('爱你头盔.com')).toEqual({ handle: null, website: 'https://爱你头盔.com' })
    expect(addressInHandle('https://xn--fsqu00a.xn--fiqs8s/')?.handle).toBeNull()
  })

  it('suggests nothing that breaks the rule of a link name', () => {
    // Too short, too long, an underscore or a hyphen at the edge.
    expect(addressInHandle('https://ab.com')?.handle).toBeNull()
    expect(addressInHandle(`https://${'a'.repeat(31)}.com`)?.handle).toBeNull()
    expect(addressInHandle('https://my_shop.com')?.handle).toBeNull()
    expect(addressInHandle('https://-shop.com')?.handle ?? null).toBeNull()
    for (const input of ['https://www.aini-helmet.com/', 'https://shop.aini-helmet.co.jp/']) {
      expect(HANDLE_PATTERN.test(addressInHandle(input)!.handle!)).toBe(true)
    }
  })

  it('leaves names alone, valid or not: the usual check speaks for them', () => {
    for (const input of ['', '  ', 'aini-helmet', 'Aini Helmet', 'aini', 'a', 'aini.helmet shop', '-aini-']) {
      expect(addressInHandle(input)).toBeNull()
    }
  })
})

describe('the rule of a link name, the same cases as the server (F-F9)', () => {
  it.each(cases.valid)('%s is a link name', (name) => expect(HANDLE_PATTERN.test(name)).toBe(true))
  it.each(cases.invalid)('%s is not', (name) => expect(HANDLE_PATTERN.test(name)).toBe(false))
})
