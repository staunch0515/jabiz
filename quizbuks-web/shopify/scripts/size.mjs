// The embedded pages' first load (docs/quizbuks/shopify/S4-launch.md F-2): what index.html loads before the overview
// shows (its module script, the chunks it preloads and its stylesheets), gzipped, against a budget, so a dependency that
// grows them fails the check. The campaigns and the plan are chunks of their own, loaded when first shown. Run after
// `pnpm build`, on dist/ (or the directory given).
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { gzipSync } from 'node:zlib'

/** Measured at S4b: 186 KB (React, the platform's packages and the overview, 13 KB of it the stylesheet); a little room. */
export const BUDGET = 200 * 1024

/** What a page loads at once from its own origin: module scripts, preloaded modules and stylesheets, by path. */
export function firstLoad(html) {
  const paths = new Set()
  const pattern = /<script[^>]*type="module"[^>]*src="([^"]+)"|<link[^>]*rel="(?:modulepreload|stylesheet)"[^>]*href="([^"]+)"/g
  for (const match of html.matchAll(pattern)) {
    const path = match[1] ?? match[2]
    if (path.startsWith('/')) paths.add(path)
  }
  return [...paths]
}

export function measure(dir) {
  const html = readFileSync(join(dir, 'index.html'), 'utf8')
  const base = (process.env.VITE_BASE ?? '/').replace(/\/$/, '')
  return firstLoad(html).map((path) => {
    const file = join(dir, path.startsWith(base + '/') ? path.slice(base.length) : path)
    return { path, size: gzipSync(readFileSync(file)).length }
  })
}

if (!process.env.VITEST) {
  const dir = resolve(process.argv[2] ?? 'dist')
  const files = measure(dir)
  const total = files.reduce((sum, file) => sum + file.size, 0)
  for (const file of files) console.log(`  ${file.path}: ${file.size} bytes gzipped`)
  console.log(`first load: ${total} bytes gzipped (budget ${BUDGET})`)
  if (total > BUDGET) {
    console.error('The embedded pages\' first load is over its budget: split what the overview does not need.')
    process.exit(1)
  }
}
