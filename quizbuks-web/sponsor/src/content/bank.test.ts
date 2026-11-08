import { describe, expect, it } from 'vitest'
import { leftOutOf, splitQuestions } from './bank'

const question = (n: number, image: string | null = null, optionImage: string | null = null) => ({
  stem: `Q${n}`, image, points: 1, selection: null,
  options: [{ text: 'A', image: optionImage, correct: true }, { text: 'B', image: null, correct: false }] })

describe('splitting a quiz file into import batches (F-S6b)', () => {
  const file = {
    format: 'quizbuks.quiz', formatVersion: 3, exportedAt: 'x',
    quiz: { kind: 'QUIZ', title: 'T', intro: 'I', timeLimitSec: null, cover: 'f0', language: 'ja' },
    materials: [{ kind: 'ARTICLE', title: 'M' }],
    questions: [question(1, 'f1'), question(2), question(3, null, 'f3'), question(4), question(5)],
    files: { f0: { data: 'c' }, f1: { data: 'a' }, f3: { data: 'b' }, f9: { data: 'z' } },
    translations: {
      en: { texts: { title: 'T', 'q1.stem': 'E1', 'q3.o1.text': 'A3', 'q4.stem': 'E4', 'q5.stem': 'E5' },
        images: { cover: 'f9', 'q4.image': 'f9' }, materials: [{ no: 1 }], hashes: { 'q4.stem': 'h4', title: 'h' } },
    },
    products: { quiz: [], questions: [{ no: 4, product: { code: 'P' } }] },
  }

  it('cuts the questions into batches of the size, each numbered from 1 with its translations renumbered', () => {
    const batches = splitQuestions(file, 2)
    expect(batches.map((b) => (b.questions as { stem: string }[]).map((q) => q.stem)))
      .toEqual([['Q1', 'Q2'], ['Q3', 'Q4'], ['Q5']])
    expect(batches[1].translations).toEqual({ en: { texts: { 'q1.o1.text': 'A3', 'q2.stem': 'E4' },
      images: { 'q2.image': 'f9' }, materials: [], hashes: { 'q2.stem': 'h4' } } })
    expect(batches[2].translations).toEqual({ en: { texts: { 'q1.stem': 'E5' }, images: {}, materials: [],
      hashes: {} } })
  })

  it('keeps the heading without its cover, no materials or products, and only the files its questions use', () => {
    const [first, second, third] = splitQuestions(file, 2)
    expect(first.quiz).toEqual({ ...file.quiz, cover: null })
    expect(first.materials).toEqual([])
    expect(first).not.toHaveProperty('products')
    expect(first.format).toBe('quizbuks.quiz')
    expect(first.formatVersion).toBe(3)
    expect(Object.keys(first.files as object)).toEqual(['f1'])
    expect(Object.keys(second.files as object).sort()).toEqual(['f3', 'f9'])
    expect(third.files).toEqual({})
  })

  it('makes one batch of a small file and none of an empty one', () => {
    expect(splitQuestions(file, 200)).toHaveLength(1)
    expect(splitQuestions({ ...file, questions: [] }, 2)).toEqual([])
  })

  it('says what an import into a quiz leaves out', () => {
    expect(leftOutOf(file)).toEqual({ materials: 2, products: true })
    expect(leftOutOf({ questions: [] })).toEqual({ materials: 0, products: false })
  })
})
