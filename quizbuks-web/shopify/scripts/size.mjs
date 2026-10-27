// The embedded pages' first load (docs/quizbuks/shopify/S4-launch.md F-2): the scripts index.html loads before the
// overview shows (its module script and the chunks it preloads), gzipped, against a budget, so a dependency that
// grows them fails the check. The campaigns and the plan are chunks of their own, loaded when first shown. Run after
// `pnpm build`, on dist/ (or the directory given).
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { gzipSync } from 'node:zlib'

/** Measured at S4b: 172 KB (React, the platform's packages and the overview); a little room, not much. */
export const BUDGET = 185 * 1024

/** The scripts a page loads at once: its module scripts and the modules it preloads, as paths under the base. */
export function firstLoad(html) {
  const paths = new Set()
  for (const match of html.matchAll(/<script[^>]*type="module"[^>]*src="([^"]+)"|<link[^>]*rel="modulepreload"[^>]*href="([^"]+)"/g)) {
    paths.add(match[1] ?? match[2])
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
