// The admin extension's brand module (admin.mjs, Q55b): up to date with the profiles, every mark without a token.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { ADMIN_MODULE, adminBrandsModule, standaloneMark } from './admin.mjs'

test('the committed module is the profiles\' (node quizbuks-web/brands/admin.mjs writes it)', () => {
  assert.equal(readFileSync(ADMIN_MODULE, 'utf8'), adminBrandsModule())
})

test('the marks are written in with the brand\'s colours', () => {
  assert.equal(standaloneMark('<svg><circle fill="var(--qb-coin)"/></svg>\n', ':root { --qb-coin: #fbbf24; }\n.dark {}'),
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent('<svg><circle fill="#fbbf24"/></svg>')}`)
  assert.throws(() => standaloneMark('<svg fill="var(--qb-x)"/>', ':root {}'), /--qb-x/)
  assert.doesNotMatch(decodeURIComponent(adminBrandsModule()), /var\(/)
})
