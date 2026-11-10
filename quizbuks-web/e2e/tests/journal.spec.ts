import { readFile } from 'node:fs/promises'
import { accountant, codeOf, issuerSet, mapAccounts, postingLastMonth, previewOf, signInAdminAs } from './journal'
import { expect, openArea, prepareQuizbuks, signIn, test } from './support'
import { fundedSponsor } from './wallet'

/*
 * The journal export and the top-up receipts (docs/quizbuks/plans/Q9d-journal-receipts.md): an accountant previews the
 * next month, exports it once the month has ended and the reconciliation after it is clean, downloads the voucher's
 * CSV exactly as kept and records the accounting system's voucher number; a sponsor downloads the receipt of a paid
 * top-up. A database that already exported up to the current month shows why the next one waits instead.
 */

test.beforeEach(async ({ request }) => {
  await prepareQuizbuks(request)
})

test('finance exports the next month and downloads its voucher', async ({ page, request }) => {
  test.setTimeout(240_000)
  await mapAccounts(request)
  await postingLastMonth(request)
  const finance = await accountant(request)
  // The server's own verdict decides what the page must show, so the test waits for a settled one: ready, or waiting
  // because the next month has not ended. Anything else (a reconciliation that is not clean yet or is running just
  // now, a database transaction open for a moment, an account moved after the mapping) comes and goes with the work
  // the earlier tests leave running, and is not what this test is about. The reconciliation runs every minute here.
  const settled = async () => {
    let last = await previewOf(request, finance.token)
    for (let i = 0; i < 30 && !(last.ready || !last.periodEnded); i++) {
      await new Promise((resolve) => setTimeout(resolve, 5_000))
      last = await previewOf(request, finance.token)
    }
    expect(last.ready || !last.periodEnded, `a settled preview of the next export: ${JSON.stringify(last)}`).toBe(true)
    return last
  }
  const next = await settled()

  await signInAdminAs(page, finance.email)
  await page.goto('/admin/qb/finance/journal')
  const preview = page.getByTestId('journal-preview-result')
  // The page asks the server itself: ask again until it says what the settled verdict said (a flap is retried; a
  // verdict that stays different fails with what the page showed).
  await expect(async () => {
    await page.getByTestId('journal-preview').click()
    await expect(preview).toHaveAttribute('data-month', next.month)
    const shown = await page.getByTestId('journal-blockers').allInnerTexts()
    const message = `the page's verdict against the server's ${JSON.stringify({ next, shown })}`
    if (next.ready) {
      expect(await page.getByTestId('journal-blockers').count(), message).toBe(0)
    } else {
      // Exported up to the current month by an earlier run: the next month has not ended, and the page says so.
      await expect(page.getByTestId('journal-blocker-open'), message).toBeVisible({ timeout: 2_000 })
    }
  }).toPass({ timeout: 60_000, intervals: [3_000] })
  await expect(preview.getByTestId('journal-detail-size')).toHaveAttribute('data-parts', String(next.detailParts))

  if (next.ready) {
    // The voucher's lines, or none: a posting recorded within the last ten minutes waits for the next export.
    await expect(preview.getByTestId('journal-lines').or(preview.getByTestId('journal-no-lines'))).toBeVisible()
    await page.getByTestId('journal-export').click()
    await page.getByRole('alertdialog').getByRole('button', { name: 'Export' }).click()
    await expect(page.getByText(/^Exported as QBJ-\d{4}-\d{5}\.$/)).toBeVisible()
  } else {
    await expect(page.getByTestId('journal-export')).toHaveCount(0)
  }

  // The newest export: its voucher as kept, quoted throughout and mapped.
  const row = page.getByTestId('journal-export-row').first()
  await expect(row).toBeVisible()
  const voucher = await row.getAttribute('data-voucher')
  expect(voucher).toMatch(/^QBJ-\d{4}-\d{5}$/)
  const download = page.waitForEvent('download')
  await row.getByTestId('journal-download').click()
  const file = await download
  expect(file.suggestedFilename()).toBe(`${voucher}.csv`)
  const csv = (await readFile(await file.path())).toString('utf8')
  expect(csv.charCodeAt(0)).toBe(0xfeff)
  expect(csv).toContain('"voucher_no","date","line_no","side","account_code"')
  // Every line of a voucher carries its number and its account's mapped code (a month may have none).
  for (const line of csv.slice(1).split('\r\n').slice(1).filter(Boolean)) {
    expect(line.startsWith(`"${voucher}",`)).toBe(true)
    expect(line).toMatch(/,"E2E-\d{4}",/)
  }
  // The detail, in as many files as the platform's limits need (one here), each named by its part.
  const detail = page.waitForEvent('download')
  await row.getByTestId('journal-download-detail').first().click()
  expect((await detail).suggestedFilename()).toBe(`${voucher}-detail-1.csv`)

  // The accounting system's voucher number, once.
  const confirm = row.getByTestId('journal-confirm-input')
  if (await confirm.count() > 0) {
    await confirm.fill(`MF-${voucher!.slice(-5)}`)
    await row.getByTestId('journal-confirm-submit').click()
    await expect(row.getByTestId('journal-external')).toHaveText(`MF-${voucher!.slice(-5)}`)
  } else {
    await expect(row.getByTestId('journal-external')).toBeVisible()
  }

  await page.goto('/admin/qb/finance/journal/mapping')
  await expect(page.locator('[data-testid="mapping-row"][data-account="2110"]'))
    .toHaveAttribute('data-code', codeOf('2110'))
})

test('a Sponsor downloads the receipt of a paid top-up', async ({ page, request }) => {
  test.setTimeout(120_000)
  await issuerSet(request)
  const sponsor = await fundedSponsor(request, 1200)
  await signIn(page, '/sponsor/', sponsor.email)
  await expect(page).toHaveURL(/\/sponsor\/home$/)
  await openArea(page, 'money', 'wallet')
  const row = page.getByTestId('topup-row').first()
  await expect(row.getByTestId('topup-row-status')).toHaveAttribute('data-status', 'PAID')
  const topUpId = await row.getAttribute('data-topup')
  const download = page.waitForEvent('download')
  await row.getByTestId('topup-row-receipt').click()
  const file = await download
  expect(file.suggestedFilename()).toBe(`${topUpId}.pdf`)
  const pdf = await readFile(await file.path())
  expect(pdf.subarray(0, 5).toString('latin1')).toBe('%PDF-')
  // Asked again: the same receipt, byte for byte.
  const again = page.waitForEvent('download')
  await row.getByTestId('topup-row-receipt').click()
  expect(await readFile(await (await again).path())).toEqual(pdf)
  await expect(page.getByTestId('topup-receipt-problem')).toHaveCount(0)
})
