import { describe, expect, it } from 'vitest'
import { sitePath } from './mailLink'

describe('sitePath (review of 16c-1): only paths of this site, whatever the URL parser makes of them', () => {
  it.each([
    ['/portal/orders?x=1#top', '/portal/orders?x=1#top'],
    ['/%2F%2Fevil.com', '/%2F%2Fevil.com'],
    ['//evil.com', '/home'],
    ['/\\evil.com', '/home'],
    ['/\t/evil.com', '/home'],
    ['/\n/evil.com', '/home'],
    ['/\r\n/evil.com', '/home'],
    ['/\t\\evil.com', '/home'],
    ['\\\\evil.com', '/home'],
    ['javascript:alert(1)', '/home'],
    ['https://evil.com/', '/home'],
    ['evil.com', '/home'],
    ['', '/home'],
    [null, '/home'],
    [undefined, '/home'],
  ])('%j → %j', (path, expected) => {
    expect(sitePath(path as string | null | undefined, '/home')).toBe(expected)
  })
})
