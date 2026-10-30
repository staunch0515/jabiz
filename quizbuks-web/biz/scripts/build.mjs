// Renders the business site: one HTML file per page and language from src/templates and src/content, the stylesheet
// and the script under assets/ with a content hash in their names (the server caches <prefix>/assets/* for a year
// and every page with no-cache, docs/design/17-apps-and-branches.md section 3.2). Gradle runs
// `pnpm run build --outDir <dir> --emptyOutDir` (BootAppPlugin); the base is VITE_BASE (/biz/).
import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, watch, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { pagePath, renderPage } from '../src/templates/page.mjs'
import { LANGUAGES, PAGES, contentOf } from '../src/content/index.mjs'
import { BRAND, BRAND_CSS, BRAND_PUBLIC } from '../src/brand.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const outDir = resolve(root, args.includes('--outDir') ? args[args.indexOf('--outDir') + 1] : 'dist')
const base = process.env.VITE_BASE ?? '/biz/'
if (!/^\/[a-z0-9-]+\/$/.test(base)) throw new Error(`VITE_BASE must be like "/biz/", was "${base}"`)
// The site's public origin, for canonical and hreflang links (search engines want them absolute) and the sitemap: the
// brand profile's unless SITE_URL says otherwise.
const siteUrl = (process.env.SITE_URL ?? BRAND.siteUrl).replace(/\/$/, '')
if (!/^https:\/\/[a-z0-9.-]+(:\d+)?$/.test(siteUrl)) throw new Error(`SITE_URL must be an https origin, was "${siteUrl}"`)

/** The try-it quizzes of the home page; a wrong value fails the build rather than rendering a bad link. */
export function readTryQuizzes(json) {
  const entries = JSON.parse(json)
  for (const language of LANGUAGES) {
    const entry = entries[language] ?? {}
    if (entry.url && !/^https:\/\/[^\s"'<>]+$/.test(entry.url)) throw new Error(`try-quizzes.json: ${language}.url must be an https URL`)
    if (entry.qr && !/^[a-z0-9][a-z0-9/_.-]*\.(png|svg)$/i.test(entry.qr) || /(^|\/)\.\.(\/|$)/.test(entry.qr ?? '')) {
      throw new Error(`try-quizzes.json: ${language}.qr must be a PNG or SVG under public/`)
    }
  }
  return entries
}

function hashed(file, extension, appended = '') {
  const bytes = Buffer.concat([readFileSync(join(root, 'src/assets', file)), Buffer.from(appended)])
  const hash = createHash('sha256').update(bytes).digest('hex').slice(0, 8)
  return { name: `${file.replace(/\.[^.]+$/, '')}-${hash}.${extension}`, bytes }
}

export function build() {
  if (args.includes('--emptyOutDir') && existsSync(outDir)) rmSync(outDir, { recursive: true })
  mkdirSync(join(outDir, 'assets'), { recursive: true })
  // The brand's colours (Q54) after the site's own tokens.
  const css = hashed('site.css', 'css', `\n${BRAND_CSS}`)
  const js = hashed('site.js', 'js')
  // The browser gets a classic script (runs in <head> before the body is painted); the tests import the module.
  js.bytes = Buffer.from(js.bytes.toString('utf8').replace(/^export /gm, ''))
  writeFileSync(join(outDir, 'assets', css.name), css.bytes)
  writeFileSync(join(outDir, 'assets', js.name), js.bytes)
  // The brand profile's icons, then the site's own public files (none at present).
  cpSync(BRAND_PUBLIC, outDir, { recursive: true })
  if (existsSync(join(root, 'public'))) cpSync(join(root, 'public'), outDir, { recursive: true })
  const tryQuizzes = readTryQuizzes(readFileSync(join(root, 'src/content/try-quizzes.json'), 'utf8'))
  let count = 0
  const urls = []
  for (const language of LANGUAGES) {
    const content = contentOf(language)
    for (const page of PAGES) {
      const html = renderPage({ language, page, content, base, tryQuizzes, siteUrl,
        assets: { css: `${base}assets/${css.name}`, js: `${base}assets/${js.name}` } })
      urls.push(`${siteUrl}${pagePath(language, page, base)}`)
      const dir = join(outDir, language === 'en' ? '' : language, page === 'home' ? '' : page)
      mkdirSync(dir, { recursive: true })
      writeFileSync(join(dir, 'index.html'), html)
      count++
    }
  }
  writeFileSync(join(outDir, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${u}</loc></url>`).join('\n')}\n</urlset>\n`)
  console.log(`biz: ${count} pages → ${outDir}`)
}

if (!process.env.VITEST) build()
if (args.includes('--watch')) {
  console.log('watching src/ and public/ …')
  const rebuild = () => { try { build() } catch (error) { console.error(error) } }
  watch(join(root, 'src'), { recursive: true }, rebuild)
  if (existsSync(join(root, 'public'))) watch(join(root, 'public'), { recursive: true }, rebuild)
}
