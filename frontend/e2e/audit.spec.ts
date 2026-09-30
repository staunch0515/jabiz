import { expect } from '@playwright/test'
import { adminToken, CARRIERS, insert, signIn, test, unique, update } from './support'

/**
 * ROADMAP phase 14f-1: a change to a record shows on the audit page with its values before and after, reached from
 * the entry's history (docs/design/21-audit-retention.md section 1.4).
 */
test.describe('audit', () => {
  test('a change shows its values before and after', async ({ page, request }) => {
    const token = await adminToken(request)
    const code = unique('AU')
    const v1 = await insert(request, token, CARRIERS, {
      carrierCode: code,
      carrierName: 'Audit One',
      countryCode: 'JP',
      creditLimit: 1000,
      active: true,
    })
    await update(request, token, CARRIERS, v1.id, v1.version, { carrierName: 'Audit Two' })

    await signIn(page)
    await page.goto(`/data/${encodeURIComponent(CARRIERS)}/${encodeURIComponent(v1.id)}/history`)
    await page.getByTestId('history-audit').click()
    await expect(page).toHaveURL(/\/audit\?entityType=.+&entityId=/)

    const records = page.getByTestId('audit-records')
    await expect(records.locator('tbody tr.ant-table-row')).toHaveCount(2)
    const update1 = records.locator('tbody tr.ant-table-row').first()
    await expect(update1).toContainText('UPDATE')
    await expect(update1).toContainText('carrierName')
    await update1.locator('.ant-table-row-expand-icon').click()
    const changes = page.locator('[data-testid^="audit-changes-"]')
    await expect(changes).toContainText('Audit One')
    await expect(changes).toContainText('Audit Two')
  })
})
