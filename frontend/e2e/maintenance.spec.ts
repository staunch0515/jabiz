import { expect } from '@playwright/test'
import { expectAccessible } from '@jabiz/ui/testing/playwright'
import { CARRIERS, datasetPath, pageRendered, signIn, test } from './support'

/**
 * ROADMAP 17x: a page already open while the service is maintained or restarted. The reverse proxy then answers every
 * call with its own page (503, HTML, Retry-After); the page says so once, in front of it, not an error in every part,
 * and carries on by itself once a check finds the service back.
 */
test('a page open during maintenance says so once and carries on when the service is back', async ({ page }) => {
  await signIn(page)
  await page.goto(datasetPath(CARRIERS))
  await pageRendered(page)

  // Maintenance begins: every call gets the proxy's page.
  await page.route('**/api/**', (route) => route.fulfill({ status: 503, contentType: 'text/html',
    headers: { 'Retry-After': '300', 'Cache-Control': 'no-store' },
    body: '<!doctype html><title>Maintenance</title><h1>Maintenance</h1>' }))
  // A new search: a call the page has not cached.
  await page.getByLabel('代码').first().fill('MAINT')
  await page.getByRole('button', { name: '查询', exact: true }).click()
  const notice = page.getByRole('alertdialog', { name: '系统维护中' })
  await expect(notice).toBeVisible()
  await expect(page.getByRole('alertdialog')).toHaveCount(1)
  await expectAccessible(page, 'service unavailable', { expect })

  // Back: a check now closes the notice, and the list is read again.
  await page.unroute('**/api/**')
  await notice.getByTestId('service-unavailable-retry').click()
  await expect(notice).toHaveCount(0)
  // What failed meanwhile is read again: the search's answer (no carrier has that code) replaces the old rows.
  await expect(page.getByText('暂无数据')).toBeVisible()
})
