import { signInAdmin } from './admin'
import { bookingMonthOf, expectOverviewAccounts, transferInReview, transfersOf } from './finance'
import { query } from './content'
import { adminToken, expect, prepareQuizbuks, test } from './support'

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
  await expectOverviewAccounts(page, request)
  // F-F10b: the first launch's money switches, one row for each parameter of the registry (setup.FirstLaunchSwitches):
  // the rows from the server (the template qb.admin.first-launch-switches, read here so that a charge that turns active
  // adds its rows to the expectation by itself), and the rows the card adds (data-source="card"): those that wait for a
  // feature whose key does not exist yet and those set up by hand. Counted apart, so that a switch that moves from the
  // card's lists to the template (#684, #692) changes no number here.
  // What the active rows say depends on the system (this one proposes the declared values, other specs change some),
  // so only the states that cannot change are fixed here.
  const fromServer = (await query(request, await adminToken(request), 'qb.admin.first-launch-switches', {})).length
  await expect(page.getByTestId('fund-switches')).toBeVisible()
  await expect(page.locator('[data-testid="fund-switch"][data-source="template"]')).toHaveCount(fromServer)
  const cardRows = page.locator('[data-testid="fund-switch"][data-source="card"]')
  expect(await cardRows.count()).toBeGreaterThan(0)
  for (const state of await cardRows.evaluateAll((rows) => rows.map((r) => r.getAttribute('data-state')))) {
    expect(state).toMatch(/^(waiting|manual)$/)
  }
  await expect(page.getByTestId('fund-switch')).toHaveCount(fromServer + await cardRows.count())
  // F-M8c: the exposure fee's switch is an ordinary one, read against its launch value like the audience fee's.
  const exposure = page.locator('[data-testid="fund-switch"][data-key="qb.exposure.billing.enabled"]')
  await expect(exposure).toHaveAttribute('data-state', /^(match|differs|proposed|proposedDiffers|missing)$/)
  await expect(exposure.getByTestId('fund-switch-note').filter({ hasText: 'F-M8' })).toHaveCount(0)
  // F-M7 delivered the audience fee: its switch is an ordinary one, read against its launch value.
  const audience = page.locator('[data-testid="fund-switch"][data-key="qb.audience.billing.enabled"]')
  await expect(audience).toHaveAttribute('data-state', /^(match|differs|proposed|proposedDiffers|missing)$/)
  await expect(page.locator('[data-testid="fund-switch"][data-key="qb.fee-credit.manual.max"]'))
    .toHaveAttribute('data-state', 'manual')
  await expect(page.locator('[data-testid="fund-switch"][data-key="qb.kudos.buy.enabled"]'))
    .toHaveAttribute('data-state', /^(match|differs|proposed|proposedDiffers|missing)$/)
  await expect(page.getByTestId('fund-switches-summary')).toBeVisible()
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

  // The reports of the month the transfer's sending was booked in (not the clock's: a run may cross a month's end).
  const month = await bookingMonthOf(request, 'QbTransfer', transferId)
  await page.goto(`/admin/qb/finance/reports?month=${month}`)
  await expect(page.getByTestId('report-month')).toHaveValue(month)
  const monthly = page.getByTestId('report-monthly')
  const sent = monthly.locator('[data-testid="monthly-row"][data-category="TRANSFER_SENT"]')
  await expect(sent).toBeVisible()
  expect(Number(await sent.getAttribute('data-count'))).toBeGreaterThanOrEqual(1)
  const balances = page.getByTestId('report-balances')
  await expect(balances.locator('[data-testid="balance-row"][data-kind="CHECK"]')).toHaveCount(4)
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
