import { describe, expect, it } from 'vitest'
import { draftPreview } from './model'
import { previewOf, previewOfPublication, previewOfQuiz } from './paths'
import type { Draft } from '../content/api'

const DRAFT = {
  quiz: { quizId: 'q1', title: 'Coffee basics', kind: 'QUIZ', intro: 'About beans', cover: 'f1', timeLimitSec: 120,
    status: 'DRAFT', latestVersionNo: 0, versionLabel: null, revision: 1, editedAt: '2026-10-26T00:00:00Z',
    changedSinceVersion: false, questionCount: 2, materialCount: 1, clonedFrom: null },
  questions: [
    { questionId: 'a', stem: 'One?', image: null, points: 2, selection: null,
      options: [{ optionId: 'x', text: 'Yes', image: null, correct: true }, { optionId: 'y', text: 'No', image: null, correct: false }] },
    { questionId: 'b', stem: 'Two?', image: null, points: 3, selection: null, options: [] },
  ],
  materials: [{ materialId: 'm', kind: 'LINK', title: 'Site', description: null, body: null, url: 'https://example.com',
    pdf: null, audio: null, images: [], watchToAnswer: false }],
} as unknown as Draft

describe('the preview (Q34b)', () => {
  it('reads a draft as its page would: the quiz itself, no reward, questions without their answers', () => {
    const preview = draftPreview(DRAFT, null, new Date('2026-10-26T00:00:00Z'))
    expect(preview.draft).toBe(true)
    expect(preview.view).toMatchObject({ name: 'Coffee basics', intro: 'About beans', cover: 'f1', questionCount: 2,
      fullScore: 5, timeLimitSec: 120, rewardMethod: 'PLATFORM', canStart: false })
    expect(preview.view.materials).toEqual([{ no: 1, kind: 'LINK', title: 'Site', description: null, body: null,
      url: 'https://example.com', pdf: null, audio: null, images: [] }])
    expect(preview.questions[0].options).toEqual([{ no: 1, text: 'Yes', image: null }, { no: 2, text: 'No', image: null }])
    expect(JSON.stringify(preview.questions)).not.toContain('correct')
  })

  it('has its own addresses', () => {
    expect(previewOf(previewOfPublication('p 1'))).toEqual({ kind: 'publication', id: 'p 1' })
    expect(previewOf(previewOfQuiz('q1'))).toEqual({ kind: 'quiz', id: 'q1' })
    expect(previewOf('/publications/p1')).toBeNull()
  })
})
