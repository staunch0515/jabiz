/**
 * `@jabiz/client/vite`: the Vite preset of an application's own SPA (decision D43; docs/guide/app-spa.md). Runs in
 * Node while Vite builds, never in the browser.
 *
 * ```ts
 * export default defineConfig({
 *   plugins: [jabizSpa({ pwa: { name: 'Customer portal', themeColor: '#0f172a', backgroundColor: '#ffffff',
 *     icons: [{ src: 'icon-192.png', sizes: '192x192' }, { src: 'icon-512.png', sizes: '512x512' },
 *       { src: 'icon-maskable-512.png', sizes: '512x512', purpose: 'maskable' }] } }), tailwindcss(), react()],
 * })
 * ```
 *
 * It takes what `jabizApp { spa(...) }` passes to the build: the base (`VITE_BASE`), the sign-in entry
 * (`VITE_JABIZ_ENTRY`), the PWA mode (`VITE_JABIZ_PWA`) and the other SPAs' prefixes (`VITE_JABIZ_SPA_PREFIXES`), and
 * checks them. It keeps one copy of the packages the SPA shares with the linked platform packages (React, i18next,
 * TanStack Query). For a PWA (`on`) it writes `manifest.webmanifest` (and links it from the page) and compiles the
 * platform's Service Worker into `sw.js` with the shell's files to precache; `remove` writes a `sw.js` that
 * unregisters itself and deletes its caches. Missing or wrong icons fail the build.
 */
import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { build } from 'vite'

/** The packages an SPA shares with `@jabiz/client` and `@jabiz/ui`: one copy each (two Reacts break hooks). */
export const SHARED_PACKAGES = ['react', 'react-dom', 'i18next', 'react-i18next', '@tanstack/react-query']
/**
 * The test tools `@jabiz/ui/testing` imports (ROADMAP 16v-1): under Vitest they are the SPA's own copies too, so that
 * the helper asserts with the very Vitest that runs the test, never one of the platform frontend's.
 */
export const TEST_PACKAGES = ['vitest', 'vitest-axe']
const BASE = /^\/(?:[a-z0-9][a-z0-9-]*\/)*$/
const ENTRY = /^[a-z][a-z0-9-]{0,39}$/
const MODES = ['on', 'off', 'remove']

/** The settings from the options and the environment; throws with every problem at once. */
export function spaSettings(options = {}, env = process.env) {
  const problems = []
  const base = options.base ?? (env.VITE_BASE || '/')
  if (!BASE.test(base)) {
    problems.push(`VITE_BASE must be "/" or like "/portal/" (lower-case segments, leading and trailing slash), was "${base}"`)
  }
  const entry = options.entry ?? (env.VITE_JABIZ_ENTRY || null)
  if (entry !== null && !ENTRY.test(entry)) {
    problems.push(`VITE_JABIZ_ENTRY must name a sign-in entry (lower-case letters, digits, hyphens), was "${entry}"`)
  }
  const mode = (options.mode ?? (env.VITE_JABIZ_PWA || 'off'))
  if (!MODES.includes(mode))
    problems.push(`VITE_JABIZ_PWA must be on, off or remove, was "${mode}"`)
  const prefixes = options.prefixes
    ?? (env.VITE_JABIZ_SPA_PREFIXES ?? '').split(',').map((p) => p.trim()).filter((p) => p !== '')
  for (const prefix of prefixes) {
    if (!BASE.test(prefix))
      problems.push(`VITE_JABIZ_SPA_PREFIXES: "${prefix}" is not like "/admin/"`)
  }
  if (mode === 'on' && !options.pwa)
    problems.push('the SPA is a PWA (VITE_JABIZ_PWA=on), but jabizSpa() has no pwa manifest')
  if (problems.length > 0)
    throw new Error(`jabizSpa: ${problems.join('; ')}`)
  return { base, entry, mode, prefixes }
}

/** The web app manifest of the PWA under `base`. */
export function manifestOf(pwa, base) {
  return {
    id: base,
    name: pwa.name,
    short_name: pwa.shortName ?? pwa.name,
    ...(pwa.description ? { description: pwa.description } : {}),
    ...(pwa.lang ? { lang: pwa.lang } : {}),
    start_url: base,
    scope: base,
    display: pwa.display ?? 'standalone',
    theme_color: pwa.themeColor,
    background_color: pwa.backgroundColor,
    icons: pwa.icons.map((icon) => ({
      src: base + icon.src,
      sizes: icon.sizes,
      type: icon.type ?? 'image/png',
      ...(icon.purpose ? { purpose: icon.purpose } : {}),
    })),
  }
}

