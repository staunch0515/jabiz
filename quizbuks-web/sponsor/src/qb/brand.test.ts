import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { BRANDS_DIR, brandIds } from '../../../brands/brand.mjs'

/*
 * The brand tokens (each brand profile's brand.css, Q54) override the platform's; the platform's own contrast test
 * (theme.test.ts) checks the platform's values, so every profile's overrides are checked here with the platform's
 * values as the fallback. This file is the same in both SPAs (src/qb).
 */
const brands = brandIds().map((id) => [id, readFileSync(join(BRANDS_DIR, id, 'brand.css'), 'utf8')] as const)
const platform = readFileSync(createRequire(import.meta.url).resolve('@jabiz/ui/theme.css'), 'utf8')

function tokens(css: string, selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`) >= 0 ? css.indexOf(`${selector} {`) : css.indexOf(`${selector},`)
  if (start < 0) return {}
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6});/g)].map((m) => [m[1], m[2]]))
}

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2.2 contrast ratio of two #rrggbb colours. */
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** Text on a background: 4.5:1 (1.4.3). */
const TEXT: [string, string][] = [
  ['primary-foreground', 'primary'],
  ['primary', 'background'],
  ['primary', 'card'],
  ['accent-foreground', 'accent'],
  ['muted-foreground', 'accent'],
  ['foreground', 'accent'],
  ['sidebar-primary-foreground', 'sidebar-primary'],
  ['sidebar-accent-foreground', 'sidebar-accent'],
  ['qb-hero-foreground', 'qb-hero-from'],
  ['qb-hero-foreground', 'qb-hero-to'],
  ['qb-hero-muted', 'qb-hero-from'],
  ['qb-hero-muted', 'qb-hero-to'],
  ['qb-ink', 'qb-sky'],
  // The button on the hero band: the band's dark green on the band's white.
  ['qb-hero-from', 'qb-hero-foreground'],
]

/** Boundaries of controls, the focus indicator and the carousel's dots: 3:1 (1.4.11). */
const NON_TEXT: [string, string][] = [
  ['ring', 'background'],
  ['sidebar-ring', 'sidebar'],
  ['muted-foreground', 'background'],
  ['chart-1', 'chart-2'],
]

const APPEARANCES = brands.flatMap(([id, brand]) => (['light', 'dark'] as const).map((appearance) =>
  ({ id, brand, appearance })))

describe.each(APPEARANCES)('the brand $id in the $appearance appearance', ({ brand, appearance }) => {
  const selector = appearance === 'light' ? ':root' : '.dark'
  const t = { ...tokens(platform, selector), ...tokens(brand, selector) }

  it.each(TEXT)('%s on %s reads (≥ 4.5:1)', (fg, bg) => {
    expect(contrast(t[fg], t[bg])).toBeGreaterThanOrEqual(4.5)
  })

  it.each(NON_TEXT)('%s against %s is distinct (≥ 3:1)', (a, b) => {
    expect(contrast(t[a], t[b])).toBeGreaterThanOrEqual(3)
  })

  it('overrides the same tokens as the other appearance', () => {
    const other = tokens(brand, appearance === 'light' ? '.dark' : ':root')
    expect(Object.keys(tokens(brand, selector)).sort()).toEqual(Object.keys(other).sort())
  })
})
