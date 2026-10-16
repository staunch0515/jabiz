import { signInAdmin } from './admin'
import { transferInReview, transfersOf } from './finance'
import { expect, prepareQuizbuks, test } from './support'

/*
 * Finance's pages in the administration (docs/quizbuks/plans/Q9c-admin-finance.md): the overview counts the large
 * transfer waiting for review and leads to it; its review shows the transfer and the payee and approves it through the
 * platform's panel, after which Stripe's stub receives it; the month's reports show the transfer, are exported through
 * the platform (CSV and PDF) and issued into the archive.
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('finance reviews a large transfer and issues the monthly report', async ({ page, request }) => {
  test.setTimeout(300_000)
  const payee = await transferInReview(request)
  const transferId = payee.transfer.transferId

  await signInAdmin(page)
  await page.goto('/admin/qb/finance')
  const inReview = page.getByTestId('finance-in-review')
  await expect(inReview).toBeVisible()
  expect(Number(await inReview.getAttribute('data-count'))).toBeGreaterThanOrEqual(1)
  await expect(page.getByTestId('finance-account')).toHaveCount(7)
  await inReview.click()
  await expect(page).toHaveURL(/\/admin\/qb\/finance\/transfers\?status=REVIEW$/)
  const row = page.locator(`[data-testid="transfer-row"][data-id="${transferId}"]`)
  await expect(row).toHaveAttribute('data-status', 'REVIEW')
  await row.getByTestId('transfer-review-link').click()

  const review = page.getByTestId('transfer-review')
  await expect(review).toHaveAttribute('data-status', 'REVIEW')
  await expect(page.getByTestId('review-amount')).toContainText('2,000')
  await expect(page.getByTestId('review-payee')).toContainText('Ren')
  await expect(page.getByTestId('review-reward')).toHaveCount(1)
  await page.getByTestId('review-decision').getByTestId('approval-approve').click()
  // Approved: the transfer is sent to the stub by its job, and the page follows.
  await expect(review).toHaveAttribute('data-status', /^(SENDING|SENT)$/, { timeout: 30_000 })
  await expect.poll(async () => (await transfersOf(request, payee.userId))[0]?.status,
    { message: 'the transfer is sent', timeout: 60_000 }).toBe('SENT')

  // This month's reports (UTC): the transfer sent and settled are in them.
  const month = new Date().toISOString().slice(0, 7)
  await page.goto('/admin/qb/finance/reports')
  await page.getByTestId('report-month').fill(month)
  const monthly = page.getByTestId('report-monthly')
  const sent = monthly.locator('[data-testid="monthly-row"][data-category="TRANSFER_SENT"]')
  await expect(sent).toBeVisible()
  expect(Number(await sent.getAttribute('data-count'))).toBeGreaterThanOrEqual(1)
  const balances = page.getByTestId('report-balances')
  await expect(balances.locator('[data-testid="balance-row"][data-kind="CHECK"]')).toHaveCount(3)
  await expect(balances.locator('[data-testid="balance-row"][data-code="BALANCED"]'))
    .toHaveAttribute('data-matches', 'true')

  const csv = page.waitForEvent('download')
  await monthly.getByTestId('export-csv').click()
  const csvFile = await csv
  expect(csvFile.suggestedFilename()).toMatch(/^qb\.admin\.monthly-\d{8}-\d{6}\.csv$/)
  const pdf = page.waitForEvent('download')
  await balances.getByTestId('export-pdf').click()
  expect((await pdf).suggestedFilename()).toMatch(/^qb\.admin\.balances-.*\.pdf$/)

  await monthly.getByTestId('report-issue').click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Issue' }).click()
  await expect(page.getByText(/^Issued \(hash [0-9a-f]{12}…\)\.$/)).toBeVisible()
  await expect(monthly.getByTestId('issued-run').first()).toBeVisible()
  await expect(monthly.getByTestId('report-archive-link')).toHaveAttribute('href', /\/admin\/reports\/archive\?template=qb\.admin\.monthly$/)
})
