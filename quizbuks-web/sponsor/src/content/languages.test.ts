import { describe, expect, it } from 'vitest'
import { assembleDraft } from './api'
import { hashesOf, sourceHash, sourceTexts, statusOf, stemKey } from './languages'

const LIMITS = { title: 100, intro: 5000, stem: 1000, option: 300 }

describe('a quiz\'s other languages (Q73a)', () => {
  it('hashes the NFC form of a base text as the server does', async () => {
    // The same vectors as ContentLanguagesTest.
    expect(await sourceHash('Capital of France?')).toBe('221acfeb028204b9')
    expect(await sourceHash('é')).toBe(await sourceHash('é'))
    expect(await sourceHash('  ')).toBeNull()
    expect(await sourceHash(null)).toBeNull()
  })

  it('says what a language lacks: missing, out of date, incomplete materials', async () => {
    const draft = assembleDraft(
      { quizId: 'q', title: 'Capitals', intro: null, language: 'en', translationLanguages: 'zh,ja', revision: 1 },
      [{ questionId: 'k1', seq: 1, stem: 'Capital of France?', points: 1 }],
      [{ optionId: 'o1', questionId: 'k1', seq: 1, text: 'Paris', correct: true },
        { optionId: 'o2', questionId: 'k1', seq: 2, text: null, image: 'f1', correct: false }],
      [{ materialId: 'm1', seq: 1, kind: 'ARTICLE', title: 'Read', body: 'Text' },
        { materialId: 'm2', seq: 1, kind: 'ARTICLE', title: '读', body: null, variantOf: 'm1', language: 'zh' }],
      [],
      [{ translationId: 't1', language: 'zh', elementKey: 'quiz.title', text: '首都',
        sourceHash: await sourceHash('Capitals') },
      { translationId: 't2', language: 'zh', elementKey: stemKey('k1'), text: '法国的首都？',
        sourceHash: 'ffffffffffffffff' }])
    expect(draft.quiz.translationLanguages).toEqual(['ja', 'zh'])
    expect(draft.materials.map((m) => m.materialId)).toEqual(['m1'])
    expect(draft.variants.map((m) => m.materialId)).toEqual(['m2'])
    const texts = sourceTexts(draft, LIMITS)
    const hashes = await hashesOf(texts.map((t) => t.text))
    const zh = statusOf(draft, 'zh', texts, hashes)
    // The introduction is empty and the second option has an image only: nothing to translate there.
    expect(zh.missing).toEqual(['option:o1.text'])
    expect(zh.stale).toEqual([stemKey('k1')])
    expect(zh.materials).toEqual([1])
    expect(statusOf(draft, 'ja', texts, hashes).missing).toHaveLength(3)
  })
})
