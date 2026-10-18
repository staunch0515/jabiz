import { readFile } from 'node:fs/promises'
import type { APIRequestContext, Page } from '@playwright/test'
import { expect, prepareQuizbuks, signIn, test, unique } from './support'
import { rewardingPublication, startAttempt, submitRight } from './transactions'
import { fundedSponsor, takerInJapan } from './wallet'

/**
 * The sponsor console's analytics (docs/quizbuks/plans/Q9e-analytics.md, Q9e2; M-10, M-11, M-40 – M-43): a sponsor's
 * publication is answered right by two takers; after the analytics job's next run (every minute in these tests,
 * `QB_ANALYTICS_RUN_CRON`) the dashboard shows the spending and the publication among the top ones, its analytics
 * page the counts, the funnel and the questions held back below the minimum group, and the exported CSV the trend
 * shown. Another sponsor finds nothing of it.
 */
test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

/** A template's rows as the sponsor reads them. */
async function templateRows(request: APIRequestContext, token: string, id: string, params: Record<string, unknown>) {
  const response = await request.post(`/api/queries/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { params, limit: 50 },
  })
  expect(response.status(), `${id}: ${await response.text()}`).toBe(200)
  return ((await response.json()).items ?? []) as Record<string, unknown>[]
}

/** The lines of a downloaded CSV, without its byte order mark. */
async function downloadedCsv(page: Page, testId: string): Promise<string[]> {
  const downloading = page.waitForEvent('download')
  await page.getByTestId(testId).click()
  const download = await downloading
  expect(download.suggestedFilename()).toMatch(/\.csv$/)
  const text = await readFile(await download.path(), 'utf8')
  return text.replace(/^﻿/, '').split(/\r\n/).filter((line) => line !== '')
}

test('a sponsor reads the dashboard and a publication\'s analytics, and exports its trend', async ({ page, request }) => {
  test.setTimeout(300_000)
  const other = await fundedSponsor(request, 1000)
  const sponsor = await fundedSponsor(request, 1000)
  const mia = await takerInJapan(request, unique('Mia '))
  const noah = await takerInJapan(request, unique('Noah '))
  const { publicationId, name } = await rewardingPublication(request, sponsor.token, 100, mia.token)
  for (const taker of [mia, noah]) {
    const started = await startAttempt(request, taker.token, publicationId)
    expect(await submitRight(request, taker.token, started)).toBe('PAID')
  }

  // The job's next run puts the completions into the hourly buckets (the top publications read them).
  await expect.poll(async () => {
    const top = await templateRows(request, sponsor.token, 'qb.sponsor.analytics.top', { at: new Date().toISOString() })
    return Number(top.find((row) => row.publicationId === publicationId)?.completions7d ?? 0)
  }, { message: 'the analytics job counts the completions', timeout: 150_000, intervals: [2_000] }).toBe(2)

  // Another sponsor reads nothing of it, even by its key.
  expect(await templateRows(request, other.token, 'qb.sponsor.analytics.overview', { publicationId })).toEqual([])
  const othersTop = await templateRows(request, other.token, 'qb.sponsor.analytics.top', { at: new Date().toISOString() })
  expect(othersTop.map((row) => row.publicationId)).not.toContain(publicationId)

  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).toHaveURL(/\/sponsor\/dashboard$/)
  await expect(page.getByTestId('metric-live')).toContainText('1 Running')
  await expect(page.getByTestId('metric-answering-value')).toHaveText('0')
  await expect(page.getByTestId('metric-spent-today-value')).toHaveText('200 Kudos')
  await expect(page.getByTestId('metric-available-value')).toHaveText('800 Kudos')
  // Days are the deployment's (the zone the job recorded), not the browser's.
  await expect(page.getByTestId('analytics-updated')).toContainText('(Asia/Tokyo)')
  await expect(page.getByTestId('metric-spent-today')).toContainText('(Asia/Tokyo)')
  await expect(page.getByTestId('week')).toHaveAttribute('data-buckets', '7')
  await expect(page.getByTestId('week-completions-caption')).toHaveText('Completions by day: 2 in all')
  const top = page.getByTestId('top-row')
  await expect(top).toHaveCount(1)
  await expect(top.getByTestId('top-completions')).toHaveText('2')

  // The publication's analytics, from the top list.
  await top.getByRole('link', { name: `Analytics of ${name}` }).click()
  await expect(page).toHaveURL(new RegExp(`/sponsor/publications/${publicationId}/analytics$`))
  await expect(page.getByTestId('analytics-title')).toHaveText(`Analytics: ${name}`)
  await expect(page.getByTestId('overview-completions-value')).toHaveText('2')
  await expect(page.getByTestId('overview-starts-value')).toHaveText('2')
  await expect(page.getByTestId('overview-passes-value')).toHaveText('2')
  await expect(page.getByTestId('overview-spent-value')).toHaveText('200 Kudos')
  await expect(page.getByTestId('overview-completion-rate-value')).toHaveText('100%')
  // Two completions are fewer than the minimum group (5): the average time and the questions are held back.
  await expect(page.getByTestId('overview-avg-duration-value')).toHaveText('Hidden')
  await expect(page.getByTestId('overview-k')).toContainText('Shown once at least 5 people have completed it')
  await expect(page.getByTestId('funnel-passes')).toBeVisible()
  await expect(page.getByTestId('questions-suppressed')).toContainText('shown once at least 5 people have answered')
  await expect(page.getByTestId('question-row')).toHaveCount(1)
  await expect(page.getByTestId('question-suppressed')).toHaveText('Shown once at least 5 people have answered.')

  // The last 24 hours by the hour, then the last 7 days; each chart's data as a table.
  await expect(page.getByTestId('trend')).toHaveAttribute('data-buckets', '24')
  await expect(page.getByTestId('trend-completions-caption')).toHaveText('Completions by hour: 2 in all')
  await page.getByRole('radio', { name: 'Last 7 days (by the day)' }).click()
  await expect(page.getByTestId('trend')).toHaveAttribute('data-buckets', '7')
  await page.getByTestId('trend-table').getByText('Show the data as a table').click()
  await expect(page.getByTestId('trend-row')).toHaveCount(7)

  // The export has the trend shown: seven days, the two completions among them.
  const lines = await downloadedCsv(page, 'export-trend-csv')
  expect(lines).toHaveLength(1 + 7)
  const completions = lines[0].split(',').map((cell) => cell.replace(/"/g, '')).indexOf('Completions')
  expect(completions).toBeGreaterThan(-1)
  expect(lines.slice(1).reduce((sum, line) => sum + Number(line.split(',')[completions].replace(/"/g, '')), 0))
    .toBe(2)
  expect(lines.join('\n')).not.toContain('Mia')

  // From the publication's page too.
  await page.goto(`/sponsor/publications/${publicationId}`)
  await page.getByTestId('publication-analytics-link').click()
  await expect(page).toHaveURL(new RegExp(`/sponsor/publications/${publicationId}/analytics$`))
  await expect(page.getByTestId('overview-as-of')).toContainText('(Asia/Tokyo)')

  // Another sponsor opening the same address finds nothing, and the page asks for nothing more of it.
  await page.getByTestId('sign-out').click()
  await expect(page).toHaveURL(/\/sponsor\/login$/)
  await signIn(page, '/sponsor/', other.email)
  await expect(page).toHaveURL(/\/sponsor\/dashboard$/)
  // Only the reads of this publication: the dashboard's own trend (of all the sponsor's) may still be on its way.
  const asked: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/queries/qb.sponsor.analytics.')
      && (request.postData() ?? '').includes(publicationId)) asked.push(new URL(request.url()).pathname)
  })
  const overview = page.waitForResponse((response) =>
    response.url().endsWith('/api/queries/qb.sponsor.analytics.overview'))
  await page.goto(`/sponsor/publications/${publicationId}/analytics`)
  await overview
  await expect(page.getByText('This publication does not exist, or it is not yours.')).toBeVisible()
  await expect(page.getByTestId('analytics-overview')).toHaveCount(0)
  await expect(page.getByTestId('analytics-export')).toHaveCount(0)
  expect(asked.filter((path) => !path.endsWith('.overview'))).toEqual([])
})
