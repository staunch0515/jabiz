// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compile } from 'tailwindcss'
import { describe, expect, it } from 'vitest'

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
  ['success', 'card'],
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
  it('reaches everything under .dark: no area is pinned to the light tokens any more (phase 15d)', async () => {
    const variant = /@custom-variant dark [^;]+;/.exec(css)![0]
    const compiler = await compile(`${variant}\n@tailwind utilities;`)
    const out = compiler.build(['dark:underline'])
    const selector = /([^{}]*)\{[^{}]*text-decoration-line: underline/.exec(out)![1].replace(/\s+/g, ' ').trim()
    expect(selector).toContain(':where(.dark, .dark *)')
    expect(selector).not.toContain('.light')
  })

  it('turns native controls and scroll bars dark with the appearance, light otherwise', () => {
    expect(css).toMatch(/:root \{\s*color-scheme: light;\s*\}/)
    expect(css).toMatch(/\.dark \{\s*color-scheme: dark;/)
  })

  it('paints the page canvas with the tokens, so no light margin shows around a dark page', () => {
    expect(css).toMatch(/body \{\s*background-color: var\(--color-background\);\s*color: var\(--color-foreground\);\s*\}/)
  })
})

describe('the preflight (phase 15d)', () => {
  it("is Tailwind's own, global, in the base layer", () => {
    expect(css).toContain("@import 'tailwindcss/preflight.css' layer(base);")
    expect(css).not.toContain('jabiz-ui')
  })

  it('leaves rendered Markdown styled, in the components layer', () => {
    expect(css).toContain("@import './markdown.css' layer(components);")
  })
})
