import { ApiError } from '@jabiz/client'
import { describe, expect, it } from 'vitest'
import { assembleDraft, contentFieldOf, isStale, MATERIAL_KINDS, moved, safeHttpUrl } from './api'
import { quizIdOf } from './paths'

describe('the content calls', () => {
  it('put a draft together in its order, each part with its own options and images', () => {
    const draft = assembleDraft(
      { quizId: 'q', title: 'T', revision: '3', timeLimitSec: null, changedSinceVersion: 'true' },
      [{ questionId: 'b', seq: 2, points: '1' }, { questionId: 'a', seq: 1, points: 4 }],
      [{ optionId: 'y', questionId: 'a', seq: 2, correct: true }, { optionId: 'x', questionId: 'a', seq: 1 },
        { optionId: 'z', questionId: 'b', seq: 1, text: 'Z' }],
      [{ materialId: 'm', seq: 1, kind: 'IMAGES' }],
      [{ imageId: 'i2', materialId: 'm', seq: 2, image: 'f2' }, { imageId: 'i1', materialId: 'm', seq: 1, image: 'f1',
        caption: 'One' }],
    )
    expect(draft.quiz).toMatchObject({ quizId: 'q', revision: 3, timeLimitSec: null, changedSinceVersion: true })
    expect(draft.questions.map((q) => [q.questionId, q.points, q.options.map((o) => o.optionId)])).toEqual([
      ['a', 4, ['x', 'y']], ['b', 1, ['z']]])
    expect(draft.questions[0].options[1].correct).toBe(true)
    expect(draft.materials[0].images).toEqual([{ imageId: 'i1', image: 'f1', caption: 'One' },
      { imageId: 'i2', image: 'f2', caption: null }])
  })

  it('move an item one place, and nowhere past either end', () => {
    expect(moved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c'])
    expect(moved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b'])
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b'])
    expect(moved(['a', 'b'], 1, 1)).toEqual(['a', 'b'])
  })

  it('make links of http(s) addresses only', () => {
    expect(safeHttpUrl('https://example.com/a b')).toBe('https://example.com/a%20b')
    expect(safeHttpUrl('http://example.com')).toBe('http://example.com/')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,x')).toBeNull()
    expect(safeHttpUrl('not a url')).toBeNull()
    expect(safeHttpUrl(null)).toBeNull()
  })

  it('know a refusal of an out-of-date page', () => {
    const refusal = (ruleCode: string) => new ApiError(422, { violations: [{ field: null, ruleCode, message: 'x' }] })
    expect(isStale(refusal('QB_QUIZ_CHANGED'))).toBe(true)
    expect(isStale(refusal('QB_ORDER_MISMATCH'))).toBe(true)
    expect(isStale(refusal('QB_QUIZ_NO_QUESTIONS'))).toBe(false)
    expect(isStale(new Error('offline'))).toBe(false)
  })

  it('name the content field of every material kind', () => {
    expect(MATERIAL_KINDS.map(contentFieldOf)).toEqual(['body', 'url', 'url', 'pdf', 'images', 'audio'])
  })

  it('read the quiz of an editor\'s path', () => {
    expect(quizIdOf('/quizzes/abc')).toBe('abc')
    expect(quizIdOf('/quizzes/abc/')).toBe('abc')
    expect(quizIdOf('/quizzes')).toBeNull()
    expect(quizIdOf('/quizzes/a/b')).toBeNull()
  })
})
