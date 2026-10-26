// node --test tools/quizbuks/content/lib.test.mjs
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  MAX_RUNNING_DAYS, PACK_FORMAT, PACK_VERSION, packProblems, publicationAction, publicationSpecOf, quizFileName,
  quizFileOf, saveInputOf, withoutData,
} from './lib.mjs'

const DAY = 86_400_000

test('a draft becomes the console\'s quiz file, each file once, keyed in the order first used', () => {
  const { document, files } = quizFileOf({
    quiz: { title: 'Coffee', kind: 'SURVEY', intro: 'About **beans**', timeLimitSec: '120', cover: 'c' },
    questions: [{ questionId: 'q2', seq: 2, stem: 'Two?', image: 'c', points: 3, selection: 'MULTIPLE' },
      { questionId: 'q1', seq: 1, stem: 'One?', image: null, points: 2, selection: null }],
    options: [{ questionId: 'q1', seq: 2, text: 'No', image: 'o', correct: false },
      { questionId: 'q1', seq: 1, text: 'Yes', image: null, correct: 'true' }],
    materials: [{ materialId: 'm', seq: 1, kind: 'IMAGES', title: 'Pics', description: null, body: null, url: null,
      pdf: null, audio: null, watchToAnswer: false }],
    images: [{ materialId: 'm', seq: 1, image: 'o', caption: 'A bean' }],
  }, '2026-10-26T00:00:00Z')
  assert.equal(document.format, 'quizbuks.quiz')
  assert.deepEqual(document.quiz, { kind: 'SURVEY', title: 'Coffee', intro: 'About **beans**', timeLimitSec: 120, cover: 'f1' })
  assert.deepEqual(document.questions.map((q) => q.stem), ['One?', 'Two?'])
  assert.deepEqual(document.questions[0].options, [{ text: 'Yes', image: null, correct: true },
    { text: 'No', image: 'f2', correct: false }])
  assert.equal(document.questions[1].image, 'f1')
  assert.deepEqual(document.materials[0].images, [{ image: 'f2', caption: 'A bean' }])
  assert.equal('watchToAnswer' in document.materials[0], false)
  assert.deepEqual(files, [{ fileId: 'c', key: 'f1', kind: 'image' }, { fileId: 'o', key: 'f2', kind: 'image' }])
})

test('file names and the document the server checks', () => {
  assert.equal(quizFileName('Café / 珈琲 101'), 'Café-珈琲-101.quizbuks.json')
  assert.equal(quizFileName('///'), 'quiz.quizbuks.json')
  assert.deepEqual(withoutData({ files: { f1: { name: 'image.png', data: 'AAAA' } } }), { files: { f1: { name: 'image.png' } } })
})

test('a publication keeps what the sponsor set, as a length from the day it is applied, never secrets', () => {
  const spec = publicationSpecOf({ name: 'Week', startAt: '2026-01-01T00:00:00Z', endAt: '2026-01-15T00:00:00Z',
    regions: 'GLOBAL,JP', audience: 'PUBLIC', timeLimitSec: 300, passScore: 2, budgetCap: null, maxWinners: 10,
    rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED', digitalDescription: '10% off', digitalSendDays: null,
    status: 'LIVE', publicationId: 'p1' },
  [{ seq: 2, fromScore: 2, toScore: 2, reward: '100' }, { seq: 1, fromScore: 0, toScore: 1, reward: 0 }], 'quizzes/q.json')
  assert.equal(spec.durationDays, 14)
  assert.deepEqual(spec.regions, ['GLOBAL', 'JP'])
  assert.deepEqual(spec.tiers, [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward: 100 }])
  assert.equal(spec.digitalDelivery, 'SHARED')
  for (const k of ['status', 'publicationId', 'digitalContent']) assert.equal(k in spec, false)
  // A year and a day at most (PublishingLimits.MAX_RUNNING).
  assert.equal(publicationSpecOf({ name: 'Long', startAt: '2026-01-01T00:00:00Z', endAt: '2028-01-01T00:00:00Z' }, [], 'q').durationDays,
    MAX_RUNNING_DAYS)
})