/** Width and height of a PNG file, or null when it is no PNG. */
export function pngSize(bytes) {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
  if (bytes.length < 24 || signature.some((b, i) => bytes[i] !== b))
    return null
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  // The first chunk is IHDR: length (4), type (4), then width and height.
  if (String.fromCharCode(...bytes.slice(12, 16)) !== 'IHDR')
    return null
  return { width: view.getUint32(16), height: view.getUint32(20) }
}

/** Every problem of the icons against the files in `publicDir`; empty when they will do. */
export function iconProblems(icons, publicDir) {
  const problems = []
  const purposes = (icon) => (icon.purpose ?? 'any').split(/\s+/)
  for (const icon of icons) {
    if (!/^[A-Za-z0-9][A-Za-z0-9._/-]*$/.test(icon.src) || icon.src.split('/').includes('..')) {
      problems.push(`icon "${icon.src}": a path inside public/ (letters, digits, . _ - /)`)
      continue
    }
    const file = resolve(publicDir, icon.src)
    if (!existsSync(file)) {
      problems.push(`icon "${icon.src}" is not in ${publicDir}`)
      continue
    }
    if (icon.type && icon.type !== 'image/png')
      problems.push(`icon "${icon.src}": only image/png, not ${icon.type}`)
    const size = pngSize(readFileSync(file))
    if (!size) {
      problems.push(`icon "${icon.src}" is not a PNG file`)
      continue
    }
    if (`${size.width}x${size.height}` !== icon.sizes) {
      problems.push(`icon "${icon.src}" is ${size.width}x${size.height}, not ${icon.sizes}`)
    }
  }
  if (!icons.some((icon) => icon.sizes === '192x192' && purposes(icon).includes('any'))) {
    problems.push('no 192x192 icon (purpose any)')
  }
  if (!icons.some((icon) => icon.sizes === '512x512' && purposes(icon).includes('any'))) {
    problems.push('no 512x512 icon (purpose any)')
  }
  if (!icons.some((icon) => purposes(icon).includes('maskable')))
    problems.push('no maskable icon')
  return problems
}

/** The files of a directory, relative and with forward slashes; dot files left out. */
function filesOf(dir) {
  if (!existsSync(dir))
    return []
  const out = []
  const walk = (current) => {
    for (const name of readdirSync(current)) {
      if (name.startsWith('.'))
        continue
      const path = join(current, name)
      if (statSync(path).isDirectory())
        walk(path)
      else
        out.push(relative(dir, path).split(sep).join('/'))
    }
  }
  walk(dir)
  return out.sort()
}

/** The files to precache: everything the build made or copied but source maps, the manifest and the worker itself. */
export function precacheList(files, base) {
  const shell = new Set([`${base}index.html`])
  for (const file of files) {
    if (file.endsWith('.map') || file === 'sw.js' || file === 'manifest.webmanifest')
      continue
    shell.add(base + file)
  }
  return [...shell].sort()
}

