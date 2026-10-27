// Renders QuizBuks for Shopify's public pages (docs/quizbuks/shopify/S4-launch.md section 1): one HTML file per page and
// language from src/templates and src/content, and the stylesheet under assets/ with a content hash in its name (the
// server caches <prefix>/assets/* for a year and every page with no-cache). Gradle runs
// `pnpm run build --outDir <dir> --emptyOutDir` (BootAppPlugin); the base is VITE_BASE (/shopify-app/).
import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { pagePath, renderPage } from '../src/templates/page.mjs'
import { LANGUAGES, PAGES, contentOf } from '../src/content/index.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** The build's settings from the environment; a wrong value fails the build rather than rendering a bad page. */
export function settings(env) {
  const base = env.VITE_BASE ?? '/shopify-app/'
  if (!/^\/[a-z0-9-]+\/$/.test(base)) throw new Error(`VITE_BASE must be like "/shopify-app/", was "${base}"`)
  // The site's public origin, for canonical and hreflang links and the sitemap.
  const siteUrl = (env.SITE_URL ?? 'https://quizbuks.com').replace(/\/$/, '')
  if (!/^https:\/\/[a-z0-9.-]+(:\d+)?$/.test(siteUrl)) throw new Error(`SITE_URL must be an https origin, was "${siteUrl}"`)
  // Where merchants write to (the same address the embedded pages show, QB_SHOPIFY_SUPPORT_EMAIL).
  const supportEmail = env.QB_SHOPIFY_SUPPORT_EMAIL || 'shopify-support@quizbuks.com'
  if (!/^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(supportEmail)) {
    throw new Error(`QB_SHOPIFY_SUPPORT_EMAIL must be an email address, was "${supportEmail}"`)
  }
  return { base, siteUrl, supportEmail }
}

export function build(args = process.argv.slice(2), env = process.env) {
  const outDir = resolve(root, args.includes('--outDir') ? args[args.indexOf('--outDir') + 1] : 'dist')
  const { base, siteUrl, supportEmail } = settings(env)
  if (args.includes('--emptyOutDir') && existsSync(outDir)) rmSync(outDir, { recursive: true })
  mkdirSync(join(outDir, 'assets'), { recursive: true })
  const bytes = readFileSync(join(root, 'src/assets/site.css'))
  const css = `site-${createHash('sha256').update(bytes).digest('hex').slice(0, 8)}.css`
  writeFileSync(join(outDir, 'assets', css), bytes)
  cpSync(join(root, 'public'), outDir, { recursive: true })
  const urls = []
  for (const language of LANGUAGES) {
    const content = contentOf(language)
    for (const page of PAGES) {
      const html = renderPage({ language, page, content, base, siteUrl, supportEmail, css: `${base}assets/${css}` })
      urls.push(`${siteUrl}${pagePath(language, page, base)}`)
      const dir = join(outDir, language === 'en' ? '' : language, page === 'home' ? '' : page)
      mkdirSync(dir, { recursive: true })
      writeFileSync(join(dir, 'index.html'), html)
    }
  }
  writeFileSync(join(outDir, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${u}</loc></url>`).join('\n')}\n</urlset>\n`)
  console.log(`shopify-site: ${urls.length} pages → ${outDir}`)
  return outDir
}

if (!process.env.VITEST) build()
