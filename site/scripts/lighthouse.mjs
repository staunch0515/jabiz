// Lighthouse against the running application (docs/culture/ROADMAP.md, C4; operations in docs/culture/operations.md):
// the mobile preset (a mid-range phone on a slow 4G connection, simulated), for the home page in the three languages
// and the other entry pages. Fails when the home page's Largest Contentful Paint is 2.5 s or more, or when any page's
// accessibility score is below 100. Reports go to lighthouse/ (not committed); a summary table is printed.
//   E2E_BASE_URL=http://localhost:8080 node scripts/lighthouse.mjs
import { chromium } from '@playwright/test'
import * as chromeLauncher from 'chrome-launcher'
import lighthouse from 'lighthouse'
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

export const LCP_BUDGET_MS = 2500
export const ACCESSIBILITY = 100

/** The pages measured; LCP is judged on the home pages, where visits start. */
export const PAGES = [
  { path: '/en/', lcp: true },
  { path: '/zh/', lcp: true },
  { path: '/ja/', lcp: true },
  { path: '/en/stories', lcp: false },
  { path: '/en/map', lcp: false },
  { path: '/en/search?q=home', lcp: false },
]

/** The verdict of one page's results: what is wrong, if anything. */
export function problems(page, result) {
  const found = []
  if (page.lcp && !(result.lcp < LCP_BUDGET_MS)) found.push(`LCP ${Math.round(result.lcp)} ms ≥ ${LCP_BUDGET_MS} ms`)
  if (result.accessibility < ACCESSIBILITY) found.push(`accessibility ${result.accessibility} < ${ACCESSIBILITY}`)
  return found
}

function summary(lhr) {
  const audit = (id) => lhr.audits[id]?.numericValue
  const script = (lhr.audits['network-requests']?.details?.items ?? [])
    .filter((r) => r.resourceType === 'Script')
    .reduce((sum, r) => sum + (r.transferSize ?? 0), 0)
  return {
    performance: Math.round((lhr.categories.performance?.score ?? 0) * 100),
    accessibility: Math.round((lhr.categories.accessibility?.score ?? 0) * 100),
    lcp: audit('largest-contentful-paint'),
    fcp: audit('first-contentful-paint'),
    tbt: audit('total-blocking-time'),
    cls: audit('cumulative-layout-shift'),
    scriptKb: script / 1024,
    failedA11y: Object.values(lhr.audits)
      .filter((a) => lhr.categories.accessibility.auditRefs.some((r) => r.id === a.id && r.weight > 0) && a.score === 0)
      .map((a) => a.id),
  }
}

async function main() {
  const base = process.env.E2E_BASE_URL ?? 'http://localhost:8080'
  const chrome = await chromeLauncher.launch({
    chromePath: process.env.E2E_CHROMIUM ?? chromium.executablePath(),
    chromeFlags: ['--headless=new', '--no-sandbox', '--disable-gpu'],
  })
  mkdirSync('lighthouse', { recursive: true })
  const rows = []
  let failed = false
  try {
    for (const page of PAGES) {
      const { lhr, report } = await lighthouse(`${base}${page.path}`, {
        port: chrome.port,
        output: 'html',
        onlyCategories: ['performance', 'accessibility'],
        logLevel: 'error',
      })
      const name = page.path.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'root'
      writeFileSync(`lighthouse/${name}.html`, report)
      writeFileSync(`lighthouse/${name}.json`, JSON.stringify(lhr))
      const result = summary(lhr)
      const found = problems(page, result)
      if (found.length > 0) failed = true
      rows.push({ page: page.path, ...result, verdict: found.length ? found.join('; ') : 'ok' })
    }
  } finally {
    chrome.kill()
  }
  const lines = [
    '| Page | Performance | Accessibility | LCP | FCP | TBT | CLS | JS transferred | Verdict |',
    '|---|---|---|---|---|---|---|---|---|',
    ...rows.map(
      (r) =>
        `| \`${r.page}\` | ${r.performance} | ${r.accessibility} | ${(r.lcp / 1000).toFixed(2)} s | ${(r.fcp / 1000).toFixed(2)} s | ` +
        `${Math.round(r.tbt)} ms | ${r.cls.toFixed(3)} | ${r.scriptKb.toFixed(0)} KB | ${r.verdict}` +
        `${r.failedA11y.length ? ` (${r.failedA11y.join(', ')})` : ''} |`,
    ),
  ]
  const table = lines.join('\n')
  console.log(table)
  writeFileSync('lighthouse/summary.md', `${table}\n`)
  if (failed) process.exit(1)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main()
