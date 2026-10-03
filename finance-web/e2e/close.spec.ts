import { expect, test, type Page } from '@playwright/test'
import { ACCOUNTANT, CONTROLLER, prepareCloseYear, signIn } from './books'

/**
 * The close workspace in the browser (ROADMAP F8d; FIN-UI-006, FIN-PC-009). It works in March 2028, a year of its
 * own that no other test posts in, and never closes it, so it runs again on the same database: the accountant starts
 * the close (the checklist is made once), runs the checks and completes the accruals task; the overview shows the
 * progress with the open tasks first; the controller's close is refused and names the review not done, and the year
 * close of 2028 is refused while its months are open. The period stays open.
 */
const PERIOD = '2028-03'

test.beforeEach(async ({ request }) => {
  await prepareCloseYear(request)
})

async function choosePeriod(page: Page, periodKey: string) {
  await page.getByTestId('period-select').click()
  await page.keyboard.type(periodKey)
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(new RegExp(`period=${periodKey}`))
}

async function openWorkspace(page: Page) {
  await page.locator('.ant-menu-submenu-title').filter({ hasText: /^Close$/ }).click()
  await page.getByRole('link', { name: 'Close workspace' }).click()
  await expect(page.getByTestId('page-title')).toHaveText('Close')
}

test('a period\'s close is worked from the workspace and refused while the review is open', async ({ browser }) => {
  const accountant = await browser.newPage()
  await signIn(accountant, ACCOUNTANT)

  await test.step('the accountant starts the close and runs the checks', async () => {
    await openWorkspace(accountant)
    await choosePeriod(accountant, PERIOD)
    await accountant.getByTestId('close-start').click()
    await expect(accountant.getByTestId('close-notice')).toContainText(`The close of ${PERIOD} is started`)
    await accountant.getByTestId('close-check').click()
    await expect(accountant.getByTestId('close-notice')).toContainText(`The automatic checks of ${PERIOD} have run`)
  })

  await test.step('the accountant completes the accruals task', async () => {
    // Done by an earlier run of the test, the task offers nothing more.
    const complete = accountant.getByTestId('complete-ACCRUALS')
    if (await complete.isVisible()) {
      await complete.click()
      await accountant.getByTestId('complete-note').fill('Accruals agreed to the schedule')
      await accountant.getByTestId('complete-submit').click()
      await expect(accountant.getByTestId('close-notice')).toContainText('ACCRUALS is done')
    }
    const accruals = accountant.getByTestId('task-table').getByRole('row').filter({ hasText: 'ACCRUALS' })
    await expect(accruals).toContainText('Done')
  })

  await test.step('the overview shows the progress, the open tasks first', async () => {
    await expect(accountant.getByTestId('close-progress')).toContainText(/\d+ of \d+ done/)
    const first = accountant.getByTestId('task-table').getByRole('row').nth(1)
    await expect(first).toContainText(/Open|Failed/)
    await expect(accountant.getByTestId('task-table')).toContainText('REVIEW')
    await expect(accountant.getByTestId('subledger-table')).toContainText('General ledger')
  })
  await accountant.close()

  const controller = await browser.newPage()
  await signIn(controller, CONTROLLER)

  await test.step('the controller\'s close is refused and names what is not done', async () => {
    await openWorkspace(controller)
    await choosePeriod(controller, PERIOD)
    await controller.getByTestId('close-close').click()
    await expect(controller.getByTestId('close-error')).toContainText('REVIEW')
    await expect(controller.getByTestId('close-error')).toContainText('BANK_RECONCILED')
    await expect(controller.getByTestId('period-status')).not.toHaveText('Closed')
  })

  await test.step('the year close is refused while the months are open', async () => {
    await choosePeriod(controller, '2028-13')
    await controller.getByTestId('close-year').click()
    await expect(controller.getByTestId('close-error')).toContainText('2028-01')
    await expect(controller.getByTestId('close-error')).toContainText('are not closed')
    await expect(controller.getByTestId('period-status')).toHaveText('Open')
  })
  await controller.close()
})
