import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import * as uiTesting from '@jabiz/ui/testing'
import * as adminTesting from './testing'

const root = resolve(import.meta.dirname, '../..')

/** The source files of the given directory that a production bundle may contain: no tests, no test helpers. */
function productionSources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return entry.name === 'test' || entry.name === 'testing' ? [] : productionSources(path)
    if (!/\.(ts|tsx)$/.test(entry.name) || /\.(test|spec)\.(ts|tsx)$/.test(entry.name) || entry.name.endsWith('.d.ts')) {
      return []
    }
    return [path]
  })
}

/**
 * A reference to test code in any form: `import … from`, `export … from`, a side-effect `import '…'`, `import('…')`
 * and `require('…')`, of Vitest, vitest-axe, the testing exports, or a relative `…/testing` module.
 */
const TEST_ONLY =
  /(?:\bfrom\s*|\bimport\s*\(?\s*|\brequire\s*\(\s*)['"`](vitest|vitest-axe|@vitest\/[^'"`]*|@jabiz\/ui\/testing[^'"`]*|@jabiz\/admin\/testing|(?:\.{1,2}\/)+(?:[\w-]+\/)*testing)['"`]/

describe('the pattern of test-code references', () => {
  it('catches every form of import', () => {
    for (const code of [
      "import { axe } from 'vitest-axe'",
      "export * from './testing'",
      "import '@jabiz/admin/testing'",
      "const m = await import('../lib/testing')",
      'const m = import(`@jabiz/ui/testing/playwright`)',
      "const v = require('vitest')",
      "export { expect } from '@vitest/expect'",
    ]) {
      expect(code, code).toMatch(TEST_ONLY)
    }
    expect("import { api } from '@jabiz/admin'").not.toMatch(TEST_ONLY)
    expect("import { a } from './testingLibrary'").not.toMatch(TEST_ONLY)
  })
})

describe('@jabiz/admin/testing (decision D22)', () => {
  it('is the accessibility check of @jabiz/ui/testing', () => {
    expect(adminTesting.expectAccessible).toBe(uiTesting.expectAccessible)
    expect(adminTesting.WCAG_TAGS).toBe(uiTesting.WCAG_TAGS)
    expect(Object.keys(adminTesting).sort()).toEqual(['WCAG_TAGS', 'expectAccessible'])
  })

  it('is not imported by @jabiz/admin, so no production bundle has Vitest or vitest-axe', () => {
    const index = readFileSync(resolve(root, 'src/lib/index.ts'), 'utf8')
    expect(index).not.toMatch(TEST_ONLY)
    expect(index).not.toMatch(/testing/)
  })

  it('is imported by no production source of the admin frontend or its packages', () => {
    const offending = [resolve(root, 'src'), resolve(root, 'packages/ui/src'), resolve(root, 'packages/client/src')]
      .flatMap(productionSources)
      .filter((path) => path !== resolve(root, 'src/lib/testing.ts'))
      .filter((path) => TEST_ONLY.test(readFileSync(path, 'utf8')))
      .map((path) => relative(root, path))
    expect(offending).toEqual([])
  })
})
