import { readFile } from 'node:fs/promises'
import type { Page } from '@playwright/test'
import { expect, openArea, prepareQuizbuks, run, signIn, test, unique } from './support'
import { completeQuiz, query } from './content'
import { decideReview } from './publishing'
import { rewardingPublication, startAttempt, submitRight } from './transactions'
import { fundedSponsor, takerInJapan } from './wallet'

/**
 * The sponsor console's transactions (docs/quizbuks/plans/Q9b-sponsor-transactions.md; M-50 – M-52): a sponsor who
 * topped up 1,000 Kudos publishes a quiz paying 600; one taker is paid, the next waits for a top-up. The list and the
 * totals show exactly these, never another sponsor's; filtering by the publication (also from its page) and by one
 * user narrows both, and the downloaded CSV has the rows shown.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** The lines of a downloaded CSV, without its byte order mark. */
async function downloadedCsv(page: Page, testId: string): Promise<string[]> {
  const downloading = page.waitForEvent('download')
  await page.getByTestId(testId).click()
  const download = await downloading
  expect(download.suggestedFilename()).toMatch(/\.csv$/)
  const text = await readFile(await download.path(), 'utf8')
  return text.replace(/^﻿/, '').split(/\r\n/).filter((line) => line !== '')
}

test('a Sponsor reads, filters and exports their transactions', async ({ page, request }) => {
  test.setTimeout(240_000)
  // Another sponsor's top-up of a distinct amount, which must never show.
  await fundedSponsor(request, 1500)
  const sponsor = await fundedSponsor(request, 1000)
  const miaName = unique('Mia ')
  const mia = await takerInJapan(request, miaName)
  const noah = await takerInJapan(request, unique('Noah '))
  const { publicationId, name } = await rewardingPublication(request, sponsor.token, 600, mia.token)
  const miaAttempt = await startAttempt(request, mia.token, publicationId)
  const noahAttempt = await startAttempt(request, noah.token, publicationId)
  expect(await submitRight(request, mia.token, miaAttempt)).toBe('PAID')
  // 400 Kudos are left: the next 600 wait for a top-up.
  expect(await submitRight(request, noah.token, noahAttempt)).toBe('PENDING')

  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).toHaveURL(/\/sponsor\/home$/)
  await openArea(page, 'money', 'wallet')
  await page.getByTestId('wallet-transactions').click()
  await expect(page).toHaveURL(/\/sponsor\/money\/transactions$/)
  const rows = page.getByTestId('transaction-row')
  await expect(rows).toHaveCount(3)
  await expect(page.getByTestId('summary-total')).toHaveText('3')
  // Rewards (paid out) and top-ups (paid in) apart, never added together.
  await expect(page.getByTestId('summary-reward-completed')).toHaveText('600 Kudos (1)')
  await expect(page.getByTestId('summary-reward-processing')).toHaveText('600 Kudos (1)')
  await expect(page.getByTestId('summary-topup-completed')).toHaveText('1,000 Kudos (1)')
  await expect(page.getByTestId('summary-topup-refunded')).toHaveText('0 Kudos')
  await expect(page.getByTestId('summary-topup-failed')).toHaveText('0 Kudos (0)')
  await expect(rows.filter({ hasText: miaName })).toHaveAttribute('data-status', 'COMPLETED')
  await expect(rows.filter({ hasText: 'Top-up' })).toHaveAttribute('data-status', 'COMPLETED')
  await expect(page.getByTestId('transactions')).not.toContainText('1,500 Kudos')
  await expect(page.getByTestId('transactions')).not.toContainText('JPY')

  // The publication's rewards only.
  await page.getByRole('combobox', { name: 'Publication' }).click()
  await page.getByRole('option', { name }).click()
  await page.getByRole('button', { name: 'Query' }).click()
  await expect(rows).toHaveCount(2)
  await expect(page.getByTestId('summary-total')).toHaveText('2')
  await expect(rows.filter({ hasText: 'Top-up' })).toHaveCount(0)

  // Only Mia's.
  await rows.filter({ hasText: miaName }).getByTestId('transaction-only-user').click()
  await expect(page.getByTestId('transactions-user-filter')).toContainText(miaName)
  await expect(rows).toHaveCount(1)
  await expect(page.getByTestId('summary-reward-completed')).toHaveText('600 Kudos (1)')
  await expect(page.getByTestId('summary-reward-processing')).toHaveText('0 Kudos (0)')
  const mine = await downloadedCsv(page, 'transactions-export-csv')
  expect(mine).toHaveLength(1 + 1)
  expect(mine[0]).toContain('Amount (Kudos)')
  expect(mine[1]).toContain(miaName)

  // Everything again: the export has every row of this sponsor's, none of the other's.
  await page.getByTestId('transactions-user-clear').click()
  await page.getByRole('button', { name: 'Reset' }).click()
  await expect(rows).toHaveCount(3)
  const all = await downloadedCsv(page, 'transactions-export-csv')
  expect(all).toHaveLength(1 + 3)
  const amount = all[0].split(',').indexOf('Amount (Kudos)')
  expect(all.slice(1).map((line) => line.split(',')[amount]).sort()).toEqual(['1000', '600', '600'])

  // From the publication's page.
  await page.goto(`/sponsor/publications/${publicationId}`)
  await page.getByTestId('publication-transactions').click()
  await expect(page).toHaveURL(new RegExp(`/sponsor/money/transactions\\?publication=${publicationId}$`))
  await expect(rows).toHaveCount(2)
  await expect(page.getByRole('combobox', { name: 'Publication' })).toContainText(name)
})

