import { expect, test } from '@playwright/test'
import { adminToken, CARRIERS, insert, PRICES, signIn, unique, update } from './support'

function historyPath(dataset: string, id: string): string {
  return `/data/${encodeURIComponent(dataset)}/${encodeURIComponent(id)}/history`
}

/**
 * Acceptance criterion 3 of ROADMAP phase 10: any temporal entity shows its history and its state at any point in
 * time; operations can be inspected and reverted from the timeline.
 */
test.describe('history', () => {
  test('timeline, point in time, operation details and revert', async ({ page, request }) => {
    const token = await adminToken(request)
    const code = unique('H')
    const v1 = await insert(request, token, CARRIERS, {
      carrierCode: code,
      carrierName: 'Hotel One',
      countryCode: 'JP',
      creditLimit: 1000,
      active: true,
    })
    const v2 = await update(request, token, CARRIERS, v1.id, v1.version, { carrierName: 'Hotel Two' })
    const v3 = await update(request, token, CARRIERS, v2.id, v2.version, { creditLimit: 2000 })
    // A change scheduled for tomorrow.
    const tomorrow = new Date(Date.now() + 24 * 3600 * 1000).toISOString()
    await update(request, token, CARRIERS, v3.id, v3.version, { carrierName: 'Hotel Tomorrow' }, tomorrow)

    await signIn(page)
    await page.goto(historyPath(CARRIERS, v1.id))
    const timeline = page.getByTestId('history-timeline')
    await expect(timeline.locator('[data-testid^="version-"]')).toHaveCount(4)
    await expect(page.getByTestId('version-4')).toContainText('预定')
    await expect(page.getByTestId('version-2')).toContainText('Hotel One')
    await expect(page.getByTestId('version-2')).toContainText('Hotel Two')

    // The state as of version 1: the first name and limit, both differing from now.
    await page.getByTestId('version-1').getByTestId('view-at').click()
    const point = page.getByTestId('point-in-time')
    await expect(point.locator('[data-field="carrierName"]')).toHaveText('Hotel One')
    await expect(point.locator('[data-field="creditLimit"]')).toContainText('1,000')
    await expect(point).toContainText('与当前不同')

    // As of version 3, and a moment before the entry existed.
    await page.getByTestId('version-3').getByTestId('view-at').click()
    await expect(point.locator('[data-field="carrierName"]')).toHaveText('Hotel Two')
    await expect(point.locator('[data-field="creditLimit"]')).toContainText('2,000')
    const before = new Date(Date.parse(v1.attributes.effectStartTime as string) - 60_000).toISOString()
    await page.goto(`${historyPath(CARRIERS, v1.id)}?asOf=${encodeURIComponent(before)}`)
    await expect(page.getByTestId('point-not-existing')).toBeVisible()

    // The scheduled version is what the entry will be tomorrow.
    await page.goto(`${historyPath(CARRIERS, v1.id)}?asOf=${encodeURIComponent(new Date(Date.now() + 2 * 24 * 3600 * 1000).toISOString())}`)
    await expect(page.getByTestId('point-in-time').locator('[data-field="carrierName"]')).toHaveText('Hotel Tomorrow')

    // Operation details of version 3.
    await page.getByTestId('version-3').getByRole('button', { name: /操作详情/ }).click()
    await expect(page.getByTestId('operation-detail')).toContainText('jabiz.dataset.commit')
    await page.locator('.ant-drawer-close').click()

    // Revert the limit change (version 3): it is restored, and the revert appears as a new version.
    await page.getByTestId('version-3').getByTestId('revert').click()
    await page.getByTestId('revert-reason').fill('e2e: undo the limit change')
    await page.locator('#revert-confirm').click()
    await expect(timeline.locator('[data-testid^="version-"]')).toHaveCount(6)
    await expect(page.getByTestId('version-5')).toHaveAttribute('data-action', 'REVERT')
    await expect(page.getByTestId('version-6')).toHaveAttribute('data-action', 'REBASE')

    // The list shows the current state (the scheduled name is not in effect yet).
    await page.goto(`/data/${encodeURIComponent(CARRIERS)}`)
    await page.getByLabel('代码').first().fill(code)
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const row = page.locator('.ant-table-tbody tr.ant-table-row').first()
    await expect(row).toContainText('Hotel Two')
    await expect(row).toContainText('1,000')
  })

  test('the list reads the data as of a point in time', async ({ page, request }) => {
    const token = await adminToken(request)
    const code = unique('T')
    const v1 = await insert(request, token, CARRIERS, {
      carrierCode: code,
      carrierName: 'Tango Before',
      countryCode: 'CN',
      creditLimit: 10,
      active: true,
    })
    await update(request, token, CARRIERS, v1.id, v1.version, { carrierName: 'Tango After' })
    const asOf = v1.attributes.effectStartTime as string

    await signIn(page)
    await page.goto(`/data/${encodeURIComponent(CARRIERS)}?asOf=${encodeURIComponent(asOf)}`)
    await page.getByLabel('代码').first().fill(code)
    await page.getByRole('button', { name: /查\s*询/ }).click()
    const row = page.locator('.ant-table-tbody tr.ant-table-row').first()
    await expect(row).toContainText('Tango Before')
    // Only the current data can be edited.
    await expect(page.getByTestId('create')).toHaveCount(0)
    await expect(row.getByTestId('row-edit')).toHaveCount(0)
  })

  test('another temporal entity has the same history page', async ({ page, request }) => {
    const token = await adminToken(request)
    const sku = unique('SKU-')
    const v1 = await insert(request, token, PRICES, { sku, amount: 100 })
    await update(request, token, PRICES, v1.id, v1.version, { amount: 120 })

    await signIn(page)
    await page.goto(`/data/${encodeURIComponent(PRICES)}`)
    await page.getByLabel('SKU').first().fill(sku)
    await page.getByRole('button', { name: /查\s*询/ }).click()
    await page.locator('.ant-table-tbody tr.ant-table-row').first().getByTestId('row-history').click()
    await expect(page.getByTestId('history-timeline').locator('[data-testid^="version-"]')).toHaveCount(2)
    await page.getByTestId('version-1').getByTestId('view-at').click()
    await expect(page.getByTestId('point-in-time').locator('[data-field="amount"]')).toContainText('100')
  })
})
