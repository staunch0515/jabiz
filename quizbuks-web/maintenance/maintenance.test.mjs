// The maintenance page (Q55), `node --test` in CI (quizbuks.yml, web): every brand's page is self-contained and in its
// brand, and the committed page is QuizBuks's as the generator writes it. The page's behaviour in a browser (one
// language, back by itself) is e2e/tests/maintenance.spec.ts.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { brandIds, loadBrand } from '../brands/brand.mjs'
import { DEFAULT_OUT, maintenancePage } from './build.mjs'
import { LANGUAGES, MESSAGES } from './messages.mjs'

for (const id of brandIds()) {
  test(`the page of ${id} is self-contained and in its brand`, () => {
    const brand = loadBrand(id)
    const html = maintenancePage(brand)
    // Nothing loaded from elsewhere: the application is down when it is shown.
    assert.deepEqual([...html.matchAll(/(?:src|href)="(?!data:)([^"]*)"/g)].map((m) => m[1]), [])
    assert.doesNotMatch(html, /@import|url\((?!data:)/)
    for (const language of LANGUAGES) {
      assert.ok(html.includes(`data-l="${language}"`), `${id}: the ${language} panel`)
      // Each language's panel and wordmark call the brand by its name in that language (Q61).
      assert.ok(html.includes(`lang="${MESSAGES[language].lang}">${brand.names[language]}</span>`), `${id}: the ${language} name`)
      assert.ok(html.includes(MESSAGES[language].heading.replace('’', '’')), `${id}: the ${language} heading`)
    }
    assert.ok(html.length < 64 * 1024, `${id}: ${html.length} bytes`)
    // Another brand's page names no QuizBuks.
    if (id !== 'quizbuks') assert.doesNotMatch(html, /QuizBuks/)
  })
}

test('the committed page is QuizBuks’s as the generator writes it', () => {
  assert.equal(readFileSync(DEFAULT_OUT, 'utf8'), maintenancePage(loadBrand('quizbuks')),
    'run `node quizbuks-web/maintenance/build.mjs` and commit deploy/quizbuks/server/maintenance.html')
})
