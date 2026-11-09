import { describe, expect, it } from 'vitest'
import { draftPreview, publicationPreview } from './model'
import { previewOf, previewOfPublication, previewOfQuiz } from './paths'
import type { Draft, Snapshot } from '../content/api'
import { toPublication } from '../publishing/api'

const DRAFT = {
  quiz: { quizId: 'q1', title: 'Coffee basics', kind: 'QUIZ', intro: 'About beans', cover: 'f1', timeLimitSec: 120,
    status: 'DRAFT', latestVersionNo: 0, versionLabel: null, revision: 1, editedAt: '2026-10-26T00:00:00Z',
    changedSinceVersion: false, questionCount: 2, materialCount: 1, clonedFrom: null, translationLanguages: [] },
  questions: [
    { questionId: 'a', stem: 'One?', image: null, points: 2, selection: null,
      options: [{ optionId: 'x', text: 'Yes', image: null, correct: true }, { optionId: 'y', text: 'No', image: null, correct: false }] },
    { questionId: 'b', stem: 'Two?', image: null, points: 3, selection: null, options: [] },
  ],
  materials: [{ materialId: 'm', kind: 'LINK', title: 'Site', description: null, body: null, url: 'https://example.com',
    pdf: null, audio: null, images: [], watchToAnswer: false, variantOf: null, language: null }],
} as unknown as Draft

