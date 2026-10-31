import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { brandIds, chosenBrand, loadBrand, problems } from '../../../brands/brand.mjs'
import { BRAND } from './brand'

/*
 * The brand profile (Q54): the profiles are complete, the build's is the one QB_BRAND chose, and the SPA's code names
 * no brand of its own. This file is the same in both SPAs (src/qb).
 */

const COMPLETE = {
  name: 'Quizzle',
  names: { en: 'Quizzle', zh: '答题乐', ja: 'クイズル' },
  description: { en: 'Quizzes', zh: '答题', ja: 'クイズ' },
  themeColor: '#123456',
  backgroundColor: '#ffffff',
  supportEmail: 'help@quizzle.example',
  siteUrl: 'https://quizzle.example',
}

describe('the brand profiles', () => {
  it.each(brandIds())('%s is complete', (id) => {
    expect(() => loadBrand(id)).not.toThrow()
  })

  it('says what a profile lacks', () => {
    expect(problems('x', COMPLETE, () => true)).toEqual([])
    expect(problems('x', { ...COMPLETE, name: ' ', names: { en: 'Quizzle' }, description: { en: 'Quizzes' },
      themeColor: '#ABCDEF', supportEmail: 'help', siteUrl: 'http://quizzle.example/path' },
    (file) => file !== 'mark.svg')).toEqual([
      'name: a text of 1 to 30 characters',
      'names.zh: a text of 1 to 30 characters',
      'names.ja: a text of 1 to 30 characters',
      'description.zh: a text of 1 to 120 characters',
      'description.ja: a text of 1 to 120 characters',
      'themeColor: a colour #rrggbb in lower case',
      'supportEmail: an e-mail address',
      'siteUrl: https://host, without a path',
      'mark.svg: missing',
    ])
  })

  it('refuses a brand there is no profile of', () => {
    expect(() => loadBrand('../app')).toThrow(/no such brand profile/)
    expect(() => loadBrand('nobody')).toThrow(/no such brand profile/)
  })

  it('builds with the brand QB_BRAND chose, QuizBuks by default', () => {
    expect(chosenBrand({})).toBe('quizbuks')
    expect(chosenBrand({ QB_BRAND: ' sample ' })).toBe('sample')
    const brand = loadBrand(chosenBrand())
    expect(BRAND).toEqual({ name: brand.name, names: brand.names, shortName: brand.shortName,
      description: brand.description, supportEmail: brand.supportEmail, siteUrl: brand.siteUrl })
  })
})

/** Identifiers that keep the word whatever the brand: dictionaries, the shells' ids, the quiz file format, keys. */
const IDENTIFIERS = [
  /urn:jabiz:dict:quizbuks:/g,
  /'com\.quizbuks\.app'/g,
  /'QuizBuksApp\/ios'/g,
  /'quizbuks\.quiz'/g,
  /\.quizbuks\.json/g,
  /fromQuizbuks/g,
]

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return sources(path)
    return /\.(ts|tsx|html|css)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) && !entry.name.endsWith('.d.ts')
      ? [path] : []
  })
}

/** The code without its comments (whole comment lines and blocks; a URL's // inside a string stays). */
function code(text: string): string {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').split('\n').filter((line) => !line.trim().startsWith('//')).join('\n')
}

/** QuizBuks's names in Chinese and Japanese, which only its profile says. */
const LOCAL_NAMES = [loadBrand('quizbuks').names.zh, loadBrand('quizbuks').names.ja]

describe('the SPA names no brand of its own', () => {
  const root = join(import.meta.dirname, '..', '..')
  const files = [...sources(join(root, 'src')), join(root, 'index.html')]

  it.each(files.map((file) => relative(root, file)))('%s', (file) => {
    let text = code(readFileSync(join(root, file), 'utf8'))
    for (const identifier of IDENTIFIERS) text = text.replace(identifier, '')
    expect(text.match(/.*quizbuks.*/gi) ?? []).toEqual([])
    // Nor its name in another language (Q61): texts take BRAND.names.
    expect(LOCAL_NAMES.filter((name) => text.includes(name))).toEqual([])
  })
})
