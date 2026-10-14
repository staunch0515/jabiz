import { describe, expect, it } from 'vitest'
import { deviceOf } from './device'

describe('deviceOf', () => {
  it('names the common browsers and systems', () => {
    expect(deviceOf('Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) '
      + 'Version/17.5 Mobile/15E148 Safari/604.1')).toBe('Safari · iOS')
    expect(deviceOf('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
      + 'Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0')).toBe('Edge · Windows')
    expect(deviceOf('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) '
      + 'Chrome/126.0.0.0 Mobile Safari/537.36')).toBe('Chrome · Android')
    expect(deviceOf('Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:127.0) Gecko/20100101 Firefox/127.0'))
      .toBe('Firefox · macOS')
  })

  it('says nothing it does not know', () => {
    expect(deviceOf(null)).toBeNull()
    expect(deviceOf('')).toBeNull()
    expect(deviceOf('curl/8.5.0')).toBeNull()
    expect(deviceOf('Something (X11; Linux x86_64)')).toBe('Linux')
  })
})
