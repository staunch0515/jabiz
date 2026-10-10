// Checks an application's own single-page application against the platform's frontend (decision D34 item 5):
//   pnpm app:check <directory of the SPA>
// React, React DOM, Tailwind CSS, TanStack Query, i18next and react-i18next (they hold state the packages share with
// the SPA: hooks, contexts, the i18next instance), and the runtime dependencies of @jabiz/client and @jabiz/ui when the
// SPA uses them, must be the very versions the platform's frontend resolves: two Reacts break hooks and
// context, two Tailwinds or Radix versions make the shared components look or behave differently. The versions are
// read from the lockfiles (pnpm-lock.yaml, else package-lock.json), else from exact versions in package.json.
import { existsSync, readFileSync } from 'node:fs'
import { isAbsolute, resolve } from 'node:path'

/** What every SPA that uses the platform's packages shares with the platform. */
export const SHARED = ['react', 'react-dom', 'tailwindcss', '@tanstack/react-query', 'i18next', 'react-i18next']

const SECTIONS = new Set(['dependencies', 'devDependencies', 'optionalDependencies'])

const unquote = (text) => text.trim().replace(/^'(.*)'$/, '$1').replace(/^"(.*)"$/, '$1')

/** A resolved version without pnpm's peer suffix: `19.3.0(react@19.3.0)` → `19.3.0`. */
export function bareVersion(version) {
  return version.replace(/\(.*$/, '').trim()
}

/**
 * The resolved versions of one importer of a pnpm lockfile (v9): name → version. Workspace and path links
 * (`link:…`) are left out.
 */
export function pnpmImporterVersions(lockText, importer = '.') {
  const versions = new Map()
  const lines = lockText.split(/\r?\n/)
  let inImporters = false
  let current = null
  let section = null
  let name = null
  for (const line of lines) {
    if (line.trim() === '' || line.trimStart().startsWith('#')) continue
    const indent = line.length - line.trimStart().length
    if (indent === 0) {
      inImporters = line.trim() === 'importers:'
      continue
    }
    if (!inImporters) continue
    const text = line.trim()
    if (indent === 2) {
      current = unquote(text.replace(/:$/, ''))
      section = null
    } else if (indent === 4) {
      section = SECTIONS.has(text.replace(/:$/, '')) ? text.replace(/:$/, '') : null
    } else if (indent === 6 && section) {
      name = unquote(text.replace(/:$/, ''))
    } else if (indent === 8 && section && name && current === importer && text.startsWith('version:')) {
      const version = text.slice('version:'.length).trim()
      if (!version.startsWith('link:')) versions.set(name, bareVersion(unquote(version)))
    }
  }
  return versions
}

/** The resolved versions of a project's own dependencies (not the transitive ones), from npm's package-lock.json. */
export function npmLockVersions(lockText) {
  const lock = JSON.parse(lockText)
  const versions = new Map()
  for (const [path, entry] of Object.entries(lock.packages ?? {})) {
    const match = /^node_modules\/((?:@[^/]+\/)?[^/]+)$/.exec(path)
    if (match && entry.version && !entry.link) versions.set(match[1], entry.version)
  }
  return versions
}

const EXACT = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

/** An SPA's dependencies as declared and as resolved. */
export function projectVersions(dir) {
  const manifest = readJson(resolve(dir, 'package.json'))
  const declared = { ...manifest.devDependencies, ...manifest.dependencies }
  let resolved = new Map()
  if (existsSync(resolve(dir, 'pnpm-lock.yaml'))) {
    resolved = pnpmImporterVersions(readFileSync(resolve(dir, 'pnpm-lock.yaml'), 'utf8'))
  } else if (existsSync(resolve(dir, 'package-lock.json'))) {
    resolved = npmLockVersions(readFileSync(resolve(dir, 'package-lock.json'), 'utf8'))
  }
  for (const [name, spec] of Object.entries(declared)) {
    if (!resolved.has(name) && EXACT.test(String(spec))) resolved.set(name, String(spec))
  }
  return { declared, resolved }
}

/** The runtime dependencies of one workspace package, as the lockfile resolves them. */
function packageVersions(lock, frontendRoot, name) {
  const resolved = pnpmImporterVersions(lock, `packages/${name}`)
  const manifest = readJson(resolve(frontendRoot, `packages/${name}/package.json`))
  const runtime = Object.keys(manifest.dependencies ?? {}).filter((dep) => !dep.startsWith('@jabiz/'))
  return new Map(runtime.map((dep) => [dep, resolved.get(dep)]))
}

/**
 * The versions the platform's frontend resolves: the shared packages, and the runtime dependencies of @jabiz/client
 * (dayjs, whose locale it sets, and the API client) and of @jabiz/ui.
 */
export function platformVersions(frontendRoot) {
  const lock = readFileSync(resolve(frontendRoot, 'pnpm-lock.yaml'), 'utf8')
  const admin = pnpmImporterVersions(lock, '.')
  return {
    shared: new Map(SHARED.map((name) => [name, admin.get(name)])),
    client: packageVersions(lock, frontendRoot, 'client'),
    ui: packageVersions(lock, frontendRoot, 'ui'),
  }
}

/** Every difference between the SPA and the platform, one line each; empty when they agree. */
export function appCheckProblems(app, platform) {
  const problems = []
  const usesUi = '@jabiz/ui' in app.declared
  // @jabiz/ui builds on @jabiz/client, so an SPA with the former has the latter's dependencies too.
  const usesClient = usesUi || '@jabiz/client' in app.declared
  const wanted = new Map([...platform.shared, ...(usesClient ? platform.client : []), ...(usesUi ? platform.ui : [])])
  for (const [name, version] of wanted) {
    if (!version) {
      problems.push(`${name}: the platform's frontend does not resolve it (run pnpm install in frontend/)`)
      continue
    }
    const own = app.resolved.get(name)
    if (!(name in app.declared) && own === undefined) {
      problems.push(`${name}: not a dependency; add "${name}": "${version}"`)
    } else if (own === undefined) {
      problems.push(`${name}: version not resolved (no lockfile entry, and "${app.declared[name]}" is not exact); use "${version}"`)
    } else if (own !== version) {
      problems.push(`${name}: ${own}, the platform has ${version}`)
    }
  }
  return problems
}

/** The check of the SPA in `dir` against the platform's frontend in `frontendRoot`. */
export function checkApp(dir, frontendRoot) {
  return appCheckProblems(projectVersions(dir), platformVersions(frontendRoot))
}

if (process.argv[1] && resolve(process.argv[1]) === resolve(import.meta.filename)) {
  const value = process.argv[2]
  if (!value) {
    console.error('app:check: name the directory of the application\'s SPA (pnpm app:check <dir>)')
    process.exit(2)
  }
  const root = resolve(import.meta.dirname, '..')
  const dir = isAbsolute(value) ? value : resolve(process.env.INIT_CWD ?? process.cwd(), value)
  if (!existsSync(resolve(dir, 'package.json'))) {
    console.error(`app:check: ${dir} has no package.json`)
    process.exit(2)
  }
  const problems = checkApp(dir, root)
  if (problems.length > 0) {
    console.error(`app:check: ${dir} differs from the platform's frontend (decision D34):\n- ${problems.join('\n- ')}`)
    process.exit(1)
  }
  console.log(`app:check: ${dir} uses the platform's versions`)
}
