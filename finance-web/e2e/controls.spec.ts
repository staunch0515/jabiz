import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'
import { ACCOUNTANT, CONTROLLER, prepareCloseYear, run, signIn, token, unique } from './books'

/**
 * The audit evidence package in the browser (ROADMAP F10c; FIN-CT-012). The accountant posts a fee of its own in July
 * 2028, a month no other test posts in; the controller enters the auditor's request for July's manual entries above
 * 200.00, issues it, finds the fee in the issued report, downloads the package and checks it as the auditor would,
 * with tools/finance/verify-package.py and the SHA-256 the page shows. The tables only grow, so it runs again on the
 * same database.
 */
test('an audit request is issued as a package the auditor checks offline', async ({ page, request }, info) => {
  // From the tests' directory (finance-web/e2e), wherever they are run from.
  const verify = resolve(info.project.testDir, '../../tools/finance/verify-package.py')
  await prepareCloseYear(request)
  const accountant = await token(request, ACCOUNTANT)
  const description = unique('E2E audited fee')
  const saved = await run(request, accountant, 'FIN_JOURNAL_SAVE', { postingDate: '2028-07-14', description,
    lines: [{ accountCode: '6400', debit: '234.56', memo: description }, { accountCode: '2100', credit: '234.56' }] })
  expect(saved.status, JSON.stringify(saved.body)).toBe(200)
  const submitted = await run(request, accountant, 'FIN_JOURNAL_SUBMIT', { journalId: saved.body.output.journalId })
  expect(submitted.status, JSON.stringify(submitted.body)).toBe(200)
  expect(submitted.body.output.status).toBe('POSTED')
  const journalNo = submitted.body.output.journalNo as string

  await signIn(page, CONTROLLER)

  await test.step('the controller issues the request from the controls menu', async () => {
    await page.locator('.ant-menu-submenu-title').filter({ hasText: /^Controls and audit$/ }).click()
    await page.getByRole('link', { name: 'Audit evidence package' }).click()
    await expect(page.getByTestId('page-title')).toHaveText('Audit evidence package')
    await page.getByTestId('field-request').fill(`Manual entries above 200.00 in July 2028 (${description})`)
    await page.getByTestId('field-from').fill('2028-07-01')
    await page.getByTestId('field-to').fill('2028-07-31')
    await page.getByTestId('field-minAmount').fill('200')
    await page.getByTestId('issue').click()
    await expect(page.getByTestId('reports')).toContainText('Manual entries with approvals')
  })

  await test.step('the issued report holds the fee', async () => {
    const cell = page.getByTestId('reports').locator('[data-testid^="run-"]').first()
    const issued = (await cell.getAttribute('data-testid'))!.slice('run-'.length)
    const csv = await request.get(`/api/reports/runs/${issued}/export?format=csv`, {
      headers: { Authorization: `Bearer ${await token(request, CONTROLLER)}` },
    })
    expect(csv.status()).toBe(200)
    expect(await csv.text()).toContain(journalNo)
  })

  await test.step('the package downloads and checks out offline with the SHA-256 shown', async () => {
    const download = page.waitForEvent('download')
    await page.getByTestId('download').click()
    const file = info.outputPath('package.zip')
    await (await download).saveAs(file)
    const shown = (await page.getByTestId('package-sha256').innerText()).trim()
    expect(createHash('sha256').update(readFileSync(file)).digest('hex')).toBe(shown)
    await expect(page.getByTestId('verify-command')).toContainText(`--package-sha256 ${shown}`)
    // The list of the request's reports, as the page saves it for the auditor.
    const saved = page.waitForEvent('download')
    await page.getByTestId('save-answer').click()
    const answer = info.outputPath('package.json')
    await (await saved).saveAs(answer)
    const output = execFileSync('python3', [verify, file, '--expect', answer, '--package-sha256', shown],
      { encoding: 'utf-8' })
    expect(output).toMatch(/^OK /)
  })
})
