// The size budgets of the storefront's scripts (docs/quizbuks/shopify/S2b-ui.md section 3, S4c): they load on a
// merchant's product pages, so they stay small. quiz.js loads with every product page; quiz-player.js only when the
// shopper opens the quiz. Fails the build when either, gzipped, is over its budget (Shopify's own limit on quiz.js as
// written is checked by scripts/extension.mjs).
import { readFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'

const BUDGETS = { 'quiz.js': 4 * 1024, 'quiz-player.js': 10 * 1024 }
let over = false
for (const [name, budget] of Object.entries(BUDGETS)) {
  const file = new URL(`../../../tools/quizbuks/shopify/extensions/quizbuks-quiz/assets/${name}`, import.meta.url)
  const size = gzipSync(readFileSync(file)).length
  console.log(`${name}: ${size} bytes gzipped (budget ${budget})`)
  if (size > budget) {
    console.error(`${name} is over its size budget`)
    over = true
  }
}
if (over) process.exit(1)
