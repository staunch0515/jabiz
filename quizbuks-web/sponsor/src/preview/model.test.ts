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
  it('puts the video the taker learns from on the home\'s card, as the server does (Q63)', () => {
    const at = new Date('2026-10-26T00:00:00Z')
    const video = (url: string, watchToAnswer: boolean) => ({ materialId: url, kind: 'VIDEO_LINK', title: 'V',
      description: null, body: null, url, pdf: null, audio: null, images: [], watchToAnswer })
    const withMaterials = (materials: unknown[]) => ({ ...DRAFT, materials }) as unknown as Draft
    // No video to watch: the first the app plays, past one it cannot.
    expect(draftPreview(withMaterials([video('https://video.example/a', false), video('https://vimeo.com/76979871', false)]),
      null, at).video).toBe('https://player.vimeo.com/video/76979871')
    // The video to watch comes first.
    expect(draftPreview(withMaterials([video('https://vimeo.com/76979871', false),
      video('https://youtu.be/dQw4w9WgXcQ', true)]), null, at).video)
      .toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
    expect(draftPreview(DRAFT, null, at).video).toBeNull()
  })

  it('reads a draft as its page would: the quiz itself, no reward, questions without their answers', () => {
    const preview = draftPreview(DRAFT, null, new Date('2026-10-26T00:00:00Z'))
    expect(preview.draft).toBe(true)
    expect(preview.view).toMatchObject({ name: 'Coffee basics', intro: 'About beans', cover: 'f1', questionCount: 2,
      fullScore: 5, timeLimitSec: 120, rewardMethod: 'PLATFORM', canStart: false })
    expect(preview.view.materials).toEqual([{ no: 1, kind: 'LINK', title: 'Site', description: null, body: null,
      url: 'https://example.com', pdf: null, audio: null, images: [], watchToAnswer: false }])
    expect(preview.questions[0].options).toEqual([{ no: 1, text: 'Yes', image: null }, { no: 2, text: 'No', image: null }])
    expect(JSON.stringify(preview.questions)).not.toContain('correct')
    // Single or multiple choice, as the taker will be told (D-Q5-2); none while no option is correct.
    expect(preview.questions.map((q) => q.choice)).toEqual(['SINGLE', null])
  })

  it('shows the sponsor only once approved, and a watch-to-answer video as the home card\'s video', () => {
    const approved = { contactName: 'Sara', company: 'Acme', intro: 'Hi', avatar: null, reviewStatus: 'APPROVED' }
    const pending = { ...approved, reviewStatus: 'PENDING' }
    expect(draftPreview(DRAFT, approved as never).view.sponsorCompany).toBe('Acme')
    expect(draftPreview(DRAFT, pending as never).view).toMatchObject({ sponsorCompany: null, sponsorName: null,
      sponsorIntro: null })
    expect(draftPreview(DRAFT, null).video).toBeNull()
    const withVideo = { ...DRAFT, materials: [{ ...DRAFT.materials[0], kind: 'VIDEO_LINK',
      url: 'https://youtu.be/dQw4w9WgXcQ', watchToAnswer: true }] } as unknown as Draft
    expect(draftPreview(withVideo, null).video).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
  })

  it('has its own addresses', () => {
    expect(previewOf(previewOfPublication('p 1'))).toEqual({ kind: 'publication', id: 'p 1' })
    expect(previewOf(previewOfQuiz('q1'))).toEqual({ kind: 'quiz', id: 'q1' })
    expect(previewOf('/publications/p1')).toBeNull()
  })
})
