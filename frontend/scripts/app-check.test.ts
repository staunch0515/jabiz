// @vitest-environment node
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { checkApp, platformVersions, pnpmImporterVersions, SHARED } from './app-check.mjs'

const frontend = resolve(import.meta.dirname, '..')
const platform = platformVersions(frontend)
const dirs: string[] = []

/** An SPA directory with a package.json and, optionally, a lockfile. */
function spa(dependencies: Record<string, string>, lock?: { pnpm?: Record<string, string>; npm?: Record<string, string> }) {
  const dir = mkdtempSync(join(tmpdir(), 'jabiz-app-check-'))
  dirs.push(dir)
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'spa', dependencies }))
  if (lock?.pnpm) {
    const entries = Object.entries(lock.pnpm)
      .map(([name, version]) => `      '${name}':\n        specifier: ${dependencies[name] ?? version}\n        version: ${version}`)
      .join('\n')
    writeFileSync(join(dir, 'pnpm-lock.yaml'), `lockfileVersion: '9.0'\n\nimporters:\n\n  .:\n    dependencies:\n${entries}\n\npackages:\n`)
  }
  if (lock?.npm) {
    const packages = Object.fromEntries(Object.entries(lock.npm).map(([name, version]) => [`node_modules/${name}`, { version }]))
    writeFileSync(join(dir, 'package-lock.json'), JSON.stringify({ lockfileVersion: 3, packages: { '': {}, ...packages } }))
  }
  return dir
}

/** The platform's versions of the shared packages, and of @jabiz/ui's runtime dependencies when asked. */
function platformDeps(withUi: boolean): Record<string, string> {
  const all = [...platform.shared, ...(withUi ? platform.ui : [])]
  return Object.fromEntries(all.map(([name, version]) => [name, version!]))
}

afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true })
})

describe('the platform versions', () => {
  it('come from the lockfile: the shared packages and the runtime dependencies of @jabiz/ui', () => {
    expect([...platform.shared.keys()]).toEqual(SHARED)
    expect(platform.shared.get('react')).toMatch(/^\d+\.\d+\.\d+$/)
    expect(platform.ui.get('radix-ui')).toMatch(/^\d+\.\d+\.\d+$/)
    expect(platform.ui.has('@jabiz/client')).toBe(false)
  })

  it('are read without peer suffixes and without workspace links', () => {
    const lock = readFileSync(join(frontend, 'pnpm-lock.yaml'), 'utf8')
    const admin = pnpmImporterVersions(lock, '.')
    expect(admin.get('react-i18next')).toMatch(/^\d+\.\d+\.\d+$/)
    expect(admin.has('@jabiz/ui')).toBe(false)
  })
})

describe('app:check (decision D34 item 5)', () => {
  it('passes an SPA with the platform versions in its pnpm lockfile', () => {
    const deps = { ...platformDeps(true), '@jabiz/ui': 'link:../../frontend/packages/ui' }
    expect(checkApp(spa(deps, { pnpm: platformDeps(true) }), frontend)).toEqual([])
  })

  it('fails on any shared package with another version, naming it', () => {
    const versions = { ...platformDeps(false), react: '19.0.0', i18next: '25.0.0' }
    const problems = checkApp(spa(versions, { pnpm: versions }), frontend)
    expect(problems).toHaveLength(2)
    expect(problems[0]).toMatch(/^react: 19\.0\.0, the platform has /)
    expect(problems[1]).toMatch(/^i18next: 25\.0\.0/)
  })

  it('requires the runtime dependencies of @jabiz/ui only from SPAs that use it', () => {
    const shared = platformDeps(false)
    expect(checkApp(spa(shared, { pnpm: shared }), frontend)).toEqual([])
    const problems = checkApp(spa({ ...shared, '@jabiz/ui': 'link:x' }, { pnpm: shared }), frontend)
    expect(problems.some((p) => p.startsWith('radix-ui: not a dependency'))).toBe(true)
    const radix = { ...platformDeps(true), 'radix-ui': '1.0.0' }
    expect(checkApp(spa({ ...radix, '@jabiz/ui': 'link:x' }, { pnpm: radix }), frontend)).toEqual([
      `radix-ui: 1.0.0, the platform has ${platform.ui.get('radix-ui')}`,
    ])
  })

  it('reads npm lockfiles, and exact versions when there is no lockfile', () => {
    const shared = platformDeps(false)
    expect(checkApp(spa(shared, { npm: shared }), frontend)).toEqual([])
    expect(checkApp(spa({ ...shared, 'react-dom': '19.0.0' }, { npm: { ...shared, 'react-dom': '19.0.0' } }), frontend))
      .toEqual([`react-dom: 19.0.0, the platform has ${platform.shared.get('react-dom')}`])
    expect(checkApp(spa(shared), frontend)).toEqual([])
    expect(checkApp(spa({ ...shared, tailwindcss: '^4.0.0' }), frontend)).toEqual([
      expect.stringMatching(/^tailwindcss: version not resolved/),
    ])
  })

  it('names what is missing', () => {
    const { react: _react, ...rest } = platformDeps(false)
    void _react
    expect(checkApp(spa(rest, { pnpm: rest }), frontend)).toEqual([
      `react: not a dependency; add "react": "${platform.shared.get('react')}"`,
    ])
  })
})
