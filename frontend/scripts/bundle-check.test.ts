// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { builtProblems, bundleProblems, noTestCodeInBundle, testCodeIn } from './bundle-check.mjs'

describe('no test code in a production bundle (phase 16w-1)', () => {
  it('finds the markers of Vitest and the accessibility check', () => {
    expect(testCodeIn('const a = 1; export { a }')).toEqual([])
    expect(testCodeIn('/*! axe v4.10 | axe-core */ var axe = {}')).toEqual(['axe-core'])
    expect(testCodeIn('import "vitest-axe"')).toEqual(['vitest-axe', 'vitest'])
    expect(testCodeIn('throw new Error("@vitest/expect")')).toEqual(['@vitest/*', 'vitest'])
    // A word that merely contains it is not a marker.
    expect(testCodeIn('const vitesting = 1')).toEqual([])
  })

  it('reports each chunk that holds test code, and no assets', () => {
    expect(bundleProblems({
      'assets/index.js': { type: 'chunk', code: 'export const x = 1' },
      'assets/a11y.js': { type: 'chunk', code: 'var axe = "axe-core"' },
      'assets/vitest.css': { type: 'asset' },
    })).toEqual(['assets/a11y.js contains test code (axe-core)'])
  })

  it('fails the build through the plugin', () => {
    const plugin = noTestCodeInBundle() as unknown as {
      apply: string
      generateBundle: (this: { error: (message: string) => never }, options: unknown, bundle: unknown) => void
    }
    expect(plugin.apply).toBe('build')
    const context = { error: (message: string): never => { throw new Error(message) } }
    expect(() => plugin.generateBundle.call(context, {}, { 'index.js': { type: 'chunk', code: 'ok' } })).not.toThrow()
    expect(() => plugin.generateBundle.call(context, {}, { 'index.js': { type: 'chunk', code: 'from "vitest"' } }))
      .toThrow(/index\.js contains test code \(vitest\).*|@jabiz\/admin\/testing/s)
  })

  it('reads the built files back', () => {
    const dir = mkdtempSync(join(tmpdir(), 'bundle-check-'))
    try {
      mkdirSync(join(dir, 'assets'))
      writeFileSync(join(dir, 'assets', 'index.js'), 'export const x = 1')
      writeFileSync(join(dir, 'assets', 'index.css'), '.vitest {}')
      expect(builtProblems(dir)).toEqual([])
      writeFileSync(join(dir, 'assets', 'page.js'), 'import("vitest-axe")')
      expect(builtProblems(dir)).toEqual(['assets/page.js contains test code (vitest-axe, vitest)'])
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})