/**
 * Q81 (docs/quizbuks/plans/Q81-question-pick.md): a publication drawing three of its version's five questions gives a
 * taker three of them, numbered 1..3, and scores them over a full score of 3; answering by the numbers shown pays the
 * top tier.
 */
test('a publication drawing three of five questions gives a Taker three and pays full marks (Q81)',
  async ({ request }) => {
    const sponsor = await fundedSponsor(request, 1000)
    const quiz = await completeQuiz(request, sponsor.token, { questions: 5 })
    const versionId = (await query(request, sponsor.token, 'qb.sponsor.quiz-versions', { quizId: quiz.quizId }))[0]
      .versionId as string
    const now = Date.now()
    const saved = await run(request, sponsor.token, 'QB_PUBLICATION_SAVE', { versionId, name: unique('Picked quiz '),
      regions: ['GLOBAL'], startAt: new Date(now - 60_000).toISOString(),
      endAt: new Date(now + 7 * 86_400_000).toISOString(), passScore: 2, pickCount: 3, budgetCap: 500,
      maxWinners: 5, tiers: [{ fromScore: 0, toScore: 1, reward: 0 }, { fromScore: 2, toScore: 2, reward: 10 },
        { fromScore: 3, toScore: 3, reward: 100 }] })
    expect(saved.fullScore).toBe(3)
    expect(saved.problems).toEqual([])
    const publicationId = saved.publicationId as string
    await run(request, sponsor.token, 'QB_PUBLICATION_SUBMIT', { publicationId })
    await decideReview(request, sponsor.token, publicationId, 'APPROVE')
    const taker = await takerInJapan(request, 'Pick')
    const started = await run(request, taker.token, 'QB_ATTEMPT_START', { publicationId })
    const questions = started.questions as { no: number; stem: string }[]
    expect(questions.map((q) => q.no)).toEqual([1, 2, 3])
    expect(new Set(questions.map((q) => q.stem)).size).toBe(3)
    const result = await run(request, taker.token, 'QB_ATTEMPT_SUBMIT', { attemptId: started.attemptId, publicationId,
      answers: (started.questions as { no: number; options: { no: number; text: string }[] }[]).map((q) => ({
        questionNo: q.no, selected: q.options.filter((o) => o.text.startsWith('Right')).map((o) => o.no) })) })
    expect(result.score).toBe(3)
    expect(result.fullScore).toBe(3)
    expect(result.rewardStatus).toBe('PAID')
    expect((result.questions as { no: number }[]).map((q) => q.no)).toEqual([1, 2, 3])
    // The result page reads the same three, numbered the same.
    const detail = await run(request, taker.token, 'QB_ATTEMPT_RESULT', { attemptId: started.attemptId })
    expect((detail.questions as { no: number }[]).map((q) => q.no)).toEqual([1, 2, 3])
  })
