// The numbered sections of docs/quizbuks/operations.md: every section of a chapter has a number of its own, the
// numbers run 1, 2, 3 … without a gap and in the order of the file, and every "operations.md §7.NN" written anywhere in
// the product's own files names a section that exists. Sections were once written by several people in parallel and
// two or three of them took the same number, so a reference no longer said which section it meant. Run with `node --test`.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const ROOT = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..', '..')
const DOCUMENT = 'docs/quizbuks/operations.md'

/** The headings of the document: `## 7. …` chapters and `### 7.12 …` sections, in file order, with their line. */
export function headings(text) {
  const found = []
  text.split('\n').forEach((line, i) => {
    const chapter = /^## (\d+)\. /.exec(line)
    if (chapter) found.push({ chapter: Number(chapter[1]), section: null, line: i + 1, text: line })
    const section = /^### (\d+)\.(\d+) /.exec(line)
    if (section) found.push({ chapter: Number(section[1]), section: Number(section[2]), line: i + 1, text: line })
  })
  return found
}

/** What is wrong with the numbering: duplicates, gaps, a section outside its chapter, or out of order. */
export function numberingProblems(found) {
  const problems = []
  let chapter = null
  const seen = new Map()
  for (const h of found) {
    if (h.section === null) {
      chapter = h.chapter
      continue
    }
    if (h.chapter !== chapter) {
      problems.push(`line ${h.line}: "${h.text}" is in chapter ${chapter}, not ${h.chapter}`)
    }
    const key = `${h.chapter}.${h.section}`
    if (seen.has(key)) problems.push(`line ${h.line}: ${key} is also line ${seen.get(key)} ("${h.text}")`)
    else seen.set(key, h.line)
  }
  const byChapter = new Map()
  for (const h of found.filter((x) => x.section !== null)) {
    if (!byChapter.has(h.chapter)) byChapter.set(h.chapter, [])
    byChapter.get(h.chapter).push(h)
  }
  for (const [number, sections] of byChapter) {
    sections.forEach((h, i) => {
      if (h.section !== i + 1) problems.push(`line ${h.line}: section ${number}.${i + 1} was expected here, found ${number}.${h.section}`)
    })
  }
  return problems
}

/** `operations.md §7.12`, `operations.md section 7.12`, `operations §7.12` and `（§7.12）` inside the document itself. */
export function referencesIn(text, inDocument) {
  const pattern = inDocument
    ? /§(7)\.(\d+)/g
    : /operations(?:\.md)?[^\n]{0,24}?(?:§|section )(\d+)\.(\d+)/g
  return [...text.matchAll(pattern)].map((m) => ({ key: `${m[1]}.${m[2]}`, index: m.index }))
}

const lineOf = (text, index) => text.slice(0, index).split('\n').length

test('the sections of operations.md are numbered once each, without gaps, in order', () => {
  const found = headings(readFileSync(join(ROOT, DOCUMENT), 'utf8'))
  assert.ok(found.filter((h) => h.section !== null).length > 20, 'the sections were found')
  assert.deepEqual(numberingProblems(found), [])
})

test('the numbering check catches a duplicate, a gap, a stray section and a wrong order', () => {
  const doc = ['## 7. Faults', '### 7.1 a', '### 7.2 b', '### 7.2 c', '### 7.5 d', '## 8. Upgrade', '### 7.6 e'].join('\n')
  const problems = numberingProblems(headings(doc))
  // The stray 7.6 is also out of line in the sequence of chapter 7 (a heading counts for the chapter it names).
  assert.equal(problems.length, 5)
  assert.match(problems[0], /7\.2 is also line 3/)
  assert.match(problems[1], /in chapter 8, not 7/)
  assert.match(problems.join('\n'), /7\.3 was expected here, found 7\.2/)
  assert.match(problems.join('\n'), /7\.4 was expected here, found 7\.5/)
  assert.match(problems.join('\n'), /7\.5 was expected here, found 7\.6/)
})

test('every reference to a section of operations.md names one that exists', () => {
  const found = headings(readFileSync(join(ROOT, DOCUMENT), 'utf8'))
  const exists = new Set(found.filter((h) => h.section !== null).map((h) => `${h.chapter}.${h.section}`))
  const files = execFileSync('git', ['ls-files', 'docs/quizbuks', 'backend/quizbuks', 'backend/quizbuks-shopify',
    'backend/quizbuks-channel', 'deploy/quizbuks', 'quizbuks-web', 'tools/quizbuks'], { cwd: ROOT, encoding: 'utf8' })
    .split('\n').filter((f) => /\.(md|ts|tsx|java|yaml|yml|sh|mjs|json|sql)$/.test(f) && !f.includes('node_modules'))
  const missing = []
  for (const file of files) {
    let text
    try {
      text = readFileSync(join(ROOT, file), 'utf8')
    } catch {
      continue
    }
    for (const ref of referencesIn(text, file === DOCUMENT)) {
      if (!exists.has(ref.key)) missing.push(`${file}:${lineOf(text, ref.index)} names section ${ref.key}`)
    }
  }
  assert.deepEqual(missing, [])
})
