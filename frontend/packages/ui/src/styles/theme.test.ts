// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compile } from 'tailwindcss'
import { describe, expect, it } from 'vitest'
import { generate, OUTPUT, scopeSelector } from '../../scripts/scoped-preflight.mjs'

const css = readFileSync(resolve(import.meta.dirname, 'theme.css'), 'utf8')

/** The custom properties declared in the first block whose selector list starts with `selector`. */
function tokens(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`) >= 0 ? css.indexOf(`${selector} {`) : css.indexOf(`${selector},`)
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
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
  ['foreground', 'background'],
  ['card-foreground', 'card'],
  ['popover-foreground', 'popover'],
  ['primary-foreground', 'primary'],
  ['secondary-foreground', 'secondary'],
  ['muted-foreground', 'muted'],
  ['muted-foreground', 'background'],
  ['muted-foreground', 'card'],
  ['accent-foreground', 'accent'],
  ['destructive-foreground', 'destructive'],
  ['destructive', 'background'],
  ['destructive', 'card'],
  ['success-foreground', 'success'],
  ['warning-foreground', 'warning'],
  ['warning', 'card'],
  ['primary', 'background'],
  ['sidebar-foreground', 'sidebar'],
  ['sidebar-primary-foreground', 'sidebar-primary'],
  ['sidebar-accent-foreground', 'sidebar-accent'],
  ['muted-foreground', 'sidebar'],
  // Tooltips invert the colours.
  ['background', 'foreground'],
]

/** Boundaries of controls and the focus indicator: 3:1 (1.4.11). */
const NON_TEXT: [string, string][] = [
  ['input', 'background'],
  ['input', 'card'],
  ['ring', 'background'],
  ['sidebar-ring', 'sidebar'],
]

describe('theme tokens (WCAG 2.2 AA, docs/design/12-frontend.md section 11)', () => {
  for (const [name, selector] of [['light', ':root'], ['dark', '.dark']] as const) {
    const values = tokens(selector)

    it(`give text 4.5:1 in the ${name} appearance`, () => {
      const low = TEXT.map(([fg, bg]) => [`${fg} on ${bg}`, contrast(values[fg], values[bg])] as const)
        .filter(([, ratio]) => !(ratio >= 4.5))
      expect(low).toEqual([])
    })

    it(`give control boundaries and focus 3:1 in the ${name} appearance`, () => {
      const low = NON_TEXT.map(([fg, bg]) => [`${fg} on ${bg}`, contrast(values[fg], values[bg])] as const)
        .filter(([, ratio]) => !(ratio >= 3))
      expect(low).toEqual([])
    })
  }

  it('are defined in both appearances, as #rrggbb where they are colours', () => {
    const light = tokens(':root')
    const dark = tokens('.dark')
    const colours = Object.keys(light).filter((k) => k !== 'radius')
    expect(colours.filter((k) => !(k in dark))).toEqual([])
    expect(colours.filter((k) => k !== 'overlay' && !/^#[0-9a-f]{6}$/.test(light[k]))).toEqual([])
  })
})

describe('the dark variant', () => {
  it('does not reach an area pinned to the light tokens (.light inside .dark)', async () => {
    const variant = /@custom-variant dark [^;]+;/.exec(css)![0]
    const compiler = await compile(`${variant}\n@tailwind utilities;`)
    const out = compiler.build(['dark:underline'])
    const selector = /([^{}]*)\{[^{}]*text-decoration-line: underline/.exec(out)![1].replace(/\s+/g, ' ').trim()
    // A light subtree is excluded however deep the element is.
    expect(selector).toContain(':where(.dark, .dark *)')
    expect(selector).toContain(':not(:where(.light, .light *))')
  })

  it('leaves color-scheme light inside the pinned area', () => {
    expect(css).toMatch(/:where\(\.dark \.jabiz-ui, \.dark\.jabiz-ui\):not\(:where\(\.light, \.light \*\)\)\s*\{\s*color-scheme: dark;/)
  })
})

describe('the scoped preflight (phase 15a)', () => {
  it('is what the installed Tailwind generates (run packages/ui/scripts/scoped-preflight.mjs after an upgrade)', () => {
    expect(readFileSync(OUTPUT, 'utf8')).toBe(generate())
  })

  it('scopes selectors without changing their specificity', () => {
    expect(scopeSelector('*')).toBe(':where(.jabiz-ui, .jabiz-ui *)')
    expect(scopeSelector('::after')).toBe(':where(.jabiz-ui, .jabiz-ui *)::after')
    expect(scopeSelector('html')).toBe(':where(.jabiz-ui)')
    expect(scopeSelector('abbr:where([title])')).toBe('abbr:where([title]):where(.jabiz-ui, .jabiz-ui *)')
    expect(scopeSelector('input::placeholder')).toBe('input:where(.jabiz-ui, .jabiz-ui *)::placeholder')
    // A component's own root is reset too (a <button class="jabiz-ui …">), not only what is inside it.
    expect(scopeSelector('button')).toBe('button:where(.jabiz-ui, .jabiz-ui *)')
    expect(scopeSelector(':where(select:is([multiple], [size])) optgroup'))
      .toBe(':where(select:is([multiple], [size])) optgroup:where(.jabiz-ui, .jabiz-ui *)')
  })
})
