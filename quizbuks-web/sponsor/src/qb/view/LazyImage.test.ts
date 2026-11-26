import { describe, expect, it } from 'vitest'
import { isNear } from './LazyImage'

const box = (x: number, y: number, width: number, height: number) =>
  ({ x, y, width, height, left: x, top: y, right: x + width, bottom: y + height, toJSON: () => ({}) }) as DOMRect

describe('a lazy image near the screen when it comes', () => {
  it('is near on the screen and within 200 px of it, in either direction', () => {
    expect(isNear(box(172, 860, 144, 256), 390, 2400)).toBe(true)
    expect(isNear(box(0, 1000, 100, 100), 390, 844)).toBe(true)
    expect(isNear(box(450, 0, 100, 100), 390, 844)).toBe(true)
    expect(isNear(box(0, -280, 100, 100), 390, 844)).toBe(true)
  })

  it('is not near farther away, nor without a size (hidden)', () => {
    expect(isNear(box(0, 1100, 100, 100), 390, 844)).toBe(false)
    expect(isNear(box(700, 0, 100, 100), 390, 844)).toBe(false)
    expect(isNear(box(0, 0, 0, 0), 390, 844)).toBe(false)
  })
})
