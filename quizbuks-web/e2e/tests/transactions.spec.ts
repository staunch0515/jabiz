import { readFile } from 'node:fs/promises'
import type { Page } from '@playwright/test'
import { expect, prepareQuizbuks, signIn, test, unique } from './support'
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

test('a sponsor reads, filters and exports their transactions', async ({ page, request }) => {
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
  await page.getByRole('link', { name: 'Wallet' }).click()
  await page.getByTestId('wallet-transactions').click()
  await expect(page).toHaveURL(/\/sponsor\/transactions$/)
  const rows = page.getByTestId('transaction-row')
  await expect(rows).toHaveCount(3)
  await expect(page.getByTestId('summary-total')).toHaveText('3')
  await expect(page.getByTestId('summary-completed')).toHaveText('1,600 Kudos (2)')
  await expect(page.getByTestId('summary-processing')).toHaveText('600 Kudos (1)')
  await expect(page.getByTestId('summary-failed')).toHaveText('0 Kudos (0)')
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
  await expect(page.getByTestId('summary-completed')).toHaveText('600 Kudos (1)')
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
  await expect(page).toHaveURL(new RegExp(`/sponsor/transactions\\?publication=${publicationId}$`))
  await expect(rows).toHaveCount(2)
  await expect(page.getByRole('combobox', { name: 'Publication' })).toContainText(name)
})

