// The brand profiles and the front ends that are not the two SPAs (Q54b): run by `node --test` in CI (quizbuks.yml,
// web), no dependencies. The takers' app and the sponsor console check themselves (src/qb/brand-profile.test.ts).
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { test } from 'node:test'
import { BRANDS_DIR, brandIds, loadBrand } from './brand.mjs'

const WEB = join(BRANDS_DIR, '..')

/** The tokens of a CSS block: `:root { … }` (light) or the one inside the dark media query. */
function tokens(css, appearance) {
  const start = appearance === 'light' ? css.indexOf(':root {') : css.indexOf(':root {', css.indexOf('prefers-color-scheme: dark'))
  if (start < 0) return {}
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start))
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]))
}

function luminance(hex) {
  const [r, g, b] = [1, 3, 5].map((i) => Number.parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

// The static sites' page backgrounds (their own site.css, the same in both).
const PAGE = { light: '#ffffff', dark: '#0b1220' }

for (const id of brandIds()) {
  test(`the brand profile ${id} is complete`, () => {
    assert.doesNotThrow(() => loadBrand(id))
  })

  test(`the static sites read the brand ${id} (WCAG AA in both appearances)`, () => {
    const css = readFileSync(join(BRANDS_DIR, id, 'site.css'), 'utf8')
    for (const appearance of ['light', 'dark']) {
      const t = { ...tokens(css, 'light'), ...(appearance === 'dark' ? tokens(css, 'dark') : {}) }
      for (const [fg, bg] of [[t.accent, PAGE[appearance]], [t['accent-fg'], t.accent], [t['chip-fg'], t['accent-soft']]]) {
        assert.ok(contrast(fg, bg) >= 4.5, `${id} ${appearance}: ${fg} on ${bg} is ${contrast(fg, bg).toFixed(2)}:1`)
      }
    }
  })

  test(`the mark of ${id} is drawn in tokens both the apps and the sites define`, () => {
    const mark = readFileSync(join(BRANDS_DIR, id, 'mark.svg'), 'utf8')
    const used = new Set([...mark.matchAll(/var\(--([\w-]+)\)/g)].map((m) => m[1]))
    assert.ok(used.size > 0)
    const app = readFileSync(join(BRANDS_DIR, id, 'brand.css'), 'utf8')
    const site = tokens(readFileSync(join(BRANDS_DIR, id, 'site.css'), 'utf8'), 'light')
    for (const token of used) {
      assert.match(app, new RegExp(`--${token}:`), `${id}: brand.css lacks --${token}`)
      assert.ok(token in site, `${id}: site.css lacks --${token}`)
    }
  })
}

/** Identifiers that keep the word whatever the brand: paths, ids, globals, storage keys, a window name, the extension
 * and the shop's meta tag (renaming them would lose what browsers stored or break the deployed extension). */
const IDENTIFIERS = [
  /docs\/quizbuks\/[\w./-]*/g,
  /urn:jabiz:dict:quizbuks:[\w-]+/g,
  /__QUIZBUKS_\w+__/g,
  /Quizbuks(Quiz|QuizPlayer|Player)\b/g,
  /\bquizbuks:/g,
  /quizbuks-quiz\b/g,
  /quizbuks-support-email/g,
  /'quizbuks-taker'/g,
  /\/apps\/quizbuks\b/g,
  /backend\/quizbuks[\w./-]*/g,
  /tools\/quizbuks[\w./-]*/g,
]

const FRONT_ENDS = ['biz/src', 'biz/scripts', 'shopify-site/src', 'shopify-site/scripts', 'shopify/src', 'storefront/src',
  'shopify/index.html', 'storefront/vite.config.ts']

function sources(path) {
  if (statSync(path).isFile()) return [path]
  return readdirSync(path, { withFileTypes: true }).flatMap((entry) => {
    const child = join(path, entry.name)
    if (entry.isDirectory()) return entry.name === 'test' ? [] : sources(child)
    return /\.(m?js|ts|tsx|html|css)$/.test(entry.name) && !/\.test\.[mt]?[jt]sx?$/.test(entry.name)
      && !entry.name.endsWith('.d.ts') ? [child] : []
  })
}

/** The code without its comments (whole comment lines and blocks; a URL's // inside a string stays). */
function code(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, '')
    .split('\n').filter((line) => !line.trim().startsWith('//')).join('\n')
}

for (const file of FRONT_ENDS.flatMap((path) => sources(join(WEB, path)))) {
  test(`${relative(WEB, file)} names no brand of its own`, () => {
    let text = code(readFileSync(file, 'utf8'))
    for (const identifier of IDENTIFIERS) text = text.replace(identifier, '')
    assert.deepEqual(text.match(/.*quizbuks.*/gi) ?? [], [])
    // Nor its name in another language (Q61): texts take the profile's names.
    const local = loadBrand('quizbuks').names
    assert.deepEqual([local.zh, local.ja].filter((name) => text.includes(name)), [])
  })
}
