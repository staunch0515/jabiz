import { describe, expect, it } from 'vitest'
import { chromeOpacity, nextScrollChrome, SHOWN, type ScrollChrome } from './scrollChrome'

/*
 * Q113 §9.1a, row by row, as measured on x.com: the slack, the one-to-one hiding, the fading to 0.3, the slow upward
 * scrolling that shows nothing, the quick swipe and the top that show everything.
 */

/** The state after the page reports each position, `gap` ms apart (a pause by default: separate moves). */
function scroll(positions: number[], { from = SHOWN, gap = 500 }: { from?: ScrollChrome; gap?: number } = {}) {
  let state = from
  for (const y of positions) state = nextScrollChrome(state, y, state.at + gap)
  return state
}

describe('scrolling down', () => {
  it('leaves the header after 20 px of slack, one to one', () => {
    expect(scroll([10]).offset).toBe(0)
    expect(scroll([20]).offset).toBe(0)
    expect(scroll([60]).offset).toBe(40)
    expect(scroll([10, 30, 60]).offset).toBe(40)
  })

  it('takes the header out at most 106 px, the bottom menu fading to 0.3', () => {
    const out = scroll([500, 2000])
    expect(out.offset).toBe(106)
    expect(chromeOpacity(out.offset)).toBeCloseTo(0.3)
    expect(chromeOpacity(scroll([60]).offset)).toBeCloseTo(1 - 0.7 * 40 / 106)
    expect(chromeOpacity(0)).toBe(1)
  })
})

describe('scrolling up', () => {
  const hidden = scroll([1000])

  it('does not follow: five slow moves of 22 px (110 px in all) leave the header out', () => {
    const after = scroll([978, 956, 934, 912, 890], { from: hidden })
    expect(after.offset).toBe(106)
  })

  it('brings everything back on one move of 60 px', () => {
    const after = scroll([940], { from: hidden })
    expect(after.offset).toBe(0)
    expect(chromeOpacity(after.offset)).toBe(1)
  })

  it('counts the moves of one quick swipe together, however the page reports them', () => {
    expect(scroll([980, 960, 940], { from: hidden, gap: 16 }).offset).toBe(0)
    expect(scroll([980, 960], { from: hidden, gap: 16 }).offset).toBe(106)
  })

  it('leaves a half-hidden header where it is', () => {
    const half = scroll([60])
    expect(scroll([50], { from: half }).offset).toBe(40)
  })

  it('shows everything at the top', () => {
    expect(scroll([0], { from: hidden }).offset).toBe(0)
    expect(scroll([-30], { from: hidden }).offset).toBe(0)
  })

  it('starts the slack again once the header is back', () => {
    const back = scroll([940], { from: hidden })
    expect(scroll([955], { from: back }).offset).toBe(0)
    expect(scroll([1000], { from: back }).offset).toBe(40)
  })

  it('gives back the slack spent while the header was all shown', () => {
    expect(scroll([15, 5, 25]).offset).toBe(5)
  })
})
