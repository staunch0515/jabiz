/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/** Contrast of the design tokens (design section 9.2), in both colour schemes, computed as WCAG 2.2 defines it. */
// Read as a file: Vitest does not load stylesheets.
const css = readFileSync(join(process.cwd(), 'src/styles/tokens.css'), 'utf8')

function tokens(block: string): Record<string, string> {
  return Object.fromEntries([...block.matchAll(/--([a-z0-9-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [m[1], m[2].toLowerCase()]))
}

const darkStart = css.indexOf('@media (prefers-color-scheme: dark)')
const light = tokens(css.slice(0, darkStart))
const dark = { ...light, ...tokens(css.slice(darkStart)) }

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
  const linear = (c: number) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
}

export function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

// Every text colour on every background it is used on; the focus ring against the backgrounds it outlines on.
const TEXT: [string, string][] = [
  ['ink', 'paper'],
  ['muted', 'paper'],
  ['ink', 'paper-2'],
  ['muted', 'paper-2'],
  ['on-mark', 'mark'],
  ['paper', 'ink'],
  ['inv-ink', 'inv-bg'],
  ['inv-muted', 'inv-bg'],
  ['on-poster', 'poster'],
]
const NON_TEXT: [string, string][] = [
  ['focus', 'paper'],
  ['focus', 'paper-2'],
  ['ink', 'rule'],
]

describe.each([
  ['light', light],
  ['dark', dark],
])('%s scheme', (_, palette) => {
  it.each(TEXT)('%s on %s has contrast >= 4.5:1', (fg, bg) => {
    expect(palette[fg], fg).toBeDefined()
    expect(palette[bg], bg).toBeDefined()
    expect(contrast(palette[fg], palette[bg])).toBeGreaterThanOrEqual(4.5)
  })

  it.each(NON_TEXT)('%s against %s has contrast >= 3:1', (fg, bg) => {
    expect(contrast(palette[fg], palette[bg])).toBeGreaterThanOrEqual(3)
  })
})

it('computes contrast as WCAG does', () => {
  expect(contrast('#000000', '#ffffff')).toBeCloseTo(21, 5)
  expect(contrast('#777777', '#ffffff')).toBeCloseTo(4.48, 2)
})
