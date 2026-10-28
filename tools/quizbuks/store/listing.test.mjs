// The Google Play listing texts (docs/quizbuks/store/play-listing.{ja,zh,en}.md, docs/quizbuks/plans/Q49d-store.md):
// the same sections in every language, within Play's lengths, only the takers' app (the sponsor console and top-ups
// stay out of the stores' apps), and the contests line Google's policy asks for. Run with `node --test`.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const LANGUAGES = ['ja', 'zh', 'en']
/** Play's limits, in characters as Play counts them (code points). */
const LIMITS = { name: 30, short: 80, full: 4000 }
const SECTIONS = { ja: ['アプリ名', '簡単な説明', '詳しい説明'], zh: ['应用名称', '简短说明', '完整说明'],
  en: ['App name', 'Short description', 'Full description'] }
/** Words that would put the sponsor side, money-making or rankings into the listing. */
const FORBIDDEN = {
  ja: [/スポンサー/, /チャージ/, /管理画面/, /稼/, /儲/, /No\.?\s*1/i, /無料で(お金|現金)/],
  zh: [/充值/, /商家后台/, /赚钱/, /第一/, /最好/],
  en: [/top[- ]?up/i, /sponsor console/i, /make money/i, /\bbest\b/i, /#1/, /\bfree money\b/i],
}
const NOT_GOOGLE = { ja: /Google はこれらの主催者・提供者ではなく/, zh: /Google 不是这些活动的主办方/, en: /Google is not a sponsor/ }

const length = (text) => [...text].length

function sections(language) {
  const text = readFileSync(new URL(`../../../docs/quizbuks/store/play-listing.${language}.md`, import.meta.url), 'utf8')
  const parts = text.split(/^## /m).slice(1)
  return parts.map((part) => {
    const [heading, ...body] = part.split('\n')
    return { heading: heading.trim(), body: body.join('\n').trim() }
  })
}

for (const language of LANGUAGES) {
  test(`${language}: the three sections within Play's lengths`, () => {
    const found = sections(language)
    assert.deepEqual(found.map((s) => s.heading), SECTIONS[language])
    const [name, short, full] = found.map((s) => s.body)
    assert.ok(name.startsWith('QuizBuks'), name)
    assert.ok(length(name) <= LIMITS.name, `name: ${length(name)}`)
    assert.ok(!short.includes('\n') && length(short) <= LIMITS.short, `short: ${length(short)}`)
    assert.ok(length(full) <= LIMITS.full, `full: ${length(full)}`)
  })

  test(`${language}: only the takers' app, and Google is not the sponsor of its rewards`, () => {
    const text = sections(language).map((s) => s.body).join('\n')
    for (const word of FORBIDDEN[language]) assert.doesNotMatch(text, word)
    assert.match(text, NOT_GOOGLE[language])
    assert.match(text, /18/)
  })
}

test('the same structure in every language: as many paragraphs and bullet points', () => {
  const shape = (language) => {
    const full = sections(language)[2].body
    return { blocks: full.split(/\n\s*\n/).length, headings: (full.match(/^■/gm) ?? []).length,
      bullets: (full.match(/^・/gm) ?? []).length }
  }
  for (const language of LANGUAGES.slice(1)) assert.deepEqual(shape(language), shape('ja'), language)
})
