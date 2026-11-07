// The product's terms (F-F5, docs/quizbuks/first-launch-spec.md; backend/quizbuks/CLAUDE.md "称呼"): the side that
// answers is 学者 / テイカー / Taker, the side that publishes is 发起方 / 主催者 / Sponsor. Every text a person reads
// is scanned for the words they replace; an exception names its file, its word and why. Run with `node --test`.
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const ROOT = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..')

/** The words replaced, in every language's texts (a Chinese word in a Japanese text is as wrong). */
export const STOP_WORDS = [
  { word: /商户/g, use: '发起方' },
  { word: /商家/g, use: '发起方' },
  { word: /答题者/g, use: '学者' },
  { word: /答题人/g, use: '学者' },
  { word: /回答者/g, use: 'テイカー（日文）/ 学者（中文）' },
  { word: /スポンサー/g, use: '主催者' },
  { word: /加盟店/g, use: '主催者' },
  { word: /\bmerchants?\b/gi, use: 'Sponsor' },
  { word: /\bquiz[- ]takers?\b/gi, use: 'Taker' },
]

/**
 * Where people's texts are: the three front ends' messages and pages, the server's messages and the display texts in
 * its code (roles, dictionaries), the business site, the legal drafts, the store listings and the help texts. Tests are left out:
 * they assert the texts, and a test of a refused word must name it.
 */
const SCANNED = [
  { dir: 'quizbuks-web/app/src', ext: ['.ts', '.tsx'] },
  { dir: 'quizbuks-web/sponsor/src', ext: ['.ts', '.tsx'] },
  { dir: 'quizbuks-web/maintenance', ext: ['.html', '.js', '.mjs', '.json'] },
  { dir: 'quizbuks-web/biz/src', ext: ['.mjs', '.json', '.js'] },
  { dir: 'quizbuks-web/brands', ext: ['.json'] },
  { dir: 'backend/quizbuks/admin-extension/src', ext: ['.ts', '.tsx'] },
  { dir: 'backend/quizbuks/src/main/resources', ext: ['.properties', '.txt', '.json', '.sql', '.html'] },
  // The code's display texts: role names, dictionaries, legal drafts' titles. Its comments are in English.
  { dir: 'backend/quizbuks/src/main/java', ext: ['.java'], cjkOnly: true },
  { dir: 'docs/quizbuks/legal', ext: ['.md'] },
  { dir: 'docs/quizbuks/store', ext: ['.md'] },
  // The help centre's texts kept in the repository (F-C1d), published by the content administrators.
  { dir: 'docs/quizbuks/help', ext: ['.md'] },
]

/** Files whose texts may keep a word, each with the reason. */
export const EXCEPTIONS = [
  {
    file: 'quizbuks-web/sponsor/src/publishing/MailServerPage.tsx',
    word: /merchant/,
    why: 'A comment names the plan file docs/quizbuks/plans/Q29-merchant-mail-server.md, kept as it was written.',
  },
]

const isTest = (path) => /\.test\.|[/\\]test[/\\]|[/\\]testing[/\\]/.test(path)

function* files(dir, ext) {
  let names
  try {
    names = readdirSync(dir)
  } catch {
    return
  }
  for (const name of names) {
    // Migrations are history (their comments are not read by people, and Flyway checks their sums).
    if (name === 'node_modules' || name === 'dist' || name === 'build' || name === 'db') continue
    const path = join(dir, name)
    if (statSync(path).isDirectory()) yield* files(path, ext)
    else if (ext.some((e) => name.endsWith(e)) && !isTest(path)) yield path
  }
}

/** The troubleshooting entries' help texts (`帮助页文案`): what goes into the help centre as it is. */
function helpTexts() {
  const path = join(ROOT, 'docs/quizbuks/troubleshooting.md')
  const lines = readFileSync(path, 'utf8').split('\n')
  const found = []
  let inHelp = false
  lines.forEach((line, i) => {
    if (/^#{1,3} /.test(line) || /^- \*\*(?!帮助页文案)/.test(line) || /^---/.test(line)) inHelp = false
    if (/^(- )?\*\*帮助页文案/.test(line)) inHelp = true
    else if (inHelp) found.push({ line: i + 1, text: line })
  })
  return { file: 'docs/quizbuks/troubleshooting.md', lines: found }
}

/** Every use of a replaced word, as `file:line word (use …)`. */
export function findOldTerms() {
  const problems = []
  const check = (file, lineNo, text, cjkOnly) => {
    for (const { word, use } of STOP_WORDS) {
      if (cjkOnly && word.flags.includes('i')) continue
      for (const match of text.matchAll(word)) {
        if (EXCEPTIONS.some((e) => e.file === file && e.word.test(match[0]))) continue
        problems.push(`${file}:${lineNo} ${match[0]} (use ${use})`)
      }
    }
  }
  for (const { dir, ext, cjkOnly } of SCANNED) {
    for (const path of files(join(ROOT, dir), ext)) {
      const file = relative(ROOT, path).split(sep).join('/')
      readFileSync(path, 'utf8').split('\n').forEach((text, i) => check(file, i + 1, text, cjkOnly))
    }
  }
  const help = helpTexts()
  for (const { line, text } of help.lines) check(help.file, line, text, false)
  return problems
}

test('no text a person reads uses the replaced terms (F-F5)', () => {
  const problems = findOldTerms()
  assert.deepEqual(problems, [], `${problems.length} uses of replaced terms:\n${problems.slice(0, 200).join('\n')}`)
})

test('the scan finds a replaced word where it is (the test itself works)', () => {
  const word = STOP_WORDS.find((w) => w.word.source === '商家').word
  assert.equal([...'联系商家'.matchAll(word)].length, 1)
  assert.equal([...'Contact the merchant'.matchAll(STOP_WORDS.find((w) => w.use === 'Sponsor').word)].length, 1)
  assert.equal([...'a quiz taker'.matchAll(STOP_WORDS.find((w) => w.use === 'Taker').word)].length, 1)
  // A scanned folder holds files: a moved folder would make the first test pass by finding nothing.
  for (const { dir, ext } of SCANNED.filter((s) => !s.dir.includes('maintenance'))) {
    assert.ok([...files(join(ROOT, dir), ext)].length > 0, `${dir} has no files to scan`)
  }
})
