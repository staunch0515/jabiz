// @vitest-environment node
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { BLOCK_SCRIPT_LIMIT, definesPlayer, problems } from './extension.mjs'

let dir

function extension(files) {
  dir = mkdtempSync(join(tmpdir(), 'quizbuks-extension-'))
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(join(dir, path, '..'), { recursive: true })
    writeFileSync(join(dir, path), content)
  }
  return dir
}

const BLOCK = '<quizbuks-quiz></quizbuks-quiz>\n{% schema %}\n{ "name": "t:name", "target": "section", '
  + '"javascript": "quiz.js", "settings": [{ "type": "text", "id": "x", "label": "t:settings.x" }] }\n{% endschema %}'

describe('the extension against what `shopify app deploy` refuses (S4c)', () => {
  afterEach(() => {
    if (dir) rmSync(dir, { recursive: true, force: true })
    dir = undefined
  })

  it('takes an extension as Shopify does', () => {
    expect(problems(extension({
      'blocks/quiz.liquid': BLOCK,
      'assets/quiz.js': 'x'.repeat(BLOCK_SCRIPT_LIMIT),
      'assets/quiz-player.js': 'x'.repeat(BLOCK_SCRIPT_LIMIT * 3),
      'locales/en.default.schema.json': JSON.stringify({ name: 'Quiz', settings: { x: 'X' } }),
    }))).toEqual([])
  })

  it('reports a file Shopify does not take in assets, a block script over its limit and a missing text', () => {
    expect(problems(extension({
      'blocks/quiz.liquid': BLOCK,
      'assets/.gitignore': 'quiz.js',
      'assets/notes.txt': 'x',
      'assets/quiz.js': 'x'.repeat(BLOCK_SCRIPT_LIMIT + 1),
      'locales/en.default.json': JSON.stringify({ name: 'Quiz' }),
    }))).toEqual([
      'assets/.gitignore: Shopify takes only .jpg, .jpeg, .json, .js, .css, .png, .svg, .wasm in assets',
      'assets/notes.txt: Shopify takes only .jpg, .jpeg, .json, .js, .css, .png, .svg, .wasm in assets',
      'blocks/quiz.liquid: assets/quiz.js is 10001 bytes, over Shopify\'s 10000 for a block\'s script',
      'blocks/quiz.liquid: "t:name" has no entry in locales/en.default.schema.json',
      'blocks/quiz.liquid: "t:settings.x" has no entry in locales/en.default.schema.json',
    ])
  })

  it('reports a schema that is not JSON, and a text that is not a text', () => {
    expect(problems(extension({
      'blocks/broken.liquid': '{% schema %}\n{ "name": "t:name", }\n{% endschema %}',
      'blocks/quiz.liquid': BLOCK,
      'assets/quiz.js': 'x',
      'locales/en.default.schema.json': JSON.stringify({ name: 'Quiz', settings: { x: { y: 'X' } } }),
    }))).toEqual([
      expect.stringMatching(/^blocks\/broken\.liquid: its schema is not JSON/),
      'blocks/quiz.liquid: "t:settings.x" has no entry in locales/en.default.schema.json',
    ])
  })

  it('tells a player script that defines the player from one that does not', () => {
    expect(definesPlayer('(function () { class QuizPlayer {} window.QuizbuksPlayer = QuizPlayer })()')).toBe(true)
    expect(definesPlayer('var QuizbuksQuizPlayer = (function () { return {} })()')).toBe(false)
  })

  it('reports a block script that was not built', () => {
    expect(problems(extension({
      'blocks/quiz.liquid': BLOCK,
      'locales/en.default.schema.json': JSON.stringify({ name: 'Quiz', settings: { x: 'X' } }),
    }))).toEqual(['blocks/quiz.liquid: its script assets/quiz.js is not built'])
  })
})
