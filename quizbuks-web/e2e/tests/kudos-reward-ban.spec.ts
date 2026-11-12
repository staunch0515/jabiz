import { completeQuiz, query } from './content'
import { allowRewardMethods } from './publishing'
import { expect, prepareQuizbuks, run, setControlledParam, test } from './support'
import { approvedSponsor } from './wallet'

/**
 * F-F10a: while the switch `qb.reward.kudos.enabled` is off (the first-launch setting), a publication paid in the
 * platform's Kudos is refused when it is submitted, with the server's message in the Sponsor's language; the same
 * publication goes through when the switch is on again. The switch is global, so the test turns it off and restores it
 * in `finally`; the suite runs with one worker, so no other test reads it meanwhile.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test.describe.configure({ mode: 'serial' })

test('a publication with a Kudos reward is refused at submit while the switch is off', async ({ request }) => {
  test.setTimeout(240_000)
  const sponsor = await approvedSponsor(request, 'Kudos')
  await allowRewardMethods(request, sponsor.token, ['PLATFORM', 'SPONSOR_CASH'])
  const quiz = await completeQuiz(request, sponsor.token, { questions: 1 })
  const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0].versionId
  const now = Date.now()
  // Saved while the switch is on (its declared value in the tests).
  const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: `Kudos ban ${now}`,
    regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(), endAt: new Date(now + 7 * 86_400_000).toISOString(),
    timeLimitSec: 300, passScore: 1, rewardMethod: 'PLATFORM',
    tiers: [{ fromScore: 0, toScore: 0, reward: 0 }, { fromScore: 1, toScore: 1, reward: 300 }] })
  const publicationId = saved.publicationId as string

  await setControlledParam(request, 'qb.reward.kudos.enabled', 'false')
  try {
    const refused = await request.post('/api/processes/QB_PUBLICATION_SUBMIT/latest', {
      headers: { Authorization: `Bearer ${sponsor.token}`, 'Idempotency-Key': crypto.randomUUID() },
      data: { publicationId } })
    expect(refused.status(), await refused.text()).toBe(422)
    const body = await refused.json()
    expect(JSON.stringify(body)).toContain('QB_REWARD_METHOD_NOT_ALLOWED')
    expect(JSON.stringify(body)).toContain('cannot be used to publish until it is opened')
    // The Sponsor is offered the other methods only.
    const methods = await query(request, sponsor.token, 'qb.sponsor.reward-methods', {})
    expect(String(methods[0].methods).split(',')).not.toContain('PLATFORM')
  } finally {
    await setControlledParam(request, 'qb.reward.kudos.enabled', 'true')
  }
})
