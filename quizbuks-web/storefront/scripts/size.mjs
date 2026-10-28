// The size budget of the storefront's script (docs/quizbuks/shopify/S2b-ui.md section 3): it loads on a merchant's
// product pages, so it stays small. Fails the build when the gzipped quiz.js is over the budget.
import { readFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'

const BUDGET = 15 * 1024
const file = new URL('../../../tools/quizbuks/shopify/extensions/quizbuks-quiz/assets/quiz.js', import.meta.url)
const size = gzipSync(readFileSync(file)).length
console.log(`quiz.js: ${size} bytes gzipped (budget ${BUDGET})`)
if (size > BUDGET) {
  console.error('quiz.js is over its size budget')
  process.exit(1)
}
