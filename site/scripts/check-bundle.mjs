// The first screen's JavaScript (docs/culture/00-design.md section 9.4): what the built index.html loads before any
// page asks for more, its entry script and the modules it preloads, gzipped. Every `vite build` checks it (the
// plugin in vite.config.ts) and fails above the budget; on its own: node scripts/check-bundle.mjs [dist]
import { readFileSync } from 'node:fs'
import { isAbsolute, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

/** 200 KB (gzip), the design's budget for the first screen. */
export const BUDGET = 200 * 1024

/** The scripts index.html loads at once: module entries and module preloads, in order, without duplicates. */
export function firstScreenScripts(html) {
  const found = []
  for (const tag of html.match(/<(script|link)\b[^>]*>/g) ?? []) {
    const attr = (name) => new RegExp(`\\b${name}="([^"]*)"`).exec(tag)?.[1]
    const isEntry = tag.startsWith('<script') && attr('type') === 'module' && attr('src')
    const isPreload = tag.startsWith('<link') && attr('rel') === 'modulepreload' && attr('href')
    const url = isEntry ? attr('src') : isPreload ? attr('href') : undefined
    if (url && !found.includes(url)) found.push(url)
  }
  return found
}

export function measure(dist, base = '/') {
  const html = readFileSync(join(dist, 'index.html'), 'utf8')
  return firstScreenScripts(html).map((url) => {
    const file = url.startsWith(base) ? url.slice(base.length) : url.replace(/^\//, '')
    return { file, gzip: gzipSync(readFileSync(join(dist, file)), { level: 9 }).length }
  })
}

/** Prints the sizes; returns false above the budget. */
export function report(dist, base = '/', log = console.log) {
  const files = measure(dist, base)
  const total = files.reduce((sum, f) => sum + f.gzip, 0)
  for (const f of files) log(`${(f.gzip / 1024).toFixed(1).padStart(7)} KB  ${f.file}`)
  const verdict = total <= BUDGET ? 'within' : 'OVER'
  log(`${(total / 1024).toFixed(1).padStart(7)} KB  first screen, gzip (${verdict} the budget of ${BUDGET / 1024} KB)`)
  return total <= BUDGET
}

/** The check after every production build. */
export function firstScreenBudget() {
  let dist = 'dist'
  let base = '/'
  return {
    name: 'first-screen-budget',
    apply: 'build',
    configResolved(config) {
      dist = isAbsolute(config.build.outDir) ? config.build.outDir : join(config.root, config.build.outDir)
      base = config.base
    },
    closeBundle() {
      if (!report(dist, base)) throw new Error(`The first screen's JavaScript is over ${BUDGET / 1024} KB (gzip)`)
    },
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  if (!report(process.argv[2] ?? 'dist', process.env.VITE_BASE ?? '/')) process.exit(1)
}
