// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { build, type Rolldown } from 'vite'
import { afterEach, describe, expect, it } from 'vitest'
import { iconProblems, jabizSpa, manifestOf, pngSize, precacheList, spaName, spaSettings, type PwaOptions } from './vite.mjs'

/** The start of a PNG file of that size: signature and IHDR, which is all the preset reads. */
function png(width: number, height: number): Uint8Array {
  const bytes = new Uint8Array(33)
  bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52])
  const view = new DataView(bytes.buffer)
  view.setUint32(16, width)
  view.setUint32(20, height)
  return bytes
}

const PWA: PwaOptions = {
  name: 'Customer portal',
  shortName: 'Portal',
  themeColor: '#0f172a',
  backgroundColor: '#ffffff',
  icons: [
    { src: 'icon-192.png', sizes: '192x192' },
    { src: 'icon-512.png', sizes: '512x512' },
    { src: 'icons/maskable-512.png', sizes: '512x512', purpose: 'maskable' },
  ],
}

const dirs: string[] = []
afterEach(() => {
  dirs.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true }))
})

/** A tiny SPA with the icons of {@link PWA} (unless left out). */
function project(icons = true): string {
  const dir = mkdtempSync(join(tmpdir(), 'jabiz-spa-'))
  dirs.push(dir)
  mkdirSync(join(dir, 'src'))
  mkdirSync(join(dir, 'public/icons'), { recursive: true })
  writeFileSync(join(dir, 'index.html'), '<!doctype html><html><head><title>x</title></head><body>'
    + '<script type="module" src="/src/main.js"></script></body></html>')
  writeFileSync(join(dir, 'src/main.js'), 'import("./lazy.js").then((m) => console.log(m.default))\n')
  writeFileSync(join(dir, 'src/lazy.js'), 'export default "lazy"\n')
  if (icons) {
    writeFileSync(join(dir, 'public/icon-192.png'), png(192, 192))
    writeFileSync(join(dir, 'public/icon-512.png'), png(512, 512))
    writeFileSync(join(dir, 'public/icons/maskable-512.png'), png(512, 512))
  }
  return dir
}

type Output = Rolldown.OutputChunk | Rolldown.OutputAsset

async function built(dir: string, plugins: ReturnType<typeof jabizSpa>): Promise<Map<string, string>> {
  const result = await build({ root: dir, configFile: false, envDir: false, logLevel: 'silent', plugins,
    build: { write: false, outDir: join(dir, 'dist') } })
  const outputs = (Array.isArray(result) ? result : [result]) as { output: Output[] }[]
  const files = new Map<string, string>()
  for (const item of outputs[0].output) {
    files.set(item.fileName, item.type === 'chunk' ? item.code : String(item.source instanceof Uint8Array
      ? new TextDecoder().decode(item.source) : item.source))
  }
  return files
}

describe('spaSettings', () => {
  it('takes the build variables jabizApp passes', () => {
    expect(spaSettings({ pwa: PWA }, { VITE_BASE: '/portal/', VITE_JABIZ_ENTRY: 'portal', VITE_JABIZ_PWA: 'on',
      VITE_JABIZ_SPA_PREFIXES: '/, /admin/' })).toEqual({ base: '/portal/', entry: 'portal', mode: 'on',
      prefixes: ['/', '/admin/'] })
    expect(spaSettings({}, {})).toEqual({ base: '/', entry: null, mode: 'off', prefixes: [] })
  })

  it('refuses a malformed base, entry, mode or prefix, and a PWA without a manifest, all at once', () => {
    expect(() => spaSettings({}, { VITE_BASE: '/Portal', VITE_JABIZ_ENTRY: 'Portal!', VITE_JABIZ_PWA: 'yes',
      VITE_JABIZ_SPA_PREFIXES: 'admin' })).toThrow(/VITE_BASE.*VITE_JABIZ_ENTRY.*VITE_JABIZ_PWA.*"admin"/)
    expect(() => spaSettings({}, { VITE_JABIZ_PWA: 'on' })).toThrow(/no pwa manifest/)
    expect(() => spaSettings({ base: '//evil.example/' }, {})).toThrow(/VITE_BASE/)
  })
})

