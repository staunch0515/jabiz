// The store listing texts (docs/quizbuks/store/{play,appstore}-listing.{ja,zh,en}.md, docs/quizbuks/plans/Q49d-store.md):
// the same sections in every language, within each store's lengths, only the takers' app (the sponsor console and
// top-ups stay out of the stores' apps), and the line each store's contest rules ask for. Run with `node --test`.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const LANGUAGES = ['ja', 'zh', 'en']

/** Each store's sections and limits, in characters as the stores count them (code points). */
const STORES = {
  play: {
    sections: { ja: ['アプリ名', '簡単な説明', '詳しい説明'], zh: ['应用名称', '简短说明', '完整说明'],
      en: ['App name', 'Short description', 'Full description'] },
    limits: [30, 80, 4000],
    /** The section of the long text. */
    full: 2,
    owner: 'Google',
  },
  appstore: {
    sections: { ja: ['名前', 'サブタイトル', 'プロモーションテキスト', '説明', 'キーワード'],
      zh: ['名称', '副标题', '推广文本', '描述', '关键词'],
      en: ['Name', 'Subtitle', 'Promotional text', 'Description', 'Keywords'] },
    limits: [30, 30, 170, 4000, 100],
    full: 3,
    owner: 'Apple',
  },
}

/**
 * Words that would put the sponsor side, money-making or rankings into the listing, and, in the launch phase
 * (operations.md "阶段一": prizes at the shop only, no transfers; Q49d-1 D-Q49d-6), any payout or cash. When cash
 * rewards start, the listing and these words change together.
 */
const FORBIDDEN = {
  ja: [/スポンサー/, /チャージ/, /管理画面/, /稼/, /儲/, /No\.?\s*1/i, /お金|現金|振込|換金|出金/],
  zh: [/充值/, /商家后台/, /赚钱/, /第一/, /最好/, /现金|提现|转账|兑现/],
  en: [/top[- ]?up/i, /sponsor console/i, /make money/i, /\bbest\b/i, /#1/, /cash|bank|withdraw|payout|transfer/i],
}
/** The store is not the sponsor of the rewards (Google's contests policy, App Store Review Guideline 5.3.3). */
const NOT_SPONSOR = {
  ja: (owner) => new RegExp(`${owner} はこれらの主催者・提供者ではなく`),
  zh: (owner) => new RegExp(`${owner} 不是这些活动的主办方`),
  en: (owner) => new RegExp(`${owner} is not a sponsor`),
}

const length = (text) => [...text].length

function sections(store, language) {
  const text = readFileSync(new URL(`../../../docs/quizbuks/store/${store}-listing.${language}.md`, import.meta.url),
    'utf8')
  return text.split(/^## /m).slice(1).map((part) => {
    const [heading, ...body] = part.split('\n')
    return { heading: heading.trim(), body: body.join('\n').trim() }
  })
}

for (const [store, spec] of Object.entries(STORES)) {
  for (const language of LANGUAGES) {
    test(`${store} ${language}: the sections within the store's lengths`, () => {
      const found = sections(store, language)
      assert.deepEqual(found.map((s) => s.heading), spec.sections[language])
      found.forEach(({ heading, body }, n) => {
        assert.ok(length(body) <= spec.limits[n], `${heading}: ${length(body)} > ${spec.limits[n]}`)
        if (n !== spec.full) assert.ok(!body.includes('\n'), `${heading} is one line`)
      })
      assert.ok(found[0].body.startsWith('QuizBuks'), found[0].body)
    })

    test(`${store} ${language}: only the takers' app, and ${spec.owner} is not the sponsor of its rewards`, () => {
      const text = sections(store, language).map((s) => s.body).join('\n')
      for (const word of FORBIDDEN[language]) assert.doesNotMatch(text, word)
      assert.match(text, NOT_SPONSOR[language](spec.owner))
      assert.match(text, /18/)
    })
  }

  test(`${store}: the same structure in every language: as many paragraphs and bullet points`, () => {
    const shape = (language) => {
      const full = sections(store, language)[spec.full].body
      return { blocks: full.split(/\n\s*\n/).length, headings: (full.match(/^■/gm) ?? []).length,
        bullets: (full.match(/^・/gm) ?? []).length }
    }
    for (const language of LANGUAGES.slice(1)) assert.deepEqual(shape(language), shape('ja'), language)
  })
}

test('the App Store keywords: comma-separated, no spaces, not repeating the name', () => {
  for (const language of LANGUAGES) {
    const found = sections('appstore', language)
    const keywords = found[4].body
    assert.doesNotMatch(keywords, /,\s|\s,/)
    assert.ok(!keywords.toLowerCase().split(',').includes('quizbuks'))
    assert.equal(new Set(keywords.split(',')).size, keywords.split(',').length, `${language}: a keyword twice`)
  }
})

test('the two stores describe the app alike: the same long text but for the store\'s name', () => {
  for (const language of LANGUAGES) {
    const play = sections('play', language)[STORES.play.full].body
    const appstore = sections('appstore', language)[STORES.appstore.full].body
    assert.equal(appstore.replaceAll('Apple', 'Google'), play, language)
  }
})
