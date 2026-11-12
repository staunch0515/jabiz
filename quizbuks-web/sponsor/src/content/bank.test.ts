import { describe, expect, it } from 'vitest'
import { bytesOf, leftOutOf, planBatches, splitQuestions } from './bank'

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

describe('the batches by questions and by bytes (F-F11b)', () => {
  const big = (n: number, size: number) => ({ ...question(n), stem: `Q${n} ${'x'.repeat(size)}` })
  const file = (questions: unknown[], extra: Record<string, unknown> = {}) => ({
    format: 'quizbuks.quiz', formatVersion: 3, quiz: { kind: 'QUIZ', title: 'T', language: 'ja' },
    materials: [], questions, files: {}, ...extra })
  const stems = (batches: Record<string, unknown>[]) =>
    batches.map((b) => (b.questions as { stem: string }[]).map((q) => q.stem.split(' ')[0]))

  it('cuts by the questions alone when no byte limit is given (none, 0 or an older server)', () => {
    const document = file([1, 2, 3, 4, 5].map((n) => question(n)))
    for (const none of [undefined, null, 0]) {
      expect(stems(planBatches(document, 2, none).batches)).toEqual([['Q1', 'Q2'], ['Q3', 'Q4'], ['Q5']])
    }
    expect(planBatches(document, 2, 0).tooLarge).toEqual([])
    expect(splitQuestions(document, 2)).toEqual(planBatches(document, 2, null).batches)
  })

  it('takes as many questions as both limits allow: a batch of exactly the limit stays one, one byte more is cut', () => {
    const document = file([1, 2, 3, 4].map((n) => big(n, 100)))
    const whole = bytesOf(planBatches(document, 4, 0).batches[0])
    expect(stems(planBatches(document, 4, whole).batches)).toEqual([['Q1', 'Q2', 'Q3', 'Q4']])
    expect(stems(planBatches(document, 4, whole - 1).batches)).toEqual([['Q1', 'Q2', 'Q3'], ['Q4']])
    // The questions limit still counts when the bytes allow more.
    expect(stems(planBatches(document, 3, whole).batches)).toEqual([['Q1', 'Q2', 'Q3'], ['Q4']])
    // Every batch is within the limit it was cut for.
    const limit = bytesOf(planBatches(document, 2, 0).batches[0])
    planBatches(document, 4, limit).batches.forEach((b) => expect(bytesOf(b)).toBeLessThanOrEqual(limit))
  })

  it('counts the other languages and the files with the questions', () => {
    const document = file([big(1, 10), big(2, 10)], {
      translations: { en: { texts: { 'q1.stem': 'e'.repeat(500), 'q2.stem': 'f'.repeat(500) }, images: {}, materials: [],
        hashes: {} } } })
    const one = bytesOf(planBatches(document, 1, 0).batches[0])
    expect(planBatches(document, 2, one + 100).batches).toHaveLength(2)
  })

  it('reports a question that alone takes more than the limit, by its number, and still cuts the rest', () => {
    const document = file([big(1, 10), big(2, 5000), big(3, 10), big(4, 10)])
    const small = bytesOf(planBatches(document, 1, 0).batches[0])
    const plan = planBatches(document, 4, small + 200)
    expect(plan.tooLarge).toEqual([2])
    expect(stems(plan.batches)).toEqual([['Q1'], ['Q2'], ['Q3', 'Q4']])
  })

  it('makes none of a file without questions', () => {
    expect(planBatches(file([]), 200, 1000)).toEqual({ batches: [], tooLarge: [], maxBytes: 1000 })
  })
})