describe('the manifest and its icons', () => {
  it('is the SPA\'s: id, start URL and scope are its prefix, the icons below it', () => {
    expect(manifestOf(PWA, '/portal/')).toEqual({
      id: '/portal/', name: 'Customer portal', short_name: 'Portal', start_url: '/portal/', scope: '/portal/',
      display: 'standalone', theme_color: '#0f172a', background_color: '#ffffff',
      icons: [
        { src: '/portal/icon-192.png', sizes: '192x192', type: 'image/png' },
        { src: '/portal/icon-512.png', sizes: '512x512', type: 'image/png' },
        { src: '/portal/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
      ],
    })
  })

  it('needs 192 and 512 icons and a maskable one, each a PNG of its stated size', () => {
    const dir = project()
    expect(iconProblems(PWA.icons, join(dir, 'public'))).toEqual([])
    writeFileSync(join(dir, 'public/wrong.png'), png(100, 100))
    writeFileSync(join(dir, 'public/not.png'), 'GIF89a')
    expect(iconProblems([
      { src: 'wrong.png', sizes: '192x192' },
      { src: 'not.png', sizes: '512x512' },
      { src: 'missing.png', sizes: '512x512', purpose: 'maskable' },
      { src: '../secret.png', sizes: '512x512' },
      { src: 'icon-192.png', sizes: '192x192', type: 'image/webp' },
    ], join(dir, 'public'))).toEqual([
      'icon "wrong.png" is 100x100, not 192x192',
      'icon "not.png" is not a PNG file',
      `icon "missing.png" is not in ${join(dir, 'public')}`,
      'icon "../secret.png": a path inside public/ (letters, digits, . _ - /)',
      'icon "icon-192.png": only image/png, not image/webp',
    ])
    expect(iconProblems([], join(dir, 'public'))).toEqual(['no 192x192 icon (purpose any)',
      'no 512x512 icon (purpose any)', 'no maskable icon'])
    expect(pngSize(png(48, 32))).toEqual({ width: 48, height: 32 })
  })
})

describe('the shell', () => {
  it('precaches what the build made, never maps, the manifest or the worker', () => {
    expect(precacheList(['assets/index-a.js', 'assets/index-a.js.map', 'assets/index-b.css', 'sw.js',
      'manifest.webmanifest', 'icon-192.png'], '/portal/')).toEqual([
      '/portal/assets/index-a.js', '/portal/assets/index-b.css', '/portal/icon-192.png', '/portal/index.html'])
    expect(spaName('/')).toBe('root')
    expect(spaName('/portal/')).toBe('portal')
    expect(spaName('/a/b/')).toBe('a-b')
  })
})

describe('jabizSpa', () => {
  it('builds a PWA: the manifest, a linked page and a worker precaching only the shell', async () => {
    const dir = project()
    const files = await built(dir, jabizSpa({ pwa: PWA, base: '/portal/', mode: 'on', prefixes: ['/', '/admin/'] }))

    const manifest = JSON.parse(files.get('manifest.webmanifest')!)
    expect(manifest.scope).toBe('/portal/')
    expect(manifest.icons).toHaveLength(3)
    expect(files.get('index.html')).toContain('<link rel="manifest" href="/portal/manifest.webmanifest">')
    expect(files.get('index.html')).toContain('src="/portal/assets/')

    const worker = files.get('sw.js')!
    const chunks = [...files.keys()].filter((name) => name.startsWith('assets/') && name.endsWith('.js'))
    expect(chunks.length).toBeGreaterThanOrEqual(2)
    for (const chunk of chunks) expect(worker).toContain(`/portal/${chunk}`)
    expect(worker).toContain('/portal/index.html')
    expect(worker).toContain('/portal/icons/maskable-512.png')
    expect(worker).toMatch(/jabiz-shell-portal-[0-9a-f]{12}/)
    expect(worker).toContain('/admin/')
    // Nothing of the API, and a classic script: no import statements (a worker registered without type: module).
    expect(worker).not.toContain('/api/')
    expect(worker).not.toMatch(/^\s*import\s/m)
    expect(worker).not.toContain('sw.js"')
    expect(worker).toContain('SKIP_WAITING')
  }, 60_000)

  it('fails the build when an icon is missing', async () => {
    const dir = project(false)
    await expect(built(dir, jabizSpa({ pwa: PWA, base: '/portal/', mode: 'on' }))).rejects.toThrow(/icons will not do/)
  }, 60_000)

  it('takes a PWA back with a worker that unregisters itself, and without a manifest', async () => {
    const dir = project(false)
    const files = await built(dir, jabizSpa({ base: '/portal/', mode: 'remove' }))
    expect(files.has('manifest.webmanifest')).toBe(false)
    expect(files.get('index.html')).not.toContain('manifest')
    expect(files.get('sw.js')).toContain('unregister')
    expect(files.get('sw.js')).toContain('jabiz-shell-portal-')
  }, 60_000)

  it('builds no PWA when off, but keeps one copy of the shared packages', () => {
    const plugins = jabizSpa({ base: '/portal/', mode: 'off' })
    expect(plugins.map((p) => p.name)).toEqual(['jabiz-spa'])
    type Config = { base: string; resolve: { dedupe: string[]; alias?: { find: RegExp; replacement: string }[] } }
    const config = (plugins[0].config as (user: { root?: string }) => Config)({ root: '/spa' })
    expect(config.base).toBe('/portal/')
    expect(config.resolve.dedupe).toEqual(expect.arrayContaining(['react', 'react-dom', 'i18next']))
    // Under Vitest, the shared packages are the SPA's own copies, also for the libraries the linked packages import.
    const react = config.resolve.alias!.find((a) => a.find.test('react/jsx-runtime'))!
    expect('react/jsx-runtime'.replace(react.find, react.replacement)).toBe('/spa/node_modules/react/jsx-runtime')
    expect(config.resolve.alias!.some((a) => a.find.test('react-domx'))).toBe(false)
    // And the test tools of @jabiz/ui/testing (16v-1): the SPA's own Vitest asserts.
    for (const name of ['vitest', 'vitest-axe']) {
      const alias = config.resolve.alias!.find((a) => a.find.test(name))!
      expect(name.replace(alias.find, alias.replacement)).toBe(`/spa/node_modules/${name}`)
    }
    expect('vitest-axe/matchers'.replace(config.resolve.alias!.find((a) => a.find.test('vitest-axe/matchers'))!.find,
      '/x$1')).toBe('/x/matchers')
  })
})
