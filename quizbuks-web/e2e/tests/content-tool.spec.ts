import { execFile } from 'node:child_process'
import { mkdtemp, readFile, readdir } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { completeQuiz, query } from './content'
import { fundedSponsor, taker, untilLive } from './play'
import { decideReview, livePublication } from './publishing'
import { expect, PASSWORD, prepareQuizbuks, rows, test, unique } from './support'

/*
 * The content tool (docs/quizbuks/plans/Q36-content-pack.md, tools/quizbuks/content): a sponsor's quizzes and
 * publications kept as a content pack and written into another sponsor's account through the API, as the tool is
 * used to move real content from a test system into the real one.
 */
const run = promisify(execFile)
const TOOL = fileURLToPath(new URL('../../../tools/quizbuks/content/qbcontent.mjs', import.meta.url))
const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:8080'
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025'
const PUBLICATIONS = 'urn:jabiz:dataset:sponsor:QbPublication'

async function tool(...args: string[]) {
  const { stdout } = await run(process.execPath, [TOOL, ...args], { env: { ...process.env, QB_PASSWORD: PASSWORD },
    timeout: 120_000 })
  return stdout
}

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('the content tool exports a sponsor\'s content and applies it to another account, once', async ({ request }) => {
  test.setTimeout(300_000)
  const first = await fundedSponsor(request)
  const quiz = await completeQuiz(request, first.token, { title: unique('Pack quiz ') })
  const name = unique('Pack publication ')
  await livePublication(request, first.token, quiz.quizId, name)

  // Export: one quiz file with its cover's bytes, and the publication as a length in days.
  const pack = await mkdtemp(join(tmpdir(), 'qb-pack-'))
  await tool('export', '--base', BASE, '--user', first.email, '--pack', pack)
  const content = JSON.parse(await readFile(join(pack, 'content.json'), 'utf8'))
  expect(content.format).toBe('quizbuks.content')
  const spec = content.publications.find((p: { name: string }) => p.name === name)
  expect(spec).toMatchObject({ durationDays: 7, regions: ['GLOBAL'], rewardMethod: 'PLATFORM' })
  const quizFile = JSON.parse(await readFile(join(pack, spec.quiz), 'utf8'))
  expect(quizFile.quiz.title).toBe(quiz.title)
  expect(quizFile.files.f1.data.length).toBeGreaterThan(0)
  expect((await readdir(join(pack, 'quizzes'))).length).toBeGreaterThanOrEqual(1)

  // Apply to another sponsor: the quiz is imported and versioned, the publication submitted.
  const second = await fundedSponsor(request)
  expect(await tool('apply', '--base', BASE, '--user', second.email, '--pack', pack)).toContain(`published ${name}`)
  const quizzes = await query(request, second.token, 'qb.sponsor.quizzes', {})
  expect(quizzes.map((q) => q.title)).toContain(quiz.title)
  const made = (await rows(request, second.token, PUBLICATIONS, [])).filter((r) => r.attributes.name === name)
  expect(made).toHaveLength(1)
  await decideReview(request, second.token, made[0].id, 'APPROVE')

  // Again: nothing new.
  expect(await tool('apply', '--base', BASE, '--user', second.email, '--pack', pack)).toContain(`skipped   ${name}`)
  expect((await rows(request, second.token, PUBLICATIONS, [])).filter((r) => r.attributes.name === name)).toHaveLength(1)

  // Renew within eight days of a week-long run: the next period starts when this one ends.
  expect(await tool('renew', '--base', BASE, '--user', second.email, '--pack', pack, '--renew-days', '8'))
    .toContain(`renewed ${name}`)
  const both = (await rows(request, second.token, PUBLICATIONS, [])).filter((r) => r.attributes.name === name)
    .map((r) => r.attributes).sort((a, b) => String(a.startAt).localeCompare(String(b.startAt)))
  expect(both).toHaveLength(2)
  expect(Date.parse(String(both[1].startAt))).toBe(Date.parse(String(both[0].endAt)))

  // Invented takers (a test system only) answer what is live of the pack.
  const someone = await taker(request, 'Probe')
  await untilLive(request, someone.token, made[0].id)
  expect(await tool('takers', '--base', BASE, '--count', '1', '--mailpit', MAILPIT, '--pack', pack))
    .toMatch(/answered [12]/)
})

test('the example pack (a year-long welcome survey) applies to a funded sponsor', async ({ request }) => {
  test.setTimeout(180_000)
  const example = fileURLToPath(new URL('../../../tools/quizbuks/content/example', import.meta.url))
  const sponsor = await fundedSponsor(request)
  expect(await tool('apply', '--base', BASE, '--user', sponsor.email, '--pack', example, '--dry-run'))
    .toContain('Welcome survey')
  expect(await tool('apply', '--base', BASE, '--user', sponsor.email, '--pack', example))
    .toContain('published Welcome survey')
  const [made] = (await rows(request, sponsor.token, PUBLICATIONS, []))
    .filter((r) => r.attributes.name === 'Welcome survey')
  const days = (Date.parse(String(made.attributes.endAt)) - Date.parse(String(made.attributes.startAt))) / 86_400_000
  expect(days).toBe(366)
})
