// Renders the business site: one HTML file per page and language from src/templates and src/content, the stylesheet
// and the script under assets/ with a content hash in their names (the server caches <prefix>/assets/* for a year
// and every page with no-cache, docs/design/17-apps-and-branches.md section 3.2). Gradle runs
// `pnpm run build --outDir <dir> --emptyOutDir` (BootAppPlugin); the base is VITE_BASE (/biz/).
import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, watch, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { renderPage } from '../src/templates/page.mjs'
import { LANGUAGES, PAGES, contentOf } from '../src/content/index.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const args = process.argv.slice(2)
const outDir = resolve(root, args.includes('--outDir') ? args[args.indexOf('--outDir') + 1] : 'dist')
const base = process.env.VITE_BASE ?? '/biz/'
if (!/^\/[a-z0-9-]+\/$/.test(base)) throw new Error(`VITE_BASE must be like "/biz/", was "${base}"`)

function hashed(file, extension) {
  const bytes = readFileSync(join(root, 'src/assets', file))
  const hash = createHash('sha256').update(bytes).digest('hex').slice(0, 8)
  return { name: `${file.replace(/\.[^.]+$/, '')}-${hash}.${extension}`, bytes }
}

export function build() {
  if (args.includes('--emptyOutDir') && existsSync(outDir)) rmSync(outDir, { recursive: true })
  mkdirSync(join(outDir, 'assets'), { recursive: true })
  const css = hashed('site.css', 'css')
  const js = hashed('site.js', 'js')
  writeFileSync(join(outDir, 'assets', css.name), css.bytes)
  writeFileSync(join(outDir, 'assets', js.name), js.bytes)
  cpSync(join(root, 'public'), outDir, { recursive: true })
  const tryQuizzes = JSON.parse(readFileSync(join(root, 'src/content/try-quizzes.json'), 'utf8'))
  let count = 0
  for (const language of LANGUAGES) {
    const content = contentOf(language)
    for (const page of PAGES) {
      const html = renderPage({ language, page, content, base, tryQuizzes,
        assets: { css: `${base}assets/${css.name}`, js: `${base}assets/${js.name}` } })
      const dir = join(outDir, language === 'en' ? '' : language, page === 'home' ? '' : page)
      mkdirSync(dir, { recursive: true })
      writeFileSync(join(dir, 'index.html'), html)
      count++
    }
  }
  console.log(`biz: ${count} pages → ${outDir}`)
}

build()
if (args.includes('--watch')) {
  console.log('watching src/ …')
  watch(join(root, 'src'), { recursive: true }, () => {
    try { build() } catch (error) { console.error(error) }
  })
}