test('a pack is checked before anything is written', () => {
  const ok = { format: PACK_FORMAT, formatVersion: PACK_VERSION, publications: [{ name: 'A', quiz: 'quizzes/a.json', durationDays: 365 }] }
  assert.deepEqual(packProblems(ok, ['quizzes/a.json']), [])
  assert.match(packProblems({ format: 'x' }, [])[0], /not a content pack/)
  const bad = { ...ok, publications: [{ name: 'A', quiz: 'quizzes/b.json', durationDays: 400 }, { name: 'A', quiz: 'quizzes/a.json',
    durationDays: 3, rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED' }] }
  const problems = packProblems(bad, ['quizzes/a.json'])
  assert.ok(problems.some((p) => p.includes('no quiz file')))
  assert.ok(problems.some((p) => p.includes('durationDays')))
  assert.ok(problems.some((p) => p.includes('two publications')))
  assert.ok(problems.some((p) => p.includes('digitalContentEnv')))
})

test('the save input starts now and runs the days asked; a shared prize\'s content from the environment', () => {
  const now = new Date('2026-10-26T00:00:00Z')
  const input = saveInputOf({ name: 'A', durationDays: 10, tiers: [{ fromScore: 0, toScore: 1, reward: 0 }],
    rewardMethod: 'DIGITAL_PRIZE', digitalDelivery: 'SHARED', digitalContentEnv: 'CODE' }, 'v1', now, { CODE: 'SAVE10' })
  assert.equal(input.startAt, '2026-10-26T00:00:00.000Z')
  assert.equal(Date.parse(input.endAt) - Date.parse(input.startAt), 10 * DAY)
  assert.deepEqual(input.tiers, [{ tierId: null, fromScore: 0, toScore: 1, reward: 0 }])
  assert.equal(input.digitalContent, 'SAVE10')
  assert.equal(input.versionId, 'v1')
  assert.equal(saveInputOf({ name: 'B', durationDays: 1, rewardMethod: 'OFFLINE_REDEEM' }, 'v', now).digitalContent, undefined)
})

test('apply makes a publication only when none of that name runs; renew one that ends soon, after it', () => {
  const now = new Date('2026-10-26T00:00:00Z')
  assert.deepEqual(publicationAction([], { now }), { action: 'create' })
  assert.deepEqual(publicationAction([{ status: 'ENDED' }], { now }), { action: 'create' })
  const live = { status: 'LIVE', startAt: '2026-01-01T00:00:00Z', endAt: '2026-11-10T00:00:00Z' }
  assert.deepEqual(publicationAction([live], { now }), { action: 'skip', status: 'LIVE' })
  assert.deepEqual(publicationAction([live], { now, renewDays: 30 }), { action: 'renew', after: new Date(live.endAt) })
  assert.equal(publicationAction([live], { now, renewDays: 7 }).action, 'skip')
  // Already renewed: the next period waits to start.
  const next = { status: 'SCHEDULED', startAt: '2026-11-10T00:00:00Z', endAt: '2027-11-10T00:00:00Z' }
  assert.equal(publicationAction([live, next], { now, renewDays: 30 }).action, 'skip')
})

test('the example pack is a pack this tool applies, its survey rewarding only a complete response', async () => {
  const { readFile } = await import('node:fs/promises')
  const read = async (path) => JSON.parse(await readFile(new URL(`./example/${path}`, import.meta.url), 'utf8'))
  const pack = await read('content.json')
  assert.deepEqual(packProblems(pack, ['quizzes/welcome-survey.quizbuks.json']), [])
  const survey = await read(pack.publications[0].quiz)
  assert.equal(survey.quiz.kind, 'SURVEY')
  assert.ok(survey.questions.every((q) => q.points === 1 && q.options.every((o) => !o.correct)))
  const [publication] = pack.publications
  assert.equal(publication.passScore, survey.questions.length)
  assert.ok(publication.tiers.every((t) => t.toScore < survey.questions.length ? t.reward === 0 : t.reward > 0))
})
