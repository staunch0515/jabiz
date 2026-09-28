import { strict as assert } from 'node:assert'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { BUDGET, firstScreenScripts, measure, report } from './check-bundle.mjs'

const HTML = `<!doctype html><html><head>
<script type="module" crossorigin src="/assets/index-a.js"></script>
<link rel="modulepreload" crossorigin href="/assets/site-b.js">
<link rel="modulepreload" crossorigin href="/assets/site-b.js">
<link rel="stylesheet" crossorigin href="/assets/index-c.css">
<script src="/legacy.js"></script>
</head><body></body></html>`

test('the first screen is the module entry and its preloads, once each; styles and classic scripts are not', () => {
  assert.deepEqual(firstScreenScripts(HTML), ['/assets/index-a.js', '/assets/site-b.js'])
})

test('sizes are gzipped, under the base the site is built for', () => {
  const dist = mkdtempSync(join(tmpdir(), 'bundle-'))
  mkdirSync(join(dist, 'assets'))
  writeFileSync(join(dist, 'index.html'), HTML.replaceAll('"/assets/', '"/site/assets/'))
  writeFileSync(join(dist, 'assets', 'index-a.js'), 'x'.repeat(10000))
  writeFileSync(join(dist, 'assets', 'site-b.js'), 'y')
  const files = measure(dist, '/site/')
  assert.deepEqual(files.map((f) => f.file), ['assets/index-a.js', 'assets/site-b.js'])
  assert.ok(files[0].gzip < 200, 'repetitive text compresses')
})

test('over the budget is reported as a failure', async () => {
  const { randomBytes } = await import('node:crypto')
  const dist = mkdtempSync(join(tmpdir(), 'bundle-'))
  mkdirSync(join(dist, 'assets'))
  writeFileSync(join(dist, 'index.html'), HTML)
  // Random bytes do not compress.
  writeFileSync(join(dist, 'assets', 'index-a.js'), randomBytes(BUDGET + 1024))
  writeFileSync(join(dist, 'assets', 'site-b.js'), 'y')
  const lines = []
  assert.equal(report(dist, '/', (line) => lines.push(line)), false)
  assert.match(lines.at(-1), /OVER/)
})