describe('the preview (Q34b)', () => {
  it('puts the video the Taker learns from on the home\'s card, as the server does (Q63)', () => {
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
    // F-S15: a video file is taken as a site's video is; a page of another site only when there is nothing else.
    expect(draftPreview(withMaterials([video('https://video.example/a', false), video('https://cdn.example.com/a.mp4', false)]),
      null, at).video).toBe('https://cdn.example.com/a.mp4')
    expect(draftPreview(withMaterials([video('http://cdn.example.com/a.mp4', false), video('https://video.example/a', false)]),
      null, at).video).toBe('https://video.example/a')
    expect(draftPreview(DRAFT, null, at).video).toBeNull()
  })

  it('reads a draft as its page would: the quiz itself, no reward, questions without their answers', () => {
    const preview = draftPreview(DRAFT, null, new Date('2026-10-26T00:00:00Z'))
    expect(preview.draft).toBe(true)
    expect(preview.view).toMatchObject({ name: 'Coffee basics', intro: 'About beans', cover: 'f1', questionCount: 2,
      fullScore: 5, timeLimitSec: 120, rewardMethod: 'PLATFORM', canStart: false })
    expect(preview.view.materials).toEqual([{ no: 1, kind: 'LINK', title: 'Site', description: null, body: null,
      url: 'https://example.com', pdf: null, audio: null, images: [], watchToAnswer: false, durationSec: null }])
    expect(preview.questions[0].options).toEqual([{ no: 1, text: 'Yes', image: null }, { no: 2, text: 'No', image: null }])
    expect(JSON.stringify(preview.questions)).not.toContain('correct')
    // Single or multiple choice, as the taker will be told (D-Q5-2); none while no option is correct.
    expect(preview.questions.map((q) => q.choice)).toEqual(['SINGLE', null])
  })

  it('shows the Sponsor only once approved, and a watch-to-answer video as the home card\'s video', () => {
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

  it('says when the Takers get the questions in an order of their own (F-S7)', () => {
    // A draft read without the setting shuffles (A2-14); a quiz set to the fixed order does not; a survey never.
    expect(draftPreview(DRAFT, null).shuffled).toBe(true)
    expect(draftPreview({ ...DRAFT, quiz: { ...DRAFT.quiz, shuffleQuestions: false } }, null).shuffled).toBe(false)
    expect(draftPreview({ ...DRAFT, quiz: { ...DRAFT.quiz, kind: 'SURVEY' } }, null).shuffled).toBe(false)
    const snapshot = { title: 'Coffee basics', intro: null, cover: null, timeLimitSec: 120, materials: [],
      questions: [] } as unknown as Snapshot
    const row = toPublication({ publicationId: 'p1', name: 'Autumn', status: 'LIVE', revision: 1, editedAt: '' })
    expect(publicationPreview(row, [], snapshot, null).shuffled).toBe(true)
    expect(publicationPreview(row, [], { ...snapshot, questionOrder: 'fixed' }, null).shuffled).toBe(false)
    expect(publicationPreview(row, [], { ...snapshot, kind: 'SURVEY' }, null).shuffled).toBe(false)
  })

  it('shows a publication with its own cover, else its version\'s (Q64)', () => {
    const snapshot = { title: 'Coffee basics', intro: null, cover: 'quiz-cover', timeLimitSec: 120, kind: 'QUIZ',
      materials: [], questions: [] } as unknown as Snapshot
    const row = { publicationId: 'p1', name: 'Autumn', status: 'LIVE', revision: 1, editedAt: '2026-10-26T00:00:00Z' }
    expect(publicationPreview(toPublication({ ...row, cover: 'own-cover' }), [], snapshot, null).view.cover)
      .toBe('own-cover')
    expect(publicationPreview(toPublication(row), [], snapshot, null).view.cover).toBe('quiz-cover')
    expect(publicationPreview(toPublication(row), [], null, null).view.cover).toBeNull()
  })

  describe('F-S12: the publication\'s shared video, chosen as the server\'s card chooses it', () => {
    const row = { publicationId: 'p1', name: 'Autumn', status: 'DRAFT', revision: 1, editedAt: '2026-10-26T00:00:00Z',
      sharedVideoId: 'sv1' }
    const material = (no: number, url: string, extra: Record<string, unknown> = {}) => ({ no, kind: 'VIDEO_LINK',
      title: 'V', description: null, body: null, url, pdf: null, audio: null, images: [], ...extra })
    const snapshotWith = (materials: unknown[]) => ({ title: 'Coffee', intro: null, cover: null, timeLimitSec: 60,
      kind: 'QUIZ', materials, questions: [] }) as unknown as Snapshot
    const horizontal = { url: 'https://vimeo.com/76979871', vertical: false }
    const preview = (materials: unknown[], shared: { url: string; vertical: boolean } | null, own = row) =>
      publicationPreview(toPublication(own), [], snapshotWith(materials), null, new Date('2026-10-26T00:00:00Z'), shared)

    it('takes the card when no material\'s video must be watched, before the materials\' videos', () => {
      const p = preview([material(1, 'https://youtu.be/dQw4w9WgXcQ')], horizontal)
      expect(p.video).toBe('https://player.vimeo.com/video/76979871')
      // The start page's screen leads with it too (`leadVideo`), as the App's does.
      expect(p.view).toMatchObject({ watchRequired: false, videoDurationSec: null,
        video: 'https://player.vimeo.com/video/76979871' })
      expect(p.videoVertical).toBe(false)
      // Vertical as the provider shared it, or a Shorts link (SharedVideoRules.vertical).
      expect(preview([], { ...horizontal, vertical: true }).videoVertical).toBe(true)
      const shorts = preview([], { url: 'https://www.youtube.com/shorts/dQw4w9WgXcQ', vertical: false })
      expect(shorts.video).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
      expect(shorts.videoVertical).toBe(true)
    })

    it('gives way to a material\'s video to watch before answering (Q75)', () => {
      const p = preview([material(1, 'https://vimeo.com/1'),
        material(2, 'https://youtu.be/dQw4w9WgXcQ', { watchToAnswer: true, durationSec: 90 })], horizontal)
      expect(p.video).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
      expect(p.view).toMatchObject({ watchRequired: true, videoDurationSec: 90 })
      expect(p.videoVertical).toBe(false)
      // A video to watch that the App cannot follow (not YouTube or Vimeo) does not keep the card from the shared one.
      expect(preview([material(1, 'https://www.bilibili.com/video/BV1xx411c7mD', { watchToAnswer: true })], horizontal)
        .video).toBe('https://player.vimeo.com/video/76979871')
    })

    it('leaves the card as before without one, or with one the App does not play', () => {
      const materials = [material(1, 'https://video.example/a'), material(2, 'https://youtu.be/dQw4w9WgXcQ',
        { vertical: true })]
      for (const p of [preview(materials, null), preview(materials, { url: 'http://video.example/b.mp4', vertical: true }),
        // The catalog's row is only the publication's while it names it.
        preview(materials, horizontal, { ...row, sharedVideoId: null as never })]) {
        expect(p.video).toBe('https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ')
        expect(p.view.watchRequired).toBe(false)
        expect(p.videoVertical).toBe(true)
      }
      expect(preview([], null).video).toBeNull()
      expect(preview([], null).videoVertical).toBe(false)
    })
  })

  it('has its own addresses', () => {
    expect(previewOf(previewOfPublication('p 1'))).toEqual({ kind: 'publication', id: 'p 1' })
    expect(previewOf(previewOfQuiz('q1'))).toEqual({ kind: 'quiz', id: 'q1' })
    expect(previewOf('/publications/p1')).toBeNull()
  })
})