/** The SPA's name in its cache names: `root` for `/`, else the prefix's segments joined by `-` (as `jabizApp`). */
export function spaName(base) {
  const trimmed = base.replace(/^\/+|\/+$/g, '')
  return trimmed === '' ? 'root' : trimmed.replace(/\//g, '-')
}

/** The worker's source, `src/pwa/sw.ts` of this package. */
const WORKER = resolve(dirname(fileURLToPath(import.meta.url)), 'src/pwa/sw.ts')

/** The platform frontend's dependencies, which the linked packages import from (`frontend/node_modules`). */
const PLATFORM_MODULES = resolve(dirname(fileURLToPath(import.meta.url)), '../../node_modules') + sep
const escaped = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The worker for its configuration, bundled into one classic script (a Service Worker is not a module here). */
export async function compileWorker(config) {
  const output = await build({
    configFile: false,
    envDir: false,
    logLevel: 'warn',
    root: dirname(WORKER),
    publicDir: false,
    mode: 'production',
    define: { __JABIZ_SW__: JSON.stringify(config) },
    build: {
      write: false,
      emptyOutDir: false,
      copyPublicDir: false,
      sourcemap: false,
      minify: true,
      modulePreload: false,
      reportCompressedSize: false,
      lib: { entry: WORKER, formats: ['iife'], name: 'jabizServiceWorker', fileName: () => 'sw.js' },
    },
  })
  const outputs = Array.isArray(output) ? output : [output]
  for (const result of outputs) {
    if (!('output' in result))
      continue
    const chunk = result.output.find((item) => item.type === 'chunk')
    if (chunk && chunk.type === 'chunk')
      return chunk.code
  }
  throw new Error('jabizSpa: the Service Worker was not compiled')
}

/** The preset (see the module's description). */
export function jabizSpa(options = {}) {
  const settings = spaSettings(options)
  const name = spaName(settings.base)
  let config
  const base = {
    name: 'jabiz-spa',
    config(user) {
      const root = resolve(user.root ?? process.cwd())
      return {
        base: settings.base,
        resolve: {
          dedupe: SHARED_PACKAGES,
          // Under Vitest, dependencies are loaded by Node, past Vite's deduplication: the libraries the linked packages
          // import (react-hook-form, Radix, lucide, ...) are processed by Vite instead (below), in their ES module builds
          // (a CommonJS build's require() would find the platform frontend's React), and their imports of the shared
          // packages point at the SPA's own copies, so that there is one React. The build bundles everything through
          // Vite, where the deduplication holds by itself.
          ...(process.env.VITEST
            ? {
              alias: [...SHARED_PACKAGES, ...TEST_PACKAGES].map((name) => ({
                find: new RegExp(`^${escaped(name)}(/.*)?$`), replacement: `${join(root, 'node_modules', name)}$1` })),
              mainFields: ['module', 'jsnext:main', 'jsnext', 'main'],
            }
            : {}),
        },
        test: { server: { deps: { inline: [new RegExp(escaped(PLATFORM_MODULES))] } } },
      }
    },
  }
  if (settings.mode === 'off')
    return [base]
  const pwa = {
    name: 'jabiz-spa-pwa',
    apply: 'build',
    configResolved(resolved) {
      config = resolved
    },
    buildStart() {
      if (settings.mode !== 'on')
        return
      const publicDir = config?.publicDir || ''
      const problems = iconProblems(options.pwa.icons, publicDir)
      if (problems.length > 0)
        this.error(`jabizSpa: the PWA's icons will not do: ${problems.join('; ')}`)
    },
    transformIndexHtml() {
      if (settings.mode !== 'on')
        return []
      return [
        { tag: 'link', attrs: { rel: 'manifest', href: `${settings.base}manifest.webmanifest` }, injectTo: 'head' },
        { tag: 'meta', attrs: { name: 'theme-color', content: options.pwa.themeColor }, injectTo: 'head' },
      ]
    },
    generateBundle: {
      order: 'post',
      async handler(_output, bundle) {
        const prefix = `jabiz-shell-${name}-`
        if (settings.mode === 'remove') {
          const code = await compileWorker({ mode: 'remove', cache: '', prefix, base: settings.base, exclude: [],
            precache: [], index: `${settings.base}index.html` })
          this.emitFile({ type: 'asset', fileName: 'sw.js', source: code })
          return
        }
        const publicDir = config?.publicDir || ''
        const publicFiles = filesOf(publicDir)
        const built = Object.keys(bundle)
        const precache = precacheList([...built, ...publicFiles], settings.base)
        // The cache's name changes with any file of the shell: the hashed names, and the contents of the others.
        const hash = createHash('sha256')
        for (const fileName of [...built].sort()) {
          const item = bundle[fileName]
          hash.update(fileName).update('\0').update(item.type === 'chunk' ? item.code : item.source).update('\0')
        }
        for (const file of publicFiles)
          hash.update(file).update('\0').update(readFileSync(join(publicDir, file)))
        const cache = `${prefix}${hash.digest('hex').slice(0, 12)}`
        const code = await compileWorker({ mode: 'on', cache, prefix, base: settings.base,
          exclude: settings.prefixes, precache, index: `${settings.base}index.html` })
        this.emitFile({ type: 'asset', fileName: 'sw.js', source: code })
        this.emitFile({
          type: 'asset',
          fileName: 'manifest.webmanifest',
          source: `${JSON.stringify(manifestOf(options.pwa, settings.base), null, 2)}\n`,
        })
      },
    },
  }
  return [base, pwa]
}
