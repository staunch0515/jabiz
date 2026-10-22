import { describe, expect, it } from 'vitest'
import { AUDIO, COVER, OPTION_IMAGE, PDF, PICTURE, SNAPSHOT_JSON } from './fixtures'
import { parseSnapshot, safeHttpUrl } from './snapshot'

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

  it('refuses anything but schema 1', () => {
    expect(() => parseSnapshot('{"schema":2,"title":"x"}')).toThrow(/schema 1/)
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
