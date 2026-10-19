// No test code in a production bundle (phase 16w-1, decision D22). `@jabiz/admin/testing` brings in Vitest and the
// accessibility check (vitest-axe, axe-core), and only tests may import it; the extension lint refuses it elsewhere,
// and this check makes sure of the result, whatever way round the lint was taken: as a Vite plugin in every build of
// the admin (with or without an extension), and on the built files in the bundle step of `pnpm ext:check`.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const MARKERS = Object.freeze([
  { name: 'vitest-axe', pattern: /vitest-axe/i },
  { name: '@vitest/*', pattern: /@vitest\//i },
  { name: 'vitest', pattern: /\bvitest\b/i },
  { name: 'axe-core', pattern: /axe-core/i },
])

/** The markers of test code found in the code of one chunk; empty when there are none. */
export function testCodeIn(code) {
  return MARKERS.filter((marker) => marker.pattern.test(code)).map((marker) => marker.name)
}

/** The problems of a bundle (Rollup's output, by file name): one line per chunk that holds test code. */
export function bundleProblems(chunks) {
  return Object.entries(chunks).flatMap(([file, chunk]) => {
    if (chunk.type !== 'chunk' || !chunk.code) return []
    const found = testCodeIn(chunk.code)
    return found.length === 0 ? [] : [`${file} contains test code (${found.join(', ')})`]
  })
}

/** The problems of a built directory: its `assets/*.js`, read back from disk. */
export function builtProblems(outDir) {
  const assets = join(outDir, 'assets')
  return readdirSync(assets).filter((file) => file.endsWith('.js')).flatMap((file) => {
    const found = testCodeIn(readFileSync(join(assets, file), 'utf8'))
    return found.length === 0 ? [] : [`assets/${file} contains test code (${found.join(', ')})`]
  })
}

const ADVICE = 'The production bundle must not contain Vitest or the accessibility check (import @jabiz/admin/testing '
  + 'in tests only, decision D22):'

/** A Vite plugin that fails a production build whose JavaScript holds test code. */
export function noTestCodeInBundle() {
  return {
    name: 'jabiz-no-test-code-in-bundle',
    apply: 'build',
    generateBundle(_options, bundle) {
      const problems = bundleProblems(bundle)
      if (problems.length > 0) this.error(`${ADVICE}\n${problems.join('\n')}`)
    },
  }
}

export { ADVICE as BUNDLE_ADVICE }
