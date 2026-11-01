import { describe, expect, it } from 'vitest'
import { AUDIO, COVER, OPTION_IMAGE, PDF, PICTURE, SNAPSHOT_JSON } from './fixtures'
import { parseSnapshot, safeHttpUrl, snapshotIn } from './snapshot'

describe('parseSnapshot', () => {
  it('reads a version as QuizSnapshots writes it', () => {
    const snapshot = parseSnapshot(SNAPSHOT_JSON)
    expect(snapshot.title).toBe('World capitals')
    expect(snapshot.cover).toBe(COVER)
    expect(snapshot.timeLimitSec).toBe(300)
    expect(snapshot.aiGenerated).toBe(false)
    expect(snapshot.materials.map((m) => m.kind)).toEqual(['ARTICLE', 'VIDEO_LINK', 'LINK', 'PDF', 'AUDIO', 'IMAGES'])
    expect(snapshot.materials[3].pdf).toBe(PDF)
    expect(snapshot.materials[4].audio).toBe(AUDIO)
    expect(snapshot.materials[5].images).toEqual([{ no: 1, image: PICTURE, caption: 'The tower' }])
    expect(snapshot.questions).toHaveLength(2)
    expect(snapshot.questions[0]).toMatchObject({ no: 1, stem: 'Capital of France?', points: 2, image: PICTURE })
    expect(snapshot.questions[0].options.map((o) => [o.text, o.correct])).toEqual([['Paris', true], ['Lyon', false]])
    expect(snapshot.questions[1].options[1]).toEqual({ no: 2, text: null, image: OPTION_IMAGE, correct: true })
  })

  it('takes the parsed object too', () => {
    expect(parseSnapshot(JSON.parse(SNAPSHOT_JSON)).questions).toHaveLength(2)
  })

  it('reads a version in its other languages (Q73a, schema 2)', () => {
    const snapshot = parseSnapshot({ schema: 2, title: 'Capitals', intro: null, materials: [
      { no: 1, kind: 'ARTICLE', title: 'Read', body: 'Text', images: [] }], questions: [
      { no: 1, stem: 'Capital of France?', image: null, points: 2, options: [
        { no: 1, text: 'Paris', image: null, correct: true }, { no: 2, text: 'Lyon', image: null, correct: false }] }],
    translations: { zh: { texts: { title: '首都', 'q1.stem': '法国的首都？', 'q1.o1.text': '巴黎' },
      images: { 'q1.image': 'f9' }, materials: [{ no: 1, kind: 'ARTICLE', title: '阅读', body: '文字', images: [] }] } } })
    expect(Object.keys(snapshot.translations)).toEqual(['zh'])
    const zh = snapshotIn(snapshot, 'zh')
    expect(zh.title).toBe('首都')
    expect(zh.questions[0]).toMatchObject({ stem: '法国的首都？', image: 'f9', points: 2 })
    expect(zh.questions[0].options.map((o) => [o.text, o.correct])).toEqual([['巴黎', true], ['Lyon', false]])
    expect(zh.materials[0].title).toBe('阅读')
    expect(snapshotIn(snapshot, 'en')).toBe(snapshot)
  })

  it('refuses anything but schemas 1 and 2', () => {
    expect(() => parseSnapshot('{"schema":3,"title":"x"}')).toThrow(/schema 1/)
    expect(() => parseSnapshot('{"title":"x"}')).toThrow(/schema 1/)
    expect(() => parseSnapshot('null')).toThrow(/schema 1/)
    expect(() => parseSnapshot('not json')).toThrow()
  })

  it('keeps an option correct only when it says so', () => {
    const snapshot = parseSnapshot({ schema: 1, title: 'T', materials: [], questions: [
      { no: 1, stem: 'Q', points: 1, options: [{ no: 1, text: 'a', correct: 'true' }, { no: 2, text: 'b' }] }] })
    expect(snapshot.questions[0].options.map((o) => o.correct)).toEqual([false, false])
  })
  it('reads a survey\'s kind and its questions\' selection, and a quiz without them (Q12)', () => {
    const survey = parseSnapshot({ schema: 1, kind: 'SURVEY', title: 'S', materials: [], questions: [
      { no: 1, stem: 'Often?', points: 1, selection: 'SINGLE', options: [{ no: 1, text: 'Yes', correct: false }] }] })
    expect(survey.kind).toBe('SURVEY')
    expect(survey.questions[0].selection).toBe('SINGLE')
    const quiz = parseSnapshot(SNAPSHOT_JSON)
    expect(quiz.kind).toBe('QUIZ')
    expect(quiz.questions.map((q) => q.selection)).toEqual([null, null])
  })

})

describe('safeHttpUrl', () => {
  it('lets only http(s) through', () => {
    expect(safeHttpUrl('https://video.example/watch?v=1')).toBe('https://video.example/watch?v=1')
    expect(safeHttpUrl('http://example.com')).toBe('http://example.com/')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,hi')).toBeNull()
    expect(safeHttpUrl('/relative')).toBeNull()
    expect(safeHttpUrl(null)).toBeNull()
  })
})
