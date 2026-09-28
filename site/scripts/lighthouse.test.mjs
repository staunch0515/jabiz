import { strict as assert } from 'node:assert'
import { test } from 'node:test'
import { PAGES, problems } from './lighthouse.mjs'

test('the home pages are judged on LCP, every page on accessibility', () => {
  const home = { path: '/en/', lcp: true }
  const other = { path: '/en/map', lcp: false }
  assert.deepEqual(problems(home, { lcp: 2499, accessibilityScore: 1 }), [])
  assert.deepEqual(problems(home, { lcp: 2500, accessibilityScore: 1 }), ['LCP 2500 ms ≥ 2500 ms'])
  assert.deepEqual(problems(other, { lcp: 4000, accessibilityScore: 1 }), [])
  assert.deepEqual(problems(other, { lcp: 1000, accessibilityScore: 0.97 }), ['accessibility 97 < 100'])
  // 99.5 is not 100, however it is rounded for the table.
  assert.equal(problems(other, { lcp: 1000, accessibilityScore: 0.995 }).length, 1)
  // A measurement that failed is a failure, not a pass.
  assert.equal(problems(home, { lcp: undefined, accessibilityScore: 1 }).length, 1)
  assert.equal(problems(other, { lcp: 1000, accessibilityScore: undefined }).length, 1)
})

test('the home page is measured in every language of the site', () => {
  assert.deepEqual(PAGES.filter((p) => p.lcp).map((p) => p.path), ['/en/', '/zh/', '/ja/'])
})
